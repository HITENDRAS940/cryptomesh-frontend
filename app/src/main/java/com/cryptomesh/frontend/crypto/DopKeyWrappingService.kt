package com.cryptomesh.frontend.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides authenticated encryption-at-rest for local DOP-Handshake private pre-key material
 * and persisted session keys stored in Room.
 *
 * On Android devices (API 26+), the wrapping key is a non-exportable 256-bit AES-GCM key
 * held inside Android Keystore ([AndroidKeyStoreWrappingService]). In JVM unit tests where
 * Android Keystore is unavailable, [SoftwareKeyWrappingService] provides an equivalent
 * AES-256-GCM authenticated wrapper.
 */
interface DopKeyWrappingService {
    fun wrap(
        plaintext: ByteArray,
        associatedData: ByteArray
    ): ByteArray

    fun unwrap(
        wrapped: ByteArray,
        associatedData: ByteArray
    ): ByteArray

    fun clear()
}

@RequiresApi(Build.VERSION_CODES.M)
class AndroidKeyStoreWrappingService : DopKeyWrappingService {
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override fun wrap(
        plaintext: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        require(iv != null && iv.size == IV_SIZE_BYTES) {
            "Android Keystore failed to provide a 12-byte GCM IV."
        }
        val encrypted = cipher.doFinal(plaintext)
        return iv + encrypted
    }

    override fun unwrap(
        wrapped: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        require(wrapped.size > IV_SIZE_BYTES) {
            "Wrapped key payload is incomplete."
        }
        val secretKey = getOrCreateSecretKey()
        val iv = wrapped.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = wrapped.copyOfRange(IV_SIZE_BYTES, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(TAG_SIZE_BITS, iv)
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }

    override fun clear() {
        runCatching {
            if (keyStore.containsAlias(WRAPPING_KEY_ALIAS)) {
                keyStore.deleteEntry(WRAPPING_KEY_ALIAS)
            }
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val existingKey = keyStore.getKey(WRAPPING_KEY_ALIAS, null)
        if (existingKey is SecretKey) {
            return existingKey
        }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    WRAPPING_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val WRAPPING_KEY_ALIAS = "cryptomesh_dop_storage_wrap_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
    }
}

class SoftwareKeyWrappingService(
    secureRandom: SecureRandom = SecureRandom(),
    initialKey: ByteArray? = null
) : DopKeyWrappingService {
    private val random = secureRandom

    @Volatile
    private var cipher = AesGcmSessionCipher(
        sessionKey = initialKey?.copyOf() ?: ByteArray(32).also(random::nextBytes),
        secureRandom = random
    )

    override fun wrap(
        plaintext: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        return cipher.encrypt(plaintext, associatedData)
    }

    override fun unwrap(
        wrapped: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        return cipher.decrypt(wrapped, associatedData)
    }

    override fun clear() {
        cipher = AesGcmSessionCipher(
            sessionKey = ByteArray(32).also(random::nextBytes),
            secureRandom = random
        )
    }
}
