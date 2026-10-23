package com.cryptomesh.frontend.protocol.dop

import com.cryptomesh.frontend.crypto.AesGcmSessionCipher
import com.cryptomesh.frontend.crypto.DopKeyWrappingService
import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.data.local.DopReplayLogEntity
import com.cryptomesh.frontend.data.local.DopSessionEntity
import com.cryptomesh.frontend.data.repository.DopPreKeyRepository
import com.cryptomesh.frontend.data.repository.ResolvedLocalPreKeyResult
import com.cryptomesh.frontend.logging.MeshLog
import com.cryptomesh.frontend.protocol.AuthenticatedSession
import com.cryptomesh.frontend.protocol.deviceIdForPublicKey
import com.cryptomesh.frontend.relay.DopRelayCacheManager
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DopInitiationBundleResult(
    val sessionId: String,
    val session: AuthenticatedSession,
    val transcriptHashHex: String,
    val wirePayload: DopInitiationWirePayload,
    val encodedWireBytes: ByteArray,
    val usedSignedPreKeyId: String,
    val usedOneTimePreKeyId: String?
)

enum class DopResponderFailureReason {
    UNSUPPORTED_PROTOCOL,
    RECIPIENT_MISMATCH,
    SELF_INITIATION_REJECTED,
    SENDER_IDENTITY_KEY_MISMATCH,
    INVALID_SENDER_SIGNATURE,
    REPLAY_REJECTED,
    UNKNOWN_PREKEY_ID,
    EXPIRED_PREKEY,
    RETIRED_PREKEY,
    MALFORMED_EPHEMERAL_KEY_OR_NONCE,
    TRANSCRIPT_MISMATCH,
    INNER_AEAD_DECRYPTION_FAILED
}

sealed class DopResponderOutcome {
    data class Accepted(
        val sessionId: String,
        val session: AuthenticatedSession,
        val innerPayload: DopInnerPayload,
        val confirmationTagBase64: String,
        val usedOneTimePreKeyId: String?
    ) : DopResponderOutcome()

    /**
     * Returned when the exact same initiation packet is delivered again (e.g. via a second
     * opportunistic relay path or after a lost ACK). The caller MUST NOT insert a duplicate
     * user-visible message, and SHOULD re-send the encrypted acknowledgement using [session]
     * and [confirmationTagBase64].
     */
    data class DuplicateInitiationCoalesced(
        val sessionId: String,
        val session: AuthenticatedSession,
        val confirmationTagBase64: String,
        val originalPacketId: String
    ) : DopResponderOutcome()

    data class Rejected(
        val reason: DopResponderFailureReason,
        val detail: String
    ) : DopResponderOutcome()
}

/**
 * Coordinates DOP-Handshake asynchronous session initiation, recipient completion,
 * replay detection, multi-sender pre-key concurrency, confirmation verification,
 * and persistent session recovery across application restarts.
 */
