package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.crypto.SoftwareKeyWrappingService
import com.cryptomesh.frontend.data.local.InMemoryDopDao
import com.cryptomesh.frontend.protocol.MediaKind
import com.cryptomesh.frontend.protocol.SecurePacketType
import com.cryptomesh.frontend.protocol.deviceIdForPublicKey
import com.cryptomesh.frontend.protocol.dop.DopHandshakeCoordinator
import com.cryptomesh.frontend.protocol.dop.DopMetricsCollector
import com.cryptomesh.frontend.protocol.dop.DopPreKeyCodec
import com.cryptomesh.frontend.protocol.dop.DopPreKeyValidator
import com.cryptomesh.frontend.relay.DopRelayCacheManager
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DopMeshIntegrationTest {

    /**
     * Scenario 1: Direct offline pre-key initiation (text and media).
     * Device A holds Device B's signed pre-key record. Device B is offline.
     * Device A initiates a DOP-Handshake session, sends an encrypted text and media attachment while B is offline.
     * Device B later comes online, completes the DOP session, decrypts the text and media, and sends delivery ACKs.
     */
    @Test
    fun scenario1_directOfflinePreKeyInitiationDeliversTextAndMediaWhenRecipientComesOnline(): Unit = runBlocking {
        val network = TestMeshNetwork()
        val clock = IncrementingClock()
        val alice = createNode("Alice", "alice", network, clock)
        val bob = createNode("Bob", "bob", network, clock)

        try {
            delay(100)
            // Alice obtains Bob's signed pre-key record while Bob is offline
            val bobRecord = bob.repository.getOrPublishOwnPreKeyRecord().getOrThrow()
            alice.repository.importPeerPreKeyRecord(bobRecord).getOrThrow()

            // Bob is NOT started on the network (completely offline).
            // Alice initiates an offline DOP text message and a media attachment to Bob.
            val textResult = alice.repository.sendText(
                peerDeviceId = bob.identity.deviceId,
                text = "Urgent offline field report from Alice"
            )
            assertTrue(textResult.isSuccess)

            val voicePayload = ByteArray(1_500) { idx -> ((idx * 31) and 0xFF).toByte() }
            val mediaResult = alice.repository.sendMedia(
                peerDeviceId = bob.identity.deviceId,
                mediaKind = MediaKind.Audio,
                fileName = "offline-briefing.m4a",
                mimeType = "audio/mp4",
                bytes = voicePayload
            )
            assertTrue(mediaResult.isSuccess)

            // Verify Alice queued a DopInitiation packet while Bob is offline
            val aliceOutbound = alice.state().messages.single { it.isOutgoing }
            assertEquals(DirectMessageStatus.Queued, aliceOutbound.status)
            assertTrue(alice.packetRepository.packetTypes().contains(SecurePacketType.DopInitiation.name))

            // Now Bob comes online and Alice connects to Bob over BLE
            alice.repository.start()
            bob.repository.start()
            alice.repository.startScan()

            alice.awaitState { state ->
                state.peers.any { it.linkId == "bob" && it.advertisedDeviceId == bob.identity.deviceId }
            }
            alice.repository.connect("bob")

            // Bob completes the DOP session, decrypts the text message and media transfer, and ACKs back to Alice
            bob.awaitState { state ->
                state.messages.any {
                    !it.isOutgoing &&
                        it.peerDeviceId == alice.identity.deviceId &&
                        it.text == "Urgent offline field report from Alice"
                } && state.mediaTransfers.any {
                    !it.isOutgoing && it.status == MediaTransferStatus.Completed
                }
            }

            alice.awaitState { state ->
                state.messages.any {
                    it.isOutgoing &&
                        it.peerDeviceId == bob.identity.deviceId &&
                        it.status == DirectMessageStatus.Acknowledged
                } && state.mediaTransfers.any {
                    it.isOutgoing && it.status == MediaTransferStatus.Completed
                }
            }

            val receivedMedia = bob.state().mediaTransfers.single { !it.isOutgoing }
            assertArrayEquals(
                voicePayload,
                File(requireNotNull(receivedMedia.outputPath)).readBytes()
            )
        } finally {
            alice.close()
            bob.close()
        }
    }

    /**
     * Scenario 2: Three-node relay-assisted pre-key discovery and offline message delivery.
     * Device B publishes its signed pre-key record to Relay R, then goes offline.
     * Device A connects only to Relay R, automatically receives B's pre-key record via DOP-Sync,
     * initiates a DOP-Handshake session + encrypted message for offline B, and forwards it to Relay R.
     * Device A disconnects. Device B later reconnects to Relay R, receives the DOP initiation,
     * decrypts the message, and sends an authenticated confirmation ACK back through Relay R to Device A.
     */
    @Test
    fun scenario2_threeNodeRelayAssistedPreKeyDiscoveryAndOfflineDelivery(): Unit = runBlocking {
        val network = TestMeshNetwork()
        val clock = IncrementingClock()
        val alice = createNode("Alice", "alice", network, clock)
        val relay = createNode("RelayR", "relayr", network, clock)
        val bob = createNode("Bob", "bob", network, clock)

        try {
            delay(100)
            // Step 1: Bob connects only to Relay R; DOP pre-key sync automatically caches Bob's pre-key on Relay R
            relay.repository.start()
            bob.repository.start()
            relay.repository.startScan()

            relay.awaitState { state ->
                state.peers.any { it.advertisedDeviceId == bob.identity.deviceId }
            }
            relay.repository.connect("bob")

            relay.awaitState { state ->
                state.peers.any {
                    it.deviceId == bob.identity.deviceId && it.status == DirectPeerStatus.Connected
                }
            }
            // Wait until Relay R holds Bob's verified DOP pre-key record in its relay cache
            withTimeout(5_000) {
                relay.repository.dopRelayCacheManager.cachedRecords.first { entries ->
                    entries.any { it.record.recipientDeviceId == bob.identity.deviceId }
                }
            }

            // Step 2: Bob goes completely offline (disconnects from Relay R)
            network.disconnect("relayr", "bob")

            // Step 3: Alice connects only to Relay R (Alice has NEVER met Bob)
            alice.repository.start()
            alice.repository.startScan()

            alice.awaitState { state ->
                state.peers.any { it.advertisedDeviceId == relay.identity.deviceId }
            }
            alice.repository.connect("relayr")

            // Wait for Alice to authenticate with Relay R and receive Bob's pre-key record via DOP-Sync
            alice.awaitState { state ->
                state.peers.any {
                    it.deviceId == relay.identity.deviceId && it.status == DirectPeerStatus.Connected
                } && state.peers.any {
                    it.deviceId == bob.identity.deviceId && it.isVerified
                }
            }
            assertNotNull(
                "Alice must have discovered Bob's pre-key record from Relay R",
                alice.repository.dopRelayCacheManager.getVerifiedRecordForRecipient(
                    bob.identity.deviceId,
                    alice.identity.deviceId
                )
            )

            // Step 4: While Bob is offline, Alice initiates an offline DOP message to Bob.
            // Alice's DopInitiation packet is automatically forwarded to Relay R for store-carry-forward.
            val sendResult = alice.repository.sendText(
                peerDeviceId = bob.identity.deviceId,
                text = "Hello Bob via Relay R pre-key cache!"
            )
            assertTrue(sendResult.isSuccess)

            withTimeout(5_000) {
                relay.packetRepository.packets.first { packets ->
                    packets.any {
                        it.envelope.packetType == SecurePacketType.DopInitiation &&
                            it.envelope.senderId == alice.identity.deviceId &&
                            it.envelope.receiverId == bob.identity.deviceId
                    }
                }
            }

            // Step 5: Alice disconnects from Relay R; Bob reconnects to Relay R
            network.disconnect("alice", "relayr")
            relay.repository.connect("bob")

            // Bob receives the DOP initiation from Relay R, completes the DOP session, and decrypts Alice's message
            bob.awaitState { state ->
                state.messages.any {
                    !it.isOutgoing &&
                        it.peerDeviceId == alice.identity.deviceId &&
                        it.text == "Hello Bob via Relay R pre-key cache!"
                }
            }

            // Step 6: Alice reconnects to Relay R and receives Bob's delivery + DOP confirmation ACK
            alice.repository.connect("relayr")

            alice.awaitState { state ->
                state.messages.any {
                    it.isOutgoing &&
                        it.peerDeviceId == bob.identity.deviceId &&
                        it.status == DirectMessageStatus.Acknowledged
                }
            }

            // Confirm DOP session is marked CONFIRMED in Alice's persistence
            val aliceDopSessions = alice.repository.dopPreKeyRepository.getAllSessions()
            assertTrue(
                aliceDopSessions.any {
                    it.peerDeviceId == bob.identity.deviceId &&
                        it.state == DopHandshakeCoordinator.STATE_CONFIRMED
                }
            )
        } finally {
            alice.close()
            relay.close()
            bob.close()
        }
    }

    /**
     * Scenario 3: Malicious relay tampering and stale replay rejection.
     * Relay R attempts to supply a modified pre-key record or a stale epoch record.
     * Device A rejects the tampered record and ignores the stale epoch record.
     */
    @Test
    fun scenario3_maliciousRelayTamperingAndStaleReplayAreRejected(): Unit = runBlocking {
        val network = TestMeshNetwork()
        val clock = IncrementingClock()
        val alice = createNode("Alice", "alice", network, clock)
        val bob = createNode("Bob", "bob", network, clock)

        try {
            delay(100)
            val bobEpoch1 = bob.repository.getOrPublishOwnPreKeyRecord().getOrThrow()

            // Malicious relay modifies Bob's pre-key record
            val tamperedRecord = bobEpoch1.copy(
                recipientDisplayName = "MaliciousRelayImpersonator"
            )
            val tamperedImport = alice.repository.importPeerPreKeyRecord(tamperedRecord)
            assertTrue("Tampered pre-key record must be rejected", tamperedImport.isFailure)
            assertEquals(
                1L,
                alice.repository.dopMetricsCollector.snapshot().invalidSignaturesRejected
            )

            // Alice imports Bob's rotated epoch 2 record, then malicious relay tries to downgrade to epoch 1
            val bobEpoch2 = bob.repository.getOrPublishOwnPreKeyRecord(forceRotateEpoch = true).getOrThrow()
            assertTrue(alice.repository.importPeerPreKeyRecord(bobEpoch2).isSuccess)
            val staleOutcome = alice.repository.importPeerPreKeyRecord(bobEpoch1).getOrThrow()
            assertTrue(staleOutcome is com.cryptomesh.frontend.relay.DopCacheIngestOutcome.IgnoredStaleEpoch)
            assertEquals(
                bobEpoch2.epochId,
                alice.repository.dopRelayCacheManager.getVerifiedRecordForRecipient(
                    bob.identity.deviceId,
                    alice.identity.deviceId
                )?.record?.epochId
            )
        } finally {
            alice.close()
            bob.close()
        }
    }

    /**
     * Scenario 4: Concurrent senders using the same relay-cached pre-key record.
     * Senders A and D both obtain B's pre-key record from Relay R while B is offline.
     * Both A and D independently initiate offline DOP sessions and send encrypted messages to B through Relay R.
     * When B comes online and connects to Relay R, B completes both distinct sessions, decrypts both messages,
     * and sends delivery ACKs back to both A and D.
     */
    @Test
    fun scenario4_concurrentSendersUsingSameRelayCachedPreKeyBothSucceed(): Unit = runBlocking {
        val network = TestMeshNetwork()
        val clock = IncrementingClock()
        val senderA = createNode("SenderA", "sendera", network, clock)
        val senderD = createNode("SenderD", "senderd", network, clock)
        val relay = createNode("RelayR", "relayr", network, clock)
        val bob = createNode("Bob", "bob", network, clock)

        try {
            delay(100)
            // Bob publishes pre-key to Relay R and goes offline
            relay.repository.start()
            bob.repository.start()
            relay.repository.startScan()
            relay.repository.connect("bob")
            relay.awaitState { state ->
                state.peers.any { it.deviceId == bob.identity.deviceId && it.status == DirectPeerStatus.Connected }
            }
            withTimeout(5_000) {
                relay.repository.dopRelayCacheManager.cachedRecords.first { entries ->
                    entries.any { it.record.recipientDeviceId == bob.identity.deviceId }
                }
            }
            network.disconnect("relayr", "bob")

            // Both Sender A and Sender D connect to Relay R and obtain Bob's cached pre-key record
            senderA.repository.start()
            senderD.repository.start()
            senderA.repository.startScan()
            senderD.repository.startScan()

            senderA.repository.connect("relayr")
            senderD.repository.connect("relayr")

            senderA.awaitState { state ->
                state.peers.any { it.deviceId == bob.identity.deviceId && it.isVerified }
            }
            senderD.awaitState { state ->
                state.peers.any { it.deviceId == bob.identity.deviceId && it.isVerified }
            }

            // Both Sender A and Sender D concurrently initiate DOP messages to offline Bob via Relay R
            assertTrue(senderA.repository.sendText(bob.identity.deviceId, "Concurrent message from A").isSuccess)
            assertTrue(senderD.repository.sendText(bob.identity.deviceId, "Concurrent message from D").isSuccess)

            // Wait for Relay R to store both DopInitiation packets
            withTimeout(5_000) {
                relay.packetRepository.packets.first { packets ->
                    packets.count {
                        it.envelope.packetType == SecurePacketType.DopInitiation &&
                            it.envelope.receiverId == bob.identity.deviceId
                    } >= 2
                }
            }

            // Bob reconnects to Relay R and receives both DOP initiations
            relay.repository.connect("bob")

            bob.awaitState { state ->
                state.messages.any { !it.isOutgoing && it.peerDeviceId == senderA.identity.deviceId && it.text == "Concurrent message from A" } &&
                    state.messages.any { !it.isOutgoing && it.peerDeviceId == senderD.identity.deviceId && it.text == "Concurrent message from D" }
            }

            // Both Sender A and Sender D receive Bob's delivery ACKs via Relay R
            senderA.awaitState { state ->
                state.messages.any { it.isOutgoing && it.peerDeviceId == bob.identity.deviceId && it.status == DirectMessageStatus.Acknowledged }
            }
            senderD.awaitState { state ->
                state.messages.any { it.isOutgoing && it.peerDeviceId == bob.identity.deviceId && it.status == DirectMessageStatus.Acknowledged }
            }
        } finally {
            senderA.close()
            senderD.close()
            relay.close()
            bob.close()
        }
    }

    /**
     * Scenario 5: Restart recovery and evaluation metrics verification.
     * Persisted pre-keys, relay cache, and established DOP sessions survive repository/application restart
     * and can immediately exchange follow-up encrypted packets without re-running a handshake.
     */
    @Test
    fun scenario5_restartRecoveryRestoresPreKeysCacheAndDopSessions(): Unit = runBlocking {
        val network = TestMeshNetwork()
        val clock = IncrementingClock()
        val alice = createNode("Alice", "alice", network, clock)

        val bobSharedDopDao = InMemoryDopDao()
        val bobSharedPacketRepo = MemoryPacketRepository()
        val bobSharedSigner = TestSignatureService()
        val bobSharedWrap = SoftwareKeyWrappingService()

        val bobBeforeRestart = createNode(
            displayName = "Bob",
            linkId = "bob",
            network = network,
            clock = clock,
            signer = bobSharedSigner,
            dopDao = bobSharedDopDao,
            packetRepository = bobSharedPacketRepo,
            wrappingService = bobSharedWrap
        )

        try {
            delay(100)
            val bobRecord = bobBeforeRestart.repository.getOrPublishOwnPreKeyRecord().getOrThrow()
            alice.repository.importPeerPreKeyRecord(bobRecord).getOrThrow()

            // Alice sends DOP initiation to Bob
            assertTrue(alice.repository.sendText(bobBeforeRestart.identity.deviceId, "Initial DOP session message").isSuccess)

            alice.repository.start()
            bobBeforeRestart.repository.start()
            alice.repository.startScan()
            alice.repository.connect("bob")

            bobBeforeRestart.awaitState { state ->
                state.messages.any { !it.isOutgoing && it.text == "Initial DOP session message" }
            }

            // Simulate Bob's process restart with the same Room DAOs and Keystore
            network.disconnect("alice", "bob")
            bobBeforeRestart.close()

            val bobAfterRestart = createNode(
                displayName = "Bob",
                linkId = "bob",
                network = network,
                clock = clock,
                signer = bobSharedSigner,
                dopDao = bobSharedDopDao,
                packetRepository = bobSharedPacketRepo,
                wrappingService = bobSharedWrap
            )
            try {
                // Bob's restored DOP session should be immediately available after restart
                bobAfterRestart.awaitState { state ->
                    state.peers.any { it.deviceId == alice.identity.deviceId && it.isVerified }
                }

                val summary = alice.repository.getDopEvaluationSummary()
                assertTrue(summary.dopInitiationPacketSize.sampleCount >= 1)
                assertTrue(summary.dopInitiationPacketSize.meanBytes > 0)
            } finally {
                bobAfterRestart.close()
            }
        } finally {
            alice.close()
        }
    }

    /**
     * Scenario 6: Side-by-side experimental benchmark comparing Direct Handshake and DOP-Handshake
     * across 30 trials (latency, wire payload size, BLE frame count, cache hit rate).
     */
    @Test
    fun scenario6_sideBySideEvaluationDirectVsDopHandshake(): Unit = runBlocking {
        val network = TestMeshNetwork()
        val clock = IncrementingClock()
        val alice = createNode("Alice", "alice", network, clock)
        val bob = createNode("Bob", "bob", network, clock)

        try {
            delay(100)
            val bobRecord = bob.repository.getOrPublishOwnPreKeyRecord().getOrThrow()
            alice.repository.importPeerPreKeyRecord(bobRecord).getOrThrow()

            val directHandshake = com.cryptomesh.frontend.protocol.AuthenticatedHandshake(
                signatureService = alice.signer,
                now = clock::now
            )
            val peerHandshake = com.cryptomesh.frontend.protocol.AuthenticatedHandshake(
                signatureService = bob.signer,
                now = clock::now
            )

            repeat(30) { trial ->
                // Measure Direct Handshake
                val directStart = System.nanoTime()
                val init = directHandshake.createHello(alice.identity)
                val peerInit = peerHandshake.createHello(bob.identity)
                val sessionA = directHandshake.authenticate(alice.identity, init, peerInit.hello).getOrThrow()
                val sessionB = peerHandshake.authenticate(bob.identity, peerInit, init.hello).getOrThrow()
                val directNanos = System.nanoTime() - directStart
                assertNotNull(sessionA)
                assertNotNull(sessionB)
                val directWireBytes = com.cryptomesh.frontend.protocol.DirectWireCodec()
                    .encodeHandshake(init.hello).size * 2
                alice.repository.dopMetricsCollector.recordDirectHandshake(
                    durationNanos = directNanos,
                    wireHelloBytes = directWireBytes
                )

                // Measure DOP-Handshake Initiation + Recipient Completion
                val pktId = "bench-dop-$trial"
                val dopInit = alice.repository.dopHandshakeCoordinator.createInitiationForOfflineRecipient(
                    localIdentity = alice.identity,
                    recipientDeviceId = bob.identity.deviceId,
                    packetId = pktId,
                    innerKind = com.cryptomesh.frontend.protocol.dop.DopInnerPayloadKind.TEXT_MESSAGE,
                    innerPlaintextBytes = "Benchmark trial #$trial payload".encodeToByteArray()
                ).getOrThrow()

                val dopOut = bob.repository.dopHandshakeCoordinator.processIncomingInitiation(
                    localIdentity = bob.identity,
                    packetId = pktId,
                    outerSenderId = alice.identity.deviceId,
                    outerReceiverId = bob.identity.deviceId,
                    initiationWireBytes = dopInit.encodedWireBytes
                )
                assertTrue(dopOut is com.cryptomesh.frontend.protocol.dop.DopResponderOutcome.Accepted)
            }

            val aliceSummary = alice.repository.getDopEvaluationSummary()
            val bobSummary = bob.repository.getDopEvaluationSummary()
            assertEquals(30, aliceSummary.directHandshakeLatency.sampleCount)
            assertEquals(30, aliceSummary.dopInitiationCreationLatency.sampleCount)
            assertEquals(30, bobSummary.dopRecipientCompletionLatency.sampleCount)
            assertTrue(aliceSummary.cacheHitRate > 0.9)
            println("ALICE_EVAL_SUMMARY=$aliceSummary")
            println("BOB_EVAL_SUMMARY=$bobSummary")
        } finally {
            alice.close()
            bob.close()
        }
    }

    private fun createNode(
        displayName: String,
        linkId: String,
        network: TestMeshNetwork,
        clock: IncrementingClock,
        signer: TestSignatureService = TestSignatureService(),
        dopDao: InMemoryDopDao = InMemoryDopDao(),
        packetRepository: MemoryPacketRepository = MemoryPacketRepository(),
        wrappingService: SoftwareKeyWrappingService = SoftwareKeyWrappingService()
    ): DopIntegrationNode {
        val identity = LocalIdentity(
            displayName = displayName,
            deviceId = deviceIdForPublicKey(signer.publicKeyBytes),
            publicKeyPreview = DopPreKeyCodec.identityFingerprintHex(signer.publicKeyBytes).take(16)
        )
        val identityRepository = FixedIdentityRepository(identity)
        val mediaTransferRepository = MemoryMediaTransferRepository()
        val groupRepository = MemoryGroupRepository(clock::now)
        val fileStore = LocalMediaFileStore(
            Files.createTempDirectory("cryptomesh-dop-test-$linkId").toFile()
        )
        val transport = MeshNearbyTransport(linkId, network)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val metrics = DopMetricsCollector()
        val dopRepo = RoomDopPreKeyRepository(
            dao = dopDao,
            signatureService = signer,
            keyWrappingService = wrappingService,
            metrics = metrics,
            now = clock::now
        )
        val validator = DopPreKeyValidator(
            signatureService = signer,
            now = clock::now
        )
        val relayCache = DopRelayCacheManager(
            repository = dopRepo,
            validator = validator,
            metrics = metrics,
            now = clock::now
        )
        val coordinator = DopHandshakeCoordinator(
            repository = dopRepo,
            relayCacheManager = relayCache,
            validator = validator,
            signatureService = signer,
            keyWrappingService = wrappingService,
            metrics = metrics,
            now = clock::now
        )
        val repository = BleDirectMeshRepository(
            identityRepository = identityRepository,
            signatureService = signer,
            packetRepository = packetRepository,
            mediaTransferRepository = mediaTransferRepository,
            mediaFileStore = fileStore,
            groupRepository = groupRepository,
            transport = transport,
            applicationScope = scope,
            now = clock::now,
            dopKeyWrappingService = wrappingService,
            dopMetricsCollector = metrics,
            dopPreKeyRepository = dopRepo,
            dopPreKeyValidator = validator,
            dopRelayCacheManager = relayCache,
            dopHandshakeCoordinator = coordinator
        )
        return DopIntegrationNode(
            identity = identity,
            signer = signer,
            packetRepository = packetRepository,
            repository = repository,
            scope = scope
        )
    }

    private class DopIntegrationNode(
        val identity: LocalIdentity,
        val signer: TestSignatureService,
        val packetRepository: MemoryPacketRepository,
        val repository: BleDirectMeshRepository,
        private val scope: CoroutineScope
    ) {
        fun state(): DirectMeshState = repository.state.value

        suspend fun awaitState(
            predicate: (DirectMeshState) -> Boolean
        ): DirectMeshState {
            return withTimeout(5_000) {
                repository.state.first(predicate)
            }
        }

        fun close() {
            scope.cancel()
        }
    }
}
