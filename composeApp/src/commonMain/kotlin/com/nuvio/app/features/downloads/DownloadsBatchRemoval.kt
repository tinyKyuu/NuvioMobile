package com.nuvio.app.features.downloads

internal data class DownloadBatchRemovalTarget(
    val record: DownloadRecord,
    val activeHandle: DownloadsTaskHandle?,
)

internal fun removeProfileRecordsFromCatalogAsBatch(
    store: DownloadsCatalogStore,
    ownerProfileKey: String,
    requestedDownloadIds: Collection<String>,
    runtimeRecordsById: Map<String, DownloadRecord> = emptyMap(),
): List<DownloadRecord> {
    val requestedIds = requestedDownloadIds.toSet()
    if (requestedIds.isEmpty()) return emptyList()

    val selected = store.recordsForProfile(ownerProfileKey)
        .filter { it.downloadId in requestedIds }
        .map { stored ->
            runtimeRecordsById[stored.downloadId]
                ?.takeIf { it.ownerProfileKey == ownerProfileKey }
                ?: stored
        }
    if (selected.isEmpty()) return emptyList()

    store.commit(downloadIdsToDelete = selected.map(DownloadRecord::downloadId))
    return selected
}

internal fun performDownloadBatchCleanup(
    targets: Collection<DownloadBatchRemovalTarget>,
    cancelPlatformTask: (String) -> Unit,
    removeRequest: (String) -> Unit,
    removeCompletedFile: (DownloadRecord) -> Unit,
    removePartialFile: (DownloadRecord) -> Unit,
    onBatchCleaned: () -> Unit,
) {
    if (targets.isEmpty()) return

    targets.forEach { target ->
        runCatching { target.activeHandle?.cancel() }
        runCatching { cancelPlatformTask(target.record.downloadId) }
        runCatching { removeRequest(target.record.downloadId) }
        runCatching { removeCompletedFile(target.record) }
        runCatching { removePartialFile(target.record) }
    }
    onBatchCleaned()
}
