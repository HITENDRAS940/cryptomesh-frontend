package com.cryptomesh.frontend.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "media_chunks",
    primaryKeys = ["transferId", "chunkIndex"],
    foreignKeys = [
        ForeignKey(
            entity = MediaTransferEntity::class,
            parentColumns = ["transferId"],
            childColumns = ["transferId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("transferId"),
        Index("status"),
        Index("packetId")
    ]
)
data class MediaChunkEntity(
    val transferId: String,
    val chunkIndex: Int,
    val packetId: String?,
    val offsetBytes: Long,
    val sizeBytes: Int,
    val chunkSha256Base64: String,
    val encryptedPath: String?,
    val plaintextPath: String?,
    val status: String,
    val lastUpdatedEpochMillis: Long
)
