package com.nuvio.app.features.downloads

internal fun findActiveRecordsMissingPlatformTasks(
    records: List<DownloadRecord>,
    snapshots: List<DownloadPlatformTaskSnapshot>,
): List<DownloadRecord> {
    val snapshotIds = snapshots
        .filter { it.state.canContinueAfterLaunch }
        .mapTo(mutableSetOf(), DownloadPlatformTaskSnapshot::downloadId)
    return records.filter { record ->
        record.downloadId !in snapshotIds && record.internalState.isPlatformActiveForReconciliation
    }
}

internal val DownloadPlatformTaskState.canContinueAfterLaunch: Boolean
    get() = this == DownloadPlatformTaskState.Running ||
        this == DownloadPlatformTaskState.Suspended

private val DownloadInternalState.isPlatformActiveForReconciliation: Boolean
    get() = this == DownloadInternalState.Downloading ||
        this == DownloadInternalState.WaitingForNetwork ||
        this == DownloadInternalState.Finalizing
