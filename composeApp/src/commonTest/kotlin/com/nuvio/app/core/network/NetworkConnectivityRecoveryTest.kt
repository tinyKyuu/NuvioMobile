package com.nuvio.app.core.network

import com.nuvio.app.core.sync.AppVisibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkConnectivityRecoveryTest {
    @Test
    fun `path observation coalesces starts and cancels its collector on cleanup`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val cleanedUp = CompletableDeferred<Unit>()
        var collectionCount = 0
        val observation = NetworkPathObservation(
            scope = scope,
            events = {
                flow {
                    collectionCount += 1
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cleanedUp.complete(Unit)
                    }
                }
            },
            onEvent = {},
        )
        try {
            observation.start()
            observation.start()
            withTimeout(1_000) { started.await() }
            assertEquals(1, collectionCount)
            assertTrue(observation.isActive)

            observation.stop()
            withTimeout(1_000) { cleanedUp.await() }
            assertFalse(observation.isActive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `path restoration automatically probes from confirmed offline and coalesces duplicates`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val probes = mutableListOf(
            CompletableDeferred<NetworkCondition>(),
            CompletableDeferred<NetworkCondition>(),
        )
        var probeCount = 0
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = { probes[probeCount++].await() },
        )
        try {
            controller.ensureStarted()
            probes[0].complete(NetworkCondition.NoInternet)
            withTimeout(1_000) { controller.uiState.first { it.condition == NetworkCondition.NoInternet } }

            controller.onNetworkPathEvent(NetworkPathEvent.Available)
            controller.onNetworkPathEvent(NetworkPathEvent.Available)
            assertEquals(2, probeCount)
            assertTrue(controller.uiState.value.isProbing)
            assertEquals(NetworkCondition.NoInternet, controller.uiState.value.condition)

            probes[1].complete(NetworkCondition.Online)
            val restored = withTimeout(1_000) { controller.uiState.first { it.condition == NetworkCondition.Online } }
            assertEquals(2L, restored.probeGeneration)
            assertFalse(restored.isProbing)
            yield()
            assertEquals(2, probeCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Reconnect succeeds on its first real probe without a minimum delay`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val results = mutableListOf(NetworkCondition.NoInternet, NetworkCondition.Online)
        val delays = mutableListOf<Long>()
        var probeCount = 0
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = {
                probeCount += 1
                results.removeFirst()
            },
            delayFor = { delays += it },
        )
        try {
            controller.ensureStarted()
            withTimeout(1_000) { controller.uiState.first { it.condition == NetworkCondition.NoInternet } }

            controller.requestReconnect()
            val restored = withTimeout(1_000) {
                controller.uiState.first { it.condition == NetworkCondition.Online && !it.isProbing }
            }

            assertEquals(2, probeCount)
            assertTrue(delays.isEmpty())
            assertTrue(restored.keepOfflinePresentation)
            controller.onRecoveryCompleted()
            assertFalse(controller.uiState.value.keepOfflinePresentation)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Reconnect retries a failed real probe and stops on the first success`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val results = mutableListOf(
            NetworkCondition.NoInternet,
            NetworkCondition.NoInternet,
            NetworkCondition.Online,
        )
        val delays = mutableListOf<Long>()
        var probeCount = 0
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = {
                probeCount += 1
                results.removeFirst()
            },
            delayFor = { delays += it },
            reconnectRetryDelaysMs = listOf(2L, 4L),
        )
        try {
            controller.ensureStarted()
            withTimeout(1_000) { controller.uiState.first { it.condition == NetworkCondition.NoInternet } }

            controller.requestReconnect()
            withTimeout(1_000) {
                controller.uiState.first { it.condition == NetworkCondition.Online && !it.isProbing }
            }

            assertEquals(3, probeCount)
            assertEquals(listOf(2L), delays)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Reconnect stops after three failed probes and returns to offline idle`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var probeCount = 0
        val delays = mutableListOf<Long>()
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = {
                probeCount += 1
                NetworkCondition.NoInternet
            },
            delayFor = { delays += it },
            reconnectRetryDelaysMs = listOf(2L, 4L),
        )
        try {
            controller.ensureStarted()
            withTimeout(1_000) { controller.uiState.first { it.condition == NetworkCondition.NoInternet } }
            val reconnectGeneration = controller.requestReconnect()
            val failed = withTimeout(1_000) {
                controller.uiState.first {
                    it.probeGeneration == reconnectGeneration && !it.isProbing
                }
            }

            assertEquals(4, probeCount)
            assertEquals(listOf(2L, 4L), delays)
            assertEquals(NetworkCondition.NoInternet, failed.condition)
            assertTrue(failed.usesOfflinePresentation)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Reconnect enforces its overall deadline before the attempt cap`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var elapsedMs = 0L
        var reconnectProbeCount = 0
        val delays = mutableListOf<Long>()
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = { NetworkCondition.NoInternet },
            probeWithinTimeout = {
                reconnectProbeCount += 1
                elapsedMs += 6_000L
                NetworkCondition.NoInternet
            },
            nowMs = { elapsedMs },
            delayFor = { delayMs ->
                delays += delayMs
                elapsedMs += delayMs
            },
            reconnectRetryDelaysMs = listOf(2_000L, 4_000L),
            reconnectTimeoutMs = 15_000L,
            reconnectMaxAttempts = 3,
        )
        try {
            controller.ensureStarted()
            withTimeout(1_000) { controller.uiState.first { it.condition == NetworkCondition.NoInternet } }
            val reconnectGeneration = controller.requestReconnect()
            withTimeout(1_000) {
                controller.uiState.first {
                    it.probeGeneration == reconnectGeneration && !it.isProbing
                }
            }

            assertEquals(2, reconnectProbeCount)
            assertEquals(listOf(2_000L, 1_000L), delays)
            assertEquals(15_000L, elapsedMs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `repeated Reconnect presses and path availability share one retry session`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manualProbe = CompletableDeferred<NetworkCondition>()
        val transitions = NetworkRecoveryTransitionTracker()
        var reconnectProbeCount = 0
        var recoveryCount = 0
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = { NetworkCondition.NoInternet },
            probeWithinTimeout = {
                reconnectProbeCount += 1
                manualProbe.await()
            },
            onProbeResult = { _, condition ->
                if (transitions.onCondition(condition)) recoveryCount += 1
            },
        )
        try {
            controller.ensureStarted()
            withTimeout(1_000) { controller.uiState.first { it.condition == NetworkCondition.NoInternet } }
            val generations = listOf(
                controller.requestReconnect(),
                controller.requestReconnect(),
                controller.requestReconnect(),
            )
            controller.onNetworkPathEvent(NetworkPathEvent.Available)

            assertEquals(1, generations.distinct().size)
            assertEquals(1, reconnectProbeCount)
            assertTrue(controller.uiState.value.isProbing)

            manualProbe.complete(NetworkCondition.Online)
            withTimeout(1_000) {
                controller.uiState.first { it.condition == NetworkCondition.Online && !it.isProbing }
            }
            assertEquals(1, reconnectProbeCount)
            assertEquals(1, recoveryCount)
        } finally {
            manualProbe.complete(NetworkCondition.NoInternet)
            scope.cancel()
        }
    }

    @Test
    fun `backgrounding cancels pending Reconnect backoff`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val delayStarted = CompletableDeferred<Unit>()
        val holdDelay = CompletableDeferred<Unit>()
        var reconnectProbeCount = 0
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = { NetworkCondition.NoInternet },
            probeWithinTimeout = {
                reconnectProbeCount += 1
                NetworkCondition.NoInternet
            },
            delayFor = {
                delayStarted.complete(Unit)
                holdDelay.await()
            },
        )
        try {
            controller.ensureStarted()
            withTimeout(1_000) { controller.uiState.first { it.condition == NetworkCondition.NoInternet } }
            controller.requestReconnect()
            withTimeout(1_000) { delayStarted.await() }

            controller.onAppVisibility(AppVisibility.Background)

            assertFalse(controller.uiState.value.isProbing)
            assertEquals(1, reconnectProbeCount)
        } finally {
            holdDelay.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `availability during an active offline probe queues one fresh probe and one recovery`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val probes = List(3) { CompletableDeferred<NetworkCondition>() }
        val transitions = NetworkRecoveryTransitionTracker()
        var probeCount = 0
        var recoveryCount = 0
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = { probes[probeCount++].await() },
            onProbeResult = { _, condition ->
                if (transitions.onCondition(condition)) recoveryCount += 1
            },
        )
        try {
            controller.ensureStarted()
            probes[0].complete(NetworkCondition.NoInternet)
            withTimeout(1_000) {
                controller.uiState.first { it.condition == NetworkCondition.NoInternet }
            }

            controller.requestRefresh(force = true)
            controller.onNetworkPathEvent(NetworkPathEvent.Available)
            val repeatedReconnectGenerations = listOf(
                controller.requestRefresh(force = true),
                controller.requestRefresh(force = true),
            )

            assertEquals(listOf(3L, 3L), repeatedReconnectGenerations)
            assertEquals(2, probeCount)

            probes[1].complete(NetworkCondition.NoInternet)
            withTimeout(1_000) {
                while (probeCount < 3) yield()
            }
            assertEquals(3, probeCount)

            probes[2].complete(NetworkCondition.Online)
            withTimeout(1_000) {
                controller.uiState.first { it.condition == NetworkCondition.Online && !it.isProbing }
            }

            assertEquals(1, recoveryCount)
            assertEquals(3L, controller.uiState.value.probeGeneration)
        } finally {
            probes.forEach { it.complete(NetworkCondition.NoInternet) }
            scope.cancel()
        }
    }

    @Test
    fun `android recovery availability requires validated internet capability`() {
        assertEquals(
            NetworkPathEvent.Unavailable,
            networkPathEventForCapabilities(
                hasInternetCapability = true,
                hasValidatedCapability = false,
            ),
        )
        assertEquals(
            NetworkPathEvent.Available,
            networkPathEventForCapabilities(
                hasInternetCapability = true,
                hasValidatedCapability = true,
            ),
        )
    }

    @Test
    fun `android default path loss is unavailable even while active capabilities are stale`() {
        assertEquals(
            NetworkPathEvent.Unavailable,
            networkPathEventAfterLoss(),
        )
    }

    @Test
    fun `confirmed no internet does not poll while it waits for a path event`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scheduledDelays = mutableListOf<Long>()
        var probeCount = 0
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = {
                probeCount += 1
                NetworkCondition.NoInternet
            },
            delayFor = { scheduledDelays += it },
        )
        try {
            controller.ensureStarted()
            withTimeout(1_000) { controller.uiState.first { it.condition == NetworkCondition.NoInternet } }

            assertEquals(1, probeCount)
            assertTrue(scheduledDelays.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `server fallback is finite and stops after configured foreground attempts`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scheduledDelays = mutableListOf<Long>()
        var probeCount = 0
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = {
                probeCount += 1
                NetworkCondition.ServersUnreachable
            },
            delayFor = {
                scheduledDelays += it
                yield()
            },
            serverRetryDelaysMs = listOf(5L, 15L, 30L),
        )
        try {
            controller.ensureStarted()
            withTimeout(1_000) {
                controller.uiState.first { it.probeGeneration == 4L && !it.isProbing }
            }

            assertEquals(4, probeCount)
            assertEquals(listOf(5L, 15L, 30L), scheduledDelays)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `background cancels a scheduled server fallback`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val delayStarted = CompletableDeferred<Unit>()
        val holdDelay = CompletableDeferred<Unit>()
        var probeCount = 0
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = {
                probeCount += 1
                NetworkCondition.ServersUnreachable
            },
            delayFor = {
                delayStarted.complete(Unit)
                holdDelay.await()
            },
            serverRetryDelaysMs = listOf(5L),
        )
        try {
            controller.ensureStarted()
            withTimeout(1_000) { delayStarted.await() }
            controller.onAppVisibility(AppVisibility.Background)
            holdDelay.complete(Unit)
            yield()

            assertEquals(1, probeCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `transient path loss stays online after delayed failure confirmation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val results = mutableListOf(
            NetworkCondition.Online,
            NetworkCondition.NoInternet,
            NetworkCondition.Online,
        )
        val delays = mutableListOf<Long>()
        val controller = NetworkStatusController(
            scope = scope,
            probeCondition = { results.removeFirst() },
            delayFor = {
                delays += it
                yield()
            },
            failureConfirmDelayMs = 7L,
            pathLossDebounceMs = 3L,
        )
        try {
            controller.ensureStarted()
            withTimeout(1_000) { controller.uiState.first { it.condition == NetworkCondition.Online } }
            controller.onNetworkPathEvent(NetworkPathEvent.Unavailable)
            withTimeout(1_000) { controller.uiState.first { it.probeGeneration == 2L && !it.isProbing } }

            assertEquals(NetworkCondition.Online, controller.uiState.value.condition)
            assertEquals(listOf(3L, 7L), delays)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `back online notification waits for successful content reconciliation`() {
        val tracker = BackOnlineToastTracker(initialGeneration = 2L)
        tracker.onNetworkCondition(NetworkCondition.NoInternet)

        assertFalse(
            tracker.onRecoveryState(
                NetworkRecoveryUiState(generation = 3L, phase = NetworkRecoveryPhase.RefreshingCatalogs),
            ),
        )
        assertFalse(
            tracker.onRecoveryState(
                NetworkRecoveryUiState(generation = 3L, phase = NetworkRecoveryPhase.Failed),
            ),
        )
        assertFalse(
            tracker.onRecoveryState(
                NetworkRecoveryUiState(generation = 4L, phase = NetworkRecoveryPhase.Completed),
                isOnline = false,
            ),
        )
        tracker.onNetworkCondition(NetworkCondition.NoInternet)
        assertTrue(
            tracker.onRecoveryState(
                NetworkRecoveryUiState(generation = 5L, phase = NetworkRecoveryPhase.Completed),
            ),
        )
        assertFalse(
            tracker.onRecoveryState(
                NetworkRecoveryUiState(generation = 5L, phase = NetworkRecoveryPhase.Completed),
            ),
        )
    }
}
