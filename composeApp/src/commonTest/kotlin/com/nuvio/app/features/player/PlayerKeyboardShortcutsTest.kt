package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.*

class PlayerKeyboardShortcutsTest {
    @Test
    fun mapsOnlyApprovedWireCodes() {
        PlayerKeyboardShortcut.entries.forEach { shortcut ->
            assertEquals(shortcut, PlayerKeyboardShortcut.fromWireCode(shortcut.wireCode))
        }
        assertNull(PlayerKeyboardShortcut.fromWireCode("play"))
    }

    @Test
    fun emitsOnceUntilReleaseAndRejectsModifiersAndRepeats() {
        val tracker = PlayerKeyboardPressTracker()
        val emitted = mutableListOf<PlayerKeyboardShortcut>()
        fun down(modified: Boolean = false, repeated: Boolean = false) = tracker.handle(
            shortcut = PlayerKeyboardShortcut.SeekForward,
            isDown = true,
            isUp = false,
            enabled = true,
            modified = modified,
            repeated = repeated,
            onShortcut = emitted::add,
        )

        assertTrue(down())
        assertTrue(down())
        assertTrue(down(repeated = true))
        assertEquals(listOf(PlayerKeyboardShortcut.SeekForward), emitted)
        assertTrue(tracker.handle(PlayerKeyboardShortcut.SeekForward, false, true, true, false, false, emitted::add))
        assertFalse(down(modified = true))
        assertTrue(down())
        assertEquals(2, emitted.size)
    }

    @Test
    fun disabledInputIsNotConsumedAndFocusResetAllowsNextPress() {
        val tracker = PlayerKeyboardPressTracker()
        var calls = 0
        assertFalse(tracker.handle(PlayerKeyboardShortcut.TogglePlayback, true, false, false, false, false) { calls++ })
        assertTrue(tracker.handle(PlayerKeyboardShortcut.TogglePlayback, true, false, true, false, false) { calls++ })
        tracker.reset()
        assertTrue(tracker.handle(PlayerKeyboardShortcut.TogglePlayback, true, false, true, false, false) { calls++ })
        assertEquals(2, calls)
    }

    @Test
    fun runtimeAllowsHiddenOfflinePlaybackAndBlocksInvalidPlayerStates() {
        val fixture = keyboardRuntime()
        val runtime = fixture.runtime
        assertFalse(runtime.canHandleKeyboardShortcut(PlayerKeyboardShortcut.TogglePlayback))
        assertTrue(runtime.canHandleKeyboardShortcut(PlayerKeyboardShortcut.Exit))

        runtime.initialLoadCompleted = true
        runtime.initialSeekApplied = true
        runtime.controlsVisible = false
        assertTrue(runtime.canHandleKeyboardShortcut(PlayerKeyboardShortcut.TogglePlayback))
        runtime.playerControllerSourceUrl = "file:///replacement.mp4"
        assertFalse(runtime.canHandleKeyboardShortcut(PlayerKeyboardShortcut.TogglePlayback))
        runtime.playerControllerSourceUrl = runtime.activeSourceUrl
        runtime.errorMessage = "Playback failed"
        assertFalse(runtime.canHandleKeyboardShortcut(PlayerKeyboardShortcut.TogglePlayback))
        runtime.errorMessage = null
        runtime.playerControlsLocked = true
        assertFalse(runtime.canHandleKeyboardShortcut(PlayerKeyboardShortcut.TogglePlayback))
        runtime.playerControlsLocked = false
        runtime.isScrubbingTimeline = true
        assertFalse(runtime.canHandleKeyboardShortcut(PlayerKeyboardShortcut.TogglePlayback))
        runtime.isScrubbingTimeline = false
        runtime.isHoldToSpeedGestureActive = true
        assertFalse(runtime.canHandleKeyboardShortcut(PlayerKeyboardShortcut.TogglePlayback))
        runtime.isHoldToSpeedGestureActive = false
        runtime.keyboardSessionActive = false
        assertFalse(runtime.canHandleKeyboardShortcut(PlayerKeyboardShortcut.Exit))
        fixture.close()
    }

