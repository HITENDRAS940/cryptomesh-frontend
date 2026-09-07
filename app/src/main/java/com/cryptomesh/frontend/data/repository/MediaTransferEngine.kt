package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.crypto.AesGcmSessionCipher
import com.cryptomesh.frontend.protocol.MediaChunkPayload
import com.cryptomesh.frontend.protocol.MediaCompletePayload
import com.cryptomesh.frontend.protocol.MediaKind
import com.cryptomesh.frontend.protocol.MediaOfferPayload
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class PreparedMediaTransfer(
    val offer: MediaOfferPayload,
    val transferKey: ByteArray,
    val chunks: List<PreparedMediaChunk>,
    val previewBytes: ByteArray
)

data class PreparedMediaChunk(
    val transferId: String,
    val chunkIndex: Int,
    val offsetBytes: Long,
    val plaintext: ByteArray,
    val plaintextSha256Base64: String
) {
    val sizeBytes: Int
        get() = plaintext.size
}

class MediaTransferEngine(
    private val now: () -> Long = System::currentTimeMillis,
    private val transferId: () -> String = { UUID.randomUUID().toString() },
    private val secureRandom: SecureRandom = SecureRandom()
) {
    fun prepareOutgoingTransfer(
        mediaKind: MediaKind,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        chunkSizeBytes: Int = DEFAULT_MEDIA_CHUNK_BYTES,
        ttlMillis: Long = DEFAULT_MEDIA_TTL_MILLIS
    ): PreparedMediaTransfer {
        require(bytes.isNotEmpty()) { "Media file cannot be empty." }
        require(chunkSizeBytes in MIN_MEDIA_CHUNK_BYTES..MAX_MEDIA_CHUNK_BYTES) {
            "Media chunk size must be between $MIN_MEDIA_CHUNK_BYTES and " +
                "$MAX_MEDIA_CHUNK_BYTES bytes."
        }
        val cleanFileName = normalizeMediaFileName(fileName, mimeType, mediaKind).trim().take(MAX_FILE_NAME_LENGTH)
        require(cleanFileName.isNotEmpty()) { "Media file name is required." }
        require(mimeType.startsWith("image/") ||
            mimeType.startsWith("video/") ||
            mimeType.startsWith("audio/") ||
            mimeType.startsWith("application/pdf") ||
            mimeType.endsWith("/pdf") ||
            mimeType == "application/octet-stream" && fileName.endsWith(".pdf", ignoreCase = true)
        ) {
            "Only photo, video, audio, and PDF media are supported."
        }

        val payloadBytes = optimizeTransferBytes(mediaKind, mimeType, bytes)
        val id = transferId()
        val createdAt = now()
        val chunks = payloadBytes
            .asIterableChunks(chunkSizeBytes)
            .mapIndexed { index, chunk ->
                PreparedMediaChunk(
                    transferId = id,
                    chunkIndex = index,
                    offsetBytes = index.toLong() * chunkSizeBytes,
                    plaintext = chunk,
                    plaintextSha256Base64 = sha256(chunk).toBase64()
                )
            }
        val transferKey = ByteArray(TRANSFER_KEY_BYTES)
            .also(secureRandom::nextBytes)
        return PreparedMediaTransfer(
            offer = MediaOfferPayload(
                transferId = id,
                mediaKind = mediaKind,
                fileName = cleanFileName,
                mimeType = mimeType,
                sizeBytes = payloadBytes.size.toLong(),
                chunkSizeBytes = chunkSizeBytes,
                totalChunks = chunks.size,
                fileSha256Base64 = sha256(payloadBytes).toBase64(),
                encryptedTransferKeyBase64 = transferKey.toBase64(),
                createdAtEpochMillis = createdAt,
                expiresAtEpochMillis = createdAt + ttlMillis
            ),
            transferKey = transferKey,
            chunks = chunks,
            previewBytes = payloadBytes
        )
    }

    fun sealChunk(
        transferKey: ByteArray,
        chunk: PreparedMediaChunk,
        totalChunks: Int
    ): MediaChunkPayload {
        require(transferKey.size == TRANSFER_KEY_BYTES) {
            "Media transfer key must be 32 bytes."
        }
        require(chunk.chunkIndex in 0 until totalChunks) {
            "Media chunk index is outside the transfer range."
        }
        val cipher = AesGcmSessionCipher(transferKey)
        val associatedData = chunkAssociatedData(
            transferId = chunk.transferId,
            chunkIndex = chunk.chunkIndex,
            totalChunks = totalChunks,
            offsetBytes = chunk.offsetBytes,
            chunkSizeBytes = chunk.sizeBytes,
            chunkSha256Base64 = chunk.plaintextSha256Base64
        )
        return MediaChunkPayload(
            transferId = chunk.transferId,
            chunkIndex = chunk.chunkIndex,
            totalChunks = totalChunks,
            offsetBytes = chunk.offsetBytes,
            chunkSizeBytes = chunk.sizeBytes,
            chunkSha256Base64 = chunk.plaintextSha256Base64,
            encryptedChunkBase64 = cipher.encrypt(
                plaintext = chunk.plaintext,
                associatedData = associatedData
            ).toBase64()
        )
    }

    fun openChunk(
        transferKey: ByteArray,
        payload: MediaChunkPayload
    ): ByteArray {
        require(transferKey.size == TRANSFER_KEY_BYTES) {
            "Media transfer key must be 32 bytes."
        }
        require(payload.chunkIndex in 0 until payload.totalChunks) {
            "Media chunk index is outside the transfer range."
        }
        val plaintext = AesGcmSessionCipher(transferKey).decrypt(
            ciphertext = payload.encryptedChunkBase64.fromBase64(),
            associatedData = chunkAssociatedData(
                transferId = payload.transferId,
                chunkIndex = payload.chunkIndex,
                totalChunks = payload.totalChunks,
                offsetBytes = payload.offsetBytes,
                chunkSizeBytes = payload.chunkSizeBytes,
                chunkSha256Base64 = payload.chunkSha256Base64
            )
        )
        require(plaintext.size == payload.chunkSizeBytes) {
            "Media chunk size does not match its header."
        }
        require(
            MessageDigest.isEqual(
                sha256(plaintext),
                payload.chunkSha256Base64.fromBase64()
            )
        ) {
            "Media chunk hash verification failed."
        }
        return plaintext
    }

    fun reassembleTransfer(
        offer: MediaOfferPayload,
        chunks: Map<Int, ByteArray>
    ): ByteArray {
        require(chunks.size == offer.totalChunks) {
            "Media transfer is missing chunks."
        }
        val bytes = (0 until offer.totalChunks)
            .map { index ->
                requireNotNull(chunks[index]) {
                    "Media transfer is missing chunk $index."
                }
            }
            .fold(byteArrayOf()) { result, chunk -> result + chunk }
        require(bytes.size.toLong() == offer.sizeBytes) {
            "Reassembled media size does not match the offer."
        }
        require(
            MessageDigest.isEqual(
                sha256(bytes),
                offer.fileSha256Base64.fromBase64()
            )
        ) {
            "Reassembled media hash verification failed."
        }
        return bytes
    }

    fun completePayload(
        offer: MediaOfferPayload,
        completedAtEpochMillis: Long = now()
    ): MediaCompletePayload {
        return MediaCompletePayload(
            transferId = offer.transferId,
            totalChunks = offer.totalChunks,
            fileSha256Base64 = offer.fileSha256Base64,
            completedAtEpochMillis = completedAtEpochMillis
        )
    }

    companion object {
        const val DEFAULT_MEDIA_CHUNK_BYTES = 8 * 1_024
        const val MIN_MEDIA_CHUNK_BYTES = 1 * 1_024
        const val MAX_MEDIA_CHUNK_BYTES = 12 * 1_024
        const val DEFAULT_MEDIA_TTL_MILLIS = 24 * 60 * 60 * 1_000L
        private const val TRANSFER_KEY_BYTES = 32
        private const val MAX_FILE_NAME_LENGTH = 120
    }
}

