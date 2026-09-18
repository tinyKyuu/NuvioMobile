package com.nuvio.app.core.network

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.ManifestRecoveryEvent
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.catalog.CatalogRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.downloads.OfflineLibraryRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.search.SearchRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class NetworkRecoveryPhase {
    Idle,
    RestoringAddons,
    RefreshingCatalogs,
    Completed,
    Failed,
}

enum class NetworkRecoveryTrigger {
    Reconnect,
    Retry,
    ManualRefresh,
}

data class NetworkRecoveryUiState(
    val profileId: Int? = null,
    val generation: Long = 0L,
    val phase: NetworkRecoveryPhase = NetworkRecoveryPhase.Idle,
    val trigger: NetworkRecoveryTrigger? = null,
    val failedManifestCount: Int = 0,
) {
    val isRecovering: Boolean
        get() = phase == NetworkRecoveryPhase.RestoringAddons || phase == NetworkRecoveryPhase.RefreshingCatalogs

    internal fun observableGeneration(profileId: Int): Long? =
        generation.takeIf { this.profileId == profileId && phase != NetworkRecoveryPhase.Idle }
}

internal class BackOnlineToastTracker(initialGeneration: Long) {
    private var lastTerminalGeneration = initialGeneration
    private var offlinePending = false

    fun onNetworkCondition(condition: NetworkCondition) {
        if (condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable) {
            offlinePending = true
        }
    }

    fun onRecoveryState(state: NetworkRecoveryUiState, isOnline: Boolean = true): Boolean {
        if (state.phase != NetworkRecoveryPhase.Completed && state.phase != NetworkRecoveryPhase.Failed) {
            return false
        }
        if (state.generation <= lastTerminalGeneration) return false
        lastTerminalGeneration = state.generation
        if (state.phase != NetworkRecoveryPhase.Completed || !offlinePending || !isOnline) return false
        offlinePending = false
        return true
    }
}

internal data class ManifestRecoveryOutcome(
    val attemptedUrls: Set<String> = emptySet(),
    val recoveredUrls: Set<String> = emptySet(),
    val failedUrls: Set<String> = emptySet(),
    val stale: Boolean = false,
    val changedUrls: Set<String> = emptySet(),
)

internal interface NetworkRecoveryOperations {
    suspend fun recoverManifests(
        profileId: Int,
        generation: Long,
        forceAll: Boolean,
        onManifestEvent: suspend (ManifestRecoveryEvent) -> Unit,
    ): ManifestRecoveryOutcome
    suspend fun refreshCatalogs(
        profileId: Int,
        generation: Long,
        readyManifestUrls: Set<String>?,
    )
}

internal fun addonsForRecoveryPass(
    addons: List<ManagedAddon>,
    readyManifestUrls: Set<String>?,
): List<ManagedAddon> {
    val enabledAddons = addons.enabledAddons()
    if (readyManifestUrls == null) return enabledAddons
    return enabledAddons.filter { addon ->
        addon.manifestUrl in readyManifestUrls &&
            addon.manifest != null
    }
}

internal enum class NetworkRecoveryRunResult {
    Completed,
    Discarded,
}

