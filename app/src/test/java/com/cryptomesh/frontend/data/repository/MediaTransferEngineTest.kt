package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.protocol.MediaKind
import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTransferEngineTest {
    @Test
    fun preparesChunksEncryptsAndReassemblesMediaBytes() {
        val engine = testEngine()
        val bytes = ByteArray(20_000) { index -> (index % 251).toByte() }

        val prepared = engine.prepareOutgoingTransfer(
            mediaKind = MediaKind.Photo,
            fileName = "image.jpg",
            mimeType = "image/jpeg",
            bytes = bytes,
            chunkSizeBytes = 4_096
        )
        val sealedChunks = prepared.chunks.map {
            engine.sealChunk(
                transferKey = prepared.transferKey,
                chunk = it,
                totalChunks = prepared.offer.totalChunks
            )
        }
        val opened = sealedChunks.associate { payload ->
            payload.chunkIndex to engine.openChunk(
                transferKey = prepared.transferKey,
                payload = payload
            )
        }
        val reassembled = engine.reassembleTransfer(prepared.offer, opened)

        assertEquals(5, prepared.offer.totalChunks)
        assertEquals(MediaKind.Photo, prepared.offer.mediaKind)
        assertEquals(bytes.size.toLong(), prepared.offer.sizeBytes)
        assertArrayEquals(bytes, reassembled)
        assertFalse(
            sealedChunks.first().encryptedChunkBase64
                .contains(Base64.getEncoder().encodeToString(bytes.take(8).toByteArray()))
        )
    }

    @Test
    fun rejectsTamperedEncryptedChunkAndMismatchedReassembly() {
        val engine = testEngine()
        val bytes = ByteArray(3_000) { index -> index.toByte() }
        val prepared = engine.prepareOutgoingTransfer(
            mediaKind = MediaKind.Audio,
            fileName = "clip.m4a",
            mimeType = "audio/mp4",
            bytes = bytes,
            chunkSizeBytes = 1_024
        )
        val chunk = engine.sealChunk(
            transferKey = prepared.transferKey,
            chunk = prepared.chunks.first(),
            totalChunks = prepared.offer.totalChunks
        )
        val tampered = chunk.copy(
            encryptedChunkBase64 = chunk.encryptedChunkBase64.flipFirstByte()
        )

        assertTrue(
            runCatching {
                engine.openChunk(prepared.transferKey, tampered)
            }.isFailure
        )
        assertTrue(
            runCatching {
                engine.reassembleTransfer(
                    prepared.offer,
                    mapOf(0 to prepared.chunks.first().plaintext)
                )
            }.isFailure
        )
    }

    private fun testEngine(): MediaTransferEngine {
        return MediaTransferEngine(
            now = { TEST_TIME },
            transferId = { "transfer-test-1" },
            secureRandom = SecureRandom(ByteArray(32) { it.toByte() })
        )
    }

    companion object {
        private const val TEST_TIME = 1_750_000_000_000L
    }
}

private fun String.flipFirstByte(): String {
    val decoded = Base64.getDecoder().decode(this)
    decoded[0] = (decoded[0].toInt() xor 1).toByte()
    return Base64.getEncoder().encodeToString(decoded)
}
