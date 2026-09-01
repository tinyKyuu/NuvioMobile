package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsSchedulerTest {
    @Test
    fun `scheduler fills two slots in durable fifo order`() {
        val records = listOf(
            queuedRecord(id = "third", createdAt = 30L),
            queuedRecord(id = "first-b", createdAt = 10L),
            queuedRecord(id = "first-a", createdAt = 10L),
        )

        val selected = selectQueuedDownloadsToStart(records)

        assertEquals(listOf("first-a", "first-b"), selected.map(DownloadRecord::downloadId))
    }

    @Test
    fun `waiting and finalizing transfers continue occupying slots`() {
        val records = listOf(
            testRecord(id = "waiting", state = DownloadInternalState.WaitingForNetwork),
            testRecord(id = "finalizing", state = DownloadInternalState.Finalizing),
            queuedRecord(id = "queued", createdAt = 1L),
        )

        assertEquals(emptyList(), selectQueuedDownloadsToStart(records))
    }

    @Test
    fun `scheduler starts next queued transfer after a slot becomes free`() {
        val records = listOf(
            testRecord(id = "active", state = DownloadInternalState.Downloading),
            testRecord(id = "completed", state = DownloadInternalState.Completed),
            queuedRecord(id = "next", createdAt = 1L),
            queuedRecord(id = "later", createdAt = 2L),
        )

        val selected = selectQueuedDownloadsToStart(records)

        assertEquals(listOf("next"), selected.map(DownloadRecord::downloadId))
    }

    @Test
    fun `scheduler shares its limit and fifo order across profiles`() {
        val records = listOf(
            testRecord(id = "profile-one-active", state = DownloadInternalState.Downloading),
            queuedRecord(id = "profile-one-later", createdAt = 20L),
            queuedRecord(id = "profile-two-next", createdAt = 10L).copy(
                ownerProfileKey = "profile:2",
            ),
        )

        val selected = selectQueuedDownloadsToStart(records)

        assertEquals(listOf("profile-two-next"), selected.map(DownloadRecord::downloadId))
        assertEquals("profile:2", selected.single().ownerProfileKey)
    }

    private fun queuedRecord(id: String, createdAt: Long): DownloadRecord =
        testRecord(id = id, state = DownloadInternalState.Queued).copy(
            createdAtEpochMs = createdAt,
            item = testRecord(id = id, state = DownloadInternalState.Queued).item.copy(
                createdAtEpochMs = createdAt,
            ),
        )
}
