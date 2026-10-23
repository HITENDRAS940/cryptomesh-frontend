package com.cryptomesh.frontend.relay

import com.cryptomesh.frontend.data.local.DopCachedPreKeyRecordEntity
import com.cryptomesh.frontend.data.repository.DopCachedPreKeyEntry
import com.cryptomesh.frontend.data.repository.DopPreKeyRepository
import com.cryptomesh.frontend.logging.MeshLog
import com.cryptomesh.frontend.protocol.dop.DopMetricsCollector
import com.cryptomesh.frontend.protocol.dop.DopPreKeyCatalogEntry
import com.cryptomesh.frontend.protocol.dop.DopPreKeyCodec
import com.cryptomesh.frontend.protocol.dop.DopPreKeyRecord
import com.cryptomesh.frontend.protocol.dop.DopPreKeySyncMessage
import com.cryptomesh.frontend.protocol.dop.DopPreKeyValidator
import com.cryptomesh.frontend.protocol.dop.DopProtocolVersion
import com.cryptomesh.frontend.protocol.dop.DopRecordResolution
import com.cryptomesh.frontend.protocol.dop.DopValidationFailureReason
import com.cryptomesh.frontend.protocol.dop.DopValidationResult
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class DopCacheIngestOutcome {
    data class AcceptedNew(val record: DopPreKeyRecord) : DopCacheIngestOutcome()
    data class ReplacedNewerEpoch(val record: DopPreKeyRecord, val previousEpochId: Long) : DopCacheIngestOutcome()
    data class AcceptedIdentityKeyRotation(val record: DopPreKeyRecord) : DopCacheIngestOutcome()
    data class IgnoredDuplicate(val recordId: String) : DopCacheIngestOutcome()
    data class IgnoredStaleEpoch(val incomingEpochId: Long, val currentEpochId: Long) : DopCacheIngestOutcome()
    data class RejectedConflict(val reason: String) : DopCacheIngestOutcome()
    data class RejectedInvalid(val reason: DopValidationFailureReason, val detail: String) : DopCacheIngestOutcome()
    data class RejectedFloodLimit(val sourcePeerDeviceId: String?) : DopCacheIngestOutcome()

    val isAcceptedOrUpdated: Boolean
        get() = this is AcceptedNew ||
            this is ReplacedNewerEpoch ||
            this is AcceptedIdentityKeyRotation
}

data class DopSyncIngestionResult(
    val outcomesByRecipient: Map<String, DopCacheIngestOutcome>,
    val newlyAcceptedRecords: List<DopPreKeyRecord>,
    val followUpSyncToReply: DopPreKeySyncMessage?
)

/**
 * Manages the opportunistic store-carry-forward relay cache of authenticated [DopPreKeyRecord]s.
 *
 * Enforces validation-first insertion, ECDSA signature verification, deduplication,
 * monotonic epoch replacement, conflict rejection, expiry pruning, LRU bounded capacity,
 * and per-peer flood protection.
 */
