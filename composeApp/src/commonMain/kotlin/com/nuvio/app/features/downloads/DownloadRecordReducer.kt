package com.nuvio.app.features.downloads

internal sealed interface DownloadRecordEvent {
    val eventId: String
    val occurredAtEpochMs: Long

    data class Progress(
        override val eventId: String,
        override val occurredAtEpochMs: Long,
        val downloadedBytes: Long,
        val expectedBytes: Long?,
    ) : DownloadRecordEvent

    data class BindPlatformTask(
        override val eventId: String,
        override val occurredAtEpochMs: Long,
        val sessionIdentifier: String?,
        val taskIdentifier: Long?,
    ) : DownloadRecordEvent

    data class ScheduleStart(
        override val eventId: String,
        override val occurredAtEpochMs: Long,
    ) : DownloadRecordEvent

    data class WaitingForConnectivity(
        override val eventId: String,
        override val occurredAtEpochMs: Long,
        val reason: String,
    ) : DownloadRecordEvent

    data class Finalize(
        override val eventId: String,
        override val occurredAtEpochMs: Long,
    ) : DownloadRecordEvent

    data class Pause(
        override val eventId: String,
        override val occurredAtEpochMs: Long,
        val reason: String,
    ) : DownloadRecordEvent

    data class Resume(
        override val eventId: String,
        override val occurredAtEpochMs: Long,
        val reason: String,
    ) : DownloadRecordEvent

    data class Complete(
        override val eventId: String,
        override val occurredAtEpochMs: Long,
        val localFileUri: String,
        val relativeMediaPath: String,
        val totalBytes: Long?,
    ) : DownloadRecordEvent

    data class Failure(
        override val eventId: String,
        override val occurredAtEpochMs: Long,
        val message: String,
        val recoverable: Boolean = true,
    ) : DownloadRecordEvent

    data class Delete(
        override val eventId: String,
        override val occurredAtEpochMs: Long,
    ) : DownloadRecordEvent
}

internal data class DownloadRecordReduction(
    val record: DownloadRecord?,
    val changed: Boolean,
    val persistImmediately: Boolean,
)

internal fun reduceDownloadRecord(
    current: DownloadRecord,
    event: DownloadRecordEvent,
): DownloadRecordReduction {
    if (current.lastEventId == event.eventId) {
        return DownloadRecordReduction(current, changed = false, persistImmediately = false)
    }

    if (event is DownloadRecordEvent.Delete) {
        return DownloadRecordReduction(record = null, changed = true, persistImmediately = true)
    }

    val next = when (event) {
        is DownloadRecordEvent.Progress -> current.progressed(event)
        is DownloadRecordEvent.BindPlatformTask -> current.boundToPlatformTask(event)
        is DownloadRecordEvent.ScheduleStart -> current.scheduledToStart(event)
        is DownloadRecordEvent.WaitingForConnectivity -> current.waitingForConnectivity(event)
        is DownloadRecordEvent.Finalize -> current.finalizing(event)
        is DownloadRecordEvent.Pause -> current.paused(event)
        is DownloadRecordEvent.Resume -> current.resumed(event)
        is DownloadRecordEvent.Complete -> current.completed(event)
        is DownloadRecordEvent.Failure -> current.failed(event)
        is DownloadRecordEvent.Delete -> null
    }

    if (next == null || next == current) {
        return DownloadRecordReduction(current, changed = false, persistImmediately = false)
    }

    return DownloadRecordReduction(
        record = next,
        changed = true,
        persistImmediately = event !is DownloadRecordEvent.Progress ||
            current.internalState == DownloadInternalState.Paused,
    )
}

private fun DownloadRecord.scheduledToStart(
    event: DownloadRecordEvent.ScheduleStart,
): DownloadRecord {
    if (internalState != DownloadInternalState.Queued) return this
    return copy(
        internalState = DownloadInternalState.Downloading,
        stateReason = null,
        platformTaskIdentifier = null,
        lastEventId = event.eventId,
        startedAtEpochMs = startedAtEpochMs ?: event.occurredAtEpochMs,
        updatedAtEpochMs = event.occurredAtEpochMs,
        completedAtEpochMs = null,
        item = item.copy(
            status = DownloadStatus.Downloading,
            localFileUri = null,
            errorMessage = null,
            updatedAtEpochMs = event.occurredAtEpochMs,
        ),
    )
}