    @Test
    fun playerPanelsSuppressShortcutsAndDismissalRestoresThem() {
        val fixture = keyboardRuntime()
        val runtime = fixture.runtime.apply {
            initialLoadCompleted = true
            initialSeekApplied = true
        }
        val panels = listOf<(Boolean) -> Unit>(
            { runtime.showAudioModal = it },
            { runtime.showSubtitleModal = it },
            { runtime.showVideoSettingsModal = it },
            { runtime.showWatchTogetherPanel = it },
            { runtime.showSourcesPanel = it },
            { runtime.showEpisodesPanel = it },
            { runtime.showSubmitIntroModal = it },
            { runtime.showParentalGuide = it },
            { runtime.episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = it) },
            { runtime.playerDownloadSheetItemId = if (it) "download" else null },
            { runtime.nextEpisodeAutoPlaySearching = it },
        )

        panels.forEach { setVisible ->
            setVisible(true)
            assertFalse(runtime.canHandleKeyboardShortcut(PlayerKeyboardShortcut.Exit))
            setVisible(false)
            assertTrue(runtime.canHandleKeyboardShortcut(PlayerKeyboardShortcut.TogglePlayback))
        }
        fixture.close()
    }

    @Test
    fun hiddenOfflinePlaybackAndSeekUseExistingControllerActions() {
        val fixture = keyboardRuntime()
        val runtime = fixture.runtime
        runtime.initialLoadCompleted = true
        runtime.initialSeekApplied = true
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(positionMs = 5_000, durationMs = 12_000)
        runtime.controlsVisible = false

        runtime.handleKeyboardShortcut(PlayerKeyboardShortcut.TogglePlayback)
        runtime.handleKeyboardShortcut(PlayerKeyboardShortcut.SeekBackward)
        runtime.handleKeyboardShortcut(PlayerKeyboardShortcut.SeekForward)

        assertEquals(1, fixture.controller.playCalls)
        assertEquals(listOf(-10_000L, 10_000L), fixture.controller.seekOffsets)
        assertTrue(runtime.controlsVisible)
        fixture.close()
    }

    @Test
    fun escapeExitsOnlyOnce() {
        var exits = 0
        val fixture = keyboardRuntime { exits++ }
        fixture.runtime.handleKeyboardShortcut(PlayerKeyboardShortcut.Exit)
        fixture.runtime.handleKeyboardShortcut(PlayerKeyboardShortcut.Exit)
        assertEquals(1, exits)
        assertTrue(fixture.runtime.keyboardExitRequested)
        fixture.close()
    }

    private fun keyboardRuntime(onBack: () -> Unit = {}): KeyboardFixture {
        val scope = CoroutineScope(SupervisorJob())
        val controller = RecordingController()
        val runtime = PlayerScreenRuntime(
            PlayerScreenArgs(
                profileId = 1, title = "Keyboard fixture", sourceUrl = "file:///fixture.mp4",
                sourceAudioUrl = null, sourceHeaders = emptyMap(), sourceResponseHeaders = emptyMap(),
                streamType = null, providerName = "Fixture", streamTitle = "Fixture", streamSubtitle = null,
                initialBingeGroup = null, pauseDescription = null, onBack = onBack,
                onOpenInExternalPlayer = null, onOpenExternalUrl = null, onOpenDownloads = null,
                modifier = Modifier, logo = null, poster = null, background = null,
                seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
                contentType = "movie", videoId = "fixture", parentMetaId = "fixture",
                parentMetaType = "movie", providerAddonId = null, torrentInfoHash = null,
                torrentFileIdx = null, torrentFilename = null, torrentTrackers = emptyList(),
                initialPositionMs = 0, initialProgressFraction = null,
            ),
        ).apply {
            this.scope = scope
            keyboardSessionActive = true
            playerController = controller
            playerControllerSourceUrl = activeSourceUrl
        }
        return KeyboardFixture(runtime, controller, scope)
    }

    private data class KeyboardFixture(
        val runtime: PlayerScreenRuntime,
        val controller: RecordingController,
        val scope: CoroutineScope,
    ) {
        fun close() = scope.cancel()
    }

    private class RecordingController : PlayerEngineController {
        var playCalls = 0
        val seekOffsets = mutableListOf<Long>()
        override fun play() { playCalls++ }
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun seekBy(offsetMs: Long) { seekOffsets += offsetMs }
        override fun retry() {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = emptyList<SubtitleTrack>()
        override fun selectAudioTrack(index: Int) {}
        override fun selectSubtitleTrack(index: Int) {}
        override fun setSubtitleUri(url: String) {}
        override fun clearExternalSubtitle() {}
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) {}
    }
}
