package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal expect fun withIsolatedTrackPreferences(block: (String) -> Unit)

class SubtitlePreferenceRestorationTest {
    private val oldUrl = "https://example.com/s1e1.en.srt"
    private val newUrl = "https://example.com/s1e2.en.srt"

    @Test
    fun previousEpisodeUrlIsNeverRequestedAndCurrentLanguageIsResolved() = withIsolatedTrackPreferences { id ->
        val runtime = runtime(id)
        runtime.persistAddonSubtitlePreference(addon(oldUrl, "episode-1"))
        advance(runtime)
        val controller = runtime.playerController as RecordingController
        runtime.restorePersistedTrackPreferenceIfNeeded()
        assertTrue(controller.urls.isEmpty())
        // Even an old response arriving after the transition must not be selected.
        runtime.addonSubtitles = listOf(addon(oldUrl, "episode-1"))
        runtime.refreshTracks()
        assertTrue(controller.urls.isEmpty())
        runtime.addonSubtitles = listOf(addon(newUrl, "episode-2"))
        runtime.refreshTracks()
        assertEquals(listOf(newUrl), controller.urls)
        assertEquals(newUrl, runtime.selectedAddonSubtitleId)
        assertEquals(runtime.subtitleVideoKey, PlayerTrackPreferenceStorage.load(id)?.addonSubtitleVideoKey)
        assertEquals(newUrl, PlayerTrackPreferenceStorage.load(id)?.addonSubtitleUrl)
    }

    @Test
    fun knownPreviousEpisodeFileIsRejectedEvenIfRepeatedInNewResults() = withIsolatedTrackPreferences { id ->
        val runtime = runtime(id)
        runtime.persistAddonSubtitlePreference(addon(oldUrl, "episode-1"))
        advance(runtime)
        runtime.addonSubtitles = listOf(addon(oldUrl, "episode-2"), addon(newUrl, "episode-2"))
        runtime.refreshTracks()
        assertEquals(listOf(newUrl), (runtime.playerController as RecordingController).urls)
    }

    @Test
    fun sameVideoSurvivesHeaderAndSourceRecreationAndLatePreferredAddon() = withIsolatedTrackPreferences { id ->
        val runtime = runtime(id)
        runtime.persistAddonSubtitlePreference(addon(oldUrl, "episode-1"))
        for (source in listOf(runtime.activeSourceUrl, "https://example.com/alternate.mp4")) {
            runtime.activeSourceUrl = source
            runtime.activeSourceHeaders = mapOf("X-Test" to source)
            runtime.resetSubtitleSelectionForSourceChange()
            runtime.refreshTracks()
            runtime.addonSubtitles = listOf(addon("https://example.com/preferred.fr.srt", "episode-1", "fr"))
            runtime.refreshTracks()
            assertEquals(oldUrl, runtime.selectedAddonSubtitleId)
            assertTrue(runtime.isUserExplicitSubtitleSelection)
        }
        assertEquals(listOf(oldUrl, oldUrl), (runtime.playerController as RecordingController).urls)
    }

    @Test
    fun legacyRecordWaitsForCurrentVideoResultsInsteadOfLoadingUnknownUrl() = withIsolatedTrackPreferences { id ->
        val runtime = runtime(id)
        PlayerTrackPreferenceStorage.save(id, savedAddon())
        advance(runtime)
        runtime.refreshTracks()
        assertTrue((runtime.playerController as RecordingController).urls.isEmpty())
        runtime.addonSubtitles = listOf(addon(newUrl, null))
        runtime.refreshTracks()
        assertNull(runtime.selectedAddonSubtitleId)
        runtime.addonSubtitles = listOf(addon(newUrl, "episode-2"))
        runtime.refreshTracks()
        assertEquals(listOf(newUrl), (runtime.playerController as RecordingController).urls)
    }

    @Test
    fun legacyUrlCanRestoreOnlyWhenConfirmedByCurrentVideoResponse() = withIsolatedTrackPreferences { id ->
        val runtime = runtime(id)
        PlayerTrackPreferenceStorage.save(id, savedAddon())
        runtime.addonSubtitles = listOf(addon(oldUrl, "episode-1"))
        runtime.refreshTracks()
        assertEquals(listOf(oldUrl), (runtime.playerController as RecordingController).urls)
        assertEquals(runtime.subtitleVideoKey, PlayerTrackPreferenceStorage.load(id)?.addonSubtitleVideoKey)
    }

    @Test
    fun manualChoiceCancelsPendingEpisodeRestoration() = withIsolatedTrackPreferences { id ->
        val runtime = runtime(id)
        PlayerTrackPreferenceStorage.save(id, savedAddon())
        advance(runtime)
        runtime.refreshTracks()
        runtime.isUserExplicitSubtitleSelection = true
        runtime.selectedSubtitleIndex = -1
        runtime.preferredSubtitleSelectionApplied = true
        runtime.addonSubtitles = listOf(addon(newUrl, "episode-2"))
        runtime.refreshTracks()
        assertTrue((runtime.playerController as RecordingController).urls.isEmpty())
        assertNull(runtime.pendingAddonSubtitlePreference)
    }

