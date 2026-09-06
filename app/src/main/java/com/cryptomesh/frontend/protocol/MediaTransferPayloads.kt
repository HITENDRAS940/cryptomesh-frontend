package com.cryptomesh.frontend.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class MediaKind {
    Photo,
    Video,
    Audio
}

@Serializable
enum class MediaRejectReason {
    UserDeclined,
    UnsupportedMediaType,
    FileTooLarge,
    InsufficientStorage,
    TransferExpired,
    IntegrityCheckFailed,
    Unknown
}

@Serializable
data class MediaOfferPayload(
    val transferId: String,
    val mediaKind: MediaKind,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val chunkSizeBytes: Int,
    val totalChunks: Int,
    val fileSha256Base64: String,
    val encryptedTransferKeyBase64: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
)

@Serializable
data class MediaAcceptPayload(
    val transferId: String,
    val acceptedAtEpochMillis: Long,
    val requestedChunkIndexes: List<Int> = emptyList()
)

@Serializable
data class MediaRejectPayload(
    val transferId: String,
    val rejectedAtEpochMillis: Long,
    val reason: MediaRejectReason,
    val message: String? = null
)

@Serializable
data class MediaChunkPayload(
    val transferId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val offsetBytes: Long,
    val chunkSizeBytes: Int,
    val chunkSha256Base64: String,
    val encryptedChunkBase64: String
)

@Serializable
data class MediaChunkAcknowledgementPayload(
    val transferId: String,
    val acknowledgedChunkIndexes: List<Int>,
    val missingChunkIndexes: List<Int> = emptyList(),
    val receivedAtEpochMillis: Long
)

@Serializable
data class MediaCompletePayload(
    val transferId: String,
    val totalChunks: Int,
    val fileSha256Base64: String,
    val completedAtEpochMillis: Long
)