private fun DownloadRecord.waitingForConnectivity(
    event: DownloadRecordEvent.WaitingForConnectivity,
): DownloadRecord {
    if (internalState != DownloadInternalState.Downloading) return this
    return copy(
        internalState = DownloadInternalState.WaitingForNetwork,
        stateReason = event.reason,
        lastEventId = event.eventId,
        updatedAtEpochMs = event.occurredAtEpochMs,
        item = item.copy(
            status = DownloadStatus.WaitingForNetwork,
            errorMessage = null,
            updatedAtEpochMs = event.occurredAtEpochMs,
        ),
    )
}

private fun DownloadRecord.finalizing(event: DownloadRecordEvent.Finalize): DownloadRecord {
    if (
        internalState != DownloadInternalState.Downloading &&
        internalState != DownloadInternalState.WaitingForNetwork
    ) {
        return this
    }
    return copy(
        internalState = DownloadInternalState.Finalizing,
        stateReason = null,
        lastEventId = event.eventId,
        updatedAtEpochMs = event.occurredAtEpochMs,
        item = item.copy(
            status = DownloadStatus.Finalizing,
            errorMessage = null,
            updatedAtEpochMs = event.occurredAtEpochMs,
        ),
    )
}

private fun DownloadRecord.boundToPlatformTask(
    event: DownloadRecordEvent.BindPlatformTask,
): DownloadRecord {
    if (
        internalState != DownloadInternalState.Queued &&
        internalState != DownloadInternalState.Downloading &&
        internalState != DownloadInternalState.WaitingForNetwork
    ) {
        return this
    }
    return copy(
        internalState = DownloadInternalState.Downloading,
        stateReason = null,
        platformSessionId = event.sessionIdentifier,
        platformTaskIdentifier = event.taskIdentifier,
        lastEventId = event.eventId,
        startedAtEpochMs = startedAtEpochMs ?: event.occurredAtEpochMs,
        updatedAtEpochMs = event.occurredAtEpochMs,
        item = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            updatedAtEpochMs = event.occurredAtEpochMs,
        ),
    )
}

private fun DownloadRecord.progressed(event: DownloadRecordEvent.Progress): DownloadRecord {
    if (
        internalState != DownloadInternalState.Downloading &&
        internalState != DownloadInternalState.WaitingForNetwork &&
        internalState != DownloadInternalState.Paused
    ) {
        return this
    }
    val normalizedBytes = event.downloadedBytes.coerceAtLeast(0L)
    val normalizedExpected = event.expectedBytes?.takeIf { it > 0L }
    val nextState = if (internalState == DownloadInternalState.Paused) {
        DownloadInternalState.Paused
    } else {
        DownloadInternalState.Downloading
    }
    val visibleStatus = nextState.toVisibleStatus()
    return copy(
        internalState = nextState,
        stateReason = null,
        lastEventId = event.eventId,
        downloadedBytes = normalizedBytes,
        expectedBytes = normalizedExpected,
        updatedAtEpochMs = event.occurredAtEpochMs,
        item = item.copy(
            status = visibleStatus,
            downloadedBytes = normalizedBytes,
            totalBytes = normalizedExpected,
            errorMessage = null,
            updatedAtEpochMs = event.occurredAtEpochMs,
        ),
    )
}

private fun DownloadRecord.paused(event: DownloadRecordEvent.Pause): DownloadRecord {
    if (
        internalState != DownloadInternalState.Queued &&
        internalState != DownloadInternalState.Downloading &&
        internalState != DownloadInternalState.WaitingForNetwork
    ) {
        return this
    }
    return copy(
        internalState = DownloadInternalState.Paused,
        stateReason = null,
        platformTaskIdentifier = null,
        lastEventId = event.eventId,
        updatedAtEpochMs = event.occurredAtEpochMs,
        item = item.copy(
            status = DownloadStatus.Paused,
            errorMessage = null,
            updatedAtEpochMs = event.occurredAtEpochMs,
        ),
    )
}

