package com.cryptomesh.frontend.protocol

import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedHandshakeTest {
    @Test
    fun signedEcdhHandshakeDerivesMatchingAuthenticatedSessionKeys() {
        val clock = { 1_000_000L }
        val aliceSigner = SoftwareSignatureService()
        val bobSigner = SoftwareSignatureService()
        val alice = identity("Alice", aliceSigner)
        val bob = identity("Bob", bobSigner)
        val aliceHandshake = AuthenticatedHandshake(aliceSigner, clock)
        val bobHandshake = AuthenticatedHandshake(bobSigner, clock)
        val alicePending = aliceHandshake.createHello(alice)
        val bobPending = bobHandshake.createHello(bob)

        val aliceSession = aliceHandshake.authenticate(
            localIdentity = alice,
            pending = alicePending,
            remoteHello = bobPending.hello,
            expectedDeviceId = bob.deviceId
        ).getOrThrow()
        val bobSession = bobHandshake.authenticate(
            localIdentity = bob,
            pending = bobPending,
            remoteHello = alicePending.hello,
            expectedDeviceId = alice.deviceId
        ).getOrThrow()

        val plaintext = "authenticated direct message".encodeToByteArray()
        val associatedData = "packet-header".encodeToByteArray()
        val ciphertext = aliceSession.cipher.encrypt(
            plaintext,
            associatedData
        )

        assertArrayEquals(
            plaintext,
            bobSession.cipher.decrypt(ciphertext, associatedData)
        )
        assertTrue(aliceSession.peerDeviceId == bob.deviceId)
        assertTrue(bobSession.peerDeviceId == alice.deviceId)
    }

    @Test
    fun discoveredIdentityMismatchRejectsHandshake() {
        val clock = { 2_000_000L }
        val aliceSigner = SoftwareSignatureService()
        val bobSigner = SoftwareSignatureService()
        val alice = identity("Alice", aliceSigner)
        val bob = identity("Bob", bobSigner)
        val aliceHandshake = AuthenticatedHandshake(aliceSigner, clock)

        val result = aliceHandshake.authenticate(
            localIdentity = alice,
            pending = aliceHandshake.createHello(alice),
            remoteHello = AuthenticatedHandshake(
                bobSigner,
                clock
            ).createHello(bob).hello,
            expectedDeviceId = "CM-00000000"
        )

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message
                ?.contains("discovered device") == true
        )
    }

    private fun identity(
        name: String,
        signer: PacketSignatureService
    ): LocalIdentity {
        return LocalIdentity(
            displayName = name,
            deviceId = deviceIdForPublicKey(signer.publicKeyBytes),
            publicKeyPreview = "test"
        )
    }
}

private class SoftwareSignatureService : PacketSignatureService {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    override val publicKeyBytes: ByteArray
        get() = keyPair.public.encoded.copyOf()

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
