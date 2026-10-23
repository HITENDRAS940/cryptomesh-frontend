package com.cryptomesh.frontend.protocol.dop

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class DopPreKeyType {
    SIGNED_EPOCH_PREKEY,
    ONE_TIME_PREKEY
}

@Serializable
enum class DopPreKeyLifecycleState {
    ACTIVE,
    CONSUMED_GRACE,
    RETIRED,
    EXPIRED
}

@Serializable
data class DopOneTimePreKeyEntry(
    val preKeyId: String,
    val preKeyType: DopPreKeyType = DopPreKeyType.ONE_TIME_PREKEY,
    val publicKeyBase64: String
)

/**
 * Versioned, self-authenticating public pre-key record published by a recipient and
 * cached/forwarded by untrusted opportunistic BLE relays.
 *
 * Contains NO private keys. Authenticity is verified independently of the relay by checking
 * [signatureBase64] against [recipientSigningPublicKeyBase64] and verifying that
 * [recipientDeviceId] and [recipientIdentityFingerprintHex] are derived from that key.
 */
@Serializable
data class DopPreKeyRecord(
    val protocolId: String = DopProtocolVersion.PROTOCOL_ID,
    val protocolVersion: Int = DopProtocolVersion.PROTOCOL_VERSION,
    val recordId: String,
    val recipientDeviceId: String,
    val recipientDisplayName: String,
    val recipientSigningPublicKeyBase64: String,
    val recipientIdentityFingerprintHex: String,
    val epochId: Long,
    val signedPreKeyId: String,
    val signedPreKeyType: DopPreKeyType = DopPreKeyType.SIGNED_EPOCH_PREKEY,
    val signedPreKeyPublicKeyBase64: String,
    val oneTimePreKeys: List<DopOneTimePreKeyEntry> = emptyList(),
    val keyAgreementAlgorithm: String = DopProtocolVersion.KEY_AGREEMENT_ALGORITHM,
    val kdfAlgorithm: String = DopProtocolVersion.KDF_ALGORITHM,
    val aeadAlgorithm: String = DopProtocolVersion.AEAD_ALGORITHM,
    val signatureAlgorithm: String = DopProtocolVersion.SIGNATURE_ALGORITHM,
    val createdAtEpochMillis: Long,
    val validFromEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val signatureBase64: String
) {
    fun toUnsigned(): UnsignedDopPreKeyRecord {
        return UnsignedDopPreKeyRecord(
            protocolId = protocolId,
            protocolVersion = protocolVersion,
            recordId = recordId,
            recipientDeviceId = recipientDeviceId,
            recipientDisplayName = recipientDisplayName,
            recipientSigningPublicKeyBase64 = recipientSigningPublicKeyBase64,
            recipientIdentityFingerprintHex = recipientIdentityFingerprintHex,
            epochId = epochId,
            signedPreKeyId = signedPreKeyId,
            signedPreKeyType = signedPreKeyType,
            signedPreKeyPublicKeyBase64 = signedPreKeyPublicKeyBase64,
            oneTimePreKeys = oneTimePreKeys,
            keyAgreementAlgorithm = keyAgreementAlgorithm,
            kdfAlgorithm = kdfAlgorithm,
            aeadAlgorithm = aeadAlgorithm,
            signatureAlgorithm = signatureAlgorithm,
            createdAtEpochMillis = createdAtEpochMillis,
            validFromEpochMillis = validFromEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis
        )
    }

    fun toCatalogEntry(): DopPreKeyCatalogEntry {
        return DopPreKeyCatalogEntry(
            recipientDeviceId = recipientDeviceId,
            recordId = recordId,
            epochId = epochId,
            recipientIdentityFingerprintHex = recipientIdentityFingerprintHex,
            expiresAtEpochMillis = expiresAtEpochMillis,
            recordDigestHex = DopPreKeyCodec.recordDigestHex(this)
        )
    }
}