private fun DownloadRecord.resumed(event: DownloadRecordEvent.Resume): DownloadRecord {
    if (
        internalState != DownloadInternalState.Paused &&
        internalState != DownloadInternalState.FailedRecoverable &&
        internalState != DownloadInternalState.FailedPermanent
    ) {
        return this
    }
    return copy(
        internalState = DownloadInternalState.Queued,
        stateReason = null,
        platformTaskIdentifier = null,
        lastEventId = event.eventId,
        startedAtEpochMs = startedAtEpochMs ?: event.occurredAtEpochMs,
        updatedAtEpochMs = event.occurredAtEpochMs,
        completedAtEpochMs = null,
        item = item.copy(
            status = DownloadStatus.Queued,
            localFileUri = null,
            errorMessage = null,
            updatedAtEpochMs = event.occurredAtEpochMs,
        ),
    )
}

private fun DownloadRecord.completed(event: DownloadRecordEvent.Complete): DownloadRecord {
    if (
        internalState == DownloadInternalState.Completed ||
        internalState == DownloadInternalState.Paused
    ) {
        return this
    }
    val normalizedTotal = event.totalBytes?.takeIf { it > 0L }
    val finalDownloadedBytes = normalizedTotal ?: downloadedBytes
    return copy(
        internalState = DownloadInternalState.Completed,
        stateReason = null,
        relativeMediaPath = event.relativeMediaPath,
        platformTaskIdentifier = null,
        lastEventId = event.eventId,
        downloadedBytes = finalDownloadedBytes,
        expectedBytes = normalizedTotal ?: expectedBytes,
        updatedAtEpochMs = event.occurredAtEpochMs,
        completedAtEpochMs = event.occurredAtEpochMs,
        item = item.copy(
            status = DownloadStatus.Completed,
            localFileUri = event.localFileUri,
            downloadedBytes = finalDownloadedBytes,
            totalBytes = normalizedTotal ?: expectedBytes,
            errorMessage = null,
            updatedAtEpochMs = event.occurredAtEpochMs,
        ),
    )
}

private fun DownloadRecord.failed(event: DownloadRecordEvent.Failure): DownloadRecord {
    if (
        internalState != DownloadInternalState.Queued &&
        internalState != DownloadInternalState.Downloading &&
        internalState != DownloadInternalState.WaitingForNetwork &&
        internalState != DownloadInternalState.Finalizing
    ) {
        return this
    }
    val nextState = if (event.recoverable) {
        DownloadInternalState.FailedRecoverable
    } else {
        DownloadInternalState.FailedPermanent
    }
    return copy(
        internalState = nextState,
        stateReason = event.message,
        platformTaskIdentifier = null,
        lastEventId = event.eventId,
        updatedAtEpochMs = event.occurredAtEpochMs,
        item = item.copy(
            status = DownloadStatus.Failed,
            errorMessage = event.message,
            updatedAtEpochMs = event.occurredAtEpochMs,
        ),
    )
}

internal class DownloadProgressPersistencePolicy(
    private val minIntervalMs: Long = 1_000L,
    private val minByteDelta: Long = 1L * 1024L * 1024L,
) {
    private val checkpoints = mutableMapOf<String, ProgressCheckpoint>()

    fun recordImmediate(record: DownloadRecord) {
        checkpoints[record.downloadId] = ProgressCheckpoint(
            downloadedBytes = record.downloadedBytes,
            persistedAtEpochMs = record.updatedAtEpochMs,
        )
    }

    fun shouldPersist(record: DownloadRecord): Boolean {
        val previous = checkpoints[record.downloadId]
        if (previous == null) {
            recordImmediate(record)
            return true
        }
        val elapsed = record.updatedAtEpochMs - previous.persistedAtEpochMs
        val byteDelta = record.downloadedBytes - previous.downloadedBytes
        val shouldPersist = elapsed >= minIntervalMs || byteDelta >= minByteDelta
        if (shouldPersist) {
            recordImmediate(record)
        }
        return shouldPersist
    }

    fun remove(downloadId: String) {
        checkpoints.remove(downloadId)
    }

    fun clear() {
        checkpoints.clear()
    }
}

private data class ProgressCheckpoint(
    val downloadedBytes: Long,
    val persistedAtEpochMs: Long,
)
