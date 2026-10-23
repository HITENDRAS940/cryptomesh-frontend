package com.cryptomesh.frontend.protocol.dop

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class DopDerivedSessionMaterial(
    val sessionId: String,
    val transcriptHashHex: String,
    val sessionKey: ByteArray,
    val confirmationKey: ByteArray
) {
    fun wipe() {
        sessionKey.fill(0)
        confirmationKey.fill(0)
    }
}

/**
 * Performs P-256 ECDH key agreement and domain-separated HKDF-SHA-256 key derivation
 * bound to the complete [DopComputedTranscript].
 */
object DopSessionKeyDerivation {
    fun generateEcKeyPair(secureRandom: SecureRandom = SecureRandom()): KeyPair {
        return KeyPairGenerator.getInstance(DopProtocolVersion.JCA_KEY_ALGORITHM).run {
            initialize(ECGenParameterSpec(DopProtocolVersion.JCA_ELLIPTIC_CURVE), secureRandom)
            generateKeyPair()
        }
    }

    fun decodePublicKey(base64: String): PublicKey {
        val encoded = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance(DopProtocolVersion.JCA_KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(encoded))
    }

    fun decodePrivateKey(pkcs8Bytes: ByteArray): PrivateKey {
        return KeyFactory.getInstance(DopProtocolVersion.JCA_KEY_ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))
    }

    fun computeEcdhSharedSecret(
        localPrivateKey: PrivateKey,
        remotePublicKey: PublicKey
    ): ByteArray {
        return KeyAgreement.getInstance(DopProtocolVersion.JCA_KEY_AGREEMENT).run {
            init(localPrivateKey)
            doPhase(remotePublicKey, true)
            generateSecret()
        }
    }

    fun deriveKeys(
        dhSignedPreKeySecret: ByteArray,
        dhOneTimePreKeySecret: ByteArray?,
        senderNonce: ByteArray,
        transcript: DopComputedTranscript
    ): DopDerivedSessionMaterial {
        require(dhSignedPreKeySecret.isNotEmpty()) {
            "Signed pre-key ECDH secret must not be empty."
        }
        require(senderNonce.size == DopProtocolVersion.SENDER_NONCE_BYTES) {
            "DOP sender nonce must be ${DopProtocolVersion.SENDER_NONCE_BYTES} bytes."
        }

        val inputKeyMaterial = if (dhOneTimePreKeySecret != null) {
            require(dhOneTimePreKeySecret.isNotEmpty()) {
                "One-time pre-key ECDH secret must not be empty when present."
            }
            dhSignedPreKeySecret + dhOneTimePreKeySecret
        } else {
            dhSignedPreKeySecret.copyOf()
        }

        val saltDigest = MessageDigest.getInstance("SHA-256").run {
            update(DopProtocolVersion.HKDF_SALT_DOMAIN.encodeToByteArray())
            update(senderNonce)
            update(transcript.transcriptHash)
            digest()
        }

        val info = (
            DopProtocolVersion.HKDF_INFO_PREFIX +
                transcript.transcriptHashHex +
                "|sessionId=" +
                transcript.sessionId
            ).encodeToByteArray()

        val okm = hkdfSha256(
            inputKeyMaterial = inputKeyMaterial,
            salt = saltDigest,
            info = info,
            outputSize = DopProtocolVersion.DERIVED_KEY_MATERIAL_BYTES
        )
        inputKeyMaterial.fill(0)

        val sessionKey = okm.copyOfRange(0, DopProtocolVersion.SESSION_KEY_BYTES)
        val confirmationKey = okm.copyOfRange(
            DopProtocolVersion.SESSION_KEY_BYTES,
            DopProtocolVersion.DERIVED_KEY_MATERIAL_BYTES
        )
        okm.fill(0)

        return DopDerivedSessionMaterial(
            sessionId = transcript.sessionId,
            transcriptHashHex = transcript.transcriptHashHex,
            sessionKey = sessionKey,
            confirmationKey = confirmationKey
        )
    }

    fun computeConfirmationTagBase64(
        confirmationKey: ByteArray,
        sessionId: String,
        transcriptHashHex: String,
        initiationPacketId: String,
        responderDeviceId: String
    ): String {
        val message = (
            DopProtocolVersion.CONFIRMATION_MAC_DOMAIN +
                "$sessionId|$transcriptHashHex|$initiationPacketId|$responderDeviceId"
            ).encodeToByteArray()
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(confirmationKey, algorithm))
            doFinal(message)
        }
        return Base64.getEncoder().encodeToString(mac)
    }

    fun verifyConfirmationTag(
        confirmationKey: ByteArray,
        sessionId: String,
        transcriptHashHex: String,
        initiationPacketId: String,
        responderDeviceId: String,
        expectedTagBase64: String
    ): Boolean {
        val computed = computeConfirmationTagBase64(
            confirmationKey = confirmationKey,
            sessionId = sessionId,
            transcriptHashHex = transcriptHashHex,
            initiationPacketId = initiationPacketId,
            responderDeviceId = responderDeviceId
        )
        return MessageDigest.isEqual(
            computed.encodeToByteArray(),
            expectedTagBase64.encodeToByteArray()
        )
    }

    internal fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputSize: Int
    ): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, algorithm))
            doFinal(inputKeyMaterial)
        }
        val output = ByteArray(outputSize)
        var previous = byteArrayOf()
        var offset = 0
        var counter = 1
        while (offset < outputSize) {
            previous = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(extract, algorithm))
                update(previous)
                update(info)
                update(counter.toByte())
                doFinal()
            }
            val copySize = minOf(previous.size, outputSize - offset)
            previous.copyInto(output, offset, endIndex = copySize)
            offset += copySize
            counter += 1
        }
        extract.fill(0)
        return output
    }
}
