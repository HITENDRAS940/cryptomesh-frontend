package com.cryptomesh.frontend.protocol

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class DirectWireKind {
    Handshake,
    SecurePacket
}

@Serializable
data class DirectWireEnvelope(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val kind: DirectWireKind,
    val payloadBase64: String
) {
    companion object {
        const val PROTOCOL_VERSION = 1
    }
}

@Serializable
data class DirectTextPayload(
    val text: String,
    val sentAtEpochMillis: Long
)

@Serializable
data class DirectAcknowledgementPayload(
    val acknowledgedPacketId: String,
    val receivedAtEpochMillis: Long
)

class DirectWireCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encodeHandshake(hello: HandshakeHello): ByteArray {
        return encode(
            DirectWireKind.Handshake,
            json.encodeToString(hello).encodeToByteArray()
        )
    }

    fun encodePacket(packet: SecurePacketEnvelope): ByteArray {
        return encode(
            DirectWireKind.SecurePacket,
            json.encodeToString(packet).encodeToByteArray()
        )
    }

    fun decode(encoded: ByteArray): DecodedDirectWireMessage {
        val envelope = json.decodeFromString<DirectWireEnvelope>(
            encoded.decodeToString()
        )
        require(
            envelope.protocolVersion == DirectWireEnvelope.PROTOCOL_VERSION
        ) {
            "Unsupported direct-wire protocol version."
        }
        val payload = Base64.getDecoder().decode(envelope.payloadBase64)
        return when (envelope.kind) {
            DirectWireKind.Handshake -> DecodedDirectWireMessage.Handshake(
                json.decodeFromString(payload.decodeToString())
            )

            DirectWireKind.SecurePacket -> DecodedDirectWireMessage.Packet(
                json.decodeFromString(payload.decodeToString())
            )
        }
    }

    fun encodeTextPayload(payload: DirectTextPayload): ByteArray {
        return json.encodeToString(payload).encodeToByteArray()
    }

    fun decodeTextPayload(payload: ByteArray): DirectTextPayload {
        return json.decodeFromString(payload.decodeToString())
    }

    fun encodeAcknowledgement(
        payload: DirectAcknowledgementPayload
    ): ByteArray {
        return json.encodeToString(payload).encodeToByteArray()
    }

    fun decodeAcknowledgement(
        payload: ByteArray
    ): DirectAcknowledgementPayload {
        return json.decodeFromString(payload.decodeToString())
    }

    fun encodeMediaOffer(payload: MediaOfferPayload): ByteArray {
        return json.encodeToString(payload).encodeToByteArray()
    }

    fun decodeMediaOffer(payload: ByteArray): MediaOfferPayload {
        return json.decodeFromString(payload.decodeToString())
    }

    fun encodeMediaAccept(payload: MediaAcceptPayload): ByteArray {
        return json.encodeToString(payload).encodeToByteArray()
    }

    fun decodeMediaAccept(payload: ByteArray): MediaAcceptPayload {
        return json.decodeFromString(payload.decodeToString())
    }

    fun encodeMediaReject(payload: MediaRejectPayload): ByteArray {
        return json.encodeToString(payload).encodeToByteArray()
    }

    fun decodeMediaReject(payload: ByteArray): MediaRejectPayload {
        return json.decodeFromString(payload.decodeToString())
    }

    fun encodeMediaChunk(payload: MediaChunkPayload): ByteArray {
        return json.encodeToString(payload).encodeToByteArray()
    }

    fun decodeMediaChunk(payload: ByteArray): MediaChunkPayload {
        return json.decodeFromString(payload.decodeToString())
    }

    fun encodeMediaFragment(payload: MediaFragmentPayload): ByteArray {
        return json.encodeToString(payload).encodeToByteArray()
    }

    fun decodeMediaFragment(payload: ByteArray): MediaFragmentPayload {
        return json.decodeFromString(payload.decodeToString())
    }

    fun encodeMediaChunkAcknowledgement(
        payload: MediaChunkAcknowledgementPayload
    ): ByteArray {
        return json.encodeToString(payload).encodeToByteArray()
    }

    fun decodeMediaChunkAcknowledgement(
        payload: ByteArray
    ): MediaChunkAcknowledgementPayload {
        return json.decodeFromString(payload.decodeToString())
    }

    fun encodeMediaComplete(payload: MediaCompletePayload): ByteArray {
        return json.encodeToString(payload).encodeToByteArray()
    }

    fun decodeMediaComplete(payload: ByteArray): MediaCompletePayload {
        return json.decodeFromString(payload.decodeToString())
    }

    private fun encode(kind: DirectWireKind, payload: ByteArray): ByteArray {
        return json.encodeToString(
            DirectWireEnvelope(
                kind = kind,
                payloadBase64 = Base64.getEncoder().encodeToString(payload)
            )
        ).encodeToByteArray()
    }
}

sealed interface DecodedDirectWireMessage {
    data class Handshake(
        val hello: HandshakeHello
    ) : DecodedDirectWireMessage

    data class Packet(
        val envelope: SecurePacketEnvelope
    ) : DecodedDirectWireMessage
}
