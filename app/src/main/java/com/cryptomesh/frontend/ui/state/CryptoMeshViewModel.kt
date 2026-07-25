package com.cryptomesh.frontend.ui.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class CryptoMeshViewModel : ViewModel() {
    private val _identity = MutableStateFlow<LocalIdentity?>(null)
    val identity: StateFlow<LocalIdentity?> = _identity.asStateFlow()

    fun createIdentity(displayName: String) {
        val cleanName = displayName.trim().ifBlank { "CryptoMesh User" }
        val deviceId = "CM-${UUID.randomUUID().toString().take(8).uppercase()}"
        val publicKeyPreview = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .chunked(4)
            .take(4)
            .joinToString(":")
            .uppercase()

        _identity.value = LocalIdentity(
            displayName = cleanName,
            deviceId = deviceId,
            publicKeyPreview = publicKeyPreview
        )
    }

    fun resetIdentity() {
        _identity.value = null
    }
}
