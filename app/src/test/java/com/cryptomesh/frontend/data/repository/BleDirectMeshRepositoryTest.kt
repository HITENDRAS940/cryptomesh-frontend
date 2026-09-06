package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.protocol.SecurePacketEnvelope
import com.cryptomesh.frontend.protocol.deviceIdForPublicKey
import com.cryptomesh.frontend.protocol.MediaKind
import com.cryptomesh.frontend.transport.DiscoveredTransportPeer
import com.cryptomesh.frontend.transport.LocalTransportNode
import com.cryptomesh.frontend.transport.NearbyTransport
import com.cryptomesh.frontend.transport.NearbyTransportEvent
import com.cryptomesh.frontend.transport.TransportLinkStatus
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.nio.file.Files
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDirectMeshRepositoryTest {
    @Test
    fun twoPeersDiscoverAuthenticateExchangeMessageAndAcknowledge() =
        runBlocking {
            val aliceSigner = TestSignatureService()
            val bobSigner = TestSignatureService()
            val aliceIdentity = identity("Alice", aliceSigner)
            val bobIdentity = identity("Bob", bobSigner)
            val aliceTransport = PairedNearbyTransport("alice")
            val bobTransport = PairedNearbyTransport("bob")
            aliceTransport.partner = bobTransport
            bobTransport.partner = aliceTransport
            val alicePackets = MemoryPacketRepository()
            val bobPackets = MemoryPacketRepository()
            val aliceScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default
            )
            val bobScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default
            )
            val clock = IncrementingClock()
            val aliceRepository = BleDirectMeshRepository(
                identityRepository = FixedIdentityRepository(aliceIdentity),
                signatureService = aliceSigner,
                packetRepository = alicePackets,
                mediaTransferRepository = MemoryMediaTransferRepository(),
                mediaFileStore = testMediaFileStore(),
                transport = aliceTransport,
                applicationScope = aliceScope,
                now = clock::now
            )
            val bobRepository = BleDirectMeshRepository(
                identityRepository = FixedIdentityRepository(bobIdentity),
                signatureService = bobSigner,
                packetRepository = bobPackets,
                mediaTransferRepository = MemoryMediaTransferRepository(),
                mediaFileStore = testMediaFileStore(),
                transport = bobTransport,
                applicationScope = bobScope,
                now = clock::now
            )

            delay(100)
            aliceRepository.start()
            bobRepository.start()
            aliceRepository.startScan()
            val discovered = awaitState(aliceRepository) {
                it.peers.any { peer ->
                    peer.advertisedDeviceId == bobIdentity.deviceId
                }
            }
            aliceRepository.connect(discovered.peers.single().linkId)

            awaitState(aliceRepository) {
                it.peers.any { peer ->
                    peer.deviceId == bobIdentity.deviceId &&
                        peer.status == DirectPeerStatus.Connected &&
                        peer.isVerified
                }
            }
            awaitState(bobRepository) {
                it.peers.any { peer ->
                    peer.deviceId == aliceIdentity.deviceId &&
                        peer.status == DirectPeerStatus.Connected &&
                        peer.isVerified
                }
            }

            val packetId = aliceRepository.sendText(
                bobIdentity.deviceId,
                "Meet at the north gate"
            ).getOrThrow()
            val aliceFinal = awaitState(aliceRepository) {
                it.messages.any { message ->
                    message.packetId == packetId &&
                        message.status == DirectMessageStatus.Acknowledged
                }
            }
            val bobFinal = awaitState(bobRepository) {
                it.messages.any { message ->
                    message.packetId == packetId &&
                        !message.isOutgoing
                }
            }

            assertEquals(
                "Meet at the north gate",
                bobFinal.messages.single().text
            )
            assertEquals(
                DirectMessageStatus.Acknowledged,
                aliceFinal.messages.single().status
            )
            assertEquals(
                PersistedPacketStatus.Acknowledged,
                alicePackets.packetStatus(packetId)
            )
            assertEquals(
                PersistedPacketStatus.Acknowledged,
                bobPackets.packetStatus(packetId)
            )

            aliceScope.cancel()
            bobScope.cancel()
        }

    @Test
    fun packetRelaysAcrossAuthenticatedOfflineMeshAndAcknowledgesBack() =
        runBlocking {
            val aliceSigner = TestSignatureService()
            val bobSigner = TestSignatureService()
            val charlieSigner = TestSignatureService()
            val aliceIdentity = identity("Alice", aliceSigner)
            val bobIdentity = identity("Bob", bobSigner)
            val charlieIdentity = identity("Charlie", charlieSigner)
            val network = TestMeshNetwork()
            val aliceTransport = MeshNearbyTransport("alice", network)
            val bobTransport = MeshNearbyTransport("bob", network)
            val charlieTransport = MeshNearbyTransport("charlie", network)
            val alicePackets = MemoryPacketRepository()
            val bobPackets = MemoryPacketRepository()
            val charliePackets = MemoryPacketRepository()
            val aliceMediaTransfers = MemoryMediaTransferRepository()
            val bobMediaTransfers = MemoryMediaTransferRepository()
            val charlieMediaTransfers = MemoryMediaTransferRepository()
            val aliceScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default
            )
            val bobScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default
            )
            val charlieScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default
            )
            val clock = IncrementingClock()
            val aliceRepository = BleDirectMeshRepository(
                identityRepository = FixedIdentityRepository(aliceIdentity),
                signatureService = aliceSigner,
                packetRepository = alicePackets,
                mediaTransferRepository = aliceMediaTransfers,
                mediaFileStore = testMediaFileStore(),
                transport = aliceTransport,
                applicationScope = aliceScope,
                now = clock::now
            )
            val bobRepository = BleDirectMeshRepository(
                identityRepository = FixedIdentityRepository(bobIdentity),
                signatureService = bobSigner,
                packetRepository = bobPackets,
                mediaTransferRepository = bobMediaTransfers,
                mediaFileStore = testMediaFileStore(),
                transport = bobTransport,
                applicationScope = bobScope,
                now = clock::now
            )
            val charlieRepository = BleDirectMeshRepository(
                identityRepository = FixedIdentityRepository(charlieIdentity),
                signatureService = charlieSigner,
                packetRepository = charliePackets,
                mediaTransferRepository = charlieMediaTransfers,
                mediaFileStore = testMediaFileStore(),
                transport = charlieTransport,
                applicationScope = charlieScope,
                now = clock::now
            )

            delay(100)
            aliceRepository.start()
            bobRepository.start()
            charlieRepository.start()
            aliceRepository.startScan()
            aliceRepository.connect("charlie")
            awaitConnected(aliceRepository, charlieIdentity.deviceId)
            awaitConnected(charlieRepository, aliceIdentity.deviceId)
            aliceRepository.disconnect("charlie")
            awaitState(aliceRepository) {
                it.peers.any { peer ->
                    peer.deviceId == charlieIdentity.deviceId &&
                        peer.status == DirectPeerStatus.Discovered &&
                        peer.isVerified
                }
            }

            aliceRepository.startScan()
            aliceRepository.connect("bob")
            bobRepository.startScan()
            bobRepository.connect("charlie")
            awaitConnected(aliceRepository, bobIdentity.deviceId)
            awaitConnected(bobRepository, aliceIdentity.deviceId)
            awaitConnected(bobRepository, charlieIdentity.deviceId)
            awaitConnected(charlieRepository, bobIdentity.deviceId)

            val packetId = aliceRepository.sendText(
                charlieIdentity.deviceId,
                "Relay through Bob"
            ).getOrThrow()
            val charlieFinal = awaitState(charlieRepository) {
                it.messages.any { message ->
                    message.packetId == packetId &&
                        message.peerDeviceId == aliceIdentity.deviceId &&
                        !message.isOutgoing
                }
            }
            val aliceFinal = awaitState(aliceRepository) {
                it.messages.any { message ->
                    message.packetId == packetId &&
                        message.status == DirectMessageStatus.Acknowledged
                }
            }

            assertEquals(
                "Relay through Bob",
                charlieFinal.messages.single().text
            )
            assertEquals(
                DirectMessageStatus.Acknowledged,
                aliceFinal.messages.single().status
            )
            assertEquals(PacketOwnership.Relay, bobPackets.packetOwnership(packetId))
            assertEquals(
                PersistedPacketStatus.Acknowledged,
                alicePackets.packetStatus(packetId)
            )
            assertEquals(
                PersistedPacketStatus.Acknowledged,
                charliePackets.packetStatus(packetId)
            )

            aliceScope.cancel()
            bobScope.cancel()
            charlieScope.cancel()
        }

    @Test
    fun mediaRelaysAcrossAuthenticatedOfflineMeshAndCompletes() =
        runBlocking {
            val aliceSigner = TestSignatureService()
            val bobSigner = TestSignatureService()
            val charlieSigner = TestSignatureService()
            val aliceIdentity = identity("Alice", aliceSigner)
            val bobIdentity = identity("Bob", bobSigner)
            val charlieIdentity = identity("Charlie", charlieSigner)
            val network = TestMeshNetwork()
            val aliceTransport = MeshNearbyTransport("alice", network)
            val bobTransport = MeshNearbyTransport("bob", network)
            val charlieTransport = MeshNearbyTransport("charlie", network)
            val alicePackets = MemoryPacketRepository()
            val bobPackets = MemoryPacketRepository()
            val charliePackets = MemoryPacketRepository()
            val aliceMediaTransfers = MemoryMediaTransferRepository()
            val bobMediaTransfers = MemoryMediaTransferRepository()
            val charlieMediaTransfers = MemoryMediaTransferRepository()
            val aliceScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default
            )
            val bobScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default
            )
            val charlieScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default
            )
            val clock = IncrementingClock()
            val aliceRepository = BleDirectMeshRepository(
                identityRepository = FixedIdentityRepository(aliceIdentity),
                signatureService = aliceSigner,
                packetRepository = alicePackets,
                mediaTransferRepository = aliceMediaTransfers,
                mediaFileStore = testMediaFileStore(),
                transport = aliceTransport,
                applicationScope = aliceScope,
                now = clock::now
            )
            val bobRepository = BleDirectMeshRepository(
                identityRepository = FixedIdentityRepository(bobIdentity),
                signatureService = bobSigner,
                packetRepository = bobPackets,
                mediaTransferRepository = bobMediaTransfers,
                mediaFileStore = testMediaFileStore(),
                transport = bobTransport,
                applicationScope = bobScope,
                now = clock::now
            )
            val charlieRepository = BleDirectMeshRepository(
                identityRepository = FixedIdentityRepository(charlieIdentity),
                signatureService = charlieSigner,
                packetRepository = charliePackets,
                mediaTransferRepository = charlieMediaTransfers,
                mediaFileStore = testMediaFileStore(),
                transport = charlieTransport,
                applicationScope = charlieScope,
                now = clock::now
            )

            delay(100)
            aliceRepository.start()
            bobRepository.start()
            charlieRepository.start()
            aliceRepository.startScan()
            aliceRepository.connect("charlie")
            awaitConnected(aliceRepository, charlieIdentity.deviceId)
            awaitConnected(charlieRepository, aliceIdentity.deviceId)
            aliceRepository.disconnect("charlie")
            aliceRepository.startScan()
            aliceRepository.connect("bob")
            bobRepository.startScan()
            bobRepository.connect("charlie")
            awaitConnected(aliceRepository, bobIdentity.deviceId)
            awaitConnected(bobRepository, aliceIdentity.deviceId)
            awaitConnected(bobRepository, charlieIdentity.deviceId)
            awaitConnected(charlieRepository, bobIdentity.deviceId)

            val bytes = ByteArray(18_000) { index -> (index % 199).toByte() }
            val transferId = aliceRepository.sendMedia(
                peerDeviceId = charlieIdentity.deviceId,
                mediaKind = MediaKind.Photo,
                fileName = "offline-photo.jpg",
                mimeType = "image/jpeg",
                bytes = bytes
            ).getOrThrow()
            val charlieFinal = awaitState(charlieRepository) {
                it.mediaTransfers.any { transfer ->
                    transfer.transferId == transferId &&
                        transfer.status == MediaTransferStatus.Completed &&
                        transfer.completedChunks == transfer.totalChunks &&
                        transfer.outputPath != null
                }
            }
            val aliceFinal = awaitState(aliceRepository) {
                it.mediaTransfers.any { transfer ->
                    transfer.transferId == transferId &&
                        transfer.status == MediaTransferStatus.Completed
                }
            }

            assertEquals(
                MediaTransferStatus.Completed,
                charlieFinal.mediaTransfers.single().status
            )
            assertEquals(
                MediaTransferStatus.Completed,
                aliceFinal.mediaTransfers.single().status
            )
            assertTrue(
                bobPackets.packetTypes().contains("MediaChunk")
            )

            aliceScope.cancel()
            bobScope.cancel()
            charlieScope.cancel()
        }

    private suspend fun awaitState(
        repository: DirectMeshRepository,
        predicate: (DirectMeshState) -> Boolean
    ): DirectMeshState {
        return withTimeout(5_000) {
            repository.state.first(predicate)
        }
    }

    private suspend fun awaitConnected(
        repository: DirectMeshRepository,
        peerDeviceId: String
    ): DirectMeshState {
        return awaitState(repository) {
            it.peers.any { peer ->
                peer.deviceId == peerDeviceId &&
                    peer.status == DirectPeerStatus.Connected &&
                    peer.isVerified
            }
        }
    }

    private fun identity(
        name: String,
        signer: PacketSignatureService
    ): LocalIdentity {
        return LocalIdentity(
            displayName = name,
            deviceId = deviceIdForPublicKey(signer.publicKeyBytes),
            publicKeyPreview = "test"
        )
    }

    private fun testMediaFileStore(): MediaFileStore {
        return LocalMediaFileStore(
            Files.createTempDirectory("cryptomesh-media-test").toFile()
        )
    }
}

