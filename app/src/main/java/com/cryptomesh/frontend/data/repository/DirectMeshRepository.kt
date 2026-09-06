package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.protocol.AuthenticatedHandshake
import com.cryptomesh.frontend.protocol.AuthenticatedSession
import com.cryptomesh.frontend.protocol.DecodedDirectWireMessage
import com.cryptomesh.frontend.protocol.DirectAcknowledgementPayload
import com.cryptomesh.frontend.protocol.DirectTextPayload
import com.cryptomesh.frontend.protocol.DirectWireCodec
import com.cryptomesh.frontend.protocol.MediaAcceptPayload
import com.cryptomesh.frontend.protocol.MediaChunkAcknowledgementPayload
import com.cryptomesh.frontend.protocol.MediaChunkPayload
import com.cryptomesh.frontend.protocol.MediaCompletePayload
import com.cryptomesh.frontend.protocol.MediaKind
import com.cryptomesh.frontend.protocol.MediaOfferPayload
import com.cryptomesh.frontend.protocol.PendingHandshake
import com.cryptomesh.frontend.protocol.SecurePacketCodec
import com.cryptomesh.frontend.protocol.SecurePacketEnvelope
import com.cryptomesh.frontend.protocol.SecurePacketType
import com.cryptomesh.frontend.transport.DiscoveredTransportPeer
import com.cryptomesh.frontend.transport.LocalTransportNode
import com.cryptomesh.frontend.transport.NearbyTransport
import com.cryptomesh.frontend.transport.NearbyTransportEvent
import com.cryptomesh.frontend.transport.TransportLinkStatus
import com.cryptomesh.frontend.transport.TransportUnavailableReason
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DirectPeerStatus {
    Discovered,
    Connecting,
    Authenticating,
    Connected,
    Failed
}

data class DirectPeer(
    val linkId: String,
    val advertisedDeviceId: String?,
    val deviceId: String?,
    val displayName: String,
    val signalStrength: Int,
    val status: DirectPeerStatus,
    val isVerified: Boolean = false,
    val connectionInitiatedByLocal: Boolean = false,
    val failureMessage: String? = null
)

enum class DirectMessageStatus {
    Queued,
    Sending,
    AwaitingAcknowledgement,
    Acknowledged,
    Failed
}

data class DirectMessage(
    val packetId: String,
    val peerDeviceId: String,
    val text: String,
    val sentAtEpochMillis: Long,
    val isOutgoing: Boolean,
    val status: DirectMessageStatus
)

data class DirectMediaTransfer(
    val transferId: String,
    val peerDeviceId: String,
    val mediaKind: MediaKind,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val completedChunks: Int,
    val totalChunks: Int,
    val isOutgoing: Boolean,
    val status: MediaTransferStatus,
    val outputPath: String? = null
)

data class DirectMeshState(
    val transportAvailable: Boolean = false,
    val transportUnavailableReason: TransportUnavailableReason? = null,
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val scanError: String? = null,
    val peers: List<DirectPeer> = emptyList(),
    val messages: List<DirectMessage> = emptyList(),
    val mediaTransfers: List<DirectMediaTransfer> = emptyList()
)

interface DirectMeshRepository {
    val state: StateFlow<DirectMeshState>

    fun start()

    fun startScan()

    fun stopScan()

    fun connect(linkId: String)

    fun disconnect(linkId: String)

    suspend fun sendText(peerDeviceId: String, text: String): Result<String>

