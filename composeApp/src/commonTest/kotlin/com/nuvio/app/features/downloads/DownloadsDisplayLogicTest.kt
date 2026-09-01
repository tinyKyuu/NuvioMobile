package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsDisplayLogicTest {
    @Test
    fun `current downloads show occupied slots before fifo queue and paused items`() {
        val items = listOf(
            displayItem("paused", DownloadStatus.Paused, createdAt = 1L),
            displayItem("queued-later", DownloadStatus.Queued, createdAt = 4L),
            displayItem("active-later", DownloadStatus.Downloading, createdAt = 3L),
            displayItem("queued-next", DownloadStatus.Queued, createdAt = 2L),
            displayItem("active-first", DownloadStatus.WaitingForNetwork, createdAt = 1L),
            displayItem("completed", DownloadStatus.Completed, createdAt = 0L),
        )

        assertEquals(
            listOf("active-first", "active-later", "queued-next", "queued-later", "paused"),
            currentDownloadsForDisplay(items).map(DownloadItem::id),
        )
    }

    @Test
    fun `live status keeps scheduler order and excludes completed paused and failed items`() {
        val items = listOf(
            displayItem("paused", DownloadStatus.Paused, createdAt = 0L),
            displayItem("queued", DownloadStatus.Queued, createdAt = 4L),
            displayItem("active-later", DownloadStatus.Downloading, createdAt = 3L),
            displayItem("failed", DownloadStatus.Failed, createdAt = 2L),
            displayItem("active-first", DownloadStatus.WaitingForNetwork, createdAt = 1L),
            displayItem("completed", DownloadStatus.Completed, createdAt = 0L),
        )

        assertEquals(
            listOf("active-first", "active-later", "queued"),
            liveStatusItemsForDisplay(items).map(DownloadItem::id),
        )
    }

    @Test
    fun `completed movie sort supports added title and stored size`() {
        val items = listOf(
            displayItem("bravo", DownloadStatus.Completed, title = "Bravo", createdAt = 3L, bytes = 200L),
            displayItem("alpha", DownloadStatus.Completed, title = "Alpha", createdAt = 2L, bytes = 300L),
            displayItem("charlie", DownloadStatus.Completed, title = "Charlie", createdAt = 1L, bytes = 100L),
        )

        assertEquals(
            listOf("bravo", "alpha", "charlie"),
            completedMoviesForDisplay(items, CompletedDownloadSort.RecentlyAdded).map(DownloadItem::id),
        )
        assertEquals(
            listOf("alpha", "bravo", "charlie"),
            completedMoviesForDisplay(items, CompletedDownloadSort.TitleAscending).map(DownloadItem::id),
        )
        assertEquals(
            listOf("alpha", "bravo", "charlie"),
            completedMoviesForDisplay(items, CompletedDownloadSort.LargestFirst).map(DownloadItem::id),
        )
        assertEquals(
            listOf("charlie", "bravo", "alpha"),
            completedMoviesForDisplay(items, CompletedDownloadSort.SmallestFirst).map(DownloadItem::id),
        )
    }

    @Test
    fun `completed show sort uses newest episode and total stored size`() {
        val items = listOf(
            displayEpisode("alpha-1", showId = "alpha", showTitle = "Alpha", episode = 1, createdAt = 1L, bytes = 100L),
            displayEpisode("alpha-2", showId = "alpha", showTitle = "Alpha", episode = 2, createdAt = 5L, bytes = 100L),
            displayEpisode("bravo-1", showId = "bravo", showTitle = "Bravo", episode = 1, createdAt = 3L, bytes = 300L),
        )

        val recent = completedShowsForDisplay(items, CompletedDownloadSort.RecentlyAdded)
        val largest = completedShowsForDisplay(items, CompletedDownloadSort.LargestFirst)

        assertEquals(listOf("alpha", "bravo"), recent.map { it.representative.parentMetaId })
        assertEquals(listOf("bravo", "alpha"), largest.map { it.representative.parentMetaId })
        assertEquals(listOf(1, 2), recent.first().episodes.map(DownloadItem::episodeNumber))
    }

    private fun displayItem(
        id: String,
        status: DownloadStatus,
        title: String = id,
        createdAt: Long,
        bytes: Long = 0L,
    ): DownloadItem = testRecordItem(
        id = id,
        status = status,
        localFileUri = if (status == DownloadStatus.Completed) "file:///downloads/$id.mp4" else null,
    ).copy(
        parentMetaId = id,
        title = title,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = createdAt,
        downloadedBytes = bytes,
        totalBytes = bytes,
    )

    private fun displayEpisode(
        id: String,
        showId: String,
        showTitle: String,
        episode: Int,
        createdAt: Long,
        bytes: Long,
    ): DownloadItem = displayItem(
        id = id,
        status = DownloadStatus.Completed,
        title = showTitle,
        createdAt = createdAt,
        bytes = bytes,
    ).copy(
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        seasonNumber = 1,
        episodeNumber = episode,
        episodeTitle = "Episode $episode",
    )
}