    @Test
    fun explicitOffRemainsOffAcrossEpisodes() = withIsolatedTrackPreferences { id ->
        val runtime = runtime(id)
        runtime.persistInternalSubtitlePreference(null)
        advance(runtime)
        runtime.addonSubtitles = listOf(addon(newUrl, "episode-2", "fr"))
        runtime.refreshTracks()
        assertNull(runtime.selectedAddonSubtitleId)
        assertFalse(runtime.useCustomSubtitles)
        assertTrue((runtime.playerController as RecordingController).urls.isEmpty())
        assertEquals(listOf(-1), (runtime.playerController as RecordingController).indices)
    }

    @Test
    fun manualLanguageRestoresNewEpisodeIndexAndSurvivesLateAddon() = withIsolatedTrackPreferences { id ->
        val runtime = runtime(id)
        runtime.persistInternalSubtitlePreference(SubtitleTrack(7, "old-en", "English", "en"))
        advance(runtime)
        val controller = runtime.playerController as RecordingController
        controller.tracks = listOf(SubtitleTrack(3, "new-en", "English", "en"))
        runtime.refreshTracks()
        runtime.addonSubtitles = listOf(addon(newUrl, "episode-2", "fr"))
        runtime.refreshTracks()
        assertEquals(3, runtime.selectedSubtitleIndex)
        assertEquals(listOf(3), controller.indices)
        assertTrue(controller.urls.isEmpty())
    }

    @Test
    fun storageRoundTripsOwnershipAndExistingFieldsAndClearsOptionalOwnership() = withIsolatedTrackPreferences { id ->
        val saved = savedAddon().copy(addonSubtitleVideoKey = "owned-video", audioLanguage = "ja",
            audioName = "Japanese", audioTrackId = "audio-2", subtitleIsForced = false)
        PlayerTrackPreferenceStorage.save(id, saved)
        assertEquals(saved, PlayerTrackPreferenceStorage.load(id))
        val legacy = saved.copy(addonSubtitleVideoKey = null)
        PlayerTrackPreferenceStorage.save(id, legacy)
        assertEquals(legacy, PlayerTrackPreferenceStorage.load(id))
        val runtime = runtime(id)
        runtime.persistInternalSubtitlePreference(null)
        val disabled = PlayerTrackPreferenceStorage.load(id)!!
        assertNull(disabled.addonSubtitleVideoKey)
        assertNull(disabled.addonSubtitleUrl)
        assertEquals("ja", disabled.audioLanguage)
        assertEquals(PersistedSubtitleSelectionType.DISABLED, disabled.subtitleType)
    }

    @Test
    fun audioUpdatesPreserveAddonOwnership() = withIsolatedTrackPreferences { id ->
        val runtime = runtime(id)
        runtime.persistAddonSubtitlePreference(addon(oldUrl, "episode-1"))
        runtime.persistAudioPreference(AudioTrack(1, "audio", "Japanese", "ja"))
        val loaded = PlayerTrackPreferenceStorage.load(id)!!
        assertEquals(runtime.subtitleVideoKey, loaded.addonSubtitleVideoKey)
        assertEquals(oldUrl, loaded.addonSubtitleUrl)
        assertEquals("ja", loaded.audioLanguage)
    }

    @Test
    fun manualLanguageRestorationWaitsForNewTracks() = withIsolatedTrackPreferences { id ->
        val runtime = runtime(id)
        runtime.persistInternalSubtitlePreference(SubtitleTrack(7, "old-en", "English", "en"))
        advance(runtime)
        runtime.refreshTracks()
        assertFalse(runtime.trackPreferenceRestoreApplied)
        val controller = runtime.playerController as RecordingController
        controller.tracks = listOf(SubtitleTrack(3, "new-en", "English", "en"))
        runtime.refreshTracks()
        assertEquals(3, runtime.selectedSubtitleIndex)
        assertTrue(runtime.isUserExplicitSubtitleSelection)
    }

    @Test
    fun completedEmptyFetchFallsBackToSavedLanguageAndActualEmbeddedIndex() = withIsolatedTrackPreferences { id ->
        val runtime = pendingNextEpisode(id)
        val controller = runtime.playerController as RecordingController
        controller.tracks = listOf(SubtitleTrack(5, "new-en", "English", "en"),
            SubtitleTrack(8, "new-fr", "French", "fr"))
        runtime.addonSubtitleFetchState = AddonSubtitleFetchState("episode-2", isComplete = true)
        repeat(12) { runtime.refreshTracks() }
        assertNull(runtime.pendingAddonSubtitlePreference)
        assertEquals(5, runtime.selectedSubtitleIndex)
        assertEquals(listOf(5), controller.indices)
        assertTrue(controller.urls.isEmpty())
    }

