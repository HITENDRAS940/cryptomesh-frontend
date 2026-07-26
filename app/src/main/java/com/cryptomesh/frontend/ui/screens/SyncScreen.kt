package com.cryptomesh.frontend.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cryptomesh.frontend.ui.components.EmptyState
import com.cryptomesh.frontend.ui.components.MainTabHeader
import com.cryptomesh.frontend.ui.components.StatusPill
import com.cryptomesh.frontend.ui.state.BackendConnectionStatus
import com.cryptomesh.frontend.ui.state.SyncPacketOwner
import com.cryptomesh.frontend.ui.state.SyncPacketType
import com.cryptomesh.frontend.ui.state.SyncQueueFilter
import com.cryptomesh.frontend.ui.state.SyncQueueItemUiModel
import com.cryptomesh.frontend.ui.state.SyncQueueStatus
import com.cryptomesh.frontend.ui.state.SyncUiState
import com.cryptomesh.frontend.ui.state.SyncViewModel
import com.cryptomesh.frontend.ui.state.filteredItems

@Composable
fun SyncScreen(
    viewModel: SyncViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedErrorItem = uiState.items.firstOrNull {
        it.id == uiState.selectedErrorItemId
    }

    SyncContent(
        uiState = uiState,
        onFilterChange = viewModel::setFilter,
        onManualSync = viewModel::runManualSync,
        onRunCleanup = viewModel::runCleanup,
        onDismissMessage = viewModel::dismissActionMessage,
        onShowErrorDetails = viewModel::showErrorDetails
    )

    if (selectedErrorItem != null) {
        SyncErrorDialog(
            item = selectedErrorItem,
            onDismiss = viewModel::dismissErrorDetails
        )
    }
}

