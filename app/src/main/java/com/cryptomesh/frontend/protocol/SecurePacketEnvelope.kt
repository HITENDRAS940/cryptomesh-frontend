package com.cryptomesh.frontend.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class SecurePacketType {
    Message,
    Acknowledgement,
    MediaOffer,
    MediaAccept,
    MediaReject,
    MediaChunk,
    MediaChunkAcknowledgement,
    MediaComplete
}

@Serializable
data class SecurePacketEnvelope(
    val protocolVersion: Int,
    val packetId: String,
    val packetType: SecurePacketType,
    val senderId: String,
    val receiverId: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val nonceBase64: String,
    val payloadHashBase64: String,
    val encryptedPayloadBase64: String,
    val signatureBase64: String
)
