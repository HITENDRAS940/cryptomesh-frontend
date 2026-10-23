package com.cryptomesh.frontend.protocol.dop

import com.cryptomesh.frontend.crypto.SoftwareKeyWrappingService
import com.cryptomesh.frontend.data.local.CryptoMeshDatabase
import com.cryptomesh.frontend.data.local.InMemoryDopDao
import com.cryptomesh.frontend.data.repository.RoomDopPreKeyRepository
import com.cryptomesh.frontend.protocol.SecurePacketCodec
import com.cryptomesh.frontend.protocol.SecurePacketType
import com.cryptomesh.frontend.relay.DopRelayCacheManager
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.io.File
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DopConcurrencyAndMigrationTest {
    private var currentTimeMillis = 1_710_000_000_000L

    @Before
    fun setUp() {
        currentTimeMillis = 1_710_000_000_000L
    }

    @Test
    fun twoSendersUsingSameCachedPreKeyDeriveDistinctSessionsAndBothCompleteConcurrently() = runTest {
        val bobSigner = TestEcSignatureService()
        val bobIdentity = createIdentity("Bob", bobSigner)
        val bobDao = InMemoryDopDao()
        val bobWrap = SoftwareKeyWrappingService()
        val bobMetrics = DopMetricsCollector()
        val bobValidator = DopPreKeyValidator(bobSigner, now = { currentTimeMillis })
        val bobRepo = RoomDopPreKeyRepository(
            dao = bobDao,
            signatureService = bobSigner,
            keyWrappingService = bobWrap,
            metrics = bobMetrics,
            now = { currentTimeMillis }
        )
        val bobCache = DopRelayCacheManager(bobRepo, bobValidator, bobMetrics, now = { currentTimeMillis })
        val bobCoordinator = DopHandshakeCoordinator(
            repository = bobRepo,
            relayCacheManager = bobCache,
            validator = bobValidator,
            signatureService = bobSigner,
            keyWrappingService = bobWrap,
            metrics = bobMetrics,
            now = { currentTimeMillis }
        )

        val bobRecord = bobRepo.getOrCreateOwnRecord(bobIdentity)

        // Create Sender A and Sender D, both receiving the exact same cached Bob pre-key record from a relay
        val senderA = createSenderStack("SenderA", bobRecord)
        val senderD = createSenderStack("SenderD", bobRecord)

        val initA = senderA.coordinator.createInitiationForOfflineRecipient(
            localIdentity = senderA.identity,
            recipientDeviceId = bobIdentity.deviceId,
            packetId = "pkt-sender-a",
            innerKind = DopInnerPayloadKind.TEXT_MESSAGE,
            innerPlaintextBytes = "From Sender A".encodeToByteArray()
        ).getOrThrow()

        val initD = senderD.coordinator.createInitiationForOfflineRecipient(
            localIdentity = senderD.identity,
            recipientDeviceId = bobIdentity.deviceId,
            packetId = "pkt-sender-d",
            innerKind = DopInnerPayloadKind.TEXT_MESSAGE,
            innerPlaintextBytes = "From Sender D".encodeToByteArray()
        ).getOrThrow()

        // Verify distinct session IDs and distinct transcript hashes even though both used the same cached pre-key record
        assertNotEquals(initA.sessionId, initD.sessionId)
        assertNotEquals(initA.transcriptHashHex, initD.transcriptHashHex)

        // Bob processes both initiations concurrently when coming back online
        val outcomes = listOf(
            async {
                bobCoordinator.processIncomingInitiation(
                    localIdentity = bobIdentity,
                    packetId = "pkt-sender-a",
                    outerSenderId = senderA.identity.deviceId,
                    outerReceiverId = bobIdentity.deviceId,
                    initiationWireBytes = initA.encodedWireBytes
                )
            },
            async {
                bobCoordinator.processIncomingInitiation(
                    localIdentity = bobIdentity,
                    packetId = "pkt-sender-d",
                    outerSenderId = senderD.identity.deviceId,
                    outerReceiverId = bobIdentity.deviceId,
                    initiationWireBytes = initD.encodedWireBytes
                )
            }
        ).awaitAll()

        assertTrue(outcomes.all { it is DopResponderOutcome.Accepted })
        val acceptedA = outcomes[0] as DopResponderOutcome.Accepted
        val acceptedD = outcomes[1] as DopResponderOutcome.Accepted

        assertEquals(initA.sessionId, acceptedA.sessionId)
        assertEquals(initD.sessionId, acceptedD.sessionId)
        assertEquals(
            "From Sender A",
            Base64.getDecoder().decode(acceptedA.innerPayload.payloadBase64).decodeToString()
        )
        assertEquals(
            "From Sender D",
            Base64.getDecoder().decode(acceptedD.innerPayload.payloadBase64).decodeToString()
        )
        assertTrue(bobMetrics.snapshot().concurrentMultiSenderInitiations >= 1L)
    }

    @Test
    fun opkCoalescingWindowAllowsBoundedUncoordinatedSendersThenRetiresAndWipesPrivateKey() = runTest {
        val bobSigner = TestEcSignatureService()
        val bobIdentity = createIdentity("Bob", bobSigner)
        val bobDao = InMemoryDopDao()
        val bobWrap = SoftwareKeyWrappingService()
        val bobMetrics = DopMetricsCollector()
        val bobValidator = DopPreKeyValidator(bobSigner, now = { currentTimeMillis })
        val bobRepo = RoomDopPreKeyRepository(
            dao = bobDao,
            signatureService = bobSigner,
            keyWrappingService = bobWrap,
            metrics = bobMetrics,
            now = { currentTimeMillis }
        )
        val bobCache = DopRelayCacheManager(bobRepo, bobValidator, bobMetrics, now = { currentTimeMillis })
        val bobCoordinator = DopHandshakeCoordinator(
            repository = bobRepo,
            relayCacheManager = bobCache,
            validator = bobValidator,
            signatureService = bobSigner,
            keyWrappingService = bobWrap,
            metrics = bobMetrics,
            now = { currentTimeMillis }
        )

        val fullRecord = bobRepo.getOrCreateOwnRecord(bobIdentity)
        val singleOpk = fullRecord.oneTimePreKeys.first()
        // Restrict the cached record to a single OPK so all senders pick the exact same OPK
        val singleOpkRecord = createSignedSingleOpkRecord(bobSigner, fullRecord, singleOpk)

        // MAX_OPK_COALESCING_USES is 4: first 4 distinct offline senders using the same OPK within grace window succeed
        val senders = (1..5).map { idx -> createSenderStack("ConcurrentSender$idx", singleOpkRecord) }
        val initiations = senders.mapIndexed { idx, stack ->
            val pktId = "pkt-concurrent-${idx + 1}"
            pktId to stack.coordinator.createInitiationForOfflineRecipient(
                localIdentity = stack.identity,
                recipientDeviceId = bobIdentity.deviceId,
                packetId = pktId,
                innerKind = DopInnerPayloadKind.TEXT_MESSAGE,
                innerPlaintextBytes = "Msg-${idx + 1}".encodeToByteArray()
            ).getOrThrow()
        }

        for (i in 0 until DopProtocolVersion.MAX_CONCURRENT_OPK_USES) {
            val (pktId, init) = initiations[i]
            val outcome = bobCoordinator.processIncomingInitiation(
                localIdentity = bobIdentity,
                packetId = pktId,
                outerSenderId = senders[i].identity.deviceId,
                outerReceiverId = bobIdentity.deviceId,
                initiationWireBytes = init.encodedWireBytes
            )
            assertTrue("Expected sender ${i + 1} to succeed within OPK grace limit", outcome is DopResponderOutcome.Accepted)
        }

        // After the 4th use, the OPK is retired and its wrapped private key is wiped from persistence
        val opkEntityAfterGrace = bobDao.getLocalPreKey(singleOpk.preKeyId)
        assertNotNull(opkEntityAfterGrace)
        assertEquals(DopPreKeyLifecycleState.RETIRED.name, opkEntityAfterGrace!!.lifecycleState)
        assertNull("Wrapped private key must be wiped upon retirement", opkEntityAfterGrace.wrappedPrivateKeyBase64)

        // The 5th sender trying to use that retired OPK is deterministically rejected with RETIRED_PREKEY
        val (fifthPktId, fifthInit) = initiations[4]
        val fifthOutcome = bobCoordinator.processIncomingInitiation(
            localIdentity = bobIdentity,
            packetId = fifthPktId,
            outerSenderId = senders[4].identity.deviceId,
            outerReceiverId = bobIdentity.deviceId,
            initiationWireBytes = fifthInit.encodedWireBytes
        )
        assertTrue(fifthOutcome is DopResponderOutcome.Rejected)
        assertEquals(
            DopResponderFailureReason.RETIRED_PREKEY,
            (fifthOutcome as DopResponderOutcome.Rejected).reason
        )
    }

    @Test
    fun crashAndRestartRecoveryRestoresWrappedSessionsAndLocalPreKeys() = runTest {
        // Shared underlying persistence (simulating SQLite DB + Keystore wrapping surviving app restart)
        val sharedDao = InMemoryDopDao()
        val sharedKeyWrap = SoftwareKeyWrappingService()
        val bobSigner = TestEcSignatureService()
        val bobIdentity = createIdentity("Bob", bobSigner)

        val validator1 = DopPreKeyValidator(bobSigner, now = { currentTimeMillis })
        val metrics1 = DopMetricsCollector()
        val repo1 = RoomDopPreKeyRepository(sharedDao, bobSigner, sharedKeyWrap, metrics1, now = { currentTimeMillis })
        val cache1 = DopRelayCacheManager(repo1, validator1, metrics1, now = { currentTimeMillis })
        val coordinator1 = DopHandshakeCoordinator(repo1, cache1, validator1, bobSigner, sharedKeyWrap, metrics1, now = { currentTimeMillis })

        val bobRecord = repo1.getOrCreateOwnRecord(bobIdentity)
        val senderA = createSenderStack("Alice", bobRecord)
        val initiation = senderA.coordinator.createInitiationForOfflineRecipient(
            localIdentity = senderA.identity,
            recipientDeviceId = bobIdentity.deviceId,
            packetId = "pkt-before-restart",
            innerKind = DopInnerPayloadKind.TEXT_MESSAGE,
            innerPlaintextBytes = "Before restart".encodeToByteArray()
        ).getOrThrow()

        val firstOutcome = coordinator1.processIncomingInitiation(
            localIdentity = bobIdentity,
            packetId = "pkt-before-restart",
            outerSenderId = senderA.identity.deviceId,
            outerReceiverId = bobIdentity.deviceId,
            initiationWireBytes = initiation.encodedWireBytes
        )
        assertTrue(firstOutcome is DopResponderOutcome.Accepted)

        // Simulate process death and app restart with fresh repository & coordinator instances over the same DAO + KeyStore
        val validator2 = DopPreKeyValidator(bobSigner, now = { currentTimeMillis })
        val metrics2 = DopMetricsCollector()
        val repo2 = RoomDopPreKeyRepository(sharedDao, bobSigner, sharedKeyWrap, metrics2, now = { currentTimeMillis })
        val cache2 = DopRelayCacheManager(repo2, validator2, metrics2, now = { currentTimeMillis })
        val coordinator2 = DopHandshakeCoordinator(repo2, cache2, validator2, bobSigner, sharedKeyWrap, metrics2, now = { currentTimeMillis })

        val restoredSessions = coordinator2.restorePersistedSessions()
        assertEquals(1, restoredSessions.size)
        val restored = restoredSessions.single()
        assertEquals(senderA.identity.deviceId, restored.peerDeviceId)

        // Verify restored session can decrypt packets sealed by Alice's session
        val aliceCodec = SecurePacketCodec(initiation.session.cipher, senderA.signer, now = { currentTimeMillis })
        val bobRestoredCodec = SecurePacketCodec(restored.cipher, bobSigner, now = { currentTimeMillis })
        val sealedAfterRestart = aliceCodec.seal(
            packetType = SecurePacketType.Message,
            senderId = senderA.identity.deviceId,
            receiverId = bobIdentity.deviceId,
            payload = "Encrypted message decrypted after Bob's restart".encodeToByteArray(),
            expiresAtEpochMillis = currentTimeMillis + 60_000L
        )
        assertEquals(
            "Encrypted message decrypted after Bob's restart",
            bobRestoredCodec.open(sealedAfterRestart, senderA.signer.publicKeyBytes)
                .getOrThrow()
                .decodeToString()
        )
    }

    @Test
    fun roomSchemaVersion4AndMigration3To4AreExportedAndConsistent() {
        assertNotNull(CryptoMeshDatabase.MIGRATION_3_4)
        assertEquals(3, CryptoMeshDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, CryptoMeshDatabase.MIGRATION_3_4.endVersion)

        val schemaFileCandidates = listOf(
            File("schemas/com.cryptomesh.frontend.data.local.CryptoMeshDatabase/4.json"),
            File("app/schemas/com.cryptomesh.frontend.data.local.CryptoMeshDatabase/4.json")
        )
        val schemaFile = schemaFileCandidates.firstOrNull { it.exists() }
        assertNotNull("Expected Room exported schema 4.json to exist", schemaFile)

        val root = Json.parseToJsonElement(schemaFile!!.readText()).jsonObject
        val databaseObj = root.getValue("database").jsonObject
        assertEquals(4, databaseObj.getValue("version").jsonPrimitive.int)

        val entitiesArray = databaseObj.getValue("entities").jsonArray
        val tableNames = entitiesArray.map { element ->
            element.jsonObject.getValue("tableName").jsonPrimitive.content
        }.toSet()

        assertTrue(tableNames.contains("dop_local_prekeys"))
        assertTrue(tableNames.contains("dop_cached_prekey_records"))
        assertTrue(tableNames.contains("dop_sessions"))
        assertTrue(tableNames.contains("dop_replay_log"))
        // Existing tables must remain intact
        assertTrue(tableNames.contains("local_identity"))
        assertTrue(tableNames.contains("secure_packets"))
        assertTrue(tableNames.contains("media_transfers"))
        assertTrue(tableNames.contains("mesh_groups"))
    }

    private data class SenderStack(
        val identity: LocalIdentity,
        val signer: TestEcSignatureService,
        val coordinator: DopHandshakeCoordinator
    )

    private suspend fun createSenderStack(
        name: String,
        cachedRecipientRecord: DopPreKeyRecord
    ): SenderStack {
        val signer = TestEcSignatureService()
        val identity = createIdentity(name, signer)
        val wrap = SoftwareKeyWrappingService()
        val validator = DopPreKeyValidator(signer, now = { currentTimeMillis })
        val metrics = DopMetricsCollector()
        val repo = RoomDopPreKeyRepository(InMemoryDopDao(), signer, wrap, metrics, now = { currentTimeMillis })
        val cache = DopRelayCacheManager(repo, validator, metrics, now = { currentTimeMillis })
        cache.ingestRecord(cachedRecipientRecord, localDeviceId = identity.deviceId, sourcePeerDeviceId = "relay")
        val coordinator = DopHandshakeCoordinator(repo, cache, validator, signer, wrap, metrics, now = { currentTimeMillis })
        return SenderStack(identity, signer, coordinator)
    }

    private fun createSignedSingleOpkRecord(
        ownerSigner: TestEcSignatureService,
        template: DopPreKeyRecord,
        singleOpk: DopOneTimePreKeyEntry
    ): DopPreKeyRecord {
        val unsigned = template.copy(
            oneTimePreKeys = listOf(singleOpk)
        ).toUnsigned()
        val sig = ownerSigner.sign(DopPreKeyCodec.encodeCanonicalUnsigned(unsigned))
        return unsigned.toSigned(Base64.getEncoder().encodeToString(sig))
    }
}
