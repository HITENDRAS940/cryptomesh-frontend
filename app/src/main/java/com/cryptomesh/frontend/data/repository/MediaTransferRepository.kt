package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.data.local.MediaChunkDao
import com.cryptomesh.frontend.data.local.MediaChunkEntity
import com.cryptomesh.frontend.data.local.MediaTransferDao
import com.cryptomesh.frontend.data.local.MediaTransferEntity
import com.cryptomesh.frontend.protocol.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class MediaTransferDirection {
    Outgoing,
    Incoming,
    Relay
}

enum class MediaTransferStatus {
    Offered,
    Accepted,
    Queued,
    Transferring,
    Receiving,
    Verifying,
    Completed,
    Rejected,
    Failed,
    Expired
}

enum class MediaChunkStatus {
    Pending,
    Queued,
    Sent,
    Received,
    Verified,
    Failed,
    Expired
}

data class StoredMediaTransfer(
    val transferId: String,
    val peerDeviceId: String,
    val direction: MediaTransferDirection,
    val mediaKind: MediaKind,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val chunkSizeBytes: Int,
    val totalChunks: Int,
    val fileSha256Base64: String,
    val encryptedTransferKeyBase64: String,
    val sourceUri: String?,
    val outputPath: String?,
    val status: MediaTransferStatus,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val lastUpdatedEpochMillis: Long,
    val chunks: List<StoredMediaChunk> = emptyList()
) {
    val completedChunks: Int
        get() = chunks.count {
            it.status == MediaChunkStatus.Sent ||
                it.status == MediaChunkStatus.Received ||
                it.status == MediaChunkStatus.Verified
        }
}

data class StoredMediaChunk(
    val transferId: String,
    val chunkIndex: Int,
    val packetId: String?,
    val offsetBytes: Long,
    val sizeBytes: Int,
    val chunkSha256Base64: String,
    val encryptedPath: String?,
    val plaintextPath: String?,
    val status: MediaChunkStatus,
    val lastUpdatedEpochMillis: Long
)

interface MediaTransferRepository {
    val transfers: Flow<List<StoredMediaTransfer>>

    suspend fun upsertTransfer(transfer: StoredMediaTransfer)

    suspend fun updateTransferStatus(
        transferId: String,
        status: MediaTransferStatus
    ): Boolean

    suspend fun getTransfer(transferId: String): StoredMediaTransfer?

    suspend fun upsertChunk(chunk: StoredMediaChunk)

    suspend fun upsertChunks(chunks: List<StoredMediaChunk>)

    suspend fun chunksForTransfer(transferId: String): List<StoredMediaChunk>

    suspend fun updateChunkStorage(
        transferId: String,
        chunkIndex: Int,
        packetId: String?,
        status: MediaChunkStatus,
        encryptedPath: String?,
        plaintextPath: String?
    ): Boolean

    suspend fun updateChunkStatus(
        transferId: String,
        chunkIndex: Int,
        status: MediaChunkStatus
    ): Boolean

    suspend fun deleteTransfer(transferId: String): Boolean

    suspend fun clearAll()
}