    @Test
    fun completedUnmatchedResultsCannotReintroduceOldEpisodeUrl() = withIsolatedTrackPreferences { id ->
        val runtime = pendingNextEpisode(id)
        val controller = runtime.playerController as RecordingController
        controller.tracks = listOf(SubtitleTrack(0, "new-en", "English", "en"))
        runtime.addonSubtitles = listOf(addon(oldUrl, "episode-1"), addon(oldUrl, "episode-2"),
            addon("https://example.com/fr.srt", "episode-2", "fr"))
        runtime.addonSubtitleFetchState = AddonSubtitleFetchState("episode-2", isComplete = true)
        runtime.refreshTracks()
        assertEquals(0, runtime.selectedSubtitleIndex)
        assertNull(runtime.pendingAddonSubtitlePreference)
        assertTrue(controller.urls.isEmpty())
    }

    @Test
    fun initialFalseLoadingAndOtherVideoCompletionDoNotReleasePendingWait() = withIsolatedTrackPreferences { id ->
        val runtime = pendingNextEpisode(id)
        val controller = runtime.playerController as RecordingController
        controller.tracks = listOf(SubtitleTrack(0, "new-en", "English", "en"))
        for (state in listOf(AddonSubtitleFetchState(),
            AddonSubtitleFetchState("episode-1", isComplete = true), AddonSubtitleFetchState("episode-2"))) {
            runtime.addonSubtitleFetchState = state
            runtime.isLoadingAddonSubtitles = false
            repeat(12) { runtime.refreshTracks() }
            assertEquals(-1, runtime.selectedSubtitleIndex)
            assertTrue(runtime.pendingAddonSubtitlePreference != null)
        }
        runtime.isLoadingAddonSubtitles = true
        runtime.refreshTracks()
        assertTrue(controller.indices.isEmpty())
        runtime.addonSubtitles = listOf(addon(newUrl, "episode-2"))
        runtime.refreshTracks()
        assertEquals(listOf(newUrl), controller.urls)
    }

    @Test
    fun failedOrNoAddonFetchCompletionReleasesWaitWithoutAUrl() = withIsolatedTrackPreferences { id ->
        // The repository reports the same terminal contract after errors,
        // timeouts or finding no compatible addons, even with no result emission.
        val runtime = pendingNextEpisode(id)
        val controller = runtime.playerController as RecordingController
        controller.tracks = listOf(SubtitleTrack(0, "new-en", "English", "en"))
        runtime.addonSubtitleFetchState = AddonSubtitleFetchState("episode-2", isComplete = true)
        runtime.refreshTracks()
        assertNull(runtime.pendingAddonSubtitlePreference)
        assertEquals(listOf(0), controller.indices)
        assertTrue(controller.urls.isEmpty())
    }

    @Test
    fun unavailableVideoFetchCanStillUseEmbeddedSavedLanguage() = withIsolatedTrackPreferences { id ->
        val runtime = pendingNextEpisode(id)
        runtime.activeVideoId = null
        val controller = runtime.playerController as RecordingController
        controller.tracks = listOf(SubtitleTrack(0, "new-en", "English", "en"))
        runtime.addonSubtitleFetchState = AddonSubtitleFetchState(null, isComplete = true)
        runtime.refreshTracks()
        assertEquals(0, runtime.selectedSubtitleIndex)
        assertNull(runtime.pendingAddonSubtitlePreference)
        assertTrue(controller.urls.isEmpty())
    }

    @Test
    fun completedFallbackCanUseTracksOrCurrentResultsArrivingLater() = withIsolatedTrackPreferences { id ->
        val runtime = pendingNextEpisode(id)
        val controller = runtime.playerController as RecordingController
        runtime.addonSubtitleFetchState = AddonSubtitleFetchState("episode-2", isComplete = true)
        runtime.refreshTracks()
        assertNull(runtime.pendingAddonSubtitlePreference)
        assertFalse(runtime.preferredSubtitleSelectionApplied)
        runtime.addonSubtitles = listOf(addon(oldUrl, "episode-1"))
        runtime.refreshTracks()
        assertTrue(controller.urls.isEmpty())
        runtime.addonSubtitles = listOf(addon(newUrl, "episode-2"))
        runtime.refreshTracks()
        assertEquals(listOf(newUrl), controller.urls)
    }

