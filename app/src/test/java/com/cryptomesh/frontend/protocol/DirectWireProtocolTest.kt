package com.cryptomesh.frontend.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectWireProtocolTest {
    private val codec = DirectWireCodec()

    @Test
    fun handshakeRoundTripsThroughVersionedWireEnvelope() {
        val hello = HandshakeHello(
            protocolVersion = 1,
            deviceId = "CM-12345678",
            displayName = "Test Peer",
            signingPublicKeyBase64 = "c2lnbmluZw==",
            ephemeralPublicKeyBase64 = "ZXBoZW1lcmFs",
            nonceBase64 = "bm9uY2U=",
            createdAtEpochMillis = 123L,
            signatureBase64 = "c2lnbmF0dXJl"
        )

        val decoded = codec.decode(codec.encodeHandshake(hello))

        assertEquals(
            hello,
            (decoded as DecodedDirectWireMessage.Handshake).hello
        )
    }

    @Test
    fun textAndAcknowledgementPayloadsRoundTrip() {
        val text = DirectTextPayload("Hello over BLE", 456L)
        val acknowledgement = DirectAcknowledgementPayload("packet-1", 789L)

        assertEquals(
            text,
            codec.decodeTextPayload(codec.encodeTextPayload(text))
        )
        assertEquals(
            acknowledgement,
            codec.decodeAcknowledgement(
                codec.encodeAcknowledgement(acknowledgement)
            )
        )
    }

    @Test
    fun mediaTransferPayloadsRoundTrip() {
        val offer = MediaOfferPayload(
            transferId = "media-transfer-1",
            mediaKind = MediaKind.Video,
            fileName = "field-clip.mp4",
            mimeType = "video/mp4",
            sizeBytes = 18_432_000L,
            chunkSizeBytes = 8_192,
            totalChunks = 2_250,
            fileSha256Base64 = "ZmlsZS1zaGEyNTY=",
            encryptedTransferKeyBase64 = "ZW5jcnlwdGVkLWtleQ==",
            createdAtEpochMillis = 1_750_000_000_000L,
            expiresAtEpochMillis = 1_750_086_400_000L
        )
        val accept = MediaAcceptPayload(
            transferId = offer.transferId,
            acceptedAtEpochMillis = 1_750_000_001_000L,
            requestedChunkIndexes = listOf(0, 1, 2)
        )
        val reject = MediaRejectPayload(
            transferId = offer.transferId,
            rejectedAtEpochMillis = 1_750_000_002_000L,
            reason = MediaRejectReason.FileTooLarge,
            message = "Recipient limit exceeded"
        )
        val chunk = MediaChunkPayload(
            transferId = offer.transferId,
            chunkIndex = 7,
            totalChunks = offer.totalChunks,
            offsetBytes = 57_344L,
            chunkSizeBytes = offer.chunkSizeBytes,
            chunkSha256Base64 = "Y2h1bmstc2hhMjU2",
            encryptedChunkBase64 = "ZW5jcnlwdGVkLWNodW5r"
        )
        val chunkAck = MediaChunkAcknowledgementPayload(
            transferId = offer.transferId,
            acknowledgedChunkIndexes = listOf(0, 1, 7),
            missingChunkIndexes = listOf(2, 3),
            receivedAtEpochMillis = 1_750_000_003_000L
        )
        val complete = MediaCompletePayload(
            transferId = offer.transferId,
            totalChunks = offer.totalChunks,
            fileSha256Base64 = offer.fileSha256Base64,
            completedAtEpochMillis = 1_750_000_004_000L
        )

        assertEquals(
            offer,
            codec.decodeMediaOffer(codec.encodeMediaOffer(offer))
        )
        assertEquals(
            accept,
            codec.decodeMediaAccept(codec.encodeMediaAccept(accept))
        )
        assertEquals(
            reject,
            codec.decodeMediaReject(codec.encodeMediaReject(reject))
        )
        assertEquals(
            chunk,
            codec.decodeMediaChunk(codec.encodeMediaChunk(chunk))
        )
        assertEquals(
            chunkAck,
            codec.decodeMediaChunkAcknowledgement(
                codec.encodeMediaChunkAcknowledgement(chunkAck)
            )
        )
        assertEquals(
            complete,
            codec.decodeMediaComplete(codec.encodeMediaComplete(complete))
        )
    }
}
