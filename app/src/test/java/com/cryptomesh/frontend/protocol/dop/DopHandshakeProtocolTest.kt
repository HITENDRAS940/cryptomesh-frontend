package com.cryptomesh.frontend.protocol.dop

import com.cryptomesh.frontend.crypto.SoftwareKeyWrappingService
import com.cryptomesh.frontend.data.local.InMemoryDopDao
import com.cryptomesh.frontend.data.repository.RoomDopPreKeyRepository
import com.cryptomesh.frontend.protocol.SecurePacketCodec
import com.cryptomesh.frontend.protocol.SecurePacketType
import com.cryptomesh.frontend.relay.DopCacheIngestOutcome
import com.cryptomesh.frontend.relay.DopRelayCacheManager
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DopHandshakeProtocolTest {
    private var currentTimeMillis = 1_710_000_000_000L

    private lateinit var aliceSigner: TestEcSignatureService
    private lateinit var aliceIdentity: LocalIdentity
    private lateinit var aliceRepo: RoomDopPreKeyRepository
    private lateinit var aliceCache: DopRelayCacheManager
    private lateinit var aliceCoordinator: DopHandshakeCoordinator

    private lateinit var bobSigner: TestEcSignatureService
    private lateinit var bobIdentity: LocalIdentity
    private lateinit var bobRepo: RoomDopPreKeyRepository
    private lateinit var bobCache: DopRelayCacheManager
    private lateinit var bobCoordinator: DopHandshakeCoordinator

    @Before
    fun setUp() {
        currentTimeMillis = 1_710_000_000_000L

        aliceSigner = TestEcSignatureService()
        val aliceWrap = SoftwareKeyWrappingService()
        val aliceValidator = DopPreKeyValidator(aliceSigner, now = { currentTimeMillis })
        val aliceMetrics = DopMetricsCollector()
        aliceRepo = RoomDopPreKeyRepository(
            dao = InMemoryDopDao(),
            signatureService = aliceSigner,
            keyWrappingService = aliceWrap,
            metrics = aliceMetrics,
            now = { currentTimeMillis }
        )
        aliceCache = DopRelayCacheManager(
            repository = aliceRepo,
            validator = aliceValidator,
            metrics = aliceMetrics,
            now = { currentTimeMillis }
        )
        aliceCoordinator = DopHandshakeCoordinator(
            repository = aliceRepo,
            relayCacheManager = aliceCache,
            validator = aliceValidator,
            signatureService = aliceSigner,
            keyWrappingService = aliceWrap,
            metrics = aliceMetrics,
            now = { currentTimeMillis }
        )
        aliceIdentity = createIdentity("Alice", aliceSigner)

        bobSigner = TestEcSignatureService()
        val bobWrap = SoftwareKeyWrappingService()
        val bobValidator = DopPreKeyValidator(bobSigner, now = { currentTimeMillis })
        val bobMetrics = DopMetricsCollector()
        bobRepo = RoomDopPreKeyRepository(
            dao = InMemoryDopDao(),
            signatureService = bobSigner,
            keyWrappingService = bobWrap,
            metrics = bobMetrics,
            now = { currentTimeMillis }
        )
        bobCache = DopRelayCacheManager(
            repository = bobRepo,
            validator = bobValidator,
            metrics = bobMetrics,
            now = { currentTimeMillis }
        )
        bobCoordinator = DopHandshakeCoordinator(
            repository = bobRepo,
            relayCacheManager = bobCache,
            validator = bobValidator,
            signatureService = bobSigner,
            keyWrappingService = bobWrap,
            metrics = bobMetrics,
            now = { currentTimeMillis }
        )
        bobIdentity = createIdentity("Bob", bobSigner)
    }

    @Test
    fun offlineInitiationAndRecipientCompletionDeriveCompatibleSessionAndVerifyConfirmationTag() = runTest {
        val bobRecord = bobRepo.getOrCreateOwnRecord(bobIdentity)
        val ingestOutcome = aliceCache.ingestRecord(
            record = bobRecord,
            localDeviceId = aliceIdentity.deviceId,
            sourcePeerDeviceId = "relay-node"
        )
        assertTrue(ingestOutcome is DopCacheIngestOutcome.AcceptedNew)

        val packetId = "dop-init-pkt-1"
        val messageText = "Offline hello from Alice to Bob!"
        val initiation = aliceCoordinator.createInitiationForOfflineRecipient(
            localIdentity = aliceIdentity,
            recipientDeviceId = bobIdentity.deviceId,
            packetId = packetId,
            innerKind = DopInnerPayloadKind.TEXT_MESSAGE,
            innerPlaintextBytes = messageText.encodeToByteArray(),
            sentAtEpochMillis = currentTimeMillis
        ).getOrThrow()

        // Bob comes online later and processes the DOP initiation
        currentTimeMillis += 15_000L
        val responderOutcome = bobCoordinator.processIncomingInitiation(
            localIdentity = bobIdentity,
            packetId = packetId,
            outerSenderId = aliceIdentity.deviceId,
            outerReceiverId = bobIdentity.deviceId,
            initiationWireBytes = initiation.encodedWireBytes
        )

        assertTrue(responderOutcome is DopResponderOutcome.Accepted)
        val accepted = responderOutcome as DopResponderOutcome.Accepted
        assertEquals(initiation.sessionId, accepted.sessionId)
        assertEquals(DopInnerPayloadKind.TEXT_MESSAGE, accepted.innerPayload.kind)
        assertEquals(
            messageText,
            Base64.getDecoder().decode(accepted.innerPayload.payloadBase64).decodeToString()
        )

        // Subsequent packets encrypted with Alice's DOP session decrypt with Bob's DOP session
        val aliceCodec = SecurePacketCodec(initiation.session.cipher, aliceSigner, now = { currentTimeMillis })
        val bobCodec = SecurePacketCodec(accepted.session.cipher, bobSigner, now = { currentTimeMillis })
        val followUpEnvelope = aliceCodec.seal(
            packetType = SecurePacketType.Message,
            senderId = aliceIdentity.deviceId,
            receiverId = bobIdentity.deviceId,
            payload = "Follow-up over established DOP session".encodeToByteArray(),
            expiresAtEpochMillis = currentTimeMillis + 60_000L
        )
        val decryptedFollowUp = bobCodec.open(followUpEnvelope, aliceSigner.publicKeyBytes)
            .getOrThrow()
            .decodeToString()
        assertEquals("Follow-up over established DOP session", decryptedFollowUp)

        // Alice verifies Bob's cryptographic confirmation tag from the ACK
        val confirmed = aliceCoordinator.confirmSessionFromAcknowledgement(
            initiationPacketId = packetId,
            responderDeviceId = bobIdentity.deviceId,
            dopSessionId = accepted.sessionId,
            dopConfirmationTagBase64 = accepted.confirmationTagBase64
        )
        assertTrue(confirmed)
    }

    @Test
    fun tamperedEphemeralKeyFailsSignatureValidation() = runTest {
        val bobRecord = bobRepo.getOrCreateOwnRecord(bobIdentity)
        aliceCache.ingestRecord(bobRecord, localDeviceId = aliceIdentity.deviceId)

        val packetId = "dop-init-tamper-eph"
        val initiation = aliceCoordinator.createInitiationForOfflineRecipient(
            localIdentity = aliceIdentity,
            recipientDeviceId = bobIdentity.deviceId,
            packetId = packetId,
            innerKind = DopInnerPayloadKind.TEXT_MESSAGE,
            innerPlaintextBytes = "Secret".encodeToByteArray()
        ).getOrThrow()

        // Replace sender ephemeral key in wire payload without valid signature
        val altEphemeral = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val tamperedWire = initiation.wirePayload.copy(
            senderEphemeralPublicKeyBase64 = Base64.getEncoder().encodeToString(altEphemeral.public.encoded)
        )

        val outcome = bobCoordinator.processIncomingInitiation(
            localIdentity = bobIdentity,
            packetId = packetId,
            outerSenderId = aliceIdentity.deviceId,
            outerReceiverId = bobIdentity.deviceId,
            initiationWireBytes = DopHandshakeMessageCodec.encodeInitiationWirePayload(tamperedWire)
        )
        assertTrue(outcome is DopResponderOutcome.Rejected)
        assertEquals(
            DopResponderFailureReason.INVALID_SENDER_SIGNATURE,
            (outcome as DopResponderOutcome.Rejected).reason
        )
    }

    @Test
    fun tamperedSenderNonceOrTranscriptHashFailsValidation() = runTest {
        val bobRecord = bobRepo.getOrCreateOwnRecord(bobIdentity)
        aliceCache.ingestRecord(bobRecord, localDeviceId = aliceIdentity.deviceId)

        val packetId = "dop-init-tamper-nonce"
        val initiation = aliceCoordinator.createInitiationForOfflineRecipient(
            localIdentity = aliceIdentity,
            recipientDeviceId = bobIdentity.deviceId,
            packetId = packetId,
            innerKind = DopInnerPayloadKind.TEXT_MESSAGE,
            innerPlaintextBytes = "Secret".encodeToByteArray()
        ).getOrThrow()

        // Modify sender nonce and even re-sign with Alice's key, keeping old transcriptHashHex
        val tamperedUnsigned = initiation.wirePayload.toUnsigned(packetId).copy(
            senderNonceBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { 0x77 })
        )
        val reSigned = tamperedUnsigned.toSigned(
            Base64.getEncoder().encodeToString(
                aliceSigner.sign(DopHandshakeMessageCodec.encodeCanonicalUnsignedInitiation(tamperedUnsigned))
            )
        )

        val outcome = bobCoordinator.processIncomingInitiation(
            localIdentity = bobIdentity,
            packetId = packetId,
            outerSenderId = aliceIdentity.deviceId,
            outerReceiverId = bobIdentity.deviceId,
            initiationWireBytes = DopHandshakeMessageCodec.encodeInitiationWirePayload(reSigned)
        )
        assertTrue(outcome is DopResponderOutcome.Rejected)
        assertEquals(
            DopResponderFailureReason.TRANSCRIPT_MISMATCH,
            (outcome as DopResponderOutcome.Rejected).reason
        )
    }

    @Test
    fun duplicateMultiRelayDeliveryIsCoalescedWhileMutatedReplayIsRejected() = runTest {
        val bobRecord = bobRepo.getOrCreateOwnRecord(bobIdentity)
        aliceCache.ingestRecord(bobRecord, localDeviceId = aliceIdentity.deviceId)

        val packetId = "dop-init-once-only"
        val initiation = aliceCoordinator.createInitiationForOfflineRecipient(
            localIdentity = aliceIdentity,
            recipientDeviceId = bobIdentity.deviceId,
            packetId = packetId,
            innerKind = DopInnerPayloadKind.TEXT_MESSAGE,
            innerPlaintextBytes = "Once only".encodeToByteArray()
        ).getOrThrow()

        // First delivery from Relay 1 succeeds
        val firstOutcome = bobCoordinator.processIncomingInitiation(
            localIdentity = bobIdentity,
            packetId = packetId,
            outerSenderId = aliceIdentity.deviceId,
            outerReceiverId = bobIdentity.deviceId,
            initiationWireBytes = initiation.encodedWireBytes
        )
        assertTrue(firstOutcome is DopResponderOutcome.Accepted)
        val firstAccepted = firstOutcome as DopResponderOutcome.Accepted
        assertNotNull(firstAccepted.innerPayload)

        // Identical second delivery from Relay 2 is coalesced into DuplicateInitiationCoalesced
        val secondOutcome = bobCoordinator.processIncomingInitiation(
            localIdentity = bobIdentity,
            packetId = packetId,
            outerSenderId = aliceIdentity.deviceId,
            outerReceiverId = bobIdentity.deviceId,
            initiationWireBytes = initiation.encodedWireBytes
        )
        assertTrue(secondOutcome is DopResponderOutcome.DuplicateInitiationCoalesced)
        val coalesced = secondOutcome as DopResponderOutcome.DuplicateInitiationCoalesced
        assertEquals(firstAccepted.confirmationTagBase64, coalesced.confirmationTagBase64)

        // Replay with different packetId but same sessionId/nonce is rejected as REPLAY_REJECTED
        val reSignedReplayUnsigned = initiation.wirePayload.toUnsigned("different-packet-id")
        val reSignedReplay = reSignedReplayUnsigned.toSigned(
            Base64.getEncoder().encodeToString(
                aliceSigner.sign(DopHandshakeMessageCodec.encodeCanonicalUnsignedInitiation(reSignedReplayUnsigned))
            )
        )
        val replayOutcome = bobCoordinator.processIncomingInitiation(
            localIdentity = bobIdentity,
            packetId = "different-packet-id",
            outerSenderId = aliceIdentity.deviceId,
            outerReceiverId = bobIdentity.deviceId,
            initiationWireBytes = DopHandshakeMessageCodec.encodeInitiationWirePayload(reSignedReplay)
        )
        assertTrue(replayOutcome is DopResponderOutcome.Rejected)
        assertEquals(
            DopResponderFailureReason.REPLAY_REJECTED,
            (replayOutcome as DopResponderOutcome.Rejected).reason
        )
    }

    @Test
    fun expiredOrRetiredPreKeyRejectedByRecipient() = runTest {
        val bobRecord = bobRepo.getOrCreateOwnRecord(bobIdentity)
        aliceCache.ingestRecord(bobRecord, localDeviceId = aliceIdentity.deviceId)

        val packetId = "dop-init-too-late"
        val initiation = aliceCoordinator.createInitiationForOfflineRecipient(
            localIdentity = aliceIdentity,
            recipientDeviceId = bobIdentity.deviceId,
            packetId = packetId,
            innerKind = DopInnerPayloadKind.TEXT_MESSAGE,
            innerPlaintextBytes = "Too late".encodeToByteArray()
        ).getOrThrow()

        // Advance clock past pre-key expiry and prune Bob's state
        currentTimeMillis += DopProtocolVersion.DEFAULT_RECORD_TTL_MILLIS +
            DopProtocolVersion.MAX_CLOCK_SKEW_MILLIS + 60_000L
        bobRepo.pruneExpiredState(currentTimeMillis)

        val outcome = bobCoordinator.processIncomingInitiation(
            localIdentity = bobIdentity,
            packetId = packetId,
            outerSenderId = aliceIdentity.deviceId,
            outerReceiverId = bobIdentity.deviceId,
            initiationWireBytes = initiation.encodedWireBytes
        )
        assertTrue(outcome is DopResponderOutcome.Rejected)
        assertEquals(
            DopResponderFailureReason.EXPIRED_PREKEY,
            (outcome as DopResponderOutcome.Rejected).reason
        )
    }
}
