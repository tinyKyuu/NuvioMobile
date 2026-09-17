package com.nuvio.app.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal enum class NetworkPathEvent {
    Available,
    Unavailable,
}

internal expect object NetworkPathMonitor {
    fun events(): Flow<NetworkPathEvent>
}

internal class NetworkPathObservation(
    private val scope: CoroutineScope,
    private val events: () -> Flow<NetworkPathEvent>,
    private val onEvent: (NetworkPathEvent) -> Unit,
) {
    private var collectionJob: Job? = null

    val isActive: Boolean
        get() = collectionJob?.isActive == true

    fun start() {
        if (collectionJob?.isActive == true) return
        collectionJob = scope.launch {
            events().collect(onEvent)
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }
}
