package com.cryptomesh.frontend.transport

import kotlinx.coroutines.flow.SharedFlow

data class LocalTransportNode(
    val deviceId: String
)

data class DiscoveredTransportPeer(
    val linkId: String,
    val advertisedDeviceId: String?,
    val signalStrength: Int
)

enum class TransportLinkStatus {
    Connecting,
    Connected,
    Disconnected,
    Failed
}

enum class TransportUnavailableReason {
    BluetoothDisabled,
    PermissionRequired,
    Unsupported,
    Unavailable,
    TransportFailure
}

sealed interface NearbyTransportEvent {
    data class Availability(
        val available: Boolean,
        val advertising: Boolean,
        val message: String? = null,
        val unavailableReason: TransportUnavailableReason? = null
    ) : NearbyTransportEvent

    data class ScanChanged(
        val scanning: Boolean,
        val error: String? = null
    ) : NearbyTransportEvent

    data class PeerFound(
        val peer: DiscoveredTransportPeer
    ) : NearbyTransportEvent

    data class LinkChanged(
        val linkId: String,
        val status: TransportLinkStatus,
        val message: String? = null
    ) : NearbyTransportEvent

    data class PayloadReceived(
        val linkId: String,
        val payload: ByteArray
    ) : NearbyTransportEvent
}

interface NearbyTransport {
    val events: SharedFlow<NearbyTransportEvent>

    fun start(localNode: LocalTransportNode)

    fun startScan()

    fun stopScan()

    fun connect(linkId: String)

    fun disconnect(linkId: String)

    fun send(linkId: String, payload: ByteArray): Result<Unit>

    fun close()
}
