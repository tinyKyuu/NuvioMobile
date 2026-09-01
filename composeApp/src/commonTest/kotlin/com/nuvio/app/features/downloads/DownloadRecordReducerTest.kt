package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRecordReducerTest {
    @Test
    fun `legacy items gain profile ownership relative paths and cold launch state`() {
        val downloading = testRecordItem(
            id = "active",
            status = DownloadStatus.Downloading,
            localFileUri = null,
        )
        val completed = testRecordItem(
            id = "completed",
            status = DownloadStatus.Completed,
            localFileUri = "file:///old-container/completed.mp4",
        )

        val migrated = migrateLegacyDownloadItems(
            items = listOf(downloading, completed),
            ownerProfileKey = "profile:7",
            resolveLocalFileUri = { _, fileName -> "file:///new-container/$fileName" },
        )

        assertEquals(2, migrated.size)
        assertTrue(migrated.all { it.ownerProfileKey == "profile:7" })
        assertEquals(DownloadInternalState.Paused, migrated[0].internalState)
        assertEquals(DownloadStatus.Paused, migrated[0].item.status)
        assertEquals("completed.mp4", migrated[1].relativeMediaPath)
        assertEquals("file:///new-container/completed.mp4", migrated[1].item.localFileUri)
    }

    @Test
    fun `version zero records migrate without losing playable files`() {
        val legacy = testRecord(
            state = DownloadInternalState.Completed,
            localFileUri = "file:///old/movie.mp4",
        ).copy(
            recordVersion = 0,
            logicalContentKey = "",
            relativeMediaPath = null,
            item = testRecord(
                state = DownloadInternalState.Completed,
                localFileUri = "file:///old/movie.mp4",
            ).item.copy(
                providerAddonId = "https://provider.test/user-secret/manifest.json",
            ),
        )

        val migrated = legacy.normalizeForColdLaunch(
            resolveLocalFileUri = { _, fileName -> "file:///current/$fileName" },
        )

        assertEquals(DOWNLOAD_RECORD_VERSION, migrated.recordVersion)
        assertEquals(legacy.item.logicalContentKey, migrated.logicalContentKey)
        assertEquals("movie.mp4", migrated.relativeMediaPath)
        assertEquals("file:///current/movie.mp4", migrated.item.localFileUri)
        assertNull(migrated.item.providerAddonId)
    }

    @Test
    fun `completion replay and late failure cannot corrupt completed state`() {
        val downloading = testRecord(state = DownloadInternalState.Downloading)
        val completion = DownloadRecordEvent.Complete(
            eventId = "complete-1",
            occurredAtEpochMs = 10L,
            localFileUri = "file:///downloads/movie.mp4",
            relativeMediaPath = "movie.mp4",
            totalBytes = 500L,
        )

        val completed = assertNotNull(reduceDownloadRecord(downloading, completion).record)
        assertEquals(DownloadInternalState.Completed, completed.internalState)
        assertEquals(500L, completed.downloadedBytes)
        assertEquals("file:///downloads/movie.mp4", completed.item.localFileUri)

        val replay = reduceDownloadRecord(completed, completion)
        assertFalse(replay.changed)
        assertEquals(completed, replay.record)

        val lateFailure = reduceDownloadRecord(
            completed,
            DownloadRecordEvent.Failure(
                eventId = "failure-after-complete",
                occurredAtEpochMs = 11L,
                message = "late failure",
            ),
        )
        assertFalse(lateFailure.changed)
        assertEquals(completed, lateFailure.record)
    }

    @Test
    fun `platform task binding is durable and idempotent`() {
        val queued = testRecord(state = DownloadInternalState.Queued)
        val binding = DownloadRecordEvent.BindPlatformTask(
            eventId = "bind-1",
            occurredAtEpochMs = 10L,
            sessionIdentifier = "com.nuvio.test.downloads.background.v1",
            taskIdentifier = 42L,
        )

        val bound = assertNotNull(reduceDownloadRecord(queued, binding).record)
        assertEquals(DownloadInternalState.Downloading, bound.internalState)
        assertEquals("com.nuvio.test.downloads.background.v1", bound.platformSessionId)
        assertEquals(42L, bound.platformTaskIdentifier)
        assertTrue(reduceDownloadRecord(bound, binding).persistImmediately.not())

        val paused = assertNotNull(
            reduceDownloadRecord(
                bound,
                DownloadRecordEvent.Pause(
                    eventId = "pause-bound",
                    occurredAtEpochMs = 11L,
                    reason = "user_pause",
                ),
            ).record,
        )
        assertNull(paused.platformTaskIdentifier)
    }

    @Test
    fun `scheduled queued record enters a slot before platform binding`() {
        val queued = testRecord(state = DownloadInternalState.Queued)

        val scheduled = assertNotNull(
            reduceDownloadRecord(
                queued,
                DownloadRecordEvent.ScheduleStart(
                    eventId = "schedule-1",
                    occurredAtEpochMs = 10L,
                ),
            ).record,
        )

        assertEquals(DownloadInternalState.Downloading, scheduled.internalState)
        assertEquals(DownloadStatus.Downloading, scheduled.item.status)
        assertEquals(10L, scheduled.startedAtEpochMs)
    }

    @Test
    fun `connectivity wait resumes on progress and finalizing is explicit`() {
        val downloading = testRecord(state = DownloadInternalState.Downloading)
        val waiting = assertNotNull(
            reduceDownloadRecord(
                downloading,
                DownloadRecordEvent.WaitingForConnectivity(
                    eventId = "waiting-1",
                    occurredAtEpochMs = 10L,
                    reason = "network_policy",
                ),
            ).record,
        )
        assertEquals(DownloadInternalState.WaitingForNetwork, waiting.internalState)
        assertEquals(DownloadStatus.WaitingForNetwork, waiting.item.status)

        val progressed = assertNotNull(
            reduceDownloadRecord(
                waiting,
                DownloadRecordEvent.Progress(
                    eventId = "progress-after-wait",
                    occurredAtEpochMs = 11L,
                    downloadedBytes = 64L,
                    expectedBytes = 128L,
                ),
            ).record,
        )
        assertEquals(DownloadInternalState.Downloading, progressed.internalState)

        val finalizing = assertNotNull(
            reduceDownloadRecord(
                progressed,
                DownloadRecordEvent.Finalize(
                    eventId = "finalize-1",
                    occurredAtEpochMs = 12L,
                ),
            ).record,
        )
        assertEquals(DownloadInternalState.Finalizing, finalizing.internalState)
        assertEquals(DownloadStatus.Finalizing, finalizing.item.status)
    }

    @Test
    fun `resume returns failed transfer to durable queue`() {
        val failed = testRecord(state = DownloadInternalState.FailedRecoverable)

        val resumed = assertNotNull(
            reduceDownloadRecord(
                failed,
                DownloadRecordEvent.Resume(
                    eventId = "resume-1",
                    occurredAtEpochMs = 10L,
                    reason = "user_retry",
                ),
            ).record,
        )

        assertEquals(DownloadInternalState.Queued, resumed.internalState)
        assertEquals(DownloadStatus.Queued, resumed.item.status)
    }

    @Test
    fun `persistent platform keeps active state until task reconciliation`() {
        val active = testRecord(state = DownloadInternalState.Downloading).copy(
            platformSessionId = "com.nuvio.test.downloads.background.v1",
            platformTaskIdentifier = 7L,
        )

        val foregroundOnly = active.normalizeForColdLaunch(
            resolveLocalFileUri = { _, _ -> null },
        )
        val persistent = active.normalizeForColdLaunch(
            resolveLocalFileUri = { _, _ -> null },
            preservePlatformActiveState = true,
        )

        assertEquals(DownloadInternalState.Paused, foregroundOnly.internalState)
        assertEquals(DownloadInternalState.Downloading, persistent.internalState)
        assertEquals(7L, persistent.platformTaskIdentifier)
    }

    @Test
    fun `pause replay is idempotent and late completion stays paused`() {
        val downloading = testRecord(state = DownloadInternalState.Downloading)
        val pause = DownloadRecordEvent.Pause(
            eventId = "pause-1",
            occurredAtEpochMs = 10L,
            reason = "user_pause",
        )

        val paused = assertNotNull(reduceDownloadRecord(downloading, pause).record)
        assertEquals(DownloadInternalState.Paused, paused.internalState)
        assertFalse(reduceDownloadRecord(paused, pause).changed)

        val lateCompletion = reduceDownloadRecord(
            paused,
            DownloadRecordEvent.Complete(
                eventId = "late-complete",
                occurredAtEpochMs = 11L,
                localFileUri = "file:///downloads/movie.mp4",
                relativeMediaPath = "movie.mp4",
                totalBytes = 100L,
            ),
        )
        assertFalse(lateCompletion.changed)
        assertEquals(DownloadInternalState.Paused, lateCompletion.record?.internalState)
    }

    @Test
    fun `late pause progress updates bytes without changing paused status`() {
        val paused = testRecord(state = DownloadInternalState.Paused)

        val progress = reduceDownloadRecord(
            paused,
            DownloadRecordEvent.Progress(
                eventId = "pause-progress",
                occurredAtEpochMs = 12L,
                downloadedBytes = 2_000L,
                expectedBytes = 10_000L,
            ),
        )

        val updated = assertNotNull(progress.record)
        assertTrue(progress.changed)
        assertTrue(progress.persistImmediately)
        assertEquals(DownloadInternalState.Paused, updated.internalState)
        assertEquals(DownloadStatus.Paused, updated.item.status)
        assertEquals(2_000L, updated.downloadedBytes)
    }

    @Test
    fun `delete produces one terminal removal`() {
        val current = testRecord(state = DownloadInternalState.Paused)
        val result = reduceDownloadRecord(
            current,
            DownloadRecordEvent.Delete(
                eventId = "delete-1",
                occurredAtEpochMs = 10L,
            ),
        )

        assertTrue(result.changed)
        assertTrue(result.persistImmediately)
        assertNull(result.record)
    }

    @Test
    fun `progress persistence is throttled by time or meaningful bytes`() {
        val policy = DownloadProgressPersistencePolicy(
            minIntervalMs = 1_000L,
            minByteDelta = 1_000L,
        )
        val initial = testRecord(state = DownloadInternalState.Downloading)
        policy.recordImmediate(initial)

        assertFalse(policy.shouldPersist(initial.withProgress(bytes = 100L, at = 500L)))
        assertTrue(policy.shouldPersist(initial.withProgress(bytes = 1_100L, at = 600L)))
        assertFalse(policy.shouldPersist(initial.withProgress(bytes = 1_200L, at = 1_000L)))
        assertTrue(policy.shouldPersist(initial.withProgress(bytes = 1_300L, at = 1_700L)))
    }

    @Test
    fun `record codec omits sandbox paths provider ids signed urls and request headers`() {
        val item = testRecordItem(
            id = "stored",
            status = DownloadStatus.Completed,
            localFileUri = "file:///private/container/movie.mp4",
        ).copy(
            providerAddonId = "https://provider.test/user-secret/manifest.json",
        )

        val payload = DownloadRecordCodec.encodeItem(item)
        val decoded = assertNotNull(DownloadRecordCodec.decodeItem(payload))

        assertFalse(payload.contains("/private/container"))
        assertFalse(payload.contains("example.test"))
        assertFalse(payload.contains("token=secret"))
        assertFalse(payload.contains("Referer"))
        assertFalse(payload.contains("user-secret"))
        assertNull(decoded.localFileUri)
        assertNull(decoded.providerAddonId)
        assertEquals(item.fileName, decoded.fileName)
        assertEquals("", decoded.sourceUrl)
        assertEquals(emptyMap(), decoded.sourceHeaders)
        assertEquals(emptyMap(), decoded.sourceResponseHeaders)
    }

    @Test
    fun `secure request codec preserves resumable request data`() {
        val request = testRecordItem(
            id = "stored",
            status = DownloadStatus.Paused,
            localFileUri = null,
        ).toStoredDownloadRequest()

        val decoded = assertNotNull(
            DownloadsRequestCodec.decodeOrNull(DownloadsRequestCodec.encode(request)),
        )

        assertEquals(request, decoded)
    }
}

