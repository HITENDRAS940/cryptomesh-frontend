package com.cryptomesh.frontend.ui.state

enum class WalletScreenMode {
    Home,
    Send
}

enum class WalletTransactionDirection {
    Sent,
    Received
}

enum class WalletTransactionStatus(
    val userLabel: String,
    val technicalLabel: String
) {
    SignedLocally("Signed locally", "Transaction signed on this device"),
    WaitingForReceiver("Waiting for receiver", "Receiver delivery pending"),
    StoredByTrustedRelay("Stored securely", "Stored by trusted relay"),
    DeliveredToReceiver("Delivered to receiver", "Receiver delivery complete"),
    PendingBackendSettlement(
        "Settlement pending",
        "Pending backend settlement"
    ),
    Synchronized("Settled", "Synchronized with backend"),
    RejectedByBackend("Settlement rejected", "Rejected by backend"),
    DuplicateTransactionRejected(
        "Duplicate rejected",
        "Duplicate transaction rejected"
    ),
    ExpiredBeforeReceiverDelivery(
        "Delivery expired",
        "Expired before receiver delivery"
    ),
    SignatureVerificationFailed(
        "Signature failed",
        "Signature verification failed"
    )
}

enum class WalletSignatureStatus(val label: String) {
    SignedLocally("Signed locally"),
    Verified("Signature verified"),
    Failed("Signature verification failed")
}

enum class ReceiverDeliveryStatus(val label: String) {
    Waiting("Waiting for receiver"),
    StoredByTrustedRelay("Stored by trusted relay"),
    Delivered("Delivered to receiver"),
    Expired("Expired before delivery"),
    BlockedByInvalidSignature("Blocked: invalid signature")
}

enum class RelayDeliveryStatus(val label: String) {
    NotUsed("No relay used"),
    Waiting("Waiting for trusted relay"),
    Stored("Stored by trusted relay"),
    Forwarded("Forwarded by trusted relay")
}

enum class BackendSettlementStatus(val label: String) {
    NotSubmitted("Not submitted"),
    Pending("Pending Internet connection"),
    Synchronized("Synchronized and settled"),
    Rejected("Rejected by backend"),
    DuplicateRejected("Duplicate rejected by backend")
}

enum class DuplicateDetectionStatus(val label: String) {
    NotChecked("Awaiting backend check"),
    Clear("No duplicate detected"),
    DuplicateRejected("Duplicate transaction rejected")
}

enum class WalletPeerRoute(
    val label: String,
    val supportingText: String
) {
    Direct(
        label = "Direct",
        supportingText = "Receiver is currently reachable."
    ),
    TrustedRelay(
        label = "Trusted relay",
        supportingText = "A trusted relay can carry the signed transaction."
    ),
    Unavailable(
        label = "Unavailable",
        supportingText = "The payment will wait locally for a connection."
    )
}

data class WalletPeerUiModel(
    val id: String,
    val name: String,
    val deviceId: String,
    val route: WalletPeerRoute,
    val isVerified: Boolean
)

data class WalletTransactionUiModel(
    val id: String,
    val direction: WalletTransactionDirection,
    val counterpartyName: String,
    val counterpartyDeviceId: String,
    val amountMinor: Long,
    val status: WalletTransactionStatus,
    val signatureStatus: WalletSignatureStatus,
    val receiverDeliveryStatus: ReceiverDeliveryStatus,
    val relayDeliveryStatus: RelayDeliveryStatus,
    val backendSettlementStatus: BackendSettlementStatus,
    val createdAt: String,
    val expiresAt: String,
    val duplicateDetectionStatus: DuplicateDetectionStatus,
    val trustedReplicaCount: Int,
    val route: WalletPeerRoute,
    val autoAdvance: Boolean = false
)

data class IncomingPaymentRequestUiModel(
    val id: String,
    val senderName: String,
    val senderDeviceId: String,
    val amountMinor: Long,
    val signatureStatus: WalletSignatureStatus,
    val createdAt: String,
    val expiresAt: String
)

enum class WalletHistoryFilter(val label: String) {
    All("All"),
    Sent("Sent"),
    Received("Received")
}

data class WalletUiState(
    val availableBalanceMinor: Long = 0,
    val pendingCreditsMinor: Long = 0,
    val screenMode: WalletScreenMode = WalletScreenMode.Home,
    val peers: List<WalletPeerUiModel> = emptyList(),
    val transactions: List<WalletTransactionUiModel> = emptyList(),
    val amountInput: String = "",
    val selectedPeerId: String? = null,
    val amountError: String? = null,
    val formError: String? = null,
    val showSendConfirmation: Boolean = false,
    val incomingPaymentRequest: IncomingPaymentRequestUiModel? = null,
    val showIncomingPaymentRequest: Boolean = false,
    val selectedTransactionId: String? = null,
    val selectedDeliveryDetailsId: String? = null,
    val historyFilter: WalletHistoryFilter = WalletHistoryFilter.All
)