@Serializable
data class UnsignedDopPreKeyRecord(
    val protocolId: String = DopProtocolVersion.PROTOCOL_ID,
    val protocolVersion: Int = DopProtocolVersion.PROTOCOL_VERSION,
    val recordId: String,
    val recipientDeviceId: String,
    val recipientDisplayName: String,
    val recipientSigningPublicKeyBase64: String,
    val recipientIdentityFingerprintHex: String,
    val epochId: Long,
    val signedPreKeyId: String,
    val signedPreKeyType: DopPreKeyType = DopPreKeyType.SIGNED_EPOCH_PREKEY,
    val signedPreKeyPublicKeyBase64: String,
    val oneTimePreKeys: List<DopOneTimePreKeyEntry> = emptyList(),
    val keyAgreementAlgorithm: String = DopProtocolVersion.KEY_AGREEMENT_ALGORITHM,
    val kdfAlgorithm: String = DopProtocolVersion.KDF_ALGORITHM,
    val aeadAlgorithm: String = DopProtocolVersion.AEAD_ALGORITHM,
    val signatureAlgorithm: String = DopProtocolVersion.SIGNATURE_ALGORITHM,
    val createdAtEpochMillis: Long,
    val validFromEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    fun toSigned(signatureBase64: String): DopPreKeyRecord {
        return DopPreKeyRecord(
            protocolId = protocolId,
            protocolVersion = protocolVersion,
            recordId = recordId,
            recipientDeviceId = recipientDeviceId,
            recipientDisplayName = recipientDisplayName,
            recipientSigningPublicKeyBase64 = recipientSigningPublicKeyBase64,
            recipientIdentityFingerprintHex = recipientIdentityFingerprintHex,
            epochId = epochId,
            signedPreKeyId = signedPreKeyId,
            signedPreKeyType = signedPreKeyType,
            signedPreKeyPublicKeyBase64 = signedPreKeyPublicKeyBase64,
            oneTimePreKeys = oneTimePreKeys,
            keyAgreementAlgorithm = keyAgreementAlgorithm,
            kdfAlgorithm = kdfAlgorithm,
            aeadAlgorithm = aeadAlgorithm,
            signatureAlgorithm = signatureAlgorithm,
            createdAtEpochMillis = createdAtEpochMillis,
            validFromEpochMillis = validFromEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            signatureBase64 = signatureBase64
        )
    }
}

/**
 * Compact discovery reference for a cached [DopPreKeyRecord]. Fits easily in small sync frames
 * (~160 bytes) so relays and peers can compare epochs and digests before transferring records.
 */
@Serializable
data class DopPreKeyCatalogEntry(
    val recipientDeviceId: String,
    val recordId: String,
    val epochId: Long,
    val recipientIdentityFingerprintHex: String,
    val expiresAtEpochMillis: Long,
    val recordDigestHex: String
)

/**
 * Wire payload exchanged over BLE links to synchronize local and relay-cached pre-key records.
 */
@Serializable
data class DopPreKeySyncMessage(
    val protocolVersion: Int = DopProtocolVersion.PROTOCOL_VERSION,
    val senderDeviceId: String,
    val ownRecord: DopPreKeyRecord? = null,
    val catalogSummaries: List<DopPreKeyCatalogEntry> = emptyList(),
    val requestedRecipientIds: List<String> = emptyList(),
    val relayedRecords: List<DopPreKeyRecord> = emptyList(),
    val sentAtEpochMillis: Long
)

object DopPreKeyCodec {
    private val canonicalJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    private val wireJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeCanonicalUnsigned(unsigned: UnsignedDopPreKeyRecord): ByteArray {
        return canonicalJson.encodeToString(unsigned).encodeToByteArray()
    }

    fun encodeRecord(record: DopPreKeyRecord): String {
        return canonicalJson.encodeToString(record)
    }

    fun decodeRecord(rawJson: String): DopPreKeyRecord {
        return wireJson.decodeFromString(rawJson)
    }

    fun encodeSyncMessage(syncMessage: DopPreKeySyncMessage): String {
        return canonicalJson.encodeToString(syncMessage)
    }

    fun decodeSyncMessage(rawJson: String): DopPreKeySyncMessage {
        return wireJson.decodeFromString(rawJson)
    }

    fun recordDigestHex(record: DopPreKeyRecord): String {
        val bytes = canonicalJson.encodeToString(record).encodeToByteArray()
        return sha256Hex(bytes)
    }

    fun identityFingerprintHex(signingPublicKeyBytes: ByteArray): String {
        return sha256Hex(signingPublicKeyBytes)
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
