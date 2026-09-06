package com.cryptomesh.frontend.notification

import com.cryptomesh.frontend.data.repository.DirectMeshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

fun interface MeshNotifier {
    fun show(event: MeshNotificationEvent)
}

class MeshNotificationCoordinator(
    private val repository: DirectMeshRepository,
    private val notifier: MeshNotifier,
    private val scope: CoroutineScope,
    private val detector: MeshNotificationEventDetector =
        MeshNotificationEventDetector()
) {
    private var collectionJob: Job? = null

    fun start() {
        if (collectionJob?.isActive == true) return
        val initialState = repository.state.value
        collectionJob = scope.launch {
            var previousState = initialState
            repository.state.collect { currentState ->
                if (currentState != previousState) {
                    detector.detect(previousState, currentState)
                        .forEach(notifier::show)
                    previousState = currentState
                }
            }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }
}
