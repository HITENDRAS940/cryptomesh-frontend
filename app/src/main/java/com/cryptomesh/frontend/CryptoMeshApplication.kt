package com.cryptomesh.frontend

import android.app.Application
import com.cryptomesh.frontend.crypto.AndroidIdentityKeyStore
import com.cryptomesh.frontend.crypto.AndroidKeyStoreWrappingService
import com.cryptomesh.frontend.crypto.DopKeyWrappingService
import com.cryptomesh.frontend.data.local.CryptoMeshDatabase
import com.cryptomesh.frontend.data.repository.IdentityRepository
import com.cryptomesh.frontend.data.repository.BleDirectMeshRepository
import com.cryptomesh.frontend.data.repository.DirectMeshRepository
import com.cryptomesh.frontend.data.repository.DopPreKeyRepository
import com.cryptomesh.frontend.data.repository.LocalMediaFileStore
import com.cryptomesh.frontend.data.repository.RoomDopPreKeyRepository
import com.cryptomesh.frontend.data.repository.RoomIdentityRepository
import com.cryptomesh.frontend.data.repository.MediaTransferRepository
import com.cryptomesh.frontend.data.repository.RoomMediaTransferRepository
import com.cryptomesh.frontend.data.repository.GroupRepository
import com.cryptomesh.frontend.data.repository.RoomGroupRepository
import com.cryptomesh.frontend.data.repository.RoomSecurePacketRepository
import com.cryptomesh.frontend.data.repository.SecurePacketRepository
import com.cryptomesh.frontend.notification.AndroidMeshNotifier
import com.cryptomesh.frontend.notification.MeshNotificationCoordinator
import com.cryptomesh.frontend.protocol.dop.DopHandshakeCoordinator
import com.cryptomesh.frontend.protocol.dop.DopMetricsCollector
import com.cryptomesh.frontend.protocol.dop.DopPreKeyValidator
import com.cryptomesh.frontend.relay.DopRelayCacheManager
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
    private val dopKeyWrappingService: DopKeyWrappingService =
        AndroidKeyStoreWrappingService()
    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    val dopMetricsCollector = DopMetricsCollector()

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

    val groupRepository: GroupRepository =
        RoomGroupRepository(
            groupDao = database.groupDao()
        )

    val dopPreKeyRepository: DopPreKeyRepository =
        RoomDopPreKeyRepository(
            dao = database.dopDao(),
            signatureService = identityKeyStore,
            keyWrappingService = dopKeyWrappingService,
            metrics = dopMetricsCollector
        )

    val dopPreKeyValidator = DopPreKeyValidator(
        signatureService = identityKeyStore
    )

    val dopRelayCacheManager = DopRelayCacheManager(
        repository = dopPreKeyRepository,
        validator = dopPreKeyValidator,
        metrics = dopMetricsCollector
    )

    val dopHandshakeCoordinator = DopHandshakeCoordinator(
        repository = dopPreKeyRepository,
        relayCacheManager = dopRelayCacheManager,
        validator = dopPreKeyValidator,
        signatureService = identityKeyStore,
        keyWrappingService = dopKeyWrappingService,
        metrics = dopMetricsCollector
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
            groupRepository = groupRepository,
            transport = AndroidBleTransport(application),
            applicationScope = applicationScope,
            dopKeyWrappingService = dopKeyWrappingService,
            dopMetricsCollector = dopMetricsCollector,
            dopPreKeyRepository = dopPreKeyRepository,
            dopPreKeyValidator = dopPreKeyValidator,
            dopRelayCacheManager = dopRelayCacheManager,
            dopHandshakeCoordinator = dopHandshakeCoordinator
        )

    private val notificationCoordinator = MeshNotificationCoordinator(
        repository = directMeshRepository,
        notifier = AndroidMeshNotifier(application),
        scope = applicationScope
    ).also(MeshNotificationCoordinator::start)
}
