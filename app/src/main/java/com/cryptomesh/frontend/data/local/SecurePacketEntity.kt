package com.cryptomesh.frontend.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "secure_packets",
    indices = [
        Index("receiverId"),
        Index("status"),
        Index("createdAtEpochMillis")
    ]
)
data class SecurePacketEntity(
    @PrimaryKey val packetId: String,
    val protocolVersion: Int,
    val packetType: String,
    val senderId: String,
    val receiverId: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val nonceBase64: String,
    val payloadHashBase64: String,
    val encryptedPayloadBase64: String,
    val signatureBase64: String,
    val status: String,
    val ownership: String,
    val lastUpdatedEpochMillis: Long
)
