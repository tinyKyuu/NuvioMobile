package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import kotlinx.coroutines.CoroutineScope
import com.nuvio.app.features.player.skip.NextEpisodeThresholdMode
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerNextEpisodeDismissalTest {
    @Test
    fun dismissalDuringSearchCancelsWorkAndRejectsLateResults() = runBlocking {
        val runtime = runtime()
        val request = runtime.beginNextEpisodeRequest()
        runtime.showNextEpisodeCard = true
        runtime.nextEpisodeAutoPlaySearching = true
        val job = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        runtime.nextEpisodeAutoPlayJob = job
        runtime.dismissNextEpisode()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(request())
        assertDismissed(runtime)
    }

    @Test
    fun dismissalDuringTheSharedStreamAndDownloadCountdownCannotPlay() = runBlocking {
        for (source in listOf("Addon", "Downloaded")) {
            val runtime = runtime()
            val request = runtime.beginNextEpisodeRequest()
            val started = CompletableDeferred<Unit>()
            var played = false
            val job = launch {
                awaitNextEpisodeCountdown(source, request,
                    { runtime.nextEpisodeAutoPlaySourceName = it },
                    { runtime.nextEpisodeAutoPlayCountdown = it; started.complete(Unit) })
                if (request()) played = true
            }
            runtime.nextEpisodeAutoPlayJob = job
            started.await()
            assertEquals(3, runtime.nextEpisodeAutoPlayCountdown)
            runtime.dismissNextEpisode()
            job.join()
            assertFalse(played)
            assertTrue(job.isCancelled)
            assertDismissed(runtime)
        }
    }

    @Test
    fun laterProgressBackwardAndForwardSeeksAndEndCannotReopen() {
        val runtime = runtime()
        assertTrue(runtime.showNextEpisodeIfEligible(threshold(980_000)))
        runtime.dismissNextEpisode()
        for (position in listOf(990_000L, 100_000L, 975_000L, 999_000L, 1_000_000L)) {
            assertFalse(runtime.showNextEpisodeIfEligible(threshold(position)))
            assertDismissed(runtime)
        }
        // Same gate used by the independent playback-ended effect.
        assertFalse(runtime.showNextEpisodeIfEligible(true))
        runtime.playNextEpisode(automatic = true) // no scope needed: dismissed requests must stop here
        assertDismissed(runtime)
    }

    @Test
    fun changingAnyPartOfMediaIdentityInvalidatesAnInFlightRequest() {
        for (change in listOf<(PlayerScreenRuntime) -> Unit>(
            { it.activeVideoId = "another-video" },
            { it.activeSeasonNumber = 2 },
            { it.activeEpisodeNumber = 2 },
        )) {
            val runtime = runtime()
            val request = runtime.beginNextEpisodeRequest()
            change(runtime)
            assertFalse(request())
        }
    }

    @Test
    fun mediaResetCancelsOldWorkAndMakesNextPromptEligible() = runBlocking {
        val runtime = runtime()
        val request = runtime.beginNextEpisodeRequest()
        runtime.dismissNextEpisode()
        runtime.activeVideoId = "series:1:2"
        runtime.activeEpisodeNumber = 2
        runtime.resetNextEpisodeForCurrentMedia()
        assertFalse(request())
        assertFalse(runtime.nextEpisodeCardDismissed)
        assertTrue(runtime.showNextEpisodeIfEligible(true))
        assertFalse(runtime.showNextEpisodeIfEligible(true))
    }

    @Test
    fun sourceChangesDoNotResetDismissalAndManualRequestsRemainPossible() {
        val runtime = runtime()
        val old = runtime.beginNextEpisodeRequest()
        runtime.dismissNextEpisode()
        runtime.activeSourceUrl = "file:///local-episode.mp4"
        assertFalse(runtime.showNextEpisodeIfEligible(true))
        val manual = runtime.beginNextEpisodeRequest()
        assertFalse(old())
        assertTrue(manual())
        assertTrue(runtime.nextEpisodeCardDismissed)
        val replacement = runtime.beginNextEpisodeRequest()
        assertFalse(manual())
        assertTrue(replacement())
    }

    @Test
    fun expiredRequestCannotPublishCountdown() = runBlocking {
        val runtime = runtime()
        val request = runtime.beginNextEpisodeRequest()
        runtime.dismissNextEpisode()
        var publications = 0
        awaitNextEpisodeCountdown("Late source", request, { publications++ }, { publications++ })
        assertEquals(0, publications)
    }

    @Test
    fun downloadedAutomaticSelectionWaitsAndCanBeCancelledButManualSelectionIsImmediate() = runBlocking {
        val runtime = runtime()
        val request = runtime.beginNextEpisodeRequest()
        val started = CompletableDeferred<Unit>()
        var plays = 0
        val job = launchDownloaded(request, automatic = true,
            onCountdown = { started.complete(Unit) }, onPlay = { plays++ })!!
        runtime.nextEpisodeAutoPlayJob = job
        started.await()
        assertEquals(0, plays)
        runtime.dismissNextEpisode()
        job.join()
        assertEquals(0, plays)
        assertTrue(job.isCancelled)
        val manual = runtime.beginNextEpisodeRequest()
        assertNull(launchDownloaded(manual, automatic = false,
            onCountdown = { error("Manual downloaded playback should be immediate") }, onPlay = { plays++ }))
        assertEquals(1, plays)
    }

    @Test
    fun downloadedAutomaticSelectionStillPlaysWhenNotDismissed() = runBlocking {
        val values = mutableListOf<Int?>()
        var plays = 0
        launchDownloaded({ true }, automatic = true,
            onCountdown = { values += it }, onPlay = { plays++ })!!.join()
        assertEquals(listOf(3, 2, 1, null), values)
        assertEquals(1, plays)
    }

    private fun CoroutineScope.launchDownloaded(
        request: () -> Boolean,
        automatic: Boolean,
        onCountdown: (Int?) -> Unit,
        onPlay: () -> Unit,
    ) = launchPlayerNextEpisodeAutoPlay(
        previousJob = null, automatic = automatic, downloadedSourceName = "Downloaded",
        isCurrentRequest = request,
        nextEpisodeInfo = NextEpisodeInfo(videoId = "series:1:2", season = 1, episode = 2,
            title = "Next", thumbnail = null, overview = null, released = null, hasAired = true,
            isWatched = false, unairedMessage = null),
        allEpisodes = listOf(MetaVideo(id = "series:1:2", title = "Next", season = 1, episode = 2)),
        parentMetaId = "series", parentMetaType = "series", contentType = "series",
        settings = PlayerSettingsUiState(), currentStreamBingeGroup = null,
        onDownloadedEpisodeSelected = { _, _ -> onPlay() },
        onEpisodeStreamSelected = { _, _ -> error("Downloaded path must not fetch a stream") },
        onManualSelectionRequired = { error("Downloaded path must not open sources") },
        onSearchingChanged = { assertFalse(it) }, onSourceNameChanged = {},
        onCountdownChanged = onCountdown, onNextEpisodeCardVisibleChanged = {},
        findDownloadedEpisode = { episode -> DownloadItem(
            id = "local", contentType = "series", parentMetaId = "series", parentMetaType = "series",
            videoId = episode.id, title = "Fixture", seasonNumber = 1, episodeNumber = 2,
            streamTitle = "Local", providerName = "", sourceUrl = "", fileName = "next.mp4",
            localFileUri = "file:///next.mp4", status = DownloadStatus.Completed,
            createdAtEpochMs = 1, updatedAtEpochMs = 1,
        ) },
    )

    private fun threshold(position: Long) = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = position, durationMs = 1_000_000, skipIntervals = emptyList(),
        thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
        thresholdPercent = 97f, thresholdMinutesBeforeEnd = 1f,
    )

    private fun assertDismissed(runtime: PlayerScreenRuntime) {
        assertTrue(runtime.nextEpisodeCardDismissed)
        assertFalse(runtime.showNextEpisodeCard)
        assertFalse(runtime.nextEpisodeAutoPlaySearching)
        assertNull(runtime.nextEpisodeAutoPlaySourceName)
        assertNull(runtime.nextEpisodeAutoPlayCountdown)
        assertNull(runtime.nextEpisodeAutoPlayJob)
    }

    private fun runtime() = PlayerScreenRuntime(PlayerScreenArgs(
        profileId = 1, title = "Fixture", sourceUrl = "file:///episode.mp4",
        sourceAudioUrl = null, sourceHeaders = emptyMap(), sourceResponseHeaders = emptyMap(),
        streamType = null, providerName = "Fixture", streamTitle = "Episode", streamSubtitle = null,
        initialBingeGroup = null, pauseDescription = null, onBack = {},
        onOpenInExternalPlayer = null, onOpenExternalUrl = null, onOpenDownloads = null,
        modifier = Modifier, logo = null, poster = null, background = null,
        seasonNumber = 1, episodeNumber = 1, episodeTitle = "Episode 1", episodeThumbnail = null,
        contentType = "series", videoId = "series:1:1", parentMetaId = "series", parentMetaType = "series",
        providerAddonId = null, torrentInfoHash = null, torrentFileIdx = null, torrentFilename = null,
        torrentTrackers = emptyList(), initialPositionMs = 0, initialProgressFraction = null,
    ))
}
