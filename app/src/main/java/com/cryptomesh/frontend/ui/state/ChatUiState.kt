package com.cryptomesh.frontend.ui.state

import com.cryptomesh.frontend.data.repository.MediaTransferStatus
import com.cryptomesh.frontend.protocol.MediaKind

enum class MessageDeliveryStatus {
    Queued,
    Sending,
    AwaitingAcknowledgement,
    Acknowledged,
    Failed
}

data class MediaAttachmentUiModel(
    val uri: String,
    val mediaKind: MediaKind,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bytes: ByteArray
)

data class MediaTransferUiModel(
    val id: String,
    val mediaKind: MediaKind,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val progressText: String,
    val progress: Float,
    val isOutgoing: Boolean,
    val status: MediaTransferStatus,
    val outputPath: String?,
    val createdAtEpochMillis: Long
)

data class ChatMessageUiModel(
    val id: String,
    val text: String,
    val timestamp: String,
    val isOutgoing: Boolean,
    val deliveryStatus: MessageDeliveryStatus?,
    val createdAtEpochMillis: Long
)

data class ConversationUiModel(
    val id: String,
    val peerName: String,
    val deviceId: String,
    val preview: String,
    val timestamp: String,
    val isConnected: Boolean,
    val isVerifiedSession: Boolean,
    val messages: List<ChatMessageUiModel>,
    val mediaTransfers: List<MediaTransferUiModel> = emptyList()
)

data class ChatUiState(
    val conversations: List<ConversationUiModel> = emptyList(),
    val selectedConversationId: String? = null,
    val composerText: String = "",
    val selectedAttachment: MediaAttachmentUiModel? = null,
    val errorMessage: String? = null
)
