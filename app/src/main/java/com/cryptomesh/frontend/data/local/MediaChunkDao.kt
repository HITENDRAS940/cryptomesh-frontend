package com.cryptomesh.frontend.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaChunkDao {
    @Query(
        "SELECT * FROM media_chunks " +
            "ORDER BY transferId ASC, chunkIndex ASC"
    )
    fun observeChunks(): Flow<List<MediaChunkEntity>>

    @Query(
        "SELECT * FROM media_chunks WHERE transferId = :transferId " +
            "ORDER BY chunkIndex ASC"
    )
    suspend fun chunksForTransfer(transferId: String): List<MediaChunkEntity>

    @Query(
        "SELECT * FROM media_chunks WHERE transferId = :transferId " +
            "AND chunkIndex = :chunkIndex LIMIT 1"
    )
    suspend fun getChunk(
        transferId: String,
        chunkIndex: Int
    ): MediaChunkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunk(chunk: MediaChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunks(chunks: List<MediaChunkEntity>)

    @Query(
        "UPDATE media_chunks SET packetId = :packetId, status = :status, " +
            "encryptedPath = :encryptedPath, plaintextPath = :plaintextPath, " +
            "lastUpdatedEpochMillis = :updatedAt " +
            "WHERE transferId = :transferId AND chunkIndex = :chunkIndex"
    )
    suspend fun updateChunkStorage(
        transferId: String,
        chunkIndex: Int,
        packetId: String?,
        status: String,
        encryptedPath: String?,
        plaintextPath: String?,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE media_chunks SET status = :status, " +
            "lastUpdatedEpochMillis = :updatedAt " +
            "WHERE transferId = :transferId AND chunkIndex = :chunkIndex"
    )
    suspend fun updateChunkStatus(
        transferId: String,
        chunkIndex: Int,
        status: String,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM media_chunks WHERE transferId = :transferId")
    suspend fun deleteChunksForTransfer(transferId: String): Int

    @Query("DELETE FROM media_chunks")
    suspend fun clearChunks()
}
