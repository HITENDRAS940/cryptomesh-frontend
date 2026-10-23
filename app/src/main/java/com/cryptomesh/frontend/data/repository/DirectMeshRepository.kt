package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.crypto.DopKeyWrappingService
import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.crypto.SoftwareKeyWrappingService
import com.cryptomesh.frontend.logging.MeshLog
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
import com.cryptomesh.frontend.crypto.GroupCrypto
import com.cryptomesh.frontend.data.local.GroupEntity
import com.cryptomesh.frontend.data.local.InMemoryDopDao
import com.cryptomesh.frontend.protocol.BroadcastTextPayload
import com.cryptomesh.frontend.protocol.GroupInvitePayload
import com.cryptomesh.frontend.protocol.GroupTextPayload
import com.cryptomesh.frontend.protocol.MediaOfferPayload
import com.cryptomesh.frontend.protocol.PendingHandshake
import com.cryptomesh.frontend.protocol.SecurePacketCodec
import com.cryptomesh.frontend.protocol.SecurePacketEnvelope
import com.cryptomesh.frontend.protocol.SecurePacketType
import com.cryptomesh.frontend.protocol.deviceIdForPublicKey
import com.cryptomesh.frontend.protocol.dop.DopEvaluationSummary
import com.cryptomesh.frontend.protocol.dop.DopHandshakeCoordinator
import com.cryptomesh.frontend.protocol.dop.DopInnerPayload
import com.cryptomesh.frontend.protocol.dop.DopInnerPayloadKind
import com.cryptomesh.frontend.protocol.dop.DopMetricsCollector
import com.cryptomesh.frontend.protocol.dop.DopPreKeyRecord
import com.cryptomesh.frontend.protocol.dop.DopPreKeyValidator
import com.cryptomesh.frontend.protocol.dop.DopResponderOutcome
import com.cryptomesh.frontend.relay.DopCacheIngestOutcome
import com.cryptomesh.frontend.relay.DopRelayCacheManager
import com.cryptomesh.frontend.transport.DiscoveredTransportPeer
import com.cryptomesh.frontend.transport.LocalTransportNode
import com.cryptomesh.frontend.transport.NearbyTransport
import com.cryptomesh.frontend.transport.NearbyTransportEvent
import com.cryptomesh.frontend.transport.TransportLinkStatus
import com.cryptomesh.frontend.transport.TransportUnavailableReason
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
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
    val status: DirectMessageStatus,
    val groupId: String? = null,
    val senderDisplayName: String? = null
)

data class DirectGroup(
    val groupId: String,
    val groupName: String,
    val isBroadcastChannel: Boolean,
    val creatorDeviceId: String,
    val members: List<String>,
    val createdAtEpochMillis: Long,
    val lastActivityEpochMillis: Long
)

fun GroupEntity.toDirectGroup(): DirectGroup {
    return DirectGroup(
        groupId = groupId,
        groupName = groupName,
        isBroadcastChannel = isBroadcastChannel,
        creatorDeviceId = creatorDeviceId,
        members = getMemberList(),
        createdAtEpochMillis = createdAtEpochMillis,
        lastActivityEpochMillis = lastActivityEpochMillis
    )
}

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
    val outputPath: String? = null,
    val createdAtEpochMillis: Long
)

data class DirectMeshState(
    val transportAvailable: Boolean = false,
    val transportUnavailableReason: TransportUnavailableReason? = null,
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val scanError: String? = null,
    val peers: List<DirectPeer> = emptyList(),
    val messages: List<DirectMessage> = emptyList(),
    val mediaTransfers: List<DirectMediaTransfer> = emptyList(),
    val groups: List<DirectGroup> = emptyList()
)

interface DirectMeshRepository {
    val state: StateFlow<DirectMeshState>

    fun start()

    fun startScan()

    fun stopScan()

    fun connect(linkId: String)

    fun disconnect(linkId: String)

    suspend fun sendText(peerDeviceId: String, text: String): Result<String>

    suspend fun createGroup(name: String, memberDeviceIds: List<String>): Result<DirectGroup>

    suspend fun sendGroupText(groupId: String, text: String): Result<String>

    suspend fun sendBroadcastText(channelId: String, text: String): Result<String>