internal suspend fun runOrderedNetworkRecovery(
    profileId: Int,
    generation: Long,
    forceAllManifests: Boolean,
    operations: NetworkRecoveryOperations,
    isCurrent: () -> Boolean,
    onPhase: (NetworkRecoveryPhase, ManifestRecoveryOutcome?) -> Unit,
): NetworkRecoveryRunResult {
    if (!isCurrent()) return NetworkRecoveryRunResult.Discarded
    onPhase(NetworkRecoveryPhase.RestoringAddons, null)
    val admittedManifestUrls = linkedSetOf<String>()
    val manifests = operations.recoverManifests(
        profileId = profileId,
        generation = generation,
        forceAll = forceAllManifests,
        onManifestEvent = { event ->
            if (isCurrent()) {
                val readyManifestUrls = when (event) {
                    is ManifestRecoveryEvent.CachedProviderAdmitted -> {
                        admittedManifestUrls += event.manifestUrl
                        admittedManifestUrls.toSet()
                    }
                    is ManifestRecoveryEvent.MissingProviderRecovered,
                    is ManifestRecoveryEvent.StaleProviderChanged,
                    is ManifestRecoveryEvent.ManualForceResult,
                    -> setOf(event.manifestUrl)
                }
                onPhase(NetworkRecoveryPhase.RefreshingCatalogs, null)
                operations.refreshCatalogs(
                    profileId = profileId,
                    generation = generation,
                    readyManifestUrls = readyManifestUrls,
                )
            }
        },
    )
    if (manifests.stale || !isCurrent()) return NetworkRecoveryRunResult.Discarded

    onPhase(NetworkRecoveryPhase.RefreshingCatalogs, manifests)
    operations.refreshCatalogs(
        profileId = profileId,
        generation = generation,
        readyManifestUrls = null,
    )
    if (!isCurrent()) return NetworkRecoveryRunResult.Discarded

    onPhase(NetworkRecoveryPhase.Completed, manifests)
    return NetworkRecoveryRunResult.Completed
}

internal enum class NetworkRecoveryRequestResult {
    Started,
    Coalesced,
    Replaced,
}

internal class NetworkRecoveryTransitionTracker {
    private var observedOfflineLike = false

    fun onCondition(condition: NetworkCondition): Boolean = when (condition) {
        NetworkCondition.NoInternet,
        NetworkCondition.ServersUnreachable,
        -> {
            observedOfflineLike = true
            false
        }

        NetworkCondition.Online -> observedOfflineLike.also {
            observedOfflineLike = false
        }

        NetworkCondition.Unknown,
        NetworkCondition.Checking,
        -> false
    }
}

internal class NetworkRecoveryRequestGate {
    private val lock = SynchronizedObject()
    private var activeProfileId: Int? = null
    private var activeJob: Job? = null
    private var latestGeneration: Long = 0L

    fun launch(
        scope: CoroutineScope,
        profileId: Int,
        replaceActiveSameProfile: Boolean = false,
        block: suspend (generation: Long) -> Unit,
    ): Pair<NetworkRecoveryRequestResult, Long> {
        lateinit var newJob: Job
        var previousJob: Job? = null
        val result = synchronized(lock) {
            val active = activeJob?.takeUnless(Job::isCompleted)
            if (active != null && activeProfileId == profileId && !replaceActiveSameProfile) {
                return NetworkRecoveryRequestResult.Coalesced to latestGeneration
            }

            previousJob = active
            latestGeneration += 1L
            val generation = latestGeneration
            newJob = scope.launch(start = CoroutineStart.LAZY) {
                block(generation)
            }
            activeProfileId = profileId
            activeJob = newJob
            newJob.invokeOnCompletion {
                synchronized(lock) {
                    if (activeJob === newJob) {
                        activeJob = null
                        activeProfileId = null
                    }
                }
            }
            (if (active == null) NetworkRecoveryRequestResult.Started else NetworkRecoveryRequestResult.Replaced) to generation
        }

        previousJob?.cancel()
        newJob.start()
        return result
    }

    fun invalidate(): Long {
        var job: Job? = null
        var generation = 0L
        synchronized(lock) {
            latestGeneration += 1L
            generation = latestGeneration
            job = activeJob
            activeJob = null
            activeProfileId = null
        }
        job?.cancel()
        return generation
    }

    fun isCurrent(profileId: Int, generation: Long): Boolean = synchronized(lock) {
        latestGeneration == generation && activeProfileId == profileId
    }
}

object NetworkRecoveryCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val operations = object : NetworkRecoveryOperations {
        override suspend fun recoverManifests(
            profileId: Int,
            generation: Long,
            forceAll: Boolean,
            onManifestEvent: suspend (ManifestRecoveryEvent) -> Unit,
        ): ManifestRecoveryOutcome {
            val result = AddonRepository.recoverEnabledManifests(
                profileId = profileId,
                recoveryGeneration = generation,
                forceAll = forceAll,
                onManifestEvent = onManifestEvent,
            )
            return ManifestRecoveryOutcome(
                attemptedUrls = result.attemptedUrls,
                recoveredUrls = result.recoveredUrls,
                changedUrls = result.changedUrls,
                failedUrls = result.failedUrls,
                stale = result.stale,
            )
        }

