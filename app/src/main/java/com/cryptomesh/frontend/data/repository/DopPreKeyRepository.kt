package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.crypto.DopKeyWrappingService
import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.data.local.DopCachedPreKeyRecordEntity
import com.cryptomesh.frontend.data.local.DopDao
import com.cryptomesh.frontend.data.local.DopLocalPreKeyEntity
import com.cryptomesh.frontend.data.local.DopReplayLogEntity
import com.cryptomesh.frontend.data.local.DopSessionEntity
import com.cryptomesh.frontend.logging.MeshLog
import com.cryptomesh.frontend.protocol.dop.DopMetricsCollector
import com.cryptomesh.frontend.protocol.dop.DopOneTimePreKeyEntry
import com.cryptomesh.frontend.protocol.dop.DopPreKeyCodec
import com.cryptomesh.frontend.protocol.dop.DopPreKeyLifecycleState
import com.cryptomesh.frontend.protocol.dop.DopPreKeyRecord
import com.cryptomesh.frontend.protocol.dop.DopPreKeyType
import com.cryptomesh.frontend.protocol.dop.DopProtocolVersion
import com.cryptomesh.frontend.protocol.dop.DopSessionKeyDerivation
import com.cryptomesh.frontend.protocol.dop.UnsignedDopPreKeyRecord
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DopCachedPreKeyEntry(
    val record: DopPreKeyRecord,
    val isOwnRecord: Boolean,
    val sourcePeerDeviceId: String?,
    val cachedAtEpochMillis: Long,
    val lastAccessedAtEpochMillis: Long,
    val consumedOneTimePreKeyIds: Set<String>
)

sealed class ResolvedLocalPreKeyResult {
    data class Available(
        val entity: DopLocalPreKeyEntity,
        val privateKey: PrivateKey,
        val rawPrivateKeyBytes: ByteArray
    ) : ResolvedLocalPreKeyResult() {
        fun wipe() {
            rawPrivateKeyBytes.fill(0)
        }
    }

    data class Retired(
        val preKeyId: String,
        val retiredAtEpochMillis: Long?
    ) : ResolvedLocalPreKeyResult()

    data class Expired(
        val preKeyId: String,
        val expiresAtEpochMillis: Long
    ) : ResolvedLocalPreKeyResult()

    data class NotFound(
        val preKeyId: String
    ) : ResolvedLocalPreKeyResult()
}

interface DopPreKeyRepository {
    val cachedRecords: Flow<List<DopCachedPreKeyEntry>>

    suspend fun getOrCreateOwnRecord(
        identity: LocalIdentity,
        forceRotateEpoch: Boolean = false
    ): DopPreKeyRecord

    suspend fun rotateOwnEpoch(
        identity: LocalIdentity,
        oneTimeKeyCount: Int = DopProtocolVersion.DEFAULT_ONE_TIME_PREKEY_COUNT,
        ttlMillis: Long = DopProtocolVersion.DEFAULT_RECORD_TTL_MILLIS
    ): DopPreKeyRecord

    suspend fun resolveLocalPreKeyPrivate(
        preKeyId: String,
        evaluationTimeMillis: Long
    ): ResolvedLocalPreKeyResult

    suspend fun recordLocalPreKeyUsage(
        preKeyId: String,
        nowMillis: Long
    ): DopPreKeyLifecycleState

    suspend fun retireLocalPreKey(
        preKeyId: String,
        nowMillis: Long
    )

    suspend fun getLocalPreKeyEntity(preKeyId: String): DopLocalPreKeyEntity?

    suspend fun getCachedRecordEntity(recipientDeviceId: String): DopCachedPreKeyRecordEntity?

    suspend fun getAllCachedRecordEntities(): List<DopCachedPreKeyRecordEntity>

    suspend fun upsertCachedRecordEntity(entity: DopCachedPreKeyRecordEntity)

    suspend fun touchCachedRecord(recipientDeviceId: String, accessedAtMillis: Long)

    suspend fun markSenderOneTimePreKeyConsumed(
        recipientDeviceId: String,
        oneTimePreKeyId: String,
        nowMillis: Long
    )

    suspend fun deleteCachedRecordEntity(recipientDeviceId: String): Int

    suspend fun getSessionById(sessionId: String): DopSessionEntity?

