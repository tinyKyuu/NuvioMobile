package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.downloads.OfflineLibraryRepository
import com.nuvio.app.features.downloads.canonicalOfflineMetaType
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.TraktRelatedRepository
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.trakt.shouldUseTraktMoreLikeThis
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object MetaDetailsRepository {
    private val controller = MetaDetailsRepositoryController()
    val uiState get() = controller.uiState
    fun load(type: String, id: String, force: Boolean = false) = controller.load(type, id, force)
    fun peek(type: String, id: String) = controller.peek(type, id)
    fun clear() = controller.clear()
    fun onRecoveryGeneration(generation: Long) = controller.onRecoveryGeneration(generation)
    suspend fun fetch(type: String, id: String, cacheResult: Boolean = true) = controller.fetch(type, id, cacheResult)
    internal suspend fun fetchForOffline(
        type: String, id: String, validators: OfflineMetaValidators, savedMeta: MetaDetails,
        preferredAddonIds: Set<String>, localeTag: String?,
    ) = controller.fetchForOffline(type, id, validators, savedMeta, preferredAddonIds, localeTag)
    fun findEmbeddedStreams(videoId: String) = controller.findEmbeddedStreams(videoId)
}

internal data class MetaDetailsLoadResult(
    val meta: MetaDetails?,
    val lookupId: String,
    val noProviders: Boolean = false,
)

