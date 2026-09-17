package com.nuvio.app.features.downloads

internal data class DownloadBatchRemovalTarget(
    val record: DownloadRecord,
    val activeHandle: DownloadsTaskHandle?,
)

internal fun selectProfileRecordsForBatchRemoval(
    store: DownloadsCatalogStore,
    ownerProfileKey: String,
    requestedDownloadIds: Collection<String>,
    runtimeRecordsById: Map<String, DownloadRecord> = emptyMap(),
): List<DownloadRecord> {
    val requestedIds = requestedDownloadIds.toSet()
    if (requestedIds.isEmpty()) return emptyList()
    return store.recordsForProfile(ownerProfileKey)
        .filter { it.downloadId in requestedIds }
        .map { stored ->
            runtimeRecordsById[stored.downloadId]
                ?.takeIf { it.ownerProfileKey == ownerProfileKey }
                ?: stored
        }
}

internal fun commitSuccessfulBatchRemoval(
    store: DownloadsCatalogStore,
    successfulRecords: Collection<DownloadRecord>,
): Set<String> {
    val successfulIds = successfulRecords.mapTo(linkedSetOf(), DownloadRecord::downloadId)
    if (successfulIds.isNotEmpty()) {
        store.commit(downloadIdsToDelete = successfulIds)
    }
    return successfulIds
}

enum class DownloadRemovalFailureReason {
    ActiveHandleCancellation,
    PlatformTaskCancellation,
    RequestCleanup,
    CompletedFileCleanup,
    PartialFileCleanup,
}

data class DownloadRemovalFailure(
    val downloadId: String,
    val reasons: Set<DownloadRemovalFailureReason>,
)

data class DownloadBatchRemovalResult(
    val successfulIds: Set<String> = emptySet(),
    val failures: List<DownloadRemovalFailure> = emptyList(),
    val bytesReclaimed: Long = 0L,
) {
    val failedIds: Set<String>
        get() = failures.mapTo(linkedSetOf(), DownloadRemovalFailure::downloadId)

    val removedCount: Int
        get() = successfulIds.size
}

internal data class DownloadBatchCleanupResult(
    val successfulTargets: List<DownloadBatchRemovalTarget>,
    val failures: List<DownloadRemovalFailure>,
)

internal fun performDownloadBatchCleanup(
    targets: Collection<DownloadBatchRemovalTarget>,
    cancelPlatformTask: (String) -> Unit,
    removeRequest: (String) -> Boolean,
    removeCompletedFile: (DownloadRecord) -> Boolean,
    removePartialFile: (DownloadRecord) -> Boolean,
): DownloadBatchCleanupResult {
    if (targets.isEmpty()) {
        return DownloadBatchCleanupResult(emptyList(), emptyList())
    }

    val successful = mutableListOf<DownloadBatchRemovalTarget>()
    val failures = mutableListOf<DownloadRemovalFailure>()
    targets.forEach { target ->
        val reasons = linkedSetOf<DownloadRemovalFailureReason>()
        if (runCatching { target.activeHandle?.cancel() }.isFailure) {
            reasons += DownloadRemovalFailureReason.ActiveHandleCancellation
        }
        if (runCatching { cancelPlatformTask(target.record.downloadId) }.isFailure) {
            reasons += DownloadRemovalFailureReason.PlatformTaskCancellation
        }
        if (!runCatching { removeRequest(target.record.downloadId) }.getOrDefault(false)) {
            reasons += DownloadRemovalFailureReason.RequestCleanup
        }
        if (!runCatching { removeCompletedFile(target.record) }.getOrDefault(false)) {
            reasons += DownloadRemovalFailureReason.CompletedFileCleanup
        }
        if (!runCatching { removePartialFile(target.record) }.getOrDefault(false)) {
            reasons += DownloadRemovalFailureReason.PartialFileCleanup
        }

        if (reasons.isEmpty()) {
            successful += target
        } else {
            failures += DownloadRemovalFailure(
                downloadId = target.record.downloadId,
                reasons = reasons,
            )
        }
    }
    return DownloadBatchCleanupResult(
        successfulTargets = successful,
        failures = failures,
    )
}

internal fun DownloadRecord.reclaimableBytes(): Long = when {
    downloadedBytes > 0L -> downloadedBytes
    internalState == DownloadInternalState.Completed -> expectedBytes?.coerceAtLeast(0L) ?: 0L
    else -> 0L
}
