package com.cryptomesh.frontend.protocol.dop

import java.security.MessageDigest

/**
 * Canonical protocol transcript inputs for a `DOP-v1` asynchronous session initiation.
 * Every field is bound into [transcriptHash] and therefore into both [sessionId] and
 * the HKDF-SHA-256 derived session keys.
 */
data class DopTranscriptInputs(
    val protocolId: String = DopProtocolVersion.PROTOCOL_ID,
    val protocolVersion: Int = DopProtocolVersion.PROTOCOL_VERSION,
    val senderDeviceId: String,
    val senderIdentityFingerprintHex: String,
    val senderSigningPublicKeyBase64: String,
    val recipientDeviceId: String,
    val recipientIdentityFingerprintHex: String,
    val recipientSigningPublicKeyBase64: String,
    val recordId: String,
    val epochId: Long,
    val signedPreKeyId: String,
    val signedPreKeyPublicKeyBase64: String,
    val oneTimePreKeyId: String?,
    val oneTimePreKeyPublicKeyBase64: String?,
    val senderEphemeralPublicKeyBase64: String,
    val senderNonceBase64: String
)

data class DopComputedTranscript(
    val inputs: DopTranscriptInputs,
    val canonicalBytes: ByteArray,
    val transcriptHash: ByteArray,
    val transcriptHashHex: String,
    val sessionId: String
)

object DopHandshakeTranscript {
    /**
     * Builds the canonical length-prefixed transcript representation, computes its SHA-256
     * digest (`H_transcript`), and derives the transcript-bound `sessionId`.
     */
    fun compute(inputs: DopTranscriptInputs): DopComputedTranscript {
        val fields = listOf(
            DopProtocolVersion.TRANSCRIPT_DOMAIN,
            inputs.protocolId,
            inputs.protocolVersion.toString(),
            inputs.senderDeviceId,
            inputs.senderIdentityFingerprintHex.lowercase(),
            inputs.senderSigningPublicKeyBase64,
            inputs.recipientDeviceId,
            inputs.recipientIdentityFingerprintHex.lowercase(),
            inputs.recipientSigningPublicKeyBase64,
            inputs.recordId,
            inputs.epochId.toString(),
            inputs.signedPreKeyId,
            inputs.signedPreKeyPublicKeyBase64,
            inputs.oneTimePreKeyId ?: "NONE",
            inputs.oneTimePreKeyPublicKeyBase64 ?: "NONE",
            inputs.senderEphemeralPublicKeyBase64,
            inputs.senderNonceBase64
        )
        val canonicalString = buildString {
            fields.forEachIndexed { index, value ->
                if (index > 0) append('|')
                append(value.length)
                append(':')
                append(value)
            }
        }
        val canonicalBytes = canonicalString.encodeToByteArray()
        val transcriptHash = MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
        val transcriptHashHex = transcriptHash.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
        val sessionPrefixHex = transcriptHash
            .take(16)
            .joinToString(separator = "") { byte -> "%02X".format(byte) }
        val sessionId = "DOP-S1-$sessionPrefixHex"

        return DopComputedTranscript(
            inputs = inputs,
            canonicalBytes = canonicalBytes,
            transcriptHash = transcriptHash,
            transcriptHashHex = transcriptHashHex,
            sessionId = sessionId
        )
    }

    /**
     * Constructs the Associated Authenticated Data (AAD) binding the inner encrypted payload
     * of a `DopInitiation` message to its transcript hash, session ID, and outer packet ID.
     */
    fun buildInnerPayloadAad(
        transcriptHashHex: String,
        sessionId: String,
        packetId: String
    ): ByteArray {
        return "${DopProtocolVersion.INNER_AAD_DOMAIN}$transcriptHashHex|$sessionId|$packetId"
            .encodeToByteArray()
    }
}
