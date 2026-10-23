package com.cryptomesh.frontend.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe in-memory implementation of [DopDao] used for deterministic JVM unit and
 * multi-node mesh integration tests.
 */
class InMemoryDopDao : DopDao {
    private val mutex = Mutex()
    private val localPreKeys = LinkedHashMap<String, DopLocalPreKeyEntity>()
    private val cachedRecords = LinkedHashMap<String, DopCachedPreKeyRecordEntity>()
    private val sessions = LinkedHashMap<String, DopSessionEntity>()
    private val replayLog = LinkedHashMap<String, DopReplayLogEntity>()
    private val cachedRecordsFlow = MutableStateFlow<List<DopCachedPreKeyRecordEntity>>(emptyList())

    override suspend fun getAllLocalPreKeys(): List<DopLocalPreKeyEntity> = mutex.withLock {
        localPreKeys.values.sortedWith(
            compareByDescending<DopLocalPreKeyEntity> { it.epochId }
                .thenBy { it.createdAtEpochMillis }
        )
    }

    override suspend fun getLocalPreKey(preKeyId: String): DopLocalPreKeyEntity? = mutex.withLock {
        localPreKeys[preKeyId]
    }

    override suspend fun getLocalPreKeysForEpoch(epochId: Long): List<DopLocalPreKeyEntity> = mutex.withLock {
        localPreKeys.values.filter { it.epochId == epochId }
    }

    override suspend fun getMaxLocalEpochId(): Long? = mutex.withLock {
        localPreKeys.values.maxOfOrNull { it.epochId }
    }

    override suspend fun upsertLocalPreKeys(entities: List<DopLocalPreKeyEntity>) = mutex.withLock {
        for (entity in entities) {
            localPreKeys[entity.preKeyId] = entity
        }
    }

    override suspend fun upsertLocalPreKey(entity: DopLocalPreKeyEntity) = mutex.withLock {
        localPreKeys[entity.preKeyId] = entity
    }

    override suspend fun deleteExpiredLocalPreKeys(nowMillis: Long): Int = mutex.withLock {
        val toRemove = localPreKeys.values.filter {
            it.expiresAtEpochMillis <= nowMillis &&
                (it.lifecycleState == "RETIRED" || it.lifecycleState == "EXPIRED")
        }.map { it.preKeyId }
        toRemove.forEach { localPreKeys.remove(it) }
        toRemove.size
    }

    override suspend fun clearAllLocalPreKeys() = mutex.withLock {
        localPreKeys.clear()
    }

    override fun observeCachedRecords(): Flow<List<DopCachedPreKeyRecordEntity>> =
        cachedRecordsFlow.asStateFlow()

    override suspend fun getAllCachedRecords(): List<DopCachedPreKeyRecordEntity> = mutex.withLock {
        cachedRecords.values.sortedByDescending { it.lastAccessedAtEpochMillis }
    }

    override suspend fun getCachedRecord(recipientDeviceId: String): DopCachedPreKeyRecordEntity? = mutex.withLock {
        cachedRecords[recipientDeviceId]
    }

    override suspend fun getOwnCachedRecord(): DopCachedPreKeyRecordEntity? = mutex.withLock {
        cachedRecords.values.firstOrNull { it.isOwnRecord }
    }

    override suspend fun upsertCachedRecord(entity: DopCachedPreKeyRecordEntity) = mutex.withLock {
        cachedRecords[entity.recipientDeviceId] = entity
        publishCachedRecordsUnlocked()
    }

    override suspend fun updateCachedRecordAccessTime(
        recipientDeviceId: String,
        accessedAtMillis: Long
    ) = mutex.withLock {
        val existing = cachedRecords[recipientDeviceId] ?: return@withLock
        cachedRecords[recipientDeviceId] = existing.copy(lastAccessedAtEpochMillis = accessedAtMillis)
        publishCachedRecordsUnlocked()
    }

    override suspend fun updateConsumedOneTimePreKeys(
        recipientDeviceId: String,
        consumedCsv: String,
        accessedAtMillis: Long
    ) = mutex.withLock {
        val existing = cachedRecords[recipientDeviceId] ?: return@withLock
        cachedRecords[recipientDeviceId] = existing.copy(
            consumedOneTimePreKeyIdsCsv = consumedCsv,
            lastAccessedAtEpochMillis = accessedAtMillis
        )
        publishCachedRecordsUnlocked()
    }

