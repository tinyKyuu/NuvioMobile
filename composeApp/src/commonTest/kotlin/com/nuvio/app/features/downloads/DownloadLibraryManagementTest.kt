package com.nuvio.app.features.downloads

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadLibraryManagementTest {
    @Test
    fun `completed library groups shows seasons and episodes without counting files twice`() {
        val items = listOf(
            item("movie", bytes = 100L),
            episode("s1e1", season = 1, episode = 1, bytes = 20L),
            episode("s1e2", season = 1, episode = 2, bytes = 30L),
            episode("s2e1", season = 2, episode = 1, bytes = 40L),
            item("active", status = DownloadStatus.Downloading, bytes = 500L),
        )

        val library = buildCompletedDownloadLibrary(items)

        assertEquals(listOf("movie"), library.movies.map { it.item.id })
        assertEquals(1, library.shows.size)
        assertEquals(listOf(1, 2), library.shows.single().seasons.map(CompletedDownloadSeason::seasonNumber))
        assertEquals(90L, library.shows.single().bytes)
        assertEquals(setOf("movie", "s1e1", "s1e2", "s2e1"), library.allIds)
    }

    @Test
    fun `group toggles promote partial to full and full to empty`() {
        val group = setOf("one", "two", "three")
        var state = DownloadLibraryManagementState(isManaging = true, selectedIds = setOf("one"))

        assertEquals(DownloadGroupSelectionState.Partial, downloadGroupSelectionState(state.selectedIds, group))
        state = reduceDownloadLibraryManagement(state, DownloadLibraryManagementEvent.Toggle(group))
        assertEquals(group, state.selectedIds)
        assertEquals(DownloadGroupSelectionState.Full, downloadGroupSelectionState(state.selectedIds, group))
        state = reduceDownloadLibraryManagement(state, DownloadLibraryManagementEvent.Toggle(group))
        assertEquals(emptySet(), state.selectedIds)
    }

    @Test
    fun `panel navigation and collapse preserve manage selection while leaving downloads clears it`() {
        var state = DownloadLibraryManagementState(
            isManaging = true,
            selectedIds = setOf("s1e1"),
        )
        state = reduceDownloadLibraryManagement(state, DownloadLibraryManagementEvent.OpenShow("show"))
        state = reduceDownloadLibraryManagement(state, DownloadLibraryManagementEvent.OpenSeason("show", 1))
        assertIs<DownloadManagerRoute.Season>(state.route)

        state = reduceDownloadLibraryManagement(state, DownloadLibraryManagementEvent.Back)
        assertIs<DownloadManagerRoute.Show>(state.route)
        state = reduceDownloadLibraryManagement(state, DownloadLibraryManagementEvent.Collapse)
        assertTrue(state.isManaging)
        assertEquals(setOf("s1e1"), state.selectedIds)
        assertFalse(state.isExpanded)

        state = reduceDownloadLibraryManagement(state, DownloadLibraryManagementEvent.LeaveDownloads)
        assertEquals(DownloadLibraryManagementState(), state)
    }

    @Test
    fun `management state restores expanded chooser and selection after recreation`() {
        val state = DownloadLibraryManagementState(
            isManaging = true,
            selectedIds = linkedSetOf("movie", "s1e2"),
            isExpanded = true,
            route = DownloadManagerRoute.Season(showId = "show", seasonNumber = 1),
        )

        val restored = restoreDownloadLibraryManagementState(state.toSavePayload())

        assertEquals(state, restored)
        assertNull(restoreDownloadLibraryManagementState(listOf(true, emptyList<String>(), true, "season")))
    }

    @Test
    fun `adaptive manager uses panels only when both dimensions can preserve context`() {
        assertEquals(
            DownloadManagerContainer.BottomSheet,
            resolveDownloadManagerContainer(390.dp, 844.dp),
        )
        assertEquals(
            DownloadManagerContainer.BottomSheet,
            resolveDownloadManagerContainer(520.dp, 900.dp),
        )
        assertEquals(
            DownloadManagerContainer.BottomSheet,
            resolveDownloadManagerContainer(900.dp, 420.dp),
        )
        assertEquals(
            DownloadManagerContainer.AdaptivePanel,
            resolveDownloadManagerContainer(600.dp, 720.dp),
        )
        assertEquals(
            DownloadManagerContainer.AdaptivePanel,
            resolveDownloadManagerContainer(1024.dp, 768.dp),
        )
    }

    @Test
    fun `narrow and large text controls stack and reserve more grid clearance`() {
        assertTrue(useStackedDownloadManagerControls(390.dp, fontScale = 1f))
        assertTrue(useStackedDownloadManagerControls(700.dp, fontScale = 1.6f))
        assertFalse(useStackedDownloadManagerControls(700.dp, fontScale = 1f))
        assertTrue(downloadManagerGridBottomClearance(1.6f) > downloadManagerGridBottomClearance(1f))
    }

    @Test
    fun `large completed shows retain every episode and exact aggregate size`() {
        val episodes = (1..55).map { number ->
            episode(
                id = "s${(number - 1) / 10 + 1}e$number",
                season = (number - 1) / 10 + 1,
                episode = number,
                bytes = number.toLong(),
            )
        }

        val show = buildCompletedDownloadLibrary(episodes).shows.single()

        assertEquals(55, show.episodes.size)
        assertEquals(6, show.seasons.size)
        assertEquals((1L..55L).sum(), show.bytes)
        assertEquals(55, show.downloadIds.size)
    }

    @Test
    fun `profile change clears temporary selection and newly completed files stay unselected`() {
        var state = DownloadLibraryManagementState(isManaging = true, selectedIds = setOf("movie"))
        state = reduceDownloadLibraryManagement(
            state,
            DownloadLibraryManagementEvent.ItemsChanged(listOf(item("movie"), item("new"))),
        )
        assertEquals(setOf("movie"), state.selectedIds)

        state = reduceDownloadLibraryManagement(state, DownloadLibraryManagementEvent.ProfileChanged)
        assertEquals(DownloadLibraryManagementState(), state)
    }

    @Test
    fun `selection totals unique movies episodes and exact known bytes`() {
        val summary = summarizeCompletedDownloadSelection(
            selectedIds = listOf("movie", "episode", "episode", "missing"),
            items = listOf(item("movie", bytes = 100L), episode("episode", bytes = 55L)),
        )

        assertEquals(1, summary.movieCount)
        assertEquals(1, summary.episodeCount)
        assertEquals(2, summary.fileCount)
        assertEquals(155L, summary.bytes)
    }

    @Test
    fun `partial removal drops successful ids and retains failed selections`() {
        val state = DownloadLibraryManagementState(
            isManaging = true,
            selectedIds = setOf("ok", "failed", "other"),
        )
        val next = applyDownloadRemovalResult(
            state,
            DownloadBatchRemovalResult(
                successfulIds = setOf("ok"),
                failures = listOf(
                    DownloadRemovalFailure(
                        "failed",
                        setOf(DownloadRemovalFailureReason.CompletedFileCleanup),
                    ),
                ),
                bytesReclaimed = 100L,
            ),
        )

        assertEquals(setOf("failed", "other"), next.selectedIds)
        assertTrue(next.isManaging)
    }

    @Test
    fun `episode shortcut resolves only the exact completed season and episode`() {
        val wanted = episode("download-id", season = 2, episode = 4).copy(videoId = "provider-specific-id")
        val items = listOf(
            episode("wrong-season", season = 1, episode = 4),
            episode("wrong-episode", season = 2, episode = 3),
            wanted,
            episode("active-copy", season = 2, episode = 4).copy(status = DownloadStatus.Downloading),
        )

        assertEquals(
            wanted,
            findExactDownloadedEpisode(
                items = items,
                parentMetaId = "show",
                seasonNumber = 2,
                episodeNumber = 4,
            ),
        )
        assertEquals(
            null,
            findExactDownloadedEpisode(
                items = items,
                parentMetaId = "another-show",
                seasonNumber = 2,
                episodeNumber = 4,
            ),
        )
        assertEquals(
            null,
            findExactDownloadedEpisode(
                items = items,
                parentMetaId = "show",
                seasonNumber = null,
                episodeNumber = null,
            ),
        )
    }

    private fun episode(
        id: String,
        season: Int = 1,
        episode: Int = 1,
        bytes: Long = 10L,
    ): DownloadItem = item(id, bytes = bytes).copy(
        contentType = "series",
        parentMetaId = "show",
        parentMetaType = "series",
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = "Episode $episode",
    )

    private fun item(
        id: String,
        status: DownloadStatus = DownloadStatus.Completed,
        bytes: Long = 10L,
    ): DownloadItem = testRecordItem(
        id = id,
        status = status,
        localFileUri = if (status == DownloadStatus.Completed) "file:///downloads/$id.mp4" else null,
    ).copy(
        parentMetaId = id,
        title = id,
        downloadedBytes = bytes,
        totalBytes = bytes,
    )
}