    suspend fun getSessionByInitiationPacketId(initiationPacketId: String): DopSessionEntity?

    suspend fun getSessionsForPeer(peerDeviceId: String): List<DopSessionEntity>

    suspend fun getAllSessions(): List<DopSessionEntity>

    suspend fun upsertSession(entity: DopSessionEntity)

    suspend fun confirmSession(sessionId: String, confirmedAtMillis: Long)

    suspend fun touchSession(sessionId: String, usedAtMillis: Long)

    suspend fun getReplayEntry(replayKey: String): DopReplayLogEntity?

    suspend fun getReplayEntryBySessionId(sessionId: String): DopReplayLogEntity?

    suspend fun insertReplayEntryIfAbsent(entity: DopReplayLogEntity): Boolean

    suspend fun pruneExpiredState(nowMillis: Long)

    suspend fun clearAll()
}

class RoomDopPreKeyRepository(
    private val dao: DopDao,
    private val signatureService: PacketSignatureService,
    private val keyWrappingService: DopKeyWrappingService,
    private val metrics: DopMetricsCollector = DopMetricsCollector(),
    private val now: () -> Long = System::currentTimeMillis,
    private val secureRandom: SecureRandom = SecureRandom()
) : DopPreKeyRepository {
    private val mutex = Mutex()

    override val cachedRecords: Flow<List<DopCachedPreKeyEntry>> =
        dao.observeCachedRecords().map { entities ->
            entities.mapNotNull { entity ->
                runCatching {
                    DopCachedPreKeyEntry(
                        record = DopPreKeyCodec.decodeRecord(entity.serializedRecordJson),
                        isOwnRecord = entity.isOwnRecord,
                        sourcePeerDeviceId = entity.sourcePeerDeviceId,
                        cachedAtEpochMillis = entity.cachedAtEpochMillis,
                        lastAccessedAtEpochMillis = entity.lastAccessedAtEpochMillis,
                        consumedOneTimePreKeyIds = entity.consumedOneTimePreKeyIds()
                    )
                }.getOrNull()
            }
        }

    override suspend fun getOrCreateOwnRecord(
        identity: LocalIdentity,
        forceRotateEpoch: Boolean
    ): DopPreKeyRecord = mutex.withLock {
        val currentMillis = now()
        if (!forceRotateEpoch) {
            val existingOwn = dao.getOwnCachedRecord()
            if (
                existingOwn != null &&
                existingOwn.recipientDeviceId == identity.deviceId &&
                existingOwn.expiresAtEpochMillis > currentMillis
            ) {
                val decoded = runCatching {
                    DopPreKeyCodec.decodeRecord(existingOwn.serializedRecordJson)
                }.getOrNull()
                if (decoded != null) {
                    val spkEntity = dao.getLocalPreKey(decoded.signedPreKeyId)
                    if (
                        spkEntity != null &&
                        spkEntity.lifecycleState == DopPreKeyLifecycleState.ACTIVE.name &&
                        spkEntity.wrappedPrivateKeyBase64 != null
                    ) {
                        if (decoded.recipientDisplayName != identity.displayName) {
                            return@withLock resignOwnRecordForDisplayName(
                                existingRecord = decoded,
                                newDisplayName = identity.displayName,
                                nowMillis = currentMillis
                            )
                        }
                        return@withLock decoded
                    }
                }
            }
        }
        return@withLock generateNewEpochUnlocked(
            identity = identity,
            oneTimeKeyCount = DopProtocolVersion.DEFAULT_ONE_TIME_PREKEY_COUNT,
            ttlMillis = DopProtocolVersion.DEFAULT_RECORD_TTL_MILLIS,
            nowMillis = currentMillis
        )
    }

    override suspend fun rotateOwnEpoch(
        identity: LocalIdentity,
        oneTimeKeyCount: Int,
        ttlMillis: Long
    ): DopPreKeyRecord = mutex.withLock {
        generateNewEpochUnlocked(
            identity = identity,
            oneTimeKeyCount = oneTimeKeyCount.coerceIn(0, DopProtocolVersion.MAX_ONE_TIME_PREKEYS_PER_RECORD),
            ttlMillis = ttlMillis,
            nowMillis = now()
        )
    }

    private suspend fun resignOwnRecordForDisplayName(
        existingRecord: DopPreKeyRecord,
        newDisplayName: String,
        nowMillis: Long
    ): DopPreKeyRecord {
        val nextEpochId = (dao.getMaxLocalEpochId() ?: existingRecord.epochId) + 1L
        val activeOpks = existingRecord.oneTimePreKeys.filter { opk ->
            val entity = dao.getLocalPreKey(opk.preKeyId)
            entity != null &&
                entity.lifecycleState == DopPreKeyLifecycleState.ACTIVE.name &&
                entity.wrappedPrivateKeyBase64 != null
        }
        val unsigned = UnsignedDopPreKeyRecord(
            recordId = "DOP-REC-${identityShortTag(existingRecord.recipientDeviceId)}-E$nextEpochId-${UUID.randomUUID().toString().take(8)}",
            recipientDeviceId = existingRecord.recipientDeviceId,
            recipientDisplayName = newDisplayName.take(DopProtocolVersion.MAX_DISPLAY_NAME_LENGTH),
            recipientSigningPublicKeyBase64 = existingRecord.recipientSigningPublicKeyBase64,
            recipientIdentityFingerprintHex = existingRecord.recipientIdentityFingerprintHex,
            epochId = nextEpochId,
            signedPreKeyId = existingRecord.signedPreKeyId,
            signedPreKeyType = DopPreKeyType.SIGNED_EPOCH_PREKEY,
            signedPreKeyPublicKeyBase64 = existingRecord.signedPreKeyPublicKeyBase64,
            oneTimePreKeys = activeOpks,
            createdAtEpochMillis = nowMillis,
            validFromEpochMillis = nowMillis,
            expiresAtEpochMillis = existingRecord.expiresAtEpochMillis
        )
        val signature = signatureService.sign(DopPreKeyCodec.encodeCanonicalUnsigned(unsigned))
        val signedRecord = unsigned.toSigned(Base64.getEncoder().encodeToString(signature))
        val spkEntity = dao.getLocalPreKey(existingRecord.signedPreKeyId)
        if (spkEntity != null) {
            dao.upsertLocalPreKey(spkEntity.copy(epochId = nextEpochId))
        }
        persistOwnRecordEntity(signedRecord, nowMillis)
        return signedRecord
    }

    private suspend fun generateNewEpochUnlocked(
        identity: LocalIdentity,
        oneTimeKeyCount: Int,
        ttlMillis: Long,
        nowMillis: Long
    ): DopPreKeyRecord {
        val startNanos = System.nanoTime()
        val previousEpochId = dao.getMaxLocalEpochId() ?: 0L
        val nextEpochId = previousEpochId + 1L

        // Transition prior epochs: retire any old epochs older than (nextEpochId - 1),
        // and place the immediate previous epoch's signed pre-key into a bounded retention window.
        val allExisting = dao.getAllLocalPreKeys()
        for (existing in allExisting) {
            if (existing.epochId < previousEpochId && existing.lifecycleState != DopPreKeyLifecycleState.RETIRED.name) {
                dao.upsertLocalPreKey(
                    existing.copy(
                        wrappedPrivateKeyBase64 = null,
                        lifecycleState = DopPreKeyLifecycleState.RETIRED.name,
                        retiredAtEpochMillis = nowMillis
                    )
                )
            } else if (existing.epochId == previousEpochId) {
                if (existing.preKeyType == DopPreKeyType.ONE_TIME_PREKEY.name &&
                    existing.lifecycleState == DopPreKeyLifecycleState.ACTIVE.name
                ) {
                    // Unused OPKs from previous epoch enter grace window so in-flight bundles still work
                    dao.upsertLocalPreKey(
                        existing.copy(
                            lifecycleState = DopPreKeyLifecycleState.CONSUMED_GRACE.name,
                            firstUsedAtEpochMillis = existing.firstUsedAtEpochMillis ?: nowMillis,
                            expiresAtEpochMillis = minOf(
                                existing.expiresAtEpochMillis,
                                nowMillis + DopProtocolVersion.PREVIOUS_EPOCH_RETENTION_MILLIS
                            )
                        )
                    )
                } else if (existing.preKeyType == DopPreKeyType.SIGNED_EPOCH_PREKEY.name &&
                    existing.lifecycleState == DopPreKeyLifecycleState.ACTIVE.name
                ) {
                    dao.upsertLocalPreKey(
                        existing.copy(
                            expiresAtEpochMillis = minOf(
                                existing.expiresAtEpochMillis,
                                nowMillis + DopProtocolVersion.PREVIOUS_EPOCH_RETENTION_MILLIS
                            )
                        )
                    )
                }
            }
        }

        val expiresAt = nowMillis + ttlMillis
        val shortTag = identityShortTag(identity.deviceId)
        val spkId = "SPK-$shortTag-E$nextEpochId-${UUID.randomUUID().toString().take(6)}"
        val spkKeyPair = DopSessionKeyDerivation.generateEcKeyPair(secureRandom)
        val spkPublicBase64 = Base64.getEncoder().encodeToString(spkKeyPair.public.encoded)
        val spkPrivateBytes = spkKeyPair.private.encoded
        val wrappedSpkPrivateBase64 = Base64.getEncoder().encodeToString(
            keyWrappingService.wrap(
                plaintext = spkPrivateBytes,
                associatedData = localKeyWrapAad(spkId, nextEpochId, DopPreKeyType.SIGNED_EPOCH_PREKEY)
            )
        )
        spkPrivateBytes.fill(0)

        val localEntities = mutableListOf(
            DopLocalPreKeyEntity(
                preKeyId = spkId,
                epochId = nextEpochId,
                preKeyType = DopPreKeyType.SIGNED_EPOCH_PREKEY.name,
                publicKeyBase64 = spkPublicBase64,
                wrappedPrivateKeyBase64 = wrappedSpkPrivateBase64,
                lifecycleState = DopPreKeyLifecycleState.ACTIVE.name,
                usageCount = 0,
                firstUsedAtEpochMillis = null,
                createdAtEpochMillis = nowMillis,
                expiresAtEpochMillis = expiresAt,
                retiredAtEpochMillis = null
            )
        )

        val opkEntries = mutableListOf<DopOneTimePreKeyEntry>()
        for (index in 1..oneTimeKeyCount) {
            val opkId = "OPK-$shortTag-E$nextEpochId-$index-${UUID.randomUUID().toString().take(6)}"
            val opkKeyPair = DopSessionKeyDerivation.generateEcKeyPair(secureRandom)
            val opkPublicBase64 = Base64.getEncoder().encodeToString(opkKeyPair.public.encoded)
            val opkPrivateBytes = opkKeyPair.private.encoded
            val wrappedOpkPrivateBase64 = Base64.getEncoder().encodeToString(
                keyWrappingService.wrap(
                    plaintext = opkPrivateBytes,
                    associatedData = localKeyWrapAad(opkId, nextEpochId, DopPreKeyType.ONE_TIME_PREKEY)
                )
            )
            opkPrivateBytes.fill(0)

            localEntities.add(
                DopLocalPreKeyEntity(
                    preKeyId = opkId,
                    epochId = nextEpochId,
                    preKeyType = DopPreKeyType.ONE_TIME_PREKEY.name,
                    publicKeyBase64 = opkPublicBase64,
                    wrappedPrivateKeyBase64 = wrappedOpkPrivateBase64,
                    lifecycleState = DopPreKeyLifecycleState.ACTIVE.name,
                    usageCount = 0,
                    firstUsedAtEpochMillis = null,
                    createdAtEpochMillis = nowMillis,
                    expiresAtEpochMillis = expiresAt,
                    retiredAtEpochMillis = null
                )
            )
            opkEntries.add(
                DopOneTimePreKeyEntry(
                    preKeyId = opkId,
                    preKeyType = DopPreKeyType.ONE_TIME_PREKEY,
                    publicKeyBase64 = opkPublicBase64
                )
            )
        }

        dao.upsertLocalPreKeys(localEntities)

        val signingPubKeyBytes = signatureService.publicKeyBytes
        val signingPubKeyBase64 = Base64.getEncoder().encodeToString(signingPubKeyBytes)
        val fingerprintHex = DopPreKeyCodec.identityFingerprintHex(signingPubKeyBytes)
        val recordId = "DOP-REC-$shortTag-E$nextEpochId-${UUID.randomUUID().toString().take(8)}"

        val unsigned = UnsignedDopPreKeyRecord(
            recordId = recordId,
            recipientDeviceId = identity.deviceId,
            recipientDisplayName = identity.displayName.take(DopProtocolVersion.MAX_DISPLAY_NAME_LENGTH),
            recipientSigningPublicKeyBase64 = signingPubKeyBase64,
            recipientIdentityFingerprintHex = fingerprintHex,
            epochId = nextEpochId,
            signedPreKeyId = spkId,
            signedPreKeyType = DopPreKeyType.SIGNED_EPOCH_PREKEY,
            signedPreKeyPublicKeyBase64 = spkPublicBase64,
            oneTimePreKeys = opkEntries,
            createdAtEpochMillis = nowMillis,
            validFromEpochMillis = nowMillis,
            expiresAtEpochMillis = expiresAt
        )
        val signature = signatureService.sign(DopPreKeyCodec.encodeCanonicalUnsigned(unsigned))
        val signedRecord = unsigned.toSigned(Base64.getEncoder().encodeToString(signature))

        val serializedJson = persistOwnRecordEntity(signedRecord, nowMillis)
        val elapsedNanos = System.nanoTime() - startNanos
        metrics.recordPreKeyGeneration(
            durationNanos = elapsedNanos,
            serializedRecordBytes = serializedJson.encodeToByteArray().size
        )
        MeshLog.auth(
            "DOP: Generated and signed pre-key record recordId=$recordId, epoch=$nextEpochId, " +
                "spkId=$spkId, opkCount=${opkEntries.size}"
        )
        return signedRecord
    }

    private suspend fun persistOwnRecordEntity(
        record: DopPreKeyRecord,
        nowMillis: Long
    ): String {
        val serializedJson = DopPreKeyCodec.encodeRecord(record)
        val digestHex = DopPreKeyCodec.recordDigestHex(record)
        dao.upsertCachedRecord(
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
                isOwnRecord = true,
                sourcePeerDeviceId = record.recipientDeviceId,
                validFromEpochMillis = record.validFromEpochMillis,
                expiresAtEpochMillis = record.expiresAtEpochMillis,
                cachedAtEpochMillis = nowMillis,
                lastAccessedAtEpochMillis = nowMillis,
                consumedOneTimePreKeyIdsCsv = ""
            )
        )
        return serializedJson
    }

    override suspend fun resolveLocalPreKeyPrivate(
        preKeyId: String,
        evaluationTimeMillis: Long
    ): ResolvedLocalPreKeyResult = mutex.withLock {
        val entity = dao.getLocalPreKey(preKeyId)
            ?: return@withLock ResolvedLocalPreKeyResult.NotFound(preKeyId)

        if (evaluationTimeMillis > entity.expiresAtEpochMillis ||
            entity.lifecycleState == DopPreKeyLifecycleState.EXPIRED.name
        ) {
            if (entity.wrappedPrivateKeyBase64 != null || entity.lifecycleState != DopPreKeyLifecycleState.EXPIRED.name) {
                dao.upsertLocalPreKey(
                    entity.copy(
                        wrappedPrivateKeyBase64 = null,
                        lifecycleState = DopPreKeyLifecycleState.EXPIRED.name,
                        retiredAtEpochMillis = evaluationTimeMillis
                    )
                )
            }
            return@withLock ResolvedLocalPreKeyResult.Expired(preKeyId, entity.expiresAtEpochMillis)
        }
        if (entity.lifecycleState == DopPreKeyLifecycleState.RETIRED.name || entity.wrappedPrivateKeyBase64 == null) {
            return@withLock ResolvedLocalPreKeyResult.Retired(preKeyId, entity.retiredAtEpochMillis)
        }

        if (
            entity.preKeyType == DopPreKeyType.ONE_TIME_PREKEY.name &&
            entity.lifecycleState == DopPreKeyLifecycleState.CONSUMED_GRACE.name
        ) {
            val firstUsed = entity.firstUsedAtEpochMillis ?: entity.createdAtEpochMillis
            val graceExpired = evaluationTimeMillis - firstUsed > DopProtocolVersion.OPK_COALESCING_GRACE_MILLIS
            val maxUsesReached = entity.usageCount >= DopProtocolVersion.MAX_CONCURRENT_OPK_USES
            if (graceExpired || maxUsesReached) {
                dao.upsertLocalPreKey(
                    entity.copy(
                        wrappedPrivateKeyBase64 = null,
                        lifecycleState = DopPreKeyLifecycleState.RETIRED.name,
                        retiredAtEpochMillis = evaluationTimeMillis
                    )
                )
                return@withLock ResolvedLocalPreKeyResult.Retired(preKeyId, evaluationTimeMillis)
            }
        }

        val keyType = runCatching { DopPreKeyType.valueOf(entity.preKeyType) }
            .getOrDefault(DopPreKeyType.SIGNED_EPOCH_PREKEY)
        val wrappedBytes = Base64.getDecoder().decode(entity.wrappedPrivateKeyBase64)
        val rawPrivateKeyBytes = runCatching {
            keyWrappingService.unwrap(
                wrapped = wrappedBytes,
                associatedData = localKeyWrapAad(entity.preKeyId, entity.epochId, keyType)
            )
        }.getOrNull() ?: return@withLock ResolvedLocalPreKeyResult.Retired(preKeyId, entity.retiredAtEpochMillis)

        val privateKey = runCatching {
            DopSessionKeyDerivation.decodePrivateKey(rawPrivateKeyBytes)
        }.getOrElse {
            rawPrivateKeyBytes.fill(0)
            return@withLock ResolvedLocalPreKeyResult.Retired(preKeyId, entity.retiredAtEpochMillis)
        }

        return@withLock ResolvedLocalPreKeyResult.Available(
            entity = entity,
            privateKey = privateKey,
            rawPrivateKeyBytes = rawPrivateKeyBytes
        )
    }

    override suspend fun recordLocalPreKeyUsage(
        preKeyId: String,
        nowMillis: Long
    ): DopPreKeyLifecycleState = mutex.withLock {
        val entity = dao.getLocalPreKey(preKeyId)
            ?: return@withLock DopPreKeyLifecycleState.RETIRED
        val newUsageCount = entity.usageCount + 1
        val firstUsed = entity.firstUsedAtEpochMillis ?: nowMillis

        if (entity.preKeyType == DopPreKeyType.ONE_TIME_PREKEY.name) {
            val shouldRetire = newUsageCount >= DopProtocolVersion.MAX_CONCURRENT_OPK_USES ||
                (nowMillis - firstUsed > DopProtocolVersion.OPK_COALESCING_GRACE_MILLIS)
            val nextState = if (shouldRetire) {
                DopPreKeyLifecycleState.RETIRED
            } else {
                DopPreKeyLifecycleState.CONSUMED_GRACE
            }
            dao.upsertLocalPreKey(
                entity.copy(
                    wrappedPrivateKeyBase64 = if (shouldRetire) null else entity.wrappedPrivateKeyBase64,
                    lifecycleState = nextState.name,
                    usageCount = newUsageCount,
                    firstUsedAtEpochMillis = firstUsed,
                    retiredAtEpochMillis = if (shouldRetire) nowMillis else entity.retiredAtEpochMillis
                )
            )
            return@withLock nextState
        } else {
            dao.upsertLocalPreKey(
                entity.copy(
                    usageCount = newUsageCount,
                    firstUsedAtEpochMillis = firstUsed
                )
            )
            return@withLock runCatching { DopPreKeyLifecycleState.valueOf(entity.lifecycleState) }
                .getOrDefault(DopPreKeyLifecycleState.ACTIVE)
        }
    }

    override suspend fun retireLocalPreKey(
        preKeyId: String,
        nowMillis: Long
    ) = mutex.withLock {
        val entity = dao.getLocalPreKey(preKeyId) ?: return@withLock
        dao.upsertLocalPreKey(
            entity.copy(
                wrappedPrivateKeyBase64 = null,
                lifecycleState = DopPreKeyLifecycleState.RETIRED.name,
                retiredAtEpochMillis = nowMillis
            )
        )
    }

    override suspend fun getLocalPreKeyEntity(preKeyId: String): DopLocalPreKeyEntity? {
        return dao.getLocalPreKey(preKeyId)
    }

    override suspend fun getCachedRecordEntity(recipientDeviceId: String): DopCachedPreKeyRecordEntity? {
        return dao.getCachedRecord(recipientDeviceId)
    }

    override suspend fun getAllCachedRecordEntities(): List<DopCachedPreKeyRecordEntity> {
        return dao.getAllCachedRecords()
    }

    override suspend fun upsertCachedRecordEntity(entity: DopCachedPreKeyRecordEntity) {
        dao.upsertCachedRecord(entity)
    }

    override suspend fun touchCachedRecord(recipientDeviceId: String, accessedAtMillis: Long) {
        dao.updateCachedRecordAccessTime(recipientDeviceId, accessedAtMillis)
    }

    override suspend fun markSenderOneTimePreKeyConsumed(
        recipientDeviceId: String,
        oneTimePreKeyId: String,
        nowMillis: Long
    ) = mutex.withLock {
        val existing = dao.getCachedRecord(recipientDeviceId) ?: return@withLock
        val updatedSet = existing.consumedOneTimePreKeyIds() + oneTimePreKeyId
        dao.updateConsumedOneTimePreKeys(
            recipientDeviceId = recipientDeviceId,
            consumedCsv = updatedSet.joinToString(","),
            accessedAtMillis = nowMillis
        )
    }

    override suspend fun deleteCachedRecordEntity(recipientDeviceId: String): Int {
        return dao.deleteCachedRecord(recipientDeviceId)
    }

    override suspend fun getSessionById(sessionId: String): DopSessionEntity? {
        return dao.getSessionById(sessionId)
    }

    override suspend fun getSessionByInitiationPacketId(initiationPacketId: String): DopSessionEntity? {
        return dao.getSessionByInitiationPacketId(initiationPacketId)
    }

    override suspend fun getSessionsForPeer(peerDeviceId: String): List<DopSessionEntity> {
        return dao.getSessionsForPeer(peerDeviceId)
    }

    override suspend fun getAllSessions(): List<DopSessionEntity> {
        return dao.getAllSessions()
    }

    override suspend fun upsertSession(entity: DopSessionEntity) {
        dao.upsertSession(entity)
    }

    override suspend fun confirmSession(sessionId: String, confirmedAtMillis: Long) {
        dao.updateSessionConfirmed(
            sessionId = sessionId,
            state = "CONFIRMED",
            confirmedAtMillis = confirmedAtMillis
        )
    }

    override suspend fun touchSession(sessionId: String, usedAtMillis: Long) {
        dao.updateSessionLastUsed(sessionId, usedAtMillis)
    }

    override suspend fun getReplayEntry(replayKey: String): DopReplayLogEntity? {
        return dao.getReplayEntry(replayKey)
    }

    override suspend fun getReplayEntryBySessionId(sessionId: String): DopReplayLogEntity? {
        return dao.getReplayEntryBySessionId(sessionId)
    }

    override suspend fun insertReplayEntryIfAbsent(entity: DopReplayLogEntity): Boolean {
        return dao.insertReplayEntryIfAbsent(entity) != -1L
    }

    override suspend fun pruneExpiredState(nowMillis: Long): Unit = mutex.withLock {
        val allLocal = dao.getAllLocalPreKeys()
        for (key in allLocal) {
            if (nowMillis > key.expiresAtEpochMillis && key.lifecycleState != DopPreKeyLifecycleState.EXPIRED.name) {
                dao.upsertLocalPreKey(
                    key.copy(
                        wrappedPrivateKeyBase64 = null,
                        lifecycleState = DopPreKeyLifecycleState.EXPIRED.name,
                        retiredAtEpochMillis = key.retiredAtEpochMillis ?: nowMillis
                    )
                )
            }
        }
        dao.deleteExpiredCachedRecords(nowMillis)
        dao.deleteExpiredReplayEntries(nowMillis)
    }

    override suspend fun clearAll() = mutex.withLock {
        dao.clearAllLocalPreKeys()
        dao.clearAllCachedRecords()
        dao.clearAllSessions()
        dao.clearAllReplayEntries()
    }

    companion object {
        internal fun localKeyWrapAad(
            preKeyId: String,
            epochId: Long,
            preKeyType: DopPreKeyType
        ): ByteArray {
            return "CryptoMesh-DOP-LocalPreKeyWrap-v1|$preKeyId|$epochId|${preKeyType.name}"
                .encodeToByteArray()
        }

        private fun identityShortTag(deviceId: String): String {
            return deviceId.removePrefix("CM-").take(8)
        }
    }
}
