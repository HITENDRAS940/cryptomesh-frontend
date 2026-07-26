package com.cryptomesh.frontend.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletViewModelTest {
    @Test
    fun invalidOrExcessiveAmountDoesNotOpenConfirmation() {
        val viewModel = WalletViewModel()
        val directPeer = peerWithRoute(viewModel, WalletPeerRoute.Direct)
        viewModel.openSendPayment()
        viewModel.selectPeer(directPeer.id)

        viewModel.updateAmountInput("0")
        viewModel.requestSendConfirmation()
        assertFalse(viewModel.uiState.value.showSendConfirmation)
        assertNotNull(viewModel.uiState.value.amountError)

        viewModel.updateAmountInput("9999")
        viewModel.requestSendConfirmation()
        assertFalse(viewModel.uiState.value.showSendConfirmation)
        assertEquals(
            "Amount exceeds the available balance.",
            viewModel.uiState.value.amountError
        )
    }

    @Test
    fun directPaymentReservesBalanceAndStopsAtPendingSettlement() {
        val viewModel = WalletViewModel()
        val initialBalance = viewModel.uiState.value.availableBalanceMinor
        createPayment(viewModel, WalletPeerRoute.Direct, "25.50")

        val signed = viewModel.uiState.value.transactions.first()
        assertEquals(WalletTransactionStatus.SignedLocally, signed.status)
        assertEquals(initialBalance - 2_550, viewModel.uiState.value.availableBalanceMinor)

        viewModel.advanceTransaction(signed.id)
        assertEquals(
            WalletTransactionStatus.WaitingForReceiver,
            viewModel.uiState.value.transactions.first().status
        )

        viewModel.advanceTransaction(signed.id)
        val delivered = viewModel.uiState.value.transactions.first()
        assertEquals(WalletTransactionStatus.DeliveredToReceiver, delivered.status)
        assertEquals(ReceiverDeliveryStatus.Delivered, delivered.receiverDeliveryStatus)
        assertEquals(BackendSettlementStatus.Pending, delivered.backendSettlementStatus)

        viewModel.advanceTransaction(signed.id)
        val pending = viewModel.uiState.value.transactions.first()
        assertEquals(
            WalletTransactionStatus.PendingBackendSettlement,
            pending.status
        )
        assertEquals(ReceiverDeliveryStatus.Delivered, pending.receiverDeliveryStatus)
        assertEquals(BackendSettlementStatus.Pending, pending.backendSettlementStatus)
        assertFalse(pending.autoAdvance)
    }

    @Test
    fun trustedRelayPaymentStoresReplicaBeforeReceiverDelivery() {
        val viewModel = WalletViewModel()
        createPayment(viewModel, WalletPeerRoute.TrustedRelay, "10.00")
        val transactionId = viewModel.uiState.value.transactions.first().id

        viewModel.advanceTransaction(transactionId)
        val stored = viewModel.uiState.value.transactions.first()
        assertEquals(WalletTransactionStatus.StoredByTrustedRelay, stored.status)
        assertEquals(RelayDeliveryStatus.Stored, stored.relayDeliveryStatus)
        assertEquals(1, stored.trustedReplicaCount)

        viewModel.advanceTransaction(transactionId)
        val delivered = viewModel.uiState.value.transactions.first()
        assertEquals(WalletTransactionStatus.DeliveredToReceiver, delivered.status)
        assertEquals(RelayDeliveryStatus.Forwarded, delivered.relayDeliveryStatus)
    }

    @Test
    fun unavailablePaymentExpiresAndReturnsReservedBalance() {
        val viewModel = WalletViewModel()
        val initialBalance = viewModel.uiState.value.availableBalanceMinor
        createPayment(viewModel, WalletPeerRoute.Unavailable, "15.00")
        val transactionId = viewModel.uiState.value.transactions.first().id
        assertEquals(
            initialBalance - 1_500,
            viewModel.uiState.value.availableBalanceMinor
        )

        viewModel.advanceTransaction(transactionId)
        viewModel.advanceTransaction(transactionId)

        val expired = viewModel.uiState.value.transactions.first()
        assertEquals(
            WalletTransactionStatus.ExpiredBeforeReceiverDelivery,
            expired.status
        )
        assertEquals(ReceiverDeliveryStatus.Expired, expired.receiverDeliveryStatus)
        assertEquals(initialBalance, viewModel.uiState.value.availableBalanceMinor)
        assertFalse(expired.autoAdvance)
    }

    @Test
    fun acceptedIncomingPaymentBecomesPendingCredit() {
        val viewModel = WalletViewModel()
        val originalPending = viewModel.uiState.value.pendingCreditsMinor
        val requestAmount =
            viewModel.uiState.value.incomingPaymentRequest?.amountMinor
                ?: error("Missing sample request")

        viewModel.showIncomingPaymentRequest()
        viewModel.acceptIncomingPaymentRequest()

        val state = viewModel.uiState.value
        assertNull(state.incomingPaymentRequest)
        assertFalse(state.showIncomingPaymentRequest)
        assertEquals(originalPending + requestAmount, state.pendingCreditsMinor)
        val received = state.transactions.first()
        assertEquals(WalletTransactionDirection.Received, received.direction)
        assertEquals(WalletTransactionStatus.DeliveredToReceiver, received.status)
        assertEquals(BackendSettlementStatus.Pending, received.backendSettlementStatus)

        viewModel.advanceTransaction(received.id)
        assertEquals(
            WalletTransactionStatus.PendingBackendSettlement,
            viewModel.uiState.value.transactions.first().status
        )
    }

    @Test
    fun sampleHistorySeparatesDuplicateAndBackendRejection() {
        val transactions = WalletViewModel().uiState.value.transactions
        val duplicate = transactions.first {
            it.status == WalletTransactionStatus.DuplicateTransactionRejected
        }
        val rejected = transactions.first {
            it.status == WalletTransactionStatus.RejectedByBackend
        }

        assertEquals(
            BackendSettlementStatus.DuplicateRejected,
            duplicate.backendSettlementStatus
        )
        assertEquals(
            DuplicateDetectionStatus.DuplicateRejected,
            duplicate.duplicateDetectionStatus
        )
        assertEquals(
            BackendSettlementStatus.Rejected,
            rejected.backendSettlementStatus
        )
        assertEquals(ReceiverDeliveryStatus.Delivered, rejected.receiverDeliveryStatus)
    }

    private fun createPayment(
        viewModel: WalletViewModel,
        route: WalletPeerRoute,
        amount: String
    ) {
        val peer = peerWithRoute(viewModel, route)
        viewModel.openSendPayment()
        viewModel.updateAmountInput(amount)
        viewModel.selectPeer(peer.id)
        viewModel.requestSendConfirmation()
        assertTrue(viewModel.uiState.value.showSendConfirmation)
        viewModel.confirmPayment()
    }

    private fun peerWithRoute(
        viewModel: WalletViewModel,
        route: WalletPeerRoute
    ): WalletPeerUiModel {
        return viewModel.uiState.value.peers.first { it.route == route }
    }
}
