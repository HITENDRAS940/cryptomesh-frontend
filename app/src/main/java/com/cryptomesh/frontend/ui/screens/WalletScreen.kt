package com.cryptomesh.frontend.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cryptomesh.frontend.ui.components.EmptyState
import com.cryptomesh.frontend.ui.components.InfoRow
import com.cryptomesh.frontend.ui.components.MainTabHeader
import com.cryptomesh.frontend.ui.components.ScreenHeader
import com.cryptomesh.frontend.ui.components.StatusPill
import com.cryptomesh.frontend.ui.state.BackendSettlementStatus
import com.cryptomesh.frontend.ui.state.IncomingPaymentRequestUiModel
import com.cryptomesh.frontend.ui.state.ReceiverDeliveryStatus
import com.cryptomesh.frontend.ui.state.RelayDeliveryStatus
import com.cryptomesh.frontend.ui.state.WalletHistoryFilter
import com.cryptomesh.frontend.ui.state.WalletPeerRoute
import com.cryptomesh.frontend.ui.state.WalletPeerUiModel
import com.cryptomesh.frontend.ui.state.WalletScreenMode
import com.cryptomesh.frontend.ui.state.WalletSignatureStatus
import com.cryptomesh.frontend.ui.state.WalletTransactionDirection
import com.cryptomesh.frontend.ui.state.WalletTransactionStatus
import com.cryptomesh.frontend.ui.state.WalletTransactionUiModel
import com.cryptomesh.frontend.ui.state.WalletUiState
import com.cryptomesh.frontend.ui.state.WalletViewModel
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun WalletScreen(
    viewModel: WalletViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTransaction = uiState.transactions.firstOrNull {
        it.id == uiState.selectedTransactionId
    }

    BackHandler(
        enabled = selectedTransaction != null ||
            uiState.screenMode == WalletScreenMode.Send
    ) {
        if (selectedTransaction != null) {
            viewModel.closeTransaction()
        } else {
            viewModel.closeSendPayment()
        }
    }

    uiState.transactions
        .filter { it.autoAdvance }
        .forEach { transaction ->
            LaunchedEffect(transaction.id, transaction.status) {
                delay(900)
                viewModel.advanceTransaction(transaction.id)
            }
        }

    WalletContent(
        uiState = uiState,
        selectedTransaction = selectedTransaction,
        onOpenSend = viewModel::openSendPayment,
        onCloseSend = viewModel::closeSendPayment,
        onAmountChange = viewModel::updateAmountInput,
        onSelectPeer = viewModel::selectPeer,
        onRequestConfirmation = viewModel::requestSendConfirmation,
        onDismissConfirmation = viewModel::dismissSendConfirmation,
        onConfirmPayment = viewModel::confirmPayment,
        onShowIncomingPayment = viewModel::showIncomingPaymentRequest,
        onDismissIncomingPayment = viewModel::dismissIncomingPaymentRequest,
        onDeclineIncomingPayment = viewModel::declineIncomingPaymentRequest,
        onAcceptIncomingPayment = viewModel::acceptIncomingPaymentRequest,
        onOpenTransaction = viewModel::openTransaction,
        onCloseTransaction = viewModel::closeTransaction,
        onShowDeliveryDetails = viewModel::showDeliveryDetails,
        onDismissDeliveryDetails = viewModel::dismissDeliveryDetails,
        onRetryDelivery = viewModel::retryReceiverDelivery,
        onFilterChange = viewModel::setHistoryFilter
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletContent(
    uiState: WalletUiState,
    selectedTransaction: WalletTransactionUiModel?,
    onOpenSend: () -> Unit,
    onCloseSend: () -> Unit,
    onAmountChange: (String) -> Unit,
    onSelectPeer: (String) -> Unit,
    onRequestConfirmation: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onConfirmPayment: () -> Unit,
    onShowIncomingPayment: () -> Unit,
    onDismissIncomingPayment: () -> Unit,
    onDeclineIncomingPayment: () -> Unit,
    onAcceptIncomingPayment: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    onCloseTransaction: () -> Unit,
    onShowDeliveryDetails: (String) -> Unit,
    onDismissDeliveryDetails: () -> Unit,
    onRetryDelivery: (String) -> Unit,
    onFilterChange: (WalletHistoryFilter) -> Unit
) {
    when {
        selectedTransaction != null -> TransactionDetailScreen(
            transaction = selectedTransaction,
            availableBalanceMinor = uiState.availableBalanceMinor,
            onBack = onCloseTransaction,
            onShowDeliveryDetails = {
                onShowDeliveryDetails(selectedTransaction.id)
            },
            onRetryDelivery = {
                onRetryDelivery(selectedTransaction.id)
            }
        )

        uiState.screenMode == WalletScreenMode.Send -> SendPaymentScreen(
            uiState = uiState,
            onBack = onCloseSend,
            onAmountChange = onAmountChange,
            onSelectPeer = onSelectPeer,
            onContinue = onRequestConfirmation
        )

        else -> WalletHome(
            uiState = uiState,
            onOpenSend = onOpenSend,
            onShowIncomingPayment = onShowIncomingPayment,
            onOpenTransaction = onOpenTransaction,
            onFilterChange = onFilterChange
        )
    }

    if (uiState.showSendConfirmation) {
        val selectedPeer = uiState.peers.firstOrNull {
            it.id == uiState.selectedPeerId
        }
        val amountMinor = parseDisplayAmountMinor(uiState.amountInput)
        if (selectedPeer != null && amountMinor != null) {
            SendPaymentConfirmationDialog(
                peer = selectedPeer,
                amountMinor = amountMinor,
                onDismiss = onDismissConfirmation,
                onConfirm = onConfirmPayment
            )
        }
    }

    if (uiState.showIncomingPaymentRequest) {
        uiState.incomingPaymentRequest?.let { request ->
            IncomingPaymentDialog(
                request = request,
                onDismiss = onDismissIncomingPayment,
                onDecline = onDeclineIncomingPayment,
                onAccept = onAcceptIncomingPayment
            )
        }
    }

    val deliveryTransaction = uiState.transactions.firstOrNull {
        it.id == uiState.selectedDeliveryDetailsId
    }
    if (deliveryTransaction != null) {
        ModalBottomSheet(
            onDismissRequest = onDismissDeliveryDetails,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            UseWalletDialogSystemBars()
            TransactionDeliveryDetails(
                transaction = deliveryTransaction,
                availableBalanceMinor = uiState.availableBalanceMinor,
                onRetryDelivery = {
                    onRetryDelivery(deliveryTransaction.id)
                }
            )
        }
    }
}

@Composable
private fun WalletHome(
    uiState: WalletUiState,
    onOpenSend: () -> Unit,
    onShowIncomingPayment: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    onFilterChange: (WalletHistoryFilter) -> Unit
) {
    val filteredTransactions = uiState.transactions.filter { transaction ->
        when (uiState.historyFilter) {
            WalletHistoryFilter.All -> true
            WalletHistoryFilter.Sent ->
                transaction.direction == WalletTransactionDirection.Sent
            WalletHistoryFilter.Received ->
                transaction.direction == WalletTransactionDirection.Received
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            MainTabHeader(
                title = "Offline Wallet",
                supportingText = "Prepaid balance on this device",
                trailingContent = {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Protected local wallet",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp,
                    vertical = 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    WalletBalanceCard(
                        availableBalanceMinor = uiState.availableBalanceMinor,
                        pendingCreditsMinor = uiState.pendingCreditsMinor
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onOpenSend,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.CallMade,
                                contentDescription = null
                            )
                            Text(
                                text = "Send",
                                modifier = Modifier.padding(start = 7.dp)
                            )
                        }
                        OutlinedButton(
                            onClick = onShowIncomingPayment,
                            enabled = uiState.incomingPaymentRequest != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.CallReceived,
                                contentDescription = null
                            )
                            Text(
                                text = "Receive",
                                modifier = Modifier.padding(start = 7.dp)
                            )
                        }
                    }
                }

                item {
                    SettlementNotice()
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Transactions",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WalletHistoryFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = uiState.historyFilter == filter,
                                onClick = { onFilterChange(filter) },
                                label = { Text(filter.label) }
                            )
                        }
                    }
                }

                if (filteredTransactions.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.AccountBalanceWallet,
                            title = "No transactions",
                            description =
                                "Transactions in this category will appear here."
                        )
                    }
                } else {
                    items(filteredTransactions, key = { it.id }) { transaction ->
                        TransactionListItem(
                            transaction = transaction,
                            onClick = { onOpenTransaction(transaction.id) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun WalletBalanceCard(
    availableBalanceMinor: Long,
    pendingCreditsMinor: Long
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Available balance",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = formatWalletAmount(availableBalanceMinor),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pending credit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = formatWalletAmount(pendingCreditsMinor),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    text = "Awaiting settlement",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun SettlementNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "Delivery is not settlement",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "A receiver can obtain a signed payment offline. Final settlement and duplicate checks require backend synchronization.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun TransactionListItem(
    transaction: WalletTransactionUiModel,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TransactionDirectionIcon(transaction.direction)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transaction.counterpartyName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = transaction.createdAt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = formatSignedWalletAmount(transaction),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (
                        transaction.direction == WalletTransactionDirection.Received
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.End
                )
            }

            HorizontalDivider()

            WalletStatusLine(
                icon = receiverStatusIcon(transaction.receiverDeliveryStatus),
                label = "Receiver",
                value = transaction.receiverDeliveryStatus.label,
                color = receiverStatusColor(transaction.receiverDeliveryStatus)
            )
            WalletStatusLine(
                icon = backendStatusIcon(transaction.backendSettlementStatus),
                label = "Backend",
                value = transaction.backendSettlementStatus.label,
                color = backendStatusColor(transaction.backendSettlementStatus)
            )
        }
    }
}

@Composable
private fun TransactionDirectionIcon(direction: WalletTransactionDirection) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(
                color = if (direction == WalletTransactionDirection.Received) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (direction == WalletTransactionDirection.Received) {
                Icons.AutoMirrored.Filled.CallReceived
            } else {
                Icons.AutoMirrored.Filled.CallMade
            },
            contentDescription = null,
            tint = if (direction == WalletTransactionDirection.Received) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun WalletStatusLine(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = color
        )
        Text(
            text = label,
            modifier = Modifier.widthIn(min = 58.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SendPaymentScreen(
    uiState: WalletUiState,
    onBack: () -> Unit,
    onAmountChange: (String) -> Unit,
    onSelectPeer: (String) -> Unit,
    onContinue: () -> Unit
) {
    val keyboardModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Modifier.imePadding()
    } else {
        Modifier
    }
    val selectedPeer = uiState.peers.firstOrNull {
        it.id == uiState.selectedPeerId
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = keyboardModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ScreenHeader(
                title = "Send payment",
                supportingText = "Create a signed offline transaction",
                onBack = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = uiState.amountInput,
                    onValueChange = onAmountChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Amount") },
                    placeholder = { Text("0.00") },
                    suffix = { Text("₹") },
                    isError = uiState.amountError != null,
                    supportingText = {
                        Text(
                            text = uiState.amountError
                                ?: "Available ${formatWalletAmount(uiState.availableBalanceMinor)}"
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onContinue() }
                    )
                )

                Text(
                    text = "Receiver",
                    style = MaterialTheme.typography.titleMedium
                )
                uiState.peers.forEach { peer ->
                    WalletPeerOption(
                        peer = peer,
                        selected = uiState.selectedPeerId == peer.id,
                        onClick = { onSelectPeer(peer.id) }
                    )
                }

                uiState.formError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                selectedPeer?.let { peer ->
                    PaymentRouteNotice(peer = peer)
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "The transaction is signed locally. Private keys never appear on screen or leave this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = null
                    )
                    Text(
                        text = "Review payment",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun WalletPeerOption(
    peer: WalletPeerUiModel,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (peer.isVerified) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "Verified receiver",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = "${peer.deviceId} | ${peer.route.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PaymentRouteNotice(peer: WalletPeerUiModel) {
    val color = when (peer.route) {
        WalletPeerRoute.Direct -> MaterialTheme.colorScheme.primaryContainer
        WalletPeerRoute.TrustedRelay -> MaterialTheme.colorScheme.secondaryContainer
        WalletPeerRoute.Unavailable -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (peer.route) {
        WalletPeerRoute.Direct -> MaterialTheme.colorScheme.onPrimaryContainer
        WalletPeerRoute.TrustedRelay ->
            MaterialTheme.colorScheme.onSecondaryContainer
        WalletPeerRoute.Unavailable -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = when (peer.route) {
                    WalletPeerRoute.Direct -> Icons.Default.Person
                    WalletPeerRoute.TrustedRelay -> Icons.Default.Route
                    WalletPeerRoute.Unavailable -> Icons.Default.CloudOff
                },
                contentDescription = null,
                tint = contentColor
            )
            Column {
                Text(
                    text = peer.route.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor
                )
                Text(
                    text = peer.route.supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun SendPaymentConfirmationDialog(
    peer: WalletPeerUiModel,
    amountMinor: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Payments,
                contentDescription = null
            )
        },
        title = { Text("Confirm payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = formatWalletAmount(amountMinor),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "To ${peer.name}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${peer.route.label} delivery",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "This reserves the amount and signs the transaction locally. Backend settlement remains pending until synchronization.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Sign and send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun IncomingPaymentDialog(
    request: IncomingPaymentRequestUiModel,
    onDismiss: () -> Unit,
    onDecline: () -> Unit,
    onAccept: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.CallReceived,
                contentDescription = null
            )
        },
        title = { Text("Incoming payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    text = formatWalletAmount(request.amountMinor),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "From ${request.senderName}",
                    style = MaterialTheme.typography.titleMedium
                )
                WalletStatusLine(
                    icon = if (
                        request.signatureStatus == WalletSignatureStatus.Failed
                    ) {
                        Icons.Default.ErrorOutline
                    } else {
                        Icons.Default.VerifiedUser
                    },
                    label = "Signature",
                    value = request.signatureStatus.label,
                    color = if (
                        request.signatureStatus == WalletSignatureStatus.Failed
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Text(
                    text = "Accepted funds remain pending credit until backend settlement and duplicate checks complete.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                enabled = request.signatureStatus != WalletSignatureStatus.Failed
            ) {
                Text("Accept")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("Decline")
            }
        }
    )
}

@Composable
private fun TransactionDetailScreen(
    transaction: WalletTransactionUiModel,
    availableBalanceMinor: Long,
    onBack: () -> Unit,
    onShowDeliveryDetails: () -> Unit,
    onRetryDelivery: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ScreenHeader(
                title = "Transaction",
                supportingText = transaction.id,
                onBack = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TransactionDirectionIcon(transaction.direction)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            text = if (
                                transaction.direction ==
                                WalletTransactionDirection.Sent
                            ) {
                                "Paid to ${transaction.counterpartyName}"
                            } else {
                                "Received from ${transaction.counterpartyName}"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = transaction.createdAt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = formatSignedWalletAmount(transaction),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (
                            transaction.direction ==
                            WalletTransactionDirection.Received
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                StatusPill(
                    text = transaction.status.userLabel,
                    containerColor =
                        transactionStatusContainerColor(transaction.status),
                    contentColor = transactionStatusColor(transaction.status)
                )

                Text(
                    text = "Delivery and settlement",
                    style = MaterialTheme.typography.titleMedium
                )
                TransactionStatusBlock(
                    title = "Receiver delivery",
                    value = transaction.receiverDeliveryStatus.label,
                    supportingText = receiverDeliveryExplanation(transaction),
                    icon = receiverStatusIcon(transaction.receiverDeliveryStatus),
                    color = receiverStatusColor(transaction.receiverDeliveryStatus)
                )
                TransactionStatusBlock(
                    title = "Backend settlement",
                    value = transaction.backendSettlementStatus.label,
                    supportingText = backendSettlementExplanation(transaction),
                    icon = backendStatusIcon(transaction.backendSettlementStatus),
                    color = backendStatusColor(transaction.backendSettlementStatus)
                )

                OutlinedButton(
                    onClick = onShowDeliveryDetails,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = null
                    )
                    Text(
                        text = "Delivery details",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                if (
                    transaction.status ==
                    WalletTransactionStatus.ExpiredBeforeReceiverDelivery
                ) {
                    Button(
                        onClick = onRetryDelivery,
                        enabled = transaction.amountMinor <= availableBalanceMinor,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null
                        )
                        Text(
                            text = "Retry receiver delivery",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Relay copies help transport a signed transaction. They do not prevent double-spending or provide final settlement.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TransactionStatusBlock(
    title: String,
    value: String,
    supportingText: String,
    icon: ImageVector,
    color: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = color
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TransactionDeliveryDetails(
    transaction: WalletTransactionUiModel,
    availableBalanceMinor: Long,
    onRetryDelivery: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        transactionStatusContainerColor(transaction.status),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = transactionStatusIcon(transaction.status),
                    contentDescription = null,
                    tint = transactionStatusColor(transaction.status)
                )
            }
            Column {
                Text(
                    text = "Transaction delivery",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = transaction.status.technicalLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = transactionStatusColor(transaction.status)
                )
            }
        }

        InfoRow(label = "Transaction ID", value = transaction.id)
        InfoRow(label = "Signature", value = transaction.signatureStatus.label)
        InfoRow(
            label = "Receiver delivery",
            value = transaction.receiverDeliveryStatus.label
        )
        InfoRow(
            label = "Relay delivery",
            value = transaction.relayDeliveryStatus.label
        )
        InfoRow(
            label = "Backend synchronization",
            value = transaction.backendSettlementStatus.label
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow(
                label = "Created",
                value = transaction.createdAt,
                modifier = Modifier.weight(1f)
            )
            InfoRow(
                label = "Expiry",
                value = transaction.expiresAt,
                modifier = Modifier.weight(1f)
            )
        }
        InfoRow(
            label = "Duplicate detection",
            value = transaction.duplicateDetectionStatus.label
        )
        InfoRow(
            label = "Trusted replicas",
            value = transaction.trustedReplicaCount.toString()
        )

        if (
            transaction.status ==
            WalletTransactionStatus.ExpiredBeforeReceiverDelivery
        ) {
            Button(
                onClick = onRetryDelivery,
                enabled = transaction.amountMinor <= availableBalanceMinor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null
                )
                Text(
                    text = "Retry delivery",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Text(
            text = "No private keys or sensitive cryptographic material are shown. Receiver delivery and relay storage are not final backend settlement.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun receiverStatusColor(status: ReceiverDeliveryStatus): Color {
    return when (status) {
        ReceiverDeliveryStatus.Delivered -> MaterialTheme.colorScheme.primary
        ReceiverDeliveryStatus.Expired,
        ReceiverDeliveryStatus.BlockedByInvalidSignature ->
            MaterialTheme.colorScheme.error
        ReceiverDeliveryStatus.StoredByTrustedRelay ->
            MaterialTheme.colorScheme.onSecondaryContainer
        ReceiverDeliveryStatus.Waiting ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun receiverStatusIcon(status: ReceiverDeliveryStatus): ImageVector {
    return when (status) {
        ReceiverDeliveryStatus.Delivered -> Icons.Default.CheckCircle
        ReceiverDeliveryStatus.StoredByTrustedRelay -> Icons.Default.Route
        ReceiverDeliveryStatus.Waiting -> Icons.Default.Schedule
        ReceiverDeliveryStatus.Expired,
        ReceiverDeliveryStatus.BlockedByInvalidSignature ->
            Icons.Default.ErrorOutline
    }
}

@Composable
private fun backendStatusColor(status: BackendSettlementStatus): Color {
    return when (status) {
        BackendSettlementStatus.Synchronized -> MaterialTheme.colorScheme.primary
        BackendSettlementStatus.Rejected,
        BackendSettlementStatus.DuplicateRejected ->
            MaterialTheme.colorScheme.error
        BackendSettlementStatus.Pending ->
            MaterialTheme.colorScheme.onSecondaryContainer
        BackendSettlementStatus.NotSubmitted ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun backendStatusIcon(status: BackendSettlementStatus): ImageVector {
    return when (status) {
        BackendSettlementStatus.Synchronized -> Icons.Default.CloudDone
        BackendSettlementStatus.Pending -> Icons.Default.Sync
        BackendSettlementStatus.NotSubmitted -> Icons.Default.CloudOff
        BackendSettlementStatus.Rejected,
        BackendSettlementStatus.DuplicateRejected ->
            Icons.Default.ErrorOutline
    }
}

@Composable
private fun transactionStatusColor(status: WalletTransactionStatus): Color {
    return when (status) {
        WalletTransactionStatus.Synchronized,
        WalletTransactionStatus.DeliveredToReceiver ->
            MaterialTheme.colorScheme.primary
        WalletTransactionStatus.RejectedByBackend,
        WalletTransactionStatus.DuplicateTransactionRejected,
        WalletTransactionStatus.ExpiredBeforeReceiverDelivery,
        WalletTransactionStatus.SignatureVerificationFailed ->
            MaterialTheme.colorScheme.error
        WalletTransactionStatus.StoredByTrustedRelay,
        WalletTransactionStatus.PendingBackendSettlement ->
            MaterialTheme.colorScheme.onSecondaryContainer
        WalletTransactionStatus.SignedLocally,
        WalletTransactionStatus.WaitingForReceiver ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun transactionStatusContainerColor(
    status: WalletTransactionStatus
): Color {
    return when (status) {
        WalletTransactionStatus.Synchronized,
        WalletTransactionStatus.DeliveredToReceiver ->
            MaterialTheme.colorScheme.primaryContainer
        WalletTransactionStatus.RejectedByBackend,
        WalletTransactionStatus.DuplicateTransactionRejected,
        WalletTransactionStatus.ExpiredBeforeReceiverDelivery,
        WalletTransactionStatus.SignatureVerificationFailed ->
            MaterialTheme.colorScheme.errorContainer
        WalletTransactionStatus.StoredByTrustedRelay,
        WalletTransactionStatus.PendingBackendSettlement ->
            MaterialTheme.colorScheme.secondaryContainer
        WalletTransactionStatus.SignedLocally,
        WalletTransactionStatus.WaitingForReceiver ->
            MaterialTheme.colorScheme.surfaceContainer
    }
}

private fun transactionStatusIcon(status: WalletTransactionStatus): ImageVector {
    return when (status) {
        WalletTransactionStatus.Synchronized -> Icons.Default.CloudDone
        WalletTransactionStatus.DeliveredToReceiver -> Icons.Default.CheckCircle
        WalletTransactionStatus.StoredByTrustedRelay -> Icons.Default.Route
        WalletTransactionStatus.PendingBackendSettlement -> Icons.Default.Sync
        WalletTransactionStatus.SignedLocally -> Icons.Default.Lock
        WalletTransactionStatus.WaitingForReceiver -> Icons.Default.HourglassTop
        WalletTransactionStatus.RejectedByBackend,
        WalletTransactionStatus.DuplicateTransactionRejected,
        WalletTransactionStatus.ExpiredBeforeReceiverDelivery,
        WalletTransactionStatus.SignatureVerificationFailed ->
            Icons.Default.ErrorOutline
    }
}

private fun receiverDeliveryExplanation(
    transaction: WalletTransactionUiModel
): String {
    return when (transaction.receiverDeliveryStatus) {
        ReceiverDeliveryStatus.Delivered ->
            "The receiver has the signed transaction. This does not confirm backend settlement."
        ReceiverDeliveryStatus.StoredByTrustedRelay ->
            "A trusted relay is carrying the signed transaction to the receiver."
        ReceiverDeliveryStatus.Waiting ->
            "The transaction remains protected locally until the receiver or a trusted relay is available."
        ReceiverDeliveryStatus.Expired ->
            "The receiver was not reached before expiry. Reserved funds were returned."
        ReceiverDeliveryStatus.BlockedByInvalidSignature ->
            "The transaction was blocked locally because its signature could not be verified."
    }
}

private fun backendSettlementExplanation(
    transaction: WalletTransactionUiModel
): String {
    return when (transaction.backendSettlementStatus) {
        BackendSettlementStatus.Synchronized ->
            "The backend accepted this transaction and completed settlement."
        BackendSettlementStatus.Pending ->
            "Internet synchronization is still required for settlement and duplicate checks."
        BackendSettlementStatus.NotSubmitted ->
            "The transaction has not been submitted to the backend."
        BackendSettlementStatus.Rejected ->
            "The backend rejected settlement. Receiver delivery may already have occurred."
        BackendSettlementStatus.DuplicateRejected ->
            "The backend identified this transaction as a duplicate and rejected settlement."
    }
}

private fun formatSignedWalletAmount(
    transaction: WalletTransactionUiModel
): String {
    val sign = if (transaction.direction == WalletTransactionDirection.Received) {
        "+"
    } else {
        "-"
    }
    return "$sign${formatWalletAmount(transaction.amountMinor)}"
}

private fun formatWalletAmount(amountMinor: Long): String {
    val whole = amountMinor / 100
    val fraction = amountMinor % 100
    return String.format(
        Locale.US,
        "%,d.%02d ₹",
        whole,
        fraction
    )
}

private fun parseDisplayAmountMinor(input: String): Long? {
    val value = input.toBigDecimalOrNull() ?: return null
    return runCatching {
        value.movePointRight(2).longValueExact()
    }.getOrNull()
}

@Composable
private fun UseWalletDialogSystemBars() {
    val view = LocalView.current

    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let {
            WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true
        onDispose { }
    }
}
