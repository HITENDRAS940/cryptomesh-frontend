package com.cryptomesh.frontend.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM mesh_groups ORDER BY isBroadcastChannel DESC, lastActivityEpochMillis DESC, createdAtEpochMillis DESC")
    fun observeAllGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM mesh_groups ORDER BY isBroadcastChannel DESC, lastActivityEpochMillis DESC")
    suspend fun getAllGroups(): List<GroupEntity>

    @Query("SELECT * FROM mesh_groups WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupById(groupId: String): GroupEntity?

    @Upsert
    suspend fun upsert(group: GroupEntity)

    @Query("UPDATE mesh_groups SET lastActivityEpochMillis = :timestamp WHERE groupId = :groupId")
    suspend fun updateLastActivity(groupId: String, timestamp: Long)

    @Query("DELETE FROM mesh_groups WHERE groupId = :groupId")
    suspend fun delete(groupId: String)

    @Query("DELETE FROM mesh_groups")
    suspend fun clear()
}
