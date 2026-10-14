package com.cryptomesh.frontend.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cryptomesh.frontend.data.repository.DirectMeshRepository
import com.cryptomesh.frontend.data.repository.DirectMediaTransfer
import com.cryptomesh.frontend.data.repository.DirectMeshState
import com.cryptomesh.frontend.data.repository.DirectMessage
import com.cryptomesh.frontend.data.repository.DirectMessageStatus
import com.cryptomesh.frontend.data.repository.DirectPeer
import com.cryptomesh.frontend.data.repository.DirectPeerStatus
import com.cryptomesh.frontend.data.repository.MediaTransferStatus
import com.cryptomesh.frontend.protocol.MediaKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val directMeshRepository: DirectMeshRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            directMeshRepository.state.collect(::applyDirectMeshState)
        }
    }

    fun openConversation(conversationId: String) {
        if (_uiState.value.conversations.none { it.id == conversationId }) {
            return
        }
        _uiState.update {
            it.copy(
                selectedConversationId = conversationId,
                composerText = "",
                errorMessage = null
            )
        }
    }

    fun closeConversation() {
        _uiState.update {
            it.copy(
                selectedConversationId = null,
                composerText = "",
                errorMessage = null
            )
        }
    }

    fun updateComposer(text: String) {
        _uiState.update {
            it.copy(composerText = text.take(MAX_MESSAGE_LENGTH))
        }
    }

    fun selectAttachment(
        uri: String,
        mediaKind: MediaKind,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        bytes: ByteArray
    ) {
        if (_uiState.value.selectedConversationId == null) return
        _uiState.update {
            it.copy(
                selectedAttachment = MediaAttachmentUiModel(
                    uri = uri,
                    mediaKind = mediaKind,
                    fileName = fileName.take(MAX_FILE_NAME_LENGTH),
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    bytes = bytes
                ),
                errorMessage = null
            )
        }
    }

    fun clearAttachment() {
        _uiState.update { it.copy(selectedAttachment = null) }
    }

    fun sendMessage() {
        val state = _uiState.value
        val conversation = state.conversations.firstOrNull {
            it.id == state.selectedConversationId
        } ?: return
        val text = state.composerText.trim()
        val attachment = state.selectedAttachment
        if (text.isEmpty() && attachment == null) return
        if (!conversation.isVerifiedSession) {
            _uiState.update {
                it.copy(
                    errorMessage =
                        "The peer has not completed authentication."
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                composerText = "",
                selectedAttachment = null,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            if (text.isNotEmpty()) {
                val result = when {
                    conversation.isBroadcastChannel -> {
                        directMeshRepository.sendBroadcastText(
                            channelId = conversation.deviceId,
                            text = text
                        )
                    }
                    conversation.isGroup -> {
                        directMeshRepository.sendGroupText(
                            groupId = conversation.deviceId,
                            text = text
                        )
                    }
                    else -> {
                        directMeshRepository.sendText(
                            conversation.deviceId,
                            text
                        )
                    }
                }
                result.onFailure { error ->
                    showSendError(error, "Message delivery failed.")
                }
            }
            if (attachment != null) {
                directMeshRepository.sendMedia(
                    peerDeviceId = conversation.deviceId,
                    mediaKind = attachment.mediaKind,
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType,
                    bytes = attachment.bytes
                ).onFailure { error ->
                    showSendError(error, "Media delivery failed.")
                }
            }
        }
    }

    fun retryMessage(messageId: String) {
        val conversation = _uiState.value.conversations
            .firstOrNull { candidate ->
                candidate.messages.any {
                    it.id == messageId &&
                        it.deliveryStatus == MessageDeliveryStatus.Failed
                }
            } ?: return
        val message = conversation.messages.first {
            it.id == messageId
        }
        if (!conversation.isVerifiedSession) {
            _uiState.update {
                it.copy(errorMessage = "Peer has not completed authentication.")
            }
            return
        }
        viewModelScope.launch {
            val result = when {
                conversation.isBroadcastChannel ->
                    directMeshRepository.sendBroadcastText(conversation.deviceId, message.text)
                conversation.isGroup ->
                    directMeshRepository.sendGroupText(conversation.deviceId, message.text)
                else ->
                    directMeshRepository.sendText(conversation.deviceId, message.text)
            }
            result.onFailure { error ->
                _uiState.update {
                    it.copy(
                        errorMessage = error.message
                            ?: "Message retry failed."
                    )
                }
            }
        }
    }

    fun retryMedia(transferId: String) {
        val conversation = _uiState.value.conversations
            .firstOrNull { candidate ->
                candidate.mediaTransfers.any {
                    it.id == transferId && it.isOutgoing && it.status == MediaTransferStatus.Failed
                }
            } ?: return
        if (!conversation.isVerifiedSession) {
            _uiState.update {
                it.copy(errorMessage = "Peer has not completed authentication.")
            }
            return
        }
        viewModelScope.launch {
            directMeshRepository.retryOutgoingTransfer(transferId).onFailure { error ->
                _uiState.update {
                    it.copy(
                        errorMessage = error.message ?: "Media retry failed."
                    )
                }
            }
        }
    }

    private fun showSendError(error: Throwable, fallback: String) {
        _uiState.update {
            it.copy(errorMessage = error.message ?: fallback)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun openCreateGroupDialog() {
        val verifiedPeers = _uiState.value.conversations
            .filter { !it.isGroup && it.isVerifiedSession }
            .map { PeerOption(it.deviceId, it.peerName) }
        _uiState.update {
            it.copy(
                isCreateGroupDialogOpen = true,
                availablePeersForGroup = verifiedPeers,
                errorMessage = null
            )
        }
    }

    fun closeCreateGroupDialog() {
        _uiState.update { it.copy(isCreateGroupDialogOpen = false) }
    }

    fun clearGroupSuccessMessage() {
        _uiState.update { it.copy(groupCreationSuccessMessage = null) }
    }

    fun createGroup(name: String, selectedMemberDeviceIds: List<String>) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Group name cannot be empty.") }
            return
        }
        viewModelScope.launch {
            directMeshRepository.createGroup(cleanName, selectedMemberDeviceIds)
                .onSuccess { group ->
                    _uiState.update {
                        it.copy(
                            isCreateGroupDialogOpen = false,
                            selectedConversationId = GROUP_CONVERSATION_PREFIX + group.groupId,
                            groupCreationSuccessMessage = "Group '${group.groupName}' created!"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "Failed to create group.")
                    }
                }
        }
    }

    private fun applyDirectMeshState(meshState: DirectMeshState) {
        val groupConversations = meshState.groups.map { group ->
            val groupMessages = meshState.messages
                .filter { it.groupId == group.groupId }
                .sortedBy(DirectMessage::sentAtEpochMillis)
                .map(DirectMessage::toUiModel)
            val latest = groupMessages.lastOrNull()
            ConversationUiModel(
                id = GROUP_CONVERSATION_PREFIX + group.groupId,
                peerName = group.groupName,
                deviceId = group.groupId,
                preview = latest?.text ?: if (group.isBroadcastChannel) "Public mesh broadcast" else "${group.members.size} members",
                timestamp = latest?.timestamp ?: if (group.isBroadcastChannel) "Broadcast" else "Group",
                isConnected = true,
                isVerifiedSession = true,
                messages = groupMessages,
                mediaTransfers = emptyList(),
                isGroup = true,
                isBroadcastChannel = group.isBroadcastChannel,
                memberCount = group.members.size
            )
        }

        val peerConversations = meshState.peers
            .filter { it.deviceId != null }
            .groupBy { requireNotNull(it.deviceId) }
            .values
            .map { peers ->
                peers.maxWithOrNull(
                    compareBy<DirectPeer> {
                        it.chatAvailabilityPriority()
                    }
                        .thenBy { it.isVerified }
                        .thenBy { it.signalStrength }
                ) ?: error("A peer group cannot be empty.")
            }
            .map { peer ->
                peer.toConversation(
                    directMessages = meshState.messages.filter {
                        it.groupId == null && it.peerDeviceId == peer.deviceId
                    },
                    mediaTransfers = meshState.mediaTransfers.filter {
                        it.peerDeviceId == peer.deviceId
                    }
                )
            }

        val conversations = (groupConversations + peerConversations).sortedWith(
            compareByDescending<ConversationUiModel> { it.isBroadcastChannel }
                .thenByDescending { it.messages.lastOrNull()?.createdAtEpochMillis ?: 0L }
        )

        _uiState.update { current ->
            current.copy(
                conversations = conversations,
                selectedConversationId =
                    current.selectedConversationId?.takeIf { selected ->
                        conversations.any { it.id == selected }
                    }
            )
        }
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 1_000
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val LIVE_CONVERSATION_PREFIX = "live-"
        private const val GROUP_CONVERSATION_PREFIX = "group-"

        fun factory(
            repository: DirectMeshRepository
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>
                ): T = ChatViewModel(repository) as T
            }
        }
    }

    private fun DirectPeer.toConversation(
        directMessages: List<DirectMessage>,
        mediaTransfers: List<DirectMediaTransfer>
    ): ConversationUiModel {
        val authenticatedDeviceId = requireNotNull(deviceId)
        val messages = directMessages
            .sortedBy(DirectMessage::sentAtEpochMillis)
            .map(DirectMessage::toUiModel)
        val latest = messages.lastOrNull()
        return ConversationUiModel(
            id = LIVE_CONVERSATION_PREFIX + authenticatedDeviceId,
            peerName = displayName,
            deviceId = authenticatedDeviceId,
            preview = latest?.text ?: "Authenticated session",
            timestamp = latest?.timestamp ?: "Nearby",
            isConnected = status == DirectPeerStatus.Connected,
            isVerifiedSession = isVerified,
            messages = messages,
            mediaTransfers = mediaTransfers
                .sortedByDescending(DirectMediaTransfer::transferId)
                .map(DirectMediaTransfer::toUiModel)
        )
    }
}

