package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleRepositoryConcurrencyTest {
    @Test
    fun cancelledWorkerCannotPublishOverSynchronousUnavailableReplacement() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = heldWorker("old", entered, release)
            try {
                entered.await()
                SubtitleRepository.fetchAddonSubtitles("", "replacement")
                release.complete(Unit)
                old.join()
                assertEquals(AddonSubtitleFetchState("replacement", true), SubtitleRepository.fetchState.value)
                assertFalse(SubtitleRepository.isLoading.value)
                assertTrue(SubtitleRepository.addonSubtitles.value.isEmpty())
                assertNull(SubtitleRepository.error.value)
            } finally {
                release.complete(Unit)
                SubtitleRepository.clear()
                old.join()
            }
        }
    }

    @Test
    fun sameVideoReplacementOwnsResultsErrorsLoadingAndCompletion() = runBlocking {
        withTimeout(5_000) {
            val oldEntered = CompletableDeferred<Unit>()
            val oldRelease = CompletableDeferred<Unit>()
            val old = heldWorker("same-video", oldEntered, oldRelease)
            val currentEntered = CompletableDeferred<Unit>()
            val currentRelease = CompletableDeferred<Unit>()
            try {
                oldEntered.await()
                val current = SubtitleRepository.startFetch("same-video", true) { publish, error ->
                    publish(listOf(subtitle("current")))
                    error("current warning")
                    currentEntered.complete(Unit)
                    currentRelease.await()
                }!!
                currentEntered.await()
                oldRelease.complete(Unit)
                old.join()
                assertEquals(AddonSubtitleFetchState("same-video", false), SubtitleRepository.fetchState.value)
                assertTrue(SubtitleRepository.isLoading.value)
                assertEquals(listOf(subtitle("current")), SubtitleRepository.addonSubtitles.value)
                assertEquals("current warning", SubtitleRepository.error.value)
                currentRelease.complete(Unit)
                current.join()
                assertEquals(AddonSubtitleFetchState("same-video", true), SubtitleRepository.fetchState.value)
                assertFalse(SubtitleRepository.isLoading.value)
            } finally {
                oldRelease.complete(Unit)
                currentRelease.complete(Unit)
                SubtitleRepository.clear()
                old.join()
            }
        }
    }

    @Test
    fun clearInvalidatesHeldWorkersBeforeTheyPublish() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = heldWorker("cleared", entered, release)
            try {
                entered.await()
                SubtitleRepository.clear()
                release.complete(Unit)
                old.join()
                assertEquals(AddonSubtitleFetchState(), SubtitleRepository.fetchState.value)
                assertFalse(SubtitleRepository.isLoading.value)
                assertTrue(SubtitleRepository.addonSubtitles.value.isEmpty())
                assertNull(SubtitleRepository.error.value)
            } finally {
                release.complete(Unit)
                SubtitleRepository.clear()
                old.join()
            }
        }
    }

    @Test
    fun currentWorkerFailureStillPublishesTerminalState() = runBlocking {
        try {
            val job = SubtitleRepository.startFetch("failed", true) { _, _ ->
                throw IllegalStateException("Test fetch failure")
            }!!
            withTimeout(5_000) { job.join() }
            assertEquals(AddonSubtitleFetchState("failed", true), SubtitleRepository.fetchState.value)
            assertFalse(SubtitleRepository.isLoading.value)
        } finally {
            SubtitleRepository.clear()
        }
    }

    @Test
    fun actualNoAddonRequestCompletesWithoutNetworkOrPersistedAddonInitialization() = runBlocking {
        assertTrue(AddonRepository.uiState.value.addons.isEmpty())
        try {
            SubtitleRepository.fetchAddonSubtitles("series", "no-addons")
            withTimeout(5_000) {
                SubtitleRepository.fetchState.first { it == AddonSubtitleFetchState("no-addons", true) }
            }
            assertFalse(SubtitleRepository.isLoading.value)
            assertTrue(SubtitleRepository.addonSubtitles.value.isEmpty())
        } finally {
            SubtitleRepository.clear()
        }
    }

    @Test
    fun repeatedActualReplacementsNeverEndWithCancelledVideoCompletion() = runBlocking {
        assertTrue(AddonRepository.uiState.value.addons.isEmpty())
        try {
            repeat(1000) { iteration ->
                SubtitleRepository.fetchAddonSubtitles("series", "cancelled-$iteration")
                SubtitleRepository.fetchAddonSubtitles("", "replacement-$iteration")
                withContext(Dispatchers.Default) { delay(5) }
                assertEquals(AddonSubtitleFetchState("replacement-$iteration", true), SubtitleRepository.fetchState.value)
                assertFalse(SubtitleRepository.isLoading.value)
            }
        } finally {
            SubtitleRepository.clear()
        }
    }

    private fun heldWorker(video: String, entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) =
        SubtitleRepository.startFetch(video, true) { publish, error ->
            // The barrier deliberately outlives cancellation, as a completed network
            // response or a worker already entering finally can do.
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                publish(listOf(subtitle("stale")))
                error("stale error")
            }
        }!!

    private fun subtitle(id: String) = AddonSubtitle(id, "https://example.test/$id.srt", "en", id)
}
