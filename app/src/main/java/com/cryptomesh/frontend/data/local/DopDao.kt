package com.cryptomesh.frontend.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DopDao {
    // --- Local pre-key management ---

    @Query("SELECT * FROM dop_local_prekeys ORDER BY epochId DESC, createdAtEpochMillis ASC")
    suspend fun getAllLocalPreKeys(): List<DopLocalPreKeyEntity>

    @Query("SELECT * FROM dop_local_prekeys WHERE preKeyId = :preKeyId LIMIT 1")
    suspend fun getLocalPreKey(preKeyId: String): DopLocalPreKeyEntity?

    @Query("SELECT * FROM dop_local_prekeys WHERE epochId = :epochId")
    suspend fun getLocalPreKeysForEpoch(epochId: Long): List<DopLocalPreKeyEntity>

    @Query("SELECT MAX(epochId) FROM dop_local_prekeys")
    suspend fun getMaxLocalEpochId(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalPreKeys(entities: List<DopLocalPreKeyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalPreKey(entity: DopLocalPreKeyEntity)

    @Query("DELETE FROM dop_local_prekeys WHERE expiresAtEpochMillis <= :nowMillis AND lifecycleState IN ('RETIRED', 'EXPIRED')")
    suspend fun deleteExpiredLocalPreKeys(nowMillis: Long): Int

    @Query("DELETE FROM dop_local_prekeys")
    suspend fun clearAllLocalPreKeys()

    // --- Cached pre-key records (own + relay cache) ---

    @Query("SELECT * FROM dop_cached_prekey_records ORDER BY lastAccessedAtEpochMillis DESC")
    fun observeCachedRecords(): Flow<List<DopCachedPreKeyRecordEntity>>

    @Query("SELECT * FROM dop_cached_prekey_records ORDER BY lastAccessedAtEpochMillis DESC")
    suspend fun getAllCachedRecords(): List<DopCachedPreKeyRecordEntity>

    @Query("SELECT * FROM dop_cached_prekey_records WHERE recipientDeviceId = :recipientDeviceId LIMIT 1")
    suspend fun getCachedRecord(recipientDeviceId: String): DopCachedPreKeyRecordEntity?

    @Query("SELECT * FROM dop_cached_prekey_records WHERE isOwnRecord = 1 LIMIT 1")
    suspend fun getOwnCachedRecord(): DopCachedPreKeyRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedRecord(entity: DopCachedPreKeyRecordEntity)

    @Query("UPDATE dop_cached_prekey_records SET lastAccessedAtEpochMillis = :accessedAtMillis WHERE recipientDeviceId = :recipientDeviceId")
    suspend fun updateCachedRecordAccessTime(recipientDeviceId: String, accessedAtMillis: Long)

    @Query("UPDATE dop_cached_prekey_records SET consumedOneTimePreKeyIdsCsv = :consumedCsv, lastAccessedAtEpochMillis = :accessedAtMillis WHERE recipientDeviceId = :recipientDeviceId")
    suspend fun updateConsumedOneTimePreKeys(
        recipientDeviceId: String,
        consumedCsv: String,
        accessedAtMillis: Long
    )

    @Query("DELETE FROM dop_cached_prekey_records WHERE recipientDeviceId = :recipientDeviceId")
    suspend fun deleteCachedRecord(recipientDeviceId: String): Int

    @Query("DELETE FROM dop_cached_prekey_records WHERE isOwnRecord = 0 AND expiresAtEpochMillis <= :nowMillis")
    suspend fun deleteExpiredCachedRecords(nowMillis: Long): Int

    @Query("DELETE FROM dop_cached_prekey_records")
    suspend fun clearAllCachedRecords()

    // --- DOP Sessions ---

    @Query("SELECT * FROM dop_sessions ORDER BY lastUsedAtEpochMillis DESC")
    suspend fun getAllSessions(): List<DopSessionEntity>

    @Query("SELECT * FROM dop_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): DopSessionEntity?

    @Query("SELECT * FROM dop_sessions WHERE initiationPacketId = :initiationPacketId LIMIT 1")
    suspend fun getSessionByInitiationPacketId(initiationPacketId: String): DopSessionEntity?

    @Query("SELECT * FROM dop_sessions WHERE peerDeviceId = :peerDeviceId ORDER BY lastUsedAtEpochMillis DESC")
    suspend fun getSessionsForPeer(peerDeviceId: String): List<DopSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(entity: DopSessionEntity)

    @Query(
        "UPDATE dop_sessions SET state = :state, confirmedAtEpochMillis = :confirmedAtMillis, " +
            "lastUsedAtEpochMillis = :confirmedAtMillis WHERE sessionId = :sessionId"
    )
    suspend fun updateSessionConfirmed(
        sessionId: String,
        state: String,
        confirmedAtMillis: Long
    ): Int

    @Query("UPDATE dop_sessions SET lastUsedAtEpochMillis = :usedAtMillis WHERE sessionId = :sessionId")
    suspend fun updateSessionLastUsed(sessionId: String, usedAtMillis: Long)

    @Query("DELETE FROM dop_sessions")
    suspend fun clearAllSessions()

    // --- Replay log ---

    @Query("SELECT * FROM dop_replay_log WHERE replayKey = :replayKey LIMIT 1")
    suspend fun getReplayEntry(replayKey: String): DopReplayLogEntity?

    @Query("SELECT * FROM dop_replay_log WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getReplayEntryBySessionId(sessionId: String): DopReplayLogEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReplayEntryIfAbsent(entity: DopReplayLogEntity): Long

    @Query("DELETE FROM dop_replay_log WHERE expiresAtEpochMillis <= :nowMillis")
    suspend fun deleteExpiredReplayEntries(nowMillis: Long): Int

    @Query("DELETE FROM dop_replay_log")
    suspend fun clearAllReplayEntries()
}
