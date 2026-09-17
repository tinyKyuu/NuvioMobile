package com.nuvio.app.features.addons

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

internal enum class ManifestRefreshReason {
    Background,
    Explicit,
    Recovery,
}

internal enum class ManifestRefreshOutcome {
    Changed,
    Unchanged,
    Failed,
    ;

    val succeeded: Boolean
        get() = this != Failed
}

internal data class ManifestRefreshIdentity(
    val profileId: Int,
    val profileGeneration: Long,
    val reason: ManifestRefreshReason,
    val recoveryGeneration: Long? = null,
)

internal fun shouldReplaceManifestRefresh(
    active: ManifestRefreshIdentity,
    requested: ManifestRefreshIdentity,
): Boolean {
    if (
        active.profileId != requested.profileId ||
        active.profileGeneration != requested.profileGeneration
    ) {
        return true
    }
    return when (requested.reason) {
        ManifestRefreshReason.Background -> false
        ManifestRefreshReason.Explicit -> active.reason == ManifestRefreshReason.Background
        ManifestRefreshReason.Recovery ->
            active.reason != ManifestRefreshReason.Recovery ||
                active.recoveryGeneration != requested.recoveryGeneration
    }
}

internal class ManifestRefreshSingleFlight {
    private data class ActiveRequest(
        val identity: ManifestRefreshIdentity,
        val deferred: Deferred<ManifestRefreshOutcome>,
    )

    private val lock = SynchronizedObject()
    private val activeRequests = mutableMapOf<String, ActiveRequest>()

    fun start(
        scope: CoroutineScope,
        manifestUrl: String,
        identity: ManifestRefreshIdentity,
        onStarted: () -> Unit = {},
        block: suspend () -> ManifestRefreshOutcome,
    ): Deferred<ManifestRefreshOutcome> {
        lateinit var request: Deferred<ManifestRefreshOutcome>
        request = scope.async(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                synchronized(lock) {
                    if (activeRequests[manifestUrl]?.deferred === request) {
                        activeRequests.remove(manifestUrl)
                    }
                }
            }
        }

        var sharedRequest: Deferred<ManifestRefreshOutcome>? = null
        var displacedRequest: Deferred<ManifestRefreshOutcome>? = null
        synchronized(lock) {
            val active = activeRequests[manifestUrl]
                ?.takeUnless { it.deferred.isCompleted }
            if (active != null && !shouldReplaceManifestRefresh(active.identity, identity)) {
                sharedRequest = active.deferred
            } else {
                displacedRequest = active?.deferred
                activeRequests[manifestUrl] = ActiveRequest(identity, request)
            }
        }

        sharedRequest?.let { shared ->
            request.cancel()
            return shared
        }

        displacedRequest?.cancel()
        onStarted()
        request.start()
        return request
    }

    fun isActive(manifestUrl: String): Boolean = synchronized(lock) {
        activeRequests[manifestUrl]?.deferred?.isCompleted == false
    }

    fun cancelAll() {
        val requests = synchronized(lock) {
            activeRequests.values.map(ActiveRequest::deferred).also { activeRequests.clear() }
        }
        requests.forEach(Job::cancel)
    }
}

internal suspend fun collectManifestRecoveryResults(
    requests: Map<String, Deferred<ManifestRefreshOutcome>>,
    isCurrent: () -> Boolean,
    onManifestRecovered: suspend (String) -> Unit,
): AddonManifestRecoveryResult = coroutineScope {
    if (requests.isEmpty()) {
        return@coroutineScope AddonManifestRecoveryResult(emptySet(), emptySet(), emptySet())
    }

    val results = Channel<Pair<String, ManifestRefreshOutcome>>(capacity = requests.size)
    val waiters = requests.map { (manifestUrl, request) ->
        launch {
            results.send(manifestUrl to request.await())
        }
    }
    val closer = launch {
        waiters.joinAll()
        results.close()
    }
    val recoveredUrls = linkedSetOf<String>()
    val changedUrls = linkedSetOf<String>()
    val failedUrls = linkedSetOf<String>()

    try {
        for ((manifestUrl, succeeded) in results) {
            if (!isCurrent()) {
                waiters.forEach(Job::cancel)
                return@coroutineScope AddonManifestRecoveryResult(
                    attemptedUrls = requests.keys,
                    recoveredUrls = emptySet(),
                    failedUrls = emptySet(),
                    stale = true,
                    changedUrls = emptySet(),
                )
            }
            if (succeeded.succeeded) {
                recoveredUrls += manifestUrl
                if (succeeded == ManifestRefreshOutcome.Changed) {
                    changedUrls += manifestUrl
                    onManifestRecovered(manifestUrl)
                }
            } else {
                failedUrls += manifestUrl
            }
        }
    } finally {
        closer.cancel()
        results.close()
    }

    AddonManifestRecoveryResult(
        attemptedUrls = requests.keys,
        recoveredUrls = recoveredUrls,
        changedUrls = changedUrls,
        failedUrls = failedUrls,
        stale = !isCurrent(),
    )
}

/** Shared by AddonRepository and recovery integration tests; only transport is injected. */
internal suspend fun recoverAddonManifestBatch(
    addons: List<ManagedAddon>,
    cache: Map<String, CachedAddonManifest>,
    nowEpochMs: Long,
    forceAll: Boolean,
    isCurrent: () -> Boolean,
    startRefresh: (String) -> Deferred<ManifestRefreshOutcome>,
    onManifestRecovered: suspend (String) -> Unit,
): AddonManifestRecoveryResult {
    if (!isCurrent()) return AddonManifestRecoveryResult(emptySet(), emptySet(), emptySet(), stale = true)
    val attemptedUrls = selectAddonManifestRefreshUrls(addons, cache, nowEpochMs, forceAll)
    // Every valid parsed manifest remains usable while background validation is
    // pending. Missing manifests still wait for a successful transport result.
    if (!forceAll && attemptedUrls.isNotEmpty()) {
        addons.asSequence()
            .filter { it.enabled && it.manifest != null }
            .map(ManagedAddon::manifestUrl)
            .distinct()
            .forEach { if (isCurrent()) onManifestRecovered(it) }
    }
    if (!isCurrent()) {
        return AddonManifestRecoveryResult(emptySet(), emptySet(), emptySet(), stale = true)
    }
    if (attemptedUrls.isEmpty()) {
        return AddonManifestRecoveryResult(
            attemptedUrls = attemptedUrls,
            recoveredUrls = emptySet(),
            failedUrls = emptySet(),
            stale = !isCurrent(),
        )
    }
    val requests = attemptedUrls.associateWith(startRefresh)
    return collectManifestRecoveryResults(
        requests = requests,
        isCurrent = isCurrent,
        onManifestRecovered = onManifestRecovered,
    )
}