    override suspend fun deleteCachedRecord(recipientDeviceId: String): Int = mutex.withLock {
        val removed = cachedRecords.remove(recipientDeviceId) != null
        if (removed) {
            publishCachedRecordsUnlocked()
            1
        } else {
            0
        }
    }

    override suspend fun deleteExpiredCachedRecords(nowMillis: Long): Int = mutex.withLock {
        val toRemove = cachedRecords.values.filter {
            !it.isOwnRecord && it.expiresAtEpochMillis <= nowMillis
        }.map { it.recipientDeviceId }
        toRemove.forEach { cachedRecords.remove(it) }
        if (toRemove.isNotEmpty()) {
            publishCachedRecordsUnlocked()
        }
        toRemove.size
    }

    override suspend fun clearAllCachedRecords() = mutex.withLock {
        cachedRecords.clear()
        publishCachedRecordsUnlocked()
    }

    override suspend fun getAllSessions(): List<DopSessionEntity> = mutex.withLock {
        sessions.values.sortedByDescending { it.lastUsedAtEpochMillis }
    }

    override suspend fun getSessionById(sessionId: String): DopSessionEntity? = mutex.withLock {
        sessions[sessionId]
    }

    override suspend fun getSessionByInitiationPacketId(
        initiationPacketId: String
    ): DopSessionEntity? = mutex.withLock {
        sessions.values.firstOrNull { it.initiationPacketId == initiationPacketId }
    }

    override suspend fun getSessionsForPeer(peerDeviceId: String): List<DopSessionEntity> = mutex.withLock {
        sessions.values
            .filter { it.peerDeviceId == peerDeviceId }
            .sortedByDescending { it.lastUsedAtEpochMillis }
    }

    override suspend fun upsertSession(entity: DopSessionEntity) = mutex.withLock {
        sessions[entity.sessionId] = entity
    }

    override suspend fun updateSessionConfirmed(
        sessionId: String,
        state: String,
        confirmedAtMillis: Long
    ): Int = mutex.withLock {
        val existing = sessions[sessionId] ?: return@withLock 0
        sessions[sessionId] = existing.copy(
            state = state,
            confirmedAtEpochMillis = confirmedAtMillis,
            lastUsedAtEpochMillis = confirmedAtMillis
        )
        1
    }

    override suspend fun updateSessionLastUsed(
        sessionId: String,
        usedAtMillis: Long
    ) = mutex.withLock {
        val existing = sessions[sessionId] ?: return@withLock
        sessions[sessionId] = existing.copy(lastUsedAtEpochMillis = usedAtMillis)
    }

    override suspend fun clearAllSessions() = mutex.withLock {
        sessions.clear()
    }

    override suspend fun getReplayEntry(replayKey: String): DopReplayLogEntity? = mutex.withLock {
        replayLog[replayKey]
    }

    override suspend fun getReplayEntryBySessionId(
        sessionId: String
    ): DopReplayLogEntity? = mutex.withLock {
        replayLog.values.firstOrNull { it.sessionId == sessionId }
    }

    override suspend fun insertReplayEntryIfAbsent(entity: DopReplayLogEntity): Long = mutex.withLock {
        if (replayLog.containsKey(entity.replayKey)) {
            -1L
        } else {
            replayLog[entity.replayKey] = entity
            1L
        }
    }

    override suspend fun deleteExpiredReplayEntries(nowMillis: Long): Int = mutex.withLock {
        val toRemove = replayLog.values.filter { it.expiresAtEpochMillis <= nowMillis }.map { it.replayKey }
        toRemove.forEach { replayLog.remove(it) }
        toRemove.size
    }

    override suspend fun clearAllReplayEntries() = mutex.withLock {
        replayLog.clear()
    }

    private fun publishCachedRecordsUnlocked() {
        cachedRecordsFlow.value = cachedRecords.values.sortedByDescending { it.lastAccessedAtEpochMillis }
    }
}
