package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.data.local.SecurePacketDao
import com.cryptomesh.frontend.data.local.SecurePacketEntity
import com.cryptomesh.frontend.protocol.SecurePacketEnvelope
import com.cryptomesh.frontend.protocol.SecurePacketType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePacketRepositoryTest {
    @Test
    fun packetIdMakesLocalStorageDuplicateSafe() = runBlocking {
        val repository = RoomSecurePacketRepository(
            packetDao = FakeSecurePacketDao(),
            now = { 2000L }
        )
        val packet = fixtureEnvelope("packet-1")

        assertTrue(
            repository.storeIfAbsent(
                packet,
                PersistedPacketStatus.Queued,
                PacketOwnership.Own
            )
        )
        assertFalse(
            repository.storeIfAbsent(
                packet,
                PersistedPacketStatus.Queued,
                PacketOwnership.Own
            )
        )
        assertEquals(1, repository.packets.first().size)
    }

    @Test
    fun statusUpdatesAndExpiredCleanupArePersisted() = runBlocking {
        val repository = RoomSecurePacketRepository(
            packetDao = FakeSecurePacketDao(),
            now = { 3000L }
        )
        repository.storeIfAbsent(
            fixtureEnvelope("packet-1"),
            PersistedPacketStatus.Queued,
            PacketOwnership.Relay
        )

        assertTrue(
            repository.updateStatus(
                "packet-1",
                PersistedPacketStatus.Expired
            )
        )
        assertEquals(
            PersistedPacketStatus.Expired,
            repository.packets.first().single().status
        )
        assertEquals(1, repository.cleanupExpired(3000L))
        assertTrue(repository.packets.first().isEmpty())
    }

    @Test
    fun clearAllRemovesEveryEncryptedPacket() = runBlocking {
        val repository = RoomSecurePacketRepository(
            packetDao = FakeSecurePacketDao()
        )
        repository.storeIfAbsent(
            fixtureEnvelope("packet-1"),
            PersistedPacketStatus.Queued,
            PacketOwnership.Own
        )
        repository.storeIfAbsent(
            fixtureEnvelope("packet-2"),
            PersistedPacketStatus.Queued,
            PacketOwnership.Own
        )

        repository.clearAll()

        assertTrue(repository.packets.first().isEmpty())
    }
}

private class FakeSecurePacketDao : SecurePacketDao {
    private val state = MutableStateFlow<List<SecurePacketEntity>>(emptyList())

    override fun observePackets(): Flow<List<SecurePacketEntity>> = state

    override suspend fun getPacket(
        packetId: String
    ): SecurePacketEntity? {
        return state.value.firstOrNull { it.packetId == packetId }
    }

    override suspend fun insertIfAbsent(
        packet: SecurePacketEntity
    ): Long {
        if (state.value.any { it.packetId == packet.packetId }) return -1L
        state.value = listOf(packet) + state.value
        return 1L
    }

    override suspend fun updateStatus(
        packetId: String,
        status: String,
        updatedAt: Long
    ): Int {
        if (state.value.none { it.packetId == packetId }) return 0
        state.value = state.value.map {
            if (it.packetId == packetId) {
                it.copy(
                    status = status,
                    lastUpdatedEpochMillis = updatedAt
                )
            } else {
                it
            }
        }
        return 1
    }

    override suspend fun deleteExpired(
        expiredStatus: String,
        olderThan: Long
    ): Int {
        val removed = state.value.count {
            it.status == expiredStatus &&
                it.lastUpdatedEpochMillis <= olderThan
        }
        state.value = state.value.filterNot {
            it.status == expiredStatus &&
                it.lastUpdatedEpochMillis <= olderThan
        }
        return removed
    }

    override suspend fun clearAll() {
        state.value = emptyList()
    }
}

private fun fixtureEnvelope(id: String): SecurePacketEnvelope {
    return SecurePacketEnvelope(
        protocolVersion = 1,
        packetId = id,
        packetType = SecurePacketType.Message,
        senderId = "CM-SENDER",
        receiverId = "CM-RECEIVER",
        createdAtEpochMillis = 1000L,
        expiresAtEpochMillis = 5000L,
        nonceBase64 = "bm9uY2U=",
        payloadHashBase64 = "aGFzaA==",
        encryptedPayloadBase64 = "Y2lwaGVydGV4dA==",
        signatureBase64 = "c2lnbmF0dXJl"
    )
}
