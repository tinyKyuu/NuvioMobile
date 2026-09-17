package com.nuvio.app.features.downloads

internal data class DownloadSelectionSummary(
    val selectedIds: Set<String>,
    val fileCount: Int,
    val completedFileCount: Int,
    val currentTransferCount: Int,
    val knownStorageBytes: Long,
)

internal data class DownloadActivitySelectionState(
    val isSelecting: Boolean,
    val selectedIds: Set<String>,
)

internal enum class DownloadActivityRemovalFeedbackKind {
    CompleteSuccess,
    PartialFailure,
    TotalFailure,
}

internal data class DownloadActivityRemovalFeedback(
    val kind: DownloadActivityRemovalFeedbackKind,
    val removedCount: Int,
    val failedCount: Int,
    val cleanupWarningCount: Int,
    val bytesReclaimed: Long,
)

internal data class DownloadActivityRemovalOutcome(
    val selection: DownloadActivitySelectionState,
    val feedback: DownloadActivityRemovalFeedback,
)

internal enum class DownloadsBackAction {
    ExitSelection,
    CloseShow,
    NavigateBack,
}

internal fun DownloadsScreenMode.supportsBulkSelection(): Boolean =
    this != DownloadsScreenMode.Policy

internal fun visibleDownloadSelectionIds(
    mode: DownloadsScreenMode,
    items: Collection<DownloadItem>,
    selectedShowDownloadIds: Collection<String> = emptySet(),
    isShowingCompletedSeries: Boolean = false,
): Set<String> = when {
    mode == DownloadsScreenMode.Policy -> emptySet()
    mode == DownloadsScreenMode.Activity -> currentDownloadsForDisplay(items.toList())
        .mapTo(linkedSetOf(), DownloadItem::id)
    isShowingCompletedSeries -> selectedShowDownloadIds.toCollection(linkedSetOf())
    else -> items.mapTo(linkedSetOf(), DownloadItem::id)
}

internal fun resolveDownloadsBackAction(
    mode: DownloadsScreenMode,
    selectionMode: Boolean,
    isShowingCompletedSeries: Boolean,
): DownloadsBackAction = when {
    mode.supportsBulkSelection() && selectionMode -> DownloadsBackAction.ExitSelection
    mode == DownloadsScreenMode.Legacy && isShowingCompletedSeries -> DownloadsBackAction.CloseShow
    else -> DownloadsBackAction.NavigateBack
}

internal fun applyDownloadActivityRemovalResult(
    selectedIds: Collection<String>,
    visibleCurrentTransferIds: Collection<String>,
    result: DownloadBatchRemovalResult,
): DownloadActivityRemovalOutcome {
    val visibleIds = visibleCurrentTransferIds.toSet()
    val previousSelection = selectedIds.filterTo(linkedSetOf()) { it in visibleIds }
    val successfulIds = result.successfulIds.intersect(previousSelection)
    val retainedIds = previousSelection
        .filterTo(linkedSetOf()) { it !in successfulIds }
    val cleanupWarningCount = result.cleanupWarnings.count { warning ->
        warning.downloadId in successfulIds
    }
    val feedbackKind = when {
        retainedIds.isEmpty() -> DownloadActivityRemovalFeedbackKind.CompleteSuccess
        successfulIds.isEmpty() -> DownloadActivityRemovalFeedbackKind.TotalFailure
        else -> DownloadActivityRemovalFeedbackKind.PartialFailure
    }
    return DownloadActivityRemovalOutcome(
        selection = DownloadActivitySelectionState(
            isSelecting = retainedIds.isNotEmpty(),
            selectedIds = retainedIds,
        ),
        feedback = DownloadActivityRemovalFeedback(
            kind = feedbackKind,
            removedCount = successfulIds.size,
            failedCount = retainedIds.size,
            cleanupWarningCount = cleanupWarningCount,
            bytesReclaimed = result.bytesReclaimed,
        ),
    )
}

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