private fun DirectPeer.chatAvailabilityPriority(): Int {
    return when (status) {
        DirectPeerStatus.Connected -> 4
        DirectPeerStatus.Authenticating -> 3
        DirectPeerStatus.Connecting -> 2
        DirectPeerStatus.Discovered -> 1
        DirectPeerStatus.Failed -> 0
    }
}

private fun DirectMediaTransfer.toUiModel(): MediaTransferUiModel {
    val safeTotal = totalChunks.coerceAtLeast(1)
    val progress = (completedChunks.toFloat() / safeTotal)
        .coerceIn(0f, 1f)
    // Use transferId hash as deterministic created-at fallback when not provided
    val createdAt = try {
        0L
    } catch (_: Exception) {
        0L
    }
    return MediaTransferUiModel(
        id = transferId,
        mediaKind = mediaKind,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        progressText = "$completedChunks / $totalChunks chunks",
        progress = if (status == MediaTransferStatus.Completed) 1f else progress,
        isOutgoing = isOutgoing,
        status = status,
        outputPath = outputPath,
        createdAtEpochMillis = createdAtEpochMillis
    )
}

private fun DirectMessage.toUiModel(): ChatMessageUiModel {
    return ChatMessageUiModel(
        id = packetId,
        text = text,
        timestamp = formatMessageTime(sentAtEpochMillis),
        isOutgoing = isOutgoing,
        deliveryStatus = if (!isOutgoing) {
            null
        } else {
            when (status) {
                DirectMessageStatus.Queued ->
                    MessageDeliveryStatus.Queued
                DirectMessageStatus.Sending ->
                    MessageDeliveryStatus.Sending
                DirectMessageStatus.AwaitingAcknowledgement ->
                    MessageDeliveryStatus.AwaitingAcknowledgement
                DirectMessageStatus.Acknowledged ->
                    MessageDeliveryStatus.Acknowledged
                DirectMessageStatus.Failed ->
                    MessageDeliveryStatus.Failed
            }
        },
        createdAtEpochMillis = sentAtEpochMillis,
        senderDisplayName = senderDisplayName,
        senderDeviceId = peerDeviceId
    )
}

private fun formatMessageTime(epochMillis: Long): String {
    return DateTimeFormatter.ofPattern("h:mm a")
        .format(
            Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
        )
}