private class FixedIdentityRepository(
    identity: LocalIdentity
) : IdentityRepository {
    private val current = MutableStateFlow<LocalIdentity?>(identity)
    override val identity: Flow<LocalIdentity?> = current

    override suspend fun createIdentity(displayName: String): LocalIdentity {
        return requireNotNull(current.value)
    }

    override suspend fun clearIdentity() {
        current.value = null
    }
}

private class MemoryPacketRepository : SecurePacketRepository {
    private val stored = linkedMapOf<String, StoredSecurePacket>()
    private val current = MutableStateFlow<List<StoredSecurePacket>>(emptyList())
    override val packets: Flow<List<StoredSecurePacket>> = current

    override suspend fun storeIfAbsent(
        envelope: SecurePacketEnvelope,
        status: PersistedPacketStatus,
        ownership: PacketOwnership
    ): Boolean {
        if (envelope.packetId in stored) return false
        stored[envelope.packetId] = StoredSecurePacket(
            envelope = envelope,
            status = status,
            ownership = ownership,
            lastUpdatedEpochMillis = envelope.createdAtEpochMillis
        )
        current.value = stored.values.toList()
        return true
    }

    override suspend fun updateStatus(
        packetId: String,
        status: PersistedPacketStatus
    ): Boolean {
        val packet = stored[packetId] ?: return false
        stored[packetId] = packet.copy(status = status)
        current.value = stored.values.toList()
        return true
    }