    suspend fun sendMedia(
        peerDeviceId: String,
        mediaKind: MediaKind,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<String>
}

class BleDirectMeshRepository(
    private val identityRepository: IdentityRepository,
    private val signatureService: PacketSignatureService,
    private val packetRepository: SecurePacketRepository,
    private val mediaTransferRepository: MediaTransferRepository,
    private val mediaFileStore: MediaFileStore,
    private val transport: NearbyTransport,
    private val applicationScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val wireCodec: DirectWireCodec = DirectWireCodec(),
    private val mediaTransferEngine: MediaTransferEngine =
        MediaTransferEngine(now),
    private val handshake: AuthenticatedHandshake =
        AuthenticatedHandshake(signatureService, now)
) : DirectMeshRepository {
    private val _state = MutableStateFlow(DirectMeshState())
    override val state: StateFlow<DirectMeshState> = _state.asStateFlow()

    private val pendingHandshakes =
        ConcurrentHashMap<String, PendingHandshake>()
    private val sessions =
        ConcurrentHashMap<String, AuthenticatedSession>()
    private val sessionsByDeviceId =
        ConcurrentHashMap<String, List<AuthenticatedSession>>()
    private val forwardedPacketLinks =
        ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var cachedPackets: List<StoredSecurePacket> = emptyList()
    private var localIdentity: LocalIdentity? = null

    init {
        applicationScope.launch {
            identityRepository.identity
                .collect { identity ->
                    val previousDeviceId = localIdentity?.deviceId
                    if (
                        previousDeviceId != null &&
                        previousDeviceId != identity?.deviceId
                    ) {
                        pendingHandshakes.clear()
                        sessions.clear()
                        sessionsByDeviceId.clear()
                        forwardedPacketLinks.clear()
                        transport.close()
                        _state.value = DirectMeshState()
                    }
                    localIdentity = identity
                }
        }
        applicationScope.launch {
            packetRepository.packets.collect { packets ->
                cachedPackets = packets
            }
        }
        applicationScope.launch {
            mediaTransferRepository.transfers.collect { transfers ->
                _state.update {
                    it.copy(
                        mediaTransfers = transfers.map(
                            StoredMediaTransfer::toDirectMediaTransfer
                        )
                    )
                }
            }
        }
        applicationScope.launch {
            transport.events.collect(::handleTransportEvent)
        }
    }

    override fun start() {
        val identity = localIdentity ?: return
        transport.start(LocalTransportNode(identity.deviceId))
    }

    override fun startScan() {
        start()
        _state.update {
            it.copy(
                isScanning = true,
                scanError = null,
                peers = it.peers.filter { peer ->
                    peer.isVerified ||
                        peer.status == DirectPeerStatus.Connected ||
                        peer.status == DirectPeerStatus.Authenticating ||
                        peer.status == DirectPeerStatus.Connecting
                }
            )
        }
        transport.startScan()
    }

    override fun stopScan() {
        transport.stopScan()
    }

    override fun connect(linkId: String) {
        updatePeer(linkId) {
            it.copy(
                status = DirectPeerStatus.Connecting,
                connectionInitiatedByLocal = true,
                failureMessage = null
            )
        }
        transport.connect(linkId)
    }

    override fun disconnect(linkId: String) {
        pendingHandshakes.remove(linkId)
        sessions.remove(linkId)
        transport.disconnect(linkId)
        updatePeer(linkId) {
            it.copy(
                status = DirectPeerStatus.Discovered,
                isVerified = it.deviceId?.let(sessionsByDeviceId::containsKey)
                    ?: false,
                connectionInitiatedByLocal = false,
                failureMessage = null
            )
        }
    }

    override suspend fun sendMedia(
        peerDeviceId: String,
        mediaKind: MediaKind,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<String> = runCatching {
        val identity = requireNotNull(localIdentity) {
            "Local identity is not ready."
        }
        requireVerifiedPeer(peerDeviceId)
        val prepared = mediaTransferEngine.prepareOutgoingTransfer(
            mediaKind = mediaKind,
            fileName = fileName,
            mimeType = mimeType,
            bytes = bytes
        )
        mediaTransferRepository.upsertTransfer(
            StoredMediaTransfer(
                transferId = prepared.offer.transferId,
                peerDeviceId = peerDeviceId,
                direction = MediaTransferDirection.Outgoing,
                mediaKind = mediaKind,
                fileName = prepared.offer.fileName,
                mimeType = prepared.offer.mimeType,
                sizeBytes = prepared.offer.sizeBytes,
                chunkSizeBytes = prepared.offer.chunkSizeBytes,
                totalChunks = prepared.offer.totalChunks,
                fileSha256Base64 = prepared.offer.fileSha256Base64,
                encryptedTransferKeyBase64 =
                    prepared.offer.encryptedTransferKeyBase64,
                sourceUri = null,
                outputPath = null,
                status = MediaTransferStatus.Queued,
                createdAtEpochMillis = prepared.offer.createdAtEpochMillis,
                expiresAtEpochMillis = prepared.offer.expiresAtEpochMillis,
                lastUpdatedEpochMillis = now()
            )
        )
        sealAndForwardMediaPacket(
            packetType = SecurePacketType.MediaOffer,
            senderId = identity.deviceId,
            receiverId = peerDeviceId,
            payload = wireCodec.encodeMediaOffer(prepared.offer),
            expiresAtEpochMillis = prepared.offer.expiresAtEpochMillis
        )
        prepared.chunks.forEach { chunk ->
            val payload = mediaTransferEngine.sealChunk(
                transferKey = prepared.transferKey,
                chunk = chunk,
                totalChunks = prepared.offer.totalChunks
            )
            val encryptedPath = mediaFileStore.writeEncryptedChunk(
                transferId = chunk.transferId,
                chunkIndex = chunk.chunkIndex,
                bytes = payload.encryptedChunkBase64.fromBase64()
            )
            val packetId = sealAndForwardMediaPacket(
                packetType = SecurePacketType.MediaChunk,
                senderId = identity.deviceId,
                receiverId = peerDeviceId,
                payload = wireCodec.encodeMediaChunk(payload),
                expiresAtEpochMillis = prepared.offer.expiresAtEpochMillis
            )
            mediaTransferRepository.upsertChunk(
                StoredMediaChunk(
                    transferId = chunk.transferId,
                    chunkIndex = chunk.chunkIndex,
                    packetId = packetId,
                    offsetBytes = chunk.offsetBytes,
                    sizeBytes = chunk.sizeBytes,
                    chunkSha256Base64 = chunk.plaintextSha256Base64,
                    encryptedPath = encryptedPath,
                    plaintextPath = null,
                    status = MediaChunkStatus.Sent,
                    lastUpdatedEpochMillis = now()
                )
            )
        }
        mediaTransferRepository.updateTransferStatus(
            prepared.offer.transferId,
            MediaTransferStatus.Transferring
        )
        prepared.offer.transferId
    }

    override suspend fun sendText(
        peerDeviceId: String,
        text: String
    ): Result<String> = runCatching {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "Message cannot be empty." }
        require(cleanText.length <= MAX_TEXT_LENGTH) {
            "Message exceeds $MAX_TEXT_LENGTH characters."
        }
        val identity = requireNotNull(localIdentity) {
            "Local identity is not ready."
        }
        val peer = _state.value.peers.firstOrNull {
            it.deviceId == peerDeviceId && it.isVerified
        } ?: error("Peer has not been authenticated.")
        val session = requireNotNull(currentSessionFor(peerDeviceId)) {
            "Authenticated peer session key is not ready."
        }
        val codec = SecurePacketCodec(
            sessionCipher = session.cipher,
            signatureService = signatureService,
            now = now
        )
        val sentAt = now()
        val envelope = codec.seal(
            packetType = SecurePacketType.Message,
            senderId = identity.deviceId,
            receiverId = peerDeviceId,
            payload = wireCodec.encodeTextPayload(
                DirectTextPayload(
                    text = cleanText,
                    sentAtEpochMillis = sentAt
                )
            ),
            expiresAtEpochMillis = sentAt + MESSAGE_TTL_MILLIS
        )
        packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Queued,
            ownership = PacketOwnership.Own
        )
        upsertMessage(
            DirectMessage(
                packetId = envelope.packetId,
                peerDeviceId = peerDeviceId,
                text = cleanText,
                sentAtEpochMillis = sentAt,
                isOutgoing = true,
                status = DirectMessageStatus.Queued
            )
        )
        val forwarded = forwardEnvelopeToMesh(envelope)
        if (forwarded) {
            packetRepository.updateStatus(
                envelope.packetId,
                PersistedPacketStatus.AwaitingAcknowledgement
            )
            updateMessageStatus(
                envelope.packetId,
                DirectMessageStatus.AwaitingAcknowledgement
            )
        }
        envelope.packetId
    }.onFailure {
        val pendingMessage = _state.value.messages.lastOrNull {
            it.peerDeviceId == peerDeviceId &&
                it.isOutgoing &&
                it.status == DirectMessageStatus.Sending
        }
        if (pendingMessage != null) {
            packetRepository.updateStatus(
                pendingMessage.packetId,
                PersistedPacketStatus.Failed
            )
            updateMessageStatus(
                pendingMessage.packetId,
                DirectMessageStatus.Failed
            )
        }
    }

