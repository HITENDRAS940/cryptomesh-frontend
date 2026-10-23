package com.cryptomesh.frontend.protocol.dop

import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.protocol.deviceIdForPublicKey
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

enum class DopValidationFailureReason {
    UNSUPPORTED_PROTOCOL_ID,
    UNSUPPORTED_PROTOCOL_VERSION,
    UNSUPPORTED_ALGORITHM_SUITE,
    MISSING_REQUIRED_FIELD,
    SELF_RECORD_REJECTED,
    DEVICE_ID_KEY_MISMATCH,
    FINGERPRINT_KEY_MISMATCH,
    INVALID_EPOCH_OR_TIMESTAMPS,
    NOT_YET_VALID_CLOCK_SKEW,
    EXPIRED_RECORD,
    MALFORMED_PUBLIC_KEY,
    DUPLICATE_ONE_TIME_PREKEY_ID,
    TOO_MANY_ONE_TIME_PREKEYS,
    INVALID_SIGNATURE
}

sealed class DopValidationResult {
    data class Valid(
        val record: DopPreKeyRecord,
        val recipientSigningKeyBytes: ByteArray,
        val signedPreKeyPublicKey: PublicKey,
        val recordDigestHex: String
    ) : DopValidationResult()

    data class Invalid(
        val reason: DopValidationFailureReason,
        val detail: String
    ) : DopValidationResult()

    val isValid: Boolean
        get() = this is Valid
}

enum class DopRecordResolution {
    ACCEPT_NEW,
    IGNORE_DUPLICATE_IDENTICAL,
    ACCEPT_NEWER_EPOCH,
    IGNORE_STALE_OLDER_EPOCH,
    REJECT_CONFLICTING_RECORD,
    ACCEPT_IDENTITY_KEY_ROTATION,
    REJECT_IDENTITY_KEY_CONFLICT
}

/**
 * Cryptographically and structurally validates [DopPreKeyRecord] instances independently
 * of the untrusted BLE relay that supplied them, and resolves epoch/conflict rules.
 */
