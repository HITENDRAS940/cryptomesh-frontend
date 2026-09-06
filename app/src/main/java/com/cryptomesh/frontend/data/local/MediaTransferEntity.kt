package com.cryptomesh.frontend.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_transfers",
    indices = [
        Index("peerDeviceId"),
        Index("direction"),
        Index("status"),
        Index("expiresAtEpochMillis")
    ]
)
data class MediaTransferEntity(
    @PrimaryKey val transferId: String,
    val peerDeviceId: String,
    val direction: String,
    val mediaKind: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val chunkSizeBytes: Int,
    val totalChunks: Int,
    val fileSha256Base64: String,
    val encryptedTransferKeyBase64: String,
    val sourceUri: String?,
    val outputPath: String?,
    val status: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val lastUpdatedEpochMillis: Long
)