class DopHandshakeCoordinator(
    private val repository: DopPreKeyRepository,
    private val relayCacheManager: DopRelayCacheManager,
    private val validator: DopPreKeyValidator,
    private val signatureService: PacketSignatureService,
    private val keyWrappingService: DopKeyWrappingService,
    private val metrics: DopMetricsCollector = DopMetricsCollector(),
    private val now: () -> Long = System::currentTimeMillis,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private val responderMutex = Mutex()

    /**
     * Creates an authenticated `DOP-v1` session initiation using a cached [DopPreKeyRecord]
     * for an offline recipient, derives the session keys bound to the full protocol transcript,
     * persists the session state, and seals [innerPlaintextBytes] inside the initiation payload.
     */
    suspend fun createInitiationForOfflineRecipient(
        localIdentity: LocalIdentity,
        recipientDeviceId: String,
        packetId: String,
        innerKind: DopInnerPayloadKind,
        innerPlaintextBytes: ByteArray,
        sentAtEpochMillis: Long = now()
    ): Result<DopInitiationBundleResult> = runCatching {
        val startNanos = System.nanoTime()
        val cachedEntry = relayCacheManager.getVerifiedRecordForRecipient(
            recipientDeviceId = recipientDeviceId,
            localDeviceId = localIdentity.deviceId
        ) ?: error("No valid DOP pre-key record cached for offline peer $recipientDeviceId.")

        val record = cachedEntry.record
        val validation = validator.validate(
            record = record,
            localDeviceId = localIdentity.deviceId,
            evaluationTimeMillis = sentAtEpochMillis
        )
        require(validation is DopValidationResult.Valid) {
            val invalid = validation as DopValidationResult.Invalid
            "Recipient pre-key record failed validation: ${invalid.reason} (${invalid.detail})"
        }

        // Select an unconsumed optional one-time pre-key if available; otherwise use signed epoch pre-key alone
        val selectedOpk = record.oneTimePreKeys.firstOrNull { opk ->
            !cachedEntry.consumedOneTimePreKeyIds.contains(opk.preKeyId)
        }
        if (selectedOpk != null) {
            repository.markSenderOneTimePreKeyConsumed(
                recipientDeviceId = recipientDeviceId,
                oneTimePreKeyId = selectedOpk.preKeyId,
                nowMillis = sentAtEpochMillis
            )
        }

        val senderEphemeralKeyPair = DopSessionKeyDerivation.generateEcKeyPair(secureRandom)
        val senderEphemeralPubBase64 = Base64.getEncoder().encodeToString(
            senderEphemeralKeyPair.public.encoded
        )
        val senderNonce = ByteArray(DopProtocolVersion.SENDER_NONCE_BYTES).also(secureRandom::nextBytes)
        val senderNonceBase64 = Base64.getEncoder().encodeToString(senderNonce)

        val dh1Secret = DopSessionKeyDerivation.computeEcdhSharedSecret(
            localPrivateKey = senderEphemeralKeyPair.private,
            remotePublicKey = validation.signedPreKeyPublicKey
        )
        val dh2Secret = selectedOpk?.let { opk ->
            val opkPub = DopSessionKeyDerivation.decodePublicKey(opk.publicKeyBase64)
            DopSessionKeyDerivation.computeEcdhSharedSecret(
                localPrivateKey = senderEphemeralKeyPair.private,
                remotePublicKey = opkPub
            )
        }

        val senderSigningPubBytes = signatureService.publicKeyBytes
        val senderSigningPubBase64 = Base64.getEncoder().encodeToString(senderSigningPubBytes)
        val senderFingerprintHex = DopPreKeyCodec.identityFingerprintHex(senderSigningPubBytes)

        val transcriptInputs = DopTranscriptInputs(
            senderDeviceId = localIdentity.deviceId,
            senderIdentityFingerprintHex = senderFingerprintHex,
            senderSigningPublicKeyBase64 = senderSigningPubBase64,
            recipientDeviceId = record.recipientDeviceId,
            recipientIdentityFingerprintHex = record.recipientIdentityFingerprintHex,
            recipientSigningPublicKeyBase64 = record.recipientSigningPublicKeyBase64,
            recordId = record.recordId,
            epochId = record.epochId,
            signedPreKeyId = record.signedPreKeyId,
            signedPreKeyPublicKeyBase64 = record.signedPreKeyPublicKeyBase64,
            oneTimePreKeyId = selectedOpk?.preKeyId,
            oneTimePreKeyPublicKeyBase64 = selectedOpk?.publicKeyBase64,
            senderEphemeralPublicKeyBase64 = senderEphemeralPubBase64,
            senderNonceBase64 = senderNonceBase64
        )
        val computedTranscript = DopHandshakeTranscript.compute(transcriptInputs)
        val derived = DopSessionKeyDerivation.deriveKeys(
            dhSignedPreKeySecret = dh1Secret,
            dhOneTimePreKeySecret = dh2Secret,
            senderNonce = senderNonce,
            transcript = computedTranscript
        )
        dh1Secret.fill(0)
        dh2Secret?.fill(0)

        val cipher = AesGcmSessionCipher(derived.sessionKey, secureRandom)
        val innerPayload = DopInnerPayload(
            kind = innerKind,
            payloadBase64 = Base64.getEncoder().encodeToString(innerPlaintextBytes),
            sentAtEpochMillis = sentAtEpochMillis
        )
        val innerPlainBytes = DopHandshakeMessageCodec.encodeInnerPayload(innerPayload)
        val innerAad = DopHandshakeTranscript.buildInnerPayloadAad(
            transcriptHashHex = computedTranscript.transcriptHashHex,
            sessionId = computedTranscript.sessionId,
            packetId = packetId
        )
        val encryptedInnerBytes = cipher.encrypt(innerPlainBytes, innerAad)
        val encryptedInnerBase64 = Base64.getEncoder().encodeToString(encryptedInnerBytes)

        val unsignedWire = UnsignedDopInitiationWirePayload(
            packetId = packetId,
            sessionId = computedTranscript.sessionId,
            senderDeviceId = localIdentity.deviceId,
            senderDisplayName = localIdentity.displayName.take(DopProtocolVersion.MAX_DISPLAY_NAME_LENGTH),
            senderSigningPublicKeyBase64 = senderSigningPubBase64,
            senderIdentityFingerprintHex = senderFingerprintHex,
            recipientDeviceId = record.recipientDeviceId,
            recipientIdentityFingerprintHex = record.recipientIdentityFingerprintHex,
            recordId = record.recordId,
            epochId = record.epochId,
            signedPreKeyId = record.signedPreKeyId,
            oneTimePreKeyId = selectedOpk?.preKeyId,
            senderEphemeralPublicKeyBase64 = senderEphemeralPubBase64,
            senderNonceBase64 = senderNonceBase64,
            transcriptHashHex = computedTranscript.transcriptHashHex,
            innerPayloadKind = innerKind,
            encryptedInnerPayloadBase64 = encryptedInnerBase64,
            createdAtEpochMillis = sentAtEpochMillis
        )
        val senderSignature = signatureService.sign(
            DopHandshakeMessageCodec.encodeCanonicalUnsignedInitiation(unsignedWire)
        )
        val wirePayload = unsignedWire.toSigned(
            Base64.getEncoder().encodeToString(senderSignature)
        )
        val encodedWireBytes = DopHandshakeMessageCodec.encodeInitiationWirePayload(wirePayload)

        persistSessionEntity(
            sessionId = computedTranscript.sessionId,
            peerDeviceId = record.recipientDeviceId,
            peerDisplayName = record.recipientDisplayName,
            peerSigningPublicKeyBase64 = record.recipientSigningPublicKeyBase64,
            peerIdentityFingerprintHex = record.recipientIdentityFingerprintHex,
            role = ROLE_INITIATOR,
            state = STATE_INITIATED_PENDING,
            recordId = record.recordId,
            epochId = record.epochId,
            signedPreKeyId = record.signedPreKeyId,
            oneTimePreKeyId = selectedOpk?.preKeyId,
            transcriptHashHex = computedTranscript.transcriptHashHex,
            sessionKey = derived.sessionKey,
            confirmationKey = derived.confirmationKey,
            initiationPacketId = packetId,
            createdAtEpochMillis = sentAtEpochMillis,
            confirmedAtEpochMillis = null
        )
        derived.wipe()

        val authenticatedSession = AuthenticatedSession(
            peerDeviceId = record.recipientDeviceId,
            peerDisplayName = record.recipientDisplayName,
            peerSigningPublicKey = validation.recipientSigningKeyBytes,
            cipher = cipher
        )

        val elapsedNanos = System.nanoTime() - startNanos
        metrics.recordDopInitiationCreation(
            durationNanos = elapsedNanos,
            wirePacketBytes = encodedWireBytes.size
        )
        MeshLog.auth(
            "DOP: Created offline session initiation sessionId=${computedTranscript.sessionId} " +
                "to ${record.recipientDeviceId} (epoch=${record.epochId}, spk=${record.signedPreKeyId}, " +
                "opk=${selectedOpk?.preKeyId ?: "NONE"})"
        )

        DopInitiationBundleResult(
            sessionId = computedTranscript.sessionId,
            session = authenticatedSession,
            transcriptHashHex = computedTranscript.transcriptHashHex,
            wirePayload = wirePayload,
            encodedWireBytes = encodedWireBytes,
            usedSignedPreKeyId = record.signedPreKeyId,
            usedOneTimePreKeyId = selectedOpk?.preKeyId
        )
    }

    /**
     * Atomically validates and completes an incoming `DopInitiation` packet addressed to
     * [localIdentity], handling replay detection, duplicate delivery coalescing,
     * multi-sender pre-key concurrency, and session persistence.
     */
    suspend fun processIncomingInitiation(
        localIdentity: LocalIdentity,
        packetId: String,
        outerSenderId: String,
        outerReceiverId: String,
        initiationWireBytes: ByteArray,
        hopCount: Int = 1
    ): DopResponderOutcome = responderMutex.withLock {
        val startNanos = System.nanoTime()
        val nowMillis = now()

        val payload = runCatching {
            DopHandshakeMessageCodec.decodeInitiationWirePayload(initiationWireBytes)
        }.getOrElse { error ->
            return@withLock DopResponderOutcome.Rejected(
                DopResponderFailureReason.UNSUPPORTED_PROTOCOL,
                "Failed to decode DopInitiationWirePayload: ${error.message}"
            )
        }

        if (
            payload.protocolId != DopProtocolVersion.PROTOCOL_ID ||
            payload.protocolVersion != DopProtocolVersion.PROTOCOL_VERSION
        ) {
            return@withLock DopResponderOutcome.Rejected(
                DopResponderFailureReason.UNSUPPORTED_PROTOCOL,
                "Unsupported DOP protocol ${payload.protocolId} v${payload.protocolVersion}."
            )
        }

        if (
            payload.recipientDeviceId != localIdentity.deviceId ||
            outerReceiverId != localIdentity.deviceId
        ) {
            return@withLock DopResponderOutcome.Rejected(
                DopResponderFailureReason.RECIPIENT_MISMATCH,
                "DOP initiation recipient '${payload.recipientDeviceId}' does not match local '${localIdentity.deviceId}'."
            )
        }

        if (
            payload.senderDeviceId == localIdentity.deviceId ||
            payload.senderDeviceId != outerSenderId ||
            payload.senderDisplayName.isBlank()
        ) {
            return@withLock DopResponderOutcome.Rejected(
                DopResponderFailureReason.SELF_INITIATION_REJECTED,
                "Invalid or self-targeted DOP initiation sender '${payload.senderDeviceId}'."
            )
        }

        val localSigningPubBytes = signatureService.publicKeyBytes
        val localSigningPubBase64 = Base64.getEncoder().encodeToString(localSigningPubBytes)
        val localFingerprintHex = DopPreKeyCodec.identityFingerprintHex(localSigningPubBytes)
        if (!localFingerprintHex.equals(payload.recipientIdentityFingerprintHex, ignoreCase = true)) {
            return@withLock DopResponderOutcome.Rejected(
                DopResponderFailureReason.RECIPIENT_MISMATCH,
                "Recipient identity fingerprint in initiation does not match local signing key."
            )
        }

        val senderSigningKeyBytes = runCatching {
            Base64.getDecoder().decode(payload.senderSigningPublicKeyBase64)
        }.getOrNull() ?: return@withLock DopResponderOutcome.Rejected(
            DopResponderFailureReason.SENDER_IDENTITY_KEY_MISMATCH,
            "Sender signing public key is not valid Base64."
        )

        if (DopPreKeyValidator.decodeEcPublicKeyBytes(senderSigningKeyBytes) == null) {
            return@withLock DopResponderOutcome.Rejected(
                DopResponderFailureReason.SENDER_IDENTITY_KEY_MISMATCH,
                "Sender signing public key is not a valid P-256 key."
            )
        }

        if (deviceIdForPublicKey(senderSigningKeyBytes) != payload.senderDeviceId) {
            return@withLock DopResponderOutcome.Rejected(
                DopResponderFailureReason.SENDER_IDENTITY_KEY_MISMATCH,
                "Sender device ID '${payload.senderDeviceId}' does not match its signing public key."
            )
        }

        val derivedSenderFingerprint = DopPreKeyCodec.identityFingerprintHex(senderSigningKeyBytes)
        if (!derivedSenderFingerprint.equals(payload.senderIdentityFingerprintHex, ignoreCase = true)) {
            return@withLock DopResponderOutcome.Rejected(
                DopResponderFailureReason.SENDER_IDENTITY_KEY_MISMATCH,
                "Sender identity fingerprint does not match sender signing public key."
            )
        }

        val senderSignatureBytes = runCatching {
            Base64.getDecoder().decode(payload.senderSignatureBase64)
        }.getOrNull() ?: return@withLock DopResponderOutcome.Rejected(
            DopResponderFailureReason.INVALID_SENDER_SIGNATURE,
            "Sender initiation signature is not valid Base64."
        )

        val canonicalUnsigned = DopHandshakeMessageCodec.encodeCanonicalUnsignedInitiation(
            payload.toUnsigned(packetId)
        )
        val sigValid = signatureService.verify(
            data = canonicalUnsigned,
            signature = senderSignatureBytes,
            publicKeyBytes = senderSigningKeyBytes
        )
        if (!sigValid) {
            return@withLock DopResponderOutcome.Rejected(
                DopResponderFailureReason.INVALID_SENDER_SIGNATURE,
                "ECDSA signature verification failed on DopInitiationWirePayload."
            )
        }

        // Check replay / duplicate delivery
        val replayKey = "${payload.sessionId}|${payload.senderDeviceId}|${payload.senderNonceBase64}"
        val existingReplay = repository.getReplayEntry(replayKey)
            ?: repository.getReplayEntryBySessionId(payload.sessionId)
        if (existingReplay != null) {
            if (
                existingReplay.packetId == packetId &&
                existingReplay.transcriptHashHex == payload.transcriptHashHex &&
                existingReplay.senderDeviceId == payload.senderDeviceId
            ) {
                val existingSessionEntity = repository.getSessionById(payload.sessionId)
                if (existingSessionEntity != null) {
                    val restored = unwrapSessionAndConfirmation(existingSessionEntity)
                    if (restored != null) {
                        val (restoredSession, confirmationKey) = restored
                        val confirmTag = DopSessionKeyDerivation.computeConfirmationTagBase64(
                            confirmationKey = confirmationKey,
                            sessionId = payload.sessionId,
                            transcriptHashHex = payload.transcriptHashHex,
                            initiationPacketId = packetId,
                            responderDeviceId = localIdentity.deviceId
                        )
                        confirmationKey.fill(0)
                        metrics.recordReplayInitiationCoalesced()
                        return@withLock DopResponderOutcome.DuplicateInitiationCoalesced(
                            sessionId = payload.sessionId,
                            session = restoredSession,
                            confirmationTagBase64 = confirmTag,
                            originalPacketId = packetId
                        )
                    }
                }
            }
            metrics.recordReplayAttackRejected()
            return@withLock DopResponderOutcome.Rejected(
                DopResponderFailureReason.REPLAY_REJECTED,
                "Replay detected for DOP session '${payload.sessionId}'."
            )
        }

        // Resolve local signed pre-key private material
        val spkResolution = repository.resolveLocalPreKeyPrivate(
            preKeyId = payload.signedPreKeyId,
            evaluationTimeMillis = nowMillis
        )
        val spkAvailable = when (spkResolution) {
            is ResolvedLocalPreKeyResult.Available -> spkResolution
            is ResolvedLocalPreKeyResult.NotFound -> {
                return@withLock DopResponderOutcome.Rejected(
                    DopResponderFailureReason.UNKNOWN_PREKEY_ID,
                    "Unknown signed pre-key '${payload.signedPreKeyId}'."
                )
            }
            is ResolvedLocalPreKeyResult.Expired -> {
                return@withLock DopResponderOutcome.Rejected(
                    DopResponderFailureReason.EXPIRED_PREKEY,
                    "Signed pre-key '${payload.signedPreKeyId}' has expired."
                )
            }
            is ResolvedLocalPreKeyResult.Retired -> {
                return@withLock DopResponderOutcome.Rejected(
                    DopResponderFailureReason.RETIRED_PREKEY,
                    "Signed pre-key '${payload.signedPreKeyId}' has been retired."
                )
            }
        }

        // Resolve optional local one-time pre-key private material
        val opkAvailable = if (payload.oneTimePreKeyId != null) {
            when (val opkRes = repository.resolveLocalPreKeyPrivate(payload.oneTimePreKeyId, nowMillis)) {
                is ResolvedLocalPreKeyResult.Available -> opkRes
                is ResolvedLocalPreKeyResult.NotFound -> {
                    spkAvailable.wipe()
                    return@withLock DopResponderOutcome.Rejected(
                        DopResponderFailureReason.UNKNOWN_PREKEY_ID,
                        "Unknown one-time pre-key '${payload.oneTimePreKeyId}'."
                    )
                }
                is ResolvedLocalPreKeyResult.Expired -> {
                    spkAvailable.wipe()
                    return@withLock DopResponderOutcome.Rejected(
                        DopResponderFailureReason.EXPIRED_PREKEY,
                        "One-time pre-key '${payload.oneTimePreKeyId}' has expired."
                    )
                }
                is ResolvedLocalPreKeyResult.Retired -> {
                    spkAvailable.wipe()
                    return@withLock DopResponderOutcome.Rejected(
                        DopResponderFailureReason.RETIRED_PREKEY,
                        "One-time pre-key '${payload.oneTimePreKeyId}' has been retired."
                    )
                }
            }
        } else {
            null
        }

        try {
            val senderEphemeralPub = DopPreKeyValidator.decodeEcPublicKey(
                payload.senderEphemeralPublicKeyBase64
            ) ?: return@withLock DopResponderOutcome.Rejected(
                DopResponderFailureReason.MALFORMED_EPHEMERAL_KEY_OR_NONCE,
                "Sender ephemeral public key is not a valid P-256 key."
            )

            val senderNonce = runCatching {
                Base64.getDecoder().decode(payload.senderNonceBase64)
            }.getOrNull()
            if (senderNonce == null || senderNonce.size != DopProtocolVersion.SENDER_NONCE_BYTES) {
                return@withLock DopResponderOutcome.Rejected(
                    DopResponderFailureReason.MALFORMED_EPHEMERAL_KEY_OR_NONCE,
                    "Sender nonce is invalid."
                )
            }

            // Reconstruct canonical transcript and verify transcriptHashHex and sessionId binding
            val transcriptInputs = DopTranscriptInputs(
                senderDeviceId = payload.senderDeviceId,
                senderIdentityFingerprintHex = derivedSenderFingerprint,
                senderSigningPublicKeyBase64 = payload.senderSigningPublicKeyBase64,
                recipientDeviceId = localIdentity.deviceId,
                recipientIdentityFingerprintHex = localFingerprintHex,
                recipientSigningPublicKeyBase64 = localSigningPubBase64,
                recordId = payload.recordId,
                epochId = payload.epochId,
                signedPreKeyId = payload.signedPreKeyId,
                signedPreKeyPublicKeyBase64 = spkAvailable.entity.publicKeyBase64,
                oneTimePreKeyId = opkAvailable?.entity?.preKeyId,
                oneTimePreKeyPublicKeyBase64 = opkAvailable?.entity?.publicKeyBase64,
                senderEphemeralPublicKeyBase64 = payload.senderEphemeralPublicKeyBase64,
                senderNonceBase64 = payload.senderNonceBase64
            )
            val computedTranscript = DopHandshakeTranscript.compute(transcriptInputs)
            if (
                computedTranscript.transcriptHashHex != payload.transcriptHashHex ||
                computedTranscript.sessionId != payload.sessionId
            ) {
                return@withLock DopResponderOutcome.Rejected(
                    DopResponderFailureReason.TRANSCRIPT_MISMATCH,
                    "Computed transcript hash or session ID does not match initiation payload."
                )
            }

            val dh1Secret = DopSessionKeyDerivation.computeEcdhSharedSecret(
                localPrivateKey = spkAvailable.privateKey,
                remotePublicKey = senderEphemeralPub
            )
            val dh2Secret = opkAvailable?.let { opk ->
                DopSessionKeyDerivation.computeEcdhSharedSecret(
                    localPrivateKey = opk.privateKey,
                    remotePublicKey = senderEphemeralPub
                )
            }

            val derived = DopSessionKeyDerivation.deriveKeys(
                dhSignedPreKeySecret = dh1Secret,
                dhOneTimePreKeySecret = dh2Secret,
                senderNonce = senderNonce,
                transcript = computedTranscript
            )
            dh1Secret.fill(0)
            dh2Secret?.fill(0)

            val cipher = AesGcmSessionCipher(derived.sessionKey, secureRandom)
            val innerAad = DopHandshakeTranscript.buildInnerPayloadAad(
                transcriptHashHex = computedTranscript.transcriptHashHex,
                sessionId = computedTranscript.sessionId,
                packetId = packetId
            )
            val encryptedInnerBytes = runCatching {
                Base64.getDecoder().decode(payload.encryptedInnerPayloadBase64)
            }.getOrNull() ?: run {
                derived.wipe()
                return@withLock DopResponderOutcome.Rejected(
                    DopResponderFailureReason.INNER_AEAD_DECRYPTION_FAILED,
                    "Encrypted inner payload is not valid Base64."
                )
            }

            val decryptedInnerBytes = runCatching {
                cipher.decrypt(encryptedInnerBytes, innerAad)
            }.getOrElse { error ->
                derived.wipe()
                return@withLock DopResponderOutcome.Rejected(
                    DopResponderFailureReason.INNER_AEAD_DECRYPTION_FAILED,
                    "AES-GCM decryption of inner DOP payload failed: ${error.message}"
                )
            }

            val innerPayload = runCatching {
                DopHandshakeMessageCodec.decodeInnerPayload(decryptedInnerBytes)
            }.getOrElse { error ->
                derived.wipe()
                return@withLock DopResponderOutcome.Rejected(
                    DopResponderFailureReason.INNER_AEAD_DECRYPTION_FAILED,
                    "Decoded inner DOP payload is malformed: ${error.message}"
                )
            }

            // Record concurrency telemetry if this pre-key has already been used by another initiation
            val wasSpkAlreadyUsed = spkAvailable.entity.usageCount > 0
            val wasOpkInGrace = opkAvailable?.entity?.lifecycleState ==
                DopPreKeyLifecycleState.CONSUMED_GRACE.name
            if (wasSpkAlreadyUsed || wasOpkInGrace) {
                metrics.recordConcurrentMultiSenderInitiation(usedOpkGrace = wasOpkInGrace)
            }

            // Atomically record usage on signed pre-key and optional one-time pre-key
            repository.recordLocalPreKeyUsage(payload.signedPreKeyId, nowMillis)
            if (payload.oneTimePreKeyId != null) {
                repository.recordLocalPreKeyUsage(payload.oneTimePreKeyId, nowMillis)
            }

            // Insert replay log entry
            repository.insertReplayEntryIfAbsent(
                DopReplayLogEntity(
                    replayKey = replayKey,
                    sessionId = computedTranscript.sessionId,
                    packetId = packetId,
                    senderDeviceId = payload.senderDeviceId,
                    signedPreKeyId = payload.signedPreKeyId,
                    oneTimePreKeyId = payload.oneTimePreKeyId,
                    transcriptHashHex = computedTranscript.transcriptHashHex,
                    receivedAtEpochMillis = nowMillis,
                    expiresAtEpochMillis = nowMillis + DopProtocolVersion.DEFAULT_RECORD_TTL_MILLIS
                )
            )

            // Persist responder session
            persistSessionEntity(
                sessionId = computedTranscript.sessionId,
                peerDeviceId = payload.senderDeviceId,
                peerDisplayName = payload.senderDisplayName,
                peerSigningPublicKeyBase64 = payload.senderSigningPublicKeyBase64,
                peerIdentityFingerprintHex = derivedSenderFingerprint,
                role = ROLE_RESPONDER,
                state = STATE_CONFIRMED,
                recordId = payload.recordId,
                epochId = payload.epochId,
                signedPreKeyId = payload.signedPreKeyId,
                oneTimePreKeyId = payload.oneTimePreKeyId,
                transcriptHashHex = computedTranscript.transcriptHashHex,
                sessionKey = derived.sessionKey,
                confirmationKey = derived.confirmationKey,
                initiationPacketId = packetId,
                createdAtEpochMillis = nowMillis,
                confirmedAtEpochMillis = nowMillis
            )

            val confirmationTagBase64 = DopSessionKeyDerivation.computeConfirmationTagBase64(
                confirmationKey = derived.confirmationKey,
                sessionId = computedTranscript.sessionId,
                transcriptHashHex = computedTranscript.transcriptHashHex,
                initiationPacketId = packetId,
                responderDeviceId = localIdentity.deviceId
            )
            derived.wipe()

            val session = AuthenticatedSession(
                peerDeviceId = payload.senderDeviceId,
                peerDisplayName = payload.senderDisplayName,
                peerSigningPublicKey = senderSigningKeyBytes,
                cipher = cipher
            )

            val elapsedNanos = System.nanoTime() - startNanos
            metrics.recordDopRecipientCompletion(
                durationNanos = elapsedNanos,
                offlineDelayMillis = (nowMillis - payload.createdAtEpochMillis).coerceAtLeast(0L),
                hopCount = hopCount
            )
            MeshLog.auth(
                "DOP: Completed incoming offline session sessionId=${computedTranscript.sessionId} " +
                    "from ${payload.senderDisplayName} (${payload.senderDeviceId}), " +
                    "opk=${payload.oneTimePreKeyId ?: "NONE"}, concurrentGrace=$wasOpkInGrace"
            )

            return@withLock DopResponderOutcome.Accepted(
                sessionId = computedTranscript.sessionId,
                session = session,
                innerPayload = innerPayload,
                confirmationTagBase64 = confirmationTagBase64,
                usedOneTimePreKeyId = payload.oneTimePreKeyId
            )
        } finally {
            spkAvailable.wipe()
            opkAvailable?.wipe()
        }
    }

    /**
     * Verifies the responder's confirmation tag when an `Acknowledgement` arrives for an
     * initiation packet, and transitions the persisted DOP session state to `CONFIRMED`.
     */
    suspend fun confirmSessionFromAcknowledgement(
        initiationPacketId: String,
        responderDeviceId: String,
        dopSessionId: String?,
        dopConfirmationTagBase64: String?
    ): Boolean {
        val sessionEntity = repository.getSessionByInitiationPacketId(initiationPacketId)
            ?: dopSessionId?.let { repository.getSessionById(it) }
            ?: return false

        if (sessionEntity.peerDeviceId != responderDeviceId) {
            return false
        }

        if (dopConfirmationTagBase64 != null) {
            val restored = unwrapSessionAndConfirmation(sessionEntity) ?: return false
            val (_, confirmationKey) = restored
            val valid = DopSessionKeyDerivation.verifyConfirmationTag(
                confirmationKey = confirmationKey,
                sessionId = sessionEntity.sessionId,
                transcriptHashHex = sessionEntity.transcriptHashHex,
                initiationPacketId = sessionEntity.initiationPacketId,
                responderDeviceId = responderDeviceId,
                expectedTagBase64 = dopConfirmationTagBase64
            )
            confirmationKey.fill(0)
            if (!valid) {
                MeshLog.warn(
                    "DOP",
                    "Confirmation tag verification failed for sessionId=${sessionEntity.sessionId}"
                )
                return false
            }
        }

        repository.confirmSession(sessionEntity.sessionId, now())
        MeshLog.auth("DOP: Session ${sessionEntity.sessionId} with $responderDeviceId confirmed via ACK.")
        return true
    }

    /**
     * Restores all persisted DOP sessions from Room so encrypted communication can continue
     * seamlessly after an application restart without re-running a handshake.
     */
    suspend fun restorePersistedSessions(): List<AuthenticatedSession> {
        val entities = repository.getAllSessions()
            .sortedBy { it.lastUsedAtEpochMillis }
        val restored = mutableListOf<AuthenticatedSession>()
        for (entity in entities) {
            val pair = unwrapSessionAndConfirmation(entity) ?: continue
            val (session, confirmationKey) = pair
            confirmationKey.fill(0)
            restored.add(session)
        }
        return restored
    }

    private suspend fun persistSessionEntity(
        sessionId: String,
        peerDeviceId: String,
        peerDisplayName: String,
        peerSigningPublicKeyBase64: String,
        peerIdentityFingerprintHex: String,
        role: String,
        state: String,
        recordId: String,
        epochId: Long,
        signedPreKeyId: String,
        oneTimePreKeyId: String?,
        transcriptHashHex: String,
        sessionKey: ByteArray,
        confirmationKey: ByteArray,
        initiationPacketId: String,
        createdAtEpochMillis: Long,
        confirmedAtEpochMillis: Long?
    ) {
        val sessionWrapAad = sessionWrapAad(sessionId, peerDeviceId, transcriptHashHex, "SESSION_KEY")
        val confirmWrapAad = sessionWrapAad(sessionId, peerDeviceId, transcriptHashHex, "CONFIRM_KEY")

        val wrappedSessionKeyBase64 = Base64.getEncoder().encodeToString(
            keyWrappingService.wrap(sessionKey, sessionWrapAad)
        )
        val wrappedConfirmKeyBase64 = Base64.getEncoder().encodeToString(
            keyWrappingService.wrap(confirmationKey, confirmWrapAad)
        )

        repository.upsertSession(
            DopSessionEntity(
                sessionId = sessionId,
                peerDeviceId = peerDeviceId,
                peerDisplayName = peerDisplayName,
                peerSigningPublicKeyBase64 = peerSigningPublicKeyBase64,
                peerIdentityFingerprintHex = peerIdentityFingerprintHex,
                role = role,
                state = state,
                recordId = recordId,
                epochId = epochId,
                signedPreKeyId = signedPreKeyId,
                oneTimePreKeyId = oneTimePreKeyId,
                transcriptHashHex = transcriptHashHex,
                wrappedSessionKeyBase64 = wrappedSessionKeyBase64,
                wrappedConfirmationKeyBase64 = wrappedConfirmKeyBase64,
                initiationPacketId = initiationPacketId,
                createdAtEpochMillis = createdAtEpochMillis,
                confirmedAtEpochMillis = confirmedAtEpochMillis,
                lastUsedAtEpochMillis = createdAtEpochMillis
            )
        )
    }

    private fun unwrapSessionAndConfirmation(
        entity: DopSessionEntity
    ): Pair<AuthenticatedSession, ByteArray>? {
        return runCatching {
            val sessionWrapAad = sessionWrapAad(
                entity.sessionId,
                entity.peerDeviceId,
                entity.transcriptHashHex,
                "SESSION_KEY"
            )
            val confirmWrapAad = sessionWrapAad(
                entity.sessionId,
                entity.peerDeviceId,
                entity.transcriptHashHex,
                "CONFIRM_KEY"
            )
            val sessionKey = keyWrappingService.unwrap(
                Base64.getDecoder().decode(entity.wrappedSessionKeyBase64),
                sessionWrapAad
            )
            val confirmationKey = keyWrappingService.unwrap(
                Base64.getDecoder().decode(entity.wrappedConfirmationKeyBase64),
                confirmWrapAad
            )
            val peerSigningPub = Base64.getDecoder().decode(entity.peerSigningPublicKeyBase64)
            val cipher = AesGcmSessionCipher(sessionKey, secureRandom)
            sessionKey.fill(0)
            AuthenticatedSession(
                peerDeviceId = entity.peerDeviceId,
                peerDisplayName = entity.peerDisplayName,
                peerSigningPublicKey = peerSigningPub,
                cipher = cipher
            ) to confirmationKey
        }.getOrNull()
    }

    companion object {
        const val ROLE_INITIATOR = "INITIATOR"
        const val ROLE_RESPONDER = "RESPONDER"
        const val STATE_INITIATED_PENDING = "INITIATED_PENDING_CONFIRMATION"
        const val STATE_CONFIRMED = "CONFIRMED"

        private fun sessionWrapAad(
            sessionId: String,
            peerDeviceId: String,
            transcriptHashHex: String,
            purpose: String
        ): ByteArray {
            return "CryptoMesh-DOP-SessionWrap-v1|$sessionId|$peerDeviceId|$transcriptHashHex|$purpose"
                .encodeToByteArray()
        }
    }
}
