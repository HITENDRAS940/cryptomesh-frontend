package com.cryptomesh.frontend.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerDiscoveryViewModelTest {
    @Test
    fun completingScanReturnsPrivacySafePeerSummaries() {
        val viewModel = PeerDiscoveryViewModel()

        viewModel.startScan()
        assertTrue(viewModel.uiState.value.isScanning)

        viewModel.completeScan()

        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
        assertTrue(state.hasScanned)
        assertEquals(3, state.peers.size)
        assertEquals("Balanced", state.peers.first().resources.batteryClass)
        assertEquals(RelayEligibility.DirectDestination, state.peers.first().relayEligibility)
    }

    @Test
    fun verifiedPeerCanConnect() {
        val viewModel = scannedViewModel()
        val peerId = viewModel.uiState.value.peers.first().id

        viewModel.requestConnection(peerId)
        viewModel.confirmConnection()
        assertEquals(
            PeerConnectionStatus.Connecting,
            viewModel.uiState.value.peers.first().connectionStatus
        )

        viewModel.completeConnection(peerId)

        assertEquals(
            PeerConnectionStatus.Connected,
            viewModel.uiState.value.peers.first().connectionStatus
        )
    }

    @Test
    fun unavailablePeerShowsRetryableFailure() {
        val viewModel = scannedViewModel()
        val peer = viewModel.uiState.value.peers.last()

        viewModel.requestConnection(peer.id)
        viewModel.confirmConnection()
        viewModel.completeConnection(peer.id)

        val updatedPeer = viewModel.uiState.value.peers.last()
        assertEquals(PeerConnectionStatus.Failed, updatedPeer.connectionStatus)
        assertTrue(updatedPeer.failureMessage?.contains("out of range") == true)
    }

    @Test
    fun stoppingScanProducesEmptyCompletedState() {
        val viewModel = PeerDiscoveryViewModel()

        viewModel.startScan()
        viewModel.stopScan()

        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
        assertTrue(state.hasScanned)
        assertTrue(state.peers.isEmpty())
    }

    private fun scannedViewModel(): PeerDiscoveryViewModel {
        return PeerDiscoveryViewModel().also {
            it.startScan()
            it.completeScan()
        }
    }
}
