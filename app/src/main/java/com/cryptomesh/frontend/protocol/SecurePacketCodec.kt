package com.cryptomesh.frontend.protocol

import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.crypto.SessionCipher
import com.cryptomesh.frontend.logging.MeshLog
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SecurePacketCodec(
    private val sessionCipher: SessionCipher,
    private val signatureService: PacketSignatureService,
    private val now: () -> Long = System::currentTimeMillis,
    private val packetId: () -> String = { UUID.randomUUID().toString() },
    private val nonce: () -> ByteArray = {
        ByteArray(NONCE_SIZE_BYTES).also(SecureRandom()::nextBytes)
    }
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun seal(
        packetType: SecurePacketType,
        senderId: String,
        receiverId: String,
        payload: ByteArray,
        expiresAtEpochMillis: Long? = null
    ): SecurePacketEnvelope {
        require(senderId.isNotBlank()) { "Sender ID is required." }
        require(receiverId.isNotBlank()) { "Receiver ID is required." }
        require(payload.isNotEmpty()) { "Packet payload cannot be empty." }

        val id = packetId()
        val ttlDesc = expiresAtEpochMillis?.let { "${it - now()}ms" } ?: "none"
        MeshLog.packet("CREATING PACKET: id=$id, type=$packetType, sender=$senderId -> receiver=$receiverId, payloadSize=${payload.size}B, ttl=$ttlDesc")

        val header = PacketHeader(
            protocolVersion = CURRENT_PROTOCOL_VERSION,
            packetId = id,
            packetType = packetType,
            senderId = senderId,
            receiverId = receiverId,
            createdAtEpochMillis = now(),
            expiresAtEpochMillis = expiresAtEpochMillis,
            nonceBase64 = nonce().toBase64()
        )
        val associatedData = json.encodeToString(header).encodeToByteArray()
        val encryptedPayload = sessionCipher.encrypt(payload, associatedData)
        val unsignedPacket = UnsignedSecurePacket(
            header = header,
            payloadHashBase64 = sha256(payload).toBase64(),
            encryptedPayloadBase64 = encryptedPayload.toBase64()
        )
        val signatureInput = json.encodeToString(unsignedPacket)
            .encodeToByteArray()

        val signature = signatureService.sign(signatureInput).toBase64()
        val envelope = unsignedPacket.toEnvelope(signatureBase64 = signature)
        MeshLog.packet("SEALED PACKET: id=$id, cipherBytes=${encryptedPayload.size}, sig=${signature.take(16)}...")
        return envelope
    }

    fun open(
        envelope: SecurePacketEnvelope,
        senderPublicKeyBytes: ByteArray
    ): Result<ByteArray> = runCatching {
        MeshLog.packet("OPENING PACKET: id=${envelope.packetId}, type=${envelope.packetType}, from=${envelope.senderId} to=${envelope.receiverId}")
        require(
            envelope.protocolVersion == CURRENT_PROTOCOL_VERSION
        ) {
            "Unsupported protocol version ${envelope.protocolVersion}."
        }
        require(
            envelope.expiresAtEpochMillis == null ||
                envelope.expiresAtEpochMillis > now()
        ) {
            "Packet has expired."
        }

        val unsignedPacket = envelope.toUnsigned()
        val signatureInput = json.encodeToString(unsignedPacket)
            .encodeToByteArray()
        val signatureValid = signatureService.verify(
            data = signatureInput,
            signature = envelope.signatureBase64.fromBase64(),
            publicKeyBytes = senderPublicKeyBytes
        )
        require(signatureValid) { "Packet signature verification failed." }
        MeshLog.packet("Signature VERIFIED for packet ${envelope.packetId} from ${envelope.senderId}")

        val associatedData = json
            .encodeToString(unsignedPacket.header)
            .encodeToByteArray()
        val plaintext = sessionCipher.decrypt(
            ciphertext = envelope.encryptedPayloadBase64.fromBase64(),
            associatedData = associatedData
        )
        val actualHash = sha256(plaintext)
        val expectedHash = envelope.payloadHashBase64.fromBase64()
        require(
            MessageDigest.isEqual(actualHash, expectedHash)
        ) {
            "Packet payload hash verification failed."
        }
        MeshLog.packet("PACKET OPENED SUCCESSFULLY: id=${envelope.packetId}, plaintext=${plaintext.size}B")
        plaintext
    }.onFailure { error ->
        MeshLog.error("PACKET", "Failed to open packet ${envelope.packetId} from ${envelope.senderId}: ${error.message}", error)
    }

    fun decryptPayloadWithoutVerification(
        envelope: SecurePacketEnvelope
    ): Result<ByteArray> = runCatching {
        MeshLog.packet("PRE-DECRYPTING group/broadcast packet ${envelope.packetId} (receiver=${envelope.receiverId}) with group key...")
        val unsignedPacket = envelope.toUnsigned()
        val associatedData = json
            .encodeToString(unsignedPacket.header)
            .encodeToByteArray()
        val plaintext = sessionCipher.decrypt(
            ciphertext = envelope.encryptedPayloadBase64.fromBase64(),
            associatedData = associatedData
        )
        MeshLog.packet("Pre-decrypted packet ${envelope.packetId}: plaintext=${plaintext.size}B")
        plaintext
    }.onFailure { error ->
        MeshLog.error("PACKET", "Failed to pre-decrypt packet ${envelope.packetId}: ${error.message}", error)
    }

    fun encode(envelope: SecurePacketEnvelope): ByteArray {
        return json.encodeToString(envelope).encodeToByteArray()
    }

    fun decode(encodedPacket: ByteArray): SecurePacketEnvelope {
        return json.decodeFromString(
            encodedPacket.decodeToString()
        )
    }

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 1
        private const val NONCE_SIZE_BYTES = 16
    }
}

@Serializable
private data class PacketHeader(
    val protocolVersion: Int,
    val packetId: String,
    val packetType: SecurePacketType,
    val senderId: String,
    val receiverId: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val nonceBase64: String
)

@Serializable
private data class UnsignedSecurePacket(
    val header: PacketHeader,
    val payloadHashBase64: String,
    val encryptedPayloadBase64: String
) {
    fun toEnvelope(signatureBase64: String): SecurePacketEnvelope {
        return SecurePacketEnvelope(
            protocolVersion = header.protocolVersion,
            packetId = header.packetId,
            packetType = header.packetType,
            senderId = header.senderId,
            receiverId = header.receiverId,
            createdAtEpochMillis = header.createdAtEpochMillis,
            expiresAtEpochMillis = header.expiresAtEpochMillis,
            nonceBase64 = header.nonceBase64,
            payloadHashBase64 = payloadHashBase64,
            encryptedPayloadBase64 = encryptedPayloadBase64,
            signatureBase64 = signatureBase64
        )
    }
}

private fun SecurePacketEnvelope.toUnsigned(): UnsignedSecurePacket {
    return UnsignedSecurePacket(
        header = PacketHeader(
            protocolVersion = protocolVersion,
            packetId = packetId,
            packetType = packetType,
            senderId = senderId,
            receiverId = receiverId,
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            nonceBase64 = nonceBase64
        ),
        payloadHashBase64 = payloadHashBase64,
        encryptedPayloadBase64 = encryptedPayloadBase64
    )
}

private fun sha256(value: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(value)
}

private fun ByteArray.toBase64(): String {
    return Base64.getEncoder().encodeToString(this)
}

private fun String.fromBase64(): ByteArray {
    return Base64.getDecoder().decode(this)
}
