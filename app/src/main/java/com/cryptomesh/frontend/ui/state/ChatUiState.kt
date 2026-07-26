package com.cryptomesh.frontend.ui.state

enum class MessageDeliveryStatus(
    val userLabel: String,
    val technicalLabel: String
) {
    QueuedLocally("Waiting for connection", "Queued locally"),
    WaitingForDestination("Waiting for connection", "Waiting for destination"),
    DirectlyDelivered("Delivered", "Directly delivered"),
    StoredOnRelay("Stored securely", "Stored on relay"),
    CarriedByRelay("On the way", "Carried by relay"),
    Forwarding("Forwarding", "Forwarding to destination"),
    Acknowledged("Delivered", "Acknowledged"),
    Expired("Delivery expired", "Expired"),
    ReplicaLimitReached("Waiting for connection", "Replica limit reached"),
    Failed("Delivery failed", "Delivery failed")
}

enum class PacketPriorityUi(val label: String) {
    Normal("Normal"),
    High("High"),
    Emergency("Emergency")
}

enum class ConversationRoute(
    val label: String,
    val supportingText: String
) {
    Direct(
        label = "Direct connection",
        supportingText = "Messages can be delivered directly to this peer."
    ),
    Relay(
        label = "Relay available",
        supportingText = "Messages can be stored securely until the destination is reachable."
    ),
    Offline(
        label = "Destination unavailable",
        supportingText = "Outgoing messages will wait locally for a connection."
    )
}

data class PacketDeliveryUiModel(
    val packetId: String,
    val priority: PacketPriorityUi,
    val replicaCount: Int,
    val maximumReplicas: Int,
    val hopCount: Int,
    val maximumHops: Int,
    val expiresAt: String,
    val ackStatus: String,
    val lastForwardingAttempt: String,
    val relayCount: Int,
    val deliveryPath: List<String>
)

data class ChatMessageUiModel(
    val id: String,
    val text: String,
    val timestamp: String,
    val isOutgoing: Boolean,
    val deliveryStatus: MessageDeliveryStatus?,
    val delivery: PacketDeliveryUiModel?,
    val deliveryAttempts: Int = 1,
    val autoAdvance: Boolean = false,
    val animateOnAppearance: Boolean = false,
    val fileTransfer: FileTransferUiModel? = null
)

data class ConversationUiModel(
    val id: String,
    val peerName: String,
    val deviceId: String,
    val preview: String,
    val timestamp: String,
    val unreadCount: Int,
    val isVerifiedSession: Boolean,
    val route: ConversationRoute,
    val messages: List<ChatMessageUiModel>
)

data class ChatUiState(
    val conversations: List<ConversationUiModel> = emptyList(),
    val selectedConversationId: String? = null,
    val composerText: String = "",
    val selectedMessageId: String? = null,
    val selectedFileTransferId: String? = null,
    val selectedAttachment: SelectedAttachmentUiModel? = null,
    val incomingFileRequest: IncomingFileRequestUiModel? = null,
    val showContactPicker: Boolean = false
)
