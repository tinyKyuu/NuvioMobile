package com.nuvio.app.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkRecoveryCoordinatorTest {
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
            override suspend fun recoverManifests(profileId: Int, forceAll: Boolean): ManifestRecoveryOutcome {
                events += "manifests:$profileId:$forceAll"
                return ManifestRecoveryOutcome(
                    attemptedUrls = setOf("one", "two"),
                    recoveredUrls = setOf("one"),
                    failedUrls = setOf("two"),
                )
            }

            override suspend fun refreshCatalogs(profileId: Int, generation: Long) {
                events += "catalogs:$profileId:$generation"
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
        assertEquals(listOf("manifests:2:false", "catalogs:2:7"), events)
        assertEquals(
            listOf(
                NetworkRecoveryPhase.RestoringAddons,
                NetworkRecoveryPhase.RefreshingCatalogs,
                NetworkRecoveryPhase.Completed,
            ),
            phases,
        )
    }

    @Test
    fun `stale profile generation is discarded before catalog refresh`() = runBlocking {
        var current = true
        var catalogRefreshes = 0
        val operations = object : NetworkRecoveryOperations {
            override suspend fun recoverManifests(profileId: Int, forceAll: Boolean): ManifestRecoveryOutcome {
                current = false
                return ManifestRecoveryOutcome(recoveredUrls = setOf("one"))
            }

            override suspend fun refreshCatalogs(profileId: Int, generation: Long) {
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
            override suspend fun recoverManifests(profileId: Int, forceAll: Boolean): ManifestRecoveryOutcome {
                events += "manifest"
                return ManifestRecoveryOutcome(
                    attemptedUrls = setOf("missing"),
                    recoveredUrls = setOf("missing"),
                )
            }

            override suspend fun refreshCatalogs(profileId: Int, generation: Long) {
                events += "catalogs"
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

        assertEquals(listOf("manifest", "catalogs"), events)
    }
}