    private suspend fun handleTransportEvent(event: NearbyTransportEvent) {
        when (event) {
            is NearbyTransportEvent.Availability -> {
                _state.update {
                    it.copy(
                        transportAvailable = event.available,
                        transportUnavailableReason =
                            event.unavailableReason.takeUnless {
                                event.available
                            },
                        isAdvertising = event.advertising,
                        scanError = if (event.available) {
                            null
                        } else {
                            event.message
                        }
                    )
                }
            }

            is NearbyTransportEvent.ScanChanged -> {
                _state.update {
                    it.copy(
                        isScanning = event.scanning,
                        scanError = event.error
                    )
                }
            }

            is NearbyTransportEvent.PeerFound -> {
                addDiscoveredPeer(event.peer)
            }

            is NearbyTransportEvent.LinkChanged -> {
                handleLinkChanged(event)
            }

            is NearbyTransportEvent.PayloadReceived -> {
                handlePayload(event.linkId, event.payload)
            }
        }
    }

    private fun addDiscoveredPeer(peer: DiscoveredTransportPeer) {
        _state.update { state ->
            val current = state.peers.firstOrNull {
                it.linkId == peer.linkId
            }
            val updated = current?.copy(
                advertisedDeviceId = peer.advertisedDeviceId
                    ?: current.advertisedDeviceId,
                signalStrength = peer.signalStrength
            ) ?: DirectPeer(
                linkId = peer.linkId,
                advertisedDeviceId = peer.advertisedDeviceId,
                deviceId = null,
                displayName = peer.advertisedDeviceId ?: "CryptoMesh peer",
                signalStrength = peer.signalStrength,
                status = DirectPeerStatus.Discovered
            )
            state.copy(
                peers = state.peers
                    .filterNot { it.linkId == peer.linkId } + updated
            )
        }
    }