@Composable
private fun SyncContent(
    uiState: SyncUiState,
    onFilterChange: (SyncQueueFilter) -> Unit,
    onManualSync: () -> Unit,
    onRunCleanup: () -> Unit,
    onDismissMessage: () -> Unit,
    onShowErrorDetails: (String) -> Unit
) {
    val filteredItems = uiState.filteredItems()
    val pendingCount = uiState.items.count {
        it.status == SyncQueueStatus.PendingDirectDelivery ||
            it.status == SyncQueueStatus.AwaitingAck ||
            it.status == SyncQueueStatus.PendingSettlement
    }
    val relayCount = uiState.items.count {
        it.owner == SyncPacketOwner.RelayPacket
    }
    val cleanupCount = uiState.items.count {
        it.status == SyncQueueStatus.ExpiredAwaitingCleanup
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MainTabHeader(
                title = "Synchronization",
                supportingText = "${uiState.items.size} local queue items",
                trailingContent = {
                    Icon(
                        imageVector = if (
                            uiState.backendStatus ==
                            BackendConnectionStatus.Connected
                        ) {
                            Icons.Default.CloudDone
                        } else {
                            Icons.Default.CloudOff
                        },
                        contentDescription = uiState.backendStatus.label,
                        tint = backendStatusColor(uiState.backendStatus)
                    )
                }
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = 20.dp,
                    vertical = 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    BackendStatusCard(uiState = uiState)
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onManualSync,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null
                            )
                            Text(
                                text = "Sync now",
                                modifier = Modifier.padding(start = 7.dp)
                            )
                        }
                        OutlinedButton(
                            onClick = onRunCleanup,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = null
                            )
                            Text(
                                text = "Run cleanup",
                                modifier = Modifier.padding(start = 7.dp)
                            )
                        }
                    }
                }

                item {
                    AnimatedVisibility(visible = uiState.actionMessage != null) {
                        uiState.actionMessage?.let { message ->
                            ActionMessage(
                                message = message,
                                onDismiss = onDismissMessage
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SyncMetric(
                            label = "Pending",
                            value = pendingCount.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        SyncMetric(
                            label = "Relays",
                            value = relayCount.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        SyncMetric(
                            label = "Cleanup",
                            value = cleanupCount.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Local queue",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${filteredItems.size} shown",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(end = 12.dp)
                    ) {
                        items(SyncQueueFilter.entries) { filter ->
                            FilterChip(
                                selected = uiState.selectedFilter == filter,
                                onClick = { onFilterChange(filter) },
                                label = { Text(filter.label) }
                            )
                        }
                    }
                }

                if (filteredItems.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.CheckCircle,
                            title = "No matching queue items",
                            description =
                                "There are no items in this sync category."
                        )
                    }
                } else {
                    items(filteredItems, key = { it.id }) { item ->
                        SyncQueueCard(
                            item = item,
                            onShowErrorDetails = {
                                onShowErrorDetails(item.id)
                            }
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
private fun BackendStatusCard(uiState: SyncUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (
                        uiState.backendStatus ==
                        BackendConnectionStatus.Connected
                    ) {
                        Icons.Default.CloudDone
                    } else {
                        Icons.Default.CloudOff
                    },
                    contentDescription = null,
                    tint = backendStatusColor(uiState.backendStatus)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.backendStatus.label,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = uiState.backendStatus.supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(
                    text = if (
                        uiState.backendStatus ==
                        BackendConnectionStatus.Connected
                    ) {
                        "Ready"
                    } else {
                        "Offline"
                    },
                    containerColor = if (
                        uiState.backendStatus ==
                        BackendConnectionStatus.Connected
                    ) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (
                        uiState.backendStatus ==
                        BackendConnectionStatus.Connected
                    ) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            }

            HorizontalDivider()

            SyncTimestampRow(
                icon = Icons.Default.CloudDone,
                label = "Last backend sync",
                value = uiState.lastBackendSync
            )
            SyncTimestampRow(
                icon = Icons.Default.Usb,
                label = "Last forwarding attempt",
                value = uiState.lastAdaptiveForwardingAttempt
            )
            SyncTimestampRow(
                icon = Icons.Default.CleaningServices,
                label = "Replica cleanup",
                value = uiState.cleanupStatus.label
            )
        }
    }
}

@Composable
private fun SyncTimestampRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActionMessage(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = message,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Dismiss message",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun SyncMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SyncQueueCard(
    item: SyncQueueItemUiModel,
    onShowErrorDetails: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = queueStatusContainerColor(item.status)
                ) {
                    Icon(
                        imageVector = packetTypeIcon(item.packetType),
                        contentDescription = item.packetType.label,
                        modifier = Modifier.padding(10.dp),
                        tint = queueStatusColor(item.status)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = item.updatedAt,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    text = item.status.label,
                    containerColor = queueStatusContainerColor(item.status),
                    contentColor = queueStatusColor(item.status)
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (
                        item.owner == SyncPacketOwner.OwnPacket
                    ) {
                        Icons.Default.Person
                    } else {
                        Icons.Default.Usb
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.owner.label,
                    modifier = Modifier.padding(start = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = item.status.supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Last attempt: ${item.lastForwardingAttempt}",
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.replicaCount > 0) {
                    Text(
                        text = "${item.replicaCount} replica",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (item.errorDetails != null) {
                TextButton(
                    onClick = onShowErrorDetails,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null
                    )
                    Text(
                        text = "Error details",
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncErrorDialog(
    item: SyncQueueItemUiModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.SyncProblem,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(text = item.status.label)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = item.errorDetails.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Packet ID: ${item.id}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun packetTypeIcon(type: SyncPacketType): ImageVector {
    return when (type) {
        SyncPacketType.Message -> Icons.AutoMirrored.Filled.Message
        SyncPacketType.File -> Icons.AutoMirrored.Filled.InsertDriveFile
        SyncPacketType.Wallet -> Icons.Default.AccountBalanceWallet
    }
}

@Composable
private fun backendStatusColor(status: BackendConnectionStatus): Color {
    return if (status == BackendConnectionStatus.Connected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }
}

@Composable
private fun queueStatusColor(status: SyncQueueStatus): Color {
    return when (status) {
        SyncQueueStatus.Synchronized,
        SyncQueueStatus.AwaitingAck,
        SyncQueueStatus.StoredReplica ->
            MaterialTheme.colorScheme.primary

        SyncQueueStatus.PendingDirectDelivery,
        SyncQueueStatus.PendingSettlement,
        SyncQueueStatus.ExpiredAwaitingCleanup ->
            MaterialTheme.colorScheme.onSecondaryContainer

        SyncQueueStatus.DuplicateRejected,
        SyncQueueStatus.ServerRejected,
        SyncQueueStatus.Failed ->
            MaterialTheme.colorScheme.error
    }
}

@Composable
private fun queueStatusContainerColor(status: SyncQueueStatus): Color {
    return when (status) {
        SyncQueueStatus.Synchronized,
        SyncQueueStatus.AwaitingAck,
        SyncQueueStatus.StoredReplica ->
            MaterialTheme.colorScheme.primaryContainer

        SyncQueueStatus.PendingDirectDelivery,
        SyncQueueStatus.PendingSettlement,
        SyncQueueStatus.ExpiredAwaitingCleanup ->
            MaterialTheme.colorScheme.secondaryContainer

        SyncQueueStatus.DuplicateRejected,
        SyncQueueStatus.ServerRejected,
        SyncQueueStatus.Failed ->
            MaterialTheme.colorScheme.errorContainer
    }
}
