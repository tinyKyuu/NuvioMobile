package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private val log = Logger.withTag("Downloads")
    private val stateLock = SynchronizedObject()
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val store: DownloadsCatalogStore
        get() = DownloadsCatalogStoreProvider.store

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    private val runtimeRecordsById = mutableMapOf<String, DownloadRecord>()
    private val progressPersistencePolicy = DownloadProgressPersistencePolicy()
    private var currentProfileRecords: List<DownloadRecord> = emptyList()
    private var loadedProfileKey: String? = null
    private var nextDownloadOrdinal = 0L
    private var nextEventOrdinal = 0L

    fun ensureLoaded() {
        val ownerProfileKey = activeOwnerProfileKey()
        val changed = synchronized(stateLock) {
            if (loadedProfileKey == ownerProfileKey) {
                false
            } else {
                loadProfileLocked(
                    profileId = ProfileRepository.activeProfileId,
                    ownerProfileKey = ownerProfileKey,
                )
                true
            }
        }
        if (changed) {
            notifyLiveStatusPlatform()
            pumpScheduler()
        }
    }

    fun onProfileChanged() {
        val profileId = ProfileRepository.activeProfileId
        val ownerProfileKey = downloadOwnerProfileKey(profileId)
        synchronized(stateLock) {
            loadProfileLocked(profileId, ownerProfileKey)
        }
        notifyLiveStatusPlatform()
        pumpScheduler()
    }

    fun clearLocalState() {
        val handles = synchronized(stateLock) {
            val detached = activeHandles.values.toList()
            activeHandles.clear()
            runtimeRecordsById.clear()
            progressPersistencePolicy.clear()
            currentProfileRecords = emptyList()
            loadedProfileKey = null
            _uiState.value = DownloadsUiState()
            detached
        }
        handles.forEach(DownloadsTaskHandle::cancel)
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        return synchronized(stateLock) {
            selectPlayableDownloadByVideoId(
                items = _uiState.value.items,
                videoId = videoId,
                resolveLocalFileUri = DownloadsPlatformDownloader::resolveLocalFileUri,
            )
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        return synchronized(stateLock) {
            selectPlayableDownload(
                items = _uiState.value.items,
                parentMetaId = parentMetaId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                videoId = videoId,
                resolveLocalFileUri = DownloadsPlatformDownloader::resolveLocalFileUri,
            )
        }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            relativeMediaPath = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            synchronized(stateLock) {
                val current = runtimeRecordsById[item.id]
                    ?: currentProfileRecords.firstOrNull { it.downloadId == item.id }
                if (current != null && current.internalState == DownloadInternalState.Completed) {
                    val updated = current.withRuntimeItem(
                        current.item.copy(localFileUri = resolvedUri),
                    )
                    runtimeRecordsById[item.id] = updated
                    replaceCurrentProfileRecordLocked(updated)
                    publishCurrentProfileLocked()
                }
            }
            notifyLiveStatusPlatform()
        }

        return resolvedUri
    }

    fun exportDownload(item: DownloadItem): Boolean {
        val localFileUri = playableLocalFileUri(item) ?: return false
        return DownloadsPlatformDownloader.exportFile(localFileUri)
    }

    internal fun evaluateEnqueue(request: DownloadEnqueueRequest): DownloadEnqueueDecision {
        ensureLoaded()
        return synchronized(stateLock) {
            decideDownloadEnqueue(
                items = _uiState.value.items,
                request = request,
                activeProfileId = ProfileRepository.activeProfileId,
            )
        }
    }

    internal fun enqueue(
        request: DownloadEnqueueRequest,
        replacingDownloadId: String? = null,
    ): DownloadEnqueueResult {
        ensureLoaded()
        val normalizedRequest = request.normalized()
        val eligibility = evaluateDownloadEligibility(normalizedRequest)
        if (eligibility is DownloadEligibility.Ineligible) {
            return eligibility.reason.toEnqueueResult()
        }

        val now = DownloadsClock.nowEpochMs()
        val commit = synchronized(stateLock) {
            val decision = decideDownloadEnqueue(
                items = _uiState.value.items,
                request = normalizedRequest,
                activeProfileId = ProfileRepository.activeProfileId,
            )
            val replacedRecord = when (decision) {
                DownloadEnqueueDecision.Enqueue -> {
                    if (replacingDownloadId != null) {
                        return@synchronized EnqueueCommit(
                            result = DownloadEnqueueResult.ReplacementRequired,
                        )
                    }
                    null
                }
                is DownloadEnqueueDecision.ExistingExact -> {
                    return@synchronized EnqueueCommit(
                        result = DownloadEnqueueResult.AlreadyExists,
                    )
                }
                is DownloadEnqueueDecision.ConfirmReplacement -> {
                    if (decision.item.id != replacingDownloadId) {
                        return@synchronized EnqueueCommit(
                            result = DownloadEnqueueResult.ReplacementRequired,
                        )
                    }
                    currentProfileRecords.firstOrNull { it.downloadId == decision.item.id }
                        ?: return@synchronized EnqueueCommit(
                            result = DownloadEnqueueResult.ReplacementRequired,
                        )
                }
                is DownloadEnqueueDecision.Ineligible -> {
                    return@synchronized EnqueueCommit(
                        result = decision.reason.toEnqueueResult(),
                    )
                }
                DownloadEnqueueDecision.ProfileChanged -> {
                    return@synchronized EnqueueCommit(
                        result = DownloadEnqueueResult.ProfileChanged,
                    )
                }
            }

            val downloadId = nextDownloadIdLocked(now)
            val ownerProfileKey = downloadOwnerProfileKey(normalizedRequest.profileId)
            val item = normalizedRequest.toDownloadItem(
                downloadId = downloadId,
                nowEpochMs = now,
            )
            val record = item.toDownloadRecord(
                ownerProfileKey = ownerProfileKey,
                nowEpochMs = now,
            )
            saveRequestLocked(record)
            try {
                store.commit(
                    recordsToUpsert = listOf(record),
                    downloadIdsToDelete = listOfNotNull(replacedRecord?.downloadId),
                )
            } catch (error: Throwable) {
                DownloadsRequestStorage.remove(record.downloadId)
                throw error
            }
            replacedRecord?.let { old ->
                DownloadsRequestStorage.remove(old.downloadId)
                runtimeRecordsById.remove(old.downloadId)
                progressPersistencePolicy.remove(old.downloadId)
            }
            runtimeRecordsById[record.downloadId] = record
            progressPersistencePolicy.recordImmediate(record)
            currentProfileRecords = buildList {
                add(record)
                currentProfileRecords.filterTo(this) { it.downloadId != replacedRecord?.downloadId }
            }
            publishCurrentProfileLocked()
            EnqueueCommit(
                result = if (replacedRecord == null) {
                    DownloadEnqueueResult.Started
                } else {
                    DownloadEnqueueResult.Replaced
                },
                record = record,
                replacedRecord = replacedRecord,
                detachedHandle = replacedRecord?.let { activeHandles.remove(it.downloadId) },
            )
        }

        val record = commit.record ?: return commit.result
        commit.detachedHandle?.cancel()
            ?: commit.replacedRecord?.let { DownloadsPlatformDownloader.cancel(it.downloadId) }
        commit.replacedRecord?.let { old ->
            DownloadsPlatformDownloader.removeFile(resolveLocalUri(old))
            DownloadsPlatformDownloader.removePartialFile(old.downloadId, old.item.fileName)
            log.i {
                "event=logical_replace download_id=${record.downloadId} replaced_download_id=${old.downloadId}"
            }
        }
        notifyLiveStatusPlatform()
        log.i {
            "event=record_created download_id=${record.downloadId} owner_profile=${record.ownerProfileKey} state=${record.internalState} downloaded_bytes=0 total_bytes=unknown"
        }
        pumpScheduler()
        return commit.result
    }

    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        val now = DownloadsClock.nowEpochMs()
        val result = applyEvent(
            downloadId = downloadId,
            eventFactory = { eventId ->
                DownloadRecordEvent.Pause(
                    eventId = eventId,
                    occurredAtEpochMs = now,
                    reason = "user_pause",
                )
            },
            eventName = "user_pause",
            detachHandle = true,
        )
        result.detachedHandle?.pause() ?: DownloadsPlatformDownloader.pause(downloadId)
        pumpScheduler()
    }

    fun pauseActiveDownloads() {
        ensureLoaded()
        val activeIds = synchronized(stateLock) {
            currentProfileRecords
                .filter { it.internalState == DownloadInternalState.Downloading }
                .map { it.downloadId }
        }
        activeIds.forEach(::pauseDownload)
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val now = DownloadsClock.nowEpochMs()
        val result = applyEvent(
            downloadId = downloadId,
            eventFactory = { eventId ->
                DownloadRecordEvent.Resume(
                    eventId = eventId,
                    occurredAtEpochMs = now,
                    reason = "user_resume",
                )
            },
            eventName = "user_resume",
        )
        if (result.changed) pumpScheduler()
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        val now = DownloadsClock.nowEpochMs()
        val result = applyEvent(
            downloadId = downloadId,
            eventFactory = { eventId ->
                DownloadRecordEvent.Delete(
                    eventId = eventId,
                    occurredAtEpochMs = now,
                )
            },
            eventName = "user_delete",
            detachHandle = true,
        )
        result.detachedHandle?.cancel() ?: DownloadsPlatformDownloader.cancel(downloadId)
        result.recordBefore?.let { old ->
            DownloadsPlatformDownloader.removeFile(resolveLocalUri(old))
            DownloadsPlatformDownloader.removePartialFile(old.downloadId, old.item.fileName)
            log.i {
                "event=record_deleted download_id=$downloadId owner_profile=${old.ownerProfileKey} prior_state=${old.internalState} downloaded_bytes=${old.downloadedBytes} total_bytes=${old.expectedBytes ?: "unknown"}"
            }
        }
        pumpScheduler()
    }

    fun cancelDownloads(downloadIds: Collection<String>): Int {
        ensureLoaded()
        val ownerProfileKey = activeOwnerProfileKey()
        val targets = synchronized(stateLock) {
            if (loadedProfileKey != ownerProfileKey || activeOwnerProfileKey() != ownerProfileKey) {
                return@synchronized emptyList()
            }

            val removedRecords = removeProfileRecordsFromCatalogAsBatch(
                store = store,
                ownerProfileKey = ownerProfileKey,
                requestedDownloadIds = downloadIds,
                runtimeRecordsById = runtimeRecordsById,
            )
            if (removedRecords.isEmpty()) return@synchronized emptyList()

            val removedIds = removedRecords.mapTo(hashSetOf(), DownloadRecord::downloadId)
            val removedTargets = removedRecords.map { record ->
                DownloadBatchRemovalTarget(
                    record = record,
                    activeHandle = activeHandles.remove(record.downloadId),
                )
            }
            removedIds.forEach { downloadId ->
                runtimeRecordsById.remove(downloadId)
                progressPersistencePolicy.remove(downloadId)
            }
            currentProfileRecords = currentProfileRecords.filterNot { it.downloadId in removedIds }
            removedTargets
        }

        performDownloadBatchCleanup(
            targets = targets,
            cancelPlatformTask = DownloadsPlatformDownloader::cancel,
            removeRequest = DownloadsRequestStorage::remove,
            removeCompletedFile = { record ->
                DownloadsPlatformDownloader.removeFile(resolveLocalUri(record))
            },
            removePartialFile = { record ->
                DownloadsPlatformDownloader.removePartialFile(
                    downloadId = record.downloadId,
                    destinationFileName = record.item.fileName,
                )
            },
            onBatchCleaned = {
                targets.forEach { target ->
                    val record = target.record
                    log.i {
                        "event=record_deleted_batch download_id=${record.downloadId} owner_profile=${record.ownerProfileKey} prior_state=${record.internalState} downloaded_bytes=${record.downloadedBytes} total_bytes=${record.expectedBytes ?: "unknown"}"
                    }
                }
                pumpScheduler(forcePublishCurrentProfile = true)
            },
        )
        return targets.size
    }

    private fun loadProfileLocked(
        profileId: Int,
        ownerProfileKey: String,
    ) {
        migrateLegacyPayloadLocked(profileId, ownerProfileKey)

        val storedRecords = store.recordsForProfile(ownerProfileKey).map(::hydrateRequestLocked)
        val normalizedRecords = storedRecords.map { stored ->
            stored.normalizeForColdLaunch(
                resolveLocalFileUri = DownloadsPlatformDownloader::resolveLocalFileUri,
                preservePlatformActiveState = DownloadsPlatformDownloader.supportsPersistentBackgroundTransfers,
            )
        }
        val changedRecords = normalizedRecords.filterIndexed { index, normalized ->
            !normalized.hasSameDurableContents(storedRecords[index])
        }
        if (changedRecords.isNotEmpty()) {
            store.commit(recordsToUpsert = changedRecords)
            log.i {
                "event=cold_launch_normalized owner_profile=$ownerProfileKey item_count=${changedRecords.size}"
            }
        }

        normalizedRecords.forEach { stored ->
            val runtime = runtimeRecordsById[stored.downloadId]
                ?.takeIf { it.ownerProfileKey == ownerProfileKey && it.updatedAtEpochMs > stored.updatedAtEpochMs }
                ?: stored
            runtimeRecordsById[stored.downloadId] = runtime
            progressPersistencePolicy.recordImmediate(runtime)
        }
        currentProfileRecords = normalizedRecords.map { stored ->
            runtimeRecordsById[stored.downloadId] ?: stored
        }
        loadedProfileKey = ownerProfileKey
        publishCurrentProfileLocked()
    }

    private fun migrateLegacyPayloadLocked(
        profileId: Int,
        ownerProfileKey: String,
    ) {
        val payload = DownloadsStorage.loadLegacyPayload(profileId)?.trim().orEmpty()
        if (payload.isEmpty()) return

        val legacyItems = DownloadsCodec.decodeItemsOrNull(payload)
        if (legacyItems == null) {
            log.w {
                "event=legacy_migration_skipped owner_profile=$ownerProfileKey reason=invalid_payload"
            }
            return
        }

        val existingIds = store.recordsForProfile(ownerProfileKey)
            .mapTo(mutableSetOf(), DownloadRecord::downloadId)
        val recordsToMigrate = migrateLegacyDownloadItems(
            items = legacyItems.filterNot { it.id in existingIds },
            ownerProfileKey = ownerProfileKey,
            resolveLocalFileUri = DownloadsPlatformDownloader::resolveLocalFileUri,
        )
        if (recordsToMigrate.isNotEmpty()) {
            val resumableRecords = recordsToMigrate.filter {
                it.internalState != DownloadInternalState.Completed && it.item.sourceUrl.isNotBlank()
            }
            val requestsSaved = resumableRecords.all(::saveRequestLocked)
            if (!requestsSaved) {
                log.w {
                    "event=legacy_migration_skipped owner_profile=$ownerProfileKey reason=request_persistence_failed"
                }
                return
            }
            try {
                store.commit(recordsToUpsert = recordsToMigrate)
            } catch (error: Throwable) {
                resumableRecords.forEach { DownloadsRequestStorage.remove(it.downloadId) }
                throw error
            }
        }
        DownloadsStorage.removeLegacyPayload(profileId)
        log.i {
            "event=legacy_migration_completed owner_profile=$ownerProfileKey migrated_count=${recordsToMigrate.size} legacy_count=${legacyItems.size}"
        }
    }

    private fun startDownload(record: DownloadRecord) {
        if (record.internalState != DownloadInternalState.Downloading) return
        val request = DownloadPlatformRequest(
            downloadId = record.downloadId,
            ownerProfileKey = record.ownerProfileKey,
            sourceUrl = record.item.sourceUrl,
            sourceHeaders = record.item.sourceHeaders,
            destinationFileName = record.item.fileName,
            resumeDownloadedBytes = record.downloadedBytes,
            networkPolicy = DownloadsNetworkPolicyRepository.policy.value,
        )

        log.i {
            "event=transfer_start download_id=${record.downloadId} owner_profile=${record.ownerProfileKey} state=${record.internalState} downloaded_bytes=${record.downloadedBytes} total_bytes=${record.expectedBytes ?: "unknown"}"
        }
        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onTaskCreated = { sessionIdentifier, taskIdentifier ->
                onPlatformTaskCreated(
                    downloadId = record.downloadId,
                    sessionIdentifier = sessionIdentifier,
                    taskIdentifier = taskIdentifier,
                )
            },
            onWaitingForConnectivity = {
                onPlatformWaitingForConnectivity(record.downloadId)
            },
            onProgress = { downloadedBytes, totalBytes ->
                onPlatformProgress(
                    downloadId = record.downloadId,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                )
            },
            onFinalizing = {
                onPlatformFinalizing(record.downloadId)
            },
            onSuccess = { localFileUri, relativeMediaPath, totalBytes ->
                onPlatformSuccess(
                    downloadId = record.downloadId,
                    localFileUri = localFileUri,
                    relativeMediaPath = relativeMediaPath,
                    totalBytes = totalBytes,
                )
            },
            onFailure = { message ->
                onPlatformFailure(
                    downloadId = record.downloadId,
                    message = message,
                )
            },
        )

        val shouldKeep = synchronized(stateLock) {
            val current = runtimeRecordsById[record.downloadId]
                ?: store.recordById(record.downloadId)
            if (current?.internalState == DownloadInternalState.Downloading) {
                activeHandles.put(record.downloadId, handle)?.cancel()
                true
            } else {
                false
            }
        }
        if (!shouldKeep) handle.cancel()
    }

    internal fun onPlatformTaskCreated(
        downloadId: String,
        sessionIdentifier: String?,
        taskIdentifier: Long?,
    ) {
        val now = DownloadsClock.nowEpochMs()
        applyEvent(
            downloadId = downloadId,
            eventFactory = { eventId ->
                DownloadRecordEvent.BindPlatformTask(
                    eventId = eventId,
                    occurredAtEpochMs = now,
                    sessionIdentifier = sessionIdentifier,
                    taskIdentifier = taskIdentifier,
                )
            },
            eventName = "platform_task_created",
        )
    }

    internal fun onPlatformProgress(
        downloadId: String,
        downloadedBytes: Long,
        totalBytes: Long?,
    ) {
        val now = DownloadsClock.nowEpochMs()
        applyEvent(
            downloadId = downloadId,
            eventFactory = { eventId ->
                DownloadRecordEvent.Progress(
                    eventId = eventId,
                    occurredAtEpochMs = now,
                    downloadedBytes = downloadedBytes,
                    expectedBytes = totalBytes,
                )
            },
            eventName = "platform_progress",
        )
    }

    internal fun onPlatformWaitingForConnectivity(downloadId: String) {
        val now = DownloadsClock.nowEpochMs()
        applyEvent(
            downloadId = downloadId,
            eventFactory = { eventId ->
                DownloadRecordEvent.WaitingForConnectivity(
                    eventId = eventId,
                    occurredAtEpochMs = now,
                    reason = "network_policy",
                )
            },
            eventName = "platform_waiting_for_connectivity",
        )
    }

    internal fun onPlatformFinalizing(downloadId: String) {
        val now = DownloadsClock.nowEpochMs()
        applyEvent(
            downloadId = downloadId,
            eventFactory = { eventId ->
                DownloadRecordEvent.Finalize(
                    eventId = eventId,
                    occurredAtEpochMs = now,
                )
            },
            eventName = "platform_finalizing",
        )
    }

    internal fun onPlatformSuccess(
        downloadId: String,
        localFileUri: String,
        relativeMediaPath: String,
        totalBytes: Long?,
    ) {
        val now = DownloadsClock.nowEpochMs()
        val result = applyEvent(
            downloadId = downloadId,
            eventFactory = { eventId ->
                DownloadRecordEvent.Complete(
                    eventId = eventId,
                    occurredAtEpochMs = now,
                    localFileUri = localFileUri,
                    relativeMediaPath = relativeMediaPath,
                    totalBytes = totalBytes,
                )
            },
            eventName = "platform_complete",
            detachHandle = true,
        )
        if (result.changed) pumpScheduler()
    }

    internal fun onPlatformFailure(
        downloadId: String,
        message: String,
    ) {
        val now = DownloadsClock.nowEpochMs()
        val result = applyEvent(
            downloadId = downloadId,
            eventFactory = { eventId ->
                DownloadRecordEvent.Failure(
                    eventId = eventId,
                    occurredAtEpochMs = now,
                    message = message.ifBlank {
                        runBlocking { getString(Res.string.download_failed) }
                    },
                )
            },
            eventName = "platform_failure",
            detachHandle = true,
        )
        if (result.changed) pumpScheduler()
    }

    internal fun shouldRetainPlatformTask(downloadId: String): Boolean = synchronized(stateLock) {
        val record = runtimeRecordsById[downloadId] ?: store.recordById(downloadId)
        record?.internalState == DownloadInternalState.Downloading ||
            record?.internalState == DownloadInternalState.WaitingForNetwork ||
            record?.internalState == DownloadInternalState.Finalizing
    }

    internal fun onPlatformReconciliation(
        sessionIdentifier: String,
        snapshots: List<DownloadPlatformTaskSnapshot>,
    ) {
        val records = synchronized(stateLock) { store.allRecords() }

        snapshots.filter { it.state.canContinueAfterLaunch }.forEach { snapshot ->
            onPlatformTaskCreated(
                downloadId = snapshot.downloadId,
                sessionIdentifier = snapshot.sessionIdentifier,
                taskIdentifier = snapshot.taskIdentifier,
            )
            onPlatformProgress(
                downloadId = snapshot.downloadId,
                downloadedBytes = snapshot.downloadedBytes,
                totalBytes = snapshot.totalBytes,
            )
        }

        val missingRecords = findActiveRecordsMissingPlatformTasks(records, snapshots)
        missingRecords
            .forEach { record ->
                onPlatformFailure(
                    downloadId = record.downloadId,
                    message = runBlocking { getString(Res.string.download_failed) },
                )
            }

        log.i {
            "event=platform_reconciled session_id=$sessionIdentifier task_count=${snapshots.size} missing_count=${missingRecords.size}"
        }
        pumpScheduler()
    }

    private fun pumpScheduler(forcePublishCurrentProfile: Boolean = false) {
        var shouldNotify = false
        val scheduled = synchronized(stateLock) {
            val durableRecords = store.allRecords().map { stored ->
                runtimeRecordsById[stored.downloadId]
                    ?.takeIf { it.updatedAtEpochMs >= stored.updatedAtEpochMs }
                    ?: hydrateRequestLocked(stored)
            }
            val queued = selectQueuedDownloadsToStart(durableRecords)
            val now = DownloadsClock.nowEpochMs()
            val nextRecords = queued.mapNotNull { queuedRecord ->
                val current = runtimeRecordsById[queuedRecord.downloadId] ?: queuedRecord
                val reduction = reduceDownloadRecord(
                    current = current,
                    event = DownloadRecordEvent.ScheduleStart(
                        eventId = nextEventIdLocked("scheduler_start", current.downloadId),
                        occurredAtEpochMs = now,
                    ),
                )
                reduction.record?.takeIf { reduction.changed }
            }
            if (nextRecords.isNotEmpty()) {
                store.commit(recordsToUpsert = nextRecords)
            }
            nextRecords.forEach { next ->
                runtimeRecordsById[next.downloadId] = next
                progressPersistencePolicy.recordImmediate(next)
                if (loadedProfileKey == next.ownerProfileKey) {
                    replaceCurrentProfileRecordLocked(next)
                    shouldNotify = true
                }
                log.i {
                    "event=scheduler_start download_id=${next.downloadId} owner_profile=${next.ownerProfileKey} max_concurrent=$MAX_CONCURRENT_DOWNLOADS"
                }
            }
            if (forcePublishCurrentProfile || shouldNotify) {
                publishCurrentProfileLocked()
                shouldNotify = true
            }
            nextRecords
        }

        if (shouldNotify) notifyLiveStatusPlatform()
        scheduled.forEach(::startDownload)
    }

    private fun applyEvent(
        downloadId: String,
        eventFactory: (eventId: String) -> DownloadRecordEvent,
        eventName: String,
        detachHandle: Boolean = false,
    ): AppliedEventResult {
        var shouldNotify = false
        val result = synchronized(stateLock) {
            val current = runtimeRecordsById[downloadId]
                ?: currentProfileRecords.firstOrNull { it.downloadId == downloadId }
                ?: store.recordById(downloadId)?.let(::hydrateRequestLocked)
                ?: return@synchronized AppliedEventResult()
            val event = eventFactory(nextEventIdLocked(eventName, downloadId))
            val reduction = reduceDownloadRecord(current, event)
            if (!reduction.changed) {
                return@synchronized AppliedEventResult(recordBefore = current, recordAfter = current)
            }

            val next = reduction.record
            if (next == null) {
                store.commit(downloadIdsToDelete = listOf(downloadId))
                DownloadsRequestStorage.remove(downloadId)
                runtimeRecordsById.remove(downloadId)
                progressPersistencePolicy.remove(downloadId)
                if (loadedProfileKey == current.ownerProfileKey) {
                    currentProfileRecords = currentProfileRecords.filterNot { it.downloadId == downloadId }
                    publishCurrentProfileLocked()
                    shouldNotify = true
                }
            } else {
                runtimeRecordsById[downloadId] = next
                val shouldPersist = reduction.persistImmediately || progressPersistencePolicy.shouldPersist(next)
                if (shouldPersist) {
                    store.commit(recordsToUpsert = listOf(next))
                    if (reduction.persistImmediately) {
                        progressPersistencePolicy.recordImmediate(next)
                    }
                }
                if (next.internalState == DownloadInternalState.Completed) {
                    DownloadsRequestStorage.remove(downloadId)
                }
                if (loadedProfileKey == next.ownerProfileKey) {
                    replaceCurrentProfileRecordLocked(next)
                    publishCurrentProfileLocked()
                    shouldNotify = true
                }
                logStateTransition(current, next, reason = eventName)
            }

            val handle = if (detachHandle || next?.internalState?.isTerminal == true) {
                activeHandles.remove(downloadId)
            } else {
                null
            }
            AppliedEventResult(
                recordBefore = current,
                recordAfter = next,
                detachedHandle = handle,
                changed = true,
            )
        }
        if (shouldNotify) notifyLiveStatusPlatform()
        return result
    }

    private fun replaceCurrentProfileRecordLocked(record: DownloadRecord) {
        currentProfileRecords = currentProfileRecords.map { existing ->
            if (existing.downloadId == record.downloadId) record else existing
        }
    }

    private fun saveRequestLocked(record: DownloadRecord): Boolean {
        val request = record.item.toStoredDownloadRequest()
        if (request.sourceUrl.isBlank()) return true
        val saved = DownloadsRequestStorage.savePayload(
            downloadId = record.downloadId,
            payload = DownloadsRequestCodec.encode(request),
        )
        if (!saved) {
            log.w {
                "event=request_persistence_failed download_id=${record.downloadId} owner_profile=${record.ownerProfileKey}"
            }
        }
        return saved
    }

    private fun hydrateRequestLocked(record: DownloadRecord): DownloadRecord {
        if (record.internalState == DownloadInternalState.Completed) {
            DownloadsRequestStorage.remove(record.downloadId)
            return record
        }
        if (record.item.sourceUrl.isNotBlank()) {
            saveRequestLocked(record)
            return record
        }
        val request = DownloadsRequestStorage.loadPayload(record.downloadId)
            ?.let(DownloadsRequestCodec::decodeOrNull)
            ?: return record
        return record.withStoredDownloadRequest(request)
    }

    private fun publishCurrentProfileLocked() {
        _uiState.value = DownloadsUiState(
            items = currentProfileRecords.map(::runtimeItemFor),
        )
    }

    private fun runtimeItemFor(record: DownloadRecord): DownloadItem {
        val localFileUri = if (record.internalState == DownloadInternalState.Completed) {
            resolveLocalUri(record)
        } else {
            null
        }
        return record.item.copy(
            status = record.internalState.toVisibleStatus(),
            localFileUri = localFileUri,
            downloadedBytes = record.downloadedBytes,
            totalBytes = record.expectedBytes,
            errorMessage = record.stateReason,
            createdAtEpochMs = record.createdAtEpochMs,
            updatedAtEpochMs = record.updatedAtEpochMs,
        )
    }

    private fun resolveLocalUri(record: DownloadRecord): String? {
        val relativeMediaPath = record.relativeMediaPath
            ?.takeIf { it.isNotBlank() }
            ?: record.item.fileName
        return DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = record.item.localFileUri,
            relativeMediaPath = relativeMediaPath,
        )
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun activeOwnerProfileKey(): String =
        downloadOwnerProfileKey(ProfileRepository.activeProfileId)

    private fun nextDownloadIdLocked(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun nextEventIdLocked(eventName: String, downloadId: String): String {
        nextEventOrdinal += 1L
        return "$eventName:$downloadId:${nextEventOrdinal.toString(36)}"
    }

    private fun logStateTransition(
        before: DownloadRecord,
        after: DownloadRecord,
        reason: String,
    ) {
        if (before.internalState == after.internalState) return
        log.i {
            "event=state_transition download_id=${after.downloadId} owner_profile=${after.ownerProfileKey} from=${before.internalState} to=${after.internalState} reason=$reason downloaded_bytes=${after.downloadedBytes} total_bytes=${after.expectedBytes ?: "unknown"}"
        }
    }
}

