package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.crypto.IdentityKeyMaterial
import com.cryptomesh.frontend.crypto.IdentityKeyStore
import com.cryptomesh.frontend.crypto.publicKeyPreview
import com.cryptomesh.frontend.data.local.IdentityDao
import com.cryptomesh.frontend.data.local.LocalIdentityEntity
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityRepositoryTest {
    @Test
    fun identityPersistsWithStablePublicKeyAndDeviceId() = runBlocking {
        val dao = FakeIdentityDao()
        val keyStore = FakeIdentityKeyStore()
        val repository = RoomIdentityRepository(
            identityDao = dao,
            keyStore = keyStore,
            now = { 1234L }
        )

        val created = repository.createIdentity("  Alice  ")
        val reloaded = RoomIdentityRepository(
            identityDao = dao,
            keyStore = keyStore
        ).identity.first()

        assertEquals("Alice", created.displayName)
        assertEquals(created, reloaded)
        assertTrue(created.deviceId.matches(Regex("CM-[0-9A-F]{8}")))
        assertEquals(1234L, dao.getIdentity()?.createdAtEpochMillis)

        val renamed = repository.createIdentity("Alice Phone")
        assertEquals(created.deviceId, renamed.deviceId)
        assertEquals(created.publicKeyPreview, renamed.publicKeyPreview)
    }

    @Test
    fun clearingIdentityRemovesDatabaseRowAndKeyMaterial() = runBlocking {
        val dao = FakeIdentityDao()
        val keyStore = FakeIdentityKeyStore()
        val repository = RoomIdentityRepository(dao, keyStore)
        val original = repository.createIdentity("")

        repository.clearIdentity()

        assertNull(repository.identity.first())
        assertEquals(1, keyStore.clearCount)

        val replacement = repository.createIdentity("Replacement")
        assertEquals("CryptoMesh User", original.displayName)
        assertNotEquals(original.deviceId, replacement.deviceId)
    }

    @Test
    fun restoredMetadataIsReconciledWhenPrivateKeyIsMissing() = runBlocking {
        val dao = FakeIdentityDao()
        val keyStore = FakeIdentityKeyStore()
        val repository = RoomIdentityRepository(dao, keyStore)
        val original = repository.createIdentity("Restored user")

        keyStore.clear()

        val reconciled = RoomIdentityRepository(
            dao,
            keyStore
        ).identity.first() ?: error("Expected reconciled identity")

        assertEquals(original.displayName, reconciled.displayName)
        assertNotEquals(original.deviceId, reconciled.deviceId)
        assertEquals(
            reconciled.deviceId,
            dao.getIdentity()?.deviceId
        )
    }
}

private class FakeIdentityDao : IdentityDao {
    private val state = MutableStateFlow<LocalIdentityEntity?>(null)

    override fun observeIdentity(): Flow<LocalIdentityEntity?> = state

    override suspend fun getIdentity(): LocalIdentityEntity? = state.value

    override suspend fun upsert(identity: LocalIdentityEntity) {
        state.value = identity
    }

    override suspend fun clear() {
        state.value = null
    }
}

private class FakeIdentityKeyStore : IdentityKeyStore {
    private var keyPair: KeyPair? = null
    var clearCount = 0
        private set

    override val publicKeyBytes: ByteArray
        get() = getOrCreateKeyPair().public.encoded.copyOf()

    override fun getOrCreate(): IdentityKeyMaterial {
        val publicKey = publicKeyBytes
        return IdentityKeyMaterial(
            publicKeyBytes = publicKey,
            publicKeyPreview = publicKeyPreview(publicKey)
        )
    }

    override fun sign(data: ByteArray): ByteArray {
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(getOrCreateKeyPair().private)
            update(data)
            sign()
        }
    }

    override fun verify(
        data: ByteArray,
        signature: ByteArray,
        publicKeyBytes: ByteArray
    ): Boolean {
        return runCatching {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(publicKeyBytes)
            )
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    override fun clear() {
        clearCount += 1
        keyPair = null
    }

    private fun getOrCreateKeyPair(): KeyPair {
        return keyPair ?: KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair().also { keyPair = it }
        }
    }
}
