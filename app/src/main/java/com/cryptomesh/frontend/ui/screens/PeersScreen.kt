package com.cryptomesh.frontend.ui.screens

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptomesh.frontend.transport.TransportUnavailableReason
import com.cryptomesh.frontend.transport.isBluetoothEnabled
import com.cryptomesh.frontend.transport.requiredBluetoothPermissions
import com.cryptomesh.frontend.ui.components.EmptyState
import com.cryptomesh.frontend.ui.components.InfoRow
import com.cryptomesh.frontend.ui.components.MainTabHeader
import com.cryptomesh.frontend.ui.components.StatusPill
import com.cryptomesh.frontend.ui.state.NearbyPeerUiModel
import com.cryptomesh.frontend.ui.state.PeerConnectionStatus
import com.cryptomesh.frontend.ui.state.PeerDiscoveryViewModel

@Composable
fun PeersScreen(
    viewModel: PeerDiscoveryViewModel,
    onRequestBluetooth: ((Boolean) -> Unit) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val beginScan = {
        if (isBluetoothEnabled(context)) {
            viewModel.startScan()
        } else {
            onRequestBluetooth { enabled ->
                if (enabled) {
                    viewModel.startScan()
                } else {
                    viewModel.reportScanFailure(
                        "Bluetooth must be turned on to find nearby peers."
                    )
                }
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            beginScan()
        } else {
            viewModel.reportScanFailure(
                "Nearby Devices permission was not granted."
            )
        }
    }
    val startScan = {
        val missingPermissions = requiredBluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            beginScan()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    DisposableEffect(viewModel) {
        onDispose(viewModel::stopScan)
    }

    val selectedPeer = uiState.peers.firstOrNull {
        it.id == uiState.selectedPeerId
    }
    val requestedPeer = uiState.peers.firstOrNull {
        it.id == uiState.connectionRequestPeerId
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MainTabHeader(
                title = "Nearby Peers",
                supportingText = if (uiState.isScanning) {
                    "Bluetooth scan active"
                } else {
                    "Authenticated Bluetooth LE"
                },
                trailingContent = {
                    IconButton(
                        onClick = startScan,
                        enabled = !uiState.isScanning
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Scan again"
                        )
                    }
                }
            )

            ScanControl(
                isScanning = uiState.isScanning,
                onStart = startScan,
                onStop = viewModel::stopScan
            )

            when {
                uiState.scanError != null -> Column {
                    EmptyState(
                        icon = Icons.Default.ErrorOutline,
                        title = "Bluetooth unavailable",
                        description = uiState.scanError.orEmpty()
                    )
                    if (
                        uiState.transportUnavailableReason ==
                        TransportUnavailableReason.BluetoothDisabled
                    ) {
                        Button(
                            onClick = beginScan,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(horizontal = 20.dp)
                        ) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null
                            )
                            Text(
                                text = "Turn on Bluetooth",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                uiState.isScanning && uiState.peers.isEmpty() -> EmptyState(
                    icon = Icons.Default.Search,
                    title = "Scanning nearby",
                    description = "Searching for CryptoMesh devices."
                )

                !uiState.hasScanned && uiState.peers.isEmpty() -> EmptyState(
                    icon = Icons.Default.Devices,
                    title = "No scan results",
                    description = "Nearby authenticated peers appear here."
                )

                uiState.peers.isEmpty() -> EmptyState(
                    icon = Icons.Default.Bluetooth,
                    title = "No peers found",
                    description = "No CryptoMesh BLE advertisements were detected."
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.peers, key = NearbyPeerUiModel::id) { peer ->
                        PeerCard(
                            peer = peer,
                            onOpen = { viewModel.selectPeer(peer.id) },
                            onConnect = {
                                viewModel.requestConnection(peer.id)
                            },
                            onRetry = {
                                viewModel.retryConnection(peer.id)
                            }
                        )
                    }
                }
            }
        }
    }

    if (selectedPeer != null) {
        PeerDetails(
            peer = selectedPeer,
            onDismiss = viewModel::dismissPeerDetails,
            onConnect = {
                viewModel.dismissPeerDetails()
                viewModel.requestConnection(selectedPeer.id)
            },
            onDisconnect = {
                viewModel.disconnect(selectedPeer.id)
                viewModel.dismissPeerDetails()
            }
        )
    }

    if (requestedPeer != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConnectionRequest,
            icon = { Icon(Icons.Default.Shield, contentDescription = null) },
            title = { Text("Connect to ${requestedPeer.displayName}?") },
            text = {
                Text(
                    "CryptoMesh will verify this peer's signing identity before creating an encrypted session."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmConnection) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConnectionRequest) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ScanControl(
    isScanning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isScanning) {
                CircularProgressIndicator()
            } else {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isScanning) "Scanning" else "Peer discovery",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Bluetooth Low Energy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isScanning) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
            } else {
                Button(onClick = onStart) { Text("Scan") }
            }
        }
    }
}

@Composable
private fun PeerCard(
    peer: NearbyPeerUiModel,
    onOpen: () -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
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
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    if (peer.isVerified) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.Bluetooth
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        peer.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        peer.deviceId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(text = peer.connectionStatus.label())
            }
            Text(
                "${peer.proximity} - ${peer.signalLabel} signal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            peer.failureMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            when (peer.connectionStatus) {
                PeerConnectionStatus.Available ->
                    Button(onClick = onConnect) { Text("Connect") }
                PeerConnectionStatus.Failed ->
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerDetails(
    peer: NearbyPeerUiModel,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                peer.displayName,
                style = MaterialTheme.typography.headlineSmall
            )
            InfoRow("Device ID", peer.deviceId)
            InfoRow("Transport", "Bluetooth Low Energy")
            InfoRow("Signal", "${peer.signalLabel} - ${peer.proximity}")
            InfoRow(
                "Identity",
                if (peer.isVerified) "Cryptographically verified" else "Not verified"
            )
            InfoRow("Connection", peer.connectionStatus.label())
            if (peer.connectionStatus == PeerConnectionStatus.Connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
            } else if (
                peer.connectionStatus == PeerConnectionStatus.Available ||
                peer.connectionStatus == PeerConnectionStatus.Failed
            ) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun PeerConnectionStatus.label(): String {
    return when (this) {
        PeerConnectionStatus.Available -> "Available"
        PeerConnectionStatus.Connecting -> "Connecting"
        PeerConnectionStatus.Authenticating -> "Verifying"
        PeerConnectionStatus.Connected -> "Connected"
        PeerConnectionStatus.Failed -> "Failed"
    }
}
