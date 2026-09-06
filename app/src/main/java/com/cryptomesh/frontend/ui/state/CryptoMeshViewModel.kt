package com.cryptomesh.frontend.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cryptomesh.frontend.data.repository.IdentityRepository
import com.cryptomesh.frontend.data.repository.MediaTransferRepository
import com.cryptomesh.frontend.data.repository.SecurePacketRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CryptoMeshViewModel(
    private val identityRepository: IdentityRepository,
    private val packetRepository: SecurePacketRepository,
    private val mediaTransferRepository: MediaTransferRepository
) : ViewModel() {
    private val _identity = MutableStateFlow<LocalIdentity?>(null)
    val identity: StateFlow<LocalIdentity?> = _identity.asStateFlow()

    private val _isIdentityLoaded = MutableStateFlow(false)
    val isIdentityLoaded: StateFlow<Boolean> =
        _isIdentityLoaded.asStateFlow()

    private val _identityError = MutableStateFlow<String?>(null)
    val identityError: StateFlow<String?> = _identityError.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                identityRepository.identity.collect { storedIdentity ->
                    _identity.value = storedIdentity
                    _isIdentityLoaded.value = true
                }
            }.onFailure {
                _identityError.value = "Unable to load the local identity."
                _isIdentityLoaded.value = true
            }
        }
    }

    fun createIdentity(
        displayName: String,
        onCreated: () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                identityRepository.createIdentity(displayName)
            }.onSuccess { createdIdentity ->
                _identity.value = createdIdentity
                _identityError.value = null
                onCreated()
            }.onFailure {
                _identityError.value =
                    "Secure identity generation failed. Try again."
            }
        }
    }

    fun resetIdentity(onReset: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                mediaTransferRepository.clearAll()
                packetRepository.clearAll()
                identityRepository.clearIdentity()
            }.onSuccess {
                _identity.value = null
                _identityError.value = null
                onReset()
            }.onFailure {
                _identityError.value =
                    "The local identity could not be reset."
            }
        }
    }

    fun dismissIdentityError() {
        _identityError.value = null
    }

    companion object {
        fun factory(
            identityRepository: IdentityRepository,
            packetRepository: SecurePacketRepository,
            mediaTransferRepository: MediaTransferRepository
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>
                ): T {
                    require(
                        modelClass.isAssignableFrom(
                            CryptoMeshViewModel::class.java
                        )
                    )
                    return CryptoMeshViewModel(
                        identityRepository,
                        packetRepository,
                        mediaTransferRepository
                    ) as T
                }
            }
        }
    }
}
