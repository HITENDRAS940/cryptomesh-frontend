package com.cryptomesh.frontend.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRepositoryTest {

    @Test
    fun initDefaultChannelsCreatesGeneralAndEmergencyChannels() = runBlocking {
        val repository = MemoryGroupRepository()
        repository.initDefaultChannels(localDeviceId = "CM-ALICE")

        val list = repository.groups.first()
        assertEquals(2, list.size)

        val general = repository.getGroup(RoomGroupRepository.CHANNEL_GENERAL_ID)
        assertNotNull(general)
        assertEquals("#general", general?.groupName)
        assertTrue(general?.isBroadcastChannel == true)

        val emergency = repository.getGroup(RoomGroupRepository.CHANNEL_EMERGENCY_ID)
        assertNotNull(emergency)
        assertEquals("#emergency", emergency?.groupName)
        assertTrue(emergency?.isBroadcastChannel == true)
    }

    @Test
    fun createGroupStoresMemberCsvAndGeneratesUniqueId() = runBlocking {
        val repository = MemoryGroupRepository()
        val created = repository.createGroup(
            name = "Team Delta",
            creatorDeviceId = "CM-ALICE",
            memberDeviceIds = listOf("CM-BOB", "CM-CHARLIE")
        )

        assertTrue(created.groupId.startsWith("GRP-"))
        assertEquals("Team Delta", created.groupName)
        assertFalse(created.isBroadcastChannel)
        assertEquals("CM-ALICE", created.creatorDeviceId)
        assertEquals("CM-ALICE,CM-BOB,CM-CHARLIE", created.membersCsv)

        val retrieved = repository.getGroup(created.groupId)
        assertEquals(created, retrieved)
    }

    @Test
    fun updateLastActivityUpdatesTimestamp() = runBlocking {
        val repository = MemoryGroupRepository { 1000L }
        val created = repository.createGroup(
            name = "Team Epsilon",
            creatorDeviceId = "CM-ALICE",
            memberDeviceIds = emptyList()
        )
        assertEquals(1000L, created.lastActivityEpochMillis)

        repository.updateLastActivity(created.groupId, 5000L)
        val updated = repository.getGroup(created.groupId)
        assertEquals(5000L, updated?.lastActivityEpochMillis)
    }
}
