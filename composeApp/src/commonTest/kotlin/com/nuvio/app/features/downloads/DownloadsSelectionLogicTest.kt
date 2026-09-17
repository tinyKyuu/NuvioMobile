package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `activity selects several current transfers but never hidden completed records`() {
        val items = listOf(
            selectionItem("active", DownloadStatus.Downloading),
            selectionItem("queued", DownloadStatus.Queued),
            selectionItem("paused", DownloadStatus.Paused),
            selectionItem("failed", DownloadStatus.Failed),
            selectionItem("completed", DownloadStatus.Completed),
        )

        val visibleIds = visibleDownloadSelectionIds(
            mode = DownloadsScreenMode.Activity,
            items = items,
        )
        val selected = toggleDownloadSelection(
            selectedIds = emptySet(),
            targetIds = visibleIds,
            items = items.filter { it.id in visibleIds },
        )
        val summary = summarizeDownloadSelection(selected, items.filter { it.id in visibleIds })

        assertEquals(setOf("active", "queued", "paused", "failed"), visibleIds)
        assertEquals(visibleIds, selected)
        assertEquals(4, summary.currentTransferCount)
        assertEquals(0, summary.completedFileCount)
        assertFalse("completed" in selected)
    }

    @Test
    fun `policy has no selection while legacy and Library keep completed bulk behavior`() {
        val items = listOf(
            selectionItem("active", DownloadStatus.Downloading),
            selectionItem("completed", DownloadStatus.Completed),
        )

        assertFalse(DownloadsScreenMode.Policy.supportsBulkSelection())
        assertEquals(
            emptySet(),
            visibleDownloadSelectionIds(DownloadsScreenMode.Policy, items),
        )
        assertTrue(DownloadsScreenMode.Activity.supportsBulkSelection())
        assertEquals(
            setOf("active"),
            visibleDownloadSelectionIds(DownloadsScreenMode.Activity, items),
        )
        assertEquals(
            setOf("active", "completed"),
            visibleDownloadSelectionIds(DownloadsScreenMode.Legacy, items),
        )
        assertEquals(
            setOf("completed"),
            buildCompletedDownloadLibrary(items).allIds,
        )
    }

    @Test
    fun `mixed activity removal retains only failed current transfers`() {
        val outcome = applyDownloadActivityRemovalResult(
            selectedIds = setOf("removed", "failed", "completed"),
            visibleCurrentTransferIds = setOf("removed", "failed"),
            result = DownloadBatchRemovalResult(
                successfulIds = setOf("removed"),
                failures = listOf(
                    DownloadRemovalFailure(
                        downloadId = "failed",
                        reasons = setOf(DownloadRemovalFailureReason.ActiveHandleCancellation),
                    ),
                ),
                bytesReclaimed = 20L,
            ),
        )

        assertEquals(setOf("failed"), outcome.selection.selectedIds)
        assertTrue(outcome.selection.isSelecting)
        assertEquals(DownloadActivityRemovalFeedbackKind.PartialFailure, outcome.feedback.kind)
        assertEquals(1, outcome.feedback.removedCount)
        assertEquals(1, outcome.feedback.failedCount)
    }

    @Test
    fun `all-success activity removal clears selection and exits selection mode`() {
        val outcome = applyDownloadActivityRemovalResult(
            selectedIds = setOf("first", "second"),
            visibleCurrentTransferIds = setOf("first", "second"),
            result = DownloadBatchRemovalResult(
                successfulIds = setOf("first", "second"),
                cleanupWarnings = listOf(
                    DownloadRemovalFailure(
                        downloadId = "first",
                        reasons = setOf(DownloadRemovalFailureReason.RequestCleanup),
                    ),
                ),
                bytesReclaimed = 40L,
            ),
        )

        assertEquals(emptySet(), outcome.selection.selectedIds)
        assertFalse(outcome.selection.isSelecting)
        assertEquals(DownloadActivityRemovalFeedbackKind.CompleteSuccess, outcome.feedback.kind)
        assertEquals(2, outcome.feedback.removedCount)
        assertEquals(0, outcome.feedback.failedCount)
        assertEquals(1, outcome.feedback.cleanupWarningCount)
    }

    @Test
    fun `all-failure activity removal keeps failed transfers selected`() {
        val failures = setOf("first", "second")
        val outcome = applyDownloadActivityRemovalResult(
            selectedIds = failures,
            visibleCurrentTransferIds = failures,
            result = DownloadBatchRemovalResult(
                failures = failures.map { downloadId ->
                    DownloadRemovalFailure(
                        downloadId = downloadId,
                        reasons = setOf(DownloadRemovalFailureReason.PlatformTaskCancellation),
                    )
                },
            ),
        )

        assertEquals(failures, outcome.selection.selectedIds)
        assertTrue(outcome.selection.isSelecting)
        assertEquals(DownloadActivityRemovalFeedbackKind.TotalFailure, outcome.feedback.kind)
        assertEquals(0, outcome.feedback.removedCount)
        assertEquals(2, outcome.feedback.failedCount)
    }

    @Test
    fun `activity feedback counts cleanup warnings only for removed transfers`() {
        val outcome = applyDownloadActivityRemovalResult(
            selectedIds = setOf("removed", "failed"),
            visibleCurrentTransferIds = setOf("removed", "failed"),
            result = DownloadBatchRemovalResult(
                successfulIds = setOf("removed"),
                failures = listOf(
                    DownloadRemovalFailure(
                        downloadId = "failed",
                        reasons = setOf(DownloadRemovalFailureReason.ActiveHandleCancellation),
                    ),
                ),
                cleanupWarnings = listOf(
                    DownloadRemovalFailure(
                        downloadId = "removed",
                        reasons = setOf(DownloadRemovalFailureReason.PartialFileCleanup),
                    ),
                    DownloadRemovalFailure(
                        downloadId = "failed",
                        reasons = setOf(DownloadRemovalFailureReason.RequestCleanup),
                    ),
                ),
            ),
        )

        assertEquals(DownloadActivityRemovalFeedbackKind.PartialFailure, outcome.feedback.kind)
        assertEquals(1, outcome.feedback.cleanupWarningCount)
        assertEquals(setOf("failed"), outcome.selection.selectedIds)
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