private fun DownloadRecord.withProgress(bytes: Long, at: Long): DownloadRecord = copy(
    downloadedBytes = bytes,
    updatedAtEpochMs = at,
    item = item.copy(downloadedBytes = bytes, updatedAtEpochMs = at),
)

internal fun testRecord(
    id: String = "download-1",
    ownerProfileKey: String = "profile:1",
    state: DownloadInternalState,
    localFileUri: String? = null,
): DownloadRecord {
    val item = testRecordItem(
        id = id,
        status = state.toVisibleStatus(),
        localFileUri = localFileUri,
    )
    return item.toDownloadRecord(ownerProfileKey).copy(
        internalState = state,
        relativeMediaPath = localFileUri?.let { item.fileName },
        item = item,
    )
}

internal fun testRecordItem(
    id: String,
    status: DownloadStatus,
    localFileUri: String?,
): DownloadItem = DownloadItem(
    id = id,
    contentType = "movie",
    parentMetaId = "movie-1",
    parentMetaType = "movie",
    videoId = "video-1",
    title = "Test movie",
    streamTitle = "Test stream",
    providerName = "Test provider",
    sourceUrl = "https://example.test/movie.mp4?token=secret",
    sourceHeaders = mapOf("Referer" to "https://example.test"),
    localFileUri = localFileUri,
    fileName = if (id == "completed") "completed.mp4" else "movie.mp4",
    status = status,
    downloadedBytes = 0L,
    totalBytes = null,
    createdAtEpochMs = 0L,
    updatedAtEpochMs = 0L,
)
