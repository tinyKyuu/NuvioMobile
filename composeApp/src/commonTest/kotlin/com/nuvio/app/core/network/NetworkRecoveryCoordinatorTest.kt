package com.nuvio.app.core.network

import com.nuvio.app.features.addons.collectManifestRecoveryResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkRecoveryCoordinatorTest {
    @Test
    fun `retry ignores stale Online and recovers after fresh NoInternet then Online`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var network = NetworkCondition.NoInternet
        var manifestAttempts = 0
        val offlineRequest = CompletableDeferred<Unit>()
        val controller = NetworkRecoveryController(
            scope = scope,
            activeProfileId = { 1 },
            requestFreshProbe = { 11L },
            operations = object : NetworkRecoveryOperations {
                override suspend fun recoverManifests(
                    profileId: Int,
                    generation: Long,
                    forceAll: Boolean,
                    onManifestRecovered: suspend (String) -> Unit,
                ): ManifestRecoveryOutcome {
                    manifestAttempts++
                    if (network != NetworkCondition.Online) offlineRequest.await()
                    onManifestRecovered("healthy")
                    return ManifestRecoveryOutcome(recoveredUrls = setOf("healthy"))
                }

                override suspend fun refreshCatalogs(profileId: Int, generation: Long, readyManifestUrls: Set<String>?) = Unit
            },
        )
        try {
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.Online, 10L))
            controller.retry()
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.Online, 10L))
            yield()
            assertEquals(0, manifestAttempts)
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.NoInternet, 11L))
            assertEquals(0, manifestAttempts)
            network = NetworkCondition.Online
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.Online, 12L))
            val completed = withTimeout(5_000L) {
                controller.uiState.first { it.phase == NetworkRecoveryPhase.Completed }
            }
            assertTrue(completed.generation > 0L)
            assertEquals(1, manifestAttempts)
            assertFalse(offlineRequest.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `fresh Online Retry coalesces with a genuine active recovery`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val hold = CompletableDeferred<Unit>()
        var attempts = 0
        val controller = NetworkRecoveryController(
            scope = scope,
            activeProfileId = { 1 },
            requestFreshProbe = { 3L },
            operations = object : NetworkRecoveryOperations {
                override suspend fun recoverManifests(
                    profileId: Int,
                    generation: Long,
                    forceAll: Boolean,
                    onManifestRecovered: suspend (String) -> Unit,
                ): ManifestRecoveryOutcome {
                    attempts++
                    hold.await()
                    return ManifestRecoveryOutcome()
                }

                override suspend fun refreshCatalogs(profileId: Int, generation: Long, readyManifestUrls: Set<String>?) = Unit
            },
        )
        try {
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.NoInternet, 1L))
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.Online, 2L))
            val generation = controller.uiState.value.generation
            controller.retry()
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.Online, 3L))
            assertEquals(1, attempts)
            assertEquals(generation, controller.uiState.value.generation)
            hold.complete(Unit)
            withTimeout(5_000L) { controller.uiState.first { it.phase == NetworkRecoveryPhase.Completed } }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `only a confirmed offline-like to online transition starts recovery`() {
        val tracker = NetworkRecoveryTransitionTracker()

        assertFalse(tracker.onCondition(NetworkCondition.Online))
        assertFalse(tracker.onCondition(NetworkCondition.Checking))
        assertFalse(tracker.onCondition(NetworkCondition.NoInternet))
        assertFalse(tracker.onCondition(NetworkCondition.Checking))
        assertTrue(tracker.onCondition(NetworkCondition.Online))
        assertFalse(tracker.onCondition(NetworkCondition.Online))
        assertFalse(tracker.onCondition(NetworkCondition.ServersUnreachable))
        assertTrue(tracker.onCondition(NetworkCondition.Online))
    }

    @Test
    fun `manifest recovery always precedes catalog refresh and partial failure is non blocking`() = runBlocking {
        val events = mutableListOf<String>()
        val phases = mutableListOf<NetworkRecoveryPhase>()
        val operations = object : NetworkRecoveryOperations {
            override suspend fun recoverManifests(
                profileId: Int,
                generation: Long,
                forceAll: Boolean,
                onManifestRecovered: suspend (String) -> Unit,
            ): ManifestRecoveryOutcome {
                events += "manifests-start:$profileId:$generation:$forceAll"
                onManifestRecovered("one")
                events += "manifests-settled"
                return ManifestRecoveryOutcome(
                    attemptedUrls = setOf("one", "two"),
                    recoveredUrls = setOf("one"),
                    failedUrls = setOf("two"),
                )
            }

            override suspend fun refreshCatalogs(
                profileId: Int,
                generation: Long,
                readyManifestUrls: Set<String>?,
            ) {
                events += "catalogs:$profileId:$generation:${readyManifestUrls?.joinToString() ?: "all"}"
            }
        }

        val result = runOrderedNetworkRecovery(
            profileId = 2,
            generation = 7L,
            forceAllManifests = false,
            operations = operations,
            isCurrent = { true },
            onPhase = { phase, _ -> phases += phase },
        )

        assertEquals(NetworkRecoveryRunResult.Completed, result)
        assertEquals(
            listOf(
                "manifests-start:2:7:false",
                "catalogs:2:7:one",
                "manifests-settled",
                "catalogs:2:7:all",
            ),
            events,
        )
        assertEquals(
            listOf(
                NetworkRecoveryPhase.RestoringAddons,
                NetworkRecoveryPhase.RefreshingCatalogs,
                NetworkRecoveryPhase.RefreshingCatalogs,
                NetworkRecoveryPhase.Completed,
            ),
            phases,
        )
    }

    @Test
    fun `healthy catalog refresh starts before slow manifest settles`() = runBlocking {
        val healthy = CompletableDeferred(true)
        val slow = CompletableDeferred<Boolean>()
        val firstCatalogRefresh = CompletableDeferred<Unit>()
        var catalogRefreshCount = 0
        val operations = object : NetworkRecoveryOperations {
            override suspend fun recoverManifests(
                profileId: Int,
                generation: Long,
                forceAll: Boolean,
                onManifestRecovered: suspend (String) -> Unit,
            ): ManifestRecoveryOutcome {
                val result = collectManifestRecoveryResults(
                    requests = linkedMapOf(
                        "healthy" to healthy,
                        "slow" to slow,
                    ),
                    isCurrent = { true },
                    onManifestRecovered = onManifestRecovered,
                )
                return ManifestRecoveryOutcome(
                    attemptedUrls = result.attemptedUrls,
                    recoveredUrls = result.recoveredUrls,
                    failedUrls = result.failedUrls,
                    stale = result.stale,
                )
            }

            override suspend fun refreshCatalogs(
                profileId: Int,
                generation: Long,
                readyManifestUrls: Set<String>?,
            ) {
                catalogRefreshCount += 1
                firstCatalogRefresh.complete(Unit)
            }
        }

        val recovery = async {
            runOrderedNetworkRecovery(
                profileId = 1,
                generation = 3L,
                forceAllManifests = false,
                operations = operations,
                isCurrent = { true },
                onPhase = { _, _ -> },
            )
        }

        firstCatalogRefresh.await()
        assertFalse(recovery.isCompleted)

        slow.complete(false)
        assertEquals(NetworkRecoveryRunResult.Completed, recovery.await())
        assertEquals(2, catalogRefreshCount)
    }

    @Test
    fun `stale profile generation is discarded before catalog refresh`() = runBlocking {
        var current = true
        var catalogRefreshes = 0
        val operations = object : NetworkRecoveryOperations {
            override suspend fun recoverManifests(
                profileId: Int,
                generation: Long,
                forceAll: Boolean,
                onManifestRecovered: suspend (String) -> Unit,
            ): ManifestRecoveryOutcome {
                current = false
                return ManifestRecoveryOutcome(recoveredUrls = setOf("one"))
            }

            override suspend fun refreshCatalogs(
                profileId: Int,
                generation: Long,
                readyManifestUrls: Set<String>?,
            ) {
                catalogRefreshes += 1
            }
        }

        val result = runOrderedNetworkRecovery(
            profileId = 1,
            generation = 4L,
            forceAllManifests = false,
            operations = operations,
            isCurrent = { current },
            onPhase = { _, _ -> },
        )

        assertEquals(NetworkRecoveryRunResult.Discarded, result)
        assertEquals(0, catalogRefreshes)
    }

    @Test
    fun `request gate coalesces duplicates and lets explicit force replace the active run`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = NetworkRecoveryRequestGate()
        val firstHold = CompletableDeferred<Unit>()
        val forcedHold = CompletableDeferred<Unit>()

        val first = gate.launch(scope, profileId = 1) { firstHold.await() }
        val duplicate = gate.launch(scope, profileId = 1) { error("duplicate must not run") }
        val forced = gate.launch(
            scope = scope,
            profileId = 1,
            replaceActiveSameProfile = true,
        ) { forcedHold.await() }

        assertEquals(NetworkRecoveryRequestResult.Started, first.first)
        assertEquals(NetworkRecoveryRequestResult.Coalesced, duplicate.first)
        assertEquals(first.second, duplicate.second)
        assertEquals(NetworkRecoveryRequestResult.Replaced, forced.first)
        assertTrue(forced.second > first.second)

        gate.invalidate()
        scope.cancel()
    }

    @Test
    fun `second confirmed reconnect replaces held run and rejects its late success`(): Unit = runBlocking {
        assertInterruptedRecovery(lateFailure = false)
    }

    @Test
    fun `second confirmed reconnect replaces held run and rejects its late failure`(): Unit = runBlocking {
        assertInterruptedRecovery(lateFailure = true)
    }

    private suspend fun assertInterruptedRecovery(lateFailure: Boolean) {
        val fixture = ReconnectFixture()
        try {
            fixture.reconnect()
            val first = fixture.controller.uiState.value.generation
            fixture.reconnect()
            val second = fixture.controller.uiState.value.generation
            assertTrue(second > first)
            assertEquals(listOf(first, second), fixture.attempts.map { it.second })

            repeat(3) {
                fixture.controller.onNetworkState(NetworkStatusUiState(NetworkCondition.Online, 10L))
                fixture.controller.retry()
                fixture.controller.onNetworkState(NetworkStatusUiState(NetworkCondition.Online, 10L))
            }
            assertEquals(2, fixture.attempts.size)
            fixture.holds.getValue(first).complete(lateFailure)
            assertEquals(second, fixture.controller.uiState.value.generation)
            assertEquals(NetworkRecoveryPhase.RestoringAddons, fixture.controller.uiState.value.phase)
            assertTrue(fixture.publications.isEmpty())

            fixture.holds.getValue(second).complete(false)
            withTimeout(5_000L) {
                fixture.controller.uiState.first { it.phase == NetworkRecoveryPhase.Completed }
            }
            assertEquals(listOf(1 to second, 1 to second), fixture.publications)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `rapid confirmed cycles and profile switch publish only the latest owned generation`(): Unit = runBlocking {
        val fixture = ReconnectFixture()
        try {
            repeat(3) { fixture.reconnect() }
            val oldGenerations = fixture.attempts.map { it.second }
            assertEquals(3, oldGenerations.distinct().size)
            fixture.profileId = 2
            fixture.controller.onProfileChanged(2)
            fixture.reconnect()
            val latest = fixture.controller.uiState.value.generation

            oldGenerations.forEachIndexed { index, generation ->
                fixture.holds.getValue(generation).complete(index % 2 == 0)
            }
            assertTrue(fixture.publications.isEmpty())
            assertEquals(2, fixture.controller.uiState.value.profileId)
            assertEquals(latest, fixture.controller.uiState.value.generation)
            fixture.holds.getValue(latest).complete(false)
            withTimeout(5_000L) {
                fixture.controller.uiState.first { it.phase == NetworkRecoveryPhase.Completed }
            }
            assertEquals(listOf(2 to latest, 2 to latest), fixture.publications)
        } finally {
            fixture.close()
        }
    }

    private class ReconnectFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var profileId = 1
        val attempts = mutableListOf<Pair<Int, Long>>()
        val publications = mutableListOf<Pair<Int, Long>>()
        val holds = mutableMapOf<Long, CompletableDeferred<Boolean>>()
        val controller = NetworkRecoveryController(
            scope = scope,
            activeProfileId = { profileId },
            requestFreshProbe = { 10L },
            operations = object : NetworkRecoveryOperations {
                override suspend fun recoverManifests(
                    profileId: Int,
                    generation: Long,
                    forceAll: Boolean,
                    onManifestRecovered: suspend (String) -> Unit,
                ): ManifestRecoveryOutcome {
                    attempts += profileId to generation
                    val hold = CompletableDeferred<Boolean>().also { holds[generation] = it }
                    // Model an already-dispatched transport callback that ignores cancellation.
                    return withContext(NonCancellable) {
                        val fail = hold.await()
                        if (fail) error("late transport failure")
                        onManifestRecovered("healthy")
                        ManifestRecoveryOutcome(recoveredUrls = setOf("healthy"))
                    }
                }

                override suspend fun refreshCatalogs(
                    profileId: Int,
                    generation: Long,
                    readyManifestUrls: Set<String>?,
                ) {
                    publications += profileId to generation
                }
            },
        )

        fun reconnect() {
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.NoInternet, 1L))
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.Online, 2L))
        }

        fun close() {
            scope.cancel()
            holds.values.forEach { it.complete(false) }
        }
    }

    @Test
    fun `profile change replaces active recovery and invalidation rejects late generation`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = NetworkRecoveryRequestGate()
        val firstHold = CompletableDeferred<Unit>()
        val secondHold = CompletableDeferred<Unit>()

        val first = gate.launch(scope, profileId = 1) { firstHold.await() }
        val second = gate.launch(scope, profileId = 2) { secondHold.await() }

        assertEquals(NetworkRecoveryRequestResult.Replaced, second.first)
        assertFalse(gate.isCurrent(profileId = 1, generation = first.second))
        assertTrue(gate.isCurrent(profileId = 2, generation = second.second))

        gate.invalidate()
        assertFalse(gate.isCurrent(profileId = 2, generation = second.second))
        scope.cancel()
    }

    @Test
    fun `completed generation remains observable to a screen composed later`() {
        val state = NetworkRecoveryUiState(
            profileId = 3,
            generation = 12L,
            phase = NetworkRecoveryPhase.Completed,
            trigger = NetworkRecoveryTrigger.Reconnect,
        )

        assertEquals(12L, state.observableGeneration(profileId = 3))
        assertEquals(null, state.observableGeneration(profileId = 2))
    }

    @Test
    fun `first offline launch recovers missing manifest before catalogs`() = runBlocking {
        val tracker = NetworkRecoveryTransitionTracker()
        val events = mutableListOf<String>()
        val operations = object : NetworkRecoveryOperations {
            override suspend fun recoverManifests(
                profileId: Int,
                generation: Long,
                forceAll: Boolean,
                onManifestRecovered: suspend (String) -> Unit,
            ): ManifestRecoveryOutcome {
                events += "manifest-start"
                onManifestRecovered("missing")
                events += "manifest-finished"
                return ManifestRecoveryOutcome(
                    attemptedUrls = setOf("missing"),
                    recoveredUrls = setOf("missing"),
                )
            }

            override suspend fun refreshCatalogs(
                profileId: Int,
                generation: Long,
                readyManifestUrls: Set<String>?,
            ) {
                events += if (readyManifestUrls == null) "catalogs-final" else "catalogs-partial"
            }
        }

        assertFalse(tracker.onCondition(NetworkCondition.NoInternet))
        assertTrue(tracker.onCondition(NetworkCondition.Online))
        runOrderedNetworkRecovery(
            profileId = 1,
            generation = 1L,
            forceAllManifests = false,
            operations = operations,
            isCurrent = { true },
            onPhase = { _, _ -> },
        )

        assertEquals(
            listOf("manifest-start", "catalogs-partial", "manifest-finished", "catalogs-final"),
            events,
        )
    }
}
