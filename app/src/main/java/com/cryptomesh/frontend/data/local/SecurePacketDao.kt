package com.cryptomesh.frontend.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SecurePacketDao {
    @Query(
        "SELECT * FROM secure_packets " +
            "ORDER BY createdAtEpochMillis DESC"
    )
    fun observePackets(): Flow<List<SecurePacketEntity>>

    @Query("SELECT * FROM secure_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun getPacket(packetId: String): SecurePacketEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(packet: SecurePacketEntity): Long

    @Query(
        "UPDATE secure_packets SET status = :status, " +
            "lastUpdatedEpochMillis = :updatedAt WHERE packetId = :packetId"
    )
    suspend fun updateStatus(
        packetId: String,
        status: String,
        updatedAt: Long
    ): Int

    @Query(
        "DELETE FROM secure_packets WHERE status = :expiredStatus " +
            "AND lastUpdatedEpochMillis <= :olderThan"
    )
    suspend fun deleteExpired(
        expiredStatus: String,
        olderThan: Long
    ): Int

    @Query("DELETE FROM secure_packets")
    suspend fun clearAll()
}
