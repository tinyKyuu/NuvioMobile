package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsSelectionLogicTest {
    @Test
    fun `show selection expands to every represented completed episode`() {
        val items = listOf(
            selectionEpisode("show-a-1", "show-a", DownloadStatus.Completed),
            selectionEpisode("show-a-2", "show-a", DownloadStatus.Completed),
            selectionEpisode("show-a-active", "show-a", DownloadStatus.Downloading),
            selectionEpisode("show-b-1", "show-b", DownloadStatus.Completed),
        )

        val showIds = downloadIdsForShow(items, "show-a")
        val selected = toggleDownloadSelection(emptySet(), showIds, items)

        assertEquals(setOf("show-a-1", "show-a-2"), showIds)
        assertEquals(showIds, selected)
        assertEquals(emptySet(), toggleDownloadSelection(selected, showIds, items))
    }

    @Test
    fun `mixed selection reports file states and combined known storage`() {
        val items = listOf(
            selectionItem("queued", DownloadStatus.Queued, downloadedBytes = 0L),
            selectionItem("active", DownloadStatus.Downloading, downloadedBytes = 25L, totalBytes = 100L),
            selectionItem("paused", DownloadStatus.Paused, downloadedBytes = 40L),
            selectionItem("failed", DownloadStatus.Failed, downloadedBytes = 5L),
            selectionItem("completed", DownloadStatus.Completed, downloadedBytes = 0L, totalBytes = 200L),
        )

        val summary = summarizeDownloadSelection(
            selectedIds = items.map(DownloadItem::id),
            items = items,
        )

        assertEquals(5, summary.fileCount)
        assertEquals(1, summary.completedFileCount)
        assertEquals(4, summary.currentTransferCount)
        assertEquals(270L, summary.knownStorageBytes)
    }

    @Test
    fun `selection stays keyed by id and discards records that no longer exist`() {
        val items = listOf(
            selectionItem("movie", DownloadStatus.Completed),
            selectionEpisode("episode", "show", DownloadStatus.Completed),
        )

        assertEquals(
            setOf("movie", "episode"),
            retainExistingDownloadSelection(
                selectedIds = setOf("missing", "episode", "movie"),
                items = items,
            ),
        )
    }
}

private fun selectionEpisode(
    id: String,
    showId: String,
    status: DownloadStatus,
): DownloadItem = selectionItem(id, status).copy(
    contentType = "series",
    parentMetaId = showId,
    parentMetaType = "series",
    seasonNumber = 1,
    episodeNumber = id.last().digitToIntOrNull() ?: 1,
    episodeTitle = "Episode $id",
)

private fun selectionItem(
    id: String,
    status: DownloadStatus,
    downloadedBytes: Long = 0L,
    totalBytes: Long? = null,
): DownloadItem = testRecordItem(
    id = id,
    status = status,
    localFileUri = if (status == DownloadStatus.Completed) "file:///downloads/$id.mp4" else null,
).copy(
    parentMetaId = id,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
)
