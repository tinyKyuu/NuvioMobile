package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Serializable
private data class AddonRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class AddonPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

private const val ADDON_PUSH_DEBOUNCE_MS = 500L

internal data class AddonManifestRecoveryResult(
    val attemptedUrls: Set<String>,
    val recoveredUrls: Set<String>,
    val failedUrls: Set<String>,
    val stale: Boolean = false,
)

object AddonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(AddonsUiState())
    val uiState: StateFlow<AddonsUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var pulledFromServer = false
    private val manifestCacheStore = AddonManifestCacheStore(
        read = AddonStorage::loadManifestCache,
        write = { profileId, payload ->
            if (payload == null) AddonStorage.deleteManifestCache(profileId)
            else AddonStorage.saveManifestCache(profileId, payload)
        },
    )
    private val currentProfileId: Int get() = manifestCacheStore.owner.profileId
    private val manifestCache: Map<String, CachedAddonManifest> get() = manifestCacheStore.snapshot()
    private val manifestRefreshes = ManifestRefreshSingleFlight()
    private val pushJobsByProfile = mutableMapOf<Int, Job>()

    fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        if (currentProfileId != effectiveProfileId) onProfileChanged(effectiveProfileId)
        val owner = manifestCacheStore.owner
        manifestCacheStore.withOwner(owner) { initializeOwned(owner) }
    }

    private fun initializeOwned(owner: AddonManifestCacheOwner) {
        if (initialized) return
        initialized = true
        log.d { "initialize() — loading local addons for profile $currentProfileId" }

        val storedUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(currentProfileId))
        val enabledByUrl = loadLocalEnabledStates()
        manifestCacheStore.load(owner)
        log.d { "initialize() — local addon count: ${storedUrls.size}, cached manifests: ${manifestCache.size}" }
        if (storedUrls.isEmpty()) {
            if (manifestCache.isNotEmpty()) {
                manifestCacheStore.update(owner) { emptyMap() }
            }
            return
        }

        val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        _uiState.value = AddonsUiState(
            addons = storedUrls.map { manifestUrl ->
                existingByUrl[manifestUrl].toPendingAddon(
                    manifestUrl = manifestUrl,
                    enabled = enabledByUrl[manifestUrl],
                )
            },
        )
        hydrateCachedManifests(storedUrls.toSet())
        refreshMissingOrStaleManifests()
    }

    fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (effectiveProfileId == currentProfileId && initialized) return
        manifestCacheStore.switchProfile(effectiveProfileId) {
            cancelActiveRefreshes()
            initialized = false
            pulledFromServer = false
            _uiState.value = AddonsUiState()
        }
    }

    fun clearLocalState() {
        manifestCacheStore.switchProfile(1) {
            cancelActiveRefreshes()
            pushJobsByProfile.values.forEach(Job::cancel)
            pushJobsByProfile.clear()
            initialized = false
            pulledFromServer = false
            _uiState.value = AddonsUiState()
        }
    }

    fun deleteProfileData(profileId: Int) {
        manifestCacheStore.deleteProfile(profileId)
    }

    suspend fun pullFromServer(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (currentProfileId != effectiveProfileId) onProfileChanged(profileId)
        val operationOwner = manifestCacheStore.owner
        val operationGeneration = operationOwner.generation
        log.i { "pullFromServer() — profileId=$profileId, initialized=$initialized, pulledFromServer=$pulledFromServer" }
        if (!initialized && manifestCache.isEmpty()) {
            manifestCacheStore.load(operationOwner)
        }
        runCatching {
            val rows = SupabaseProvider.client.postgrest
                .from("addons")
                .select {
                    filter { eq("profile_id", operationOwner.profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<AddonRow>()

            if (!ownsProfile(effectiveProfileId, operationGeneration)) return@runCatching

            val rowsByUrl = linkedMapOf<String, AddonRow>()
            rows.forEach { row ->
                val manifestUrl = ensureManifestSuffix(row.url)
                if (!rowsByUrl.containsKey(manifestUrl)) {
                    rowsByUrl[manifestUrl] = row.copy(url = manifestUrl)
                }
            }

            val urls = rowsByUrl.keys.toList()
            log.i { "pullFromServer() — server returned ${rows.size} addons" }
            urls.forEachIndexed { i, u -> log.d { "  server[$i]: $u" } }

            if (urls.isEmpty() && !pulledFromServer) {
                val localUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(currentProfileId))
                log.i { "pullFromServer() — server empty, local has ${localUrls.size} addons" }
                if (localUrls.isNotEmpty()) {
                    log.i { "pullFromServer() — migrating local addons to server for profile $currentProfileId" }
                    initialize()
                    pulledFromServer = true
                    val enabledByUrl = loadLocalEnabledStates()
                    val addons = localUrls.mapIndexed { index, addonUrl ->
                        val manifestUrl = ensureManifestSuffix(addonUrl)
                        AddonPushItem(
                            url = manifestUrl,
                            name = _uiState.value.addons
                                .find { it.manifestUrl == manifestUrl }?.manifest?.name ?: "",
                            enabled = enabledByUrl[manifestUrl]
                                ?: _uiState.value.addons.find { it.manifestUrl == manifestUrl }?.enabled
                                ?: true,
                            sortOrder = index,
                        )
                    }
                    val params = buildJsonObject {
                        put("p_profile_id", currentProfileId)
                        put("p_addons", json.encodeToJsonElement(addons))
                        putSyncOriginClientId()
                    }
                    SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
                    log.i { "pullFromServer() — migration push done (${addons.size} addons)" }
                    return
                }
            }

            if (urls.isEmpty()) {
                val localUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(currentProfileId))
                if (localUrls.isNotEmpty()) {
                    log.w { "pullFromServer() — remote empty while local has ${localUrls.size} addons; preserving local addons" }
                    val enabledByUrl = loadLocalEnabledStates()
                    val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
                    _uiState.value = AddonsUiState(
                        addons = localUrls.map { url ->
                            existingByUrl[url].toPendingAddon(
                                manifestUrl = url,
                                enabled = enabledByUrl[url],
                            )
                        },
                    )
                    persist(operationOwner)
                    hydrateCachedManifests(localUrls.toSet(), operationOwner)
                    refreshMissingOrStaleManifests()
                    pulledFromServer = true
                    initialized = true
                    return
                }
            }

            val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
            _uiState.value = AddonsUiState(
                addons = urls.map { url ->
                    val row = rowsByUrl[url]
                    existingByUrl[url].toPendingAddon(
                        manifestUrl = url,
                        userSetName = row?.name?.takeIf { it.isNotBlank() },
                        enabled = row?.enabled,
                    )
                },
            )
            persist(operationOwner)
            hydrateCachedManifests(urls.toSet(), operationOwner)
            refreshMissingOrStaleManifests()
            pulledFromServer = true
            initialized = true
            log.i { "pullFromServer() — applied ${urls.size} addons to state" }
        }.onFailure { e ->
            log.e(e) { "pullFromServer() — FAILED" }
        }
    }

    suspend fun awaitManifestsLoaded() {
        if (_uiState.value.addons.isEmpty()) return
        uiState.first { state ->
            state.addons.isEmpty() ||
                state.addons.any { it.manifest != null } ||
                state.addons.none { it.isRefreshing }
        }
    }

    suspend fun addAddon(rawUrl: String): AddAddonResult {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            return AddAddonResult.Error(getString(Res.string.profile_primary_addons_required))
        }
        log.i { "addAddon() — rawUrl=$rawUrl" }
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_invalid_url))
        }

        if (_uiState.value.addons.any { it.manifestUrl == manifestUrl }) {
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }

        val operationOwner = manifestCacheStore.owner
        var fetchedPayload: String? = null
        val manifest = try {
            withContext(Dispatchers.Default) {
                val payload = fetchAddonResponseText(manifestUrl)
                fetchedPayload = payload
                AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
            }
        } catch (error: Throwable) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_load_manifest_failed))
        }

        val applied = manifestCacheStore.withOwner(operationOwner) {
            _uiState.update { current ->
                current.copy(
                    addons = current.addons + ManagedAddon(
                        manifestUrl = manifestUrl,
                        manifest = manifest,
                        isRefreshing = false,
                        errorMessage = null,
                    ),
                )
            }
            persist(operationOwner)
            fetchedPayload?.let { payload -> upsertManifestCache(operationOwner, manifestUrl, payload) }
            pushToServer()
            true
        } ?: false
        if (!applied) return AddAddonResult.Error(getString(Res.string.addon_load_manifest_failed))
        return AddAddonResult.Success(manifest)
    }

    fun removeAddon(manifestUrl: String) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        log.i { "removeAddon() — $manifestUrl" }
        val owner = manifestCacheStore.owner
        manifestCacheStore.withOwner(owner) {
            var changed = false
            _uiState.update { current ->
                val updatedAddons = current.addons.filterNot { it.manifestUrl == manifestUrl }
                changed = updatedAddons.size != current.addons.size
                if (changed) current.copy(addons = updatedAddons) else current
            }
            if (!changed) return@withOwner
            persist(owner)
            pushToServer()
        }
    }

    fun moveAddon(fromIndex: Int, toIndex: Int) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var changed = false
        _uiState.update { current ->
            val addons = current.addons
            if (
                fromIndex !in addons.indices ||
                toIndex !in addons.indices ||
                fromIndex == toIndex
            ) {
                return@update current
            }

            val reordered = addons.toMutableList()
            val movingAddon = reordered.removeAt(fromIndex)
            reordered.add(toIndex, movingAddon)
            changed = true
            current.copy(addons = reordered)
        }
        if (!changed) return
        persist()
        pushToServer()
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var shouldRefresh = false
        var changed = false
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl != manifestUrl || addon.enabled == enabled) {
                        addon
                    } else {
                        changed = true
                        shouldRefresh = enabled && addon.manifest == null && !addon.isRefreshing
                        addon.copy(enabled = enabled)
                    }
                },
            )
        }
        if (!changed) return
        persist()
        pushToServer()
        if (shouldRefresh) {
            refreshAddon(manifestUrl)
        }
    }

    fun refreshAll() {
        _uiState.value.addons.filter { it.enabled }.distinctBy { it.manifestUrl }.forEach { addon ->
            refreshAddon(
                manifestUrl = addon.manifestUrl,
                forceRefresh = true,
            )
        }
    }

    internal suspend fun recoverEnabledManifests(
        profileId: Int,
        recoveryGeneration: Long,
        forceAll: Boolean,
        onManifestRecovered: suspend (String) -> Unit,
    ): AddonManifestRecoveryResult {
        if (ProfileRepository.activeProfileId != profileId) {
            return AddonManifestRecoveryResult(emptySet(), emptySet(), emptySet(), stale = true)
        }
        initialize()
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        val operationOwner = manifestCacheStore.owner
        val operationGeneration = operationOwner.generation
        if (!ownsProfile(effectiveProfileId, operationGeneration)) {
            return AddonManifestRecoveryResult(emptySet(), emptySet(), emptySet(), stale = true)
        }

        val nowEpochMs = EpisodeReleaseDatePlatform.nowEpochMs()
        return recoverAddonManifestBatch(
            addons = _uiState.value.addons,
            cache = manifestCacheStore.snapshot(operationOwner),
            nowEpochMs = nowEpochMs,
            forceAll = forceAll,
            startRefresh = { manifestUrl ->
                startManifestRefresh(
                    manifestUrl = manifestUrl,
                    forceRefresh = true,
                    surfaceCachedFailure = forceAll,
                    reason = ManifestRefreshReason.Recovery,
                    recoveryGeneration = recoveryGeneration,
                )
            },
            isCurrent = {
                ownsProfile(effectiveProfileId, operationGeneration) &&
                    ProfileRepository.activeProfileId == profileId
            },
            onManifestRecovered = onManifestRecovered,
        )
    }

    fun refreshAddon(
        manifestUrl: String,
        forceRefresh: Boolean = false,
    ) {
        startManifestRefresh(
            manifestUrl = manifestUrl,
            forceRefresh = forceRefresh,
            surfaceCachedFailure = forceRefresh,
            reason = ManifestRefreshReason.Explicit,
        )
    }

    private fun startManifestRefresh(
        manifestUrl: String,
        forceRefresh: Boolean,
        surfaceCachedFailure: Boolean,
        reason: ManifestRefreshReason,
        recoveryGeneration: Long? = null,
    ): Deferred<Boolean> {
        val operationOwner = manifestCacheStore.owner
        val operationProfileId = operationOwner.profileId
        val operationGeneration = operationOwner.generation
        return manifestRefreshes.start(
            scope = scope,
            manifestUrl = manifestUrl,
            identity = ManifestRefreshIdentity(
                profileId = operationProfileId,
                profileGeneration = operationGeneration,
                reason = reason,
                recoveryGeneration = recoveryGeneration,
            ),
            onStarted = {
                manifestCacheStore.withOwner(operationOwner) {
                    markRefreshing(manifestUrl)
                }
            },
        ) refresh@{
            val result = runCatching {
                val payload = fetchAddonResponseText(
                    url = manifestUrl,
                    forceRefresh = forceRefresh,
                )
                payload to AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
            }
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }

            val success = result.getOrNull()
            val failureMessage = if (success == null) {
                result.exceptionOrNull()?.message ?: getString(Res.string.addon_load_manifest_failed)
            } else null
            manifestCacheStore.withOwner(operationOwner) {
                if (success != null) {
                    if (_uiState.value.addons.none { it.manifestUrl == manifestUrl }) return@withOwner false
                    upsertManifestCache(operationOwner, manifestUrl, success.first)
                }
                _uiState.update { current ->
                    current.copy(
                        addons = current.addons.map { addon ->
                            if (addon.manifestUrl != manifestUrl) addon
                            else addon.copy(
                                manifest = success?.second ?: addon.manifest,
                                isRefreshing = false,
                                errorMessage = if (success != null || addon.manifest != null && !surfaceCachedFailure) {
                                    null
                                } else {
                                    failureMessage
                                },
                            )
                        },
                    )
                }
                success != null
            } ?: false
        }
    }

    private fun pushToServer() {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        val profileId = currentProfileId
        val addons = _uiState.value.addons
            .distinctBy { it.manifestUrl }
            .mapIndexed { index, addon ->
                AddonPushItem(
                    url = addon.manifestUrl,
                    name = addon.userSetName?.takeIf { it.isNotBlank() } ?: addon.manifest?.name ?: "",
                    enabled = addon.enabled,
                    sortOrder = index,
                )
            }
        pushJobsByProfile[profileId]?.cancel()
        var pushJob: Job? = null
        pushJob = scope.launch {
            try {
                delay(ADDON_PUSH_DEBOUNCE_MS)
                log.d { "pushToServer() — profileId=$profileId, pushing ${addons.size} addons" }
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_addons", json.encodeToJsonElement(addons))
                    putSyncOriginClientId()
                }
                SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
                log.d { "pushToServer() — success" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "pushToServer() — FAILED" }
            } finally {
                if (pushJobsByProfile[profileId] === pushJob) {
                    pushJobsByProfile.remove(profileId)
                }
            }
        }
        pushJobsByProfile[profileId] = pushJob
    }

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl == manifestUrl) {
                        addon.copy(
                            isRefreshing = true,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                },
            )
        }
    }

    private fun persist(owner: AddonManifestCacheOwner = manifestCacheStore.owner) = manifestCacheStore.withOwner(owner) {
        val addons = _uiState.value.addons
        val installedUrls = dedupeManifestUrls(addons.map { it.manifestUrl })
        AddonStorage.saveInstalledAddonUrls(
            owner.profileId,
            installedUrls,
        )
        AddonStorage.saveAddonEnabledStates(
            owner.profileId,
            addons.associate { it.manifestUrl to it.enabled },
        )
        pruneManifestCache(installedUrls.toSet(), owner)
    }

    private fun loadLocalEnabledStates(): Map<String, Boolean> =
        AddonStorage.loadAddonEnabledStates(currentProfileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }

    private fun cancelActiveRefreshes() {
        manifestRefreshes.cancelAll()
    }

    private fun hydrateCachedManifests(
        installedUrls: Set<String>,
        owner: AddonManifestCacheOwner = manifestCacheStore.owner,
    ) = manifestCacheStore.withOwner(owner) {
        val parsedByUrl = linkedMapOf<String, AddonManifest>()
        val validCache = linkedMapOf<String, CachedAddonManifest>()
        manifestCache.forEach { (manifestUrl, cached) ->
            if (manifestUrl !in installedUrls) return@forEach
            val manifest = runCatching {
                AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = cached.payload,
                )
            }.getOrNull() ?: return@forEach
            validCache[manifestUrl] = cached
            parsedByUrl[manifestUrl] = manifest
        }
        if (validCache != manifestCache) {
            manifestCacheStore.update(owner) { validCache }
        }
        _uiState.update { state ->
            state.copy(
                addons = state.addons.map { addon ->
                    val cachedManifest = parsedByUrl[addon.manifestUrl]
                    if (cachedManifest == null || addon.manifest != null) {
                        addon
                    } else {
                        addon.copy(
                            manifest = cachedManifest,
                            isRefreshing = false,
                            errorMessage = null,
                        )
                    }
                },
            )
        }
    }

    private fun refreshMissingOrStaleManifests() {
        val nowEpochMs = EpisodeReleaseDatePlatform.nowEpochMs()
        selectAddonManifestRefreshUrls(
            addons = _uiState.value.addons,
            cache = manifestCache,
            nowEpochMs = nowEpochMs,
        ).forEach { manifestUrl ->
                val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == manifestUrl }
                    ?: return@forEach
                if (!shouldRefreshManifest(addon, nowEpochMs)) return@forEach
                startManifestRefresh(
                    manifestUrl = manifestUrl,
                    forceRefresh = addon.manifest != null,
                    surfaceCachedFailure = false,
                    reason = ManifestRefreshReason.Background,
                )
            }
    }

    private fun shouldRefreshManifest(addon: ManagedAddon, nowEpochMs: Long): Boolean {
        val hasActiveRefresh = manifestRefreshes.isActive(addon.manifestUrl)
        if (!addon.enabled || addon.isRefreshing && hasActiveRefresh) {
            return false
        }
        if (addon.manifest == null) return true
        val cached = manifestCache[addon.manifestUrl] ?: return true
        return cached.isStale(nowEpochMs)
    }

    private fun upsertManifestCache(owner: AddonManifestCacheOwner, manifestUrl: String, payload: String) {
        manifestCacheStore.upsert(
            expected = owner,
            manifestUrl = manifestUrl,
            payload = payload,
            fetchedAtEpochMs = EpisodeReleaseDatePlatform.nowEpochMs(),
        )
    }

    private fun pruneManifestCache(installedUrls: Set<String>, owner: AddonManifestCacheOwner) {
        manifestCacheStore.update(owner) { it.filterKeys(installedUrls::contains) }
    }

    private fun ownsProfile(profileId: Int, generation: Long): Boolean =
        manifestCacheStore.owner == AddonManifestCacheOwner(profileId, generation)

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && active.profileIndex != 1 && active.usesPrimaryAddons) 1 else profileId
    }

    private fun isUsingPrimaryAddonsFromSecondaryProfile(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return active != null && active.profileIndex != 1 && active.usesPrimaryAddons
    }
}

private fun ManagedAddon?.toPendingAddon(
    manifestUrl: String,
    userSetName: String? = null,
    enabled: Boolean? = null,
): ManagedAddon =
    when {
        this == null -> ManagedAddon(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: true,
            userSetName = userSetName,
            enabled = enabled ?: true,
        )
        manifest != null -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = false,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        isRefreshing -> copy(
            manifestUrl = manifestUrl,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        else -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: this.enabled,
            errorMessage = null,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
    }

private fun dedupeManifestUrls(urls: List<String>): List<String> =
    urls.map(::ensureManifestSuffix).distinct()

private fun ensureManifestSuffix(url: String): String {
    val path = url.substringBefore("?").trimEnd('/')
    val query = url.substringAfter("?", "")
    val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
    return if (query.isEmpty()) withSuffix else "$withSuffix?$query"
}

private fun normalizeManifestUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { runBlocking { getString(Res.string.addons_error_enter_url) } }

    val normalizedScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("stremio://") -> "https://${trimmed.removePrefix("stremio://")}"
        else -> "https://$trimmed"
    }

    val withoutFragment = normalizedScheme.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
}
