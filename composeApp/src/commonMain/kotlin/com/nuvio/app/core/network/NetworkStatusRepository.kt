package com.nuvio.app.core.network

import co.touchlab.kermit.Logger
import androidx.compose.runtime.Composable
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlin.time.TimeSource

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
    val keepOfflinePresentation: Boolean = false,
) {
    val isOnline: Boolean
        get() = condition == NetworkCondition.Online

    val isOfflineLike: Boolean
        get() = condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable

    val usesOfflinePresentation: Boolean
        get() = isOfflineLike || keepOfflinePresentation
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
    private const val RECONNECT_TIMEOUT_MS = 15_000L
    private const val RECONNECT_MAX_ATTEMPTS = 3
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
        reconnectTimeoutMs = RECONNECT_TIMEOUT_MS,
        reconnectMaxAttempts = RECONNECT_MAX_ATTEMPTS,
    )
    val uiState: StateFlow<NetworkStatusUiState> = controller.uiState

    fun ensureStarted() = controller.ensureStarted()

    internal fun onAppVisibility(visibility: AppVisibility) = controller.onAppVisibility(visibility)

    internal fun onNetworkPathEvent(event: NetworkPathEvent) = controller.onNetworkPathEvent(event)

    fun requestRefresh(force: Boolean = false, confirmFailures: Boolean = false): Long =
        controller.requestRefresh(force = force, confirmFailures = confirmFailures)

    internal fun requestReconnect(): Long = controller.requestReconnect()

    internal fun cancelReconnect() = controller.cancelReconnect()

    internal fun onRecoveryCompleted() = controller.onRecoveryCompleted()

    internal fun clearOfflinePresentationHold() = controller.clearOfflinePresentationHold()

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

private val networkStatusTimeOrigin = TimeSource.Monotonic.markNow()

private fun networkStatusMonotonicMs(): Long =
    networkStatusTimeOrigin.elapsedNow().inWholeMilliseconds

