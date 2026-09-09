package com.nuvio.app.features.streams

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamResumeStateTest {
    @Test
    fun returningFromPlayerUsesUpdatedTimestampForBannerAndStreamSelection() {
        val launchPosition = 60_000L
        assertEquals(StreamResumeState(positionMs = 60_000L), resolve(progress(60_000L), launchPosition))

        assertEquals(StreamResumeState(positionMs = 120_000L), resolve(progress(120_000L), launchPosition))
    }

    @Test
    fun returningAfterSeekingBackUsesLatestPositionInsteadOfTheLargestPosition() {
        assertEquals(StreamResumeState(positionMs = 30_000L), resolve(progress(30_000L), initialPosition = 60_000L))
    }

    @Test
    fun updatedPercentageReplacesTheLaunchPercentage() {
        assertEquals(StreamResumeState(progressFraction = 0.2f),
            resolve(progress(0L).copy(durationMs = 0L, progressPercent = 20f), initialFraction = 0.1f))
    }

    @Test
    fun localPlaybackPositionReplacesAnOlderPercentageOnlyLaunch() {
        assertEquals(StreamResumeState(positionMs = 120_000L),
            resolve(progress(120_000L), initialFraction = 0.1f))
    }

    @Test
    fun completedPlaybackDoesNotKeepTheOldResumeTimestamp() {
        assertEquals(StreamResumeState(), resolve(progress(600_000L), initialPosition = 60_000L))
        assertEquals(StreamResumeState(),
            resolve(progress(0L).copy(durationMs = 0L, progressPercent = 100f), initialFraction = 0.1f))
    }

    @Test
    fun explicitStartFromBeginningStillSuppressesResume() {
        assertEquals(StreamResumeState(),
            resolveStreamResumeState(progress(120_000L), 60_000L, 0.1f, startFromBeginning = true))
    }

    @Test
    fun launchPositionIsAvailableBeforeSavedProgressLoads() {
        assertEquals(StreamResumeState(positionMs = 60_000L), resolve(null, initialPosition = 60_000L))
        assertEquals(StreamResumeState(progressFraction = 0.1f),
            resolve(null, initialPosition = 60_000L, initialFraction = 0.1f))
    }

    @Test
    fun savedPercentageDoesNotMixWithTheOldLaunchPosition() {
        assertEquals(StreamResumeState(progressFraction = 0.2f),
            resolve(progress(0L).copy(durationMs = 0L, progressPercent = 20f), initialPosition = 60_000L))
    }

    @Test
    fun movieProgressAlsoReplacesLaunchAndHonorsCompletionAndStartOver() {
        val movie = progress(120_000L).copy(
            contentType = "movie", parentMetaType = "movie", videoId = "test-movie",
            seasonNumber = null, episodeNumber = null,
        )
        assertEquals(StreamResumeState(positionMs = 120_000L), resolve(movie, 60_000L))
        assertEquals(StreamResumeState(), resolve(movie.copy(isCompleted = true), 60_000L))
        assertEquals(StreamResumeState(), resolveStreamResumeState(movie, 60_000L, 0.1f, true))
    }

    @Test
    fun savedZeroPositionDoesNotResurrectLaunchProgress() {
        assertEquals(StreamResumeState(), resolve(progress(0L), 60_000L, 0.1f))
    }

    @Test
    fun savedPercentTakesPrecedenceOverSavedAndLaunchTimestamps() {
        assertEquals(StreamResumeState(progressFraction = 0.3f),
            resolve(progress(120_000L).copy(progressPercent = 30f), 60_000L, 0.1f))
    }

    @Test
    fun forkEpisodeIdentityLookupUsesLatestEntryWithoutCrossingEpisodes() {
        val current = progress(30_000L).copy(lastUpdatedEpochMs = 3L)
        val other = progress(240_000L).copy(videoId = "test-series:1:2", episodeNumber = 2,
            lastUpdatedEpochMs = 4L)
        val state = com.nuvio.app.features.watchprogress.WatchProgressUiState(
            entries = listOf(progress(60_000L), current, other),
        )
        assertEquals(StreamResumeState(positionMs = 30_000L), resolve(state.progressForVideo(
            videoId = "test-series:1:1", parentMetaId = "test-series", seasonNumber = 1, episodeNumber = 1,
        ), initialPosition = 60_000L))
    }

    private fun resolve(
        progress: WatchProgressEntry?,
        initialPosition: Long? = null,
        initialFraction: Float? = null,
    ) = resolveStreamResumeState(progress, initialPosition, initialFraction, startFromBeginning = false)

    private fun progress(positionMs: Long) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = "test-series",
        parentMetaType = "series",
        videoId = "test-series:1:1",
        title = "Episode",
        seasonNumber = 1,
        episodeNumber = 1,
        lastPositionMs = positionMs,
        durationMs = 600_000L,
        lastUpdatedEpochMs = 1L,
    )
}