    override suspend fun cleanupExpired(
        olderThanEpochMillis: Long
    ): Int = 0

    override suspend fun clearAll() {
        stored.clear()
        current.value = emptyList()
    }

    fun packetStatus(packetId: String): PersistedPacketStatus? {
        return stored[packetId]?.status
    }

    fun packetOwnership(packetId: String): PacketOwnership? {
        return stored[packetId]?.ownership
    }

    fun packetTypes(): Set<String> {
        return stored.values.map { it.envelope.packetType.name }.toSet()
    }
}

private class MemoryMediaTransferRepository : MediaTransferRepository {
    private val storedTransfers = linkedMapOf<String, StoredMediaTransfer>()
    private val storedChunks = linkedMapOf<Pair<String, Int>, StoredMediaChunk>()
    private val current = MutableStateFlow<List<StoredMediaTransfer>>(emptyList())
    override val transfers: Flow<List<StoredMediaTransfer>> = current

    override suspend fun upsertTransfer(transfer: StoredMediaTransfer) {
        storedTransfers[transfer.transferId] = transfer
        publish()
    }

    override suspend fun updateTransferStatus(
        transferId: String,
        status: MediaTransferStatus
    ): Boolean {
        val transfer = storedTransfers[transferId] ?: return false
        storedTransfers[transferId] = transfer.copy(status = status)
        publish()
        return true
    }

