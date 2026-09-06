package com.cryptomesh.frontend.ui.state

import com.cryptomesh.frontend.transport.TransportUnavailableReason

enum class PeerConnectionStatus {
    Available,
    Connecting,
    Authenticating,
    Connected,
    Failed
}

data class NearbyPeerUiModel(
    val id: String,
    val displayName: String,
    val deviceId: String,
    val signalLabel: String,
    val proximity: String,
    val isVerified: Boolean,
    val connectionStatus: PeerConnectionStatus,
    val failureMessage: String? = null
)

data class PeerDiscoveryUiState(
    val isScanning: Boolean = false,
    val hasScanned: Boolean = false,
    val peers: List<NearbyPeerUiModel> = emptyList(),
    val scanError: String? = null,
    val transportUnavailableReason: TransportUnavailableReason? = null,
    val selectedPeerId: String? = null,
    val connectionRequestPeerId: String? = null
)