    suspend fun sendMedia(
        peerDeviceId: String,
        mediaKind: MediaKind,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<String>

    // Retry a previously-created outgoing transfer. If the transfer has already
    // encrypted chunk files on disk (from a prior attempt), this will re-send
    // the offer and forward those encrypted chunks again. Returns failure if
    // the transfer cannot be retried (missing files, not outgoing, etc.).
    suspend fun retryOutgoingTransfer(transferId: String): Result<Unit>

    suspend fun sendDopText(peerDeviceId: String, text: String): Result<String> =
        sendText(peerDeviceId, text)

    suspend fun getOrPublishOwnPreKeyRecord(
        forceRotateEpoch: Boolean = false
    ): Result<DopPreKeyRecord> = Result.failure(
        UnsupportedOperationException("DOP-Handshake is not configured on this repository.")
    )

    suspend fun importPeerPreKeyRecord(
        record: DopPreKeyRecord,
        sourcePeerDeviceId: String? = null
    ): Result<DopCacheIngestOutcome> = Result.failure(
        UnsupportedOperationException("DOP-Handshake is not configured on this repository.")
    )

    fun getDopEvaluationSummary(): DopEvaluationSummary? = null
}

class BleDirectMeshRepository(
    private val identityRepository: IdentityRepository,
    private val signatureService: PacketSignatureService,
    private val packetRepository: SecurePacketRepository,
    private val mediaTransferRepository: MediaTransferRepository,
    private val mediaFileStore: MediaFileStore,
    private val transport: NearbyTransport,
    private val applicationScope: CoroutineScope,
    private val groupRepository: GroupRepository = MemoryGroupRepository(),
    private val now: () -> Long = System::currentTimeMillis,
    private val wireCodec: DirectWireCodec = DirectWireCodec(),
    private val mediaTransferEngine: MediaTransferEngine =
        MediaTransferEngine(now),
    private val handshake: AuthenticatedHandshake =
        AuthenticatedHandshake(signatureService, now),
    private val dopKeyWrappingService: DopKeyWrappingService =
        SoftwareKeyWrappingService(),
    val dopMetricsCollector: DopMetricsCollector =
        DopMetricsCollector(),
    val dopPreKeyRepository: DopPreKeyRepository =
        RoomDopPreKeyRepository(
            dao = InMemoryDopDao(),
            signatureService = signatureService,
            keyWrappingService = dopKeyWrappingService,
            metrics = dopMetricsCollector,
            now = now
        ),
    val dopPreKeyValidator: DopPreKeyValidator =
        DopPreKeyValidator(
            signatureService = signatureService,
            now = now
        ),
    val dopRelayCacheManager: DopRelayCacheManager =
        DopRelayCacheManager(
            repository = dopPreKeyRepository,
            validator = dopPreKeyValidator,
            metrics = dopMetricsCollector,
            now = now
        ),
    val dopHandshakeCoordinator: DopHandshakeCoordinator =
        DopHandshakeCoordinator(
            repository = dopPreKeyRepository,
            relayCacheManager = dopRelayCacheManager,
            validator = dopPreKeyValidator,
            signatureService = signatureService,
            keyWrappingService = dopKeyWrappingService,
            metrics = dopMetricsCollector,
            now = now
        )
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
                        groupRepository.clearAll()
                        dopPreKeyRepository.clearAll()
                        dopKeyWrappingService.clear()
                        _state.value = DirectMeshState()
                    }
                    localIdentity = identity
                    if (identity != null) {
                        groupRepository.initDefaultChannels(identity.deviceId)
                        runCatching {
                            dopPreKeyRepository.getOrCreateOwnRecord(identity)
                        }
                        val restoredSessions = runCatching {
                            dopHandshakeCoordinator.restorePersistedSessions()
                        }.getOrDefault(emptyList())
                        restoredSessions.forEach { restored ->
                            rememberSession(restored)
                            upsertOfflineVerifiedPeer(
                                peerDeviceId = restored.peerDeviceId,
                                displayName = restored.peerDisplayName
                            )
                        }
                    }
                }
        }
        applicationScope.launch {
            dopRelayCacheManager.cachedRecords.collect { entries ->
                val currentLocalId = localIdentity?.deviceId
                entries.forEach { entry ->
                    if (!entry.isOwnRecord && entry.record.recipientDeviceId != currentLocalId) {
                        upsertOfflineVerifiedPeer(
                            peerDeviceId = entry.record.recipientDeviceId,
                            displayName = entry.record.recipientDisplayName
                        )
                    }
                }
            }
        }
        applicationScope.launch {
            packetRepository.packets.collect { packets ->
                cachedPackets = packets
            }
        }
        applicationScope.launch {
            groupRepository.groups.collect { groups ->
                _state.update {
                    it.copy(groups = groups.map(GroupEntity::toDirectGroup))
                }
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
        dopRelayCacheManager.clearLinkSyncState(linkId)
        removeSessionForLink(linkId)
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

    override suspend fun getOrPublishOwnPreKeyRecord(
        forceRotateEpoch: Boolean
    ): Result<DopPreKeyRecord> = runCatching {
        val identity = localIdentity ?: identityRepository.identity.first().also {
            localIdentity = it
        }
        requireNotNull(identity) {
            "Local identity is not ready."
        }
        dopPreKeyRepository.getOrCreateOwnRecord(
            identity = identity,
            forceRotateEpoch = forceRotateEpoch
        )
    }

    override suspend fun importPeerPreKeyRecord(
        record: DopPreKeyRecord,
        sourcePeerDeviceId: String?
    ): Result<DopCacheIngestOutcome> = runCatching {
        val identity = localIdentity ?: identityRepository.identity.first().also {
            localIdentity = it
        }
        val outcome = dopRelayCacheManager.ingestRecord(
            record = record,
            localDeviceId = identity?.deviceId,
            sourcePeerDeviceId = sourcePeerDeviceId
        )
        when (outcome) {
            is DopCacheIngestOutcome.RejectedInvalid -> {
                error("Rejected invalid DOP pre-key record: ${outcome.reason} (${outcome.detail})")
            }
            is DopCacheIngestOutcome.RejectedConflict -> {
                error("Rejected conflicting DOP pre-key record: ${outcome.reason}")
            }
            is DopCacheIngestOutcome.RejectedFloodLimit -> {
                error("Rejected DOP pre-key record due to flood limit.")
            }
            else -> Unit
        }
        if (outcome.isAcceptedOrUpdated) {
            upsertOfflineVerifiedPeer(
                peerDeviceId = record.recipientDeviceId,
                displayName = record.recipientDisplayName
            )
        }
        outcome
    }

    override fun getDopEvaluationSummary(): DopEvaluationSummary {
        return dopMetricsCollector.snapshot()
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
        ensureVerifiedOrCachedDopPeer(peerDeviceId, identity.deviceId)

        // Prepare the transfer synchronously but perform network sending async
        val prepared = mediaTransferEngine.prepareOutgoingTransfer(
            mediaKind = mediaKind,
            fileName = fileName,
            mimeType = mimeType,
            bytes = bytes
        )

        val previewPath = try {
            mediaFileStore.writeCompletedMedia(
                transferId = prepared.offer.transferId,
                fileName = prepared.offer.fileName,
                bytes = prepared.previewBytes
            )
        } catch (_: Exception) {
            null
        }

        // Persist transfer metadata immediately so UI can show a preview in-chat
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
                outputPath = previewPath,
                status = MediaTransferStatus.Offered,
                createdAtEpochMillis = prepared.offer.createdAtEpochMillis,
                expiresAtEpochMillis = prepared.offer.expiresAtEpochMillis,
                lastUpdatedEpochMillis = now()
            )
        )

        // Create placeholder chunk records (no encrypted file or packetId yet)
        prepared.chunks.forEach { chunk ->
            mediaTransferRepository.upsertChunk(
                StoredMediaChunk(
                    transferId = chunk.transferId,
                    chunkIndex = chunk.chunkIndex,
                    packetId = null,
                    offsetBytes = chunk.offsetBytes,
                    sizeBytes = chunk.sizeBytes,
                    chunkSha256Base64 = chunk.plaintextSha256Base64,
                    encryptedPath = null,
                    plaintextPath = null,
                    status = MediaChunkStatus.Pending,
                    lastUpdatedEpochMillis = now()
                )
            )
        }

        // Kick off background sender so media sending does not block the chat
        applicationScope.launch {
            processOutgoingTransfer(prepared, peerDeviceId, identity.deviceId)
        }

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
        MeshLog.transfer("Initiating sendText to peerDeviceId=$peerDeviceId: \"$cleanText\"")
        ensureVerifiedOrCachedDopPeer(peerDeviceId, identity.deviceId)

        val existingSession = currentSessionFor(peerDeviceId)
        if (existingSession == null) {
            return@runCatching sendDopTextInternal(
                identity = identity,
                peerDeviceId = peerDeviceId,
                cleanText = cleanText
            )
        }

        val codec = SecurePacketCodec(
            sessionCipher = existingSession.cipher,
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
        MeshLog.packet("Direct text sealed: packetId=${envelope.packetId}, cipherChars=${envelope.encryptedPayloadBase64.length}, TTL=${MESSAGE_TTL_MILLIS}ms, queuing...")
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
        MeshLog.error("TRANSFER", "Failed to send direct text to peer $peerDeviceId: ${it.message}", it)
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

    override suspend fun sendDopText(
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
        ensureVerifiedOrCachedDopPeer(peerDeviceId, identity.deviceId)
        sendDopTextInternal(
            identity = identity,
            peerDeviceId = peerDeviceId,
            cleanText = cleanText
        )
    }

    private suspend fun sendDopTextInternal(
        identity: LocalIdentity,
        peerDeviceId: String,
        cleanText: String
    ): String {
        val sentAt = now()
        val packetId = UUID.randomUUID().toString()
        val innerPlaintext = wireCodec.encodeTextPayload(
            DirectTextPayload(
                text = cleanText,
                sentAtEpochMillis = sentAt
            )
        )
        val initiation = dopHandshakeCoordinator.createInitiationForOfflineRecipient(
            localIdentity = identity,
            recipientDeviceId = peerDeviceId,
            packetId = packetId,
            innerKind = DopInnerPayloadKind.TEXT_MESSAGE,
            innerPlaintextBytes = innerPlaintext,
            sentAtEpochMillis = sentAt
        ).getOrThrow()

        rememberSession(initiation.session)
        val envelope = SecurePacketCodec.sealDopInitiation(
            packetId = packetId,
            senderId = identity.deviceId,
            receiverId = peerDeviceId,
            initiationWireBytes = initiation.encodedWireBytes,
            signatureService = signatureService,
            now = now,
            expiresAtEpochMillis = sentAt + MESSAGE_TTL_MILLIS
        )
        MeshLog.packet(
            "DOP initiation text sealed: packetId=${envelope.packetId}, sessionId=${initiation.sessionId}, " +
                "wireBytes=${initiation.encodedWireBytes.size}, queuing for offline store-carry-forward..."
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
        return envelope.packetId
    }

    override suspend fun createGroup(
        name: String,
        memberDeviceIds: List<String>
    ): Result<DirectGroup> = runCatching {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Group name cannot be empty." }
        val identity = requireNotNull(localIdentity) { "Local identity is not ready." }
        val group = groupRepository.createGroup(
            name = cleanName,
            creatorDeviceId = identity.deviceId,
            memberDeviceIds = memberDeviceIds
        )
        memberDeviceIds.forEach { memberId ->
            if (memberId != identity.deviceId) {
                sendGroupInvite(group, memberId)
            }
        }
        group.toDirectGroup()
    }

    private suspend fun sendGroupInvite(group: GroupEntity, recipientDeviceId: String) {
        val identity = localIdentity ?: return
        val sentAt = now()
        val payload = wireCodec.encodeGroupInvite(
            GroupInvitePayload(
                groupId = group.groupId,
                groupName = group.groupName,
                groupKeyBase64 = group.groupKeyBase64,
                creatorDeviceId = group.creatorDeviceId,
                members = group.getMemberList(),
                createdAtEpochMillis = sentAt
            )
        )
        val session = currentSessionFor(recipientDeviceId)
        val envelope = if (session != null) {
            MeshLog.transfer("Sending group invite for '${group.groupName}' (${group.groupId}) to recipient $recipientDeviceId")
            val codec = SecurePacketCodec(session.cipher, signatureService, now)
            codec.seal(
                packetType = SecurePacketType.GroupInvite,
                senderId = identity.deviceId,
                receiverId = recipientDeviceId,
                payload = payload,
                expiresAtEpochMillis = sentAt + MESSAGE_TTL_MILLIS
            )
        } else {
            val packetId = UUID.randomUUID().toString()
            val initiation = dopHandshakeCoordinator.createInitiationForOfflineRecipient(
                localIdentity = identity,
                recipientDeviceId = recipientDeviceId,
                packetId = packetId,
                innerKind = DopInnerPayloadKind.GROUP_INVITE,
                innerPlaintextBytes = payload,
                sentAtEpochMillis = sentAt
            ).getOrElse {
                MeshLog.warn("TRANSFER", "Cannot send group invite to $recipientDeviceId: no active session or DOP pre-key record.")
                return
            }
            rememberSession(initiation.session)
            SecurePacketCodec.sealDopInitiation(
                packetId = packetId,
                senderId = identity.deviceId,
                receiverId = recipientDeviceId,
                initiationWireBytes = initiation.encodedWireBytes,
                signatureService = signatureService,
                now = now,
                expiresAtEpochMillis = sentAt + MESSAGE_TTL_MILLIS
            )
        }
        MeshLog.packet("Group invite sealed: packetId=${envelope.packetId}, forwarding to mesh...")
        packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Queued,
            ownership = PacketOwnership.Own
        )
        forwardEnvelopeToMesh(envelope)
    }

    override suspend fun sendGroupText(
        groupId: String,
        text: String
    ): Result<String> = runCatching {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "Message cannot be empty." }
        require(cleanText.length <= MAX_TEXT_LENGTH) {
            "Message exceeds $MAX_TEXT_LENGTH characters."
        }
        val identity = requireNotNull(localIdentity) { "Local identity is not ready." }
        val group = requireNotNull(groupRepository.getGroup(groupId)) {
            "Group not found: $groupId"
        }
        MeshLog.transfer("Initiating sendGroupText to group '${group.groupName}' ($groupId): \"$cleanText\"")
        val groupKey = GroupCrypto.decodeKey(group.groupKeyBase64)
        val cipher = GroupCrypto.createGroupCipher(groupKey)
        val codec = SecurePacketCodec(
            sessionCipher = cipher,
            signatureService = signatureService,
            now = now
        )
        val sentAt = now()
        val envelope = codec.seal(
            packetType = SecurePacketType.GroupMessage,
            senderId = identity.deviceId,
            receiverId = groupId,
            payload = wireCodec.encodeGroupText(
                GroupTextPayload(
                    groupId = groupId,
                    groupName = group.groupName,
                    senderDisplayName = identity.displayName,
                    text = cleanText,
                    sentAtEpochMillis = sentAt,
                    senderPublicKeyBase64 = Base64.getEncoder().encodeToString(signatureService.publicKeyBytes)
                )
            ),
            expiresAtEpochMillis = sentAt + MESSAGE_TTL_MILLIS
        )
        MeshLog.packet("Group message sealed: packetId=${envelope.packetId}, payloadCipherChars=${envelope.encryptedPayloadBase64.length}, forwarding to mesh...")
        packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Queued,
            ownership = PacketOwnership.Own
        )
        upsertMessage(
            DirectMessage(
                packetId = envelope.packetId,
                peerDeviceId = identity.deviceId,
                text = cleanText,
                sentAtEpochMillis = sentAt,
                isOutgoing = true,
                status = DirectMessageStatus.Queued,
                groupId = groupId,
                senderDisplayName = identity.displayName
            )
        )
        groupRepository.updateLastActivity(groupId, sentAt)
        val forwarded = forwardEnvelopeToMesh(envelope)
        if (forwarded) {
            packetRepository.updateStatus(
                envelope.packetId,
                PersistedPacketStatus.Acknowledged
            )
            updateMessageStatus(
                envelope.packetId,
                DirectMessageStatus.Acknowledged
            )
        }
        envelope.packetId
    }

    override suspend fun sendBroadcastText(
        channelId: String,
        text: String
    ): Result<String> = runCatching {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "Message cannot be empty." }
        require(cleanText.length <= MAX_TEXT_LENGTH) {
            "Message exceeds $MAX_TEXT_LENGTH characters."
        }
        val identity = requireNotNull(localIdentity) { "Local identity is not ready." }
        val group = groupRepository.getGroup(channelId)
        val channelKey = if (group != null) {
            GroupCrypto.decodeKey(group.groupKeyBase64)
        } else {
            GroupCrypto.deriveBroadcastKey(channelId)
        }
        val channelName = group?.groupName ?: if (channelId == RoomGroupRepository.CHANNEL_EMERGENCY_ID) "#emergency" else "#general"
        MeshLog.transfer("Initiating sendBroadcastText to channel '$channelName' ($channelId): \"$cleanText\"")
        val cipher = GroupCrypto.createGroupCipher(channelKey)
        val codec = SecurePacketCodec(
            sessionCipher = cipher,
            signatureService = signatureService,
            now = now
        )
        val sentAt = now()
        val envelope = codec.seal(
            packetType = SecurePacketType.BroadcastMessage,
            senderId = identity.deviceId,
            receiverId = channelId,
            payload = wireCodec.encodeBroadcastText(
                BroadcastTextPayload(
                    channelId = channelId,
                    channelName = channelName,
                    senderDisplayName = identity.displayName,
                    text = cleanText,
                    sentAtEpochMillis = sentAt,
                    senderPublicKeyBase64 = Base64.getEncoder().encodeToString(signatureService.publicKeyBytes)
                )
            ),
            expiresAtEpochMillis = sentAt + MESSAGE_TTL_MILLIS
        )
        MeshLog.packet("Broadcast message sealed: packetId=${envelope.packetId}, payloadCipherChars=${envelope.encryptedPayloadBase64.length}, forwarding to mesh...")
        packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Queued,
            ownership = PacketOwnership.Own
        )
        upsertMessage(
            DirectMessage(
                packetId = envelope.packetId,
                peerDeviceId = identity.deviceId,
                text = cleanText,
                sentAtEpochMillis = sentAt,
                isOutgoing = true,
                status = DirectMessageStatus.Queued,
                groupId = channelId,
                senderDisplayName = identity.displayName
            )
        )
        groupRepository.updateLastActivity(channelId, sentAt)
        val forwarded = forwardEnvelopeToMesh(envelope)
        if (forwarded) {
            packetRepository.updateStatus(
                envelope.packetId,
                PersistedPacketStatus.Acknowledged
            )
            updateMessageStatus(
                envelope.packetId,
                DirectMessageStatus.Acknowledged
            )
        }
        envelope.packetId
    }

    private suspend fun handleTransportEvent(event: NearbyTransportEvent) {
        when (event) {
            is NearbyTransportEvent.Availability -> {
                MeshLog.ble("Transport availability changed: available=${event.available}, advertising=${event.advertising}, message=${event.message ?: "none"}")
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
                MeshLog.ble("Transport scan status changed: scanning=${event.scanning}, error=${event.error ?: "none"}")
                _state.update {
                    it.copy(
                        isScanning = event.scanning,
                        scanError = event.error
                    )
                }
            }

            is NearbyTransportEvent.PeerFound -> {
                MeshLog.ble("Transport peer found: linkId=${event.peer.linkId}, advertisedId=${event.peer.advertisedDeviceId ?: "unknown"}, rssi=${event.peer.signalStrength}dBm")
                addDiscoveredPeer(event.peer)
            }

            is NearbyTransportEvent.LinkChanged -> {
                MeshLog.ble("Transport link changed: linkId=${event.linkId}, status=${event.status}, msg=${event.message ?: "none"}")
                handleLinkChanged(event)
            }

            is NearbyTransportEvent.PayloadReceived -> {
                MeshLog.transfer("Transport payload received: linkId=${event.linkId}, size=${event.payload.size} bytes")
                handlePayload(event.linkId, event.payload)
            }
        }
    }

    private fun addDiscoveredPeer(peer: DiscoveredTransportPeer) {
        _state.update { state ->
            val currentByLink = state.peers.firstOrNull {
                it.linkId == peer.linkId
            }
            val currentByDopDevice = if (currentByLink == null && peer.advertisedDeviceId != null) {
                state.peers.firstOrNull {
                    it.linkId.startsWith(DOP_LINK_PREFIX) &&
                        (it.deviceId == peer.advertisedDeviceId ||
                            it.advertisedDeviceId == peer.advertisedDeviceId)
                }
            } else {
                null
            }
            val base = currentByLink ?: currentByDopDevice
            val updated = base?.copy(
                linkId = peer.linkId,
                advertisedDeviceId = peer.advertisedDeviceId
                    ?: base.advertisedDeviceId,
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
                    .filterNot {
                        it.linkId == peer.linkId ||
                            (currentByDopDevice != null && it.linkId == currentByDopDevice.linkId)
                    } + updated
            )
        }
    }

    private fun handleLinkChanged(
        event: NearbyTransportEvent.LinkChanged
    ) {
        when (event.status) {
            TransportLinkStatus.Connecting -> {
                MeshLog.ble("Link ${event.linkId} connecting...")
                updatePeer(event.linkId) {
                    it.copy(
                        status = DirectPeerStatus.Connecting,
                        failureMessage = null
                    )
                }
            }

            TransportLinkStatus.Connected -> {
                MeshLog.ble("Link ${event.linkId} connected! Initiating mutual authentication...")
                beginHandshake(event.linkId)
            }

            TransportLinkStatus.Disconnected -> {
                MeshLog.ble("Link ${event.linkId} disconnected. Clearing sessions.")
                pendingHandshakes.remove(event.linkId)
                dopRelayCacheManager.clearLinkSyncState(event.linkId)
                removeSessionForLink(event.linkId)
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
                MeshLog.warn("BLE", "Link ${event.linkId} failed: ${event.message}")
                pendingHandshakes.remove(event.linkId)
                dopRelayCacheManager.clearLinkSyncState(event.linkId)
                removeSessionForLink(event.linkId)
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
        if (sessions.containsKey(linkId)) {
            MeshLog.auth("Handshake skipped for linkId=$linkId: session already established.")
            return
        }
        val identity = localIdentity ?: run {
            MeshLog.warn("AUTH", "Cannot begin handshake for linkId=$linkId: local identity not ready.")
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
        MeshLog.auth("Initiating handshake with linkId=$linkId: sending HandshakeHello (device=${identity.deviceId.take(8)}..., ephemeralKey=${pending.hello.ephemeralPublicKeyBase64.take(16)}...)")
        transport.send(
            linkId,
            wireCodec.encodeHandshake(pending.hello)
        ).onFailure {
            MeshLog.error("AUTH", "Failed to send HandshakeHello to linkId=$linkId: ${it.message}")
            markAuthenticationFailure(
                linkId,
                it.message ?: "Unable to send authenticated handshake."
            )
        }
    }

    private suspend fun handlePayload(linkId: String, payload: ByteArray) {
        MeshLog.transfer("Decoding incoming wire message from linkId=$linkId (${payload.size} bytes)...")
        runCatching {
            wireCodec.decode(payload)
        }.onSuccess { decoded ->
            when (decoded) {
                is DecodedDirectWireMessage.Handshake -> {
                    MeshLog.auth("Wire message decoded as HandshakeHello from linkId=$linkId")
                    handleHandshake(linkId, decoded)
                }

                is DecodedDirectWireMessage.Packet -> {
                    MeshLog.packet("Wire message decoded as SecurePacket from linkId=$linkId (packetId=${decoded.envelope.packetId}, type=${decoded.envelope.packetType})")
                    handleSecurePacket(linkId, decoded)
                }

                is DecodedDirectWireMessage.DopPreKeySync -> {
                    MeshLog.auth("Wire message decoded as DopPreKeySync from linkId=$linkId")
                    handleDopPreKeySync(linkId, decoded)
                }
            }
        }.onFailure {
            MeshLog.error("TRANSFER", "Failed to decode wire payload from linkId=$linkId: ${it.message}")
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
        if (sessions.containsKey(linkId)) {
            MeshLog.auth("handleHandshake: linkId=$linkId already authenticated.")
            return
        }
        val identity = localIdentity ?: return
        ensurePeer(linkId)
        val startNanos = System.nanoTime()
        MeshLog.auth("Processing peer HandshakeHello on linkId=$linkId from peerDisplayName='${message.hello.displayName}' (device=${message.hello.deviceId.take(8)}...)")
        val pending = pendingHandshakes[linkId] ?: run {
            val created = handshake.createHello(identity)
            pendingHandshakes[linkId] = created
            MeshLog.auth("Responding with our HandshakeHello to linkId=$linkId...")
            transport.send(
                linkId,
                wireCodec.encodeHandshake(created.hello)
            ).getOrElse {
                MeshLog.error("AUTH", "Unable to answer peer handshake on linkId=$linkId: ${it.message}")
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
            val durationNanos = System.nanoTime() - startNanos
            val helloBytes = wireCodec.encodeHandshake(message.hello).size
            dopMetricsCollector.recordDirectHandshake(durationNanos, helloBytes)

            MeshLog.auth("Mutual authentication SUCCEEDED for linkId=$linkId! Peer verified: deviceId=${session.peerDeviceId}, name='${session.peerDisplayName}'")
            sessions[linkId] = session
            rememberSession(session)
            pendingHandshakes.remove(linkId)
            _state.update { state ->
                val withoutSyntheticDop = state.peers.filterNot {
                    it.linkId == "$DOP_LINK_PREFIX${session.peerDeviceId}" && it.linkId != linkId
                }
                state.copy(
                    peers = withoutSyntheticDop.map {
                        if (it.linkId == linkId) {
                            it.copy(
                                deviceId = session.peerDeviceId,
                                displayName = session.peerDisplayName,
                                status = DirectPeerStatus.Connected,
                                isVerified = true,
                                failureMessage = null
                            )
                        } else {
                            it
                        }
                    }
                )
            }
            applicationScope.launch {
                syncDopPreKeysWithPeer(linkId, session.peerDeviceId)
                flushPacketsForLink(linkId)
            }
        }.onFailure {
            MeshLog.error("AUTH", "Mutual authentication FAILED for linkId=$linkId: ${it.message}", it)
            markAuthenticationFailure(
                linkId,
                it.message ?: "Peer authentication failed."
            )
            transport.disconnect(linkId)
        }
    }

    private suspend fun syncDopPreKeysWithPeer(
        linkId: String,
        peerDeviceId: String
    ) {
        val identity = localIdentity ?: return
        if (!sessions.containsKey(linkId)) return
        runCatching {
            val syncMessage = dopRelayCacheManager.buildSyncMessageForPeer(
                localIdentity = identity,
                targetPeerDeviceId = peerDeviceId,
                linkId = linkId
            )
            val wireBytes = wireCodec.encodeDopPreKeySync(syncMessage)
            transport.send(linkId, wireBytes)
        }.onFailure {
            MeshLog.warn("DOP-SYNC", "Failed to send DopPreKeySync on linkId=$linkId: ${it.message}")
        }
    }

    private suspend fun handleDopPreKeySync(
        linkId: String,
        message: DecodedDirectWireMessage.DopPreKeySync
    ) {
        val identity = localIdentity ?: return
        val linkSession = sessions[linkId] ?: run {
            MeshLog.warn("DOP-SYNC", "Rejected DopPreKeySync on unauthenticated linkId=$linkId")
            return
        }
        val ingestion = dopRelayCacheManager.processIncomingSyncMessage(
            localIdentity = identity,
            sourcePeerDeviceId = linkSession.peerDeviceId,
            linkId = linkId,
            syncMessage = message.syncMessage
        )
        ingestion.newlyAcceptedRecords.forEach { record ->
            upsertOfflineVerifiedPeer(
                peerDeviceId = record.recipientDeviceId,
                displayName = record.recipientDisplayName
            )
        }
        val followUp = ingestion.followUpSyncToReply
        if (followUp != null && sessions.containsKey(linkId)) {
            runCatching {
                transport.send(linkId, wireCodec.encodeDopPreKeySync(followUp))
            }
        }
        if (ingestion.newlyAcceptedRecords.isNotEmpty()) {
            // Propagate newly learned records to any other currently connected links
            val otherLinks = sessions.entries.filter { (otherLinkId, _) ->
                otherLinkId != linkId
            }
            for ((otherLinkId, otherSession) in otherLinks) {
                syncDopPreKeysWithPeer(otherLinkId, otherSession.peerDeviceId)
            }
        }
    }

    private suspend fun handleSecurePacket(
        linkId: String,
        message: DecodedDirectWireMessage.Packet
    ) {
        val identity = localIdentity ?: return
        val envelope = message.envelope
        MeshLog.packet("Processing SecurePacket id=${envelope.packetId}, type=${envelope.packetType}, sender=${envelope.senderId.take(8)}..., receiver=${envelope.receiverId.take(8)}..., wireLink=$linkId")
        sessions[linkId] ?: run {
            MeshLog.warn("AUTH", "SecurePacket rejected: Encrypted data arrived before peer authentication on linkId=$linkId")
            markAuthenticationFailure(
                linkId,
                "Encrypted data arrived before peer authentication."
            )
            return
        }
        if (isExpired(envelope)) {
            MeshLog.warn("PACKET", "SecurePacket id=${envelope.packetId} has expired (TTL exceeded). Discarding.")
            packetRepository.storeIfAbsent(
                envelope = envelope,
                status = PersistedPacketStatus.Expired,
                ownership = packetOwnershipFor(envelope, identity.deviceId)
            )
            return
        }
        if (envelope.senderId == identity.deviceId) {
            MeshLog.packet("SecurePacket id=${envelope.packetId} originated from self (${identity.deviceId.take(8)}...). Ignoring loopback.")
            return
        }
        if (envelope.packetType == SecurePacketType.GroupMessage) {
            MeshLog.packet("SecurePacket id=${envelope.packetId} is a GroupMessage for group=${envelope.receiverId}. Handling group payload...")
            receiveGroupMessage(linkId, envelope)
            return
        }
        if (envelope.packetType == SecurePacketType.BroadcastMessage) {
            MeshLog.packet("SecurePacket id=${envelope.packetId} is a BroadcastMessage for channel=${envelope.receiverId}. Handling broadcast...")
            receiveBroadcastMessage(linkId, envelope)
            return
        }
        if (envelope.receiverId != identity.deviceId) {
            MeshLog.transfer("SecurePacket id=${envelope.packetId} is destined for ${envelope.receiverId.take(8)}... (Relay needed). Relaying to mesh...")
            receiveRelayPacket(linkId, envelope, identity.deviceId)
            return
        }
        if (envelope.packetType == SecurePacketType.DopInitiation) {
            MeshLog.auth("SecurePacket id=${envelope.packetId} is a DOP-Handshake initiation directed to this device.")
            receiveDopInitiation(linkId, envelope, identity)
            return
        }
        MeshLog.packet("SecurePacket id=${envelope.packetId} is directed to this device. Opening payload with peer session...")
        val plaintext = openWithKnownPeerSession(envelope) ?: run {
            MeshLog.warn("PACKET", "Unable to decrypt packet id=${envelope.packetId} with known sessions. Storing for relay queue.")
            packetRepository.storeIfAbsent(
                envelope = envelope,
                status = PersistedPacketStatus.Queued,
                ownership = PacketOwnership.Relay
            )
            return
        }
        dispatchDecryptedUnicastPacket(linkId, envelope, plaintext)
    }

    private suspend fun dispatchDecryptedUnicastPacket(
        linkId: String?,
        envelope: SecurePacketEnvelope,
        plaintext: ByteArray
    ) {
        MeshLog.packet("SecurePacket id=${envelope.packetId} decrypted successfully (${plaintext.size} bytes)! Dispatching type=${envelope.packetType}...")
        runCatching {
            when (envelope.packetType) {
                SecurePacketType.Message -> {
                    receiveText(envelope, plaintext)
                }

                SecurePacketType.GroupInvite -> {
                    receiveGroupInvite(envelope, plaintext)
                }

                SecurePacketType.GroupMessage -> Unit

                SecurePacketType.BroadcastMessage -> Unit

                SecurePacketType.DopInitiation -> Unit

                SecurePacketType.Acknowledgement -> {
                    receiveAcknowledgement(envelope, plaintext)
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

                SecurePacketType.MediaFragment -> {
                    receiveMediaFragment(envelope, plaintext)
                }

                SecurePacketType.MediaChunkAcknowledgement -> {
                    receiveMediaChunkAcknowledgement(plaintext)
                }

                SecurePacketType.MediaComplete -> {
                    receiveMediaComplete(plaintext)
                }
            }
        }.onFailure {
            MeshLog.error("PACKET", "Failed to process decrypted packet ${envelope.packetId}: ${it.message}", it)
            if (linkId != null) {
                updatePeer(linkId) { peer ->
                    peer.copy(
                        failureMessage = it.message
                            ?: "Secure packet verification failed."
                    )
                }
            }
        }
    }

    private suspend fun receiveDopInitiation(
        linkId: String,
        envelope: SecurePacketEnvelope,
        identity: LocalIdentity
    ) {
        val rawWireBytes = runCatching {
            envelope.encryptedPayloadBase64.fromBase64()
        }.getOrNull() ?: return

        val previewWire = runCatching {
            com.cryptomesh.frontend.protocol.dop.DopHandshakeMessageCodec
                .decodeInitiationWirePayload(rawWireBytes)
        }.getOrNull() ?: return

        val senderSigningPubBytes = runCatching {
            previewWire.senderSigningPublicKeyBase64.fromBase64()
        }.getOrNull() ?: return

        if (deviceIdForPublicKey(senderSigningPubBytes) != envelope.senderId) {
            MeshLog.warn("DOP", "Rejected DopInitiation ${envelope.packetId}: outer senderId does not match signing key.")
            return
        }

        val verifiedWireBytes = SecurePacketCodec.openDopInitiationOuter(
            envelope = envelope,
            senderPublicKeyBytes = senderSigningPubBytes,
            signatureService = signatureService,
            now = now
        ).getOrElse { error ->
            MeshLog.warn("DOP", "Outer DopInitiation envelope verification failed for ${envelope.packetId}: ${error.message}")
            return
        }

        val linkPeerId = sessions[linkId]?.peerDeviceId
        val hopCount = if (linkPeerId != null && linkPeerId != envelope.senderId) 2 else 1

        when (
            val outcome = dopHandshakeCoordinator.processIncomingInitiation(
                localIdentity = identity,
                packetId = envelope.packetId,
                outerSenderId = envelope.senderId,
                outerReceiverId = envelope.receiverId,
                initiationWireBytes = verifiedWireBytes,
                hopCount = hopCount
            )
        ) {
            is DopResponderOutcome.Accepted -> {
                rememberSession(outcome.session)
                upsertOfflineVerifiedPeer(
                    peerDeviceId = outcome.session.peerDeviceId,
                    displayName = outcome.session.peerDisplayName
                )
                dispatchDopInnerPayload(
                    envelope = envelope,
                    innerPayload = outcome.innerPayload,
                    dopSessionId = outcome.sessionId,
                    dopConfirmationTagBase64 = outcome.confirmationTagBase64
                )
                reprocessQueuedPacketsForSender(outcome.session.peerDeviceId)
            }

            is DopResponderOutcome.DuplicateInitiationCoalesced -> {
                rememberSession(outcome.session)
                upsertOfflineVerifiedPeer(
                    peerDeviceId = outcome.session.peerDeviceId,
                    displayName = outcome.session.peerDisplayName
                )
                sendAcknowledgement(
                    receiverDeviceId = envelope.senderId,
                    acknowledgedPacketId = outcome.originalPacketId,
                    dopSessionId = outcome.sessionId,
                    dopConfirmationTagBase64 = outcome.confirmationTagBase64
                )
            }

            is DopResponderOutcome.Rejected -> {
                MeshLog.warn(
                    "DOP",
                    "Rejected DOP initiation packet ${envelope.packetId} from ${envelope.senderId}: " +
                        "${outcome.reason} (${outcome.detail})"
                )
            }
        }
    }

    private suspend fun dispatchDopInnerPayload(
        envelope: SecurePacketEnvelope,
        innerPayload: DopInnerPayload,
        dopSessionId: String,
        dopConfirmationTagBase64: String
    ) {
        val innerPlaintext = innerPayload.payloadBase64.fromBase64()
        when (innerPayload.kind) {
            DopInnerPayloadKind.TEXT_MESSAGE -> {
                val text = wireCodec.decodeTextPayload(innerPlaintext)
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
                    acknowledgedPacketId = envelope.packetId,
                    dopSessionId = dopSessionId,
                    dopConfirmationTagBase64 = dopConfirmationTagBase64
                )
            }

            DopInnerPayloadKind.MEDIA_OFFER -> {
                packetRepository.storeIfAbsent(
                    envelope = envelope,
                    status = PersistedPacketStatus.Acknowledged,
                    ownership = PacketOwnership.Own
                )
                receiveMediaOffer(envelope, innerPlaintext)
                sendAcknowledgement(
                    receiverDeviceId = envelope.senderId,
                    acknowledgedPacketId = envelope.packetId,
                    dopSessionId = dopSessionId,
                    dopConfirmationTagBase64 = dopConfirmationTagBase64
                )
            }

            DopInnerPayloadKind.GROUP_INVITE -> {
                val invite = wireCodec.decodeGroupInvite(innerPlaintext)
                val inserted = packetRepository.storeIfAbsent(
                    envelope = envelope,
                    status = PersistedPacketStatus.Acknowledged,
                    ownership = PacketOwnership.Own
                )
                if (inserted) {
                    groupRepository.joinGroup(
                        GroupEntity(
                            groupId = invite.groupId,
                            groupName = invite.groupName,
                            isBroadcastChannel = false,
                            creatorDeviceId = invite.creatorDeviceId,
                            groupKeyBase64 = invite.groupKeyBase64,
                            membersCsv = invite.members.joinToString(","),
                            createdAtEpochMillis = invite.createdAtEpochMillis,
                            lastActivityEpochMillis = now()
                        )
                    )
                }
                sendAcknowledgement(
                    receiverDeviceId = envelope.senderId,
                    acknowledgedPacketId = envelope.packetId,
                    dopSessionId = dopSessionId,
                    dopConfirmationTagBase64 = dopConfirmationTagBase64
                )
            }

            DopInnerPayloadKind.MEDIA_CHUNK -> {
                packetRepository.storeIfAbsent(
                    envelope = envelope,
                    status = PersistedPacketStatus.Acknowledged,
                    ownership = PacketOwnership.Own
                )
                receiveMediaChunk(envelope, innerPlaintext)
            }

            DopInnerPayloadKind.MEDIA_FRAGMENT -> {
                packetRepository.storeIfAbsent(
                    envelope = envelope,
                    status = PersistedPacketStatus.Acknowledged,
                    ownership = PacketOwnership.Own
                )
                receiveMediaFragment(envelope, innerPlaintext)
            }

            DopInnerPayloadKind.MEDIA_ACCEPT -> Unit

            DopInnerPayloadKind.MEDIA_REJECT -> {
                receiveMediaRejected(innerPlaintext)
            }

            DopInnerPayloadKind.MEDIA_CHUNK_ACK -> {
                receiveMediaChunkAcknowledgement(innerPlaintext)
            }

            DopInnerPayloadKind.MEDIA_COMPLETE -> {
                receiveMediaComplete(innerPlaintext)
            }

            DopInnerPayloadKind.SESSION_BOOTSTRAP -> {
                packetRepository.storeIfAbsent(
                    envelope = envelope,
                    status = PersistedPacketStatus.Acknowledged,
                    ownership = PacketOwnership.Own
                )
                sendAcknowledgement(
                    receiverDeviceId = envelope.senderId,
                    acknowledgedPacketId = envelope.packetId,
                    dopSessionId = dopSessionId,
                    dopConfirmationTagBase64 = dopConfirmationTagBase64
                )
            }
        }
    }

    private suspend fun reprocessQueuedPacketsForSender(senderDeviceId: String) {
        val localId = localIdentity?.deviceId ?: return
        val queuedFromSender = cachedPackets
            .filter {
                it.status == PersistedPacketStatus.Queued &&
                    it.envelope.receiverId == localId &&
                    it.envelope.senderId == senderDeviceId &&
                    it.envelope.packetType != SecurePacketType.DopInitiation &&
                    !isExpired(it.envelope)
            }
            .sortedBy { it.envelope.createdAtEpochMillis }

        for (stored in queuedFromSender) {
            val plaintext = openWithKnownPeerSession(stored.envelope) ?: continue
            packetRepository.updateStatus(
                stored.envelope.packetId,
                PersistedPacketStatus.Acknowledged
            )
            dispatchDecryptedUnicastPacket(
                linkId = null,
                envelope = stored.envelope,
                plaintext = plaintext
            )
        }
    }

    private suspend fun receiveGroupInvite(
        envelope: SecurePacketEnvelope,
        plaintext: ByteArray
    ) {
        val invite = wireCodec.decodeGroupInvite(plaintext)
        MeshLog.packet("Received group invite for group '${invite.groupName}' (${invite.groupId}) from ${invite.creatorDeviceId.take(8)}...")
        val inserted = packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Acknowledged,
            ownership = PacketOwnership.Own
        )
        if (inserted) {
            groupRepository.joinGroup(
                GroupEntity(
                    groupId = invite.groupId,
                    groupName = invite.groupName,
                    isBroadcastChannel = false,
                    creatorDeviceId = invite.creatorDeviceId,
                    groupKeyBase64 = invite.groupKeyBase64,
                    membersCsv = invite.members.joinToString(","),
                    createdAtEpochMillis = invite.createdAtEpochMillis,
                    lastActivityEpochMillis = now()
                )
            )
        }
        sendAcknowledgement(
            receiverDeviceId = envelope.senderId,
            acknowledgedPacketId = envelope.packetId
        )
    }

    private suspend fun receiveGroupMessage(
        incomingLinkId: String,
        envelope: SecurePacketEnvelope
    ) {
        val inserted = packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Acknowledged,
            ownership = PacketOwnership.Own
        )
        if (inserted) {
            val group = groupRepository.getGroup(envelope.receiverId)
            if (group != null) {
                runCatching {
                    val groupKey = GroupCrypto.decodeKey(group.groupKeyBase64)
                    val cipher = GroupCrypto.createGroupCipher(groupKey)
                    val plaintext = openGroupEnvelope(envelope, cipher)
                    if (plaintext != null) {
                        val groupText = wireCodec.decodeGroupText(plaintext)
                        MeshLog.packet("Received group message in '${group.groupName}' from ${groupText.senderDisplayName} (${envelope.senderId.take(8)}...): \"${groupText.text}\" (packetId=${envelope.packetId})")
                        upsertMessage(
                            DirectMessage(
                                packetId = envelope.packetId,
                                peerDeviceId = envelope.senderId,
                                text = groupText.text,
                                sentAtEpochMillis = groupText.sentAtEpochMillis,
                                isOutgoing = false,
                                status = DirectMessageStatus.Acknowledged,
                                groupId = group.groupId,
                                senderDisplayName = groupText.senderDisplayName
                            )
                        )
                        groupRepository.updateLastActivity(group.groupId, groupText.sentAtEpochMillis)
                    } else {
                        MeshLog.warn("PACKET", "Failed to decrypt group message envelope for group ${group.groupName}")
                    }
                }.onFailure {
                    MeshLog.error("PACKET", "Error processing group message: ${it.message}", it)
                }
            } else {
                MeshLog.warn("PACKET", "Received group packet for unknown group ${envelope.receiverId}")
            }
        }
        MeshLog.transfer("Flooding GroupMessage packetId=${envelope.packetId} across mesh (excluding linkId=$incomingLinkId)...")
        forwardEnvelopeToMesh(envelope, incomingLinkId)
    }

    private suspend fun receiveBroadcastMessage(
        incomingLinkId: String,
        envelope: SecurePacketEnvelope
    ) {
        val inserted = packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Acknowledged,
            ownership = PacketOwnership.Own
        )
        if (inserted) {
            runCatching {
                val channelKey = GroupCrypto.deriveBroadcastKey(envelope.receiverId)
                val cipher = GroupCrypto.createGroupCipher(channelKey)
                val plaintext = openGroupEnvelope(envelope, cipher)
                if (plaintext != null) {
                    val broadcastText = wireCodec.decodeBroadcastText(plaintext)
                    MeshLog.packet("Received broadcast message in '${broadcastText.channelName}' from ${broadcastText.senderDisplayName} (${envelope.senderId.take(8)}...): \"${broadcastText.text}\" (packetId=${envelope.packetId})")
                    upsertMessage(
                        DirectMessage(
                            packetId = envelope.packetId,
                            peerDeviceId = envelope.senderId,
                            text = broadcastText.text,
                            sentAtEpochMillis = broadcastText.sentAtEpochMillis,
                            isOutgoing = false,
                            status = DirectMessageStatus.Acknowledged,
                            groupId = broadcastText.channelId,
                            senderDisplayName = broadcastText.senderDisplayName
                        )
                    )
                    groupRepository.updateLastActivity(broadcastText.channelId, broadcastText.sentAtEpochMillis)
                } else {
                    MeshLog.warn("PACKET", "Failed to decrypt broadcast message envelope for channel ${envelope.receiverId}")
                }
            }.onFailure {
                MeshLog.error("PACKET", "Error processing broadcast message: ${it.message}", it)
            }
        }
        MeshLog.transfer("Flooding BroadcastMessage packetId=${envelope.packetId} across mesh (excluding linkId=$incomingLinkId)...")
        forwardEnvelopeToMesh(envelope, incomingLinkId)
    }

    private fun openGroupEnvelope(
        envelope: SecurePacketEnvelope,
        cipher: com.cryptomesh.frontend.crypto.SessionCipher
    ): ByteArray? = runCatching {
        val directKey = sessionsByDeviceId[envelope.senderId]
            ?.firstOrNull()
            ?.peerSigningPublicKey

        val codec = SecurePacketCodec(cipher, signatureService, now)
        if (directKey != null) {
            val opened = codec.open(envelope, directKey).getOrNull()
            if (opened != null) return opened
        }

        val unverified = codec.decryptPayloadWithoutVerification(envelope).getOrNull() ?: return null
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val payloadStr = unverified.decodeToString()
        val pubKeyBase64 = runCatching {
            json.decodeFromString<GroupTextPayload>(payloadStr).senderPublicKeyBase64
        }.getOrNull() ?: runCatching {
            json.decodeFromString<BroadcastTextPayload>(payloadStr).senderPublicKeyBase64
        }.getOrNull()

        if (pubKeyBase64 != null) {
            val pubKeyBytes = Base64.getDecoder().decode(pubKeyBase64)
            if (deviceIdForPublicKey(pubKeyBytes) == envelope.senderId) {
                return codec.open(envelope, pubKeyBytes).getOrNull()
            }
        }
        null
    }.getOrNull()

    private suspend fun receiveRelayPacket(
        incomingLinkId: String,
        envelope: SecurePacketEnvelope,
        localDeviceId: String
    ) {
        MeshLog.transfer("Relay packet received: packetId=${envelope.packetId}, sender=${envelope.senderId.take(8)}..., dest=${envelope.receiverId.take(8)}..., incomingLink=$incomingLinkId")
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
        MeshLog.packet("Received direct message from ${envelope.senderId.take(8)}...: \"${text.text}\" (packetId=${envelope.packetId})")
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
        MeshLog.transfer("Sending direct ACK for packetId=${envelope.packetId} to ${envelope.senderId.take(8)}...")
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

    private suspend fun receiveMediaFragment(
        envelope: SecurePacketEnvelope,
        plaintext: ByteArray
    ) {
        val payload = wireCodec.decodeMediaFragment(plaintext)
        val transfer = mediaTransferRepository.getTransfer(payload.transferId) ?: return
        // write fragment and attempt assembly
        val assembledPath = try {
            mediaFileStore.writeEncryptedFragment(
                transferId = payload.transferId,
                chunkIndex = payload.chunkIndex,
                fragmentIndex = payload.fragmentIndex,
                totalFragments = payload.totalFragments,
                bytes = payload.encryptedFragmentBase64.fromBase64()
            )
        } catch (_: Exception) {
            null
        }
        if (assembledPath != null) {
            // assembled, verify and persist chunk
            val transferKey = transfer.encryptedTransferKeyBase64.fromBase64()
            // Build a MediaChunkPayload to verify integrity
        val encryptedBytes = mediaFileStore.readEncryptedChunk(assembledPath)
        val reconstructedPayload = MediaChunkPayload(
            transferId = payload.transferId,
            chunkIndex = payload.chunkIndex,
            totalChunks = transfer.totalChunks,
            offsetBytes = payload.offsetBytes,
            chunkSizeBytes = payload.chunkSizeBytes,
            chunkSha256Base64 = payload.chunkSha256Base64,
            encryptedChunkBase64 = encryptedBytes.toBase64()
        )
        // Validate by attempting to open chunk (may throw)
        try {
            mediaTransferEngine.openChunk(transferKey, reconstructedPayload)
            mediaTransferRepository.upsertChunk(
                StoredMediaChunk(
                    transferId = reconstructedPayload.transferId,
                    chunkIndex = reconstructedPayload.chunkIndex,
                    packetId = envelope.packetId,
                    offsetBytes = reconstructedPayload.offsetBytes,
                    sizeBytes = reconstructedPayload.chunkSizeBytes,
                    chunkSha256Base64 = reconstructedPayload.chunkSha256Base64,
                    encryptedPath = assembledPath,
                    plaintextPath = null,
                    status = MediaChunkStatus.Verified,
                    lastUpdatedEpochMillis = now()
                )
            )
            sendMediaChunkAcknowledgement(
                receiverDeviceId = envelope.senderId,
                transferId = reconstructedPayload.transferId,
                chunkIndex = reconstructedPayload.chunkIndex,
                expiresAtEpochMillis = transfer.expiresAtEpochMillis
            )
            completeTransferIfReady(transfer)
        } catch (_: Exception) {
            // if verification fails, mark chunk failed
            mediaTransferRepository.updateChunkStatus(
                transferId = payload.transferId,
                chunkIndex = payload.chunkIndex,
                status = MediaChunkStatus.Failed
            )
        }
        }
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
        finalizeOutgoingTransferIfAcknowledged(acknowledgement.transferId)
    }

    private suspend fun finalizeOutgoingTransferIfAcknowledged(
        transferId: String
    ) {
        val transfer = mediaTransferRepository.getTransfer(transferId) ?: return
        val chunks = mediaTransferRepository.chunksForTransfer(transferId)
        if (chunks.isEmpty()) return
        if (chunks.all { it.status == MediaChunkStatus.Sent } &&
            transfer.totalChunks == chunks.size
        ) {
            mediaTransferRepository.updateTransferStatus(
                transferId,
                MediaTransferStatus.Completed
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
        acknowledgedPacketId: String,
        dopSessionId: String? = null,
        dopConfirmationTagBase64: String? = null
    ) {
        val identity = localIdentity ?: return
        val session = currentSessionFor(receiverDeviceId) ?: run {
            MeshLog.warn("TRANSFER", "Cannot send ACK for packetId=$acknowledgedPacketId: no active session for receiver $receiverDeviceId")
            return
        }
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
                    receivedAtEpochMillis = sentAt,
                    dopSessionId = dopSessionId,
                    dopConfirmationTagBase64 = dopConfirmationTagBase64
                )
            ),
            expiresAtEpochMillis = sentAt + ACK_TTL_MILLIS
        )
        MeshLog.transfer("Generated & sealed ACK packet id=${envelope.packetId} for target packetId=$acknowledgedPacketId to receiver ${receiverDeviceId.take(8)}...")
        packetRepository.storeIfAbsent(
            envelope = envelope,
            status = PersistedPacketStatus.Queued,
            ownership = PacketOwnership.Own
        )
        forwardEnvelopeToMesh(envelope)
    }

    private suspend fun receiveAcknowledgement(
        envelope: SecurePacketEnvelope,
        plaintext: ByteArray
    ) {
        val acknowledgement = wireCodec.decodeAcknowledgement(plaintext)
        if (
            acknowledgement.dopSessionId != null ||
            acknowledgement.dopConfirmationTagBase64 != null
        ) {
            val confirmed = dopHandshakeCoordinator.confirmSessionFromAcknowledgement(
                initiationPacketId = acknowledgement.acknowledgedPacketId,
                responderDeviceId = envelope.senderId,
                dopSessionId = acknowledgement.dopSessionId,
                dopConfirmationTagBase64 = acknowledgement.dopConfirmationTagBase64
            )
            if (!confirmed) {
                MeshLog.warn(
                    "DOP",
                    "Rejected DOP acknowledgement for packetId=${acknowledgement.acknowledgedPacketId} " +
                        "from ${envelope.senderId}: confirmation tag verification failed."
                )
                return
            }
        } else {
            dopHandshakeCoordinator.confirmSessionFromAcknowledgement(
                initiationPacketId = acknowledgement.acknowledgedPacketId,
                responderDeviceId = envelope.senderId,
                dopSessionId = null,
                dopConfirmationTagBase64 = null
            )
        }
        MeshLog.transfer("Delivery ACK RECEIVED for packetId=${acknowledgement.acknowledgedPacketId} (status -> Acknowledged)!")
        packetRepository.updateStatus(
            acknowledgement.acknowledgedPacketId,
            PersistedPacketStatus.Acknowledged
        )
        updateMessageStatus(
            acknowledgement.acknowledgedPacketId,
            DirectMessageStatus.Acknowledged
        )
    }

    private suspend fun ensureVerifiedOrCachedDopPeer(
        peerDeviceId: String,
        localDeviceId: String
    ): DirectPeer {
        val existingVerified = _state.value.peers.firstOrNull {
            it.deviceId == peerDeviceId && it.isVerified
        }
        if (existingVerified != null) return existingVerified

        val cachedEntry = dopRelayCacheManager.getVerifiedRecordForRecipient(
            recipientDeviceId = peerDeviceId,
            localDeviceId = localDeviceId
        )
        if (cachedEntry != null) {
            upsertOfflineVerifiedPeer(
                peerDeviceId = cachedEntry.record.recipientDeviceId,
                displayName = cachedEntry.record.recipientDisplayName
            )
            return _state.value.peers.firstOrNull {
                it.deviceId == peerDeviceId && it.isVerified
            } ?: DirectPeer(
                linkId = "$DOP_LINK_PREFIX$peerDeviceId",
                advertisedDeviceId = peerDeviceId,
                deviceId = peerDeviceId,
                displayName = cachedEntry.record.recipientDisplayName,
                signalStrength = UNKNOWN_SIGNAL_STRENGTH,
                status = DirectPeerStatus.Discovered,
                isVerified = true
            )
        }
        error("Peer has not been authenticated.")
    }

    private suspend fun sealAndForwardMediaPacket(
        packetType: SecurePacketType,
        senderId: String,
        receiverId: String,
        payload: ByteArray,
        expiresAtEpochMillis: Long
    ): String {
        MeshLog.transfer("Sealing media packet: type=$packetType, sender=${senderId.take(8)}..., receiver=${receiverId.take(8)}...")
        val existingSession = currentSessionFor(receiverId)
        val envelope = if (existingSession != null) {
            SecurePacketCodec(
                sessionCipher = existingSession.cipher,
                signatureService = signatureService,
                now = now
            ).seal(
                packetType = packetType,
                senderId = senderId,
                receiverId = receiverId,
                payload = payload,
                expiresAtEpochMillis = expiresAtEpochMillis
            )
        } else if (packetType == SecurePacketType.MediaOffer) {
            val identity = requireNotNull(localIdentity) {
                "Local identity is not ready."
            }
            val packetId = UUID.randomUUID().toString()
            val initiation = dopHandshakeCoordinator.createInitiationForOfflineRecipient(
                localIdentity = identity,
                recipientDeviceId = receiverId,
                packetId = packetId,
                innerKind = DopInnerPayloadKind.MEDIA_OFFER,
                innerPlaintextBytes = payload,
                sentAtEpochMillis = now()
            ).getOrThrow()
            rememberSession(initiation.session)
            SecurePacketCodec.sealDopInitiation(
                packetId = packetId,
                senderId = senderId,
                receiverId = receiverId,
                initiationWireBytes = initiation.encodedWireBytes,
                signatureService = signatureService,
                now = now,
                expiresAtEpochMillis = expiresAtEpochMillis
            )
        } else {
            error("Authenticated peer session key is not ready.")
        }
        MeshLog.packet("Media packet sealed: id=${envelope.packetId}, payloadCipherChars=${envelope.encryptedPayloadBase64.length}, forwarding to mesh...")
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

    private fun removeSessionForLink(linkId: String) {
        val removed = sessions.remove(linkId) ?: return
        sessionsByDeviceId.compute(removed.peerDeviceId) { _, existing ->
            existing?.filterNot { it !== removed }?.takeIf { it.isNotEmpty() }
        }

        // Clean up any in-progress media transfers for the peer when the
        // underlying transport link is removed. Run asynchronously.
        applicationScope.launch {
            try {
                // Collect a snapshot of transfers and remove any transient ones.
                val transient = setOf(
                    MediaTransferStatus.Offered,
                    MediaTransferStatus.Accepted,
                    MediaTransferStatus.Queued,
                    MediaTransferStatus.Transferring,
                    MediaTransferStatus.Receiving,
                    MediaTransferStatus.Verifying
                )
                val transfersSnapshot = mediaTransferRepository.transfers.first()
                transfersSnapshot
                    .filter { it.peerDeviceId == removed.peerDeviceId }
                    .filter { it.status in transient }
                    .forEach { transfer ->
                        // Mark transfer as failed so UI shows a failed card instead
                        // of silently removing the transfer and its files. Also mark
                        // any associated chunks as Failed for clarity.
                        try {
                            mediaTransferRepository.updateTransferStatus(
                                transfer.transferId,
                                MediaTransferStatus.Failed
                            )
                        } catch (_: Exception) {
                            // Best-effort: ignore DB failures here
                        }

                        transfer.chunks.forEach { chunk ->
                            try {
                                mediaTransferRepository.updateChunkStatus(
                                    transfer.transferId,
                                    chunk.chunkIndex,
                                    MediaChunkStatus.Failed
                                )
                            } catch (_: Exception) {
                                // Best-effort: ignore per-chunk failures
                            }
                        }
                    }
            } catch (_: Exception) {
                // swallow errors to avoid crashing repository event handlers
            }
        }
    }

    private fun currentSessionFor(
        peerDeviceId: String
    ): AuthenticatedSession? {
        // Prefer an active session associated with a transport link.
        val active = sessions.values.lastOrNull { it.peerDeviceId == peerDeviceId }
        return active ?: sessionsByDeviceId[peerDeviceId]?.lastOrNull()
    }

    private suspend fun processOutgoingTransfer(
        prepared: PreparedMediaTransfer,
        receiverDeviceId: String,
        senderDeviceId: String
    ) {
        // Throttle chunk sends and ensure an active session exists for each send.
        try {
            mediaTransferRepository.updateTransferStatus(
                prepared.offer.transferId,
                MediaTransferStatus.Transferring
            )

            // Send MediaOffer
            val offerSent = runCatching {
                sealAndForwardMediaPacket(
                    packetType = SecurePacketType.MediaOffer,
                    senderId = senderDeviceId,
                    receiverId = receiverDeviceId,
                    payload = wireCodec.encodeMediaOffer(prepared.offer),
                    expiresAtEpochMillis = prepared.offer.expiresAtEpochMillis
                )
            }
            if (offerSent.isFailure) {
                mediaTransferRepository.updateTransferStatus(
                    prepared.offer.transferId,
                    MediaTransferStatus.Failed
                )
                return
            }

            // Iterate chunks: seal, write file, forward, and mark Sent.
            // Insert a small delay between sends to avoid saturating transport.
            val chunkDelayMs = 100L
            prepared.chunks.forEach { chunk ->
                // If authenticated session is lost, abort and mark failed.
                if (currentSessionFor(receiverDeviceId) == null) {
                    mediaTransferRepository.updateTransferStatus(
                        prepared.offer.transferId,
                        MediaTransferStatus.Failed
                    )
                    return
                }

                val payload = mediaTransferEngine.sealChunk(
                    transferKey = prepared.transferKey,
                    chunk = chunk,
                    totalChunks = prepared.offer.totalChunks
                )

                val encryptedPath = try {
                    mediaFileStore.writeEncryptedChunk(
                        transferId = chunk.transferId,
                        chunkIndex = chunk.chunkIndex,
                        bytes = payload.encryptedChunkBase64.fromBase64()
                    )
                } catch (e: Exception) {
                    null
                }

                // Fragment the encrypted payload to BLE-friendly fragments and send as MediaFragment packets
                val encryptedBytes = payload.encryptedChunkBase64.fromBase64()
                val fragmentSize = 1024 // BLE-friendly fragment size
                val totalFragments = (encryptedBytes.size + fragmentSize - 1) / fragmentSize
                var lastPacketId: String? = null
                var assembledPath: String? = null
                for (fragmentIndex in 0 until totalFragments) {
                    val start = fragmentIndex * fragmentSize
                    val end = minOf(start + fragmentSize, encryptedBytes.size)
                    val fragmentBytes = encryptedBytes.copyOfRange(start, end)
                    val fragmentPayload = com.cryptomesh.frontend.protocol.MediaFragmentPayload(
                        transferId = payload.transferId,
                        chunkIndex = payload.chunkIndex,
                        offsetBytes = chunk.offsetBytes,
                        chunkSizeBytes = payload.chunkSizeBytes,
                        fragmentIndex = fragmentIndex,
                        totalFragments = totalFragments,
                        chunkSha256Base64 = payload.chunkSha256Base64,
                        encryptedFragmentBase64 = fragmentBytes.toBase64()
                    )
                    val fragPacketId = runCatching {
                        sealAndForwardMediaPacket(
                            packetType = SecurePacketType.MediaFragment,
                            senderId = senderDeviceId,
                            receiverId = receiverDeviceId,
                            payload = wireCodec.encodeMediaFragment(fragmentPayload),
                            expiresAtEpochMillis = prepared.offer.expiresAtEpochMillis
                        )
                    }.getOrNull()

                    lastPacketId = fragPacketId ?: lastPacketId
                    if (fragPacketId == null) {
                        mediaTransferRepository.updateChunkStatus(
                            transferId = chunk.transferId,
                            chunkIndex = chunk.chunkIndex,
                            status = MediaChunkStatus.Failed
                        )
                        mediaTransferRepository.updateTransferStatus(
                            prepared.offer.transferId,
                            MediaTransferStatus.Failed
                        )
                        return
                    }

                    // Save fragment to disk as we go (best-effort) and capture assembled path if assembly completes
                    var assembledSoFar: String? = null
                    try {
                        val maybeAssembled = mediaFileStore.writeEncryptedFragment(
                            transferId = chunk.transferId,
                            chunkIndex = chunk.chunkIndex,
                            fragmentIndex = fragmentIndex,
                            totalFragments = totalFragments,
                            bytes = fragmentBytes
                        )
                        if (maybeAssembled != null) assembledPath = maybeAssembled
                    } catch (_: Exception) {
                        // ignore write failures
                    }

                    // small delay between fragments
                    try { kotlinx.coroutines.delay(50L) } catch (_: Exception) {}
                }

                // After all fragments sent, assembledPath may have been set during fragment writes
                // Use last fragment's packet id as representative packet id for the chunk
                val packetId = lastPacketId

                // Update chunk storage with packet id and file path and mark Sent (or Failed)
                if (packetId != null) {
                    mediaTransferRepository.updateChunkStorage(
                        transferId = chunk.transferId,
                        chunkIndex = chunk.chunkIndex,
                        packetId = packetId,
                        status = MediaChunkStatus.Sent,
                        encryptedPath = assembledPath ?: encryptedPath,
                        plaintextPath = null
                    )
                } else {
                    mediaTransferRepository.updateChunkStatus(
                        transferId = chunk.transferId,
                        chunkIndex = chunk.chunkIndex,
                        status = MediaChunkStatus.Failed
                    )
                    mediaTransferRepository.updateTransferStatus(
                        prepared.offer.transferId,
                        MediaTransferStatus.Failed
                    )
                    return
                }

                // Yield to the scheduler and avoid transport saturation
                try {
                    kotlinx.coroutines.delay(chunkDelayMs)
                } catch (_: Exception) {
                    // ignore cancellation during delay
                }
            }

            // All chunks forwarded; remain in Transferring awaiting remote ACK/complete
            mediaTransferRepository.updateTransferStatus(
                prepared.offer.transferId,
                MediaTransferStatus.Transferring
            )
        } catch (_: Exception) {
            mediaTransferRepository.updateTransferStatus(
                prepared.offer.transferId,
                MediaTransferStatus.Failed
            )
        }
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

    override suspend fun retryOutgoingTransfer(transferId: String): Result<Unit> = runCatching {
        val transfer = mediaTransferRepository.getTransfer(transferId)
            ?: error("Transfer not found.")
        if (transfer.direction != MediaTransferDirection.Outgoing) {
            error("Can only retry outgoing transfers.")
        }

        val identity = localIdentity ?: error("Local identity not ready.")

        // Build offer payload from stored transfer record
        val offer = MediaOfferPayload(
            transferId = transfer.transferId,
            mediaKind = transfer.mediaKind,
            fileName = transfer.fileName,
            mimeType = transfer.mimeType,
            sizeBytes = transfer.sizeBytes,
            chunkSizeBytes = transfer.chunkSizeBytes,
            totalChunks = transfer.totalChunks,
            fileSha256Base64 = transfer.fileSha256Base64,
            encryptedTransferKeyBase64 = transfer.encryptedTransferKeyBase64,
            createdAtEpochMillis = transfer.createdAtEpochMillis,
            expiresAtEpochMillis = transfer.expiresAtEpochMillis
        )

        // Mark transfer as transferring in DB
        mediaTransferRepository.updateTransferStatus(
            transferId,
            MediaTransferStatus.Transferring
        )

        // Ensure there is an active session before attempting retry
        if (currentSessionFor(transfer.peerDeviceId) == null) {
            error("No active authenticated session for peer ${transfer.peerDeviceId}.")
        }

        // Send MediaOffer
        val offerSent = runCatching {
            sealAndForwardMediaPacket(
                packetType = SecurePacketType.MediaOffer,
                senderId = identity.deviceId,
                receiverId = transfer.peerDeviceId,
                payload = wireCodec.encodeMediaOffer(offer),
                expiresAtEpochMillis = transfer.expiresAtEpochMillis
            )
        }
        if (offerSent.isFailure) {
            mediaTransferRepository.updateTransferStatus(
                transferId,
                MediaTransferStatus.Failed
            )
            error("Failed to send media offer.")
        }

        // Iterate existing chunk files (encrypted) and forward them.
        val chunks = mediaTransferRepository.chunksForTransfer(transferId)
        chunks.forEach { chunk ->
            val encryptedPath = chunk.encryptedPath
                ?: error("Missing encrypted chunk file for index ${chunk.chunkIndex}.")
            val encryptedBytes = mediaFileStore.readEncryptedChunk(encryptedPath)
            val payload = MediaChunkPayload(
                transferId = chunk.transferId,
                chunkIndex = chunk.chunkIndex,
                totalChunks = transfer.totalChunks,
                offsetBytes = chunk.offsetBytes,
                chunkSizeBytes = chunk.sizeBytes,
                chunkSha256Base64 = chunk.chunkSha256Base64,
                encryptedChunkBase64 = encryptedBytes.toBase64()
            )

            val packetId = runCatching {
                sealAndForwardMediaPacket(
                    packetType = SecurePacketType.MediaChunk,
                    senderId = identity.deviceId,
                    receiverId = transfer.peerDeviceId,
                    payload = wireCodec.encodeMediaChunk(payload),
                    expiresAtEpochMillis = transfer.expiresAtEpochMillis
                )
            }.getOrNull()

            if (packetId != null) {
                mediaTransferRepository.updateChunkStorage(
                    transferId = chunk.transferId,
                    chunkIndex = chunk.chunkIndex,
                    packetId = packetId,
                    status = MediaChunkStatus.Sent,
                    encryptedPath = encryptedPath,
                    plaintextPath = null
                )
            } else {
                mediaTransferRepository.updateChunkStatus(
                    transferId = chunk.transferId,
                    chunkIndex = chunk.chunkIndex,
                    status = MediaChunkStatus.Failed
                )
                mediaTransferRepository.updateTransferStatus(
                    transferId,
                    MediaTransferStatus.Failed
                )
                error("Failed to send chunk ${chunk.chunkIndex}.")
            }
        }

        // All chunks forwarded, remain in Transferring state awaiting remote
        mediaTransferRepository.updateTransferStatus(
            transferId,
            MediaTransferStatus.Transferring
        )
    }

    private suspend fun flushPacketsForLink(linkId: String) {
        val forwardable = cachedPackets
            .filter(::isForwardablePacket)
            .map(StoredSecurePacket::envelope)
            .filter { envelope ->
                candidateLinksFor(envelope, incomingLinkId = null)
                    .contains(linkId)
            }
            .sortedWith(
                compareBy<SecurePacketEnvelope> {
                    if (it.packetType == SecurePacketType.DopInitiation) 0 else 1
                }.thenBy { it.createdAtEpochMillis }
            )
        if (forwardable.isNotEmpty()) {
            MeshLog.transfer("Flushing ${forwardable.size} queued packets for newly authenticated linkId=$linkId...")
        }
        forwardable.forEach { envelope ->
            forwardEnvelopeToLink(linkId, envelope)
        }
    }

    private suspend fun forwardEnvelopeToMesh(
        envelope: SecurePacketEnvelope,
        incomingLinkId: String? = null
    ): Boolean {
        val candidateLinks = candidateLinksFor(envelope, incomingLinkId)
        MeshLog.transfer("forwardEnvelopeToMesh: packetId=${envelope.packetId}, type=${envelope.packetType}, candidateLinks=${candidateLinks.size} (${candidateLinks.joinToString()})")
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
            MeshLog.warn("TRANSFER", "Cannot forward expired packetId=${envelope.packetId} to linkId=$linkId")
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
        if (!forwardedPacketLinks.add(packetLinkKey)) {
            MeshLog.transfer("Packet packetId=${envelope.packetId} already forwarded to linkId=$linkId. Skipping duplicate.")
            return false
        }
        val wireBytes = wireCodec.encodePacket(envelope)
        MeshLog.transfer("Transmitting packetId=${envelope.packetId} over linkId=$linkId (${wireBytes.size} wire bytes)...")
        return transport.send(
            linkId,
            wireBytes
        ).onSuccess {
            val status = if (envelope.senderId == localIdentity?.deviceId) {
                PersistedPacketStatus.AwaitingAcknowledgement
            } else {
                PersistedPacketStatus.Forwarding
            }
            MeshLog.transfer("Successfully transmitted packetId=${envelope.packetId} over linkId=$linkId (status=$status)")
            packetRepository.updateStatus(envelope.packetId, status)
        }.onFailure {
            MeshLog.warn("TRANSFER", "Failed to transmit packetId=${envelope.packetId} over linkId=$linkId: ${it.message}")
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

    private fun upsertOfflineVerifiedPeer(
        peerDeviceId: String,
        displayName: String
    ) {
        if (peerDeviceId == localIdentity?.deviceId) return
        _state.update { state ->
            val existingByDevice = state.peers.firstOrNull {
                it.deviceId == peerDeviceId || it.advertisedDeviceId == peerDeviceId
            }
            if (existingByDevice != null) {
                state.copy(
                    peers = state.peers.map { peer ->
                        if (peer.linkId == existingByDevice.linkId) {
                            peer.copy(
                                deviceId = peerDeviceId,
                                displayName = if (peer.displayName == "CryptoMesh peer" || peer.displayName.startsWith("Peer ")) {
                                    displayName
                                } else {
                                    peer.displayName
                                },
                                isVerified = true
                            )
                        } else {
                            peer
                        }
                    }
                )
            } else {
                state.copy(
                    peers = state.peers + DirectPeer(
                        linkId = "$DOP_LINK_PREFIX$peerDeviceId",
                        advertisedDeviceId = peerDeviceId,
                        deviceId = peerDeviceId,
                        displayName = displayName,
                        signalStrength = UNKNOWN_SIGNAL_STRENGTH,
                        status = DirectPeerStatus.Discovered,
                        isVerified = true
                    )
                )
            }
        }
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 2_000
        const val UNKNOWN_SIGNAL_STRENGTH = -127
        const val MESSAGE_TTL_MILLIS = 24 * 60 * 60 * 1_000L
        const val ACK_TTL_MILLIS = 60 * 60 * 1_000L
        const val MAX_SESSION_HISTORY = 4
        const val DOP_LINK_PREFIX = "dop:"
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
    outputPath = outputPath,
    createdAtEpochMillis = createdAtEpochMillis
)
}

private fun ByteArray.toBase64(): String {
    return Base64.getEncoder().encodeToString(this)
}

private fun String.fromBase64(): ByteArray {
    return Base64.getDecoder().decode(this)
}
