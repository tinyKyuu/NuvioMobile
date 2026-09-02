package com.nuvio.app.features.downloads

internal data class DownloadSelectionSummary(
    val selectedIds: Set<String>,
    val fileCount: Int,
    val completedFileCount: Int,
    val currentTransferCount: Int,
    val knownStorageBytes: Long,
)

internal fun downloadIdsForShow(
    items: Collection<DownloadItem>,
    showId: String,
): Set<String> = items
    .asSequence()
    .filter { item ->
        item.status == DownloadStatus.Completed &&
            item.isEpisode &&
            item.parentMetaId == showId
    }
    .mapTo(linkedSetOf(), DownloadItem::id)

internal fun retainExistingDownloadSelection(
    selectedIds: Collection<String>,
    items: Collection<DownloadItem>,
): Set<String> {
    val existingIds = items.mapTo(hashSetOf(), DownloadItem::id)
    return selectedIds.filterTo(linkedSetOf()) { it in existingIds }
}

internal fun toggleDownloadSelection(
    selectedIds: Collection<String>,
    targetIds: Collection<String>,
    items: Collection<DownloadItem>,
): Set<String> {
    val retained = retainExistingDownloadSelection(selectedIds, items).toMutableSet()
    val validTargets = retainExistingDownloadSelection(targetIds, items)
    if (validTargets.isEmpty()) return retained

    if (validTargets.all(retained::contains)) {
        retained.removeAll(validTargets)
    } else {
        retained.addAll(validTargets)
    }
    return retained
}

internal fun summarizeDownloadSelection(
    selectedIds: Collection<String>,
    items: Collection<DownloadItem>,
): DownloadSelectionSummary {
    val retainedIds = retainExistingDownloadSelection(selectedIds, items)
    val selectedItems = items.filter { it.id in retainedIds }
    val completedCount = selectedItems.count { it.status == DownloadStatus.Completed }
    return DownloadSelectionSummary(
        selectedIds = retainedIds,
        fileCount = selectedItems.size,
        completedFileCount = completedCount,
        currentTransferCount = selectedItems.size - completedCount,
        knownStorageBytes = selectedItems.sumOf(DownloadItem::knownStoredBytes),
    )
}

private fun DownloadItem.knownStoredBytes(): Long = when {
    downloadedBytes > 0L -> downloadedBytes
    status == DownloadStatus.Completed -> totalBytes?.coerceAtLeast(0L) ?: 0L
    else -> 0L
}
