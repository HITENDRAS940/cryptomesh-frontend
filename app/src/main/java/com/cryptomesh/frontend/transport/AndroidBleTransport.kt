package com.cryptomesh.frontend.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@SuppressLint("MissingPermission")
class AndroidBleTransport(
    context: Context,
    private val frameCodec: BleFrameCodec = BleFrameCodec(),
    private val reassembler: BleFrameReassembler = BleFrameReassembler()
) : NearbyTransport {
    private val applicationContext = context.applicationContext
    private val bluetoothManager =
        applicationContext.getSystemService(BluetoothManager::class.java)
    private val adapter
        get() = bluetoothManager?.adapter
    private val _events = MutableSharedFlow<NearbyTransportEvent>(
        extraBufferCapacity = EVENT_BUFFER_SIZE
    )
    override val events: SharedFlow<NearbyTransportEvent> =
        _events.asSharedFlow()

    private val discoveredDevices = mutableMapOf<String, BluetoothDevice>()
    private val clientChannels = mutableMapOf<String, ClientChannel>()
    private val serverDevices = mutableMapOf<String, BluetoothDevice>()
    private val serverQueues = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private val serverNotifying = mutableSetOf<String>()
    private val serverSubscribers = mutableSetOf<String>()

    private var localNode: LocalTransportNode? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private var isAdvertising = false
    private var isAdvertisingStarting = false
    private var isScanning = false

    override fun start(localNode: LocalTransportNode) {
        val isSameNode = this.localNode == localNode
        this.localNode = localNode
        if (!checkReady()) return
        if (isSameNode && gattServer != null && isAdvertising) {
            emitAvailability()
            return
        }
        openGattServer()
        startAdvertising()
    }

    override fun startScan() {
        if (!checkReady()) {
            emit(
                NearbyTransportEvent.ScanChanged(
                    scanning = false,
                    error = readinessError()
                )
            )
            return
        }
        if (localNode == null) {
            emit(
                NearbyTransportEvent.ScanChanged(
                    scanning = false,
                    error = "Create a local identity before scanning."
                )
            )
            return
        }
        if (gattServer == null || !isAdvertising) {
            openGattServer()
            startAdvertising()
        }
        if (isScanning) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            emit(
                NearbyTransportEvent.ScanChanged(
                    scanning = false,
                    error = "BLE scanning is unavailable on this device."
                )
            )
            return
        }
        runCatching {
            scanner.startScan(
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(SERVICE_UUID))
                        .build()
                ),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                scanCallback
            )
            isScanning = true
            emit(NearbyTransportEvent.ScanChanged(scanning = true))
        }.onFailure {
            emit(
                NearbyTransportEvent.ScanChanged(
                    scanning = false,
                    error = it.transportMessage("Unable to start BLE scan")
                )
            )
        }
    }

    override fun stopScan() {
        if (!isScanning) {
            emit(NearbyTransportEvent.ScanChanged(scanning = false))
            return
        }
        runCatching {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        isScanning = false
        emit(NearbyTransportEvent.ScanChanged(scanning = false))
    }

    override fun connect(linkId: String) {
        if (!checkReady()) return
        val device = synchronized(discoveredDevices) {
            discoveredDevices[linkId]
        }
        if (device == null) {
            emit(
                NearbyTransportEvent.LinkChanged(
                    linkId = linkId,
                    status = TransportLinkStatus.Failed,
                    message = "Peer is no longer in the current scan."
                )
            )
            return
        }
        emit(
            NearbyTransportEvent.LinkChanged(
                linkId,
                TransportLinkStatus.Connecting
            )
        )
        runCatching {
            val callback = createClientCallback(linkId)
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(
                    applicationContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                device.connectGatt(applicationContext, false, callback)
            }
            synchronized(clientChannels) {
                clientChannels[linkId] = ClientChannel(gatt = gatt)
            }
        }.onFailure {
            emitLinkFailure(linkId, it.transportMessage("BLE connection failed"))
        }
    }

    override fun disconnect(linkId: String) {
        val client = synchronized(clientChannels) {
            clientChannels.remove(linkId)
        }
        runCatching {
            client?.gatt?.disconnect()
            client?.gatt?.close()
        }
        val serverDevice = synchronized(serverDevices) {
            serverDevices.remove(linkId)
        }
        runCatching {
            if (serverDevice != null) {
                gattServer?.cancelConnection(serverDevice)
            }
        }
        clearLink(linkId)
        emit(
            NearbyTransportEvent.LinkChanged(
                linkId,
                TransportLinkStatus.Disconnected
            )
        )
    }

    override fun send(linkId: String, payload: ByteArray): Result<Unit> {
        return runCatching {
            val frames = frameCodec.chunk(payload)
            val client = synchronized(clientChannels) {
                clientChannels[linkId]
            }
            if (client?.ready == true) {
                synchronized(client) {
                    client.pendingWrites.addAll(frames)
                }
                writeNextClientFrame(linkId)
                return@runCatching
            }

            val serverReady = synchronized(serverSubscribers) {
                linkId in serverSubscribers
            }
            require(serverReady) {
                "Peer link is not ready for encrypted data."
            }
            val queue = synchronized(serverQueues) {
                serverQueues.getOrPut(linkId) { ArrayDeque() }
            }
            synchronized(queue) {
                queue.addAll(frames)
            }
            notifyNextServerFrame(linkId)
        }
    }

    override fun close() {
        stopScan()
        runCatching {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        }
        isAdvertising = false
        isAdvertisingStarting = false
        synchronized(clientChannels) {
            clientChannels.values.forEach {
                runCatching {
                    it.gatt.disconnect()
                    it.gatt.close()
                }
            }
            clientChannels.clear()
        }
        runCatching {
            gattServer?.clearServices()
            gattServer?.close()
        }
        gattServer = null
        synchronized(serverDevices) { serverDevices.clear() }
        synchronized(serverSubscribers) { serverSubscribers.clear() }
        synchronized(serverQueues) { serverQueues.clear() }
        serverNotifying.clear()
    }

    private fun checkReady(): Boolean {
        val failure = readinessFailure()
        if (failure == null) return true
        if (
            isScanning ||
            isAdvertising ||
            isAdvertisingStarting ||
            gattServer != null ||
            clientChannels.isNotEmpty()
        ) {
            close()
        }
        emit(
            NearbyTransportEvent.Availability(
                available = false,
                advertising = false,
                message = failure.message,
                unavailableReason = failure.reason
            )
        )
        return false
    }

    private fun readinessError(): String? {
        return readinessFailure()?.message
    }

    private fun readinessFailure(): ReadinessFailure? {
        if (!applicationContext.packageManager.hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE
            )
        ) {
            return ReadinessFailure(
                TransportUnavailableReason.Unsupported,
                "Bluetooth Low Energy is not supported on this device."
            )
        }
        if (adapter == null) {
            return ReadinessFailure(
                TransportUnavailableReason.Unavailable,
                "Bluetooth is not available on this device."
            )
        }
        if (adapter?.isEnabled != true) {
            return ReadinessFailure(
                TransportUnavailableReason.BluetoothDisabled,
                "Turn on Bluetooth to discover CryptoMesh peers."
            )
        }
        val missingPermission = requiredBluetoothPermissions().firstOrNull {
            ContextCompat.checkSelfPermission(applicationContext, it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missingPermission != null) {
            return ReadinessFailure(
                TransportUnavailableReason.PermissionRequired,
                "Nearby-device permission is required for BLE transport."
            )
        }
        return null
    }

    private fun openGattServer() {
        if (gattServer != null) return
        runCatching {
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            characteristic.addDescriptor(
                BluetoothGattDescriptor(
                    CLIENT_CONFIGURATION_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
            val service = BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            ).apply {
                addCharacteristic(characteristic)
            }
            val server = requireNotNull(
                bluetoothManager?.openGattServer(
                    applicationContext,
                    serverCallback
                )
            ) {
                "Unable to open the local BLE GATT server."
            }
            require(server.addService(service)) {
                "Unable to register the CryptoMesh BLE service."
            }
            serverCharacteristic = characteristic
            gattServer = server
        }.onFailure {
            emit(
                NearbyTransportEvent.Availability(
                    available = false,
                    advertising = false,
                    message = it.transportMessage(
                        "Unable to host the CryptoMesh BLE service"
                    ),
                    unavailableReason =
                        TransportUnavailableReason.TransportFailure
                )
            )
        }
    }

    private fun startAdvertising() {
        if (isAdvertising || isAdvertisingStarting) {
            emitAvailability()
            return
        }
        val node = localNode ?: return
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            emit(
                NearbyTransportEvent.Availability(
                    available = false,
                    advertising = false,
                    message = "BLE advertising is unavailable on this device.",
                    unavailableReason =
                        TransportUnavailableReason.TransportFailure
                )
            )
            return
        }
        runCatching {
            isAdvertisingStarting = true
            advertiser.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(
                        AdvertiseSettings.ADVERTISE_MODE_BALANCED
                    )
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(
                        AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
                    )
                    .build(),
                AdvertiseData.Builder()
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .setIncludeDeviceName(false)
                    .build(),
                AdvertiseData.Builder()
                    .addManufacturerData(
                        MANUFACTURER_ID,
                        node.deviceId.encodeToByteArray()
                    )
                    .build(),
                advertiseCallback
            )
        }.onFailure {
            isAdvertisingStarting = false
            emit(
                NearbyTransportEvent.Availability(
                    available = false,
                    advertising = false,
                    message = it.transportMessage(
                        "Unable to advertise this CryptoMesh device"
                    ),
                    unavailableReason =
                        TransportUnavailableReason.TransportFailure
                )
            )
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertisingStarting = false
            isAdvertising = true
            emitAvailability()
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertisingStarting = false
            isAdvertising = false
            emit(
                NearbyTransportEvent.Availability(
                    available = true,
                    advertising = false,
                    message = "BLE advertising failed (code $errorCode)."
                )
            )
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            emit(
                NearbyTransportEvent.ScanChanged(
                    scanning = false,
                    error = "BLE scan failed (code $errorCode)."
                )
            )
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val linkId = result.device.address
        val advertisedDeviceId = result.scanRecord
            ?.getManufacturerSpecificData(MANUFACTURER_ID)
            ?.decodeToString()
            ?.takeIf { it.startsWith("CM-") }
        if (advertisedDeviceId == localNode?.deviceId) return
        synchronized(discoveredDevices) {
            discoveredDevices[linkId] = result.device
        }
        emit(
            NearbyTransportEvent.PeerFound(
                DiscoveredTransportPeer(
                    linkId = linkId,
                    advertisedDeviceId = advertisedDeviceId,
                    signalStrength = result.rssi
                )
            )
        )
    }

    private fun createClientCallback(linkId: String): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        closeClient(linkId)
                        emitLinkFailure(
                            linkId,
                            "BLE connection failed (status $status)."
                        )
                    }

                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        gatt.requestConnectionPriority(
                            BluetoothGatt.CONNECTION_PRIORITY_HIGH
                        )
                        if (!gatt.discoverServices()) {
                            closeClient(linkId)
                            emitLinkFailure(
                                linkId,
                                "Unable to discover CryptoMesh BLE service."
                            )
                        }
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        closeClient(linkId)
                        clearLink(linkId)
                        emit(
                            NearbyTransportEvent.LinkChanged(
                                linkId,
                                TransportLinkStatus.Disconnected
                            )
                        )
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitLinkFailure(linkId, "BLE service discovery failed.")
                    return
                }
                val characteristic = gatt.getService(SERVICE_UUID)
                    ?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    emitLinkFailure(
                        linkId,
                        "Peer does not expose the CryptoMesh BLE service."
                    )
                    return
                }
                synchronized(clientChannels) {
                    clientChannels[linkId]?.characteristic = characteristic
                }
                if (!gatt.requestMtu(REQUESTED_MTU)) {
                    enableClientNotifications(linkId)
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {
                enableClientNotifications(linkId)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (
                    descriptor.uuid != CLIENT_CONFIGURATION_UUID ||
                    status != BluetoothGatt.GATT_SUCCESS
                ) {
                    emitLinkFailure(
                        linkId,
                        "Unable to enable encrypted BLE data notifications."
                    )
                    return
                }
                synchronized(clientChannels) {
                    clientChannels[linkId]?.ready = true
                }
                emit(
                    NearbyTransportEvent.LinkChanged(
                        linkId,
                        TransportLinkStatus.Connected
                    )
                )
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                synchronized(clientChannels) {
                    clientChannels[linkId]?.writing = false
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitLinkFailure(
                        linkId,
                        "Encrypted BLE frame write failed."
                    )
                    return
                }
                writeNextClientFrame(linkId)
            }

            @Deprecated("Deprecated by Android")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                characteristic.value?.let {
                    handleReceivedFrame(linkId, it)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleReceivedFrame(linkId, value)
            }
        }
    }

    private fun enableClientNotifications(linkId: String) {
        val channel = synchronized(clientChannels) {
            clientChannels[linkId]
        } ?: return
        val characteristic = channel.characteristic ?: return
        val descriptor = characteristic.getDescriptor(
            CLIENT_CONFIGURATION_UUID
        ) ?: run {
            emitLinkFailure(linkId, "Peer notification descriptor is missing.")
            return
        }
        if (!channel.gatt.setCharacteristicNotification(
                characteristic,
                true
            )
        ) {
            emitLinkFailure(linkId, "Unable to subscribe to peer data.")
            return
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            channel.gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            channel.gatt.writeDescriptor(descriptor)
        }
        if (!started) {
            emitLinkFailure(linkId, "Unable to configure peer data channel.")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            val linkId = device.address
            if (
                status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                synchronized(serverDevices) {
                    serverDevices[linkId] = device
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clearLink(linkId)
                emit(
                    NearbyTransportEvent.LinkChanged(
                        linkId,
                        TransportLinkStatus.Disconnected
                    )
                )
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val valid = characteristic.uuid == CHARACTERISTIC_UUID &&
                !preparedWrite &&
                offset == 0
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (valid) {
                        BluetoothGatt.GATT_SUCCESS
                    } else {
                        BluetoothGatt.GATT_INVALID_OFFSET
                    },
                    0,
                    null
                )
            }
            if (valid) {
                handleReceivedFrame(device.address, value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val enabled = descriptor.uuid == CLIENT_CONFIGURATION_UUID &&
                value.contentEquals(
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            if (enabled) {
                synchronized(serverSubscribers) {
                    serverSubscribers += device.address
                }
            } else {
                synchronized(serverSubscribers) {
                    serverSubscribers -= device.address
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
            if (enabled) {
                emit(
                    NearbyTransportEvent.LinkChanged(
                        device.address,
                        TransportLinkStatus.Connected
                    )
                )
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(serverNotifying) {
                serverNotifying -= device.address
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitLinkFailure(
                    device.address,
                    "Encrypted BLE notification failed."
                )
                return
            }
            notifyNextServerFrame(device.address)
        }
    }

    private fun writeNextClientFrame(linkId: String) {
        val channel = synchronized(clientChannels) {
            clientChannels[linkId]
        } ?: return
        val frame = synchronized(channel) {
            if (channel.writing) return
            channel.pendingWrites.pollFirst()?.also {
                channel.writing = true
            }
        } ?: return
        val characteristic = channel.characteristic ?: run {
            synchronized(channel) { channel.writing = false }
            emitLinkFailure(linkId, "BLE data characteristic is unavailable.")
            return
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            channel.gatt.writeCharacteristic(
                characteristic,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            channel.gatt.writeCharacteristic(characteristic)
        }
        if (!started) {
            synchronized(channel) { channel.writing = false }
            emitLinkFailure(linkId, "BLE data channel is busy.")
        }
    }

    private fun notifyNextServerFrame(linkId: String) {
        synchronized(serverNotifying) {
            if (linkId in serverNotifying) return
            serverNotifying += linkId
        }
        val device = synchronized(serverDevices) {
            serverDevices[linkId]
        }
        val characteristic = serverCharacteristic
        val queue = synchronized(serverQueues) {
            serverQueues[linkId]
        }
        val frame = if (queue == null) {
            null
        } else {
            synchronized(queue) { queue.pollFirst() }
        }
        if (device == null || characteristic == null || frame == null) {
            synchronized(serverNotifying) { serverNotifying -= linkId }
            return
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer?.notifyCharacteristicChanged(
                device,
                characteristic,
                false,
                frame
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            gattServer?.notifyCharacteristicChanged(
                device,
                characteristic,
                false
            ) == true
        }
        if (!started) {
            synchronized(serverNotifying) { serverNotifying -= linkId }
            emitLinkFailure(linkId, "BLE notification channel is busy.")
        }
    }

    private fun handleReceivedFrame(linkId: String, frame: ByteArray) {
        runCatching {
            reassembler.accept(linkId, frame)
        }.onSuccess { payload ->
            if (payload != null) {
                emit(
                    NearbyTransportEvent.PayloadReceived(
                        linkId = linkId,
                        payload = payload
                    )
                )
            }
        }.onFailure {
            emitLinkFailure(
                linkId,
                it.transportMessage("Invalid BLE data frame")
            )
        }
    }

    private fun closeClient(linkId: String) {
        synchronized(clientChannels) {
            clientChannels.remove(linkId)
        }?.let {
            runCatching { it.gatt.close() }
        }
    }

    private fun clearLink(linkId: String) {
        synchronized(serverDevices) { serverDevices.remove(linkId) }
        synchronized(serverSubscribers) { serverSubscribers.remove(linkId) }
        synchronized(serverQueues) { serverQueues.remove(linkId) }
        synchronized(serverNotifying) { serverNotifying.remove(linkId) }
        reassembler.clear(linkId)
    }

    private fun emitAvailability() {
        emit(
            NearbyTransportEvent.Availability(
                available = true,
                advertising = isAdvertising
            )
        )
    }

    private fun emitLinkFailure(linkId: String, message: String) {
        emit(
            NearbyTransportEvent.LinkChanged(
                linkId = linkId,
                status = TransportLinkStatus.Failed,
                message = message
            )
        )
    }

    private fun emit(event: NearbyTransportEvent) {
        _events.tryEmit(event)
    }

    private data class ClientChannel(
        val gatt: BluetoothGatt,
        var characteristic: BluetoothGattCharacteristic? = null,
        var ready: Boolean = false,
        var writing: Boolean = false,
        val pendingWrites: ArrayDeque<ByteArray> = ArrayDeque()
    )

    private data class ReadinessFailure(
        val reason: TransportUnavailableReason,
        val message: String
    )

    companion object {
        val SERVICE_UUID: UUID =
            UUID.fromString("7dd65a20-18ed-4bb7-84df-34d447b008a1")
        val CHARACTERISTIC_UUID: UUID =
            UUID.fromString("be07bf18-5306-4d9a-9b44-26875e2cc605")
        val CLIENT_CONFIGURATION_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MANUFACTURER_ID = 0x0C4D
        private const val REQUESTED_MTU = 517
        private const val EVENT_BUFFER_SIZE = 128
    }
}

private fun Throwable.transportMessage(prefix: String): String {
    return message?.takeIf(String::isNotBlank)?.let { "$prefix: $it" }
        ?: "$prefix."
}