    private fun handleLinkChanged(
        event: NearbyTransportEvent.LinkChanged
    ) {
        when (event.status) {
            TransportLinkStatus.Connecting -> updatePeer(event.linkId) {
                it.copy(
                    status = DirectPeerStatus.Connecting,
                    failureMessage = null
                )
            }

            TransportLinkStatus.Connected -> beginHandshake(event.linkId)

            TransportLinkStatus.Disconnected -> {
                pendingHandshakes.remove(event.linkId)
                sessions.remove(event.linkId)
                updatePeer(event.linkId) {
                    it.copy(
                        status = DirectPeerStatus.Discovered,
                        isVerified = it.deviceId
                            ?.let(sessionsByDeviceId::containsKey)
                            ?: false,
                        connectionInitiatedByLocal = false,
                        failureMessage = null
                    )
                }
            }

            TransportLinkStatus.Failed -> {
                pendingHandshakes.remove(event.linkId)
                sessions.remove(event.linkId)
                ensurePeer(event.linkId)
                updatePeer(event.linkId) {
                    it.copy(
                        status = DirectPeerStatus.Failed,
                        isVerified = false,
                        failureMessage = event.message
                            ?: "Peer connection failed."
                    )
                }
            }
        }
    }

    private fun beginHandshake(linkId: String) {
        if (sessions.containsKey(linkId)) return
        val identity = localIdentity ?: run {
            markAuthenticationFailure(
                linkId,
                "Local identity is not ready."
            )
            return
        }
        ensurePeer(linkId)
        updatePeer(linkId) {
            it.copy(
                status = DirectPeerStatus.Authenticating,
                failureMessage = null
            )
        }
        val pending = pendingHandshakes.getOrPut(linkId) {
            handshake.createHello(identity)
        }
        transport.send(
            linkId,
            wireCodec.encodeHandshake(pending.hello)
        ).onFailure {
            markAuthenticationFailure(
                linkId,
                it.message ?: "Unable to send authenticated handshake."
            )
        }
    }

    private suspend fun handlePayload(linkId: String, payload: ByteArray) {
        runCatching {
            wireCodec.decode(payload)
        }.onSuccess { decoded ->
            when (decoded) {
                is DecodedDirectWireMessage.Handshake -> {
                    handleHandshake(linkId, decoded)
                }

                is DecodedDirectWireMessage.Packet -> {
                    handleSecurePacket(linkId, decoded)
                }
            }
        }.onFailure {
            markAuthenticationFailure(
                linkId,
                it.message ?: "Peer sent an invalid wire message."
            )
        }
    }

    private fun handleHandshake(
        linkId: String,
        message: DecodedDirectWireMessage.Handshake
    ) {
        if (sessions.containsKey(linkId)) return
        val identity = localIdentity ?: return
        ensurePeer(linkId)
        val pending = pendingHandshakes[linkId] ?: run {
            val created = handshake.createHello(identity)
            pendingHandshakes[linkId] = created
            transport.send(
                linkId,
                wireCodec.encodeHandshake(created.hello)
            ).getOrElse {
                markAuthenticationFailure(
                    linkId,
                    it.message ?: "Unable to answer peer handshake."
                )
                return
            }
            created
        }
        val discoveredDeviceId = _state.value.peers
            .firstOrNull { it.linkId == linkId }
            ?.advertisedDeviceId
        handshake.authenticate(
            localIdentity = identity,
            pending = pending,
            remoteHello = message.hello,
            expectedDeviceId = discoveredDeviceId
        ).onSuccess { session ->
            sessions[linkId] = session
            rememberSession(session)
            pendingHandshakes.remove(linkId)
            updatePeer(linkId) {
                it.copy(
                    deviceId = session.peerDeviceId,
                    displayName = session.peerDisplayName,
                    status = DirectPeerStatus.Connected,
                    isVerified = true,
                    failureMessage = null
                )
            }
            applicationScope.launch {
                flushPacketsForLink(linkId)
            }
        }.onFailure {
            markAuthenticationFailure(
                linkId,
                it.message ?: "Peer authentication failed."
            )
            transport.disconnect(linkId)
        }
    }

