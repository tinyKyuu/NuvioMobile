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
    val cleanupWarnings: List<DownloadRemovalFailure> = emptyList(),
    val bytesReclaimed: Long = 0L,
) {
    val failedIds: Set<String>
        get() = failures.mapTo(linkedSetOf(), DownloadRemovalFailure::downloadId)

    val removedCount: Int
        get() = successfulIds.size
}

internal data class DownloadBatchCleanupSuccess(
    val target: DownloadBatchRemovalTarget,
    val cleanupWarnings: Set<DownloadRemovalFailureReason> = emptySet(),
    val partialFileRemoved: Boolean,
    val completedFileRemoved: Boolean,
)

internal data class DownloadBatchCleanupFailure(
    val target: DownloadBatchRemovalTarget,
    val reasons: Set<DownloadRemovalFailureReason>,
    val transferCancelled: Boolean,
)

internal data class DownloadBatchCleanupResult(
    val successes: List<DownloadBatchCleanupSuccess>,
    val failedTargets: List<DownloadBatchCleanupFailure>,
) {
    val successfulTargets: List<DownloadBatchRemovalTarget>
        get() = successes.map(DownloadBatchCleanupSuccess::target)

    val failures: List<DownloadRemovalFailure>
        get() = failedTargets.map { failure ->
            DownloadRemovalFailure(
                downloadId = failure.target.record.downloadId,
                reasons = failure.reasons,
            )
        }
}

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

    val successes = mutableListOf<DownloadBatchCleanupSuccess>()
    val failures = mutableListOf<DownloadBatchCleanupFailure>()
    targets.forEach { target ->
        val cancellationFailure = if (target.activeHandle != null) {
            if (runCatching { target.activeHandle.cancel() }.isFailure) {
                DownloadRemovalFailureReason.ActiveHandleCancellation
            } else {
                null
            }
        } else {
            if (runCatching { cancelPlatformTask(target.record.downloadId) }.isFailure) {
                DownloadRemovalFailureReason.PlatformTaskCancellation
            } else {
                null
            }
        }
        if (cancellationFailure != null) {
            failures += DownloadBatchCleanupFailure(
                target = target,
                reasons = setOf(cancellationFailure),
                transferCancelled = false,
            )
            return@forEach
        }

        val requestRemoved = runCatching {
            removeRequest(target.record.downloadId)
        }.getOrDefault(false)

        if (target.record.internalState == DownloadInternalState.Completed && !requestRemoved) {
            failures += DownloadBatchCleanupFailure(
                target = target,
                reasons = setOf(DownloadRemovalFailureReason.RequestCleanup),
                transferCancelled = true,
            )
            return@forEach
        }

        val partialFileRemoved = runCatching {
            removePartialFile(target.record)
        }.getOrDefault(false)

        if (target.record.internalState == DownloadInternalState.Completed && !partialFileRemoved) {
            failures += DownloadBatchCleanupFailure(
                target = target,
                reasons = setOf(DownloadRemovalFailureReason.PartialFileCleanup),
                transferCancelled = true,
            )
            return@forEach
        }

        if (target.record.internalState == DownloadInternalState.Completed) {
            val completedFileRemoved = runCatching {
                removeCompletedFile(target.record)
            }.getOrDefault(false)
            if (!completedFileRemoved) {
                failures += DownloadBatchCleanupFailure(
                    target = target,
                    reasons = setOf(DownloadRemovalFailureReason.CompletedFileCleanup),
                    transferCancelled = true,
                )
                return@forEach
            }
            successes += DownloadBatchCleanupSuccess(
                target = target,
                partialFileRemoved = true,
                completedFileRemoved = true,
            )
            return@forEach
        }

        val cleanupWarnings = buildSet {
            if (!requestRemoved) add(DownloadRemovalFailureReason.RequestCleanup)
            if (!partialFileRemoved) add(DownloadRemovalFailureReason.PartialFileCleanup)
        }
        successes += DownloadBatchCleanupSuccess(
            target = target,
            cleanupWarnings = cleanupWarnings,
            partialFileRemoved = partialFileRemoved,
            completedFileRemoved = false,
        )
    }
    return DownloadBatchCleanupResult(
        successes = successes,
        failedTargets = failures,
    )
}

internal fun settleDownloadBatchRemoval(
    cleanup: DownloadBatchCleanupResult,
    completedMediaIdsWithResolvedFiles: Set<String>,
    applyState: (
        successfulTargets: List<DownloadBatchRemovalTarget>,
        failedTargets: List<DownloadBatchCleanupFailure>,
    ) -> Set<String>,
    publishFinalState: () -> Unit,
    pumpScheduler: () -> Unit,
): DownloadBatchRemovalResult {
    val successfulIds = applyState(cleanup.successfulTargets, cleanup.failedTargets)
    val committedSuccesses = cleanup.successes.filter { success ->
        success.target.record.downloadId in successfulIds
    }
    val result = DownloadBatchRemovalResult(
        successfulIds = successfulIds,
        failures = cleanup.failures,
        cleanupWarnings = committedSuccesses.mapNotNull { success ->
            success.cleanupWarnings.takeIf(Set<DownloadRemovalFailureReason>::isNotEmpty)?.let { reasons ->
                DownloadRemovalFailure(
                    downloadId = success.target.record.downloadId,
                    reasons = reasons,
                )
            }
        },
        bytesReclaimed = committedSuccesses.sumOf { success ->
            val record = success.target.record
            when {
                record.internalState == DownloadInternalState.Completed -> {
                    if (
                        record.downloadId in completedMediaIdsWithResolvedFiles &&
                        success.completedFileRemoved
                    ) {
                        record.reclaimableBytes()
                    } else {
                        0L
                    }
                }
                success.partialFileRemoved -> record.reclaimableBytes()
                else -> 0L
            }
        },
    )
    publishFinalState()
    pumpScheduler()
    return result
}

internal fun performPostCommitDownloadBatchCleanup(
    targets: Collection<DownloadBatchRemovalTarget>,
    cancelPlatformTask: (String) -> Unit,
    removeRequest: (String) -> Boolean,
    removeCompletedFile: (DownloadRecord) -> Boolean,
    removePartialFile: (DownloadRecord) -> Boolean,
) {
    targets.forEach { target ->
        runCatching {
            target.activeHandle?.cancel() ?: cancelPlatformTask(target.record.downloadId)
        }
        runCatching { removeRequest(target.record.downloadId) }
        runCatching { removePartialFile(target.record) }
        runCatching { removeCompletedFile(target.record) }
    }
}

internal fun DownloadRecord.reclaimableBytes(): Long = when {
    downloadedBytes > 0L -> downloadedBytes
    internalState == DownloadInternalState.Completed -> expectedBytes?.coerceAtLeast(0L) ?: 0L
    else -> 0L
}
