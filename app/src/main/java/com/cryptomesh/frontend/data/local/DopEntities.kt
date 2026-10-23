package com.cryptomesh.frontend.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dop_local_prekeys",
    indices = [
        Index("epochId"),
        Index("lifecycleState"),
        Index("expiresAtEpochMillis")
    ]
)
data class DopLocalPreKeyEntity(
    @PrimaryKey val preKeyId: String,
    val epochId: Long,
    val preKeyType: String,
    val publicKeyBase64: String,
    val wrappedPrivateKeyBase64: String?,
    val lifecycleState: String,
    val usageCount: Int,
    val firstUsedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val retiredAtEpochMillis: Long?
)

@Entity(
    tableName = "dop_cached_prekey_records",
    indices = [
        Index("epochId"),
        Index("expiresAtEpochMillis"),
        Index("lastAccessedAtEpochMillis"),
        Index("isOwnRecord")
    ]
)
data class DopCachedPreKeyRecordEntity(
    @PrimaryKey val recipientDeviceId: String,
    val recordId: String,
    val recipientDisplayName: String,
    val recipientSigningPublicKeyBase64: String,
    val recipientIdentityFingerprintHex: String,
    val epochId: Long,
    val signedPreKeyId: String,
    val serializedRecordJson: String,
    val recordDigestHex: String,
    val isOwnRecord: Boolean,
    val sourcePeerDeviceId: String?,
    val validFromEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val cachedAtEpochMillis: Long,
    val lastAccessedAtEpochMillis: Long,
    val consumedOneTimePreKeyIdsCsv: String
) {
    fun consumedOneTimePreKeyIds(): Set<String> {
        if (consumedOneTimePreKeyIdsCsv.isBlank()) return emptySet()
        return consumedOneTimePreKeyIdsCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}

@Entity(
    tableName = "dop_sessions",
    indices = [
        Index("peerDeviceId"),
        Index("state"),
        Index("initiationPacketId"),
        Index("lastUsedAtEpochMillis")
    ]
)
data class DopSessionEntity(
    @PrimaryKey val sessionId: String,
    val peerDeviceId: String,
    val peerDisplayName: String,
    val peerSigningPublicKeyBase64: String,
    val peerIdentityFingerprintHex: String,
    val role: String,
    val state: String,
    val recordId: String,
    val epochId: Long,
    val signedPreKeyId: String,
    val oneTimePreKeyId: String?,
    val transcriptHashHex: String,
    val wrappedSessionKeyBase64: String,
    val wrappedConfirmationKeyBase64: String,
    val initiationPacketId: String,
    val createdAtEpochMillis: Long,
    val confirmedAtEpochMillis: Long?,
    val lastUsedAtEpochMillis: Long
)

@Entity(
    tableName = "dop_replay_log",
    indices = [
        Index("sessionId"),
        Index("packetId"),
        Index("expiresAtEpochMillis")
    ]
)
data class DopReplayLogEntity(
    @PrimaryKey val replayKey: String,
    val sessionId: String,
    val packetId: String,
    val senderDeviceId: String,
    val signedPreKeyId: String,
    val oneTimePreKeyId: String?,
    val transcriptHashHex: String,
    val receivedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
)
