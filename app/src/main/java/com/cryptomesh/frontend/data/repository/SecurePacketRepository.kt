package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.data.local.SecurePacketDao
import com.cryptomesh.frontend.data.local.SecurePacketEntity
import com.cryptomesh.frontend.protocol.SecurePacketEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PacketOwnership {
    Own,
    Relay
}

enum class PersistedPacketStatus {
    Queued,
    Forwarding,
    AwaitingAcknowledgement,
    Acknowledged,
    Failed,
    Expired
}

data class StoredSecurePacket(
    val envelope: SecurePacketEnvelope,
    val status: PersistedPacketStatus,
    val ownership: PacketOwnership,
    val lastUpdatedEpochMillis: Long
)

interface SecurePacketRepository {
    val packets: Flow<List<StoredSecurePacket>>

    suspend fun storeIfAbsent(
        envelope: SecurePacketEnvelope,
        status: PersistedPacketStatus,
        ownership: PacketOwnership
    ): Boolean

    suspend fun updateStatus(
        packetId: String,
        status: PersistedPacketStatus
    ): Boolean

    suspend fun cleanupExpired(olderThanEpochMillis: Long): Int

    suspend fun clearAll()
}

class RoomSecurePacketRepository(
    private val packetDao: SecurePacketDao,
    private val now: () -> Long = System::currentTimeMillis
) : SecurePacketRepository {
    override val packets: Flow<List<StoredSecurePacket>> =
        packetDao.observePackets().map { entities ->
            entities.map(SecurePacketEntity::toModel)
        }

    override suspend fun storeIfAbsent(
        envelope: SecurePacketEnvelope,
        status: PersistedPacketStatus,
        ownership: PacketOwnership
    ): Boolean {
        val insertedRow = packetDao.insertIfAbsent(
            envelope.toEntity(
                status = status,
                ownership = ownership,
                updatedAt = now()
            )
        )
        return insertedRow != INSERT_CONFLICT
    }

    override suspend fun updateStatus(
        packetId: String,
        status: PersistedPacketStatus
    ): Boolean {
        return packetDao.updateStatus(
            packetId = packetId,
            status = status.name,
            updatedAt = now()
        ) > 0
    }

    override suspend fun cleanupExpired(
        olderThanEpochMillis: Long
    ): Int {
        return packetDao.deleteExpired(
            expiredStatus = PersistedPacketStatus.Expired.name,
            olderThan = olderThanEpochMillis
        )
    }

    override suspend fun clearAll() {
        packetDao.clearAll()
    }

    companion object {
        private const val INSERT_CONFLICT = -1L
    }
}

private fun SecurePacketEnvelope.toEntity(
    status: PersistedPacketStatus,
    ownership: PacketOwnership,
    updatedAt: Long
): SecurePacketEntity {
    return SecurePacketEntity(
        packetId = packetId,
        protocolVersion = protocolVersion,
        packetType = packetType.name,
        senderId = senderId,
        receiverId = receiverId,
        createdAtEpochMillis = createdAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        nonceBase64 = nonceBase64,
        payloadHashBase64 = payloadHashBase64,
        encryptedPayloadBase64 = encryptedPayloadBase64,
        signatureBase64 = signatureBase64,
        status = status.name,
        ownership = ownership.name,
        lastUpdatedEpochMillis = updatedAt
    )
}

private fun SecurePacketEntity.toModel(): StoredSecurePacket {
    return StoredSecurePacket(
        envelope = SecurePacketEnvelope(
            protocolVersion = protocolVersion,
            packetId = packetId,
            packetType =
                com.cryptomesh.frontend.protocol.SecurePacketType.valueOf(
                    packetType
                ),
            senderId = senderId,
            receiverId = receiverId,
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            nonceBase64 = nonceBase64,
            payloadHashBase64 = payloadHashBase64,
            encryptedPayloadBase64 = encryptedPayloadBase64,
            signatureBase64 = signatureBase64
        ),
        status = PersistedPacketStatus.valueOf(status),
        ownership = PacketOwnership.valueOf(ownership),
        lastUpdatedEpochMillis = lastUpdatedEpochMillis
    )
}
