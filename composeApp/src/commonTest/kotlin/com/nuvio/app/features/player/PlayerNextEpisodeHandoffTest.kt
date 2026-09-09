package com.nuvio.app.features.player

import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerNextEpisodeHandoffTest {
    @Test
    fun progressAndEndDuringDelayedHandoffPreserveOwnershipAndAcceptSuccess() = runBlocking {
        val attempt = Attempt()
        val job = attempt.start(this)
        attempt.resolving.await()
        assertEquals(listOf(null, 3, 2, 1, null), attempt.countdown)
        assertTrue(job.isActive)
        assertTrue(attempt.runtime.showNextEpisodeCard)
        assertTrue(attempt.runtime.nextEpisodeAutoPlaySearching)
        assertNull(attempt.runtime.nextEpisodeAutoPlayCountdown)
        val owner = attempt.runtime.nextEpisodeRequest
        // The actual gates used by progress, seeks, and playback-ended effects.
        for (eligible in listOf(true, false, true, true)) {
            assertFalse(attempt.runtime.showNextEpisodeIfEligible(eligible))
            attempt.runtime.playNextEpisode(automatic = true)
            assertTrue(owner === attempt.runtime.nextEpisodeRequest)
        }
        attempt.result.complete(DirectDebridPlayableResult.Success(stream))
        job.join()
        assertEquals(1, attempt.accepted)
        assertEquals(0, attempt.feedback)
        attempt.assertSettled()
    }

    @Test
    fun dismissalAfterCountdownRejectsLateSuccessAndFailure() = runBlocking {
        for (result in listOf(DirectDebridPlayableResult.Success(stream), DirectDebridPlayableResult.Stale)) {
            val attempt = Attempt()
            val job = attempt.start(this, nonCancellableResolver = true)
            attempt.resolving.await()
            attempt.runtime.dismissNextEpisode()
            attempt.result.complete(result)
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(0, attempt.accepted)
            assertEquals(0, attempt.feedback)
            attempt.assertSettled()
        }
    }

    @Test
    fun failuresAndStaleSourcesSettleOnceWithoutAutomaticRetry() = runBlocking {
        for (result in listOf(DirectDebridPlayableResult.Error, DirectDebridPlayableResult.Stale)) {
            val attempt = Attempt()
            val job = attempt.start(this)
            attempt.resolving.await()
            attempt.result.complete(result)
            job.join()
            assertEquals(0, attempt.accepted)
            assertEquals(1, attempt.feedback)
            attempt.assertSettled()
            attempt.runtime.playNextEpisode(automatic = true)
            assertNull(attempt.runtime.nextEpisodeRequest)
            // An explicit manual attempt can retry the same media.
            assertTrue(attempt.runtime.beginNextEpisodeRequest()())
        }
    }

    @Test
    fun abandonedResolverTimesOutToManualSelectionAndClearsOwnership() = runBlocking {
        val attempt = Attempt()
        val job = attempt.start(this, timeoutMs = 20, abandoned = true)
        withTimeout(10_000) { job.join() }
        assertEquals(0, attempt.accepted)
        assertEquals(1, attempt.feedback)
        attempt.assertSettled()
    }

    @Test
    fun resolverExceptionSettlesToManualSelection() = runBlocking {
        val attempt = Attempt()
        val job = attempt.start(this, throws = true)
        job.join()
        assertEquals(0, attempt.accepted)
        assertEquals(1, attempt.feedback)
        attempt.assertSettled()
    }

    @Test
    fun manualReplacementCannotBeClearedOrReceiveFeedbackFromOldResolver() = runBlocking {
        val attempt = Attempt()
        val job = attempt.start(this, nonCancellableResolver = true)
        attempt.resolving.await()
        attempt.runtime.cancelNextEpisodeAutoPlay()
        val replacement = attempt.runtime.beginNextEpisodeRequest()
        attempt.runtime.showNextEpisodeCard = true
        attempt.runtime.nextEpisodeAutoPlaySourceName = "Manual replacement"
        attempt.result.complete(DirectDebridPlayableResult.Stale)
        job.join()
        assertTrue(replacement())
        assertEquals("Manual replacement", attempt.runtime.nextEpisodeAutoPlaySourceName)
        assertTrue(attempt.runtime.showNextEpisodeCard)
        assertEquals(0, attempt.feedback)
        assertEquals(0, attempt.accepted)
    }

    @Test
    fun mediaChangeRejectsLateResolverAndAllowsNewEpisodePrompt() = runBlocking {
        val attempt = Attempt()
        val job = attempt.start(this, nonCancellableResolver = true)
        attempt.resolving.await()
        attempt.runtime.activeVideoId = "series:1:2"
        attempt.runtime.activeEpisodeNumber = 2
        attempt.runtime.resetNextEpisodeForCurrentMedia()
        assertTrue(attempt.runtime.showNextEpisodeIfEligible(true))
        attempt.result.complete(DirectDebridPlayableResult.Success(stream))
        job.join()
        assertEquals(0, attempt.accepted)
        assertEquals(0, attempt.feedback)
        assertTrue(attempt.runtime.showNextEpisodeCard)
        assertFalse(attempt.runtime.nextEpisodeCardDismissed)
    }

    private class Attempt {
        val runtime = nextEpisodeTestRuntime()
        val resolving = CompletableDeferred<Unit>()
        val result = CompletableDeferred<DirectDebridPlayableResult>()
        val countdown = mutableListOf<Int?>()
        var accepted = 0
        var feedback = 0

        fun start(
            scope: CoroutineScope,
            nonCancellableResolver: Boolean = false,
            timeoutMs: Long = NEXT_EPISODE_HARD_TIMEOUT_MS,
            abandoned: Boolean = false,
            throws: Boolean = false,
        ): Job {
            assertTrue(runtime.showNextEpisodeIfEligible(true))
            val request = runtime.beginNextEpisodeRequest()
            return scope.launchPlayerNextEpisodeAutoPlay(
                previousJob = null, automatic = true, isCurrentRequest = request,
                nextEpisodeInfo = NextEpisodeInfo(videoId = episode.id, season = 1, episode = 2,
                    title = "Next", thumbnail = null, overview = null, released = null,
                    hasAired = true, isWatched = false, unairedMessage = null),
                allEpisodes = listOf(episode), parentMetaId = "series", parentMetaType = "series",
                contentType = "series", currentStreamBingeGroup = null,
                settings = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
                    streamAutoPlayTimeoutSeconds = 0),
                onDownloadedEpisodeSelected = { _, _ -> error("Expected stream selection") },
                onEpisodeStreamSelected = { selected, video ->
                    handoffNextEpisodeStream(selected, video, request,
                        onResolved = { accepted++ }, onUnresolved = { feedback++ },
                        resolve = { _, _ ->
                            resolving.complete(Unit)
                            if (throws) error("Resolver failed")
                            if (abandoned) awaitCancellation()
                            if (nonCancellableResolver) withContext(NonCancellable) { result.await() }
                            else result.await()
                        })
                },
                onManualSelectionRequired = { if (request()) feedback++ },
                onSearchingChanged = { if (request()) runtime.nextEpisodeAutoPlaySearching = it },
                onSourceNameChanged = { if (request()) runtime.nextEpisodeAutoPlaySourceName = it },
                onCountdownChanged = {
                    if (request()) { countdown += it; runtime.nextEpisodeAutoPlayCountdown = it }
                },
                onNextEpisodeCardVisibleChanged = { if (request()) runtime.showNextEpisodeCard = it },
                onRequestFinished = { runtime.finishNextEpisodeRequest(request) },
                handoffTimeoutMs = timeoutMs,
                loadEpisodeStreams = { _, _ -> MutableStateFlow(StreamsUiState(groups = listOf(
                    AddonStreamGroup(addonName = "Fixture", addonId = "fixture", streams = listOf(stream)),
                ))) },
                findDownloadedEpisode = { null },
            )!!.also { runtime.nextEpisodeAutoPlayJob = it }
        }

        fun assertSettled() {
            assertNull(runtime.nextEpisodeRequest)
            assertNull(runtime.nextEpisodeAutoPlayJob)
            assertNull(runtime.nextEpisodeAutoPlayCountdown)
            assertNull(runtime.nextEpisodeAutoPlaySourceName)
            assertFalse(runtime.nextEpisodeAutoPlaySearching)
            assertFalse(runtime.showNextEpisodeCard)
            assertFalse(runtime.showNextEpisodeIfEligible(true))
        }
    }

    private companion object {
        val episode = MetaVideo(id = "series:1:2", title = "Next", season = 1, episode = 2)
        val stream = StreamItem(name = "Fixture", url = "https://example.invalid/next.mp4",
            addonName = "Fixture", addonId = "fixture")
    }
}