    override suspend fun getTransfer(
        transferId: String
    ): StoredMediaTransfer? {
        return storedTransfers[transferId]?.withChunks()
    }

    override suspend fun upsertChunk(chunk: StoredMediaChunk) {
        storedChunks[chunk.transferId to chunk.chunkIndex] = chunk
        publish()
    }

    override suspend fun upsertChunks(chunks: List<StoredMediaChunk>) {
        chunks.forEach { storedChunks[it.transferId to it.chunkIndex] = it }
        publish()
    }

    override suspend fun chunksForTransfer(
        transferId: String
    ): List<StoredMediaChunk> {
        return storedChunks.values
            .filter { it.transferId == transferId }
            .sortedBy(StoredMediaChunk::chunkIndex)
    }

    override suspend fun updateChunkStorage(
        transferId: String,
        chunkIndex: Int,
        packetId: String?,
        status: MediaChunkStatus,
        encryptedPath: String?,
        plaintextPath: String?
    ): Boolean {
        val key = transferId to chunkIndex
        val chunk = storedChunks[key] ?: return false
        storedChunks[key] = chunk.copy(
            packetId = packetId,
            status = status,
            encryptedPath = encryptedPath,
            plaintextPath = plaintextPath
        )
        publish()
        return true
    }

    override suspend fun updateChunkStatus(
        transferId: String,
        chunkIndex: Int,
        status: MediaChunkStatus
    ): Boolean {
        val key = transferId to chunkIndex
        val chunk = storedChunks[key] ?: return false
        storedChunks[key] = chunk.copy(status = status)
        publish()
        return true
    }

