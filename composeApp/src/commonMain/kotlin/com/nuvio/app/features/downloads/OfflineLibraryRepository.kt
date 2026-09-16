package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.OfflineMetaFetchResult
import com.nuvio.app.features.details.OfflineMetaValidators
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object OfflineLibraryRepository {
    private const val MaxArtworkBytes = 8 * 1024 * 1024
    private const val MaxAutomaticBackfillTitles = 8

    private val log = Logger.withTag("OfflineLibrary")
    private val stateLock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val refreshSlots = Semaphore(2)
    private val activeRefreshes = mutableMapOf<String, Job>()
    private val activeArtworkRefreshes = mutableMapOf<String, ArtworkRefresh>()
    private val _uiState = MutableStateFlow(OfflineLibraryUiState())
    internal val uiState: StateFlow<OfflineLibraryUiState> = _uiState.asStateFlow()

    private val store: OfflineLibraryStore
        get() = OfflineLibraryStoreProvider.store

    private var loadedOwnerProfileKey: String? = null
    private var currentDownloads: List<DownloadItem> = emptyList()

    fun ensureLoaded() {
        DownloadsRepository.ensureLoaded()
    }

    fun details(type: String, id: String): MetaDetails? {
        ensureLoaded()
        val normalizedType = canonicalOfflineMetaType(type)
        return _uiState.value.titles.firstOrNull { title ->
            title.record.metaId == id && canonicalOfflineMetaType(title.record.metaType) == normalizedType
        }?.toMetaDetails()
    }

    fun captureNormalDetails(
        requestedType: String,
        requestedId: String,
        meta: MetaDetails,
        sourceUrl: String? = null,
        providerAddonId: String? = null,
        etag: String? = null,
        lastModified: String? = null,
    ) {
        if (meta.id.isBlank() || meta.name.isBlank()) return
        if (canonicalOfflineMetaType(meta.type) != canonicalOfflineMetaType(requestedType)) return
        ensureLoaded()
        val owner = downloadOwnerProfileKey(ProfileRepository.activeProfileId)
        val key = offlineTitleKey(owner, canonicalOfflineMetaType(requestedType), requestedId)
        val now = DownloadsClock.nowEpochMs()
        val updated = synchronized(stateLock) {
            val record = store.record(key) ?: return@synchronized null
            val downloads = currentDownloads.filter { it.id in record.downloadIds }
            if (downloads.isEmpty()) return@synchronized null
            val mergedMetadata = mergeOfflineMetadata(record.metadata, meta, downloads)
            record.copy(
                metadata = mergedMetadata,
                providerAddonIds = providerAddonId
                    ?.let { addonId -> record.providerAddonIds + addonId }
                    ?: record.providerAddonIds,
                providerMetaIds = record.providerMetaIds + meta.id + requestedId,
                metadataSourceUrl = sourceUrl ?: record.metadataSourceUrl,
                metadataEtag = etag ?: record.metadataEtag,
                metadataLastModified = lastModified ?: record.metadataLastModified,
                lastSuccessfulRefreshEpochMs = now,
                lastRefreshFailureEpochMs = null,
                refreshFailureCount = 0,
                nextRetryEpochMs = null,
                metadataComplete = true,
                generation = if (mergedMetadata != record.metadata) record.generation + 1L else record.generation,
                updatedAtEpochMs = now,
            ).also { store.commit(recordsToUpsert = listOf(it)) }
        } ?: return
        publishCurrentProfile()
        refreshArtwork(updated.key, updated.generation, validateExisting = false)
    }

    fun refresh(type: String, id: String, manual: Boolean = false) {
        ensureLoaded()
        val owner = downloadOwnerProfileKey(ProfileRepository.activeProfileId)
        val key = offlineTitleKey(owner, canonicalOfflineMetaType(type), id)
        refreshRecord(key, manual)
    }

    fun refreshMissingAndStale() {
        ensureLoaded()
        val now = DownloadsClock.nowEpochMs()
        val candidates = synchronized(stateLock) {
            val activeMetadataKeys = activeRefreshes
                .filterValues(Job::isActive)
                .keys
            val activeArtworkKeys = activeArtworkRefreshes
                .filterValues { refresh -> refresh.job.isActive }
                .keys
            planOfflineAutomaticRefresh(
                records = _uiState.value.titles.map(OfflineTitle::record),
                activeMetadataKeys = activeMetadataKeys,
                activeArtworkKeys = activeArtworkKeys,
                nowEpochMs = now,
                maxTitles = MaxAutomaticBackfillTitles,
            )
        }
        candidates.metadataKeys.forEach { key -> refreshRecord(key, manual = false) }
        candidates.artwork.forEach { (key, generation) ->
            refreshArtwork(key = key, generation = generation, validateExisting = false)
        }
    }

    internal fun onDownloadsChanged(
        ownerProfileKey: String,
        downloads: List<DownloadItem>,
    ) {
        val now = DownloadsClock.nowEpochMs()
        val removedArtwork = synchronized(stateLock) {
            loadedOwnerProfileKey = ownerProfileKey
            currentDownloads = downloads
            activeRefreshes.keys
                .filter { key -> store.record(key)?.ownerProfileKey != ownerProfileKey }
                .forEach { key -> activeRefreshes.remove(key)?.cancel() }
            activeArtworkRefreshes.keys
                .filter { key -> store.record(key)?.ownerProfileKey != ownerProfileKey }
                .forEach { key -> activeArtworkRefreshes.remove(key)?.job?.cancel() }
            val reconciliation = reconcileOfflineRecords(
                existingRecords = store.recordsForProfile(ownerProfileKey),
                ownerProfileKey = ownerProfileKey,
                downloads = downloads,
                localeTag = TmdbSettingsRepository.snapshot().language,
                nowEpochMs = now,
            )
            if (reconciliation.recordsToUpsert.isNotEmpty() || reconciliation.keysToDelete.isNotEmpty()) {
                store.commit(
                    recordsToUpsert = reconciliation.recordsToUpsert,
                    keysToDelete = reconciliation.keysToDelete,
                )
            }
            reconciliation.keysToDelete.forEach { key -> activeRefreshes.remove(key)?.cancel() }
            reconciliation.keysToDelete.forEach { key -> activeArtworkRefreshes.remove(key)?.job?.cancel() }
            reconciliation.artworkToDelete
        }
        cleanupUnreferencedArtwork(removedArtwork)
        publishCurrentProfile()
    }

    internal fun deleteProfile(profileId: Int) {
        val owner = downloadOwnerProfileKey(profileId)
        val removedArtwork = synchronized(stateLock) {
            val records = store.recordsForProfile(owner)
            records.forEach { activeRefreshes.remove(it.key)?.cancel() }
            records.forEach { activeArtworkRefreshes.remove(it.key)?.job?.cancel() }
            store.deleteProfile(owner)
            if (loadedOwnerProfileKey == owner) {
                currentDownloads = emptyList()
            }
            records.flatMap { it.artwork.values }
        }
        cleanupUnreferencedArtwork(removedArtwork)
        publishCurrentProfile()
    }

    fun clearLocalState() {
        synchronized(stateLock) {
            activeRefreshes.values.forEach(Job::cancel)
            activeRefreshes.clear()
            activeArtworkRefreshes.values.forEach { refresh -> refresh.job.cancel() }
            activeArtworkRefreshes.clear()
            store.allRecords().map(OfflineTitleRecord::key).let { keys ->
                store.commit(keysToDelete = keys)
            }
            currentDownloads = emptyList()
            loadedOwnerProfileKey = null
            _uiState.value = OfflineLibraryUiState()
        }
        OfflineArtworkPlatform.deleteAll()
    }

    private fun refreshRecord(key: String, manual: Boolean) {
        val now = DownloadsClock.nowEpochMs()
        val capture = synchronized(stateLock) {
            if (activeRefreshes[key]?.isActive == true) return@synchronized null
            val record = store.record(key) ?: return@synchronized null
            val decision = decideOfflineRefresh(record, now, manual)
            if (!decision.shouldRefresh) return@synchronized null
            val marked = record.copy(
                lastAutomaticAttemptEpochMs = if (manual) record.lastAutomaticAttemptEpochMs else now,
                updatedAtEpochMs = now,
            )
            store.commit(recordsToUpsert = listOf(marked))
            RefreshCapture(marked.key, marked.ownerProfileKey, marked.generation, marked.downloadIds)
        } ?: return

        val job = scope.launch {
            refreshSlots.withPermit {
                val record = synchronized(stateLock) {
                    store.record(capture.key)?.takeIf { current -> current.matches(capture) }
                } ?: return@withPermit
                val result = MetaDetailsRepository.fetchForOffline(
                    type = record.metaType,
                    id = record.metaId,
                    validators = OfflineMetaValidators(
                        sourceUrl = record.metadataSourceUrl,
                        etag = record.metadataEtag,
                        lastModified = record.metadataLastModified,
                    ),
                    savedMeta = record.metadata.toMetaDetails(emptyMap()).copy(isOfflineSnapshot = false),
                    preferredAddonIds = record.providerAddonIds,
                    localeTag = record.localeTag,
                )
                when (result) {
                    is OfflineMetaFetchResult.Updated -> applyRefreshSuccess(capture, result)
                    is OfflineMetaFetchResult.NotModified -> applyRefreshNotModified(capture, result)
                    OfflineMetaFetchResult.Failed -> applyRefreshFailure(capture)
                }
            }
        }
        synchronized(stateLock) {
            activeRefreshes[key] = job
            publishCurrentProfileLocked()
        }
        job.invokeOnCompletion {
            synchronized(stateLock) {
                if (activeRefreshes[key] === job) activeRefreshes.remove(key)
                publishCurrentProfileLocked()
            }
        }
    }

    private fun applyRefreshSuccess(capture: RefreshCapture, result: OfflineMetaFetchResult.Updated) {
        val now = DownloadsClock.nowEpochMs()
        val updated = synchronized(stateLock) {
            val current = store.record(capture.key)?.takeIf { it.matches(capture) }
                ?: return@synchronized null
            val downloads = currentDownloads.filter { it.id in current.downloadIds }
            if (downloads.isEmpty()) return@synchronized null
            val mergedMetadata = mergeOfflineMetadata(current.metadata, result.meta, downloads)
            current.copy(
                metadata = mergedMetadata,
                providerMetaIds = current.providerMetaIds + result.meta.id,
                metadataSourceUrl = result.sourceUrl,
                metadataEtag = result.etag,
                metadataLastModified = result.lastModified,
                lastSuccessfulRefreshEpochMs = now,
                lastRefreshFailureEpochMs = null,
                refreshFailureCount = 0,
                nextRetryEpochMs = null,
                metadataComplete = true,
                generation = current.generation + 1L,
                updatedAtEpochMs = now,
            ).also { store.commit(recordsToUpsert = listOf(it)) }
        } ?: return
        publishCurrentProfile()
        refreshArtwork(updated.key, updated.generation, validateExisting = true)
    }

    private fun applyRefreshNotModified(capture: RefreshCapture, result: OfflineMetaFetchResult.NotModified) {
        val now = DownloadsClock.nowEpochMs()
        val updated = synchronized(stateLock) {
            val current = store.record(capture.key)?.takeIf { it.matches(capture) }
                ?: return@synchronized null
            val mergedMetadata = result.enrichedMeta?.let { meta ->
                mergeOfflineMetadata(
                    current.metadata,
                    meta,
                    currentDownloads.filter { it.id in current.downloadIds },
                )
            } ?: current.metadata
            current.copy(
                metadata = mergedMetadata,
                providerMetaIds = result.enrichedMeta?.id
                    ?.let { providerId -> current.providerMetaIds + providerId }
                    ?: current.providerMetaIds,
                metadataEtag = result.etag ?: current.metadataEtag,
                metadataLastModified = result.lastModified ?: current.metadataLastModified,
                lastSuccessfulRefreshEpochMs = now,
                lastRefreshFailureEpochMs = null,
                refreshFailureCount = 0,
                nextRetryEpochMs = null,
                generation = current.generation + 1L,
                updatedAtEpochMs = now,
            ).also { store.commit(recordsToUpsert = listOf(it)) }
        } ?: return
        publishCurrentProfile()
        refreshArtwork(updated.key, updated.generation, validateExisting = true)
    }

    private fun applyRefreshFailure(capture: RefreshCapture) {
        val now = DownloadsClock.nowEpochMs()
        synchronized(stateLock) {
            val current = store.record(capture.key)?.takeIf { it.matches(capture) }
                ?: return@synchronized
            val failureCount = current.refreshFailureCount + 1
            store.commit(
                recordsToUpsert = listOf(
                    current.copy(
                        lastRefreshFailureEpochMs = now,
                        refreshFailureCount = failureCount,
                        nextRetryEpochMs = now + offlineRetryDelayMs(failureCount),
                        updatedAtEpochMs = now,
                    ),
                ),
            )
        }
        publishCurrentProfile()
    }

    private fun refreshArtwork(
        key: String,
        generation: Long,
        validateExisting: Boolean,
    ) {
        synchronized(stateLock) {
            val active = activeArtworkRefreshes[key]
            if (active?.job?.isActive == true && active.generation == generation) return
            active?.job?.cancel()
        }
        val job = scope.launch {
            var committed = false
            var artworkToCleanOnAbort: Collection<OfflineArtworkRef> = emptyList()
            try {
                refreshSlots.withPermit {
                    val initial = synchronized(stateLock) {
                        val current = store.record(key)?.takeIf { record ->
                            canApplyOfflineRefresh(
                                record = record,
                                capturedOwnerProfileKey = record.ownerProfileKey,
                                capturedGeneration = generation,
                                capturedDownloadIds = record.downloadIds,
                                loadedOwnerProfileKey = loadedOwnerProfileKey,
                            )
                        } ?: return@synchronized null
                        val attemptAt = DownloadsClock.nowEpochMs()
                        current.copy(
                            lastArtworkAttemptEpochMs = attemptAt,
                            updatedAtEpochMs = attemptAt,
                        ).also { marked -> store.commit(recordsToUpsert = listOf(marked)) }
                    } ?: return@withPermit
                    val downloads = synchronized(stateLock) {
                        currentDownloads.filter { it.id in initial.downloadIds }
                    }
                    val required = requiredOfflineArtwork(initial.metadata, downloads)
                    var artwork = initial.artwork
                    artworkToCleanOnAbort = artwork.values
                    val removed = mutableListOf<OfflineArtworkRef>()
                    for ((role, url) in required) {
                        val existing = artwork[role]
                        val existingLocalUri = existing?.assetKey
                            ?.let(OfflineArtworkPlatform::localUri)
                        if (
                            !validateExisting &&
                            existing?.remoteUrl == url &&
                            existingLocalUri != null
                        ) {
                            continue
                        }
                        val headers = buildMap {
                            if (existing?.remoteUrl == url && existingLocalUri != null) {
                                existing.etag?.let { put("If-None-Match", it) }
                                existing.lastModified?.let { put("If-Modified-Since", it) }
                            }
                        }
                        val response = try {
                            OfflineArtworkPlatform.fetch(url, headers, MaxArtworkBytes)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            continue
                        }
                        if (response.statusCode == 304 && existing != null && existingLocalUri != null) {
                            artwork = artwork + (
                                role to existing.copy(updatedAtEpochMs = DownloadsClock.nowEpochMs())
                            )
                            continue
                        }
                        if (!validOfflineArtwork(response, MaxArtworkBytes)) continue
                        val assetKey = offlineArtworkAssetKey(url, response.contentType)
                        if (OfflineArtworkPlatform.save(assetKey, response.bytes) == null) continue
                        if (existing != null && existing.assetKey != assetKey) removed += existing
                        artwork = artwork + (
                            role to OfflineArtworkRef(
                                role = role,
                                remoteUrl = url,
                                assetKey = assetKey,
                                etag = response.etag,
                                lastModified = response.lastModified,
                                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            )
                        )
                        artworkToCleanOnAbort = artwork.values
                    }
                    val now = DownloadsClock.nowEpochMs()
                    committed = synchronized(stateLock) {
                        val current = store.record(key)
                            ?.takeIf { record ->
                                canApplyOfflineRefresh(
                                    record = record,
                                    capturedOwnerProfileKey = initial.ownerProfileKey,
                                    capturedGeneration = generation,
                                    capturedDownloadIds = initial.downloadIds,
                                    loadedOwnerProfileKey = loadedOwnerProfileKey,
                                )
                            }
                            ?: return@synchronized false
                        val stillRequired = requiredOfflineArtwork(
                            current.metadata,
                            currentDownloads.filter { it.id in current.downloadIds },
                        )
                        val retained = artwork.filterKeys(stillRequired::containsKey)
                        removed += current.artwork
                            .filterKeys { role -> role !in retained }
                            .values
                        val locallyAvailableAssetKeys = retained.values
                            .mapNotNullTo(hashSetOf()) { reference ->
                                reference.assetKey.takeIf { assetKey ->
                                    OfflineArtworkPlatform.localUri(assetKey) != null
                                }
                            }
                        store.commit(
                            recordsToUpsert = listOf(
                                finishOfflineArtworkAttempt(
                                    record = current,
                                    artwork = retained,
                                    requiredArtwork = stillRequired,
                                    locallyAvailableAssetKeys = locallyAvailableAssetKeys,
                                    nowEpochMs = now,
                                ),
                            ),
                        )
                        true
                    }
                    if (committed) {
                        cleanupUnreferencedArtwork(removed)
                        publishCurrentProfile()
                    }
                }
            } finally {
                if (!committed) cleanupUnreferencedArtwork(artworkToCleanOnAbort)
            }
        }
        synchronized(stateLock) {
            activeArtworkRefreshes[key] = ArtworkRefresh(generation, job)
        }
        job.invokeOnCompletion {
            synchronized(stateLock) {
                if (activeArtworkRefreshes[key]?.job === job) activeArtworkRefreshes.remove(key)
            }
        }
    }

    private fun cleanupUnreferencedArtwork(candidates: Collection<OfflineArtworkRef>) {
        if (candidates.isEmpty()) return
        val referenced = synchronized(stateLock) {
            store.allRecords().flatMapTo(hashSetOf()) { record ->
                record.artwork.values.map(OfflineArtworkRef::assetKey)
            }
        }
        candidates.map(OfflineArtworkRef::assetKey).distinct().forEach { assetKey ->
            if (assetKey !in referenced) OfflineArtworkPlatform.delete(assetKey)
        }
    }

    private fun publishCurrentProfile() {
        synchronized(stateLock) { publishCurrentProfileLocked() }
    }

    private fun publishCurrentProfileLocked() {
        val owner = loadedOwnerProfileKey ?: return
        val downloadsById = currentDownloads.associateBy(DownloadItem::id)
        _uiState.value = OfflineLibraryUiState(
            titles = store.recordsForProfile(owner)
                .map { record ->
                    OfflineTitle(
                        record = record,
                        downloads = record.downloadIds.mapNotNull(downloadsById::get),
                    )
                }
                .sortedWith(
                    compareByDescending<OfflineTitle> { title ->
                        title.downloads.maxOfOrNull(DownloadItem::updatedAtEpochMs) ?: title.record.updatedAtEpochMs
                    }.thenBy { it.record.metadata.name.lowercase() },
                ),
            refreshingKeys = activeRefreshes.filterValues(Job::isActive).keys,
        )
    }

    private fun OfflineTitleRecord.matches(capture: RefreshCapture): Boolean =
        canApplyOfflineRefresh(
            record = this,
            capturedOwnerProfileKey = capture.ownerProfileKey,
            capturedGeneration = capture.generation,
            capturedDownloadIds = capture.downloadIds,
            loadedOwnerProfileKey = loadedOwnerProfileKey,
        )

    private data class RefreshCapture(
        val key: String,
        val ownerProfileKey: String,
        val generation: Long,
        val downloadIds: Set<String>,
    )

    private data class ArtworkRefresh(
        val generation: Long,
        val job: Job,
    )
}
