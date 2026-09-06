package com.cryptomesh.frontend.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaTransferDao {
    @Query(
        "SELECT * FROM media_transfers " +
            "ORDER BY lastUpdatedEpochMillis DESC"
    )
    fun observeTransfers(): Flow<List<MediaTransferEntity>>

    @Query(
        "SELECT * FROM media_transfers WHERE transferId = :transferId LIMIT 1"
    )
    suspend fun getTransfer(transferId: String): MediaTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransfer(transfer: MediaTransferEntity)

    @Query(
        "UPDATE media_transfers SET status = :status, " +
            "lastUpdatedEpochMillis = :updatedAt " +
            "WHERE transferId = :transferId"
    )
    suspend fun updateTransferStatus(
        transferId: String,
        status: String,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM media_transfers WHERE transferId = :transferId")
    suspend fun deleteTransfer(transferId: String): Int

    @Query("DELETE FROM media_transfers")
    suspend fun clearTransfers()
}