    override suspend fun deleteTransfer(transferId: String): Boolean {
        val removed = storedTransfers.remove(transferId) != null
        storedChunks.keys.removeAll { it.first == transferId }
        publish()
        return removed
    }

    override suspend fun clearAll() {
        storedTransfers.clear()
        storedChunks.clear()
        publish()
    }

    private fun publish() {
        current.value = storedTransfers.values.map { it.withChunks() }
    }

    private fun StoredMediaTransfer.withChunks(): StoredMediaTransfer {
        return copy(chunks = storedChunks.values
            .filter { it.transferId == transferId }
            .sortedBy(StoredMediaChunk::chunkIndex))
    }
}

private class TestMeshNetwork {
    private val transports = mutableMapOf<String, MeshNearbyTransport>()

    @Synchronized
    fun register(transport: MeshNearbyTransport) {
        transports[transport.transportId] = transport
    }

    @Synchronized
    fun peersFor(transportId: String): List<MeshNearbyTransport> {
        return transports.values
            .filter { it.transportId != transportId && it.localNode != null }
    }

    @Synchronized
    fun connect(sourceId: String, targetId: String) {
        val source = requireNotNull(transports[sourceId])
        val target = requireNotNull(transports[targetId])
        source.markConnected(targetId)
        target.markConnected(sourceId)
    }

    @Synchronized
    fun disconnect(sourceId: String, targetId: String) {
        val source = requireNotNull(transports[sourceId])
        val target = transports[targetId]
        source.markDisconnected(targetId)
        target?.markDisconnected(sourceId)
    }

    @Synchronized
    fun deliver(sourceId: String, targetId: String, payload: ByteArray) {
        requireNotNull(transports[targetId]).receive(sourceId, payload)
    }
}

