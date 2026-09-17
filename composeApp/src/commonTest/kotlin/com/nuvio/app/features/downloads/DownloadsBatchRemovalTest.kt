package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadsBatchRemovalTest {
    @Test
    fun `selection is profile scoped and does not mutate the catalog before cleanup`() {
        val profileOneActive = batchRecord("active", "profile:1", DownloadInternalState.Downloading)
        val profileOneCompleted = batchRecord("completed", "profile:1", DownloadInternalState.Completed)
        val profileTwo = batchRecord("other-profile", "profile:2", DownloadInternalState.Completed)
        val store = FakeBatchCatalogStore(
            mutableListOf(profileOneActive, profileOneCompleted, profileTwo),
        )

        val selected = selectProfileRecordsForBatchRemoval(
            store = store,
            ownerProfileKey = "profile:1",
            requestedDownloadIds = listOf("active", "completed", "other-profile", "missing"),
        )

        assertEquals(listOf("active", "completed"), selected.map(DownloadRecord::downloadId))
        assertEquals(emptyList(), store.commits)
        assertEquals(3, store.records.size)
    }

    @Test
    fun `catalog commit contains successful records only and happens once`() {
        val successful = batchRecord("successful", "profile:1", DownloadInternalState.Completed)
        val failed = batchRecord("failed", "profile:1", DownloadInternalState.Completed)
        val store = FakeBatchCatalogStore(mutableListOf(successful, failed))

        val committedIds = commitSuccessfulBatchRemoval(store, listOf(successful))

        assertEquals(setOf("successful"), committedIds)
        assertEquals(listOf(listOf("successful")), store.commits)
        assertEquals(listOf("failed"), store.records.map(DownloadRecord::downloadId))
    }

    @Test
    fun `earlier completed cleanup failures retain playable media and stop before deletion`() {
        val events = mutableListOf<String>()
        val mediaPresent = mutableMapOf(
            "request-failed" to true,
            "partial-failed" to true,
            "cancel-failed" to true,
        )
        val cancelFailureHandle = object : DownloadsTaskHandle {
            override fun pause() = Unit

            override fun cancel() {
                events += "handle:cancel-failed"
                error("cancel failed")
            }
        }
        val targets = listOf(
            completedTarget("request-failed"),
            completedTarget("partial-failed"),
            completedTarget("cancel-failed", cancelFailureHandle),
        )

        val cleanup = performDownloadBatchCleanup(
            targets = targets,
            cancelPlatformTask = { events += "platform:$it" },
            removeRequest = { id ->
                events += "request:$id"
                id != "request-failed"
            },
            removePartialFile = { record ->
                events += "partial:${record.downloadId}"
                record.downloadId != "partial-failed"
            },
            removeCompletedFile = { record ->
                events += "completed:${record.downloadId}"
                mediaPresent[record.downloadId] = false
                true
            },
        )
        val store = FakeBatchCatalogStore(targets.mapTo(mutableListOf()) { it.record })
        var publicationCount = 0
        var schedulerPumpCount = 0
        val result = settleDownloadBatchRemoval(
            cleanup = cleanup,
            completedMediaIdsWithResolvedFiles = mediaPresent.keys,
            applyState = { successful, _ ->
                commitSuccessfulBatchRemoval(store, successful.map(DownloadBatchRemovalTarget::record))
            },
            publishFinalState = { publicationCount += 1 },
            pumpScheduler = { schedulerPumpCount += 1 },
        )

        assertEquals(
            listOf(
                "platform:request-failed",
                "request:request-failed",
                "platform:partial-failed",
                "request:partial-failed",
                "partial:partial-failed",
                "handle:cancel-failed",
            ),
            events,
        )
        assertEquals(
            mapOf(
                "request-failed" to setOf(DownloadRemovalFailureReason.RequestCleanup),
                "partial-failed" to setOf(DownloadRemovalFailureReason.PartialFileCleanup),
                "cancel-failed" to setOf(DownloadRemovalFailureReason.ActiveHandleCancellation),
            ),
            result.failures.associate { it.downloadId to it.reasons },
        )
        assertTrue(mediaPresent.values.all { it })
        assertEquals(mediaPresent.keys, store.records.mapTo(linkedSetOf(), DownloadRecord::downloadId))
        assertEquals(1, publicationCount)
        assertEquals(1, schedulerPumpCount)
    }

    @Test
    fun `completed file deletion failure retains the catalog record and media`() {
        val target = completedTarget("completed-failed")
        val store = FakeBatchCatalogStore(mutableListOf(target.record))
        var mediaPresent = true

        val cleanup = performDownloadBatchCleanup(
            targets = listOf(target),
            cancelPlatformTask = {},
            removeRequest = { true },
            removePartialFile = { true },
            removeCompletedFile = {
                assertTrue(mediaPresent)
                false
            },
        )
        val result = settleDownloadBatchRemoval(
            cleanup = cleanup,
            completedMediaIdsWithResolvedFiles = setOf(target.record.downloadId),
            applyState = { successful, _ ->
                commitSuccessfulBatchRemoval(store, successful.map(DownloadBatchRemovalTarget::record))
            },
            publishFinalState = {},
            pumpScheduler = {},
        )

        assertEquals(setOf("completed-failed"), result.failedIds)
        assertTrue(mediaPresent)
        assertEquals(listOf("completed-failed"), store.records.map(DownloadRecord::downloadId))
    }

    @Test
    fun `successful completed deletion cannot remain cataloged as a failure`() {
        val target = completedTarget("completed-success").withDownloadedBytes(80L)
        val store = FakeBatchCatalogStore(mutableListOf(target.record))
        var mediaPresent = true

        val cleanup = performDownloadBatchCleanup(
            targets = listOf(target),
            cancelPlatformTask = {},
            removeRequest = { true },
            removePartialFile = { true },
            removeCompletedFile = {
                mediaPresent = false
                true
            },
        )
        val result = settleDownloadBatchRemoval(
            cleanup = cleanup,
            completedMediaIdsWithResolvedFiles = setOf(target.record.downloadId),
            applyState = { successful, _ ->
                commitSuccessfulBatchRemoval(store, successful.map(DownloadBatchRemovalTarget::record))
            },
            publishFinalState = {},
            pumpScheduler = {},
        )

        assertFalse(mediaPresent)
        assertEquals(setOf("completed-success"), result.successfulIds)
        assertEquals(emptySet(), result.failedIds)
        assertEquals(80L, result.bytesReclaimed)
        assertEquals(emptyList(), store.records)
    }

    @Test
    fun `successful stale completed record does not report bytes when no file was resolved`() {
        val target = completedTarget("missing-file").withDownloadedBytes(80L)
        val store = FakeBatchCatalogStore(mutableListOf(target.record))

        val cleanup = performDownloadBatchCleanup(
            targets = listOf(target),
            cancelPlatformTask = {},
            removeRequest = { true },
            removePartialFile = { true },
            removeCompletedFile = { true },
        )
        val result = settleDownloadBatchRemoval(
            cleanup = cleanup,
            completedMediaIdsWithResolvedFiles = emptySet(),
            applyState = { successful, _ ->
                commitSuccessfulBatchRemoval(store, successful.map(DownloadBatchRemovalTarget::record))
            },
            publishFinalState = {},
            pumpScheduler = {},
        )

        assertEquals(setOf("missing-file"), result.successfulIds)
        assertEquals(0L, result.bytesReclaimed)
        assertEquals(emptyList(), store.records)
    }

    @Test
    fun `mixed cleanup reports exact ids bytes warnings and one final settlement`() {
        val events = mutableListOf<String>()
        val completedGood = completedTarget("completed-good").withDownloadedBytes(100L)
        val completedFailed = completedTarget("completed-failed").withDownloadedBytes(200L)
        val active = DownloadBatchRemovalTarget(
            record = batchRecord("active", "profile:1", DownloadInternalState.Downloading)
                .copy(downloadedBytes = 25L),
            activeHandle = recordingHandle("active", events),
        )
        val queued = DownloadBatchRemovalTarget(
            record = batchRecord("queued", "profile:1", DownloadInternalState.Queued)
                .copy(downloadedBytes = 50L),
            activeHandle = null,
        )
        val targets = listOf(completedGood, completedFailed, active, queued)
        val store = FakeBatchCatalogStore(targets.mapTo(mutableListOf()) { it.record })
        var publicationCount = 0
        var schedulerPumpCount = 0

        val cleanup = performDownloadBatchCleanup(
            targets = targets,
            cancelPlatformTask = { events += "platform:$it" },
            removeRequest = { id ->
                events += "request:$id"
                id != "active"
            },
            removePartialFile = { record ->
                events += "partial:${record.downloadId}"
                record.downloadId != "queued"
            },
            removeCompletedFile = { record ->
                events += "completed:${record.downloadId}"
                record.downloadId != "completed-failed"
            },
        )
        var stateApplicationCount = 0
        val result = settleDownloadBatchRemoval(
            cleanup = cleanup,
            completedMediaIdsWithResolvedFiles = setOf("completed-good", "completed-failed"),
            applyState = { successful, _ ->
                stateApplicationCount += 1
                commitSuccessfulBatchRemoval(store, successful.map(DownloadBatchRemovalTarget::record))
            },
            publishFinalState = { publicationCount += 1 },
            pumpScheduler = { schedulerPumpCount += 1 },
        )

        assertEquals(setOf("completed-good", "active", "queued"), result.successfulIds)
        assertEquals(setOf("completed-failed"), result.failedIds)
        assertEquals(125L, result.bytesReclaimed)
        assertEquals(
            mapOf(
                "active" to setOf(DownloadRemovalFailureReason.RequestCleanup),
                "queued" to setOf(DownloadRemovalFailureReason.PartialFileCleanup),
            ),
            result.cleanupWarnings.associate { it.downloadId to it.reasons },
        )
        assertEquals(listOf("completed-failed"), store.records.map(DownloadRecord::downloadId))
        assertEquals(1, stateApplicationCount)
        assertEquals(1, publicationCount)
        assertEquals(1, schedulerPumpCount)
        assertEquals(
            listOf(
                "platform:completed-good",
                "request:completed-good",
                "partial:completed-good",
                "completed:completed-good",
                "platform:completed-failed",
                "request:completed-failed",
                "partial:completed-failed",
                "completed:completed-failed",
                "handle:active",
                "request:active",
                "partial:active",
                "platform:queued",
                "request:queued",
                "partial:queued",
            ),
            events,
        )
    }

    @Test
    fun `structured result keeps failed ids separate and counts reclaimed bytes once`() {
        val result = DownloadBatchRemovalResult(
            successfulIds = setOf("movie", "episode"),
            failures = listOf(
                DownloadRemovalFailure(
                    downloadId = "failed",
                    reasons = setOf(DownloadRemovalFailureReason.CompletedFileCleanup),
                ),
            ),
            bytesReclaimed = 300L,
        )

        assertEquals(setOf("movie", "episode"), result.successfulIds)
        assertEquals(setOf("failed"), result.failedIds)
        assertEquals(2, result.removedCount)
        assertEquals(300L, result.bytesReclaimed)
    }
}

