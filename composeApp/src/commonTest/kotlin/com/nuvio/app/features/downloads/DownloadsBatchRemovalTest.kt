package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `cleanup reports per file success and failure without suppressing errors`() {
        val events = mutableListOf<String>()
        val activeHandle = object : DownloadsTaskHandle {
            override fun pause() = Unit

            override fun cancel() {
                events += "handle:active"
            }
        }
        val targets = listOf(
            DownloadBatchRemovalTarget(
                record = batchRecord("active", "profile:1", DownloadInternalState.Downloading),
                activeHandle = activeHandle,
            ),
            DownloadBatchRemovalTarget(
                record = batchRecord("queued", "profile:1", DownloadInternalState.Queued),
                activeHandle = null,
            ),
            DownloadBatchRemovalTarget(
                record = batchRecord("paused", "profile:1", DownloadInternalState.Paused),
                activeHandle = null,
            ),
            DownloadBatchRemovalTarget(
                record = batchRecord("failed", "profile:1", DownloadInternalState.FailedRecoverable),
                activeHandle = null,
            ),
            DownloadBatchRemovalTarget(
                record = batchRecord("completed", "profile:1", DownloadInternalState.Completed),
                activeHandle = null,
            ),
        )

        val result = performDownloadBatchCleanup(
            targets = targets,
            cancelPlatformTask = { events += "platform:$it" },
            removeRequest = {
                events += "request:$it"
                true
            },
            removeCompletedFile = {
                events += "completed:${it.downloadId}"
                it.downloadId != "completed"
            },
            removePartialFile = {
                events += "partial:${it.downloadId}"
                true
            },
        )

        assertEquals(1, events.count { it == "handle:active" })
        assertEquals(
            setOf("active", "queued", "paused", "failed"),
            result.successfulTargets.mapTo(linkedSetOf()) { it.record.downloadId },
        )
        assertEquals(setOf("completed"), result.failures.mapTo(linkedSetOf(), DownloadRemovalFailure::downloadId))
        assertEquals(
            setOf(DownloadRemovalFailureReason.CompletedFileCleanup),
            result.failures.single().reasons,
        )
        targets.forEach { target ->
            val id = target.record.downloadId
            assertEquals(1, events.count { it == "platform:$id" })
            assertEquals(1, events.count { it == "request:$id" })
            assertEquals(1, events.count { it == "completed:$id" })
            assertEquals(1, events.count { it == "partial:$id" })
        }
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

    @Test
    fun `request storage refusal is reported instead of becoming catalog-only success`() {
        val target = DownloadBatchRemovalTarget(
            record = batchRecord("request-failed", "profile:1", DownloadInternalState.Completed),
            activeHandle = null,
        )

        val result = performDownloadBatchCleanup(
            targets = listOf(target),
            cancelPlatformTask = {},
            removeRequest = { false },
            removeCompletedFile = { true },
            removePartialFile = { true },
        )

        assertEquals(emptyList(), result.successfulTargets)
        assertEquals(
            setOf(DownloadRemovalFailureReason.RequestCleanup),
            result.failures.single().reasons,
        )
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