private class MeshNearbyTransport(
    val transportId: String,
    private val network: TestMeshNetwork
) : NearbyTransport {
    private val _events = MutableSharedFlow<NearbyTransportEvent>(
        extraBufferCapacity = 128
    )
    override val events: SharedFlow<NearbyTransportEvent> =
        _events.asSharedFlow()

    var localNode: LocalTransportNode? = null
        private set
    private val connectedLinks = mutableSetOf<String>()

    init {
        network.register(this)
    }

    override fun start(localNode: LocalTransportNode) {
        this.localNode = localNode
        _events.tryEmit(
            NearbyTransportEvent.Availability(
                available = true,
                advertising = true
            )
        )
    }

    override fun startScan() {
        _events.tryEmit(NearbyTransportEvent.ScanChanged(scanning = true))
        network.peersFor(transportId).forEach { peer ->
            _events.tryEmit(
                NearbyTransportEvent.PeerFound(
                    DiscoveredTransportPeer(
                        linkId = peer.transportId,
                        advertisedDeviceId = peer.localNode?.deviceId,
                        signalStrength = -48
                    )
                )
            )
        }
        _events.tryEmit(NearbyTransportEvent.ScanChanged(scanning = false))
    }

    override fun stopScan() {
        _events.tryEmit(NearbyTransportEvent.ScanChanged(scanning = false))
    }

    override fun connect(linkId: String) {
        network.connect(transportId, linkId)
    }

    override fun disconnect(linkId: String) {
        network.disconnect(transportId, linkId)
    }

    override fun send(
        linkId: String,
        payload: ByteArray
    ): Result<Unit> = runCatching {
        require(linkId in connectedLinks) {
            "Peer link is not connected."
        }
        assertTrue(payload.decodeToString().contains("payloadBase64"))
        network.deliver(transportId, linkId, payload.copyOf())
    }

    override fun close() {
        connectedLinks.toList().forEach(::disconnect)
    }

    fun markConnected(linkId: String) {
        connectedLinks += linkId
        _events.tryEmit(
            NearbyTransportEvent.LinkChanged(
                linkId,
                TransportLinkStatus.Connected
            )
        )
    }

    fun markDisconnected(linkId: String) {
        connectedLinks -= linkId
        _events.tryEmit(
            NearbyTransportEvent.LinkChanged(
                linkId,
                TransportLinkStatus.Disconnected
            )
        )
    }

    fun receive(linkId: String, payload: ByteArray) {
        _events.tryEmit(
            NearbyTransportEvent.PayloadReceived(
                linkId = linkId,
                payload = payload
            )
        )
    }
}

private class PairedNearbyTransport(
    val transportId: String
) : NearbyTransport {
    private val _events = MutableSharedFlow<NearbyTransportEvent>(
        extraBufferCapacity = 128
    )
    override val events: SharedFlow<NearbyTransportEvent> =
        _events.asSharedFlow()

    lateinit var partner: PairedNearbyTransport
    private var localNode: LocalTransportNode? = null

    override fun start(localNode: LocalTransportNode) {
        this.localNode = localNode
        _events.tryEmit(
            NearbyTransportEvent.Availability(
                available = true,
                advertising = true
            )
        )
    }

    override fun startScan() {
        _events.tryEmit(NearbyTransportEvent.ScanChanged(scanning = true))
        val remoteNode = requireNotNull(partner.localNode)
        _events.tryEmit(
            NearbyTransportEvent.PeerFound(
                DiscoveredTransportPeer(
                    linkId = partner.transportId,
                    advertisedDeviceId = remoteNode.deviceId,
                    signalStrength = -48
                )
            )
        )
        _events.tryEmit(NearbyTransportEvent.ScanChanged(scanning = false))
    }

    override fun stopScan() {
        _events.tryEmit(NearbyTransportEvent.ScanChanged(scanning = false))
    }

    override fun connect(linkId: String) {
        require(linkId == partner.transportId)
        _events.tryEmit(
            NearbyTransportEvent.LinkChanged(
                linkId,
                TransportLinkStatus.Connected
            )
        )
        partner._events.tryEmit(
            NearbyTransportEvent.LinkChanged(
                transportId,
                TransportLinkStatus.Connected
            )
        )
    }

    override fun disconnect(linkId: String) {
        _events.tryEmit(
            NearbyTransportEvent.LinkChanged(
                linkId,
                TransportLinkStatus.Disconnected
            )
        )
    }

    override fun send(
        linkId: String,
        payload: ByteArray
    ): Result<Unit> = runCatching {
        require(linkId == partner.transportId)
        assertTrue(payload.decodeToString().contains("payloadBase64"))
        partner._events.tryEmit(
            NearbyTransportEvent.PayloadReceived(
                linkId = transportId,
                payload = payload.copyOf()
            )
        )
    }

    override fun close() = Unit
}

private class TestSignatureService : PacketSignatureService {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    override val publicKeyBytes: ByteArray
        get() = keyPair.public.encoded.copyOf()

    override fun sign(data: ByteArray): ByteArray {
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
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
}

private class IncrementingClock {
    private var value = 10_000L

    @Synchronized
    fun now(): Long {
        value += 1
        return value
    }
}
