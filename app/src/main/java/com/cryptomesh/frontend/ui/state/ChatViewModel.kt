package com.cryptomesh.frontend.ui.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ChatViewModel : ViewModel() {
    private var nextMessageNumber = 20
    private var nextFileNumber = 10
    private var incomingRequestHandled = false
    private val _uiState = MutableStateFlow(
        ChatUiState(conversations = sampleConversations)
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun openConversation(conversationId: String) {
        if (_uiState.value.conversations.none { it.id == conversationId }) return
        _uiState.update { state ->
            state.copy(
                selectedConversationId = conversationId,
                composerText = "",
                selectedAttachment = null,
                showContactPicker = false,
                incomingFileRequest = if (
                    conversationId == INCOMING_REQUEST_CONVERSATION_ID &&
                    !incomingRequestHandled
                ) {
                    sampleIncomingRequest
                } else {
                    null
                },
                conversations = state.conversations.map { conversation ->
                    if (conversation.id == conversationId) {
                        conversation.copy(unreadCount = 0)
                    } else {
                        conversation
                    }
                }
            )
        }
    }

    fun closeConversation() {
        _uiState.update {
            it.copy(
                selectedConversationId = null,
                composerText = "",
                selectedMessageId = null,
                selectedFileTransferId = null,
                selectedAttachment = null,
                incomingFileRequest = null
            )
        }
    }

    fun updateComposer(text: String) {
        _uiState.update { it.copy(composerText = text.take(MAX_MESSAGE_LENGTH)) }
    }

    fun selectAttachment(
        uri: String,
        name: String,
        mimeType: String,
        sizeBytes: Long
    ) {
        if (_uiState.value.selectedConversationId == null) return
        _uiState.update {
            it.copy(
                selectedAttachment = SelectedAttachmentUiModel(
                    uri = uri,
                    name = name.take(MAX_FILE_NAME_LENGTH),
                    mimeType = mimeType,
                    sizeBytes = sizeBytes.coerceAtLeast(0)
                )
            )
        }
    }

    fun clearAttachment() {
        _uiState.update { it.copy(selectedAttachment = null) }
    }

    fun sendMessage() {
        val state = _uiState.value
        val conversationId = state.selectedConversationId ?: return
        val cleanMessage = state.composerText.trim()
        val attachment = state.selectedAttachment
        if (cleanMessage.isEmpty() && attachment == null) return

        val messageNumber = nextMessageNumber++
        val conversation = state.conversations.first { it.id == conversationId }
        val fileTransfer = attachment?.let {
            createOutgoingFileTransfer(
                attachment = it,
                route = conversation.route
            )
        }
        val message = ChatMessageUiModel(
            id = "message-$messageNumber",
            text = cleanMessage,
            timestamp = "Now",
            isOutgoing = true,
            deliveryStatus = if (fileTransfer == null) {
                MessageDeliveryStatus.QueuedLocally
            } else {
                null
            },
            delivery = if (fileTransfer == null) {
                PacketDeliveryUiModel(
                    packetId = "PKT-CM-${messageNumber.toString().padStart(4, '0')}",
                    priority = PacketPriorityUi.Normal,
                    replicaCount = 0,
                    maximumReplicas = 2,
                    hopCount = 0,
                    maximumHops = 4,
                    expiresAt = "In 24 hours",
                    ackStatus = "Awaiting ACK",
                    lastForwardingAttempt = "Not attempted",
                    relayCount = 0,
                    deliveryPath = listOf("This device")
                )
            } else {
                null
            },
            autoAdvance = fileTransfer == null,
            animateOnAppearance = true,
            fileTransfer = fileTransfer
        )

        _uiState.update { current ->
            current.copy(
                composerText = "",
                selectedAttachment = null,
                conversations = current.conversations.map { conversation ->
                    if (conversation.id == conversationId) {
                        conversation.copy(
                            preview = attachment?.let { "File: ${it.name}" }
                                ?: cleanMessage,
                            timestamp = "Now",
                            messages = conversation.messages + message
                        )
                    } else {
                        conversation
                    }
                }
            )
        }
    }

    fun advanceFileTransfer(fileTransferId: String) {
        val conversation = _uiState.value.conversations.firstOrNull { candidate ->
            candidate.messages.any { it.fileTransfer?.id == fileTransferId }
        } ?: return

        updateFileTransfer(fileTransferId) { transfer ->
            if (!transfer.autoAdvance) return@updateFileTransfer transfer

            if (transfer.isIncoming) {
                return@updateFileTransfer advanceActiveTransfer(transfer)
            }

            when (conversation.route) {
                ConversationRoute.Direct -> advanceActiveTransfer(transfer)
                ConversationRoute.Relay -> advanceRelayTransfer(transfer)
                ConversationRoute.Offline -> when (transfer.status) {
                    FileTransferStatus.Preparing ->
                        transfer.copy(status = FileTransferStatus.WaitingForSuitableConnection)

                    FileTransferStatus.WaitingForSuitableConnection ->
                        transfer.copy(
                            status = FileTransferStatus.Failed,
                            autoAdvance = false
                        )

                    else -> transfer.copy(autoAdvance = false)
                }
            }
        }
    }

    fun retryFileTransfer(fileTransferId: String) {
        val conversation = _uiState.value.conversations.firstOrNull { candidate ->
            candidate.messages.any { it.fileTransfer?.id == fileTransferId }
        } ?: return

        updateFileTransfer(fileTransferId) { transfer ->
            if (!transfer.status.canRetry) return@updateFileTransfer transfer
            transfer.copy(
                status = if (conversation.route == ConversationRoute.Offline) {
                    FileTransferStatus.WaitingForSuitableConnection
                } else {
                    FileTransferStatus.Preparing
                },
                progress = 0f,
                transferredChunks = 0,
                verificationStatus = FileVerificationStatus.NotStarted,
                autoAdvance = true
            )
        }
    }

    fun showFileTransferDetails(fileTransferId: String) {
        val exists = _uiState.value.conversations.any { conversation ->
            conversation.messages.any { it.fileTransfer?.id == fileTransferId }
        }
        if (exists) {
            _uiState.update {
                it.copy(
                    selectedFileTransferId = fileTransferId,
                    selectedMessageId = null
                )
            }
        }
    }

    fun dismissFileTransferDetails() {
        _uiState.update { it.copy(selectedFileTransferId = null) }
    }

    fun acceptIncomingFile() {
        val request = _uiState.value.incomingFileRequest ?: return
        val fileNumber = nextFileNumber++
        val totalChunks = chunkCount(request.sizeBytes)
        val message = ChatMessageUiModel(
            id = "message-file-in-$fileNumber",
            text = "",
            timestamp = "Now",
            isOutgoing = false,
            deliveryStatus = null,
            delivery = null,
            animateOnAppearance = true,
            fileTransfer = FileTransferUiModel(
                id = "file-$fileNumber",
                uri = null,
                name = request.fileName,
                mimeType = request.mimeType,
                sizeBytes = request.sizeBytes,
                status = FileTransferStatus.Transferring,
                progress = 0.12f,
                transferredChunks = chunksForProgress(totalChunks, 0.12f),
                totalChunks = totalChunks,
                verificationStatus = FileVerificationStatus.Pending,
                policy = createPolicy(
                    sizeBytes = request.sizeBytes,
                    route = ConversationRoute.Direct
                ),
                isIncoming = true,
                autoAdvance = true
            )
        )

        incomingRequestHandled = true
        _uiState.update { state ->
            state.copy(
                incomingFileRequest = null,
                conversations = state.conversations.map { conversation ->
                    if (conversation.id == request.conversationId) {
                        conversation.copy(
                            preview = "Receiving ${request.fileName}",
                            timestamp = "Now",
                            messages = conversation.messages + message
                        )
                    } else {
                        conversation
                    }
                }
            )
        }
    }

    fun declineIncomingFile() {
        incomingRequestHandled = true
        _uiState.update { it.copy(incomingFileRequest = null) }
    }

    fun retryMessage(messageId: String) {
        updateMessage(messageId) { message ->
            if (message.deliveryStatus != MessageDeliveryStatus.Failed &&
                message.deliveryStatus != MessageDeliveryStatus.Expired
            ) {
                message
            } else {
                message.copy(
                    deliveryStatus = MessageDeliveryStatus.QueuedLocally,
                    deliveryAttempts = message.deliveryAttempts + 1,
                    autoAdvance = true,
                    delivery = message.delivery?.copy(
                        ackStatus = "Awaiting ACK",
                        lastForwardingAttempt = "Retry queued",
                        expiresAt = "In 24 hours"
                    )
                )
            }
        }
    }

    fun advanceDelivery(messageId: String) {
        val conversation = _uiState.value.conversations.firstOrNull { candidate ->
            candidate.messages.any { it.id == messageId }
        } ?: return

        updateMessage(messageId) { message ->
            if (!message.autoAdvance) return@updateMessage message

            when (message.deliveryStatus) {
                MessageDeliveryStatus.QueuedLocally,
                MessageDeliveryStatus.WaitingForDestination -> {
                    when (conversation.route) {
                        ConversationRoute.Direct -> message.withDeliveryStatus(
                            status = MessageDeliveryStatus.DirectlyDelivered,
                            hopCount = 1,
                            lastAttempt = "Direct delivery completed",
                            path = listOf("This device", conversation.peerName)
                        )

                        ConversationRoute.Relay -> message.withDeliveryStatus(
                            status = MessageDeliveryStatus.StoredOnRelay,
                            replicas = 1,
                            relayCount = 1,
                            lastAttempt = "Stored on trusted relay",
                            path = listOf("This device", "Library Relay")
                        )

                        ConversationRoute.Offline -> {
                            if (message.deliveryAttempts > 1) {
                                message.withDeliveryStatus(
                                    status = MessageDeliveryStatus.StoredOnRelay,
                                    replicas = 1,
                                    relayCount = 1,
                                    lastAttempt = "Retry stored on trusted relay",
                                    path = listOf("This device", "Library Relay")
                                )
                            } else {
                                message.copy(
                                    deliveryStatus = MessageDeliveryStatus.Failed,
                                    autoAdvance = false,
                                    delivery = message.delivery?.copy(
                                        lastForwardingAttempt = "Destination unavailable"
                                    )
                                )
                            }
                        }
                    }
                }

                MessageDeliveryStatus.DirectlyDelivered -> {
                    message.withDeliveryStatus(
                        status = MessageDeliveryStatus.Acknowledged,
                        ackStatus = "End-to-end ACK received",
                        lastAttempt = "Acknowledgement received",
                        autoAdvance = false
                    )
                }

                MessageDeliveryStatus.StoredOnRelay -> {
                    message.withDeliveryStatus(
                        status = MessageDeliveryStatus.CarriedByRelay,
                        hopCount = 1,
                        lastAttempt = "Relay is carrying packet"
                    )
                }

                MessageDeliveryStatus.CarriedByRelay -> {
                    message.withDeliveryStatus(
                        status = MessageDeliveryStatus.Forwarding,
                        hopCount = 2,
                        lastAttempt = "Relay found destination",
                        path = message.delivery?.deliveryPath.orEmpty() + conversation.peerName
                    )
                }

                MessageDeliveryStatus.Forwarding -> {
                    message.withDeliveryStatus(
                        status = MessageDeliveryStatus.Acknowledged,
                        ackStatus = "End-to-end ACK received",
                        lastAttempt = "Acknowledgement received",
                        autoAdvance = false
                    )
                }

                else -> message.copy(autoAdvance = false)
            }
        }
    }

    fun markMessageAnimationComplete(messageId: String) {
        updateMessage(messageId) { message ->
            message.copy(animateOnAppearance = false)
        }
    }

    fun showMessageDetails(messageId: String) {
        val exists = _uiState.value.conversations.any { conversation ->
            conversation.messages.any { it.id == messageId && it.delivery != null }
        }
        if (exists) {
            _uiState.update { it.copy(selectedMessageId = messageId) }
        }
    }

    fun dismissMessageDetails() {
        _uiState.update { it.copy(selectedMessageId = null) }
    }

    fun showContactPicker() {
        _uiState.update { it.copy(showContactPicker = true) }
    }

    fun dismissContactPicker() {
        _uiState.update { it.copy(showContactPicker = false) }
    }

    private fun createOutgoingFileTransfer(
        attachment: SelectedAttachmentUiModel,
        route: ConversationRoute
    ): FileTransferUiModel {
        val fileNumber = nextFileNumber++
        return FileTransferUiModel(
            id = "file-$fileNumber",
            uri = attachment.uri,
            name = attachment.name,
            mimeType = attachment.mimeType,
            sizeBytes = attachment.sizeBytes,
            status = FileTransferStatus.Preparing,
            progress = 0f,
            transferredChunks = 0,
            totalChunks = chunkCount(attachment.sizeBytes),
            verificationStatus = FileVerificationStatus.NotStarted,
            policy = createPolicy(
                sizeBytes = attachment.sizeBytes,
                route = route
            ),
            isIncoming = false,
            autoAdvance = true
        )
    }

    private fun createPolicy(
        sizeBytes: Long,
        route: ConversationRoute
    ): FileReplicationPolicyUiModel {
        val isLargeFile = sizeBytes >= LARGE_FILE_THRESHOLD_BYTES
        return FileReplicationPolicyUiModel(
            isLargeFile = isLargeFile,
            replicationEnabled = !isLargeFile,
            replicaCount = 0,
            maximumReplicas = if (isLargeFile) 0 else 2,
            fileTtl = if (isLargeFile) "6 hours" else "24 hours",
            requiredConnectionQuality = if (isLargeFile) "Strong" else "Stable",
            availableRelayCandidates = when (route) {
                ConversationRoute.Direct -> 2
                ConversationRoute.Relay -> if (isLargeFile) 0 else 1
                ConversationRoute.Offline -> 0
            },
            notice = if (isLargeFile) {
                "Direct transfer is preferred. Replication is disabled to protect relay storage."
            } else {
                "Up to two encrypted replicas may be used when direct delivery is unavailable."
            }
        )
    }

    private fun advanceActiveTransfer(
        transfer: FileTransferUiModel
    ): FileTransferUiModel {
        return when (transfer.status) {
            FileTransferStatus.Preparing,
            FileTransferStatus.WaitingForSuitableConnection,
            FileTransferStatus.StoredForForwarding -> {
                val progress = 0.12f
                transfer.copy(
                    status = FileTransferStatus.Transferring,
                    progress = progress,
                    transferredChunks = chunksForProgress(transfer.totalChunks, progress),
                    verificationStatus = FileVerificationStatus.Pending
                )
            }

            FileTransferStatus.Transferring -> {
                val nextProgress = (transfer.progress + TRANSFER_PROGRESS_STEP)
                    .coerceAtMost(1f)
                if (nextProgress >= 1f) {
                    transfer.copy(
                        status = FileTransferStatus.Verifying,
                        progress = 1f,
                        transferredChunks = transfer.totalChunks,
                        verificationStatus = FileVerificationStatus.Pending
                    )
                } else {
                    transfer.copy(
                        progress = nextProgress,
                        transferredChunks = chunksForProgress(
                            transfer.totalChunks,
                            nextProgress
                        )
                    )
                }
            }

            FileTransferStatus.Verifying -> transfer.copy(
                status = FileTransferStatus.Completed,
                verificationStatus = FileVerificationStatus.Passed,
                autoAdvance = false
            )

            else -> transfer.copy(autoAdvance = false)
        }
    }

    private fun advanceRelayTransfer(
        transfer: FileTransferUiModel
    ): FileTransferUiModel {
        if (transfer.policy.isLargeFile) {
            return when (transfer.status) {
                FileTransferStatus.Preparing -> transfer.copy(
                    status = FileTransferStatus.WaitingForSuitableConnection
                )

                FileTransferStatus.WaitingForSuitableConnection -> transfer.copy(
                    status = FileTransferStatus.InsufficientRelayStorage,
                    autoAdvance = false
                )

                else -> transfer.copy(autoAdvance = false)
            }
        }

        return when (transfer.status) {
            FileTransferStatus.Preparing -> transfer.copy(
                status = FileTransferStatus.StoredForForwarding,
                policy = transfer.policy.copy(replicaCount = 1)
            )

            else -> advanceActiveTransfer(transfer)
        }
    }

    private fun updateFileTransfer(
        fileTransferId: String,
        transform: (FileTransferUiModel) -> FileTransferUiModel
    ) {
        _uiState.update { state ->
            state.copy(
                conversations = state.conversations.map { conversation ->
                    if (conversation.messages.none {
                            it.fileTransfer?.id == fileTransferId
                        }
                    ) {
                        conversation
                    } else {
                        conversation.copy(
                            messages = conversation.messages.map { message ->
                                if (message.fileTransfer?.id == fileTransferId) {
                                    message.copy(
                                        fileTransfer = message.fileTransfer?.let(transform)
                                    )
                                } else {
                                    message
                                }
                            }
                        )
                    }
                }
            )
        }
    }

    private fun chunkCount(sizeBytes: Long): Int {
        if (sizeBytes <= 0) return 1
        return ((sizeBytes + FILE_CHUNK_SIZE_BYTES - 1) / FILE_CHUNK_SIZE_BYTES)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun chunksForProgress(totalChunks: Int, progress: Float): Int {
        return (totalChunks * progress)
            .toInt()
            .coerceIn(0, totalChunks)
    }

    private fun updateMessage(
        messageId: String,
        transform: (ChatMessageUiModel) -> ChatMessageUiModel
    ) {
        _uiState.update { state ->
            state.copy(
                conversations = state.conversations.map { conversation ->
                    if (conversation.messages.none { it.id == messageId }) {
                        conversation
                    } else {
                        conversation.copy(
                            messages = conversation.messages.map { message ->
                                if (message.id == messageId) transform(message) else message
                            }
                        )
                    }
                }
            )
        }
    }

    private fun ChatMessageUiModel.withDeliveryStatus(
        status: MessageDeliveryStatus,
        replicas: Int = delivery?.replicaCount ?: 0,
        hopCount: Int = delivery?.hopCount ?: 0,
        relayCount: Int = delivery?.relayCount ?: 0,
        ackStatus: String = delivery?.ackStatus ?: "Awaiting ACK",
        lastAttempt: String,
        path: List<String> = delivery?.deliveryPath.orEmpty(),
        autoAdvance: Boolean = true
    ): ChatMessageUiModel {
        return copy(
            deliveryStatus = status,
            autoAdvance = autoAdvance,
            delivery = delivery?.copy(
                replicaCount = replicas,
                hopCount = hopCount,
                relayCount = relayCount,
                ackStatus = ackStatus,
                lastForwardingAttempt = lastAttempt,
                deliveryPath = path
            )
        )
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 1_000
        const val MAX_FILE_NAME_LENGTH = 120
        const val FILE_CHUNK_SIZE_BYTES = 256L * 1_024L
        const val LARGE_FILE_THRESHOLD_BYTES = 10L * 1_024L * 1_024L
        const val TRANSFER_PROGRESS_STEP = 0.22f
        const val INCOMING_REQUEST_CONVERSATION_ID = "conversation-meera"

        val sampleIncomingRequest = IncomingFileRequestUiModel(
            id = "incoming-file-01",
            conversationId = INCOMING_REQUEST_CONVERSATION_ID,
            peerName = "Meera's Phone",
            fileName = "field-notes.pdf",
            mimeType = "application/pdf",
            sizeBytes = 3_840_000
        )

        val sampleConversations = listOf(
            ConversationUiModel(
                id = "conversation-aarav",
                peerName = "Aarav's Pixel",
                deviceId = "CM-7A21F4C8",
                preview = "I'm near the north gate.",
                timestamp = "10:42 AM",
                unreadCount = 1,
                isVerifiedSession = true,
                route = ConversationRoute.Direct,
                messages = listOf(
                    ChatMessageUiModel(
                        id = "message-01",
                        text = "Are you still near the campus?",
                        timestamp = "10:36 AM",
                        isOutgoing = true,
                        deliveryStatus = MessageDeliveryStatus.Acknowledged,
                        delivery = directDelivery("PKT-CM-0001", "Aarav's Pixel")
                    ),
                    ChatMessageUiModel(
                        id = "message-02",
                        text = "Yes, I'm near the north gate.",
                        timestamp = "10:42 AM",
                        isOutgoing = false,
                        deliveryStatus = null,
                        delivery = null
                    )
                )
            ),
            ConversationUiModel(
                id = "conversation-meera",
                peerName = "Meera's Phone",
                deviceId = "CM-8C14E7B9",
                preview = "Stored securely for delivery",
                timestamp = "9:18 AM",
                unreadCount = 0,
                isVerifiedSession = true,
                route = ConversationRoute.Relay,
                messages = listOf(
                    ChatMessageUiModel(
                        id = "message-03",
                        text = "Please carry this update to the lab node.",
                        timestamp = "9:18 AM",
                        isOutgoing = true,
                        deliveryStatus = MessageDeliveryStatus.StoredOnRelay,
                        delivery = PacketDeliveryUiModel(
                            packetId = "PKT-CM-0003",
                            priority = PacketPriorityUi.High,
                            replicaCount = 1,
                            maximumReplicas = 2,
                            hopCount = 1,
                            maximumHops = 4,
                            expiresAt = "Today, 9:18 PM",
                            ackStatus = "Awaiting ACK",
                            lastForwardingAttempt = "Stored on trusted relay",
                            relayCount = 1,
                            deliveryPath = listOf("This device", "Library Relay")
                        )
                    )
                )
            ),
            ConversationUiModel(
                id = "conversation-campus",
                peerName = "Campus Node",
                deviceId = "CM-5F33C0A2",
                preview = "Delivery failed",
                timestamp = "Yesterday",
                unreadCount = 0,
                isVerifiedSession = false,
                route = ConversationRoute.Offline,
                messages = listOf(
                    ChatMessageUiModel(
                        id = "message-04",
                        text = "Is the workshop still open?",
                        timestamp = "Yesterday, 6:20 PM",
                        isOutgoing = true,
                        deliveryStatus = MessageDeliveryStatus.Failed,
                        delivery = PacketDeliveryUiModel(
                            packetId = "PKT-CM-0004",
                            priority = PacketPriorityUi.Normal,
                            replicaCount = 0,
                            maximumReplicas = 2,
                            hopCount = 0,
                            maximumHops = 4,
                            expiresAt = "In 12 hours",
                            ackStatus = "No ACK",
                            lastForwardingAttempt = "Destination unavailable",
                            relayCount = 0,
                            deliveryPath = listOf("This device")
                        )
                    ),
                    ChatMessageUiModel(
                        id = "message-05",
                        text = "The previous notice is no longer current.",
                        timestamp = "Monday, 3:10 PM",
                        isOutgoing = true,
                        deliveryStatus = MessageDeliveryStatus.Expired,
                        delivery = PacketDeliveryUiModel(
                            packetId = "PKT-CM-0005",
                            priority = PacketPriorityUi.Normal,
                            replicaCount = 1,
                            maximumReplicas = 1,
                            hopCount = 1,
                            maximumHops = 3,
                            expiresAt = "Expired Monday, 9:10 PM",
                            ackStatus = "No ACK",
                            lastForwardingAttempt = "Replica expired and removed",
                            relayCount = 1,
                            deliveryPath = listOf("This device", "Library Relay")
                        )
                    )
                )
            )
        )

        fun directDelivery(
            packetId: String,
            peerName: String
        ) = PacketDeliveryUiModel(
            packetId = packetId,
            priority = PacketPriorityUi.Normal,
            replicaCount = 0,
            maximumReplicas = 2,
            hopCount = 1,
            maximumHops = 4,
            expiresAt = "Delivered before expiry",
            ackStatus = "End-to-end ACK received",
            lastForwardingAttempt = "Acknowledgement received",
            relayCount = 0,
            deliveryPath = listOf("This device", peerName)
        )
    }
}
