package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.ActionButton
import com.cryptomesh.frontend.ui.components.MainTabHeader
import com.cryptomesh.frontend.ui.components.MetricCard
import com.cryptomesh.frontend.ui.components.SectionHeader
import com.cryptomesh.frontend.ui.components.StatusPill
import com.cryptomesh.frontend.data.repository.DirectMeshState
import com.cryptomesh.frontend.data.repository.DirectMessageStatus
import com.cryptomesh.frontend.data.repository.DirectPeerStatus
import com.cryptomesh.frontend.transport.TransportUnavailableReason
import com.cryptomesh.frontend.ui.state.LocalIdentity

@Composable
fun DashboardScreen(
    identity: LocalIdentity?,
    meshState: DirectMeshState,
    onOpenProfile: () -> Unit,
    onOpenPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit
) {
    val connectedPeers = meshState.peers.count {
        it.status == DirectPeerStatus.Connected
    }
    val pendingMessages = meshState.messages.count {
        it.isOutgoing &&
            it.status != DirectMessageStatus.Acknowledged &&
            it.status != DirectMessageStatus.Failed
    }
    val acknowledgedMessages = meshState.messages.count {
        it.status == DirectMessageStatus.Acknowledged
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
                title = "CryptoMesh",
                supportingText =
                    "Signed in as ${identity?.displayName ?: "Local user"}",
                trailingContent = {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ActionButton(
                            label = "Profile",
                            icon = Icons.Default.Badge,
                            onClick = onOpenProfile,
                            modifier = Modifier.weight(1f)
                        )
                        ActionButton(
                            label = "Permissions",
                            icon = Icons.Default.Lock,
                            onClick = onOpenPermissions,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    SectionHeader(text = "Current State")
                }

                item {
                    MetricCard(
                        title = "Nearby peers",
                        value = meshState.peers.size.toString(),
                        supportingText = "$connectedPeers authenticated sessions"
                    )
                }
                item {
                    MetricCard(
                        title = "Messages awaiting ACK",
                        value = pendingMessages.toString(),
                        supportingText = "$acknowledgedMessages messages acknowledged"
                    )
                }
                item {
                    StatusPill(
                        text = when {
                            !meshState.transportAvailable ->
                                "Bluetooth unavailable"
                            meshState.isScanning -> "Scanning for peers"
                            meshState.isAdvertising -> "Visible to nearby peers"
                            meshState.transportAvailable -> "Bluetooth ready"
                            else -> "Bluetooth not active"
                        }
                    )
                }
                if (
                    meshState.transportUnavailableReason ==
                    TransportUnavailableReason.BluetoothDisabled
                ) {
                    item {
                        ActionButton(
                            label = "Turn on Bluetooth",
                            icon = Icons.Default.Bluetooth,
                            onClick = onEnableBluetooth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
