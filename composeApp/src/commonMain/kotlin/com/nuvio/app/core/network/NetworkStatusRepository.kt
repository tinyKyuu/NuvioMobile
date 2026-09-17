package com.nuvio.app.core.network

import co.touchlab.kermit.Logger
import androidx.compose.runtime.Composable
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_check_connection
import nuvio.composeapp.generated.resources.details_servers_unreachable
import nuvio.composeapp.generated.resources.network_cannot_reach_servers
import nuvio.composeapp.generated.resources.network_connection_issue
import nuvio.composeapp.generated.resources.network_no_internet_connection
import nuvio.composeapp.generated.resources.network_please_check_connection
import org.jetbrains.compose.resources.stringResource

enum class NetworkCondition {
    Unknown,
    Checking,
    Online,
    NoInternet,
    ServersUnreachable,
}

data class NetworkStatusUiState(
    val condition: NetworkCondition = NetworkCondition.Unknown,
    val probeGeneration: Long = 0L,
    val isProbing: Boolean = false,
) {
    val isOnline: Boolean
        get() = condition == NetworkCondition.Online

    val isOfflineLike: Boolean
        get() = condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable
}

@Composable
fun NetworkCondition.titleForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> stringResource(Res.string.network_cannot_reach_servers)
        NetworkCondition.NoInternet -> stringResource(Res.string.network_no_internet_connection)
        else -> stringResource(Res.string.network_connection_issue)
    }

@Composable
fun NetworkCondition.messageForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> stringResource(Res.string.details_servers_unreachable)
        NetworkCondition.NoInternet -> stringResource(Res.string.details_check_connection)
        else -> stringResource(Res.string.network_please_check_connection)
    }

object NetworkStatusRepository {
    private val log = Logger.withTag("NetworkStatus")
    private const val REQUEST_TIMEOUT_MS = 4_500L
    private const val FOREGROUND_REFRESH_DELAY_MS = 6_000L
    private const val FOREGROUND_FAILURE_CONFIRM_DELAY_MS = 2_000L
    private const val PUBLIC_PROBE_PRIMARY = "https://www.gstatic.com/generate_204"
    private const val PUBLIC_PROBE_FALLBACK = "https://cloudflare.com/cdn-cgi/trace"

    private val controller = NetworkStatusController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        probeCondition = ::probeCondition,
        onProbeResult = { generation, condition ->
            log.d { "Probe generation=$generation condition=$condition" }
        },
        foregroundRefreshDelayMs = FOREGROUND_REFRESH_DELAY_MS,
        failureConfirmDelayMs = FOREGROUND_FAILURE_CONFIRM_DELAY_MS,
    )
    val uiState: StateFlow<NetworkStatusUiState> = controller.uiState

    fun ensureStarted() = controller.ensureStarted()

    internal fun onAppVisibility(visibility: AppVisibility) = controller.onAppVisibility(visibility)

    internal fun onNetworkPathEvent(event: NetworkPathEvent) = controller.onNetworkPathEvent(event)

    fun requestRefresh(force: Boolean = false, confirmFailures: Boolean = false): Long =
        controller.requestRefresh(force = force, confirmFailures = confirmFailures)

    private suspend fun probeCondition(): NetworkCondition {
        val internetReachable = probePublicInternet()
        if (!internetReachable) {
            return NetworkCondition.NoInternet
        }

        val supabaseReachable = SupabaseEndpointConfig.restEndpointUrls().any { url ->
            probeReachable(
                url = url,
                headers = mapOf("apikey" to ServerConfigurationRepository.active.value.publishableKey),
            )
        }
        if (!supabaseReachable) {
            return NetworkCondition.ServersUnreachable
        }

        return NetworkCondition.Online
    }

    private suspend fun probePublicInternet(): Boolean =
        probeReachable(PUBLIC_PROBE_PRIMARY) || probeReachable(PUBLIC_PROBE_FALLBACK)

    private suspend fun probeReachable(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Boolean {
        val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = headers,
                    body = "",
                )
            }.getOrNull()
        } ?: return false

        return response.status in 100..599
    }

}