internal class MetaDetailsRepositoryController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val activeProfileId: () -> Int = { ProfileRepository.activeProfileId },
    private val metadataLoader: (suspend (String, String) -> MetaDetailsLoadResult)? = null,
    private val captureSnapshot: (String, String, MetaDetails) -> Unit = { type, id, meta ->
        OfflineLibraryRepository.captureNormalDetails(type, id, meta)
    },
    private val failureMessage: suspend (Boolean) -> String = { noProviders ->
        getString(if (noProviders) Res.string.details_no_addon_meta else Res.string.details_load_failed_all_addons)
    },
) {
    private data class CachedMetaEntry(
        val baseMeta: MetaDetails,
        val metaScreenMeta: MetaDetails? = null,
        val metaScreenSettingsFingerprint: String? = null,
    )

    private val log = Logger.withTag("MetaDetailsRepo")
    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()
    private var activeRequestKey: String? = null
    private var activeRequestType: String? = null
    private var activeRequestId: String? = null
    private var activeRequestProfileId: Int? = null
    private var requestGeneration: Long = 0L
    private var activeLoadJob: Job? = null
    private var latestRecoveryGeneration: Long = 0L
    private var pendingRecoveryGeneration: Long? = null
    private val cachedMetaByRequestKey = mutableMapOf<String, CachedMetaEntry>()

    fun load(type: String, id: String, force: Boolean = false) {
        log.d { "load() called — type=$type id=$id" }
        val requestKey = "$type:$id"
        val profileId = activeProfileId()
        activeRequestType = type
        activeRequestId = id
        activeRequestProfileId = profileId
        val currentState = _uiState.value
        val mdbListSettings = MdbListSettingsRepository.snapshot()
        val metaScreenSettingsFingerprint = buildMetaScreenSettingsFingerprint(mdbListSettings)

        cachedMetaByRequestKey[requestKey]?.takeUnless { force }?.let { cachedEntry ->
            cachedEntry.metaScreenMeta
                ?.takeIf { cachedEntry.metaScreenSettingsFingerprint == metaScreenSettingsFingerprint }
                ?.let { cachedMeta ->
                    activateSynchronousRequest(requestKey)
                    _uiState.value = MetaDetailsUiState(meta = cachedMeta.withUnreleasedFilter())
                    return
                }

            val cachedBaseMeta = cachedEntry.baseMeta
            if (!shouldEnrichForMetaScreen(cachedBaseMeta, id, mdbListSettings)) {
                activateSynchronousRequest(requestKey)
                _uiState.value = MetaDetailsUiState(meta = cachedBaseMeta.withUnreleasedFilter())
                return
            }

            if (currentState.isLoading && activeRequestKey == requestKey) {
                log.d { "Meta screen enrichment already in flight — type=$type id=$id" }
                return
            }

            val generation = beginAsyncRequest(requestKey)
            _uiState.value = MetaDetailsUiState(isLoading = true, meta = cachedBaseMeta)
            activeLoadJob = scope.launch {
                val enrichedMeta = withContext(Dispatchers.Default) {
                    enrichForMetaScreen(
                        meta = cachedBaseMeta,
                        fallbackItemId = id,
                        fallbackItemType = type,
                        settings = mdbListSettings,
                    )
                }
                if (!ownsRequest(requestKey, generation, profileId)) return@launch
                pendingRecoveryGeneration = null
                cachedMetaByRequestKey[requestKey] = cachedEntry.copy(
                    metaScreenMeta = enrichedMeta,
                    metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
                )
                _uiState.value = MetaDetailsUiState(meta = enrichedMeta.withUnreleasedFilter())
            }
            return
        }

        if (!force && currentState.meta?.type == type && currentState.meta.id == id && !currentState.isLoading) {
            log.d { "Skipping reload for cached meta — type=$type id=$id" }
            activateSynchronousRequest(requestKey)
            return
        }

        if (!force && currentState.isLoading && activeRequestKey == requestKey) {
            log.d { "Request already in flight — type=$type id=$id" }
            return
        }

        val generation = beginAsyncRequest(requestKey)
        _uiState.value = MetaDetailsUiState(
            isLoading = true,
            meta = currentState.meta?.takeIf { it.type == type && it.id == id },
        )

        activeLoadJob = scope.launch {
            val result = try {
                metadataLoader?.invoke(type, id) ?: loadMetadata(type, id)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                MetaDetailsLoadResult(null, id)
            }
            if (!ownsRequest(requestKey, generation, profileId)) return@launch
            if (result.meta != null) {
                publishLoadedMeta(
                    requestKey = requestKey,
                    meta = result.meta,
                    fallbackItemId = result.lookupId,
                    fallbackItemType = type,
                    mdbListSettings = mdbListSettings,
                    metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
                    generation = generation,
                    profileId = profileId,
                )
                return@launch
            }

            val message = failureMessage(result.noProviders)
            if (!ownsRequest(requestKey, generation, profileId)) return@launch
            pendingRecoveryGeneration?.let { recovery ->
                pendingRecoveryGeneration = null
                log.d { "Retrying interrupted details request=$generation after recovery=$recovery profile=$profileId" }
                load(type, id, force = true)
                return@launch
            }
            _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = message)
            activeRequestKey = null
        }
    }

    private suspend fun loadMetadata(type: String, id: String): MetaDetailsLoadResult {
        val lookupId = resolveMetaLookupId(itemId = id, itemType = type)
        val manifests = findReadyMetaManifests(type, lookupId)
        for (manifest in manifests) {
            val meta = withContext(Dispatchers.Default) { tryFetchMeta(manifest, type, lookupId, includeMdbList = false) }
            if (meta != null) return MetaDetailsLoadResult(meta, lookupId)
        }
        return MetaDetailsLoadResult(tryFetchTmdbFallbackMeta(type, id), id, noProviders = manifests.isEmpty())
    }

    fun peek(type: String, id: String): MetaDetails? {
        val requestKey = "$type:$id"
        val currentMeta = _uiState.value.meta?.takeIf { it.type == type && it.id == id }
        if (currentMeta != null) return currentMeta

        val metaScreenSettingsFingerprint = buildMetaScreenSettingsFingerprint(MdbListSettingsRepository.snapshot())
        val cachedEntry = cachedMetaByRequestKey[requestKey] ?: return null
        return cachedEntry.metaScreenMeta
            ?.takeIf { cachedEntry.metaScreenSettingsFingerprint == metaScreenSettingsFingerprint }
            ?: cachedEntry.baseMeta
    }

    fun clear() {
        requestGeneration += 1L
        activeLoadJob?.cancel()
        activeLoadJob = null
        activeRequestKey = null
        activeRequestType = null
        activeRequestId = null
        activeRequestProfileId = null
        latestRecoveryGeneration = 0L
        pendingRecoveryGeneration = null
        cachedMetaByRequestKey.clear()
        _uiState.value = MetaDetailsUiState()
    }

    fun onRecoveryGeneration(generation: Long) {
        val profileId = activeProfileId()
        val interruptedRequest = requestGeneration
        // The coordinator runs off-main. Serialize recovery with normal UI loads,
        // and do not replace a new load started after this notification.
        scope.launch {
            if (profileId != activeProfileId() || generation <= latestRecoveryGeneration) return@launch
            latestRecoveryGeneration = generation
            if (activeRequestProfileId != profileId || interruptedRequest != requestGeneration) return@launch
            val type = activeRequestType ?: return@launch
            val id = activeRequestId ?: return@launch
            val current = _uiState.value
            if (current.isLoading) {
                pendingRecoveryGeneration = generation
                log.d { "Deferred details recovery=$generation request=$interruptedRequest profile=$profileId" }
            } else if (current.meta == null && current.errorMessage != null) {
                load(type = type, id = id, force = true)
            }
        }
    }

    suspend fun fetch(type: String, id: String, cacheResult: Boolean = true): MetaDetails? {
        val requestKey = "$type:$id"
        cachedMetaByRequestKey[requestKey]?.let { return it.baseMeta }

        val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
        val manifests = findReadyMetaManifests(type = type, id = metaLookupId)

        for (manifest in manifests) {
            val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                tryFetchMeta(manifest, type, metaLookupId, includeMdbList = false)
            }
            if (result != null) {
                if (cacheResult) {
                    cachedMetaByRequestKey[requestKey] = CachedMetaEntry(baseMeta = result)
                }
                OfflineLibraryRepository.captureNormalDetails(
                    requestedType = type,
                    requestedId = id,
                    meta = result,
                    sourceUrl = buildAddonResourceUrl(
                        manifestUrl = manifest.transportUrl,
                        resource = "meta",
                        type = type,
                        id = metaLookupId,
                    ),
                    providerAddonId = manifest.id,
                )
                return result
            }
        }

        return tryFetchTmdbFallbackMeta(type = type, id = id)?.also { result ->
            if (cacheResult) {
                cachedMetaByRequestKey[requestKey] = CachedMetaEntry(baseMeta = result)
            }
            OfflineLibraryRepository.captureNormalDetails(type, id, result)
        }
    }

    internal suspend fun fetchForOffline(
        type: String,
        id: String,
        validators: OfflineMetaValidators,
        savedMeta: MetaDetails,
        preferredAddonIds: Set<String>,
        localeTag: String?,
    ): OfflineMetaFetchResult {
        val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
        val manifests = findReadyMetaManifests(type = type, id = metaLookupId)
            .sortedByDescending { manifest -> manifest.id in preferredAddonIds }
        val tmdbSettings = TmdbSettingsRepository.snapshot().let { settings ->
            localeTag?.takeIf(String::isNotBlank)
                ?.let { language -> settings.copy(language = language) }
                ?: settings
        }
        for (manifest in manifests) {
            val url = buildAddonResourceUrl(
                manifestUrl = manifest.transportUrl,
                resource = "meta",
                type = type,
                id = metaLookupId,
            )
            val headers = buildMap {
                if (validators.sourceUrl == url) {
                    validators.etag?.let { put("If-None-Match", it) }
                    validators.lastModified?.let { put("If-Modified-Since", it) }
                }
                put("Accept", "application/json")
            }
            val response = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                runCatching {
                    httpRequestRaw(
                        method = "GET",
                        url = url,
                        headers = headers,
                        body = "",
                        maxResponseBodyBytes = OFFLINE_META_MAX_BYTES,
                    )
                }.getOrNull()
            } ?: continue
            if (response.status == 304 && validators.sourceUrl == url) {
                // The validator belongs only to the addon response. TMDB remains
                // an independent enrichment request with its own caching policy.
                val enriched = withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
                    TmdbMetadataService.enrichMeta(
                        meta = savedMeta,
                        fallbackItemId = metaLookupId,
                        settings = tmdbSettings,
                    )
                }
                return OfflineMetaFetchResult.NotModified(
                    sourceUrl = url,
                    etag = response.headers["etag"] ?: validators.etag,
                    lastModified = response.headers["last-modified"] ?: validators.lastModified,
                    enrichedMeta = enriched,
                )
            }
            if (response.status !in 200..299 || response.body.isBlank()) continue
            val parsed = runCatching { MetaDetailsParser.parse(response.body) }.getOrNull() ?: continue
            if (
                parsed.name.isBlank() ||
                parsed.id !in setOf(id, metaLookupId) ||
                canonicalOfflineMetaType(parsed.type) != canonicalOfflineMetaType(type)
            ) continue
            val enriched = withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
                TmdbMetadataService.enrichMeta(
                    meta = parsed,
                    fallbackItemId = metaLookupId,
                    settings = tmdbSettings,
                )
            } ?: parsed
            cachedMetaByRequestKey["$type:$id"] = CachedMetaEntry(baseMeta = enriched)
            return OfflineMetaFetchResult.Updated(
                meta = enriched,
                sourceUrl = url,
                etag = response.headers["etag"],
                lastModified = response.headers["last-modified"],
            )
        }

        val fallback = withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
            TmdbMetadataService.fetchStandaloneMeta(
                type = type,
                id = id,
                settings = tmdbSettings,
            )
        }
            ?: return OfflineMetaFetchResult.Failed
        cachedMetaByRequestKey["$type:$id"] = CachedMetaEntry(baseMeta = fallback)
        return OfflineMetaFetchResult.Updated(
            meta = fallback,
            sourceUrl = null,
            etag = null,
            lastModified = null,
        )
    }

    private companion object {
        const val FETCH_TIMEOUT_MS = 5_000L
        const val OFFLINE_META_MAX_BYTES = 4 * 1024 * 1024
        const val METADATA_PROVIDER_READY_TIMEOUT_MS = 10_000L
        const val TMDB_ENRICH_TIMEOUT_MS = 5_000L
        const val MDBLIST_ENRICH_TIMEOUT_MS = 5_000L
    }

    private suspend fun tryFetchMeta(
        manifest: AddonManifest,
        type: String,
        id: String,
        includeMdbList: Boolean,
    ): MetaDetails? {
        val url = buildAddonResourceUrl(
            manifestUrl = manifest.transportUrl,
            resource = "meta",
            type = type,
            id = id,
        )

        return try {
            TmdbSettingsRepository.ensureLoaded()
            log.d { "Fetching meta from: $url" }
            val payload = fetchAddonResponseText(url)
            log.d { "Raw payload length=${payload.length}, first 500 chars: ${payload.take(500)}" }
            val result = MetaDetailsParser.parse(payload)
            val tmdbEnriched = withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
                TmdbMetadataService.enrichMeta(
                    meta = result,
                    fallbackItemId = id,
                    settings = TmdbSettingsRepository.snapshot(),
                )
            } ?: result
            val enriched = if (includeMdbList) {
                MdbListSettingsRepository.ensureLoaded()
                withTimeoutOrNull(MDBLIST_ENRICH_TIMEOUT_MS) {
                    MdbListMetadataService.enrichMeta(
                        meta = tmdbEnriched,
                        fallbackItemId = id,
                        settings = MdbListSettingsRepository.snapshot(),
                    )
                } ?: tmdbEnriched
            } else {
                tmdbEnriched
            }
            log.d { "Parsed meta: type=${enriched.type}, name=${enriched.name}, videos=${enriched.videos.size}" }
            if (enriched.videos.isNotEmpty()) {
                val first = enriched.videos.first()
                log.d { "First video: id=${first.id} title=${first.title} s=${first.season} e=${first.episode} embeddedStreams=${first.streams.size}" }
            }
            enriched
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.e(e) { "Failed to fetch/parse meta from $url (manifest=${manifest.transportUrl})" }
            null
        }
    }

    private suspend fun findReadyMetaManifests(type: String, id: String): List<AddonManifest> {
        AddonRepository.initialize()

        findMetaManifests(AddonRepository.uiState.value, type, id).takeIf { it.isNotEmpty() }?.let { return it }

        if (!AddonRepository.uiState.value.hasPendingEnabledAddonManifests()) {
            return emptyList()
        }

        val readyState = withTimeoutOrNull(METADATA_PROVIDER_READY_TIMEOUT_MS) {
            AddonRepository.uiState.first { state ->
                findMetaManifests(state, type, id).isNotEmpty() ||
                    !state.hasPendingEnabledAddonManifests()
            }
        } ?: AddonRepository.uiState.value

        return findMetaManifests(readyState, type, id)
    }

    private fun findMetaManifests(state: com.nuvio.app.features.addons.AddonsUiState, type: String, id: String): List<AddonManifest> =
        state.addons
            .enabledAddons()
            .mapNotNull { it.manifest }
            .filter { manifest ->
                manifest.resources.any { resource ->
                    resource.name == "meta" &&
                        resource.types.contains(type) &&
                        (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { id.startsWith(it) })
                }
            }

    private fun com.nuvio.app.features.addons.AddonsUiState.hasPendingEnabledAddonManifests(): Boolean =
        addons.enabledAddons().any { addon -> addon.manifest == null && addon.isRefreshing }

    private suspend fun resolveMetaLookupId(itemId: String, itemType: String): String {
        val tmdbId = itemId
            .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toIntOrNull()
            ?: return itemId

        return withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            TmdbService.tmdbToImdb(tmdbId = tmdbId, mediaType = itemType)
        }
            ?.takeIf { it.isNotBlank() }
            ?: itemId
    }

    private suspend fun tryFetchTmdbFallbackMeta(type: String, id: String): MetaDetails? =
        withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
            TmdbMetadataService.fetchStandaloneMeta(
                type = type,
                id = id,
                settings = TmdbSettingsRepository.snapshot(),
            )
        }

    private suspend fun publishLoadedMeta(
        requestKey: String,
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
        mdbListSettings: com.nuvio.app.features.mdblist.MdbListSettings,
        metaScreenSettingsFingerprint: String,
        generation: Long,
        profileId: Int,
    ) {
        if (!ownsRequest(requestKey, generation, profileId)) return
        pendingRecoveryGeneration = null
        captureSnapshot(fallbackItemType, fallbackItemId, meta)
        val cachedEntry = CachedMetaEntry(baseMeta = meta)
        cachedMetaByRequestKey[requestKey] = cachedEntry

        if (!shouldEnrichForMetaScreen(meta, fallbackItemId, mdbListSettings)) {
            _uiState.value = MetaDetailsUiState(meta = meta.withUnreleasedFilter())
            activeRequestKey = requestKey
            return
        }

        _uiState.value = MetaDetailsUiState(
            isLoading = true,
            meta = meta,
        )
        val enrichedMeta = withContext(Dispatchers.Default) {
            enrichForMetaScreen(
                meta = meta,
                fallbackItemId = fallbackItemId,
                fallbackItemType = fallbackItemType,
                settings = mdbListSettings,
            )
        }
        if (!ownsRequest(requestKey, generation, profileId)) return
        pendingRecoveryGeneration = null
        cachedMetaByRequestKey[requestKey] = cachedEntry.copy(
            metaScreenMeta = enrichedMeta,
            metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
        )
        _uiState.value = MetaDetailsUiState(meta = enrichedMeta.withUnreleasedFilter())
        activeRequestKey = requestKey
    }

    private fun activateSynchronousRequest(requestKey: String) {
        pendingRecoveryGeneration = null
        requestGeneration += 1L
        activeLoadJob?.cancel()
        activeLoadJob = null
        activeRequestKey = requestKey
    }

    private fun beginAsyncRequest(requestKey: String): Long {
        pendingRecoveryGeneration = null
        requestGeneration += 1L
        activeLoadJob?.cancel()
        activeLoadJob = null
        activeRequestKey = requestKey
        return requestGeneration
    }

    private fun ownsRequest(requestKey: String, generation: Long, profileId: Int): Boolean =
        activeRequestKey == requestKey &&
            requestGeneration == generation &&
            activeProfileId() == profileId

    private suspend fun enrichForMetaScreen(
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): MetaDetails {
        val mdbListEnrichedMeta = withTimeoutOrNull(MDBLIST_ENRICH_TIMEOUT_MS) {
            MdbListMetadataService.enrichMeta(
                meta = meta,
                fallbackItemId = fallbackItemId,
                settings = settings,
            )
        } ?: meta
        val enrichedMeta = applyMoreLikeThisSource(
            meta = mdbListEnrichedMeta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType,
        )

        return enrichedMeta
    }

    private suspend fun applyMoreLikeThisSource(
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
    ): MetaDetails {
        TrackingSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()

        val trackingSettings = TrackingSettingsRepository.uiState.value
        val isTraktAuthenticated = TraktAuthRepository.uiState.value.mode == TraktConnectionMode.CONNECTED
        val shouldUseTrakt = shouldUseTraktMoreLikeThis(
            isAuthenticated = isTraktAuthenticated,
            source = trackingSettings.moreLikeThisSource,
        ) && supportsMoreLikeThis(meta, fallbackItemType)

        if (shouldUseTrakt) {
            val items = runCatching {
                TraktRelatedRepository.getRelated(
                    meta = meta,
                    fallbackItemId = fallbackItemId,
                    fallbackItemType = fallbackItemType,
                )
            }.onFailure { error ->
                log.w { "Failed to load Trakt related titles for ${meta.id}: ${error.message}" }
            }.getOrDefault(emptyList())

            return meta.copy(
                moreLikeThis = items,
                moreLikeThisSource = MoreLikeThisSource.TRAKT.takeIf { items.isNotEmpty() },
            )
        }

        val tmdbSettings = TmdbSettingsRepository.snapshot()
        if (!tmdbSettings.enabled || !tmdbSettings.useMoreLikeThis) {
            return meta.copy(moreLikeThis = emptyList(), moreLikeThisSource = null)
        }

        return meta.copy(
            moreLikeThisSource = MoreLikeThisSource.TMDB.takeIf { meta.moreLikeThis.isNotEmpty() },
        )
    }

    private fun shouldFetchMdbListOnMetaScreen(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): Boolean = MdbListMetadataService.shouldFetchForMeta(
        meta = meta,
        fallbackItemId = fallbackItemId,
        settings = settings,
    )

    private fun shouldEnrichForMetaScreen(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): Boolean {
        if (shouldFetchMdbListOnMetaScreen(meta, fallbackItemId, settings)) return true
        return shouldApplyMoreLikeThisSource(meta)
    }

    private fun shouldApplyMoreLikeThisSource(meta: MetaDetails): Boolean {
        TrackingSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()

        val trackingSettings = TrackingSettingsRepository.uiState.value
        val isTraktAuthenticated = TraktAuthRepository.uiState.value.mode == TraktConnectionMode.CONNECTED
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        return shouldUseTraktMoreLikeThis(
            isAuthenticated = isTraktAuthenticated,
            source = trackingSettings.moreLikeThisSource,
        ) || !tmdbSettings.enabled || !tmdbSettings.useMoreLikeThis || meta.moreLikeThisSource == null && meta.moreLikeThis.isNotEmpty()
    }

    private fun buildMetaScreenSettingsFingerprint(
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): String {
        TrackingSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        val providers = settings.enabledProvidersInPriorityOrder().joinToString(",")
        val trackingSettings = TrackingSettingsRepository.uiState.value
        val traktAuthMode = TraktAuthRepository.uiState.value.mode
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        return buildString {
            append("${settings.enabled}:${settings.apiKey.trim()}:$providers")
            append("|more_like=${trackingSettings.moreLikeThisSource}:$traktAuthMode")
            append("|tmdb=${tmdbSettings.enabled}:${tmdbSettings.useMoreLikeThis}:${tmdbSettings.hasApiKey}:${tmdbSettings.language}")
        }
    }

    private fun supportsMoreLikeThis(meta: MetaDetails, fallbackItemType: String): Boolean =
        normalizeMoreLikeThisType(meta.type) != null || normalizeMoreLikeThisType(fallbackItemType) != null

    private fun normalizeMoreLikeThisType(value: String?): String? =
        when (value?.trim()?.lowercase()) {
            "movie", "film" -> "movie"
            "series", "show", "tv", "tvshow" -> "series"
            else -> null
        }

    private fun MetaDetails.withUnreleasedFilter(): MetaDetails {
        if (!HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) return this
        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val releasedMoreLikeThis = moreLikeThis.filterReleasedItems(todayIsoDate)
        return copy(
            moreLikeThis = releasedMoreLikeThis,
            moreLikeThisSource = moreLikeThisSource.takeIf { releasedMoreLikeThis.isNotEmpty() },
            collectionItems = collectionItems.filterReleasedItems(todayIsoDate),
        )
    }

   
    fun findEmbeddedStreams(videoId: String): List<com.nuvio.app.features.streams.StreamItem> {
        val meta = _uiState.value.meta ?: return emptyList()
        val videosWithStreams = meta.videos.filter { it.streams.isNotEmpty() }
        if (videosWithStreams.isEmpty()) return emptyList()

        val directMatch = videosWithStreams.firstOrNull { it.id == videoId }
        if (directMatch != null) return directMatch.streams

        val parts = videoId.split(":")
        if (parts.size >= 3) {
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()
            if (season != null && episode != null) {
                val episodeMatch = videosWithStreams.firstOrNull { it.season == season && it.episode == episode }
                if (episodeMatch != null) return episodeMatch.streams
            }
        }

        val prefixMatch = videosWithStreams.firstOrNull { it.id.startsWith("$videoId:") }
        if (prefixMatch != null) return prefixMatch.streams

        if (videoId == meta.id && videosWithStreams.size == 1) {
            return videosWithStreams.first().streams
        }

        if (videoId == meta.id && videosWithStreams.isNotEmpty()) {
            return videosWithStreams.flatMap { it.streams }
        }

        return emptyList()
    }
}