    private suspend fun handleSecurePacket(
        linkId: String,
        message: DecodedDirectWireMessage.Packet
    ) {
        val identity = localIdentity ?: return
        sessions[linkId] ?: run {
            markAuthenticationFailure(
                linkId,
                "Encrypted data arrived before peer authentication."
            )
            return
        }
        val envelope = message.envelope
        if (isExpired(envelope)) {
            packetRepository.storeIfAbsent(
                envelope = envelope,
                status = PersistedPacketStatus.Expired,
                ownership = packetOwnershipFor(envelope, identity.deviceId)
            )
            return
        }
        if (envelope.senderId == identity.deviceId) return
        if (envelope.receiverId != identity.deviceId) {
            receiveRelayPacket(linkId, envelope, identity.deviceId)
            return
        }
        val plaintext = openWithKnownPeerSession(envelope) ?: run {
            packetRepository.storeIfAbsent(
                envelope = envelope,
                status = PersistedPacketStatus.Queued,
                ownership = PacketOwnership.Relay
            )
            return
        }
        runCatching {
            when (envelope.packetType) {
                SecurePacketType.Message -> {
                    receiveText(envelope, plaintext)
                }

                SecurePacketType.Acknowledgement -> {
                    receiveAcknowledgement(plaintext)
                }

                SecurePacketType.MediaOffer -> {
                    receiveMediaOffer(envelope, plaintext)
                }

                SecurePacketType.MediaAccept -> Unit

                SecurePacketType.MediaReject -> {
                    receiveMediaRejected(plaintext)
                }

                SecurePacketType.MediaChunk -> {
                    receiveMediaChunk(envelope, plaintext)
                }

                SecurePacketType.MediaChunkAcknowledgement -> {
                    receiveMediaChunkAcknowledgement(plaintext)
                }

                SecurePacketType.MediaComplete -> {
                    receiveMediaComplete(plaintext)
                }
            }
        }.onFailure {
            updatePeer(linkId) { peer ->
                peer.copy(
                    failureMessage = it.message
                        ?: "Secure packet verification failed."
                )
            }
        }
    }

