package com.nuvio.app.features.downloads

internal typealias DownloadFileResolver = (
    localFileUri: String?,
    destinationFileName: String,
) -> String?

internal data class NormalizedDownloads(
    val items: List<DownloadItem>,
    val changed: Boolean,
)

internal data class LogicalDownloadReplacement(
    val items: List<DownloadItem>,
    val replacedItems: List<DownloadItem>,
)

internal fun normalizeLoadedDownloads(
    items: List<DownloadItem>,
    resolveLocalFileUri: DownloadFileResolver,
): NormalizedDownloads {
    var changed = false
    val normalizedItems = items.map { item ->
        val statusNormalized = if (
            item.status == DownloadStatus.Downloading ||
            item.status == DownloadStatus.WaitingForNetwork ||
            item.status == DownloadStatus.Finalizing
        ) {
            item.copy(
                status = DownloadStatus.Paused,
                errorMessage = null,
            )
        } else {
            item
        }

        val localUriNormalized = statusNormalized.recoverCompletedLocalFileUri(resolveLocalFileUri)
        if (localUriNormalized != item) {
            changed = true
        }
        localUriNormalized
    }

    return NormalizedDownloads(
        items = normalizedItems,
        changed = changed,
    )
}

internal fun replaceDownloadForLogicalContent(
    currentItems: List<DownloadItem>,
    replacement: DownloadItem,
): LogicalDownloadReplacement {
    val replacedItem = currentItems.firstOrNull {
        it.logicalContentKey == replacement.logicalContentKey
    }
    return LogicalDownloadReplacement(
        items = buildList {
            add(replacement)
            currentItems.filterTo(this) { it.id != replacedItem?.id }
        },
        replacedItems = listOfNotNull(replacedItem),
    )
}

internal fun selectPlayableDownloadByVideoId(
    items: List<DownloadItem>,
    videoId: String?,
    resolveLocalFileUri: DownloadFileResolver,
): DownloadItem? {
    val normalizedVideoId = videoId?.trim().orEmpty()
    if (normalizedVideoId.isBlank()) return null

    return items.firstOrNull { item ->
        item.videoId == normalizedVideoId && item.hasPlayableLocalFile(resolveLocalFileUri)
    }
}

internal fun selectPlayableDownload(
    items: List<DownloadItem>,
    parentMetaId: String,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    videoId: String? = null,
    resolveLocalFileUri: DownloadFileResolver,
): DownloadItem? {
    selectPlayableDownloadByVideoId(
        items = items,
        videoId = videoId,
        resolveLocalFileUri = resolveLocalFileUri,
    )?.let { return it }

    val normalizedParentMetaId = parentMetaId.trim()
    return if (seasonNumber != null && episodeNumber != null) {
        items.firstOrNull { item ->
            item.parentMetaId == normalizedParentMetaId &&
                item.seasonNumber == seasonNumber &&
                item.episodeNumber == episodeNumber &&
                item.hasPlayableLocalFile(resolveLocalFileUri)
        }
    } else {
        items.firstOrNull { item ->
            item.parentMetaId == normalizedParentMetaId &&
                item.seasonNumber == null &&
                item.episodeNumber == null &&
                item.hasPlayableLocalFile(resolveLocalFileUri)
        }
    }
}

internal fun DownloadItem.pausedAt(nowEpochMs: Long): DownloadItem =
    if (
        status == DownloadStatus.Queued ||
        status == DownloadStatus.Downloading ||
        status == DownloadStatus.WaitingForNetwork
    ) {
        copy(
            status = DownloadStatus.Paused,
            updatedAtEpochMs = nowEpochMs,
            errorMessage = null,
        )
    } else {
        this
    }

internal fun DownloadItem.resumedAt(nowEpochMs: Long): DownloadItem =
    if (status == DownloadStatus.Paused || status == DownloadStatus.Failed) {
        copy(
            status = DownloadStatus.Queued,
            errorMessage = null,
            localFileUri = null,
            updatedAtEpochMs = nowEpochMs,
        )
    } else {
        this
    }

internal fun DownloadItem.withProgress(
    downloadedBytes: Long,
    totalBytes: Long?,
    nowEpochMs: Long,
): DownloadItem =
    if (status == DownloadStatus.Downloading || status == DownloadStatus.WaitingForNetwork) {
        copy(
            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
            totalBytes = totalBytes?.takeIf { it > 0L },
            updatedAtEpochMs = nowEpochMs,
            errorMessage = null,
        )
    } else {
        this
    }

internal fun DownloadItem.completedAt(
    localFileUri: String,
    totalBytes: Long?,
    nowEpochMs: Long,
): DownloadItem {
    val normalizedTotalBytes = totalBytes?.takeIf { it > 0L }
    return copy(
        status = DownloadStatus.Completed,
        localFileUri = localFileUri,
        downloadedBytes = normalizedTotalBytes ?: downloadedBytes,
        totalBytes = normalizedTotalBytes ?: this.totalBytes,
        errorMessage = null,
        updatedAtEpochMs = nowEpochMs,
    )
}

internal fun DownloadItem.failedAt(
    message: String,
    nowEpochMs: Long,
): DownloadItem =
    if (
        status == DownloadStatus.Queued ||
        status == DownloadStatus.Downloading ||
        status == DownloadStatus.WaitingForNetwork ||
        status == DownloadStatus.Finalizing
    ) {
        copy(
            status = DownloadStatus.Failed,
            errorMessage = message,
            updatedAtEpochMs = nowEpochMs,
        )
    } else {
        this
    }

private fun DownloadItem.recoverCompletedLocalFileUri(
    resolveLocalFileUri: DownloadFileResolver,
): DownloadItem {
    if (status != DownloadStatus.Completed) return this
    val resolvedUri = resolveLocalFileUri(localFileUri, fileName) ?: return this
    return if (resolvedUri != localFileUri) {
        copy(localFileUri = resolvedUri)
    } else {
        this
    }
}

private fun DownloadItem.hasPlayableLocalFile(
    resolveLocalFileUri: DownloadFileResolver,
): Boolean =
    status == DownloadStatus.Completed && resolveLocalFileUri(localFileUri, fileName) != null
