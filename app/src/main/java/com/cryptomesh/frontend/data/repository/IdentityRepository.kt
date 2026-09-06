package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.crypto.IdentityKeyStore
import com.cryptomesh.frontend.data.local.IdentityDao
import com.cryptomesh.frontend.data.local.LocalIdentityEntity
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface IdentityRepository {
    val identity: Flow<LocalIdentity?>

    suspend fun createIdentity(displayName: String): LocalIdentity

    suspend fun clearIdentity()
}

class RoomIdentityRepository(
    private val identityDao: IdentityDao,
    private val keyStore: IdentityKeyStore,
    private val now: () -> Long = System::currentTimeMillis
) : IdentityRepository {
    override val identity: Flow<LocalIdentity?> =
        identityDao.observeIdentity().map { entity ->
            entity?.let { reconcileKeyMaterial(it).toUiModel() }
        }

    override suspend fun createIdentity(displayName: String): LocalIdentity {
        val cleanName = displayName.trim().ifBlank { "CryptoMesh User" }
        val keyMaterial = keyStore.getOrCreate()
        val entity = LocalIdentityEntity(
            displayName = cleanName,
            deviceId = deviceIdFor(keyMaterial.publicKeyBytes),
            signingPublicKeyBase64 = Base64.getEncoder()
                .encodeToString(keyMaterial.publicKeyBytes),
            publicKeyPreview = keyMaterial.publicKeyPreview,
            createdAtEpochMillis = now()
        )
        identityDao.upsert(entity)
        return entity.toUiModel()
    }

    override suspend fun clearIdentity() {
        identityDao.clear()
        keyStore.clear()
    }

    private suspend fun reconcileKeyMaterial(
        entity: LocalIdentityEntity
    ): LocalIdentityEntity {
        val keyMaterial = keyStore.getOrCreate()
        val currentPublicKey = Base64.getEncoder()
            .encodeToString(keyMaterial.publicKeyBytes)
        if (entity.signingPublicKeyBase64 == currentPublicKey) {
            return entity
        }

        return entity.copy(
            deviceId = deviceIdFor(keyMaterial.publicKeyBytes),
            signingPublicKeyBase64 = currentPublicKey,
            publicKeyPreview = keyMaterial.publicKeyPreview
        ).also { identityDao.upsert(it) }
    }
}

private fun LocalIdentityEntity.toUiModel(): LocalIdentity {
    return LocalIdentity(
        displayName = displayName,
        deviceId = deviceId,
        publicKeyPreview = publicKeyPreview
    )
}

private fun deviceIdFor(publicKeyBytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(publicKeyBytes)
        .take(4)
        .joinToString(separator = "") { byte -> "%02X".format(byte) }
    return "CM-$digest"
}