private fun ByteArray.asIterableChunks(chunkSizeBytes: Int): List<ByteArray> {
    return indices.step(chunkSizeBytes).map { start ->
        copyOfRange(start, minOf(start + chunkSizeBytes, size))
    }
}

private fun optimizeTransferBytes(
    mediaKind: MediaKind,
    mimeType: String,
    bytes: ByteArray
): ByteArray {
    if (mediaKind != MediaKind.Photo || !mimeType.startsWith("image/") || !isAndroidRuntime()) {
        return bytes
    }
    val options = android.graphics.BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    val maxDimension = 1_600
    var sampleSize = 1
    if (options.outWidth > maxDimension || options.outHeight > maxDimension) {
        val widthRatio = kotlin.math.ceil(options.outWidth.toDouble() / maxDimension.toDouble())
        val heightRatio = kotlin.math.ceil(options.outHeight.toDouble() / maxDimension.toDouble())
        sampleSize = maxOf(1, minOf(widthRatio.toInt(), heightRatio.toInt()))
    }
    val samplingOptions = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, samplingOptions)
        ?: return bytes
    val stream = java.io.ByteArrayOutputStream()
    val quality = 0.72f
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, (quality * 100).toInt(), stream)
    bitmap.recycle()
    return stream.toByteArray()
}

private fun isAndroidRuntime(): Boolean {
    return runCatching {
        android.os.Build.VERSION.SDK_INT > 0
    }.getOrDefault(false)
}

private fun normalizeMediaFileName(
    fileName: String,
    mimeType: String,
    mediaKind: MediaKind
): String {
    val trimmed = fileName.trim().ifBlank { "media" }
    if (mediaKind == MediaKind.Photo && !trimmed.lowercase().endsWith(".jpg") &&
        !trimmed.lowercase().endsWith(".jpeg") &&
        !trimmed.lowercase().endsWith(".png") &&
        !trimmed.lowercase().endsWith(".webp")
    ) {
        val suffix = when {
            mimeType.startsWith("image/jpeg") -> ".jpg"
            mimeType.startsWith("image/png") -> ".png"
            else -> ".jpg"
        }
        return trimmed + suffix
    }
    if (mediaKind == MediaKind.Photo && trimmed.lowercase().endsWith(".png")) {
        return trimmed.removeSuffix(".png") + ".jpg"
    }
    return trimmed
}

private fun chunkAssociatedData(
    transferId: String,
    chunkIndex: Int,
    totalChunks: Int,
    offsetBytes: Long,
    chunkSizeBytes: Int,
    chunkSha256Base64: String
): ByteArray {
    return listOf(
        transferId,
        chunkIndex.toString(),
        totalChunks.toString(),
        offsetBytes.toString(),
        chunkSizeBytes.toString(),
        chunkSha256Base64
    ).joinToString(separator = "|").encodeToByteArray()
}

private fun sha256(value: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(value)
}

internal fun ByteArray.toMediaBase64(): String = toBase64()

internal fun String.fromMediaBase64(): ByteArray = fromBase64()

private fun ByteArray.toBase64(): String {
    return Base64.getEncoder().encodeToString(this)
}

private fun String.fromBase64(): ByteArray {
    return Base64.getDecoder().decode(this)
}
