package com.cryptomesh.frontend.protocol.dop

/**
 * Protocol constants, algorithm identifiers, and operational bounds for
 * DOP-Handshake (Decentralized Offline Pre-Key Handshake, version 1).
 */
object DopProtocolVersion {
    const val PROTOCOL_ID = "CRYPTO_MESH_DOP"
    const val PROTOCOL_VERSION = 1

    const val KEY_AGREEMENT_ALGORITHM = "ECDH-P256"
    const val KDF_ALGORITHM = "HKDF-SHA256"
    const val AEAD_ALGORITHM = "AES-256-GCM"
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    const val JCA_KEY_ALGORITHM = "EC"
    const val JCA_KEY_AGREEMENT = "ECDH"
    const val JCA_ELLIPTIC_CURVE = "secp256r1"
    const val JCA_SIGNATURE_ALGORITHM = "SHA256withECDSA"

    const val HKDF_SALT_DOMAIN = "CryptoMesh-DOP-v1-Salt"
    const val HKDF_INFO_PREFIX = "CryptoMesh-DOP-v1-Keys|"
    const val TRANSCRIPT_DOMAIN = "CryptoMesh-DOP-v1-Transcript"
    const val INNER_AAD_DOMAIN = "CryptoMesh-DOP-v1-InnerAAD|"
    const val CONFIRMATION_MAC_DOMAIN = "CryptoMesh-DOP-v1-Confirm|"

    const val SENDER_NONCE_BYTES = 32
    const val SESSION_KEY_BYTES = 32
    const val CONFIRMATION_KEY_BYTES = 32
    const val DERIVED_KEY_MATERIAL_BYTES = SESSION_KEY_BYTES + CONFIRMATION_KEY_BYTES

    const val MAX_DISPLAY_NAME_LENGTH = 40
    const val DEFAULT_ONE_TIME_PREKEY_COUNT = 4
    const val MAX_ONE_TIME_PREKEYS_PER_RECORD = 8

    /**
     * Tolerated local clock skew when validating `validFromEpochMillis` (10 minutes).
     */
    const val MAX_CLOCK_SKEW_MILLIS = 10 * 60 * 1_000L

    /**
     * Default lifetime for a published DOP pre-key record (7 days).
     */
    const val DEFAULT_RECORD_TTL_MILLIS = 7 * 24 * 60 * 60 * 1_000L

    /**
     * Grace retention period for a rotated-out signed epoch pre-key so in-flight
     * store-carry-forward initiations referencing the prior epoch can still complete (48 hours).
     */
    const val PREVIOUS_EPOCH_RETENTION_MILLIS = 48 * 60 * 60 * 1_000L

    /**
     * Grace window during which a one-time pre-key in `CONSUMED_GRACE` state remains
     * usable for concurrent offline senders that obtained the same relay-cached bundle (24 hours).
     */
    const val OPK_COALESCING_GRACE_MILLIS = 24 * 60 * 60 * 1_000L

    /**
     * Maximum distinct offline sender initiations allowed against a single one-time pre-key
     * during its coalescing grace window before hard retirement.
     */
    const val MAX_CONCURRENT_OPK_USES = 4

    /**
     * Maximum number of verified peer pre-key records held in the relay cache.
     */
    const val DEFAULT_MAX_RELAY_CACHE_RECORDS = 64

    /**
     * Maximum records transmitted in a single opportunistic BLE pre-key sync message
     * to stay well below the 21,888-byte `BleFrameCodec` limit.
     */
    const val MAX_RECORDS_PER_BLE_SYNC = 6
}

enum class ProtocolMode {
    DIRECT_HANDSHAKE,
    DOP_HANDSHAKE
}
