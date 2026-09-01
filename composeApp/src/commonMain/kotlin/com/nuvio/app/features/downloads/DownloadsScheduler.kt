package com.nuvio.app.features.downloads

internal const val MAX_CONCURRENT_DOWNLOADS = 2

internal fun selectQueuedDownloadsToStart(
    records: Collection<DownloadRecord>,
    maxConcurrentDownloads: Int = MAX_CONCURRENT_DOWNLOADS,
): List<DownloadRecord> {
    val normalizedLimit = maxConcurrentDownloads.coerceAtLeast(0)
    val occupiedSlots = records.count { it.internalState.occupiesDownloadSlot }
    val availableSlots = (normalizedLimit - occupiedSlots).coerceAtLeast(0)
    if (availableSlots == 0) return emptyList()

    return records
        .asSequence()
        .filter { it.internalState == DownloadInternalState.Queued }
        .sortedWith(
            compareBy<DownloadRecord> { it.createdAtEpochMs }
                .thenBy { it.downloadId },
        )
        .take(availableSlots)
        .toList()
}

internal val DownloadInternalState.occupiesDownloadSlot: Boolean
    get() = this == DownloadInternalState.Downloading ||
        this == DownloadInternalState.WaitingForNetwork ||
        this == DownloadInternalState.Finalizing
