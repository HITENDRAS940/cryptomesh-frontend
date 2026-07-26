package com.cryptomesh.frontend.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncViewModelTest {
    @Test
    fun packetFiltersReturnOnlyMatchingQueueItems() {
        val viewModel = SyncViewModel()

        viewModel.setFilter(SyncQueueFilter.Messages)
        assertTrue(
            viewModel.uiState.value.filteredItems().all {
                it.packetType == SyncPacketType.Message
            }
        )

        viewModel.setFilter(SyncQueueFilter.RelayPackets)
        assertTrue(
            viewModel.uiState.value.filteredItems().all {
                it.owner == SyncPacketOwner.RelayPacket
            }
        )

        viewModel.setFilter(SyncQueueFilter.Failed)
        assertTrue(
            viewModel.uiState.value.filteredItems().all {
                it.status == SyncQueueStatus.Failed ||
                    it.status == SyncQueueStatus.ServerRejected
            }
        )
    }

    @Test
    fun manualSyncSettlesPendingWalletItemsOnly() {
        val viewModel = SyncViewModel()
        val before = viewModel.uiState.value
        val pendingSettlementIds = before.items.filter {
            it.status == SyncQueueStatus.PendingSettlement
        }.map { it.id }
        val peerDeliveryId = before.items.first {
            it.status == SyncQueueStatus.PendingDirectDelivery
        }.id

        viewModel.runManualSync()

        val after = viewModel.uiState.value
        assertEquals("Just now", after.lastBackendSync)
        assertTrue(pendingSettlementIds.isNotEmpty())
        assertTrue(
            after.items.filter { it.id in pendingSettlementIds }.all {
                it.status == SyncQueueStatus.Synchronized
            }
        )
        assertEquals(
            SyncQueueStatus.PendingDirectDelivery,
            after.items.first { it.id == peerDeliveryId }.status
        )
    }

    @Test
    fun cleanupRemovesOnlyExpiredReplicas() {
        val viewModel = SyncViewModel()
        val before = viewModel.uiState.value.items
        val expiredIds = before.filter {
            it.status == SyncQueueStatus.ExpiredAwaitingCleanup
        }.map { it.id }

        viewModel.runCleanup()

        val after = viewModel.uiState.value
        assertTrue(expiredIds.isNotEmpty())
        assertFalse(after.items.any { it.id in expiredIds })
        assertEquals(
            before.size - expiredIds.size,
            after.items.size
        )
        assertEquals(
            ReplicaCleanupStatus.Completed,
            after.cleanupStatus
        )
    }

    @Test
    fun errorDetailsOpenOnlyForItemsWithDetails() {
        val viewModel = SyncViewModel()
        val state = viewModel.uiState.value
        val failed = state.items.first { it.errorDetails != null }
        val normal = state.items.first { it.errorDetails == null }

        viewModel.showErrorDetails(normal.id)
        assertNull(viewModel.uiState.value.selectedErrorItemId)

        viewModel.showErrorDetails(failed.id)
        assertEquals(failed.id, viewModel.uiState.value.selectedErrorItemId)

        viewModel.dismissErrorDetails()
        assertNull(viewModel.uiState.value.selectedErrorItemId)
    }
}
