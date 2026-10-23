package com.cryptomesh.frontend.protocol.dop

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class DopInnerPayloadKind {
    TEXT_MESSAGE,
    MEDIA_OFFER,
    MEDIA_ACCEPT,
    MEDIA_REJECT,
    MEDIA_CHUNK,
    MEDIA_FRAGMENT,
    MEDIA_CHUNK_ACK,
    MEDIA_COMPLETE,
    GROUP_INVITE,
    SESSION_BOOTSTRAP
}

/**
 * Plaintext structure sealed inside [DopInitiationWirePayload.encryptedInnerPayloadBase64]
 * using the derived `DOP-v1` AES-256-GCM session key and transcript-bound AAD.
 */
@Serializable
data class DopInnerPayload(
    val kind: DopInnerPayloadKind,
    val payloadBase64: String,
    val sentAtEpochMillis: Long
)

/**
 * Wire payload carried inside `SecurePacketEnvelope.encryptedPayloadBase64` when
 * `packetType == SecurePacketType.DopInitiation`.
 *
 * Intermediate relays only see the outer `SecurePacketEnvelope` and cannot decrypt
 * [encryptedInnerPayloadBase64] because they do not possess the recipient's private pre-keys
 * or the sender's ephemeral private key.
 */
@Serializable
data class DopInitiationWirePayload(
    val protocolId: String = DopProtocolVersion.PROTOCOL_ID,
    val protocolVersion: Int = DopProtocolVersion.PROTOCOL_VERSION,
    val sessionId: String,
    val senderDeviceId: String,
    val senderDisplayName: String,
    val senderSigningPublicKeyBase64: String,
    val senderIdentityFingerprintHex: String,
    val recipientDeviceId: String,
    val recipientIdentityFingerprintHex: String,
    val recordId: String,
    val epochId: Long,
    val signedPreKeyId: String,
    val oneTimePreKeyId: String? = null,
    val senderEphemeralPublicKeyBase64: String,
    val senderNonceBase64: String,
    val transcriptHashHex: String,
    val innerPayloadKind: DopInnerPayloadKind,
    val encryptedInnerPayloadBase64: String,
    val createdAtEpochMillis: Long,
    val senderSignatureBase64: String
) {
    fun toUnsigned(packetId: String): UnsignedDopInitiationWirePayload {
        return UnsignedDopInitiationWirePayload(
            protocolId = protocolId,
            protocolVersion = protocolVersion,
            packetId = packetId,
            sessionId = sessionId,
            senderDeviceId = senderDeviceId,
            senderDisplayName = senderDisplayName,
            senderSigningPublicKeyBase64 = senderSigningPublicKeyBase64,
            senderIdentityFingerprintHex = senderIdentityFingerprintHex,
            recipientDeviceId = recipientDeviceId,
            recipientIdentityFingerprintHex = recipientIdentityFingerprintHex,
            recordId = recordId,
            epochId = epochId,
            signedPreKeyId = signedPreKeyId,
            oneTimePreKeyId = oneTimePreKeyId,
            senderEphemeralPublicKeyBase64 = senderEphemeralPublicKeyBase64,
            senderNonceBase64 = senderNonceBase64,
            transcriptHashHex = transcriptHashHex,
            innerPayloadKind = innerPayloadKind,
            encryptedInnerPayloadBase64 = encryptedInnerPayloadBase64,
            createdAtEpochMillis = createdAtEpochMillis
        )
    }
}

@Serializable
data class UnsignedDopInitiationWirePayload(
    val protocolId: String = DopProtocolVersion.PROTOCOL_ID,
    val protocolVersion: Int = DopProtocolVersion.PROTOCOL_VERSION,
    val packetId: String,
    val sessionId: String,
    val senderDeviceId: String,
    val senderDisplayName: String,
    val senderSigningPublicKeyBase64: String,
    val senderIdentityFingerprintHex: String,
    val recipientDeviceId: String,
    val recipientIdentityFingerprintHex: String,
    val recordId: String,
    val epochId: Long,
    val signedPreKeyId: String,
    val oneTimePreKeyId: String? = null,
    val senderEphemeralPublicKeyBase64: String,
    val senderNonceBase64: String,
    val transcriptHashHex: String,
    val innerPayloadKind: DopInnerPayloadKind,
    val encryptedInnerPayloadBase64: String,
    val createdAtEpochMillis: Long
) {
    fun toSigned(senderSignatureBase64: String): DopInitiationWirePayload {
        return DopInitiationWirePayload(
            protocolId = protocolId,
            protocolVersion = protocolVersion,
            sessionId = sessionId,
            senderDeviceId = senderDeviceId,
            senderDisplayName = senderDisplayName,
            senderSigningPublicKeyBase64 = senderSigningPublicKeyBase64,
            senderIdentityFingerprintHex = senderIdentityFingerprintHex,
            recipientDeviceId = recipientDeviceId,
            recipientIdentityFingerprintHex = recipientIdentityFingerprintHex,
            recordId = recordId,
            epochId = epochId,
            signedPreKeyId = signedPreKeyId,
            oneTimePreKeyId = oneTimePreKeyId,
            senderEphemeralPublicKeyBase64 = senderEphemeralPublicKeyBase64,
            senderNonceBase64 = senderNonceBase64,
            transcriptHashHex = transcriptHashHex,
            innerPayloadKind = innerPayloadKind,
            encryptedInnerPayloadBase64 = encryptedInnerPayloadBase64,
            createdAtEpochMillis = createdAtEpochMillis,
            senderSignatureBase64 = senderSignatureBase64
        )
    }
}

object DopHandshakeMessageCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encodeCanonicalUnsignedInitiation(
        unsigned: UnsignedDopInitiationWirePayload
    ): ByteArray {
        return json.encodeToString(unsigned).encodeToByteArray()
    }

    fun encodeInitiationWirePayload(payload: DopInitiationWirePayload): ByteArray {
        return json.encodeToString(payload).encodeToByteArray()
    }

    fun decodeInitiationWirePayload(bytes: ByteArray): DopInitiationWirePayload {
        return json.decodeFromString(bytes.decodeToString())
    }

    fun encodeInnerPayload(inner: DopInnerPayload): ByteArray {
        return json.encodeToString(inner).encodeToByteArray()
    }

    fun decodeInnerPayload(bytes: ByteArray): DopInnerPayload {
        return json.decodeFromString(bytes.decodeToString())
    }
}
