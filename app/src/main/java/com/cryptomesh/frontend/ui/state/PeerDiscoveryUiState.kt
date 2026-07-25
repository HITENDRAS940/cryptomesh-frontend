package com.cryptomesh.frontend.ui.state

enum class PeerTrustLevel(val label: String) {
    Verified("Verified"),
    Known("Known"),
    Unverified("Unverified")
}

enum class RelayEligibility(val label: String, val explanation: String) {
    Eligible(
        label = "Relay eligible",
        explanation = "Resources and connection history meet the current relay policy."
    ),
    Limited(
        label = "Limited relay",
        explanation = "This peer may relay small, normal-priority packets."
    ),
    NotEligible(
        label = "Not a relay",
        explanation = "This peer is unavailable or does not meet the relay policy."
    ),
    DirectDestination(
        label = "Direct destination",
        explanation = "Connect directly when possible; relay selection is not required."
    )
}

enum class PeerConnectionStatus {
    Available,
    Connecting,
    Connected,
    Failed
}

data class PeerResourceUiModel(
    val batteryClass: String,
    val storageClass: String,
    val linkQuality: String,
    val connectionStability: String,
    val reliability: String
)

data class NearbyPeerUiModel(
    val id: String,
    val displayName: String,
    val deviceId: String,
    val proximity: String,
    val transport: String,
    val trustLevel: PeerTrustLevel,
    val relayEligibility: RelayEligibility,
    val resources: PeerResourceUiModel,
    val lastEncounter: String,
    val successfulConnections: Int,
    val connectionStatus: PeerConnectionStatus = PeerConnectionStatus.Available,
    val failureMessage: String? = null
)

data class PeerDiscoveryUiState(
    val isScanning: Boolean = false,
    val hasScanned: Boolean = false,
    val peers: List<NearbyPeerUiModel> = emptyList(),
    val scanError: String? = null,
    val selectedPeerId: String? = null,
    val connectionRequestPeerId: String? = null
)
