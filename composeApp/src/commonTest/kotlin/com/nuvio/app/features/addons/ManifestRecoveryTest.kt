package com.nuvio.app.features.addons

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ManifestRecoveryTest {
    @Test
    fun `unchanged manifest renews successfully without redundant provider reconciliation`() = runBlocking {
        var reconciliationCount = 0

        val result = collectManifestRecoveryResults(
            requests = mapOf(
                "https://unchanged.example/manifest.json" to
                    CompletableDeferred(ManifestRefreshOutcome.Unchanged),
            ),
            isCurrent = { true },
            onManifestEvent = { reconciliationCount += 1 },
        )

        assertEquals(setOf("https://unchanged.example/manifest.json"), result.recoveredUrls)
        assertTrue(result.changedUrls.isEmpty())
        assertEquals(0, reconciliationCount)
    }

    @Test
    fun `healthy manifest publishes before another provider settles`() = runBlocking {
        val healthy = CompletableDeferred(ManifestRefreshOutcome.Changed)
        val hanging = CompletableDeferred<ManifestRefreshOutcome>()
        val healthyPublished = CompletableDeferred<String>()

        val recovery = async {
            collectManifestRecoveryResults(
                requests = linkedMapOf(
                    "healthy" to healthy,
                    "hanging" to hanging,
                ),
                isCurrent = { true },
                onManifestEvent = { event -> healthyPublished.complete(event.manifestUrl) },
            )
        }

        assertEquals("healthy", healthyPublished.await())
        assertFalse(recovery.isCompleted)

        hanging.complete(ManifestRefreshOutcome.Failed)
        val result = recovery.await()
        assertEquals(setOf("healthy"), result.recoveredUrls)
        assertEquals(setOf("hanging"), result.failedUrls)
    }

    @Test
    fun `first launch offline request is replaced on reconnect without restart`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val singleFlight = ManifestRefreshSingleFlight()
        val url = "https://first-launch.example/manifest.json"
        val offlineRequestStarted = CompletableDeferred<Unit>()
        val offlineRequestNeverCompletes = CompletableDeferred<ManifestRefreshOutcome>()
        val offlineRequest = singleFlight.start(
            scope = scope,
            manifestUrl = url,
            identity = ManifestRefreshIdentity(
                profileId = 1,
                profileGeneration = 0L,
                reason = ManifestRefreshReason.Background,
            ),
        ) {
            offlineRequestStarted.complete(Unit)
            offlineRequestNeverCompletes.await()
        }
        offlineRequestStarted.await()

        val reconnectRequest = singleFlight.start(
            scope = scope,
            manifestUrl = url,
            identity = ManifestRefreshIdentity(
                profileId = 1,
                profileGeneration = 0L,
                reason = ManifestRefreshReason.Recovery,
                recoveryGeneration = 1L,
            ),
        ) { ManifestRefreshOutcome.Changed }
        assertNotSame(offlineRequest, reconnectRequest)

        var contentRecovered = false
        val result = collectManifestRecoveryResults(
            requests = mapOf(url to reconnectRequest),
            isCurrent = { true },
            initiallyMissingUrls = setOf(url),
            onManifestEvent = { contentRecovered = true },
        )
        offlineRequest.join()

        assertTrue(offlineRequest.isCancelled)
        assertTrue(contentRecovered)
        assertEquals(setOf(url), result.recoveredUrls)
        scope.cancel()
    }

    @Test
    fun `same recovery generation keeps one request`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val singleFlight = ManifestRefreshSingleFlight()
        val hold = CompletableDeferred<ManifestRefreshOutcome>()
        val identity = ManifestRefreshIdentity(
            profileId = 1,
            profileGeneration = 2L,
            reason = ManifestRefreshReason.Recovery,
            recoveryGeneration = 8L,
        )

        val first = singleFlight.start(scope, "manifest", identity) { hold.await() }
        val duplicate = singleFlight.start(scope, "manifest", identity) { error("must not start") }

        assertSame(first, duplicate)
        singleFlight.cancelAll()
        listOf(first, duplicate).joinAll()
        scope.cancel()
    }

    @Test
    fun `profile switch rejects a late manifest result`() = runBlocking {
        val late = CompletableDeferred<ManifestRefreshOutcome>()
        var current = true
        var published = false
        val recovery = async {
            collectManifestRecoveryResults(
                requests = mapOf("old-profile" to late),
                isCurrent = { current },
                onManifestEvent = { published = true },
            )
        }

        current = false
        late.complete(ManifestRefreshOutcome.Changed)
        val result = recovery.await()

        assertTrue(result.stale)
        assertFalse(published)
        assertTrue(result.recoveredUrls.isEmpty())
    }
}
