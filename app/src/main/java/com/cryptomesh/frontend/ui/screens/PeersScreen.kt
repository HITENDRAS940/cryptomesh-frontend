package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptomesh.frontend.ui.components.EmptyState
import com.cryptomesh.frontend.ui.components.InfoRow
import com.cryptomesh.frontend.ui.components.MainTabHeader
import com.cryptomesh.frontend.ui.components.StatusPill
import com.cryptomesh.frontend.ui.state.NearbyPeerUiModel
import com.cryptomesh.frontend.ui.state.PeerConnectionStatus
import com.cryptomesh.frontend.ui.state.PeerDiscoveryUiState
import com.cryptomesh.frontend.ui.state.PeerDiscoveryViewModel
import com.cryptomesh.frontend.ui.state.PeerResourceUiModel
import com.cryptomesh.frontend.ui.state.PeerTrustLevel
import com.cryptomesh.frontend.ui.state.RelayEligibility
import com.cryptomesh.frontend.ui.theme.CryptoMeshTheme
import kotlinx.coroutines.delay

@Composable
fun PeersScreen(
    viewModel: PeerDiscoveryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isScanning) {
        if (uiState.isScanning) {
            delay(1_400)
            viewModel.completeScan()
        }
    }

    uiState.peers
        .filter { it.connectionStatus == PeerConnectionStatus.Connecting }
        .forEach { peer ->
            LaunchedEffect(peer.id, peer.connectionStatus) {
                delay(1_000)
                viewModel.completeConnection(peer.id)
            }
        }

    PeerDiscoveryContent(
        uiState = uiState,
        onStartScan = viewModel::startScan,
        onStopScan = viewModel::stopScan,
        onSelectPeer = viewModel::selectPeer,
        onDismissPeer = viewModel::dismissPeerDetails,
        onRequestConnection = viewModel::requestConnection,
        onDismissConnectionRequest = viewModel::dismissConnectionRequest,
        onConfirmConnection = viewModel::confirmConnection,
        onRetryConnection = viewModel::retryConnection,
        onDisconnect = viewModel::disconnect
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerDiscoveryContent(
    uiState: PeerDiscoveryUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectPeer: (String) -> Unit,
    onDismissPeer: () -> Unit,
    onRequestConnection: (String) -> Unit,
    onDismissConnectionRequest: () -> Unit,
    onConfirmConnection: () -> Unit,
    onRetryConnection: (String) -> Unit,
    onDisconnect: (String) -> Unit
) {
    val selectedPeer = uiState.peers.firstOrNull { it.id == uiState.selectedPeerId }
    val requestedPeer = uiState.peers.firstOrNull {
        it.id == uiState.connectionRequestPeerId
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            MainTabHeader(
                title = "Nearby Peers",
                supportingText = if (uiState.isScanning) {
                    "Local scan active"
                } else {
                    "Bluetooth and nearby Wi-Fi"
                },
                trailingContent = {
                    IconButton(
                        onClick = onStartScan,
                        enabled = !uiState.isScanning
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh nearby peers"
                        )
                    }
                }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ScanControl(
                    isScanning = uiState.isScanning,
                    onStartScan = onStartScan,
                    onStopScan = onStopScan
                )

                when {
                    uiState.scanError != null -> {
                        EmptyState(
                            icon = Icons.Default.ErrorOutline,
                            title = "Scan unavailable",
                            description = uiState.scanError
                        )
                        OutlinedButton(
                            onClick = onStartScan,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text(
                                text = "Retry scan",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    uiState.isScanning -> {
                        EmptyState(
                            icon = Icons.Default.Radar,
                            title = "Looking for nearby peers",
                            description =
                                "Keep this device close to other CryptoMesh devices."
                        )
                    }

                    !uiState.hasScanned -> {
                        EmptyState(
                            icon = Icons.Default.Devices,
                            title = "Ready to discover",
                            description = "Nearby CryptoMesh devices will appear here."
                        )
                    }

                    uiState.peers.isEmpty() -> {
                        EmptyState(
                            icon = Icons.Default.Radar,
                            title = "No peers found",
                            description = "Move closer to another device and scan again."
                        )
                    }

                    else -> {
                        Text(
                            text = "${uiState.peers.size} peers nearby",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(uiState.peers, key = { it.id }) { peer ->
                                PeerListItem(
                                    peer = peer,
                                    onClick = { onSelectPeer(peer.id) },
                                    onConnect = { onRequestConnection(peer.id) },
                                    onRetry = { onRetryConnection(peer.id) }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedPeer != null) {
        ModalBottomSheet(
            onDismissRequest = onDismissPeer,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            PeerDetailsSheet(
                peer = selectedPeer,
                onConnect = {
                    onDismissPeer()
                    onRequestConnection(selectedPeer.id)
                },
                onRetry = { onRetryConnection(selectedPeer.id) },
                onDisconnect = { onDisconnect(selectedPeer.id) }
            )
        }
    }

    if (requestedPeer != null) {
        ConnectionRequestDialog(
            peer = requestedPeer,
            onDismiss = onDismissConnectionRequest,
            onConfirm = onConfirmConnection
        )
    }
}

@Composable
private fun ScanControl(
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (isScanning) Icons.Default.Radar else Icons.Default.NearMe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isScanning) "Scanning nearby" else "Peer discovery",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isScanning) {
                            "Searching on available local connections"
                        } else {
                            "Last results remain only on this device"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isScanning) {
                    TextButton(onClick = onStopScan) {
                        Text("Stop")
                    }
                } else {
                    Button(onClick = onStartScan) {
                        Icon(Icons.Default.Radar, contentDescription = null)
                        Text(
                            text = "Scan",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            if (isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PeerListItem(
    peer: NearbyPeerUiModel,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PeerAvatar(name = peer.displayName)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${peer.proximity} • ${peer.transport}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ConnectionAction(
                    status = peer.connectionStatus,
                    onConnect = onConnect,
                    onRetry = onRetry
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrustBadge(level = peer.trustLevel)
                RelayBadge(eligibility = peer.relayEligibility)
            }

            if (peer.failureMessage != null) {
                Text(
                    text = peer.failureMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    text = "${peer.resources.linkQuality} link • ${peer.resources.reliability} reliability",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PeerAvatar(name: String) {
    val initials = name
        .split(" ")
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .take(2)
        .joinToString("")

    Box(
        modifier = Modifier
            .size(46.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ConnectionAction(
    status: PeerConnectionStatus,
    onConnect: () -> Unit,
    onRetry: () -> Unit
) {
    when (status) {
        PeerConnectionStatus.Available -> {
            IconButton(onClick = onConnect) {
                Icon(Icons.Default.Link, contentDescription = "Connect")
            }
        }

        PeerConnectionStatus.Connecting -> {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        }

        PeerConnectionStatus.Connected -> {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Connected",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        PeerConnectionStatus.Failed -> {
            IconButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Retry connection",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun TrustBadge(level: PeerTrustLevel) {
    val verified = level == PeerTrustLevel.Verified
    StatusPill(
        text = level.label,
        containerColor = if (verified) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (verified) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

@Composable
private fun RelayBadge(eligibility: RelayEligibility) {
    val colors = when (eligibility) {
        RelayEligibility.Eligible -> {
            MaterialTheme.colorScheme.primaryContainer to
                MaterialTheme.colorScheme.onPrimaryContainer
        }
        RelayEligibility.Limited,
        RelayEligibility.DirectDestination -> {
            MaterialTheme.colorScheme.secondaryContainer to
                MaterialTheme.colorScheme.onSecondaryContainer
        }
        RelayEligibility.NotEligible -> {
            MaterialTheme.colorScheme.errorContainer to
                MaterialTheme.colorScheme.onErrorContainer
        }
    }
    StatusPill(
        text = eligibility.label,
        containerColor = colors.first,
        contentColor = colors.second
    )
}

@Composable
private fun ConnectionRequestDialog(
    peer: NearbyPeerUiModel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null
            )
        },
        title = { Text("Connect to ${peer.displayName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Verify the device ID with the other user before sharing sensitive data."
                )
                Text(
                    text = peer.deviceId,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${peer.trustLevel.label} peer • ${peer.transport}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Connect")
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
private fun PeerDetailsSheet(
    peer: NearbyPeerUiModel,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit
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
            PeerAvatar(name = peer.displayName)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = peer.deviceId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TrustIcon(level = peer.trustLevel)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrustBadge(level = peer.trustLevel)
            RelayBadge(eligibility = peer.relayEligibility)
        }

        Text(
            text = peer.relayEligibility.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        Text(
            text = "Connection",
            style = MaterialTheme.typography.titleMedium
        )
        DetailLine(Icons.Default.NearMe, "Proximity", peer.proximity)
        DetailLine(transportIcon(peer.transport), "Transport", peer.transport)
        DetailLine(Icons.Default.History, "Last encounter", peer.lastEncounter)
        DetailLine(
            Icons.Default.CheckCircle,
            "Previous connections",
            peer.successfulConnections.toString()
        )

        Text(
            text = "Privacy-safe resource summary",
            style = MaterialTheme.typography.titleMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow(
                label = "Battery class",
                value = peer.resources.batteryClass,
                modifier = Modifier.weight(1f)
            )
            InfoRow(
                label = "Storage class",
                value = peer.resources.storageClass,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow(
                label = "Link quality",
                value = peer.resources.linkQuality,
                modifier = Modifier.weight(1f)
            )
            InfoRow(
                label = "Reliability",
                value = peer.resources.reliability,
                modifier = Modifier.weight(1f)
            )
        }
        DetailLine(
            Icons.Default.Wifi,
            "Connection stability",
            peer.resources.connectionStability
        )

        peer.failureMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        when (peer.connectionStatus) {
            PeerConnectionStatus.Available -> {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Text("Connect", modifier = Modifier.padding(start = 8.dp))
                }
            }

            PeerConnectionStatus.Connecting -> {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Text("Connecting", modifier = Modifier.padding(start = 8.dp))
                }
            }

            PeerConnectionStatus.Connected -> {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Text("Disconnect", modifier = Modifier.padding(start = 8.dp))
                }
            }

            PeerConnectionStatus.Failed -> {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Retry connection", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun DetailLine(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun TrustIcon(level: PeerTrustLevel) {
    Icon(
        imageVector = if (level == PeerTrustLevel.Verified) {
            Icons.Default.Shield
        } else {
            Icons.Outlined.Shield
        },
        contentDescription = "${level.label} peer",
        tint = if (level == PeerTrustLevel.Unverified) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
    )
}

private fun transportIcon(transport: String): ImageVector {
    return if (transport == "Bluetooth") Icons.Default.Bluetooth else Icons.Default.Wifi
}

@Preview(showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun PeerDiscoveryPreview() {
    CryptoMeshTheme {
        PeerDiscoveryContent(
            uiState = PeerDiscoveryUiState(
                hasScanned = true,
                peers = listOf(
                    NearbyPeerUiModel(
                        id = "preview",
                        displayName = "Aarav's Pixel",
                        deviceId = "CM-7A21F4C8",
                        proximity = "Very close",
                        transport = "Nearby Wi-Fi",
                        trustLevel = PeerTrustLevel.Verified,
                        relayEligibility = RelayEligibility.DirectDestination,
                        resources = PeerResourceUiModel(
                            batteryClass = "Balanced",
                            storageClass = "Available",
                            linkQuality = "Excellent",
                            connectionStability = "Stable",
                            reliability = "High"
                        ),
                        lastEncounter = "Today, 10:42 AM",
                        successfulConnections = 8
                    )
                )
            ),
            onStartScan = {},
            onStopScan = {},
            onSelectPeer = {},
            onDismissPeer = {},
            onRequestConnection = {},
            onDismissConnectionRequest = {},
            onConfirmConnection = {},
            onRetryConnection = {},
            onDisconnect = {}
        )
    }
}