class RoomMediaTransferRepository(
    private val transferDao: MediaTransferDao,
    private val chunkDao: MediaChunkDao,
    private val now: () -> Long = System::currentTimeMillis
) : MediaTransferRepository {
    override val transfers: Flow<List<StoredMediaTransfer>> =
        combine(
            transferDao.observeTransfers(),
            chunkDao.observeChunks()
        ) { transfers, chunks ->
            val chunksByTransfer = chunks
                .map(MediaChunkEntity::toModel)
                .groupBy(StoredMediaChunk::transferId)
            transfers.map { transfer ->
                transfer.toModel(
                    chunks = chunksByTransfer[transfer.transferId]
                        .orEmpty()
                        .sortedBy(StoredMediaChunk::chunkIndex)
                )
            }
        }

    override suspend fun upsertTransfer(transfer: StoredMediaTransfer) {
        transferDao.upsertTransfer(transfer.toEntity(now()))
    }

    override suspend fun updateTransferStatus(
        transferId: String,
        status: MediaTransferStatus
    ): Boolean {
        return transferDao.updateTransferStatus(
            transferId = transferId,
            status = status.name,
            updatedAt = now()
        ) > 0
    }

    override suspend fun getTransfer(
        transferId: String
    ): StoredMediaTransfer? {
        val transfer = transferDao.getTransfer(transferId) ?: return null
        return transfer.toModel(chunksForTransfer(transferId))
    }

    override suspend fun upsertChunk(chunk: StoredMediaChunk) {
        chunkDao.upsertChunk(chunk.toEntity(now()))
    }

    override suspend fun upsertChunks(chunks: List<StoredMediaChunk>) {
        chunkDao.upsertChunks(chunks.map { it.toEntity(now()) })
    }

    override suspend fun chunksForTransfer(
        transferId: String
    ): List<StoredMediaChunk> {
        return chunkDao.chunksForTransfer(transferId)
            .map(MediaChunkEntity::toModel)
    }

    override suspend fun updateChunkStorage(
        transferId: String,
        chunkIndex: Int,
        packetId: String?,
        status: MediaChunkStatus,
        encryptedPath: String?,
        plaintextPath: String?
    ): Boolean {
        return chunkDao.updateChunkStorage(
            transferId = transferId,
            chunkIndex = chunkIndex,
            packetId = packetId,
            status = status.name,
            encryptedPath = encryptedPath,
            plaintextPath = plaintextPath,
            updatedAt = now()
        ) > 0
    }

    override suspend fun updateChunkStatus(
        transferId: String,
        chunkIndex: Int,
        status: MediaChunkStatus
    ): Boolean {
        return chunkDao.updateChunkStatus(
            transferId = transferId,
            chunkIndex = chunkIndex,
            status = status.name,
            updatedAt = now()
        ) > 0
    }

    override suspend fun deleteTransfer(transferId: String): Boolean {
        return transferDao.deleteTransfer(transferId) > 0
    }

    override suspend fun clearAll() {
        chunkDao.clearChunks()
        transferDao.clearTransfers()
    }
}

private fun StoredMediaTransfer.toEntity(
    updatedAt: Long
): MediaTransferEntity {
    return MediaTransferEntity(
        transferId = transferId,
        peerDeviceId = peerDeviceId,
        direction = direction.name,
        mediaKind = mediaKind.name,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        chunkSizeBytes = chunkSizeBytes,
        totalChunks = totalChunks,
        fileSha256Base64 = fileSha256Base64,
        encryptedTransferKeyBase64 = encryptedTransferKeyBase64,
        sourceUri = sourceUri,
        outputPath = outputPath,
        status = status.name,
        createdAtEpochMillis = createdAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        lastUpdatedEpochMillis = updatedAt
    )
}

private fun MediaTransferEntity.toModel(
    chunks: List<StoredMediaChunk>
): StoredMediaTransfer {
    return StoredMediaTransfer(
        transferId = transferId,
        peerDeviceId = peerDeviceId,
        direction = MediaTransferDirection.valueOf(direction),
        mediaKind = MediaKind.valueOf(mediaKind),
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        chunkSizeBytes = chunkSizeBytes,
        totalChunks = totalChunks,
        fileSha256Base64 = fileSha256Base64,
        encryptedTransferKeyBase64 = encryptedTransferKeyBase64,
        sourceUri = sourceUri,
        outputPath = outputPath,
        status = MediaTransferStatus.valueOf(status),
        createdAtEpochMillis = createdAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        lastUpdatedEpochMillis = lastUpdatedEpochMillis,
        chunks = chunks
    )
}

private fun StoredMediaChunk.toEntity(updatedAt: Long): MediaChunkEntity {
    return MediaChunkEntity(
        transferId = transferId,
        chunkIndex = chunkIndex,
        packetId = packetId,
        offsetBytes = offsetBytes,
        sizeBytes = sizeBytes,
        chunkSha256Base64 = chunkSha256Base64,
        encryptedPath = encryptedPath,
        plaintextPath = plaintextPath,
        status = status.name,
        lastUpdatedEpochMillis = updatedAt
    )
}

private fun MediaChunkEntity.toModel(): StoredMediaChunk {
    return StoredMediaChunk(
        transferId = transferId,
        chunkIndex = chunkIndex,
        packetId = packetId,
        offsetBytes = offsetBytes,
        sizeBytes = sizeBytes,
        chunkSha256Base64 = chunkSha256Base64,
        encryptedPath = encryptedPath,
        plaintextPath = plaintextPath,
        status = MediaChunkStatus.valueOf(status),
        lastUpdatedEpochMillis = lastUpdatedEpochMillis
    )
}
