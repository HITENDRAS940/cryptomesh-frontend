package com.cryptomesh.frontend.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface SessionCipher {
    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray
    ): ByteArray

    fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray
    ): ByteArray
}

class AesGcmSessionCipher(
    sessionKey: ByteArray,
    private val secureRandom: SecureRandom = SecureRandom()
) : SessionCipher {
    private val key = SecretKeySpec(
        sessionKey.copyOf().also {
            require(it.size == KEY_SIZE_BYTES) {
                "AES-256 requires a 32-byte session key."
            }
        },
        "AES"
    )

    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        val nonce = ByteArray(NONCE_SIZE_BYTES).also(secureRandom::nextBytes)
        val ciphertext = Cipher.getInstance(TRANSFORMATION).run {
            init(
                Cipher.ENCRYPT_MODE,
                key,
                GCMParameterSpec(TAG_SIZE_BITS, nonce)
            )
            updateAAD(associatedData)
            doFinal(plaintext)
        }
        return nonce + ciphertext
    }

    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        require(ciphertext.size > NONCE_SIZE_BYTES) {
            "Encrypted session payload is incomplete."
        }
        val nonce = ciphertext.copyOfRange(0, NONCE_SIZE_BYTES)
        val encryptedPayload = ciphertext.copyOfRange(
            NONCE_SIZE_BYTES,
            ciphertext.size
        )
        return Cipher.getInstance(TRANSFORMATION).run {
            init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_SIZE_BITS, nonce)
            )
            updateAAD(associatedData)
            doFinal(encryptedPayload)
        }
    }

    private companion object {
        const val KEY_SIZE_BYTES = 32
        const val NONCE_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
