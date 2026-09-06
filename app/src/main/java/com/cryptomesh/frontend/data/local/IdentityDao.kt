package com.cryptomesh.frontend.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {
    @Query("SELECT * FROM local_identity WHERE slot = 1 LIMIT 1")
    fun observeIdentity(): Flow<LocalIdentityEntity?>

    @Query("SELECT * FROM local_identity WHERE slot = 1 LIMIT 1")
    suspend fun getIdentity(): LocalIdentityEntity?

    @Upsert
    suspend fun upsert(identity: LocalIdentityEntity)

    @Query("DELETE FROM local_identity")
    suspend fun clear()
}