        override suspend fun refreshCatalogs(
            profileId: Int,
            generation: Long,
            readyManifestUrls: Set<String>?,
        ) {
            if (ProfileRepository.activeProfileId != profileId) return
            val enabledAddons: List<ManagedAddon> = AddonRepository.uiState.value.addons.enabledAddons()
            HomeCatalogSettingsRepository.syncCatalogs(enabledAddons)
            val readyAddons = addonsForRecoveryPass(
                addons = enabledAddons,
                readyManifestUrls = readyManifestUrls,
            )
            HomeRepository.refresh(readyAddons, force = true, partial = readyManifestUrls != null)
            SearchRepository.refreshAfterRecovery(enabledAddons, readyManifestUrls)
            if (readyManifestUrls != null) return
            HomeRepository.awaitCurrentRefresh()
            SearchRepository.awaitCurrentRecoveryRefresh()
            OfflineLibraryRepository.refreshMissingAndStale()
            CatalogRepository.onRecoveryGeneration(generation)
            MetaDetailsRepository.onRecoveryGeneration(generation)
        }
    }

    private val controller = NetworkRecoveryController(
        scope = scope,
        operations = operations,
        activeProfileId = { ProfileRepository.activeProfileId },
        requestFreshProbe = NetworkStatusRepository::requestReconnect,
        onRecoveryCompleted = NetworkStatusRepository::onRecoveryCompleted,
        cancelConnectivitySession = {
            NetworkStatusRepository.cancelReconnect()
            NetworkStatusRepository.clearOfflinePresentationHold()
        },
    )
    val uiState: StateFlow<NetworkRecoveryUiState> = controller.uiState

    fun ensureStarted() {
        if (started) return
        started = true
        NetworkStatusRepository.ensureStarted()
        scope.launch {
            NetworkStatusRepository.uiState.collect { state ->
                controller.onNetworkState(state)
            }
        }
    }

    fun retry() {
        ensureStarted()
        controller.retry()
    }

    fun refreshAllManifests() {
        ensureStarted()
        controller.retry(forceAllManifests = true)
    }

    fun onProfileChanged(profileId: Int) {
        controller.onProfileChanged(profileId)
    }

    fun onProfileDeleted(profileId: Int) {
        if (uiState.value.profileId == profileId) onProfileChanged(ProfileRepository.activeProfileId)
    }

    internal fun isRecoveringProfile(profileId: Int): Boolean =
        uiState.value.profileId == profileId && uiState.value.isRecovering
}