class DopPreKeyValidator(
    private val signatureService: PacketSignatureService,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxClockSkewMillis: Long = DopProtocolVersion.MAX_CLOCK_SKEW_MILLIS,
    private val allowExplicitEpochKeyRotation: Boolean = true
) {
    fun validate(
        record: DopPreKeyRecord,
        localDeviceId: String? = null,
        evaluationTimeMillis: Long = now()
    ): DopValidationResult {
        if (record.protocolId != DopProtocolVersion.PROTOCOL_ID) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.UNSUPPORTED_PROTOCOL_ID,
                "Unsupported DOP protocol identifier '${record.protocolId}'."
            )
        }
        if (record.protocolVersion != DopProtocolVersion.PROTOCOL_VERSION) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.UNSUPPORTED_PROTOCOL_VERSION,
                "Unsupported DOP protocol version ${record.protocolVersion}."
            )
        }
        if (
            record.keyAgreementAlgorithm != DopProtocolVersion.KEY_AGREEMENT_ALGORITHM ||
            record.kdfAlgorithm != DopProtocolVersion.KDF_ALGORITHM ||
            record.aeadAlgorithm != DopProtocolVersion.AEAD_ALGORITHM ||
            record.signatureAlgorithm != DopProtocolVersion.SIGNATURE_ALGORITHM ||
            record.signedPreKeyType != DopPreKeyType.SIGNED_EPOCH_PREKEY
        ) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.UNSUPPORTED_ALGORITHM_SUITE,
                "Unsupported cryptographic algorithm suite in DOP pre-key record."
            )
        }
        if (
            record.recordId.isBlank() ||
            record.recipientDeviceId.isBlank() ||
            record.recipientDisplayName.isBlank() ||
            record.recipientSigningPublicKeyBase64.isBlank() ||
            record.recipientIdentityFingerprintHex.isBlank() ||
            record.signedPreKeyId.isBlank() ||
            record.signedPreKeyPublicKeyBase64.isBlank() ||
            record.signatureBase64.isBlank()
        ) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.MISSING_REQUIRED_FIELD,
                "One or more required DOP pre-key record fields are blank."
            )
        }
        if (localDeviceId != null && record.recipientDeviceId == localDeviceId) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.SELF_RECORD_REJECTED,
                "Device cannot import its own pre-key record as a remote peer."
            )
        }
        if (
            record.epochId < 1L ||
            record.expiresAtEpochMillis <= record.validFromEpochMillis ||
            record.createdAtEpochMillis <= 0L
        ) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.INVALID_EPOCH_OR_TIMESTAMPS,
                "Invalid epoch (${record.epochId}) or validity window [${record.validFromEpochMillis}, ${record.expiresAtEpochMillis}]."
            )
        }
        if (evaluationTimeMillis + maxClockSkewMillis < record.validFromEpochMillis) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.NOT_YET_VALID_CLOCK_SKEW,
                "Pre-key record validFrom (${record.validFromEpochMillis}) exceeds local clock + skew tolerance."
            )
        }
        if (evaluationTimeMillis > record.expiresAtEpochMillis) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.EXPIRED_RECORD,
                "Pre-key record expired at ${record.expiresAtEpochMillis} (now=$evaluationTimeMillis)."
            )
        }
        if (record.oneTimePreKeys.size > DopProtocolVersion.MAX_ONE_TIME_PREKEYS_PER_RECORD) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.TOO_MANY_ONE_TIME_PREKEYS,
                "Pre-key record contains ${record.oneTimePreKeys.size} one-time keys (max ${DopProtocolVersion.MAX_ONE_TIME_PREKEYS_PER_RECORD})."
            )
        }
        val opkIds = mutableSetOf<String>()
        for (opk in record.oneTimePreKeys) {
            if (opk.preKeyId.isBlank() || opk.publicKeyBase64.isBlank() || opk.preKeyType != DopPreKeyType.ONE_TIME_PREKEY) {
                return DopValidationResult.Invalid(
                    DopValidationFailureReason.MISSING_REQUIRED_FIELD,
                    "Malformed one-time pre-key entry."
                )
            }
            if (!opkIds.add(opk.preKeyId) || opk.preKeyId == record.signedPreKeyId) {
                return DopValidationResult.Invalid(
                    DopValidationFailureReason.DUPLICATE_ONE_TIME_PREKEY_ID,
                    "Duplicate pre-key identifier '${opk.preKeyId}'."
                )
            }
            if (decodeEcPublicKey(opk.publicKeyBase64) == null) {
                return DopValidationResult.Invalid(
                    DopValidationFailureReason.MALFORMED_PUBLIC_KEY,
                    "One-time pre-key '${opk.preKeyId}' is not a valid P-256 public key."
                )
            }
        }

        val signingKeyBytes = runCatching {
            Base64.getDecoder().decode(record.recipientSigningPublicKeyBase64)
        }.getOrNull() ?: return DopValidationResult.Invalid(
            DopValidationFailureReason.MALFORMED_PUBLIC_KEY,
            "Recipient signing public key is not valid Base64."
        )

        if (decodeEcPublicKeyBytes(signingKeyBytes) == null) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.MALFORMED_PUBLIC_KEY,
                "Recipient signing public key is not a valid P-256 public key."
            )
        }

        val derivedDeviceId = deviceIdForPublicKey(signingKeyBytes)
        if (derivedDeviceId != record.recipientDeviceId) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.DEVICE_ID_KEY_MISMATCH,
                "Recipient device ID '${record.recipientDeviceId}' does not match derived ID '$derivedDeviceId'."
            )
        }

        val derivedFingerprint = DopPreKeyCodec.identityFingerprintHex(signingKeyBytes)
        if (!derivedFingerprint.equals(record.recipientIdentityFingerprintHex, ignoreCase = true)) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.FINGERPRINT_KEY_MISMATCH,
                "Recipient identity fingerprint does not match signing key SHA-256."
            )
        }

        val signedPreKeyPublicKey = decodeEcPublicKey(record.signedPreKeyPublicKeyBase64)
            ?: return DopValidationResult.Invalid(
                DopValidationFailureReason.MALFORMED_PUBLIC_KEY,
                "Signed pre-key '${record.signedPreKeyId}' is not a valid P-256 public key."
            )

        val signatureBytes = runCatching {
            Base64.getDecoder().decode(record.signatureBase64)
        }.getOrNull() ?: return DopValidationResult.Invalid(
            DopValidationFailureReason.INVALID_SIGNATURE,
            "Record signature is not valid Base64."
        )

        val canonicalUnsigned = DopPreKeyCodec.encodeCanonicalUnsigned(record.toUnsigned())
        val isSignatureValid = signatureService.verify(
            data = canonicalUnsigned,
            signature = signatureBytes,
            publicKeyBytes = signingKeyBytes
        )
        if (!isSignatureValid) {
            return DopValidationResult.Invalid(
                DopValidationFailureReason.INVALID_SIGNATURE,
                "ECDSA signature verification failed for pre-key record '${record.recordId}'."
            )
        }

        return DopValidationResult.Valid(
            record = record,
            recipientSigningKeyBytes = signingKeyBytes,
            signedPreKeyPublicKey = signedPreKeyPublicKey,
            recordDigestHex = DopPreKeyCodec.recordDigestHex(record)
        )
    }

    /**
     * Resolves how a cryptographically valid incoming [DopPreKeyRecord] should be handled
     * relative to an existing cached record for the same recipient.
     */
    fun resolveAgainstExisting(
        incoming: DopPreKeyRecord,
        existing: DopPreKeyRecord?
    ): DopRecordResolution {
        if (existing == null) {
            return DopRecordResolution.ACCEPT_NEW
        }
        val incomingDigest = DopPreKeyCodec.recordDigestHex(incoming)
        val existingDigest = DopPreKeyCodec.recordDigestHex(existing)
        if (incomingDigest == existingDigest) {
            return DopRecordResolution.IGNORE_DUPLICATE_IDENTICAL
        }

        val sameIdentityKey = incoming.recipientIdentityFingerprintHex.equals(
            existing.recipientIdentityFingerprintHex,
            ignoreCase = true
        )
        if (!sameIdentityKey) {
            return if (
                allowExplicitEpochKeyRotation &&
                incoming.epochId > existing.epochId &&
                incoming.createdAtEpochMillis > existing.createdAtEpochMillis
            ) {
                DopRecordResolution.ACCEPT_IDENTITY_KEY_ROTATION
            } else {
                DopRecordResolution.REJECT_IDENTITY_KEY_CONFLICT
            }
        }

        if (incoming.recordId == existing.recordId) {
            // Same recordId but different canonical digest -> conflicting record!
            return DopRecordResolution.REJECT_CONFLICTING_RECORD
        }

        return when {
            incoming.epochId > existing.epochId -> DopRecordResolution.ACCEPT_NEWER_EPOCH
            incoming.epochId < existing.epochId -> DopRecordResolution.IGNORE_STALE_OLDER_EPOCH
            else -> DopRecordResolution.REJECT_CONFLICTING_RECORD
        }
    }

    companion object {
        fun decodeEcPublicKey(base64: String): PublicKey? {
            return runCatching {
                val bytes = Base64.getDecoder().decode(base64)
                decodeEcPublicKeyBytes(bytes)
            }.getOrNull()
        }

        fun decodeEcPublicKeyBytes(encodedBytes: ByteArray): PublicKey? {
            return runCatching {
                val pub = KeyFactory.getInstance(DopProtocolVersion.JCA_KEY_ALGORITHM)
                    .generatePublic(X509EncodedKeySpec(encodedBytes))
                if (pub is ECPublicKey && pub.params.curve.field.fieldSize == 256) {
                    pub
                } else {
                    null
                }
            }.getOrNull()
        }
    }
}