internal class NetworkStatusController(
    private val scope: CoroutineScope,
    private val probeCondition: suspend () -> NetworkCondition,
    private val onProbeResult: (Long, NetworkCondition) -> Unit = { _, _ -> },
    private val delayFor: suspend (Long) -> Unit = { delay(it) },
    private val probeWithinTimeout: suspend (Long) -> NetworkCondition? = { timeoutMs ->
        withTimeoutOrNull(timeoutMs) { probeCondition() }
    },
    private val nowMs: () -> Long = ::networkStatusMonotonicMs,
    private val foregroundRefreshDelayMs: Long = 6_000L,
    private val failureConfirmDelayMs: Long = 2_000L,
    private val pathLossDebounceMs: Long = 1_000L,
    private val serverRetryDelaysMs: List<Long> = listOf(5_000L, 15_000L, 30_000L),
    private val reconnectRetryDelaysMs: List<Long> = listOf(2_000L, 4_000L),
    private val reconnectTimeoutMs: Long = 15_000L,
    private val reconnectMaxAttempts: Int = 3,
) {
    private enum class ProbeKind {
        Standard,
        Reconnect,
    }

    private data class ProbeRequest(
        val generation: Long,
        val confirmFailures: Boolean,
        val kind: ProbeKind = ProbeKind.Standard,
    )

    private val lock = SynchronizedObject()
    private val _uiState = MutableStateFlow(NetworkStatusUiState())
    val uiState: StateFlow<NetworkStatusUiState> = _uiState.asStateFlow()

    private var started = false
    private var foreground = true
    private var lastNetworkPathEvent: NetworkPathEvent? = null
    private var latestProbeGeneration = 0L
    private var activeProbeRequest: ProbeRequest? = null
    private var pendingProbeRequest: ProbeRequest? = null
    private var activeProbeJob: Job? = null
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
            cancelReconnect()
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
        val isNewPathState = synchronized(lock) {
            (lastNetworkPathEvent != event).also { changed ->
                if (changed) lastNetworkPathEvent = event
            }
        }
        if (!isNewPathState) return
        when (event) {
            NetworkPathEvent.Available -> {
                pathLossJob?.cancel()
                if (_uiState.value.isOfflineLike && !hasReconnectRequest()) {
                    requestRefresh(force = true)
                }
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

    fun requestRefresh(force: Boolean = false, confirmFailures: Boolean = false): Long {
        ensureMarkedStarted()
        var shouldLaunch = false
        val request = synchronized(lock) {
            activeProbeRequest?.let { active ->
                if (active.kind == ProbeKind.Reconnect || !force) return active.generation
                pendingProbeRequest?.let { pending ->
                    if (pending.kind == ProbeKind.Reconnect) return pending.generation
                    if (confirmFailures && !pending.confirmFailures) {
                        pendingProbeRequest = pending.copy(confirmFailures = true)
                    }
                    return pending.generation
                }
                latestProbeGeneration += 1L
                return@synchronized ProbeRequest(
                    generation = latestProbeGeneration,
                    confirmFailures = confirmFailures,
                ).also { pendingProbeRequest = it }
            }
            latestProbeGeneration += 1L
            ProbeRequest(
                generation = latestProbeGeneration,
                confirmFailures = confirmFailures,
            ).also {
                activeProbeRequest = it
                shouldLaunch = true
            }
        }
        if (shouldLaunch) launchProbe(request)
        return request.generation
    }

    fun requestReconnect(): Long {
        ensureMarkedStarted()
        var shouldLaunch = false
        val request = synchronized(lock) {
            activeProbeRequest?.takeIf { it.kind == ProbeKind.Reconnect }?.let { return it.generation }
            pendingProbeRequest?.let { pending ->
                if (pending.kind == ProbeKind.Reconnect) return pending.generation
                return@synchronized pending.copy(
                    confirmFailures = false,
                    kind = ProbeKind.Reconnect,
                ).also { pendingProbeRequest = it }
            }

            latestProbeGeneration += 1L
            ProbeRequest(
                generation = latestProbeGeneration,
                confirmFailures = false,
                kind = ProbeKind.Reconnect,
            ).also { reconnect ->
                if (activeProbeRequest == null) {
                    activeProbeRequest = reconnect
                    shouldLaunch = true
                } else {
                    pendingProbeRequest = reconnect
                }
            }
        }
        if (shouldLaunch) launchProbe(request)
        return request.generation
    }

    fun cancelReconnect() {
        var jobToCancel: Job? = null
        var cancelledGeneration: Long? = null
        synchronized(lock) {
            if (pendingProbeRequest?.kind == ProbeKind.Reconnect) {
                pendingProbeRequest = null
            }
            val active = activeProbeRequest
            if (active?.kind == ProbeKind.Reconnect) {
                cancelledGeneration = active.generation
                activeProbeRequest = null
                jobToCancel = activeProbeJob
                activeProbeJob = null
            }
        }
        jobToCancel?.cancel()
        cancelledGeneration?.let { generation ->
            _uiState.value = _uiState.value.copy(
                probeGeneration = generation,
                isProbing = false,
            )
        }
    }

    fun onRecoveryCompleted() {
        if (_uiState.value.condition == NetworkCondition.Online) {
            _uiState.value = _uiState.value.copy(keepOfflinePresentation = false)
        }
    }

    fun clearOfflinePresentationHold() {
        _uiState.value = _uiState.value.copy(keepOfflinePresentation = false)
    }

    private fun launchProbe(request: ProbeRequest) {
        val generation = request.generation
        serverRetryJob?.cancel()
        _uiState.value = _uiState.value.copy(
            condition = if (_uiState.value.condition == NetworkCondition.Unknown) {
                NetworkCondition.Checking
            } else {
                _uiState.value.condition
            },
            isProbing = true,
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var result: NetworkCondition? = null
            try {
                result = when (request.kind) {
                    ProbeKind.Standard -> runStandardProbe(request)
                    ProbeKind.Reconnect -> runReconnectSession()
                }
                val next = requireNotNull(result)
                _uiState.value = NetworkStatusUiState(
                    condition = next,
                    probeGeneration = generation,
                    isProbing = false,
                    keepOfflinePresentation = next.isOfflineLike() ||
                        (next == NetworkCondition.Online && _uiState.value.keepOfflinePresentation),
                )
                onProbeResult(generation, next)
            } finally {
                val nextRequest = synchronized(lock) {
                    if (activeProbeRequest?.generation != generation) {
                        null
                    } else {
                        pendingProbeRequest.also { pending ->
                            pendingProbeRequest = null
                            activeProbeRequest = pending
                            activeProbeJob = null
                        }
                    }
                }
                if (_uiState.value.probeGeneration != generation) {
                    _uiState.value = _uiState.value.copy(isProbing = false)
                }
                if (nextRequest != null) {
                    launchProbe(nextRequest)
                } else {
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
        }
        val shouldStart = synchronized(lock) {
            if (activeProbeRequest?.generation == generation) {
                activeProbeJob = job
                true
            } else {
                false
            }
        }
        if (shouldStart) job.start() else job.cancel()
    }

    private suspend fun runStandardProbe(request: ProbeRequest): NetworkCondition {
        val previous = _uiState.value.condition
        var next = probeCondition()
        if (request.confirmFailures && previous == NetworkCondition.Online && next.isOfflineLike()) {
            delayFor(failureConfirmDelayMs)
            next = probeCondition()
        }
        return next
    }

    private suspend fun runReconnectSession(): NetworkCondition {
        val startedAtMs = nowMs()
        var attempts = 0
        var lastFailure: NetworkCondition? = null

        while (attempts < reconnectMaxAttempts) {
            val remainingMs = reconnectTimeoutMs - (nowMs() - startedAtMs)
            if (remainingMs <= 0L) break

            val next = probeWithinTimeout(remainingMs) ?: break
            attempts += 1
            if (next == NetworkCondition.Online) return next
            lastFailure = next

            val retryDelayMs = reconnectRetryDelaysMs.getOrNull(attempts - 1) ?: break
            val remainingAfterProbeMs = reconnectTimeoutMs - (nowMs() - startedAtMs)
            if (remainingAfterProbeMs <= 0L) break
            delayFor(minOf(retryDelayMs, remainingAfterProbeMs))
        }

        return lastFailure
            ?: _uiState.value.condition.takeIf { it.isOfflineLike() }
            ?: NetworkCondition.NoInternet
    }

    private fun ensureMarkedStarted() {
        synchronized(lock) { started = true }
    }

    private fun hasReconnectRequest(): Boolean = synchronized(lock) {
        activeProbeRequest?.kind == ProbeKind.Reconnect || pendingProbeRequest?.kind == ProbeKind.Reconnect
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
