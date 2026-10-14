package com.cryptomesh.frontend.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object GroupCrypto {
    private const val KEY_SIZE_BYTES = 32
    private val secureRandom = SecureRandom()
    private val broadcastSalt = "cryptomesh-mesh-broadcast-v1".toByteArray()

    fun generateGroupKey(): ByteArray {
        val key = ByteArray(KEY_SIZE_BYTES)
        secureRandom.nextBytes(key)
        return key
    }

    fun deriveBroadcastKey(channelId: String): ByteArray {
        val ikm = channelId.trim().lowercase().toByteArray()
        val info = "cryptomesh-broadcast-channel-cipher".toByteArray()
        return hkdfSha256(
            inputKeyMaterial = ikm,
            salt = broadcastSalt,
            info = info,
            outputSize = KEY_SIZE_BYTES
        )
    }

    fun createGroupCipher(groupKey: ByteArray): SessionCipher {
        return AesGcmSessionCipher(groupKey)
    }

    fun encodeKey(key: ByteArray): String {
        return Base64.getEncoder().encodeToString(key)
    }

    fun decodeKey(keyBase64: String): ByteArray {
        return Base64.getDecoder().decode(keyBase64)
    }

    private fun hkdfSha256(
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
        return output
    }
}
