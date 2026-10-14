package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.crypto.GroupCrypto
import com.cryptomesh.frontend.data.local.GroupDao
import com.cryptomesh.frontend.data.local.GroupEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface GroupRepository {
    val groups: Flow<List<GroupEntity>>

    suspend fun initDefaultChannels(localDeviceId: String)

    suspend fun createGroup(
        name: String,
        creatorDeviceId: String,
        memberDeviceIds: List<String>
    ): GroupEntity

    suspend fun joinGroup(group: GroupEntity)

    suspend fun getGroup(groupId: String): GroupEntity?

    suspend fun updateLastActivity(groupId: String, timestamp: Long)

    suspend fun clearAll()
}

class RoomGroupRepository(
    private val groupDao: GroupDao,
    private val now: () -> Long = System::currentTimeMillis
) : GroupRepository {
    override val groups: Flow<List<GroupEntity>> = groupDao.observeAllGroups()

    override suspend fun initDefaultChannels(localDeviceId: String) {
        val currentTime = now()
        val general = groupDao.getGroupById(CHANNEL_GENERAL_ID)
        if (general == null) {
            val key = GroupCrypto.deriveBroadcastKey(CHANNEL_GENERAL_ID)
            groupDao.upsert(
                GroupEntity(
                    groupId = CHANNEL_GENERAL_ID,
                    groupName = "#general",
                    isBroadcastChannel = true,
                    creatorDeviceId = "SYSTEM",
                    groupKeyBase64 = GroupCrypto.encodeKey(key),
                    membersCsv = "",
                    createdAtEpochMillis = currentTime,
                    lastActivityEpochMillis = currentTime
                )
            )
        }
        val emergency = groupDao.getGroupById(CHANNEL_EMERGENCY_ID)
        if (emergency == null) {
            val key = GroupCrypto.deriveBroadcastKey(CHANNEL_EMERGENCY_ID)
            groupDao.upsert(
                GroupEntity(
                    groupId = CHANNEL_EMERGENCY_ID,
                    groupName = "#emergency",
                    isBroadcastChannel = true,
                    creatorDeviceId = "SYSTEM",
                    groupKeyBase64 = GroupCrypto.encodeKey(key),
                    membersCsv = "",
                    createdAtEpochMillis = currentTime,
                    lastActivityEpochMillis = currentTime
                )
            )
        }
    }

    override suspend fun createGroup(
        name: String,
        creatorDeviceId: String,
        memberDeviceIds: List<String>
    ): GroupEntity {
        val currentTime = now()
        val key = GroupCrypto.generateGroupKey()
        val allMembers = (listOf(creatorDeviceId) + memberDeviceIds).distinct()
        val group = GroupEntity(
            groupId = "GRP-" + UUID.randomUUID().toString().take(8).uppercase(),
            groupName = name.trim(),
            isBroadcastChannel = false,
            creatorDeviceId = creatorDeviceId,
            groupKeyBase64 = GroupCrypto.encodeKey(key),
            membersCsv = allMembers.joinToString(","),
            createdAtEpochMillis = currentTime,
            lastActivityEpochMillis = currentTime
        )
        groupDao.upsert(group)
        return group
    }

    override suspend fun joinGroup(group: GroupEntity) {
        groupDao.upsert(group)
    }

    override suspend fun getGroup(groupId: String): GroupEntity? {
        return groupDao.getGroupById(groupId)
    }

    override suspend fun updateLastActivity(groupId: String, timestamp: Long) {
        groupDao.updateLastActivity(groupId, timestamp)
    }

    override suspend fun clearAll() {
        groupDao.clear()
    }

    companion object {
        const val CHANNEL_GENERAL_ID = "CHANNEL-GENERAL"
        const val CHANNEL_EMERGENCY_ID = "CHANNEL-EMERGENCY"
    }
}

class MemoryGroupRepository(
    private val now: () -> Long = System::currentTimeMillis
) : GroupRepository {
    private val _groups = kotlinx.coroutines.flow.MutableStateFlow<List<GroupEntity>>(emptyList())
    override val groups: Flow<List<GroupEntity>> = _groups

    override suspend fun initDefaultChannels(localDeviceId: String) {
        val currentTime = now()
        val current = _groups.value.toMutableList()
        if (current.none { it.groupId == RoomGroupRepository.CHANNEL_GENERAL_ID }) {
            val key = GroupCrypto.deriveBroadcastKey(RoomGroupRepository.CHANNEL_GENERAL_ID)
            current.add(
                GroupEntity(
                    groupId = RoomGroupRepository.CHANNEL_GENERAL_ID,
                    groupName = "#general",
                    isBroadcastChannel = true,
                    creatorDeviceId = "SYSTEM",
                    groupKeyBase64 = GroupCrypto.encodeKey(key),
                    membersCsv = "",
                    createdAtEpochMillis = currentTime,
                    lastActivityEpochMillis = currentTime
                )
            )
        }
        if (current.none { it.groupId == RoomGroupRepository.CHANNEL_EMERGENCY_ID }) {
            val key = GroupCrypto.deriveBroadcastKey(RoomGroupRepository.CHANNEL_EMERGENCY_ID)
            current.add(
                GroupEntity(
                    groupId = RoomGroupRepository.CHANNEL_EMERGENCY_ID,
                    groupName = "#emergency",
                    isBroadcastChannel = true,
                    creatorDeviceId = "SYSTEM",
                    groupKeyBase64 = GroupCrypto.encodeKey(key),
                    membersCsv = "",
                    createdAtEpochMillis = currentTime,
                    lastActivityEpochMillis = currentTime
                )
            )
        }
        _groups.value = current
    }

    override suspend fun createGroup(
        name: String,
        creatorDeviceId: String,
        memberDeviceIds: List<String>
    ): GroupEntity {
        val currentTime = now()
        val key = GroupCrypto.generateGroupKey()
        val allMembers = (listOf(creatorDeviceId) + memberDeviceIds).distinct()
        val group = GroupEntity(
            groupId = "GRP-" + java.util.UUID.randomUUID().toString().take(8).uppercase(),
            groupName = name.trim(),
            isBroadcastChannel = false,
            creatorDeviceId = creatorDeviceId,
            groupKeyBase64 = GroupCrypto.encodeKey(key),
            membersCsv = allMembers.joinToString(","),
            createdAtEpochMillis = currentTime,
            lastActivityEpochMillis = currentTime
        )
        _groups.value = (_groups.value.filter { it.groupId != group.groupId } + group)
        return group
    }

    override suspend fun joinGroup(group: GroupEntity) {
        _groups.value = (_groups.value.filter { it.groupId != group.groupId } + group)
    }

    override suspend fun getGroup(groupId: String): GroupEntity? {
        return _groups.value.firstOrNull { it.groupId == groupId }
    }

    override suspend fun updateLastActivity(groupId: String, timestamp: Long) {
        _groups.value = _groups.value.map {
            if (it.groupId == groupId) it.copy(lastActivityEpochMillis = timestamp) else it
        }
    }

    override suspend fun clearAll() {
        _groups.value = emptyList()
    }
}
