package com.cryptomesh.frontend.ui.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SyncViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(
        SyncUiState(items = sampleSyncQueue)
    )
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    fun setFilter(filter: SyncQueueFilter) {
        _uiState.update {
            it.copy(
                selectedFilter = filter,
                actionMessage = null
            )
        }
    }

    fun runManualSync() {
        _uiState.update { state ->
            if (state.backendStatus == BackendConnectionStatus.Disconnected) {
                return@update state.copy(
                    actionMessage =
                        "No Internet connection. The queue remains on this device."
                )
            }

            val synchronizedCount = state.items.count {
                it.status == SyncQueueStatus.PendingSettlement
            }
            state.copy(
                items = state.items.map { item ->
                    if (item.status == SyncQueueStatus.PendingSettlement) {
                        item.copy(
                            status = SyncQueueStatus.Synchronized,
                            updatedAt = "Just now",
                            errorDetails = null
                        )
                    } else {
                        item
                    }
                },
                lastBackendSync = "Just now",
                actionMessage = if (synchronizedCount == 0) {
                    "Backend sync completed. No settlement items were pending."
                } else {
                    "$synchronizedCount wallet transaction synchronized. " +
                        "Peer-delivery items remain in the local queue."
                }
            )
        }
    }

    fun runCleanup() {
        _uiState.update { state ->
            val expiredCount = state.items.count {
                it.status == SyncQueueStatus.ExpiredAwaitingCleanup
            }
            state.copy(
                items = state.items.filterNot {
                    it.status == SyncQueueStatus.ExpiredAwaitingCleanup
                },
                cleanupStatus = ReplicaCleanupStatus.Completed,
                actionMessage = if (expiredCount == 0) {
                    "Cleanup completed. No expired replicas were found."
                } else {
                    "$expiredCount expired replica removed by the cleanup worker."
                },
                selectedErrorItemId = state.selectedErrorItemId?.takeIf { id ->
                    state.items.none {
                        it.id == id &&
                            it.status == SyncQueueStatus.ExpiredAwaitingCleanup
                    }
                }
            )
        }
    }

    fun showErrorDetails(itemId: String) {
        val item = _uiState.value.items.firstOrNull { it.id == itemId }
        if (item?.errorDetails == null) return
        _uiState.update { it.copy(selectedErrorItemId = itemId) }
    }

    fun dismissErrorDetails() {
        _uiState.update { it.copy(selectedErrorItemId = null) }
    }

    fun dismissActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }
}

fun SyncUiState.filteredItems(): List<SyncQueueItemUiModel> {
    return items.filter { item ->
        when (selectedFilter) {
            SyncQueueFilter.All -> true
            SyncQueueFilter.Messages ->
                item.packetType == SyncPacketType.Message
            SyncQueueFilter.Files ->
                item.packetType == SyncPacketType.File
            SyncQueueFilter.Wallet ->
                item.packetType == SyncPacketType.Wallet
            SyncQueueFilter.OwnPackets ->
                item.owner == SyncPacketOwner.OwnPacket
            SyncQueueFilter.RelayPackets ->
                item.owner == SyncPacketOwner.RelayPacket
            SyncQueueFilter.AwaitingAck ->
                item.status == SyncQueueStatus.AwaitingAck
            SyncQueueFilter.Expired ->
                item.status == SyncQueueStatus.ExpiredAwaitingCleanup
            SyncQueueFilter.Failed ->
                item.status == SyncQueueStatus.Failed ||
                    item.status == SyncQueueStatus.ServerRejected
        }
    }
}

private val sampleSyncQueue = listOf(
    SyncQueueItemUiModel(
        id = "MSG-CM-0204",
        title = "Message to Aarav",
        description = "Delivered through Campus Node",
        packetType = SyncPacketType.Message,
        owner = SyncPacketOwner.OwnPacket,
        status = SyncQueueStatus.AwaitingAck,
        updatedAt = "2 min ago",
        lastForwardingAttempt = "Today, 12:02 PM",
        replicaCount = 1
    ),
    SyncQueueItemUiModel(
        id = "FILE-CM-0118",
        title = "project-notes.pdf",
        description = "Direct transfer preferred",
        packetType = SyncPacketType.File,
        owner = SyncPacketOwner.OwnPacket,
        status = SyncQueueStatus.PendingDirectDelivery,
        updatedAt = "8 min ago",
        lastForwardingAttempt = "Today, 11:56 AM",
        replicaCount = 0
    ),
    SyncQueueItemUiModel(
        id = "RLY-CM-0041",
        title = "Relay packet for Meera",
        description = "Encrypted message packet",
        packetType = SyncPacketType.Message,
        owner = SyncPacketOwner.RelayPacket,
        status = SyncQueueStatus.StoredReplica,
        updatedAt = "18 min ago",
        lastForwardingAttempt = "Today, 11:48 AM",
        replicaCount = 1
    ),
    SyncQueueItemUiModel(
        id = "RLY-CM-0038",
        title = "Expired file replica",
        description = "Stored for Market Node",
        packetType = SyncPacketType.File,
        owner = SyncPacketOwner.RelayPacket,
        status = SyncQueueStatus.ExpiredAwaitingCleanup,
        updatedAt = "1 hr ago",
        lastForwardingAttempt = "Today, 10:51 AM",
        replicaCount = 1
    ),
    SyncQueueItemUiModel(
        id = "WTX-CM-0109",
        title = "Payment to Aarav's Pixel",
        description = "Delivered to receiver",
        packetType = SyncPacketType.Wallet,
        owner = SyncPacketOwner.OwnPacket,
        status = SyncQueueStatus.PendingSettlement,
        updatedAt = "Today, 10:52 AM",
        lastForwardingAttempt = "Direct delivery",
        replicaCount = 0
    ),
    SyncQueueItemUiModel(
        id = "WTX-CM-0091",
        title = "Duplicate wallet transaction",
        description = "No balance change was applied",
        packetType = SyncPacketType.Wallet,
        owner = SyncPacketOwner.OwnPacket,
        status = SyncQueueStatus.DuplicateRejected,
        updatedAt = "Yesterday, 6:20 PM",
        lastForwardingAttempt = "Not applicable",
        replicaCount = 0,
        errorDetails =
            "The backend had already processed this transaction ID. " +
                "The duplicate was ignored and no second settlement occurred."
    ),
    SyncQueueItemUiModel(
        id = "MSG-CM-0197",
        title = "Message to Campus Node",
        description = "No eligible route is currently available",
        packetType = SyncPacketType.Message,
        owner = SyncPacketOwner.OwnPacket,
        status = SyncQueueStatus.Failed,
        updatedAt = "Yesterday, 4:12 PM",
        lastForwardingAttempt = "Yesterday, 4:12 PM",
        replicaCount = 0,
        errorDetails =
            "The destination was unavailable and no trusted relay met the " +
                "delivery policy. The encrypted packet remains local."
    ),
    SyncQueueItemUiModel(
        id = "WTX-CM-0084",
        title = "Payment to Market Node",
        description = "Delivered to receiver before settlement",
        packetType = SyncPacketType.Wallet,
        owner = SyncPacketOwner.OwnPacket,
        status = SyncQueueStatus.ServerRejected,
        updatedAt = "Sunday, 5:35 PM",
        lastForwardingAttempt = "Direct delivery",
        replicaCount = 0,
        errorDetails =
            "The backend rejected settlement validation. Receiver delivery " +
                "does not mean the transaction was settled by the backend."
    )
)
