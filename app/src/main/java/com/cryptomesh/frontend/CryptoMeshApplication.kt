package com.cryptomesh.frontend

import android.app.Application
import com.cryptomesh.frontend.crypto.AndroidIdentityKeyStore
import com.cryptomesh.frontend.data.local.CryptoMeshDatabase
import com.cryptomesh.frontend.data.repository.IdentityRepository
import com.cryptomesh.frontend.data.repository.BleDirectMeshRepository
import com.cryptomesh.frontend.data.repository.DirectMeshRepository
import com.cryptomesh.frontend.data.repository.LocalMediaFileStore
import com.cryptomesh.frontend.data.repository.RoomIdentityRepository
import com.cryptomesh.frontend.data.repository.MediaTransferRepository
import com.cryptomesh.frontend.data.repository.RoomMediaTransferRepository
import com.cryptomesh.frontend.data.repository.RoomSecurePacketRepository
import com.cryptomesh.frontend.data.repository.SecurePacketRepository
import com.cryptomesh.frontend.notification.AndroidMeshNotifier
import com.cryptomesh.frontend.notification.MeshNotificationCoordinator
import com.cryptomesh.frontend.transport.AndroidBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CryptoMeshApplication : Application() {
    val container: AppContainer by lazy {
        AppContainer(this)
    }
}

class AppContainer(application: Application) {
    private val database = CryptoMeshDatabase.getInstance(application)
    private val identityKeyStore = AndroidIdentityKeyStore()
    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    val identityRepository: IdentityRepository = RoomIdentityRepository(
        identityDao = database.identityDao(),
        keyStore = identityKeyStore
    )

    val securePacketRepository: SecurePacketRepository =
        RoomSecurePacketRepository(
            packetDao = database.securePacketDao()
        )

    val mediaTransferRepository: MediaTransferRepository =
        RoomMediaTransferRepository(
            transferDao = database.mediaTransferDao(),
            chunkDao = database.mediaChunkDao()
        )

    private val mediaFileStore = LocalMediaFileStore(
        application.filesDir.resolve("media_transfers")
    )

    val directMeshRepository: DirectMeshRepository =
        BleDirectMeshRepository(
            identityRepository = identityRepository,
            signatureService = identityKeyStore,
            packetRepository = securePacketRepository,
            mediaTransferRepository = mediaTransferRepository,
            mediaFileStore = mediaFileStore,
            transport = AndroidBleTransport(application),
            applicationScope = applicationScope
        )

    private val notificationCoordinator = MeshNotificationCoordinator(
        repository = directMeshRepository,
        notifier = AndroidMeshNotifier(application),
        scope = applicationScope
    ).also(MeshNotificationCoordinator::start)
}