    @Test
    fun userOffAfterFallbackIsProtectedFromLateResults() = withIsolatedTrackPreferences { id ->
        val runtime = pendingNextEpisode(id)
        val controller = runtime.playerController as RecordingController
        runtime.addonSubtitleFetchState = AddonSubtitleFetchState("episode-2", isComplete = true)
        runtime.refreshTracks()
        runtime.persistInternalSubtitlePreference(null)
        runtime.isUserExplicitSubtitleSelection = true
        runtime.preferredSubtitleSelectionApplied = true
        runtime.addonSubtitles = listOf(addon(newUrl, "episode-2"))
        runtime.refreshTracks()
        assertEquals(-1, runtime.selectedSubtitleIndex)
        assertTrue(controller.urls.isEmpty())
    }

    private fun pendingNextEpisode(id: String): PlayerScreenRuntime = runtime(id).apply {
        persistAddonSubtitlePreference(addon(oldUrl, "episode-1"))
        advance(this)
        refreshTracks()
    }

    @Test
    fun embeddedTrackArrivingAfterTerminalEmptySnapshotIsStillSelected() = withIsolatedTrackPreferences { id ->
        val runtime = pendingNextEpisode(id)
        runtime.addonSubtitleFetchState = AddonSubtitleFetchState("episode-2", isComplete = true)
        runtime.refreshTracks()
        val controller = runtime.playerController as RecordingController
        controller.tracks = listOf(SubtitleTrack(4, "new-en", "English", "en"))
        runtime.refreshTracks()
        assertEquals(listOf(4), controller.indices)
        assertTrue(controller.urls.isEmpty())
    }

    @Test
    fun repositoryReportsUnavailableCompletionAndClearReturnsToNotStarted() {
        try {
            SubtitleRepository.fetchAddonSubtitles("", "unavailable-video")
            assertEquals(AddonSubtitleFetchState("unavailable-video", true), SubtitleRepository.fetchState.value)
            assertFalse(SubtitleRepository.isLoading.value)
            SubtitleRepository.fetchAddonSubtitles("series", null)
            assertEquals(AddonSubtitleFetchState(null, true), SubtitleRepository.fetchState.value)
        } finally {
            SubtitleRepository.clear()
        }
        assertEquals(AddonSubtitleFetchState(), SubtitleRepository.fetchState.value)
    }

    private fun savedAddon() = PersistedPlayerTrackPreference(
        subtitleType = PersistedSubtitleSelectionType.ADDON, subtitleLanguage = "en",
        addonSubtitleId = "en", addonSubtitleUrl = oldUrl, addonSubtitleAddonName = "Test addon",
    )

    private fun addon(url: String, video: String?, language: String = "en") = AddonSubtitle(
        id = language, url = url, language = language, display = language,
        addonName = "Test addon", sourceVideoId = video,
    )

    private fun advance(runtime: PlayerScreenRuntime) {
        runtime.activeVideoId = "episode-2"
        runtime.activeEpisodeNumber = 2
        runtime.activeSourceUrl = "https://example.com/s1e2.mp4"
        runtime.resetIdentityStateIfNeeded()
        runtime.resetSubtitleSelectionForSourceChange()
    }

    private fun runtime(id: String) = PlayerScreenRuntime(PlayerScreenArgs(
        profileId = 1, title = "Subtitle regression", sourceUrl = "https://example.com/s1e1.mp4",
        sourceAudioUrl = null, sourceHeaders = emptyMap(), sourceResponseHeaders = emptyMap(),
        streamType = null, providerName = "Provider", streamTitle = "Source", streamSubtitle = null,
        initialBingeGroup = null, pauseDescription = null, onBack = {}, onOpenInExternalPlayer = null,
        onOpenExternalUrl = null, onOpenDownloads = null, modifier = Modifier, logo = null,
        poster = null, background = null, seasonNumber = 1, episodeNumber = 1, episodeTitle = null,
        episodeThumbnail = null, contentType = "series", videoId = "episode-1", parentMetaId = id,
        parentMetaType = "series", providerAddonId = null, torrentInfoHash = null,
        torrentFileIdx = null, torrentFilename = null, torrentTrackers = emptyList(),
        initialPositionMs = 0L, initialProgressFraction = null,
    )).apply {
        playerController = RecordingController()
        playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        playerSettingsUiState = playerSettingsUiState.copy(preferredSubtitleLanguage = "fr",
            subtitleStyle = subtitleStyle.copy(useForcedSubtitles = false))
    }

    private class RecordingController : PlayerEngineController {
        val urls = mutableListOf<String>()
        val indices = mutableListOf<Int>()
        var tracks = emptyList<SubtitleTrack>()
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun seekBy(offsetMs: Long) {}
        override fun retry() {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = tracks
        override fun selectAudioTrack(index: Int) {}
        override fun selectSubtitleTrack(index: Int) { indices += index }
        override fun setSubtitleUri(url: String) { urls += url }
        override fun clearExternalSubtitle() {}
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) { indices += trackIndex }
    }
}