private class FakeBatchCatalogStore(
    val records: MutableList<DownloadRecord>,
) : DownloadsCatalogStore {
    val commits = mutableListOf<List<String>>()

    override fun allRecords(): List<DownloadRecord> = records.toList()

    override fun recordsForProfile(ownerProfileKey: String): List<DownloadRecord> =
        records.filter { it.ownerProfileKey == ownerProfileKey }

    override fun recordById(downloadId: String): DownloadRecord? =
        records.firstOrNull { it.downloadId == downloadId }

    override fun commit(
        recordsToUpsert: Collection<DownloadRecord>,
        downloadIdsToDelete: Collection<String>,
    ) {
        val deletedIds = downloadIdsToDelete.toList()
        commits += deletedIds
        records.removeAll { it.downloadId in deletedIds }
        recordsToUpsert.forEach { record ->
            records.removeAll { it.downloadId == record.downloadId }
            records += record
        }
    }

    override fun replaceProfile(
        ownerProfileKey: String,
        records: Collection<DownloadRecord>,
    ) {
        this.records.removeAll { it.ownerProfileKey == ownerProfileKey }
        this.records += records
    }
}

private fun completedTarget(
    id: String,
    activeHandle: DownloadsTaskHandle? = null,
): DownloadBatchRemovalTarget = DownloadBatchRemovalTarget(
    record = batchRecord(id, "profile:1", DownloadInternalState.Completed),
    activeHandle = activeHandle,
)

private fun DownloadBatchRemovalTarget.withDownloadedBytes(bytes: Long): DownloadBatchRemovalTarget =
    copy(record = record.copy(downloadedBytes = bytes, expectedBytes = bytes))

private fun recordingHandle(
    id: String,
    events: MutableList<String>,
): DownloadsTaskHandle = object : DownloadsTaskHandle {
    override fun pause() = Unit

    override fun cancel() {
        events += "handle:$id"
    }
}

private fun batchRecord(
    id: String,
    ownerProfileKey: String,
    state: DownloadInternalState,
): DownloadRecord = testRecord(
    id = id,
    ownerProfileKey = ownerProfileKey,
    state = state,
    localFileUri = if (state == DownloadInternalState.Completed) {
        "file:///downloads/$id.mp4"
    } else {
        null
    },
)
