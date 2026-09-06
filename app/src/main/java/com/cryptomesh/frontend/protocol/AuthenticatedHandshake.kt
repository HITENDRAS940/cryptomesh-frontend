package com.cryptomesh.frontend.protocol

import com.cryptomesh.frontend.crypto.AesGcmSessionCipher
import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.crypto.SessionCipher
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HandshakeHello(
    val protocolVersion: Int,
    val deviceId: String,
    val displayName: String,
    val signingPublicKeyBase64: String,
    val ephemeralPublicKeyBase64: String,
    val nonceBase64: String,
    val createdAtEpochMillis: Long,
    val signatureBase64: String
)

data class PendingHandshake(
    val hello: HandshakeHello,
    internal val ephemeralKeyPair: KeyPair,
    internal val nonce: ByteArray
)

data class AuthenticatedSession(
    val peerDeviceId: String,
    val peerDisplayName: String,
    val peerSigningPublicKey: ByteArray,
    val cipher: SessionCipher
)

class AuthenticatedHandshake(
    private val signatureService: PacketSignatureService,
    private val now: () -> Long = System::currentTimeMillis,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private val json = Json { encodeDefaults = true }

    fun createHello(identity: LocalIdentity): PendingHandshake {
        val ephemeralKeyPair = KeyPairGenerator.getInstance(KEY_ALGORITHM).run {
            initialize(ECGenParameterSpec(ELLIPTIC_CURVE), secureRandom)
            generateKeyPair()
        }
        val nonce = ByteArray(HANDSHAKE_NONCE_BYTES).also(
            secureRandom::nextBytes
        )
        val unsigned = UnsignedHandshakeHello(
            protocolVersion = PROTOCOL_VERSION,
            deviceId = identity.deviceId,
            displayName = identity.displayName.take(MAX_DISPLAY_NAME_LENGTH),
            signingPublicKeyBase64 = signatureService.publicKeyBytes.toBase64(),
            ephemeralPublicKeyBase64 = ephemeralKeyPair.public.encoded.toBase64(),
            nonceBase64 = nonce.toBase64(),
            createdAtEpochMillis = now()
        )
        val signature = signatureService.sign(
            json.encodeToString(unsigned).encodeToByteArray()
        )
        return PendingHandshake(
            hello = unsigned.toSigned(signature.toBase64()),
            ephemeralKeyPair = ephemeralKeyPair,
            nonce = nonce
        )
    }

    fun authenticate(
        localIdentity: LocalIdentity,
        pending: PendingHandshake,
        remoteHello: HandshakeHello,
        expectedDeviceId: String? = null
    ): Result<AuthenticatedSession> = runCatching {
        require(remoteHello.protocolVersion == PROTOCOL_VERSION) {
            "Unsupported handshake protocol version."
        }
        require(remoteHello.deviceId != localIdentity.deviceId) {
            "A device cannot establish a session with itself."
        }
        require(remoteHello.displayName.isNotBlank()) {
            "Peer display name is missing."
        }
        require(
            kotlin.math.abs(now() - remoteHello.createdAtEpochMillis) <=
                MAX_CLOCK_SKEW_MILLIS
        ) {
            "Peer handshake has expired."
        }

        val remoteSigningKey = remoteHello.signingPublicKeyBase64.fromBase64()
        require(deviceIdForPublicKey(remoteSigningKey) == remoteHello.deviceId) {
            "Peer device ID does not match its signing key."
        }
        require(
            expectedDeviceId == null ||
                expectedDeviceId == remoteHello.deviceId
        ) {
            "Authenticated peer does not match the discovered device."
        }
        val unsigned = remoteHello.toUnsigned()
        val signatureValid = signatureService.verify(
            data = json.encodeToString(unsigned).encodeToByteArray(),
            signature = remoteHello.signatureBase64.fromBase64(),
            publicKeyBytes = remoteSigningKey
        )
        require(signatureValid) {
            "Peer handshake signature verification failed."
        }

        val remoteEphemeralKey = KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePublic(
                X509EncodedKeySpec(
                    remoteHello.ephemeralPublicKeyBase64.fromBase64()
                )
            )
        val sharedSecret = KeyAgreement.getInstance(KEY_AGREEMENT).run {
            init(pending.ephemeralKeyPair.private)
            doPhase(remoteEphemeralKey, true)
            generateSecret()
        }
        val remoteNonce = remoteHello.nonceBase64.fromBase64()
        require(remoteNonce.size == HANDSHAKE_NONCE_BYTES) {
            "Peer handshake nonce is invalid."
        }
        val firstIsLocal = localIdentity.deviceId < remoteHello.deviceId
        val orderedNonce = if (firstIsLocal) {
            pending.nonce + remoteNonce
        } else {
            remoteNonce + pending.nonce
        }
        val orderedIds = listOf(
            localIdentity.deviceId,
            remoteHello.deviceId
        ).sorted()
        val sessionKey = hkdfSha256(
            inputKeyMaterial = sharedSecret,
            salt = MessageDigest.getInstance("SHA-256").digest(orderedNonce),
            info = (
                "CryptoMesh direct session v1|" +
                    orderedIds.joinToString("|")
                ).encodeToByteArray(),
            outputSize = SESSION_KEY_BYTES
        )

        AuthenticatedSession(
            peerDeviceId = remoteHello.deviceId,
            peerDisplayName = remoteHello.displayName,
            peerSigningPublicKey = remoteSigningKey,
            cipher = AesGcmSessionCipher(sessionKey)
        )
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val KEY_ALGORITHM = "EC"
        private const val KEY_AGREEMENT = "ECDH"
        private const val ELLIPTIC_CURVE = "secp256r1"
        private const val HANDSHAKE_NONCE_BYTES = 32
        private const val SESSION_KEY_BYTES = 32
        private const val MAX_DISPLAY_NAME_LENGTH = 40
        private const val MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1_000L
    }
}

@Serializable
private data class UnsignedHandshakeHello(
    val protocolVersion: Int,
    val deviceId: String,
    val displayName: String,
    val signingPublicKeyBase64: String,
    val ephemeralPublicKeyBase64: String,
    val nonceBase64: String,
    val createdAtEpochMillis: Long
) {
    fun toSigned(signatureBase64: String): HandshakeHello {
        return HandshakeHello(
            protocolVersion = protocolVersion,
            deviceId = deviceId,
            displayName = displayName,
            signingPublicKeyBase64 = signingPublicKeyBase64,
            ephemeralPublicKeyBase64 = ephemeralPublicKeyBase64,
            nonceBase64 = nonceBase64,
            createdAtEpochMillis = createdAtEpochMillis,
            signatureBase64 = signatureBase64
        )
    }
}

private fun HandshakeHello.toUnsigned(): UnsignedHandshakeHello {
    return UnsignedHandshakeHello(
        protocolVersion = protocolVersion,
        deviceId = deviceId,
        displayName = displayName,
        signingPublicKeyBase64 = signingPublicKeyBase64,
        ephemeralPublicKeyBase64 = ephemeralPublicKeyBase64,
        nonceBase64 = nonceBase64,
        createdAtEpochMillis = createdAtEpochMillis
    )
}

internal fun deviceIdForPublicKey(publicKeyBytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(publicKeyBytes)
        .take(4)
        .joinToString(separator = "") { byte -> "%02X".format(byte) }
    return "CM-$digest"
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

private fun ByteArray.toBase64(): String {
    return Base64.getEncoder().encodeToString(this)
}

private fun String.fromBase64(): ByteArray {
    return Base64.getDecoder().decode(this)
}