private val DownloadInternalState.isTerminal: Boolean
    get() = this == DownloadInternalState.Completed ||
        this == DownloadInternalState.FailedRecoverable ||
        this == DownloadInternalState.FailedPermanent

private data class EnqueueCommit(
    val result: DownloadEnqueueResult,
    val record: DownloadRecord? = null,
    val replacedRecord: DownloadRecord? = null,
    val detachedHandle: DownloadsTaskHandle? = null,
)

private data class AppliedEventResult(
    val recordBefore: DownloadRecord? = null,
    val recordAfter: DownloadRecord? = null,
    val detachedHandle: DownloadsTaskHandle? = null,
    val changed: Boolean = false,
)

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
    nowEpochMs: Long,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(nowEpochMs.toString(36))
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

private fun DownloadEligibilityReason.toEnqueueResult(): DownloadEnqueueResult =
    if (this == DownloadEligibilityReason.MissingUrl) {
        DownloadEnqueueResult.MissingUrl
    } else {
        DownloadEnqueueResult.UnsupportedFormat
    }

private fun DownloadEnqueueRequest.toDownloadItem(
    downloadId: String,
    nowEpochMs: Long,
): DownloadItem = DownloadItem(
    id = downloadId,
    contentType = contentType,
    parentMetaId = parentMetaId,
    parentMetaType = parentMetaType,
    videoId = videoId,
    title = title,
    logo = logo,
    poster = poster,
    background = background,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeTitle = episodeTitle,
    episodeThumbnail = episodeThumbnail,
    streamTitle = streamTitle,
    streamSubtitle = streamSubtitle,
    providerName = providerName,
    providerAddonId = providerAddonId,
    sourceFingerprint = sourceFingerprint(),
    sourceUrl = sourceUrl,
    sourceHeaders = sourceHeaders,
    sourceResponseHeaders = sourceResponseHeaders,
    localFileUri = null,
    fileName = buildFileName(
        title = title,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        fallbackTitle = streamTitle,
        sourceUrl = sourceUrl,
        nowEpochMs = nowEpochMs,
    ),
    status = DownloadStatus.Queued,
    downloadedBytes = 0L,
    totalBytes = null,
    errorMessage = null,
    createdAtEpochMs = nowEpochMs,
    updatedAtEpochMs = nowEpochMs,
)
