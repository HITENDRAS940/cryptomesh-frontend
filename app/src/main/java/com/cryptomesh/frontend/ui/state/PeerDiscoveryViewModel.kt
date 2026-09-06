package com.cryptomesh.frontend.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cryptomesh.frontend.data.repository.DirectMeshRepository
import com.cryptomesh.frontend.data.repository.DirectPeer
import com.cryptomesh.frontend.data.repository.DirectPeerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PeerDiscoveryViewModel(
    private val directMeshRepository: DirectMeshRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(PeerDiscoveryUiState())
    val uiState: StateFlow<PeerDiscoveryUiState> = _uiState.asStateFlow()
    private var scanStarted = false

    init {
        directMeshRepository.start()
        viewModelScope.launch {
            directMeshRepository.state.collect { meshState ->
                _uiState.update { current ->
                    current.copy(
                        isScanning = meshState.isScanning,
                        hasScanned = scanStarted && !meshState.isScanning,
                        peers = meshState.peers.map(DirectPeer::toUiModel),
                        scanError = meshState.scanError,
                        transportUnavailableReason =
                            meshState.transportUnavailableReason,
                        selectedPeerId = current.selectedPeerId?.takeIf {
                            selected ->
                            meshState.peers.any { it.linkId == selected }
                        },
                        connectionRequestPeerId =
                            current.connectionRequestPeerId?.takeIf {
                                requested ->
                                meshState.peers.any {
                                    it.linkId == requested
                                }
                            }
                    )
                }
            }
        }
    }

    fun startScan() {
        scanStarted = true
        _uiState.update {
            it.copy(
                isScanning = true,
                hasScanned = false,
                scanError = null,
                selectedPeerId = null,
                connectionRequestPeerId = null
            )
        }
        directMeshRepository.startScan()
    }

    fun stopScan() {
        directMeshRepository.stopScan()
    }

    fun reportScanFailure(message: String) {
        _uiState.update {
            it.copy(
                isScanning = false,
                hasScanned = true,
                scanError = message
            )
        }
    }

    fun selectPeer(peerId: String) {
        if (_uiState.value.peers.any { it.id == peerId }) {
            _uiState.update { it.copy(selectedPeerId = peerId) }
        }
    }

    fun dismissPeerDetails() {
        _uiState.update { it.copy(selectedPeerId = null) }
    }

    fun requestConnection(peerId: String) {
        val peer = _uiState.value.peers.firstOrNull {
            it.id == peerId
        } ?: return
        if (peer.connectionStatus == PeerConnectionStatus.Connected) return
        _uiState.update { it.copy(connectionRequestPeerId = peerId) }
    }

    fun dismissConnectionRequest() {
        _uiState.update { it.copy(connectionRequestPeerId = null) }
    }

    fun confirmConnection() {
        val peerId = _uiState.value.connectionRequestPeerId ?: return
        _uiState.update { it.copy(connectionRequestPeerId = null) }
        directMeshRepository.connect(peerId)
    }

    fun retryConnection(peerId: String) {
        directMeshRepository.connect(peerId)
    }

    fun disconnect(peerId: String) {
        directMeshRepository.disconnect(peerId)
    }

    companion object {
        fun factory(
            repository: DirectMeshRepository
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>
                ): T = PeerDiscoveryViewModel(repository) as T
            }
        }
    }
}

private fun DirectPeer.toUiModel(): NearbyPeerUiModel {
    return NearbyPeerUiModel(
        id = linkId,
        displayName = displayName,
        deviceId = deviceId ?: advertisedDeviceId ?: "Pending verification",
        signalLabel = when {
            signalStrength >= -55 -> "Excellent"
            signalStrength >= -70 -> "Good"
            signalStrength >= -85 -> "Fair"
            else -> "Weak"
        },
        proximity = when {
            signalStrength >= -55 -> "Very close"
            signalStrength >= -70 -> "Nearby"
            signalStrength >= -85 -> "In range"
            else -> "At edge of range"
        },
        isVerified = isVerified,
        connectionStatus = when (status) {
            DirectPeerStatus.Discovered -> PeerConnectionStatus.Available
            DirectPeerStatus.Connecting -> PeerConnectionStatus.Connecting
            DirectPeerStatus.Authenticating ->
                PeerConnectionStatus.Authenticating
            DirectPeerStatus.Connected -> PeerConnectionStatus.Connected
            DirectPeerStatus.Failed -> PeerConnectionStatus.Failed
        },
        failureMessage = failureMessage
    )
}
