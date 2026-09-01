package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsPlatformReconciliationTest {
    @Test
    fun `missing active records fail even when an older transport never stored a task id`() {
        val records = listOf(
            testRecord(id = "queued", state = DownloadInternalState.Queued),
            testRecord(id = "downloading", state = DownloadInternalState.Downloading),
            testRecord(id = "paused", state = DownloadInternalState.Paused),
            testRecord(id = "completed", state = DownloadInternalState.Completed),
        )

        val missing = findActiveRecordsMissingPlatformTasks(
            records = records,
            snapshots = emptyList(),
        )

        assertEquals(listOf("downloading"), missing.map(DownloadRecord::downloadId))
    }

    @Test
    fun `enumerated task protects its catalog record from missing-task recovery`() {
        val records = listOf(
            testRecord(id = "present", state = DownloadInternalState.Downloading),
            testRecord(id = "missing", state = DownloadInternalState.WaitingForNetwork),
        )
        val snapshots = listOf(
            DownloadPlatformTaskSnapshot(
                downloadId = "present",
                sessionIdentifier = "com.nuvio.test.downloads.background.v1",
                taskIdentifier = 12L,
                state = DownloadPlatformTaskState.Running,
                downloadedBytes = 100L,
                totalBytes = 1_000L,
            ),
        )

        val missing = findActiveRecordsMissingPlatformTasks(records, snapshots)

        assertEquals(listOf("missing"), missing.map(DownloadRecord::downloadId))
    }

    @Test
    fun `suspended task remains recoverable while terminal tasks fail reconciliation`() {
        val records = listOf(
            testRecord(id = "suspended", state = DownloadInternalState.Downloading),
            testRecord(id = "canceling", state = DownloadInternalState.Downloading),
            testRecord(id = "completed", state = DownloadInternalState.Finalizing),
        )
        val snapshots = listOf(
            snapshot(id = "suspended", state = DownloadPlatformTaskState.Suspended),
            snapshot(id = "canceling", state = DownloadPlatformTaskState.Canceling),
            snapshot(id = "completed", state = DownloadPlatformTaskState.Completed),
        )

        val missing = findActiveRecordsMissingPlatformTasks(records, snapshots)

        assertEquals(listOf("canceling", "completed"), missing.map(DownloadRecord::downloadId))
    }

    private fun snapshot(
        id: String,
        state: DownloadPlatformTaskState,
    ) = DownloadPlatformTaskSnapshot(
        downloadId = id,
        sessionIdentifier = "com.nuvio.test.downloads.background.v1",
        taskIdentifier = 12L,
        state = state,
        downloadedBytes = 100L,
        totalBytes = 1_000L,
    )
}
