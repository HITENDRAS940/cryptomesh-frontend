package com.cryptomesh.frontend.ui.state

import com.cryptomesh.frontend.data.repository.DirectMeshRepository
import com.cryptomesh.frontend.data.repository.DirectMeshState
import com.cryptomesh.frontend.protocol.MediaKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeDirectMeshRepository(
    initialState: DirectMeshState = DirectMeshState()
) : DirectMeshRepository {
    private val mutableState = MutableStateFlow(initialState)
    override val state: StateFlow<DirectMeshState> = mutableState

    var startCalls = 0
    var scanCalls = 0
    var stopScanCalls = 0
    val connectionRequests = mutableListOf<String>()
    val disconnectionRequests = mutableListOf<String>()
    val sentMessages = mutableListOf<Pair<String, String>>()
    var sendFailure: Throwable? = null

    fun emit(state: DirectMeshState) {
        mutableState.value = state
    }

    override fun start() {
        startCalls += 1
    }

    override fun startScan() {
        scanCalls += 1
    }

    override fun stopScan() {
        stopScanCalls += 1
    }

    override fun connect(linkId: String) {
        connectionRequests += linkId
    }

    override fun disconnect(linkId: String) {
        disconnectionRequests += linkId
    }

    override suspend fun sendText(
        peerDeviceId: String,
        text: String
    ): Result<String> {
        sentMessages += peerDeviceId to text
        return sendFailure?.let(Result.Companion::failure)
            ?: Result.success("packet-test")
    }

    override suspend fun sendMedia(
        peerDeviceId: String,
        mediaKind: MediaKind,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<String> {
        return sendFailure?.let(Result.Companion::failure)
            ?: Result.success("media-transfer-test")
    }
}
