package com.cryptomesh.frontend.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_identity")
data class LocalIdentityEntity(
    @PrimaryKey val slot: Int = SINGLE_IDENTITY_SLOT,
    val displayName: String,
    val deviceId: String,
    val signingPublicKeyBase64: String,
    val publicKeyPreview: String,
    val createdAtEpochMillis: Long
) {
    companion object {
        const val SINGLE_IDENTITY_SLOT = 1
    }
}