internal class NetworkStatusController(
    private val scope: CoroutineScope,
    private val probeCondition: suspend () -> NetworkCondition,
    private val onProbeResult: (Long, NetworkCondition) -> Unit = { _, _ -> },
    private val delayFor: suspend (Long) -> Unit = { delay(it) },
    private val foregroundRefreshDelayMs: Long = 6_000L,
    private val failureConfirmDelayMs: Long = 2_000L,
    private val pathLossDebounceMs: Long = 1_000L,
    private val serverRetryDelaysMs: List<Long> = listOf(5_000L, 15_000L, 30_000L),
) {
    private val lock = SynchronizedObject()
    private val _uiState = MutableStateFlow(NetworkStatusUiState())
    val uiState: StateFlow<NetworkStatusUiState> = _uiState.asStateFlow()

    private var started = false
    private var foreground = true
    private var latestProbeGeneration = 0L
    private var activeProbeGeneration: Long? = null
    private var foregroundRefreshJob: Job? = null
    private var pathLossJob: Job? = null
    private var serverRetryJob: Job? = null
    private var serverRetryIndex = 0

    fun ensureStarted() {
        val shouldStart = synchronized(lock) {
            if (started) false else true.also { started = true }
        }
        if (shouldStart) requestRefresh(force = true)
    }

    fun onAppVisibility(visibility: AppVisibility) {
        val isForeground = visibility == AppVisibility.Foreground
        synchronized(lock) { foreground = isForeground }
        foregroundRefreshJob?.cancel()
        pathLossJob?.cancel()
        if (!isForeground) {
            serverRetryJob?.cancel()
            return
        }
        ensureStarted()
        foregroundRefreshJob = scope.launch {
            delayFor(foregroundRefreshDelayMs)
            requestRefresh(force = true, confirmFailures = true)
        }
        if (_uiState.value.condition == NetworkCondition.ServersUnreachable) {
            scheduleServerRetry()
        }
    }

    fun onNetworkPathEvent(event: NetworkPathEvent) {
        if (!synchronized(lock) { foreground }) return
        when (event) {
            NetworkPathEvent.Available -> {
                pathLossJob?.cancel()
                if (_uiState.value.isOfflineLike) requestRefresh(force = true)
            }
            NetworkPathEvent.Unavailable -> {
                if (_uiState.value.condition != NetworkCondition.Online) return
                pathLossJob?.cancel()
                pathLossJob = scope.launch {
                    delayFor(pathLossDebounceMs)
                    if (synchronized(lock) { foreground }) {
                        requestRefresh(force = true, confirmFailures = true)
                    }
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun requestRefresh(force: Boolean = false, confirmFailures: Boolean = false): Long {
        ensureMarkedStarted()
        val generation = synchronized(lock) {
            activeProbeGeneration?.let { return it }
            latestProbeGeneration += 1L
            latestProbeGeneration.also { activeProbeGeneration = it }
        }
        serverRetryJob?.cancel()
        _uiState.value = _uiState.value.copy(
            condition = if (_uiState.value.condition == NetworkCondition.Unknown) {
                NetworkCondition.Checking
            } else {
                _uiState.value.condition
            },
            isProbing = true,
        )
        scope.launch {
            var result: NetworkCondition? = null
            try {
                val previous = _uiState.value.condition
                var next = probeCondition()
                if (confirmFailures && previous == NetworkCondition.Online && next.isOfflineLike()) {
                    delayFor(failureConfirmDelayMs)
                    next = probeCondition()
                }
                result = next
                _uiState.value = NetworkStatusUiState(
                    condition = next,
                    probeGeneration = generation,
                    isProbing = false,
                )
                onProbeResult(generation, next)
            } finally {
                synchronized(lock) {
                    if (activeProbeGeneration == generation) activeProbeGeneration = null
                }
                if (_uiState.value.probeGeneration != generation) {
                    _uiState.value = _uiState.value.copy(isProbing = false)
                }
                when (result) {
                    NetworkCondition.ServersUnreachable -> scheduleServerRetry()
                    NetworkCondition.Online,
                    NetworkCondition.NoInternet,
                    -> {
                        serverRetryIndex = 0
                        serverRetryJob?.cancel()
                    }
                    else -> Unit
                }
            }
        }
        return generation
    }

    private fun ensureMarkedStarted() {
        synchronized(lock) { started = true }
    }

    private fun scheduleServerRetry() {
        if (!synchronized(lock) { foreground }) return
        val delayMs = serverRetryDelaysMs.getOrNull(serverRetryIndex) ?: return
        serverRetryIndex += 1
        serverRetryJob?.cancel()
        serverRetryJob = scope.launch {
            delayFor(delayMs)
            if (synchronized(lock) { foreground } && _uiState.value.condition == NetworkCondition.ServersUnreachable) {
                serverRetryJob = null
                requestRefresh(force = true)
            }
        }
    }

    private fun NetworkCondition.isOfflineLike(): Boolean =
        this == NetworkCondition.NoInternet || this == NetworkCondition.ServersUnreachable
}