class DopRelayCacheManager(
    private val repository: DopPreKeyRepository,
    private val validator: DopPreKeyValidator,
    private val metrics: DopMetricsCollector = DopMetricsCollector(),
    private val maxCachedPeerRecords: Int = DopProtocolVersion.DEFAULT_MAX_RELAY_CACHE_RECORDS,
    private val maxRecordsPerBleSync: Int = DopProtocolVersion.MAX_RECORDS_PER_BLE_SYNC,
    private val maxIngestPerSyncMessage: Int = 16,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val cacheMutex = Mutex()
    private val syncedDigestsByLink = ConcurrentHashMap<String, MutableSet<String>>()

    val cachedRecords: Flow<List<DopCachedPreKeyEntry>>
        get() = repository.cachedRecords

    /**
     * Validates and ingests a single [DopPreKeyRecord] received from a direct peer or relay.
     */
    suspend fun ingestRecord(
        record: DopPreKeyRecord,
        localDeviceId: String? = null,
        sourcePeerDeviceId: String? = null
    ): DopCacheIngestOutcome = cacheMutex.withLock {
        ingestRecordUnlocked(
            record = record,
            localDeviceId = localDeviceId,
            sourcePeerDeviceId = sourcePeerDeviceId,
            nowMillis = now()
        )
    }

    private suspend fun ingestRecordUnlocked(
        record: DopPreKeyRecord,
        localDeviceId: String?,
        sourcePeerDeviceId: String?,
        nowMillis: Long
    ): DopCacheIngestOutcome {
        val valStartNanos = System.nanoTime()
        val validation = validator.validate(
            record = record,
            localDeviceId = localDeviceId,
            evaluationTimeMillis = nowMillis
        )
        val valDurationNanos = System.nanoTime() - valStartNanos
        metrics.recordPreKeyValidation(valDurationNanos, validation)

        if (validation is DopValidationResult.Invalid) {
            MeshLog.warn(
                "DOP-CACHE",
                "Rejected invalid pre-key record '${record.recordId}' for ${record.recipientDeviceId} " +
                    "from source=${sourcePeerDeviceId ?: "unknown"}: ${validation.reason} (${validation.detail})"
            )
            return DopCacheIngestOutcome.RejectedInvalid(validation.reason, validation.detail)
        }
        val validResult = validation as DopValidationResult.Valid

        // Prune expired records before checking existing / capacity
        repository.pruneExpiredState(nowMillis)

        val existingEntity = repository.getCachedRecordEntity(record.recipientDeviceId)
        val existingRecord = existingEntity?.let {
            runCatching { DopPreKeyCodec.decodeRecord(it.serializedRecordJson) }.getOrNull()
        }

        val resolution = validator.resolveAgainstExisting(
            incoming = record,
            existing = existingRecord
        )

        return when (resolution) {
            DopRecordResolution.ACCEPT_NEW -> {
                enforceCapacityBeforeInsertUnlocked(nowMillis)
                persistVerifiedRecordUnlocked(
                    record = record,
                    digestHex = validResult.recordDigestHex,
                    sourcePeerDeviceId = sourcePeerDeviceId,
                    nowMillis = nowMillis,
                    consumedOpkCsv = ""
                )
                metrics.recordCacheInsertion()
                MeshLog.auth(
                    "DOP-CACHE: Cached new verified pre-key record '${record.recordId}' " +
                        "for ${record.recipientDisplayName} (${record.recipientDeviceId}, epoch=${record.epochId})"
                )
                DopCacheIngestOutcome.AcceptedNew(record)
            }

            DopRecordResolution.IGNORE_DUPLICATE_IDENTICAL -> {
                metrics.recordDuplicateRecordIgnored()
                DopCacheIngestOutcome.IgnoredDuplicate(record.recordId)
            }

            DopRecordResolution.ACCEPT_NEWER_EPOCH -> {
                val previousEpoch = existingRecord?.epochId ?: 0L
                persistVerifiedRecordUnlocked(
                    record = record,
                    digestHex = validResult.recordDigestHex,
                    sourcePeerDeviceId = sourcePeerDeviceId,
                    nowMillis = nowMillis,
                    consumedOpkCsv = ""
                )
                metrics.recordCacheInsertion()
                MeshLog.auth(
                    "DOP-CACHE: Replaced pre-key record for ${record.recipientDeviceId} " +
                        "with newer epoch ${record.epochId} (was $previousEpoch)"
                )
                DopCacheIngestOutcome.ReplacedNewerEpoch(record, previousEpoch)
            }

            DopRecordResolution.IGNORE_STALE_OLDER_EPOCH -> {
                metrics.recordStaleRecordIgnored()
                DopCacheIngestOutcome.IgnoredStaleEpoch(
                    incomingEpochId = record.epochId,
                    currentEpochId = existingRecord?.epochId ?: record.epochId
                )
            }

            DopRecordResolution.REJECT_CONFLICTING_RECORD -> {
                metrics.recordConflictingRecordRejected()
                MeshLog.warn(
                    "DOP-CACHE",
                    "Rejected conflicting pre-key record '${record.recordId}' for ${record.recipientDeviceId} " +
                        "at epoch ${record.epochId} (existing recordId=${existingRecord?.recordId})"
                )
                DopCacheIngestOutcome.RejectedConflict(
                    "Conflicting pre-key record at epoch ${record.epochId} for ${record.recipientDeviceId}."
                )
            }

            DopRecordResolution.ACCEPT_IDENTITY_KEY_ROTATION -> {
                persistVerifiedRecordUnlocked(
                    record = record,
                    digestHex = validResult.recordDigestHex,
                    sourcePeerDeviceId = sourcePeerDeviceId,
                    nowMillis = nowMillis,
                    consumedOpkCsv = ""
                )
                metrics.recordCacheInsertion()
                MeshLog.warn(
                    "DOP-CACHE",
                    "Accepted newer-epoch identity key rotation for ${record.recipientDeviceId} (epoch=${record.epochId})"
                )
                DopCacheIngestOutcome.AcceptedIdentityKeyRotation(record)
            }

            DopRecordResolution.REJECT_IDENTITY_KEY_CONFLICT -> {
                metrics.recordConflictingRecordRejected()
                MeshLog.warn(
                    "DOP-CACHE",
                    "Rejected identity key conflict for ${record.recipientDeviceId} from source=${sourcePeerDeviceId ?: "unknown"}"
                )
                DopCacheIngestOutcome.RejectedConflict(
                    "Identity key conflict for ${record.recipientDeviceId}."
                )
            }
        }
    }

    private suspend fun enforceCapacityBeforeInsertUnlocked(nowMillis: Long) {
        val allPeerRecords = repository.getAllCachedRecordEntities()
            .filter { !it.isOwnRecord && it.expiresAtEpochMillis > nowMillis }
        if (allPeerRecords.size < maxCachedPeerRecords) {
            return
        }
        val toEvictCount = (allPeerRecords.size - maxCachedPeerRecords + 1).coerceAtLeast(1)
        val lruOrdered = allPeerRecords.sortedWith(
            compareBy<DopCachedPreKeyRecordEntity> { it.lastAccessedAtEpochMillis }
                .thenBy { it.expiresAtEpochMillis }
        )
        var evicted = 0
        for (victim in lruOrdered.take(toEvictCount)) {
            evicted += repository.deleteCachedRecordEntity(victim.recipientDeviceId)
        }
        if (evicted > 0) {
            metrics.recordCacheEviction(evicted)
        }
    }

    private suspend fun persistVerifiedRecordUnlocked(
        record: DopPreKeyRecord,
        digestHex: String,
        sourcePeerDeviceId: String?,
        nowMillis: Long,
        consumedOpkCsv: String
    ) {
        val serializedJson = DopPreKeyCodec.encodeRecord(record)
        repository.upsertCachedRecordEntity(
            DopCachedPreKeyRecordEntity(
                recipientDeviceId = record.recipientDeviceId,
                recordId = record.recordId,
                recipientDisplayName = record.recipientDisplayName,
                recipientSigningPublicKeyBase64 = record.recipientSigningPublicKeyBase64,
                recipientIdentityFingerprintHex = record.recipientIdentityFingerprintHex,
                epochId = record.epochId,
                signedPreKeyId = record.signedPreKeyId,
                serializedRecordJson = serializedJson,
                recordDigestHex = digestHex,
                isOwnRecord = false,
                sourcePeerDeviceId = sourcePeerDeviceId,
                validFromEpochMillis = record.validFromEpochMillis,
                expiresAtEpochMillis = record.expiresAtEpochMillis,
                cachedAtEpochMillis = nowMillis,
                lastAccessedAtEpochMillis = nowMillis,
                consumedOneTimePreKeyIdsCsv = consumedOpkCsv
            )
        )
    }

    /**
     * Retrieves a verified, non-expired [DopCachedPreKeyEntry] for [recipientDeviceId],
     * re-validating its signature and expiry before returning it for session initiation.
     */
    suspend fun getVerifiedRecordForRecipient(
        recipientDeviceId: String,
        localDeviceId: String? = null
    ): DopCachedPreKeyEntry? = cacheMutex.withLock {
        val nowMillis = now()
        val entity = repository.getCachedRecordEntity(recipientDeviceId)
        if (entity == null) {
            metrics.recordCacheMiss()
            return@withLock null
        }
        val record = runCatching {
            DopPreKeyCodec.decodeRecord(entity.serializedRecordJson)
        }.getOrNull()
        if (record == null) {
            repository.deleteCachedRecordEntity(recipientDeviceId)
            metrics.recordCacheMiss()
            return@withLock null
        }
        val validation = validator.validate(
            record = record,
            localDeviceId = localDeviceId,
            evaluationTimeMillis = nowMillis
        )
        if (!validation.isValid) {
            repository.deleteCachedRecordEntity(recipientDeviceId)
            metrics.recordCacheMiss()
            return@withLock null
        }
        repository.touchCachedRecord(recipientDeviceId, nowMillis)
        metrics.recordCacheHit()
        return@withLock DopCachedPreKeyEntry(
            record = record,
            isOwnRecord = entity.isOwnRecord,
            sourcePeerDeviceId = entity.sourcePeerDeviceId,
            cachedAtEpochMillis = entity.cachedAtEpochMillis,
            lastAccessedAtEpochMillis = nowMillis,
            consumedOneTimePreKeyIds = entity.consumedOneTimePreKeyIds()
        )
    }

    /**
     * Builds an initial or periodic [DopPreKeySyncMessage] to transmit over an authenticated
     * BLE link to `targetPeerDeviceId`.
     */
    suspend fun buildSyncMessageForPeer(
        localIdentity: LocalIdentity,
        targetPeerDeviceId: String,
        linkId: String? = null,
        requestedRecipientIds: List<String> = emptyList()
    ): DopPreKeySyncMessage = cacheMutex.withLock {
        val nowMillis = now()
        repository.pruneExpiredState(nowMillis)
        val ownRecord = repository.getOrCreateOwnRecord(localIdentity)
        val allEntities = repository.getAllCachedRecordEntities()
            .filter {
                !it.isOwnRecord &&
                    it.recipientDeviceId != localIdentity.deviceId &&
                    it.recipientDeviceId != targetPeerDeviceId &&
                    it.expiresAtEpochMillis > nowMillis
            }

        val validRecords = mutableListOf<DopPreKeyRecord>()
        val catalogEntries = mutableListOf<DopPreKeyCatalogEntry>()

        for (entity in allEntities) {
            val decoded = runCatching {
                DopPreKeyCodec.decodeRecord(entity.serializedRecordJson)
            }.getOrNull() ?: continue
            if (validator.validate(decoded, localIdentity.deviceId, nowMillis).isValid) {
                validRecords.add(decoded)
                catalogEntries.add(decoded.toCatalogEntry())
            } else {
                repository.deleteCachedRecordEntity(entity.recipientDeviceId)
            }
        }

        val sentDigests = linkId?.let {
            syncedDigestsByLink.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() }
        }
        val eligibleToRelay = validRecords
            .filter { record ->
                val digest = DopPreKeyCodec.recordDigestHex(record)
                sentDigests == null || !sentDigests.contains(digest)
            }
            .take(maxRecordsPerBleSync)

        if (sentDigests != null) {
            eligibleToRelay.forEach { sentDigests.add(DopPreKeyCodec.recordDigestHex(it)) }
        }

        val message = DopPreKeySyncMessage(
            senderDeviceId = localIdentity.deviceId,
            ownRecord = ownRecord,
            catalogSummaries = catalogEntries,
            requestedRecipientIds = requestedRecipientIds,
            relayedRecords = eligibleToRelay,
            sentAtEpochMillis = nowMillis
        )
        metrics.recordPreKeySyncMessageSize(
            DopPreKeyCodec.encodeSyncMessage(message).encodeToByteArray().size
        )
        return@withLock message
    }

    /**
     * Processes an incoming [DopPreKeySyncMessage] from a connected peer or relay,
     * validates and caches any new or newer-epoch records, and produces an optional
     * follow-up [DopPreKeySyncMessage] if the remote peer requested specific records or
     * advertised older/missing catalog entries.
     */
    suspend fun processIncomingSyncMessage(
        localIdentity: LocalIdentity,
        sourcePeerDeviceId: String,
        linkId: String? = null,
        syncMessage: DopPreKeySyncMessage
    ): DopSyncIngestionResult = cacheMutex.withLock {
        val nowMillis = now()
        val outcomes = LinkedHashMap<String, DopCacheIngestOutcome>()
        val newlyAccepted = mutableListOf<DopPreKeyRecord>()

        val candidateRecords = buildList {
            syncMessage.ownRecord?.let { add(it) }
            addAll(syncMessage.relayedRecords)
        }

        if (candidateRecords.size > maxIngestPerSyncMessage) {
            MeshLog.warn(
                "DOP-CACHE",
                "Sync message from $sourcePeerDeviceId contained ${candidateRecords.size} records " +
                    "(truncating to flood limit $maxIngestPerSyncMessage)"
            )
        }

        val sentDigests = linkId?.let {
            syncedDigestsByLink.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() }
        }

        for ((index, candidate) in candidateRecords.withIndex()) {
            if (index >= maxIngestPerSyncMessage) {
                outcomes[candidate.recipientDeviceId] =
                    DopCacheIngestOutcome.RejectedFloodLimit(sourcePeerDeviceId)
                continue
            }
            val outcome = ingestRecordUnlocked(
                record = candidate,
                localDeviceId = localIdentity.deviceId,
                sourcePeerDeviceId = sourcePeerDeviceId,
                nowMillis = nowMillis
            )
            outcomes[candidate.recipientDeviceId] = outcome
            if (outcome.isAcceptedOrUpdated) {
                newlyAccepted.add(candidate)
                sentDigests?.add(DopPreKeyCodec.recordDigestHex(candidate))
            }
        }

        // Check if the remote peer advertised catalog summaries that we don't have or that have a newer epoch
        val missingOrNewerFromRemote = mutableListOf<String>()
        for (summary in syncMessage.catalogSummaries) {
            if (summary.recipientDeviceId == localIdentity.deviceId) continue
            if (outcomes.containsKey(summary.recipientDeviceId)) continue
            if (summary.expiresAtEpochMillis <= nowMillis) continue
            val localEntity = repository.getCachedRecordEntity(summary.recipientDeviceId)
            if (localEntity == null || summary.epochId > localEntity.epochId) {
                missingOrNewerFromRemote.add(summary.recipientDeviceId)
            }
        }

        // Check if the remote peer requested specific recipient IDs that we hold in our cache
        val recordsToFulfillRequest = mutableListOf<DopPreKeyRecord>()
        for (requestedId in syncMessage.requestedRecipientIds.distinct()) {
            val entity = repository.getCachedRecordEntity(requestedId) ?: continue
            if (entity.expiresAtEpochMillis <= nowMillis) continue
            val decoded = runCatching {
                DopPreKeyCodec.decodeRecord(entity.serializedRecordJson)
            }.getOrNull() ?: continue
            if (validator.validate(decoded, null, nowMillis).isValid) {
                recordsToFulfillRequest.add(decoded)
            }
        }

        val followUp = if (missingOrNewerFromRemote.isNotEmpty() || recordsToFulfillRequest.isNotEmpty()) {
            DopPreKeySyncMessage(
                senderDeviceId = localIdentity.deviceId,
                ownRecord = null,
                catalogSummaries = emptyList(),
                requestedRecipientIds = missingOrNewerFromRemote,
                relayedRecords = recordsToFulfillRequest.take(maxRecordsPerBleSync),
                sentAtEpochMillis = nowMillis
            )
        } else {
            null
        }

        return@withLock DopSyncIngestionResult(
            outcomesByRecipient = outcomes,
            newlyAcceptedRecords = newlyAccepted,
            followUpSyncToReply = followUp
        )
    }

    fun clearLinkSyncState(linkId: String) {
        syncedDigestsByLink.remove(linkId)
    }
}
