package com.cryptomesh.frontend.protocol

import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.crypto.AesGcmSessionCipher
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePacketCodecTest {
    @Test
    fun sealedPacketRoundTripsWithoutExposingPlaintext() {
        val signer = JvmEcdsaSignatureService()
        val codec = testCodec(signer)
        val plaintext = "Meet at the north gate".encodeToByteArray()

        val packet = codec.seal(
            packetType = SecurePacketType.Message,
            senderId = "CM-SENDER",
            receiverId = "CM-RECEIVER",
            payload = plaintext,
            expiresAtEpochMillis = TEST_TIME + 60_000
        )
        val encoded = codec.encode(packet)
        val decoded = codec.decode(encoded)
        val opened = codec.open(decoded, signer.publicKeyBytes)

        assertTrue(opened.isSuccess)
        assertArrayEquals(plaintext, opened.getOrThrow())
        assertFalse(encoded.decodeToString().contains(plaintext.decodeToString()))
        assertEquals(SecurePacketCodec.CURRENT_PROTOCOL_VERSION, packet.protocolVersion)
        assertEquals("packet-test-1", packet.packetId)
    }

    @Test
    fun sealedMediaOfferRoundTripsWithoutExposingFileMetadata() {
        val signer = JvmEcdsaSignatureService()
        val wireCodec = DirectWireCodec()
        val codec = testCodec(signer)
        val offer = MediaOfferPayload(
            transferId = "media-transfer-1",
            mediaKind = MediaKind.Photo,
            fileName = "private-photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 2_048_000L,
            chunkSizeBytes = 8_192,
            totalChunks = 250,
            fileSha256Base64 = "ZmlsZS1zaGEyNTY=",
            encryptedTransferKeyBase64 = "ZW5jcnlwdGVkLWtleQ==",
            createdAtEpochMillis = TEST_TIME,
            expiresAtEpochMillis = TEST_TIME + 86_400_000L
        )
        val plaintext = wireCodec.encodeMediaOffer(offer)

        val packet = codec.seal(
            packetType = SecurePacketType.MediaOffer,
            senderId = "CM-SENDER",
            receiverId = "CM-RECEIVER",
            payload = plaintext,
            expiresAtEpochMillis = TEST_TIME + 86_400_000L
        )
        val opened = codec.open(packet, signer.publicKeyBytes).getOrThrow()

        assertEquals(offer, wireCodec.decodeMediaOffer(opened))
        assertFalse(
            codec.encode(packet).decodeToString().contains("private-photo.jpg")
        )
        assertEquals(SecurePacketType.MediaOffer, packet.packetType)
    }

    @Test
    fun ciphertextAndMetadataTamperingAreRejected() {
        val signer = JvmEcdsaSignatureService()
        val codec = testCodec(signer)
        val packet = codec.seal(
            packetType = SecurePacketType.Message,
            senderId = "CM-SENDER",
            receiverId = "CM-RECEIVER",
            payload = byteArrayOf(1, 2, 3, 4)
        )

        val ciphertextTampered = packet.copy(
            encryptedPayloadBase64 =
                packet.encryptedPayloadBase64.flipFirstByte()
        )
        val metadataTampered = packet.copy(receiverId = "CM-ATTACKER")

        assertTrue(
            codec.open(
                ciphertextTampered,
                signer.publicKeyBytes
            ).isFailure
        )
        assertTrue(
            codec.open(
                metadataTampered,
                signer.publicKeyBytes
            ).isFailure
        )
    }

    @Test
    fun invalidSignerAndExpiredPacketAreRejected() {
        val signer = JvmEcdsaSignatureService()
        val otherSigner = JvmEcdsaSignatureService()
        var currentTime = TEST_TIME
        val codec = testCodec(signer) { currentTime }
        val packet = codec.seal(
            packetType = SecurePacketType.Message,
            senderId = "CM-SENDER",
            receiverId = "CM-RECEIVER",
            payload = "1250".encodeToByteArray(),
            expiresAtEpochMillis = TEST_TIME + 1_000
        )

        assertTrue(
            codec.open(packet, otherSigner.publicKeyBytes).isFailure
        )

        currentTime = TEST_TIME + 2_000
        assertTrue(codec.open(packet, signer.publicKeyBytes).isFailure)
    }

    private fun testCodec(
        signer: PacketSignatureService,
        now: () -> Long = { TEST_TIME }
    ): SecurePacketCodec {
        return SecurePacketCodec(
            sessionCipher = AesGcmSessionCipher(
                ByteArray(32) { index -> (index + 1).toByte() }
            ),
            signatureService = signer,
            now = now,
            packetId = { "packet-test-1" },
            nonce = { ByteArray(16) { index -> index.toByte() } }
        )
    }

    companion object {
        private const val TEST_TIME = 1_750_000_000_000L
    }
}

private class JvmEcdsaSignatureService : PacketSignatureService {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    override val publicKeyBytes: ByteArray =
        keyPair.public.encoded.copyOf()

    override fun sign(data: ByteArray): ByteArray {
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(data)
            sign()
        }
    }

    override fun verify(
        data: ByteArray,
        signature: ByteArray,
        publicKeyBytes: ByteArray
    ): Boolean {
        return runCatching {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(publicKeyBytes)
            )
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
    }
}

private fun String.flipFirstByte(): String {
    val decoded = Base64.getDecoder().decode(this)
    decoded[0] = (decoded[0].toInt() xor 1).toByte()
    return Base64.getEncoder().encodeToString(decoded)
}
