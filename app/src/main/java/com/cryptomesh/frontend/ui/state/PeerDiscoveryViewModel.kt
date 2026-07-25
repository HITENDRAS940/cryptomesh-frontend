package com.cryptomesh.frontend.ui.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class PeerDiscoveryViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PeerDiscoveryUiState())
    val uiState: StateFlow<PeerDiscoveryUiState> = _uiState.asStateFlow()

    fun startScan() {
        _uiState.update {
            it.copy(
                isScanning = true,
                hasScanned = false,
                peers = emptyList(),
                scanError = null,
                selectedPeerId = null,
                connectionRequestPeerId = null
            )
        }
    }

    fun stopScan() {
        _uiState.update {
            it.copy(
                isScanning = false,
                hasScanned = true
            )
        }
    }

    fun completeScan() {
        _uiState.update {
            if (!it.isScanning) {
                it
            } else {
                it.copy(
                    isScanning = false,
                    hasScanned = true,
                    peers = samplePeers
                )
            }
        }
    }

    fun reportScanFailure(message: String) {
        _uiState.update {
            it.copy(
                isScanning = false,
                hasScanned = true,
                peers = emptyList(),
                scanError = message
            )
        }
    }

    fun selectPeer(peerId: String) {
        _uiState.update { it.copy(selectedPeerId = peerId) }
    }

    fun dismissPeerDetails() {
        _uiState.update { it.copy(selectedPeerId = null) }
    }

    fun requestConnection(peerId: String) {
        val peer = _uiState.value.peers.firstOrNull { it.id == peerId } ?: return
        if (peer.connectionStatus == PeerConnectionStatus.Connected) return
        _uiState.update { it.copy(connectionRequestPeerId = peerId) }
    }

    fun dismissConnectionRequest() {
        _uiState.update { it.copy(connectionRequestPeerId = null) }
    }

    fun confirmConnection() {
        val peerId = _uiState.value.connectionRequestPeerId ?: return
        updatePeer(peerId) {
            it.copy(
                connectionStatus = PeerConnectionStatus.Connecting,
                failureMessage = null
            )
        }
        _uiState.update { it.copy(connectionRequestPeerId = null) }
    }

    fun retryConnection(peerId: String) {
        updatePeer(peerId) {
            it.copy(
                connectionStatus = PeerConnectionStatus.Connecting,
                failureMessage = null
            )
        }
    }

    fun completeConnection(peerId: String) {
        updatePeer(peerId) { peer ->
            if (peer.id == UNAVAILABLE_PEER_ID) {
                peer.copy(
                    connectionStatus = PeerConnectionStatus.Failed,
                    failureMessage = "Peer moved out of range. Scan again or retry nearby."
                )
            } else {
                peer.copy(
                    connectionStatus = PeerConnectionStatus.Connected,
                    failureMessage = null
                )
            }
        }
    }

    fun disconnect(peerId: String) {
        updatePeer(peerId) {
            it.copy(
                connectionStatus = PeerConnectionStatus.Available,
                failureMessage = null
            )
        }
    }

    private fun updatePeer(
        peerId: String,
        transform: (NearbyPeerUiModel) -> NearbyPeerUiModel
    ) {
        _uiState.update { state ->
            state.copy(
                peers = state.peers.map { peer ->
                    if (peer.id == peerId) transform(peer) else peer
                }
            )
        }
    }

    private companion object {
        const val UNAVAILABLE_PEER_ID = "peer-03"

        val samplePeers = listOf(
            NearbyPeerUiModel(
                id = "peer-01",
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
            ),
            NearbyPeerUiModel(
                id = "peer-02",
                displayName = "Library Relay",
                deviceId = "CM-2D90B1E6",
                proximity = "Nearby",
                transport = "Bluetooth",
                trustLevel = PeerTrustLevel.Known,
                relayEligibility = RelayEligibility.Eligible,
                resources = PeerResourceUiModel(
                    batteryClass = "High",
                    storageClass = "Available",
                    linkQuality = "Stable",
                    connectionStability = "Stable",
                    reliability = "High"
                ),
                lastEncounter = "Yesterday, 4:18 PM",
                successfulConnections = 14
            ),
            NearbyPeerUiModel(
                id = UNAVAILABLE_PEER_ID,
                displayName = "Campus Node",
                deviceId = "CM-5F33C0A2",
                proximity = "At edge of range",
                transport = "Bluetooth",
                trustLevel = PeerTrustLevel.Unverified,
                relayEligibility = RelayEligibility.NotEligible,
                resources = PeerResourceUiModel(
                    batteryClass = "Low",
                    storageClass = "Limited",
                    linkQuality = "Weak",
                    connectionStability = "Unstable",
                    reliability = "Unknown"
                ),
                lastEncounter = "First encounter",
                successfulConnections = 0
            )
        )
    }
}
