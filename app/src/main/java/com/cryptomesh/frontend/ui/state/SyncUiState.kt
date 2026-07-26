package com.cryptomesh.frontend.ui.state

enum class BackendConnectionStatus(
    val label: String,
    val supportingText: String
) {
    Connected(
        label = "Internet available",
        supportingText = "Backend synchronization can run."
    ),
    Disconnected(
        label = "Backend disconnected",
        supportingText = "Items remain safely queued on this device."
    )
}

enum class SyncPacketType(val label: String) {
    Message("Message"),
    File("File"),
    Wallet("Wallet")
}

enum class SyncPacketOwner(val label: String) {
    OwnPacket("Own packet"),
    RelayPacket("Relay packet")
}

enum class SyncQueueStatus(
    val label: String,
    val supportingText: String
) {
    PendingDirectDelivery(
        label = "Waiting for peer",
        supportingText = "Pending direct delivery"
    ),
    StoredReplica(
        label = "Stored securely",
        supportingText = "Local relay replica"
    ),
    AwaitingAck(
        label = "Awaiting ACK",
        supportingText = "Delivered; end-to-end acknowledgement pending"
    ),
    ExpiredAwaitingCleanup(
        label = "Cleanup required",
        supportingText = "Expired replica awaiting cleanup"
    ),
    PendingSettlement(
        label = "Settlement pending",
        supportingText = "Wallet transaction awaiting backend"
    ),
    Synchronized(
        label = "Synchronized",
        supportingText = "Accepted by backend"
    ),
    DuplicateRejected(
        label = "Duplicate rejected",
        supportingText = "Duplicate transaction ignored by backend"
    ),
    ServerRejected(
        label = "Server rejected",
        supportingText = "Backend rejected this item"
    ),
    Failed(
        label = "Delivery failed",
        supportingText = "The latest forwarding attempt failed"
    )
}

enum class SyncQueueFilter(val label: String) {
    All("All"),
    Messages("Messages"),
    Files("Files"),
    Wallet("Wallet"),
    OwnPackets("Own packets"),
    RelayPackets("Relay packets"),
    AwaitingAck("Awaiting ACK"),
    Expired("Expired"),
    Failed("Failed")
}

enum class ReplicaCleanupStatus(val label: String) {
    NotRequired("No cleanup needed"),
    Required("Cleanup required"),
    Completed("Cleanup completed")
}

data class SyncQueueItemUiModel(
    val id: String,
    val title: String,
    val description: String,
    val packetType: SyncPacketType,
    val owner: SyncPacketOwner,
    val status: SyncQueueStatus,
    val updatedAt: String,
    val lastForwardingAttempt: String,
    val replicaCount: Int,
    val errorDetails: String? = null
)

data class SyncUiState(
    val backendStatus: BackendConnectionStatus =
        BackendConnectionStatus.Connected,
    val items: List<SyncQueueItemUiModel> = emptyList(),
    val selectedFilter: SyncQueueFilter = SyncQueueFilter.All,
    val lastBackendSync: String = "Today, 10:42 AM",
    val lastAdaptiveForwardingAttempt: String = "Today, 11:56 AM",
    val cleanupStatus: ReplicaCleanupStatus = ReplicaCleanupStatus.Required,
    val actionMessage: String? = null,
    val selectedErrorItemId: String? = null
)
