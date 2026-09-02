package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsBatchRemovalTest {
    @Test
    fun `catalog removal is one profile scoped commit and is idempotent`() {
        val profileOneActive = batchRecord("active", "profile:1", DownloadInternalState.Downloading)
        val profileOneCompleted = batchRecord("completed", "profile:1", DownloadInternalState.Completed)
        val profileTwo = batchRecord("other-profile", "profile:2", DownloadInternalState.Completed)
        val store = FakeBatchCatalogStore(
            mutableListOf(profileOneActive, profileOneCompleted, profileTwo),
        )

        val removed = removeProfileRecordsFromCatalogAsBatch(
            store = store,
            ownerProfileKey = "profile:1",
            requestedDownloadIds = listOf("active", "completed", "other-profile", "missing"),
        )
        val repeated = removeProfileRecordsFromCatalogAsBatch(
            store = store,
            ownerProfileKey = "profile:1",
            requestedDownloadIds = listOf("active", "completed"),
        )

        assertEquals(listOf("active", "completed"), removed.map(DownloadRecord::downloadId))
        assertEquals(emptyList(), repeated)
        assertEquals(1, store.commits.size)
        assertEquals(setOf("active", "completed"), store.commits.single().toSet())
        assertEquals(listOf("other-profile"), store.records.map(DownloadRecord::downloadId))
    }

    @Test
    fun `cleanup coordinates cancellation and files before one scheduler advance`() {
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

        performDownloadBatchCleanup(
            targets = targets,
            cancelPlatformTask = { events += "platform:$it" },
            removeRequest = { events += "request:$it" },
            removeCompletedFile = { events += "completed:${it.downloadId}" },
            removePartialFile = { events += "partial:${it.downloadId}" },
            onBatchCleaned = { events += "scheduler" },
        )

        assertEquals(1, events.count { it == "handle:active" })
        assertEquals(1, events.count { it == "scheduler" })
        assertEquals("scheduler", events.last())
        targets.forEach { target ->
            val id = target.record.downloadId
            assertEquals(1, events.count { it == "platform:$id" })
            assertEquals(1, events.count { it == "request:$id" })
            assertEquals(1, events.count { it == "completed:$id" })
            assertEquals(1, events.count { it == "partial:$id" })
        }
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