    private suspend fun receiveRelayPacket(
        incomingLinkId: String,
        envelope: SecurePacketEnvelope,
        localDeviceId: String
    ) {
        packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Queued,
            ownership = PacketOwnership.Relay
        )
        if (envelope.senderId != localDeviceId) {
            forwardEnvelopeToMesh(envelope, incomingLinkId)
        }
    }

    private suspend fun receiveText(
        envelope: SecurePacketEnvelope,
        plaintext: ByteArray
    ) {
        val text = wireCodec.decodeTextPayload(plaintext)
        val inserted = packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Acknowledged,
            ownership = PacketOwnership.Own
        )
        if (inserted) {
            upsertMessage(
                DirectMessage(
                    packetId = envelope.packetId,
                    peerDeviceId = envelope.senderId,
                    text = text.text,
                    sentAtEpochMillis = text.sentAtEpochMillis,
                    isOutgoing = false,
                    status = DirectMessageStatus.Acknowledged
                )
            )
        }
        sendAcknowledgement(
            receiverDeviceId = envelope.senderId,
            acknowledgedPacketId = envelope.packetId
        )
    }

    private suspend fun receiveMediaOffer(
        envelope: SecurePacketEnvelope,
        plaintext: ByteArray
    ) {
        val offer = wireCodec.decodeMediaOffer(plaintext)
        mediaTransferRepository.upsertTransfer(
            StoredMediaTransfer(
                transferId = offer.transferId,
                peerDeviceId = envelope.senderId,
                direction = MediaTransferDirection.Incoming,
                mediaKind = offer.mediaKind,
                fileName = offer.fileName,
                mimeType = offer.mimeType,
                sizeBytes = offer.sizeBytes,
                chunkSizeBytes = offer.chunkSizeBytes,
                totalChunks = offer.totalChunks,
                fileSha256Base64 = offer.fileSha256Base64,
                encryptedTransferKeyBase64 = offer.encryptedTransferKeyBase64,
                sourceUri = null,
                outputPath = null,
                status = MediaTransferStatus.Receiving,
                createdAtEpochMillis = offer.createdAtEpochMillis,
                expiresAtEpochMillis = offer.expiresAtEpochMillis,
                lastUpdatedEpochMillis = now()
            )
        )
        val accept = MediaAcceptPayload(
            transferId = offer.transferId,
            acceptedAtEpochMillis = now()
        )
        sealAndForwardMediaPacket(
            packetType = SecurePacketType.MediaAccept,
            senderId = envelope.receiverId,
            receiverId = envelope.senderId,
            payload = wireCodec.encodeMediaAccept(accept),
            expiresAtEpochMillis = offer.expiresAtEpochMillis
        )
    }

    private suspend fun receiveMediaChunk(
        envelope: SecurePacketEnvelope,
        plaintext: ByteArray
    ) {
        val payload = wireCodec.decodeMediaChunk(plaintext)
        val transfer = mediaTransferRepository.getTransfer(payload.transferId)
            ?: return
        val transferKey = transfer.encryptedTransferKeyBase64.fromBase64()
        mediaTransferEngine.openChunk(transferKey, payload)
        val encryptedPath = mediaFileStore.writeEncryptedChunk(
            transferId = payload.transferId,
            chunkIndex = payload.chunkIndex,
            bytes = payload.encryptedChunkBase64.fromBase64()
        )
        mediaTransferRepository.upsertChunk(
            StoredMediaChunk(
                transferId = payload.transferId,
                chunkIndex = payload.chunkIndex,
                packetId = envelope.packetId,
                offsetBytes = payload.offsetBytes,
                sizeBytes = payload.chunkSizeBytes,
                chunkSha256Base64 = payload.chunkSha256Base64,
                encryptedPath = encryptedPath,
                plaintextPath = null,
                status = MediaChunkStatus.Verified,
                lastUpdatedEpochMillis = now()
            )
        )
        sendMediaChunkAcknowledgement(
            receiverDeviceId = envelope.senderId,
            transferId = payload.transferId,
            chunkIndex = payload.chunkIndex,
            expiresAtEpochMillis = transfer.expiresAtEpochMillis
        )
        completeTransferIfReady(transfer)
    }

    private suspend fun sendMediaChunkAcknowledgement(
        receiverDeviceId: String,
        transferId: String,
        chunkIndex: Int,
        expiresAtEpochMillis: Long
    ) {
        sealAndForwardMediaPacket(
            packetType = SecurePacketType.MediaChunkAcknowledgement,
            senderId = localIdentity?.deviceId ?: return,
            receiverId = receiverDeviceId,
            payload = wireCodec.encodeMediaChunkAcknowledgement(
                MediaChunkAcknowledgementPayload(
                    transferId = transferId,
                    acknowledgedChunkIndexes = listOf(chunkIndex),
                    receivedAtEpochMillis = now()
                )
            ),
            expiresAtEpochMillis = expiresAtEpochMillis
        )
    }

    private suspend fun completeTransferIfReady(
        transfer: StoredMediaTransfer
    ) {
        val chunks = mediaTransferRepository.chunksForTransfer(
            transfer.transferId
        )
        if (chunks.size != transfer.totalChunks) return
        if (chunks.any { it.status != MediaChunkStatus.Verified }) return
        val transferKey = transfer.encryptedTransferKeyBase64.fromBase64()
        val plaintextChunks = chunks.associate { chunk ->
            val encryptedPath = requireNotNull(chunk.encryptedPath)
            val payload = MediaChunkPayload(
                transferId = chunk.transferId,
                chunkIndex = chunk.chunkIndex,
                totalChunks = transfer.totalChunks,
                offsetBytes = chunk.offsetBytes,
                chunkSizeBytes = chunk.sizeBytes,
                chunkSha256Base64 = chunk.chunkSha256Base64,
                encryptedChunkBase64 = mediaFileStore
                    .readEncryptedChunk(encryptedPath)
                    .toBase64()
            )
            chunk.chunkIndex to mediaTransferEngine.openChunk(
                transferKey,
                payload
            )
        }
        val offer = MediaOfferPayload(
            transferId = transfer.transferId,
            mediaKind = transfer.mediaKind,
            fileName = transfer.fileName,
            mimeType = transfer.mimeType,
            sizeBytes = transfer.sizeBytes,
            chunkSizeBytes = transfer.chunkSizeBytes,
            totalChunks = transfer.totalChunks,
            fileSha256Base64 = transfer.fileSha256Base64,
            encryptedTransferKeyBase64 =
                transfer.encryptedTransferKeyBase64,
            createdAtEpochMillis = transfer.createdAtEpochMillis,
            expiresAtEpochMillis = transfer.expiresAtEpochMillis
        )
        val completedBytes = mediaTransferEngine.reassembleTransfer(
            offer,
            plaintextChunks
        )
        val outputPath = mediaFileStore.writeCompletedMedia(
            transferId = transfer.transferId,
            fileName = transfer.fileName,
            bytes = completedBytes
        )
        mediaTransferRepository.upsertTransfer(
            transfer.copy(
                status = MediaTransferStatus.Completed,
                outputPath = outputPath,
                lastUpdatedEpochMillis = now()
            )
        )
        sealAndForwardMediaPacket(
            packetType = SecurePacketType.MediaComplete,
            senderId = localIdentity?.deviceId ?: return,
            receiverId = transfer.peerDeviceId,
            payload = wireCodec.encodeMediaComplete(
                mediaTransferEngine.completePayload(offer)
            ),
            expiresAtEpochMillis = transfer.expiresAtEpochMillis
        )
    }

    private suspend fun receiveMediaChunkAcknowledgement(
        plaintext: ByteArray
    ) {
        val acknowledgement = wireCodec.decodeMediaChunkAcknowledgement(
            plaintext
        )
        acknowledgement.acknowledgedChunkIndexes.forEach { chunkIndex ->
            mediaTransferRepository.updateChunkStatus(
                transferId = acknowledgement.transferId,
                chunkIndex = chunkIndex,
                status = MediaChunkStatus.Sent
            )
        }
    }

    private suspend fun receiveMediaComplete(plaintext: ByteArray) {
        val complete = wireCodec.decodeMediaComplete(plaintext)
        mediaTransferRepository.updateTransferStatus(
            complete.transferId,
            MediaTransferStatus.Completed
        )
    }

    private suspend fun receiveMediaRejected(plaintext: ByteArray) {
        val rejected = wireCodec.decodeMediaReject(plaintext)
        mediaTransferRepository.updateTransferStatus(
            rejected.transferId,
            MediaTransferStatus.Rejected
        )
    }

    private suspend fun sendAcknowledgement(
        receiverDeviceId: String,
        acknowledgedPacketId: String
    ) {
        val identity = localIdentity ?: return
        val session = currentSessionFor(receiverDeviceId) ?: return
        val sentAt = now()
        val envelope = SecurePacketCodec(
            sessionCipher = session.cipher,
            signatureService = signatureService,
            now = now
        ).seal(
            packetType = SecurePacketType.Acknowledgement,
            senderId = identity.deviceId,
            receiverId = receiverDeviceId,
            payload = wireCodec.encodeAcknowledgement(
                DirectAcknowledgementPayload(
                    acknowledgedPacketId = acknowledgedPacketId,
                    receivedAtEpochMillis = sentAt
                )
            ),
            expiresAtEpochMillis = sentAt + ACK_TTL_MILLIS
        )
        packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Queued,
            ownership = PacketOwnership.Own
        )
        forwardEnvelopeToMesh(envelope)
    }

    private suspend fun receiveAcknowledgement(plaintext: ByteArray) {
        val acknowledgement = wireCodec.decodeAcknowledgement(plaintext)
        packetRepository.updateStatus(
            acknowledgement.acknowledgedPacketId,
            PersistedPacketStatus.Acknowledged
        )
        updateMessageStatus(
            acknowledgement.acknowledgedPacketId,
            DirectMessageStatus.Acknowledged
        )
    }

    private fun requireVerifiedPeer(peerDeviceId: String): DirectPeer {
        return _state.value.peers.firstOrNull {
            it.deviceId == peerDeviceId && it.isVerified
        } ?: error("Peer has not been authenticated.")
    }

    private suspend fun sealAndForwardMediaPacket(
        packetType: SecurePacketType,
        senderId: String,
        receiverId: String,
        payload: ByteArray,
        expiresAtEpochMillis: Long
    ): String {
        val session = requireNotNull(currentSessionFor(receiverId)) {
            "Authenticated peer session key is not ready."
        }
        val envelope = SecurePacketCodec(
            sessionCipher = session.cipher,
            signatureService = signatureService,
            now = now
        ).seal(
            packetType = packetType,
            senderId = senderId,
            receiverId = receiverId,
            payload = payload,
            expiresAtEpochMillis = expiresAtEpochMillis
        )
        packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Queued,
            ownership = PacketOwnership.Own
        )
        forwardEnvelopeToMesh(envelope)
        return envelope.packetId
    }

    private fun rememberSession(session: AuthenticatedSession) {
        sessionsByDeviceId.compute(session.peerDeviceId) { _, existing ->
            (existing.orEmpty() + session).takeLast(MAX_SESSION_HISTORY)
        }
    }

    private fun currentSessionFor(
        peerDeviceId: String
    ): AuthenticatedSession? {
        return sessionsByDeviceId[peerDeviceId]?.lastOrNull()
    }

    private fun openWithKnownPeerSession(
        envelope: SecurePacketEnvelope
    ): ByteArray? {
        return sessionsByDeviceId[envelope.senderId]
            .orEmpty()
            .asReversed()
            .firstNotNullOfOrNull { session ->
                SecurePacketCodec(
                    sessionCipher = session.cipher,
                    signatureService = signatureService,
                    now = now
                ).open(
                    envelope,
                    session.peerSigningPublicKey
                ).getOrNull()
            }
    }

    private suspend fun flushPacketsForLink(linkId: String) {
        cachedPackets
            .filter(::isForwardablePacket)
            .map(StoredSecurePacket::envelope)
            .filter { envelope ->
                candidateLinksFor(envelope, incomingLinkId = null)
                    .contains(linkId)
            }
            .forEach { envelope ->
                forwardEnvelopeToLink(linkId, envelope)
            }
    }

    private suspend fun forwardEnvelopeToMesh(
        envelope: SecurePacketEnvelope,
        incomingLinkId: String? = null
    ): Boolean {
        val candidateLinks = candidateLinksFor(envelope, incomingLinkId)
        var forwarded = false
        candidateLinks.forEach { linkId ->
            forwarded = forwardEnvelopeToLink(linkId, envelope) || forwarded
        }
        return forwarded
    }

    private suspend fun forwardEnvelopeToLink(
        linkId: String,
        envelope: SecurePacketEnvelope
    ): Boolean {
        if (isExpired(envelope)) {
            packetRepository.updateStatus(
                envelope.packetId,
                PersistedPacketStatus.Expired
            )
            updateMessageStatus(
                envelope.packetId,
                DirectMessageStatus.Failed
            )
            return false
        }
        val packetLinkKey = "${envelope.packetId}:$linkId"
        if (!forwardedPacketLinks.add(packetLinkKey)) return false
        return transport.send(
            linkId,
            wireCodec.encodePacket(envelope)
        ).onSuccess {
            val status = if (envelope.senderId == localIdentity?.deviceId) {
                PersistedPacketStatus.AwaitingAcknowledgement
            } else {
                PersistedPacketStatus.Forwarding
            }
            packetRepository.updateStatus(envelope.packetId, status)
        }.onFailure {
            forwardedPacketLinks.remove(packetLinkKey)
        }.isSuccess
    }

    private fun candidateLinksFor(
        envelope: SecurePacketEnvelope,
        incomingLinkId: String?
    ): List<String> {
        val directReceiverLinks = sessions
            .filter { (linkId, session) ->
                linkId != incomingLinkId &&
                    session.peerDeviceId == envelope.receiverId
            }
            .keys
            .toList()
        if (directReceiverLinks.isNotEmpty()) return directReceiverLinks

        return sessions
            .filter { (linkId, session) ->
                linkId != incomingLinkId &&
                    session.peerDeviceId != envelope.senderId
            }
            .keys
            .toList()
    }

    private fun isForwardablePacket(packet: StoredSecurePacket): Boolean {
        return when (packet.status) {
            PersistedPacketStatus.Queued,
            PersistedPacketStatus.Forwarding,
            PersistedPacketStatus.AwaitingAcknowledgement -> {
                !isExpired(packet.envelope)
            }

            PersistedPacketStatus.Acknowledged,
            PersistedPacketStatus.Failed,
            PersistedPacketStatus.Expired -> false
        }
    }

    private fun isExpired(envelope: SecurePacketEnvelope): Boolean {
        return envelope.expiresAtEpochMillis?.let { it <= now() } ?: false
    }

    private fun packetOwnershipFor(
        envelope: SecurePacketEnvelope,
        localDeviceId: String
    ): PacketOwnership {
        return if (
            envelope.senderId == localDeviceId ||
            envelope.receiverId == localDeviceId
        ) {
            PacketOwnership.Own
        } else {
            PacketOwnership.Relay
        }
    }

    private fun ensurePeer(linkId: String) {
        if (_state.value.peers.any { it.linkId == linkId }) return
        _state.update {
            it.copy(
                peers = it.peers + DirectPeer(
                    linkId = linkId,
                    advertisedDeviceId = null,
                    deviceId = null,
                    displayName = "CryptoMesh peer",
                    signalStrength = UNKNOWN_SIGNAL_STRENGTH,
                    status = DirectPeerStatus.Authenticating
                )
            )
        }
    }

    private fun markAuthenticationFailure(
        linkId: String,
        message: String
    ) {
        ensurePeer(linkId)
        updatePeer(linkId) {
            it.copy(
                status = DirectPeerStatus.Failed,
                isVerified = false,
                failureMessage = message
            )
        }
    }

    private fun updatePeer(
        linkId: String,
        transform: (DirectPeer) -> DirectPeer
    ) {
        _state.update { state ->
            state.copy(
                peers = state.peers.map {
                    if (it.linkId == linkId) transform(it) else it
                }
            )
        }
    }

    private fun upsertMessage(message: DirectMessage) {
        _state.update { state ->
            state.copy(
                messages = state.messages
                    .filterNot { it.packetId == message.packetId } + message
            )
        }
    }

    private fun updateMessageStatus(
        packetId: String,
        status: DirectMessageStatus
    ) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map {
                    if (it.packetId == packetId) {
                        it.copy(status = status)
                    } else {
                        it
                    }
                }
            )
        }
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 2_000
        const val UNKNOWN_SIGNAL_STRENGTH = -127
        const val MESSAGE_TTL_MILLIS = 24 * 60 * 60 * 1_000L
        const val ACK_TTL_MILLIS = 60 * 60 * 1_000L
        const val MAX_SESSION_HISTORY = 4
    }
}

private fun StoredMediaTransfer.toDirectMediaTransfer(): DirectMediaTransfer {
    return DirectMediaTransfer(
        transferId = transferId,
        peerDeviceId = peerDeviceId,
        mediaKind = mediaKind,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        completedChunks = completedChunks,
        totalChunks = totalChunks,
        isOutgoing = direction == MediaTransferDirection.Outgoing,
        status = status,
        outputPath = outputPath
    )
}

private fun ByteArray.toBase64(): String {
    return Base64.getEncoder().encodeToString(this)
}

private fun String.fromBase64(): ByteArray {
    return Base64.getDecoder().decode(this)
}