internal class NetworkRecoveryController(
    private val scope: CoroutineScope,
    private val operations: NetworkRecoveryOperations,
    private val activeProfileId: () -> Int,
    private val requestFreshProbe: () -> Long,
    private val onRecoveryCompleted: () -> Unit = {},
    private val cancelConnectivitySession: () -> Unit = {},
) {
    private val log = Logger.withTag("NetworkRecovery")
    private val requestGate = NetworkRecoveryRequestGate()
    private val transitionLock = SynchronizedObject()
    private val transitionTracker = NetworkRecoveryTransitionTracker()
    private val _uiState = MutableStateFlow(NetworkRecoveryUiState())
    val uiState: StateFlow<NetworkRecoveryUiState> = _uiState.asStateFlow()
    private var retryProbeGeneration: Long? = null
    private var forceAllPendingUntilOnline = false

    fun retry(forceAllManifests: Boolean = false) {
        synchronized(transitionLock) {
            forceAllPendingUntilOnline = forceAllPendingUntilOnline || forceAllManifests
            if (retryProbeGeneration == null) {
                retryProbeGeneration = requestFreshProbe()
            }
        }
    }

    fun onProfileChanged(profileId: Int) {
        cancelConnectivitySession()
        val generation = requestGate.invalidate()
        synchronized(transitionLock) {
            retryProbeGeneration = null
            forceAllPendingUntilOnline = false
        }
        _uiState.value = NetworkRecoveryUiState(
            profileId = profileId,
            generation = generation,
        )
    }

    fun onNetworkState(state: NetworkStatusUiState) {
        var shouldRecover = false
        var shouldCancelRecovery = false
        var forceAll = false
        var reconnected = false
        var trigger = NetworkRecoveryTrigger.Reconnect
        synchronized(transitionLock) {
            reconnected = transitionTracker.onCondition(state.condition)
            shouldCancelRecovery = state.isOfflineLike && _uiState.value.isRecovering
            if (
                state.isOfflineLike &&
                retryProbeGeneration?.let { state.probeGeneration >= it } == true
            ) {
                retryProbeGeneration = null
                forceAllPendingUntilOnline = false
            }
            if (state.isOnline) {
                val retryReady = retryProbeGeneration?.let { state.probeGeneration >= it } == true
                shouldRecover = reconnected || retryReady
                if (retryReady) {
                    forceAll = forceAllPendingUntilOnline
                    trigger = if (forceAll) NetworkRecoveryTrigger.ManualRefresh else NetworkRecoveryTrigger.Retry
                    retryProbeGeneration = null
                    forceAllPendingUntilOnline = false
                }
            }
        }

        if (shouldCancelRecovery) {
            val profileId = activeProfileId()
            val generation = requestGate.invalidate()
            _uiState.value = NetworkRecoveryUiState(
                profileId = profileId,
                generation = generation,
            )
        }

        if (shouldRecover) {
            requestRecovery(
                trigger = trigger,
                forceAllManifests = forceAll,
                replaceActiveSameProfile = reconnected || forceAll,
            )
        }
    }

    private fun requestRecovery(
        trigger: NetworkRecoveryTrigger,
        forceAllManifests: Boolean,
        replaceActiveSameProfile: Boolean,
    ) {
        val profileId = activeProfileId()
        val (result, generation) = requestGate.launch(
            scope = scope,
            profileId = profileId,
            // A new confirmed outage interrupts the old attempt. Duplicate Online and
            // repeated Retry requests can still share an uninterrupted active attempt.
            replaceActiveSameProfile = replaceActiveSameProfile,
        ) { runGeneration ->
            try {
                runOrderedNetworkRecovery(
                    profileId = profileId,
                    generation = runGeneration,
                    forceAllManifests = forceAllManifests,
                    operations = operations,
                    isCurrent = {
                        requestGate.isCurrent(profileId, runGeneration) &&
                            activeProfileId() == profileId
                    },
                    onPhase = { phase, manifestOutcome ->
                        if (
                            requestGate.isCurrent(profileId, runGeneration) &&
                            activeProfileId() == profileId
                        ) {
                            log.d { "Recovery profile=$profileId generation=$runGeneration phase=$phase" }
                            _uiState.value = NetworkRecoveryUiState(
                                profileId = profileId,
                                generation = runGeneration,
                                phase = phase,
                                trigger = trigger,
                                failedManifestCount = manifestOutcome?.failedUrls?.size ?: 0,
                            )
                            if (phase == NetworkRecoveryPhase.Completed) {
                                onRecoveryCompleted()
                            }
                        }
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "Recovery failed for profile $profileId generation $runGeneration" }
                if (
                    requestGate.isCurrent(profileId, runGeneration) &&
                        activeProfileId() == profileId
                ) {
                    _uiState.value = NetworkRecoveryUiState(
                        profileId = profileId,
                        generation = runGeneration,
                        phase = NetworkRecoveryPhase.Failed,
                        trigger = trigger,
                    )
                }
            }
        }
        if (result == NetworkRecoveryRequestResult.Coalesced) {
            log.d { "Coalesced recovery for profile $profileId generation $generation" }
        }
    }
}
