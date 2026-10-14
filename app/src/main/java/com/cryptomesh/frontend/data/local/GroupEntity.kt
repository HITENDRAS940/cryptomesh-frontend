package com.cryptomesh.frontend.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mesh_groups",
    indices = [
        Index("isBroadcastChannel"),
        Index("createdAtEpochMillis")
    ]
)
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val groupName: String,
    val isBroadcastChannel: Boolean,
    val creatorDeviceId: String,
    val groupKeyBase64: String,
    val membersCsv: String,
    val createdAtEpochMillis: Long,
    val lastActivityEpochMillis: Long
) {
    fun getMemberList(): List<String> {
        return membersCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
