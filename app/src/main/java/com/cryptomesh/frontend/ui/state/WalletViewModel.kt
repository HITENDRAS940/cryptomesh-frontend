package com.cryptomesh.frontend.ui.state

import androidx.lifecycle.ViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class WalletViewModel : ViewModel() {
    private var nextTransactionNumber = 20
    private val _uiState = MutableStateFlow(
        WalletUiState(
            availableBalanceMinor = 125_000,
            pendingCreditsMinor = 4_500,
            peers = samplePeers,
            transactions = sampleTransactions,
            incomingPaymentRequest = sampleIncomingPayment
        )
    )
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    fun openSendPayment() {
        _uiState.update {
            it.copy(
                screenMode = WalletScreenMode.Send,
                amountInput = "",
                selectedPeerId = null,
                amountError = null,
                formError = null,
                showSendConfirmation = false
            )
        }
    }

    fun closeSendPayment() {
        _uiState.update {
            it.copy(
                screenMode = WalletScreenMode.Home,
                amountInput = "",
                selectedPeerId = null,
                amountError = null,
                formError = null,
                showSendConfirmation = false
            )
        }
    }

    fun updateAmountInput(input: String) {
        val normalized = normalizeAmountInput(input) ?: return
        _uiState.update {
            it.copy(
                amountInput = normalized,
                amountError = null,
                formError = null
            )
        }
    }

    fun selectPeer(peerId: String) {
        if (_uiState.value.peers.none { it.id == peerId }) return
        _uiState.update {
            it.copy(
                selectedPeerId = peerId,
                formError = null
            )
        }
    }

    fun requestSendConfirmation() {
        val state = _uiState.value
        val amountMinor = parseAmountMinor(state.amountInput)
        val amountError = when {
            amountMinor == null || amountMinor <= 0 -> "Enter a valid amount."
            amountMinor > state.availableBalanceMinor ->
                "Amount exceeds the available balance."
            else -> null
        }
        val formError = if (state.selectedPeerId == null) {
            "Select a receiver."
        } else {
            null
        }

        if (amountError != null || formError != null) {
            _uiState.update {
                it.copy(
                    amountError = amountError,
                    formError = formError
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                showSendConfirmation = true,
                amountError = null,
                formError = null
            )
        }
    }

    fun dismissSendConfirmation() {
        _uiState.update { it.copy(showSendConfirmation = false) }
    }

    fun confirmPayment() {
        val state = _uiState.value
        if (!state.showSendConfirmation) return
        val amountMinor = parseAmountMinor(state.amountInput) ?: return
        val peer = state.peers.firstOrNull { it.id == state.selectedPeerId } ?: return
        if (amountMinor <= 0 || amountMinor > state.availableBalanceMinor) return

        val transactionNumber = nextTransactionNumber++
        val transaction = WalletTransactionUiModel(
            id = "WTX-CM-${transactionNumber.toString().padStart(4, '0')}",
            direction = WalletTransactionDirection.Sent,
            counterpartyName = peer.name,
            counterpartyDeviceId = peer.deviceId,
            amountMinor = amountMinor,
            status = WalletTransactionStatus.SignedLocally,
            signatureStatus = WalletSignatureStatus.SignedLocally,
            receiverDeliveryStatus = ReceiverDeliveryStatus.Waiting,
            relayDeliveryStatus = if (peer.route == WalletPeerRoute.TrustedRelay) {
                RelayDeliveryStatus.Waiting
            } else {
                RelayDeliveryStatus.NotUsed
            },
            backendSettlementStatus = BackendSettlementStatus.NotSubmitted,
            createdAt = "Now",
            expiresAt = "In 24 hours",
            duplicateDetectionStatus = DuplicateDetectionStatus.NotChecked,
            trustedReplicaCount = 0,
            route = peer.route,
            autoAdvance = true
        )

        _uiState.update {
            it.copy(
                availableBalanceMinor = it.availableBalanceMinor - amountMinor,
                transactions = listOf(transaction) + it.transactions,
                screenMode = WalletScreenMode.Home,
                amountInput = "",
                selectedPeerId = null,
                amountError = null,
                formError = null,
                showSendConfirmation = false
            )
        }
    }

    fun advanceTransaction(transactionId: String) {
        _uiState.update { state ->
            val current = state.transactions.firstOrNull { it.id == transactionId }
                ?: return@update state
            if (!current.autoAdvance) return@update state

            val next = nextTransactionState(current)
            val refund = current.direction == WalletTransactionDirection.Sent &&
                current.status != WalletTransactionStatus.ExpiredBeforeReceiverDelivery &&
                next.status == WalletTransactionStatus.ExpiredBeforeReceiverDelivery

            state.copy(
                availableBalanceMinor = if (refund) {
                    state.availableBalanceMinor + current.amountMinor
                } else {
                    state.availableBalanceMinor
                },
                transactions = state.transactions.map {
                    if (it.id == transactionId) next else it
                }
            )
        }
    }

    fun retryReceiverDelivery(transactionId: String) {
        _uiState.update { state ->
            val transaction = state.transactions.firstOrNull {
                it.id == transactionId
            } ?: return@update state
            if (transaction.status !=
                WalletTransactionStatus.ExpiredBeforeReceiverDelivery ||
                transaction.amountMinor > state.availableBalanceMinor
            ) {
                return@update state
            }

            state.copy(
                availableBalanceMinor =
                    state.availableBalanceMinor - transaction.amountMinor,
                transactions = state.transactions.map {
                    if (it.id == transactionId) {
                        it.copy(
                            status = WalletTransactionStatus.SignedLocally,
                            signatureStatus = WalletSignatureStatus.SignedLocally,
                            receiverDeliveryStatus = ReceiverDeliveryStatus.Waiting,
                            relayDeliveryStatus = RelayDeliveryStatus.Waiting,
                            backendSettlementStatus =
                                BackendSettlementStatus.NotSubmitted,
                            expiresAt = "In 24 hours",
                            trustedReplicaCount = 0,
                            autoAdvance = true
                        )
                    } else {
                        it
                    }
                },
                selectedDeliveryDetailsId = null
            )
        }
    }

    fun showIncomingPaymentRequest() {
        if (_uiState.value.incomingPaymentRequest != null) {
            _uiState.update { it.copy(showIncomingPaymentRequest = true) }
        }
    }

    fun dismissIncomingPaymentRequest() {
        _uiState.update { it.copy(showIncomingPaymentRequest = false) }
    }

    fun declineIncomingPaymentRequest() {
        _uiState.update {
            it.copy(
                incomingPaymentRequest = null,
                showIncomingPaymentRequest = false
            )
        }
    }

    fun acceptIncomingPaymentRequest() {
        val request = _uiState.value.incomingPaymentRequest ?: return
        if (!_uiState.value.showIncomingPaymentRequest) return
        val transactionNumber = nextTransactionNumber++
        val signatureValid =
            request.signatureStatus != WalletSignatureStatus.Failed
        val transaction = WalletTransactionUiModel(
            id = "WTX-CM-${transactionNumber.toString().padStart(4, '0')}",
            direction = WalletTransactionDirection.Received,
            counterpartyName = request.senderName,
            counterpartyDeviceId = request.senderDeviceId,
            amountMinor = request.amountMinor,
            status = if (signatureValid) {
                WalletTransactionStatus.DeliveredToReceiver
            } else {
                WalletTransactionStatus.SignatureVerificationFailed
            },
            signatureStatus = request.signatureStatus,
            receiverDeliveryStatus = if (signatureValid) {
                ReceiverDeliveryStatus.Delivered
            } else {
                ReceiverDeliveryStatus.BlockedByInvalidSignature
            },
            relayDeliveryStatus = RelayDeliveryStatus.NotUsed,
            backendSettlementStatus = if (signatureValid) {
                BackendSettlementStatus.Pending
            } else {
                BackendSettlementStatus.NotSubmitted
            },
            createdAt = request.createdAt,
            expiresAt = request.expiresAt,
            duplicateDetectionStatus = DuplicateDetectionStatus.NotChecked,
            trustedReplicaCount = 0,
            route = WalletPeerRoute.Direct,
            autoAdvance = signatureValid
        )

        _uiState.update {
            it.copy(
                pendingCreditsMinor = if (signatureValid) {
                    it.pendingCreditsMinor + request.amountMinor
                } else {
                    it.pendingCreditsMinor
                },
                transactions = listOf(transaction) + it.transactions,
                incomingPaymentRequest = null,
                showIncomingPaymentRequest = false
            )
        }
    }

    fun openTransaction(transactionId: String) {
        if (_uiState.value.transactions.none { it.id == transactionId }) return
        _uiState.update {
            it.copy(
                selectedTransactionId = transactionId,
                selectedDeliveryDetailsId = null
            )
        }
    }

    fun closeTransaction() {
        _uiState.update {
            it.copy(
                selectedTransactionId = null,
                selectedDeliveryDetailsId = null
            )
        }
    }

    fun showDeliveryDetails(transactionId: String) {
        if (_uiState.value.transactions.none { it.id == transactionId }) return
        _uiState.update { it.copy(selectedDeliveryDetailsId = transactionId) }
    }

    fun dismissDeliveryDetails() {
        _uiState.update { it.copy(selectedDeliveryDetailsId = null) }
    }

    fun setHistoryFilter(filter: WalletHistoryFilter) {
        _uiState.update { it.copy(historyFilter = filter) }
    }

    private fun nextTransactionState(
        transaction: WalletTransactionUiModel
    ): WalletTransactionUiModel {
        return when (transaction.status) {
            WalletTransactionStatus.SignedLocally -> when (transaction.route) {
                WalletPeerRoute.Direct -> transaction.copy(
                    status = WalletTransactionStatus.WaitingForReceiver,
                    receiverDeliveryStatus = ReceiverDeliveryStatus.Waiting
                )

                WalletPeerRoute.TrustedRelay -> transaction.copy(
                    status = WalletTransactionStatus.StoredByTrustedRelay,
                    receiverDeliveryStatus =
                        ReceiverDeliveryStatus.StoredByTrustedRelay,
                    relayDeliveryStatus = RelayDeliveryStatus.Stored,
                    trustedReplicaCount = 1
                )

                WalletPeerRoute.Unavailable -> transaction.copy(
                    status = WalletTransactionStatus.WaitingForReceiver,
                    receiverDeliveryStatus = ReceiverDeliveryStatus.Waiting,
                    relayDeliveryStatus = RelayDeliveryStatus.Waiting
                )
            }

            WalletTransactionStatus.WaitingForReceiver -> {
                if (transaction.route == WalletPeerRoute.Unavailable) {
                    transaction.copy(
                        status =
                            WalletTransactionStatus.ExpiredBeforeReceiverDelivery,
                        receiverDeliveryStatus = ReceiverDeliveryStatus.Expired,
                        expiresAt = "Expired before delivery",
                        autoAdvance = false
                    )
                } else {
                    transaction.deliveredToReceiver()
                }
            }

            WalletTransactionStatus.StoredByTrustedRelay ->
                transaction.deliveredToReceiver(
                    relayStatus = RelayDeliveryStatus.Forwarded
                )

            WalletTransactionStatus.DeliveredToReceiver -> transaction.copy(
                status = WalletTransactionStatus.PendingBackendSettlement,
                backendSettlementStatus = BackendSettlementStatus.Pending,
                duplicateDetectionStatus = DuplicateDetectionStatus.NotChecked,
                autoAdvance = false
            )

            else -> transaction.copy(autoAdvance = false)
        }
    }

    private fun WalletTransactionUiModel.deliveredToReceiver(
        relayStatus: RelayDeliveryStatus = relayDeliveryStatus
    ): WalletTransactionUiModel {
        return copy(
            status = WalletTransactionStatus.DeliveredToReceiver,
            signatureStatus = WalletSignatureStatus.Verified,
            receiverDeliveryStatus = ReceiverDeliveryStatus.Delivered,
            relayDeliveryStatus = relayStatus,
            backendSettlementStatus = BackendSettlementStatus.Pending
        )
    }

    private fun normalizeAmountInput(input: String): String? {
        val filtered = input.filter { it.isDigit() || it == '.' }
        if (filtered.count { it == '.' } > 1) return null
        val parts = filtered.split('.', limit = 2)
        if (parts.firstOrNull().orEmpty().length > MAX_WHOLE_DIGITS) return null
        if (parts.getOrNull(1).orEmpty().length > 2) return null
        return filtered.take(MAX_AMOUNT_INPUT_LENGTH)
    }

    private fun parseAmountMinor(input: String): Long? {
        return input.toBigDecimalOrNull()
            ?.setScale(2, RoundingMode.UNNECESSARY)
            ?.multiply(BigDecimal(100))
            ?.longValueExact()
    }

    private companion object {
        const val MAX_WHOLE_DIGITS = 7
        const val MAX_AMOUNT_INPUT_LENGTH = 10

        val samplePeers = listOf(
            WalletPeerUiModel(
                id = "wallet-peer-aarav",
                name = "Aarav's Pixel",
                deviceId = "CM-7A21F4C8",
                route = WalletPeerRoute.Direct,
                isVerified = true
            ),
            WalletPeerUiModel(
                id = "wallet-peer-meera",
                name = "Meera's Phone",
                deviceId = "CM-8C14E7B9",
                route = WalletPeerRoute.TrustedRelay,
                isVerified = true
            ),
            WalletPeerUiModel(
                id = "wallet-peer-campus",
                name = "Campus Node",
                deviceId = "CM-5F33C0A2",
                route = WalletPeerRoute.Unavailable,
                isVerified = true
            )
        )

        val sampleIncomingPayment = IncomingPaymentRequestUiModel(
            id = "incoming-payment-01",
            senderName = "Aarav's Pixel",
            senderDeviceId = "CM-7A21F4C8",
            amountMinor = 7_500,
            signatureStatus = WalletSignatureStatus.Verified,
            createdAt = "Today, 11:20 AM",
            expiresAt = "In 18 hours"
        )

        val sampleTransactions = listOf(
            WalletTransactionUiModel(
                id = "WTX-CM-0014",
                direction = WalletTransactionDirection.Received,
                counterpartyName = "Aarav's Pixel",
                counterpartyDeviceId = "CM-7A21F4C8",
                amountMinor = 4_500,
                status = WalletTransactionStatus.PendingBackendSettlement,
                signatureStatus = WalletSignatureStatus.Verified,
                receiverDeliveryStatus = ReceiverDeliveryStatus.Delivered,
                relayDeliveryStatus = RelayDeliveryStatus.NotUsed,
                backendSettlementStatus = BackendSettlementStatus.Pending,
                createdAt = "Today, 10:52 AM",
                expiresAt = "In 20 hours",
                duplicateDetectionStatus = DuplicateDetectionStatus.NotChecked,
                trustedReplicaCount = 0,
                route = WalletPeerRoute.Direct
            ),
            WalletTransactionUiModel(
                id = "WTX-CM-0013",
                direction = WalletTransactionDirection.Sent,
                counterpartyName = "Meera's Phone",
                counterpartyDeviceId = "CM-8C14E7B9",
                amountMinor = 27_500,
                status = WalletTransactionStatus.Synchronized,
                signatureStatus = WalletSignatureStatus.Verified,
                receiverDeliveryStatus = ReceiverDeliveryStatus.Delivered,
                relayDeliveryStatus = RelayDeliveryStatus.Forwarded,
                backendSettlementStatus = BackendSettlementStatus.Synchronized,
                createdAt = "Yesterday, 4:18 PM",
                expiresAt = "Delivered before expiry",
                duplicateDetectionStatus = DuplicateDetectionStatus.Clear,
                trustedReplicaCount = 1,
                route = WalletPeerRoute.TrustedRelay
            ),
            WalletTransactionUiModel(
                id = "WTX-CM-0012",
                direction = WalletTransactionDirection.Sent,
                counterpartyName = "Campus Node",
                counterpartyDeviceId = "CM-5F33C0A2",
                amountMinor = 1_000,
                status = WalletTransactionStatus.DuplicateTransactionRejected,
                signatureStatus = WalletSignatureStatus.Verified,
                receiverDeliveryStatus = ReceiverDeliveryStatus.Delivered,
                relayDeliveryStatus = RelayDeliveryStatus.Stored,
                backendSettlementStatus =
                    BackendSettlementStatus.DuplicateRejected,
                createdAt = "Monday, 2:10 PM",
                expiresAt = "Delivered before expiry",
                duplicateDetectionStatus =
                    DuplicateDetectionStatus.DuplicateRejected,
                trustedReplicaCount = 1,
                route = WalletPeerRoute.TrustedRelay
            ),
            WalletTransactionUiModel(
                id = "WTX-CM-0011",
                direction = WalletTransactionDirection.Sent,
                counterpartyName = "Market Node",
                counterpartyDeviceId = "CM-4B92D6E1",
                amountMinor = 8_000,
                status = WalletTransactionStatus.RejectedByBackend,
                signatureStatus = WalletSignatureStatus.Verified,
                receiverDeliveryStatus = ReceiverDeliveryStatus.Delivered,
                relayDeliveryStatus = RelayDeliveryStatus.NotUsed,
                backendSettlementStatus = BackendSettlementStatus.Rejected,
                createdAt = "Sunday, 5:35 PM",
                expiresAt = "Delivered before expiry",
                duplicateDetectionStatus = DuplicateDetectionStatus.Clear,
                trustedReplicaCount = 0,
                route = WalletPeerRoute.Direct
            ),
            WalletTransactionUiModel(
                id = "WTX-CM-0010",
                direction = WalletTransactionDirection.Received,
                counterpartyName = "Unknown device",
                counterpartyDeviceId = "CM-UNKNOWN",
                amountMinor = 2_500,
                status = WalletTransactionStatus.SignatureVerificationFailed,
                signatureStatus = WalletSignatureStatus.Failed,
                receiverDeliveryStatus =
                    ReceiverDeliveryStatus.BlockedByInvalidSignature,
                relayDeliveryStatus = RelayDeliveryStatus.NotUsed,
                backendSettlementStatus = BackendSettlementStatus.NotSubmitted,
                createdAt = "Saturday, 9:40 AM",
                expiresAt = "Rejected locally",
                duplicateDetectionStatus = DuplicateDetectionStatus.NotChecked,
                trustedReplicaCount = 0,
                route = WalletPeerRoute.Direct
            )
        )
    }
}
