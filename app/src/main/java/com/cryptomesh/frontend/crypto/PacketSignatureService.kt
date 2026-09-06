package com.cryptomesh.frontend.crypto

interface PacketSignatureService {
    val publicKeyBytes: ByteArray

    fun sign(data: ByteArray): ByteArray

    fun verify(
        data: ByteArray,
        signature: ByteArray,
        publicKeyBytes: ByteArray
    ): Boolean
}

data class IdentityKeyMaterial(
    val publicKeyBytes: ByteArray,
    val publicKeyPreview: String
)

interface IdentityKeyStore : PacketSignatureService {
    fun getOrCreate(): IdentityKeyMaterial

    fun clear()
}
