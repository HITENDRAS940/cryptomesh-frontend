package com.cryptomesh.frontend.ui.state

import com.cryptomesh.frontend.data.repository.DirectMeshState
import com.cryptomesh.frontend.data.repository.DirectPeer
import com.cryptomesh.frontend.data.repository.DirectPeerStatus
import com.cryptomesh.frontend.transport.TransportUnavailableReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeerDiscoveryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun betaStartsTransportWithoutSeededPeers() {
        val repository = FakeDirectMeshRepository()
        val viewModel = PeerDiscoveryViewModel(repository)

        assertEquals(1, repository.startCalls)
        assertTrue(viewModel.uiState.value.peers.isEmpty())
    }

    @Test
    fun liveRepositoryPeerMapsToVerifiedUiState() = runTest {
        val repository = FakeDirectMeshRepository()
        val viewModel = PeerDiscoveryViewModel(repository)

        repository.emit(
            DirectMeshState(
                peers = listOf(
                    DirectPeer(
                        linkId = "ble-link",
                        advertisedDeviceId = "CM-ABCDEF12",
                        deviceId = "CM-ABCDEF12",
                        displayName = "Physical Phone",
                        signalStrength = -52,
                        status = DirectPeerStatus.Connected,
                        isVerified = true
                    )
                )
            )
        )
        advanceUntilIdle()

        val peer = viewModel.uiState.value.peers.single()
        assertEquals("Physical Phone", peer.displayName)
        assertEquals(PeerConnectionStatus.Connected, peer.connectionStatus)
        assertTrue(peer.isVerified)
    }

    @Test
    fun bluetoothDisabledReasonIsExposedToTheScreen() = runTest {
        val repository = FakeDirectMeshRepository()
        val viewModel = PeerDiscoveryViewModel(repository)

        repository.emit(
            DirectMeshState(
                transportUnavailableReason =
                    TransportUnavailableReason.BluetoothDisabled,
                scanError = "Turn on Bluetooth."
            )
        )
        advanceUntilIdle()

        assertEquals(
            TransportUnavailableReason.BluetoothDisabled,
            viewModel.uiState.value.transportUnavailableReason
        )
        assertEquals(
            "Turn on Bluetooth.",
            viewModel.uiState.value.scanError
        )
    }

    @Test
    fun scanConnectAndDisconnectCommandsReachRepository() {
        val repository = FakeDirectMeshRepository(
            DirectMeshState(
                peers = listOf(
                    DirectPeer(
                        linkId = "ble-link",
                        advertisedDeviceId = "CM-ABCDEF12",
                        deviceId = null,
                        displayName = "CryptoMesh peer",
                        signalStrength = -70,
                        status = DirectPeerStatus.Discovered
                    )
                )
            )
        )
        val viewModel = PeerDiscoveryViewModel(repository)

        viewModel.startScan()
        viewModel.requestConnection("ble-link")
        viewModel.confirmConnection()
        viewModel.disconnect("ble-link")
        viewModel.stopScan()

        assertEquals(1, repository.scanCalls)
        assertEquals(listOf("ble-link"), repository.connectionRequests)
        assertEquals(listOf("ble-link"), repository.disconnectionRequests)
        assertEquals(1, repository.stopScanCalls)
        assertFalse(viewModel.uiState.value.peers.isEmpty())
    }
}
