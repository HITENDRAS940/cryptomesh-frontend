package com.cryptomesh.frontend.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

class AndroidIdentityKeyStore : IdentityKeyStore {
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override val publicKeyBytes: ByteArray
        get() = getOrCreateKeyPair().public.encoded.copyOf()

    override fun getOrCreate(): IdentityKeyMaterial {
        val publicKey = publicKeyBytes
        return IdentityKeyMaterial(
            publicKeyBytes = publicKey,
            publicKeyPreview = publicKeyPreview(publicKey)
        )
    }

    override fun sign(data: ByteArray): ByteArray {
        val privateKey = getOrCreateKeyPair().private
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
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
            val publicKey = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
                .generatePublic(X509EncodedKeySpec(publicKeyBytes))
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    override fun clear() {
        keyStore.deleteEntry(IDENTITY_KEY_ALIAS)
    }

    private fun getOrCreateKeyPair(): KeyPair {
        val existingPrivateKey = keyStore.getKey(
            IDENTITY_KEY_ALIAS,
            null
        )
        val existingCertificate = keyStore.getCertificate(IDENTITY_KEY_ALIAS)
        if (
            existingPrivateKey is PrivateKey &&
            existingCertificate != null
        ) {
            return KeyPair(existingCertificate.publicKey, existingPrivateKey)
        }

        return KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEY_STORE
        ).run {
            initialize(
                KeyGenParameterSpec.Builder(
                    IDENTITY_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or
                        KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(
                        ECGenParameterSpec(ELLIPTIC_CURVE)
                    )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKeyPair()
        }
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val IDENTITY_KEY_ALIAS = "cryptomesh_identity_signing_v1"
        private const val ELLIPTIC_CURVE = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

internal fun publicKeyPreview(publicKeyBytes: ByteArray): String {
    return publicKeyBytes
        .takeLast(16)
        .joinToString(separator = "") { byte -> "%02X".format(byte) }
        .chunked(4)
        .joinToString(":")
}
