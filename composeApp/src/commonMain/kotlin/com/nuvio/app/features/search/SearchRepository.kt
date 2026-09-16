package com.nuvio.app.features.search

import co.touchlab.kermit.Logger
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.firstEnabledManifestError
import com.nuvio.app.features.addons.hasPendingEnabledManifests
import com.nuvio.app.features.catalog.CATALOG_PAGE_SIZE
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.buildCatalogUrl
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.catalog.mergeCatalogItems
import com.nuvio.app.features.catalog.nextCatalogPaginationState
import com.nuvio.app.features.catalog.supportsPagination
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal fun <T> canReuseRequestState(
    forceRefresh: Boolean,
    requestKey: T,
    cachedRequestKey: T?,
): Boolean = !forceRefresh && requestKey == cachedRequestKey

internal fun resolveDiscoverCatalog(
    sources: List<DiscoverCatalogOption>,
    preferredCatalogKey: String?,
    currentCatalogKey: String?,
): DiscoverCatalogOption? =
    sources.firstOrNull { it.key == preferredCatalogKey }
        ?: sources.firstOrNull { it.key == currentCatalogKey }
        ?: sources.firstOrNull()

private data class DiscoverRequestKey(
    val sources: List<DiscoverCatalogOption>,
    val hideUnreleasedContent: Boolean,
    val hasPendingAddonManifests: Boolean,
    val readyManifestUrls: Set<String>?,
)

object SearchRepository {
    private val controller = SearchRepositoryController()
    val uiState get() = controller.uiState
    val discoverUiState get() = controller.discoverUiState

    fun search(query: String, addons: List<ManagedAddon>, forceRefresh: Boolean = false) =
        controller.search(query, addons, forceRefresh)
    fun clear() = controller.clear()
    fun reset() = controller.reset()
    fun refreshDiscover(addons: List<ManagedAddon>, forceRefresh: Boolean = false) =
        controller.refreshDiscover(addons, forceRefresh)
    fun refreshAfterRecovery(addons: List<ManagedAddon>, readyManifestUrls: Set<String>? = null) =
        controller.refreshAfterRecovery(addons, readyManifestUrls)
    fun selectDiscoverType(type: String) = controller.selectDiscoverType(type)
    fun selectDiscoverCatalog(catalogKey: String) = controller.selectDiscoverCatalog(catalogKey)
    fun selectDiscoverGenre(genre: String?) = controller.selectDiscoverGenre(genre)
    fun loadMoreDiscover() = controller.loadMoreDiscover()
}

internal class SearchRepositoryController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val loadSearchSection: suspend (SearchCatalogRequest, Boolean) -> HomeCatalogSection =
        { request, forceRefresh -> request.toSection(forceRefresh) },
    private val loadDiscoverPage: suspend (DiscoverCatalogOption, String?, Int?, Boolean) -> CatalogPage =
        { source, genre, skip, forceRefresh ->
            fetchCatalogPage(
                manifestUrl = source.manifestUrl,
                type = source.type,
                catalogId = source.catalogId,
                genre = genre,
                skip = skip,
                forceRefresh = forceRefresh,
            ).withUnreleasedFilter()
        },
    private val loadPreferredCatalogKey: () -> String? = { DiscoverSelectionStorage.loadCatalogKey() },
    private val savePreferredCatalogKey: (String) -> Unit = { DiscoverSelectionStorage.saveCatalogKey(it) },
) {
    private val log = Logger.withTag("SearchRepository")
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()
    private val _discoverUiState = MutableStateFlow(DiscoverUiState())
    val discoverUiState: StateFlow<DiscoverUiState> = _discoverUiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeDiscoverJob: Job? = null
    private var searchGeneration: Long = 0L
    private var discoverGeneration: Long = 0L
    private var lastRequestKey: String? = null
    private var searchContextKey: String? = null
    private var cachedSearchSections: Map<SearchCatalogKey, HomeCatalogSection> = emptyMap()
    private var currentQuery: String? = null
    private var discoverSources: List<DiscoverCatalogOption> = emptyList()
    private var lastDiscoverRequestKey: DiscoverRequestKey? = null

    fun search(
        query: String,
        addons: List<ManagedAddon>,
        forceRefresh: Boolean = false,
        readyManifestUrls: Set<String>? = null,
    ) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            clear()
            return
        }
        currentQuery = normalizedQuery

        val enabledAddons = addons.enabledAddons()
        val hasPendingAddonManifests = enabledAddons.hasPendingEnabledManifests()
        val addonManifestErrorMessage = enabledAddons.firstEnabledManifestError()
        val activeAddons = enabledAddons.filter { it.manifest != null }
        val allRequests = buildSearchRequests(
            addons = activeAddons,
            query = normalizedQuery,
        )
        val contextKey = "$normalizedQuery|${HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent}"
        if (searchContextKey != contextKey) {
            cachedSearchSections = emptyMap()
            searchContextKey = contextKey
        }
        // A partial pass controls fetching, not which providers still own visible results.
        val unresolvedUrls = enabledAddons.filter { it.manifest == null }.map { it.manifestUrl }.toSet()
        val validKeys = allRequests.map { it.key }.toSet()
        cachedSearchSections = cachedSearchSections.filterKeys { it in validKeys || it.manifestUrl in unresolvedUrls }
        val requests = allRequests.filter { request ->
            readyManifestUrls == null ||
                (request.addon.manifestUrl in readyManifestUrls && !request.addon.isRefreshing)
        }
        val hasDeferredCatalogs = requests.size < allRequests.size
        fun retainedSections(): List<HomeCatalogSection> =
            (allRequests.map { it.key } + cachedSearchSections.keys).distinct().mapNotNull(cachedSearchSections::get)

        val requestKey = buildString {
            append(contextKey)
            append('|')
            append(hasPendingAddonManifests)
            append('|')
            append(addonManifestErrorMessage)
            append('|')
            append(readyManifestUrls?.sorted())
            append('|')
            append(enabledAddons.joinToString("|") { it.manifestUrl })
            append('|')
            append(
                allRequests.joinToString(separator = "|") { request ->
                    "${request.addon.manifestUrl}:${request.type}:${request.catalogId}"
                },
            )
        }
        if (canReuseRequestState(forceRefresh, requestKey, lastRequestKey)) return
        lastRequestKey = requestKey

        activeJob?.cancel()
        val generation = ++searchGeneration
        val initialSections = retainedSections()
        _uiState.value = SearchUiState(
            sections = initialSections,
            isLoading = allRequests.isNotEmpty() || hasPendingAddonManifests,
            emptyStateReason = when {
                initialSections.isNotEmpty() || allRequests.isNotEmpty() || hasPendingAddonManifests -> null
                addonManifestErrorMessage != null -> SearchEmptyStateReason.RequestFailed
                activeAddons.isEmpty() -> SearchEmptyStateReason.NoActiveAddons
                else -> SearchEmptyStateReason.NoSearchCatalogs
            },
            errorMessage = addonManifestErrorMessage.takeIf { initialSections.isEmpty() && !hasPendingAddonManifests },
        )
        if (requests.isEmpty()) return

        activeJob = scope.launch {
            val resultChannel = Channel<IndexedSearchResult>(Channel.UNLIMITED)
            val jobs = requests.mapIndexed { index, request ->
                launch {
                    runCatching { loadSearchSection(request, forceRefresh) }
                        .fold(
                            onSuccess = { section ->
                                resultChannel.trySend(
                                    IndexedSearchResult(
                                        index = index,
                                        section = section,
                                    ),
                                )
                            },
                            onFailure = { error ->
                                if (error is CancellationException) throw error
                                resultChannel.trySend(
                                    IndexedSearchResult(
                                        index = index,
                                        error = error,
                                    ),
                                )
                            },
                        )
                }
            }
            val closeChannelJob = launch {
                jobs.joinAll()
                resultChannel.close()
            }
            val results = arrayOfNulls<IndexedSearchResult>(requests.size)

            try {
                for (result in resultChannel) {
                    if (generation != searchGeneration) return@launch
                    results[result.index] = result
                    result.section?.let { cachedSearchSections = cachedSearchSections + (requests[result.index].key to it) }
                    val sections = retainedSections()
                    if (sections.isNotEmpty()) {
                        _uiState.value = SearchUiState(
                            isLoading = true,
                            sections = sections,
                        )
                    }
                }
            } finally {
                closeChannelJob.cancel()
                resultChannel.close()
            }

            val completedResults = results.filterNotNull()
            if (generation != searchGeneration) return@launch
            val firstFailure = completedResults.firstNotNullOfOrNull { it.error?.message }
            val allFailed = completedResults.isNotEmpty() && completedResults.all { it.error != null }
            val publishedSections = retainedSections()

            _uiState.value = SearchUiState(
                isLoading = publishedSections.isEmpty() && (hasPendingAddonManifests || hasDeferredCatalogs),
                sections = publishedSections,
                emptyStateReason = when {
                    publishedSections.isNotEmpty() -> null
                    hasPendingAddonManifests || hasDeferredCatalogs -> null
                    allFailed -> SearchEmptyStateReason.RequestFailed
                    else -> SearchEmptyStateReason.NoResults
                },
                errorMessage = firstFailure.takeIf {
                    allFailed && publishedSections.isEmpty() && !hasPendingAddonManifests && !hasDeferredCatalogs
                },
            )
        }
    }

    fun clear() {
        activeJob?.cancel()
        searchGeneration += 1L
        lastRequestKey = null
        searchContextKey = null
        cachedSearchSections = emptyMap()
        currentQuery = null
        _uiState.value = SearchUiState()
    }

    fun reset() {
        activeJob?.cancel()
        activeDiscoverJob?.cancel()
        searchGeneration += 1L
        discoverGeneration += 1L
        lastRequestKey = null
        searchContextKey = null
        cachedSearchSections = emptyMap()
        currentQuery = null
        discoverSources = emptyList()
        lastDiscoverRequestKey = null
        _uiState.value = SearchUiState()
        _discoverUiState.value = DiscoverUiState()
    }

    fun refreshDiscover(
        addons: List<ManagedAddon>,
        forceRefresh: Boolean = false,
        readyManifestUrls: Set<String>? = null,
    ) {
        val enabledAddons = addons.enabledAddons()
        val hasPendingAddonManifests = enabledAddons.hasPendingEnabledManifests()
        val addonManifestErrorMessage = enabledAddons.firstEnabledManifestError()
        val activeAddons = enabledAddons.filter { it.manifest != null }
        val unresolvedUrls = enabledAddons.filter { it.manifest == null }.map { it.manifestUrl }.toSet()
        val sources = buildDiscoverSources(activeAddons) +
            discoverSources.filter { it.manifestUrl in unresolvedUrls }
        val current = _discoverUiState.value
        val hideUnreleasedContent = HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent
        val requestKey = DiscoverRequestKey(
            sources = sources,
            hideUnreleasedContent = hideUnreleasedContent,
            hasPendingAddonManifests = hasPendingAddonManifests,
            readyManifestUrls = readyManifestUrls,
        )
        if (canReuseRequestState(forceRefresh, requestKey, lastDiscoverRequestKey)) {
            log.d {
                "Reusing discover state type=${current.selectedType} catalog=${current.selectedCatalogKey} " +
                    "genre=${current.selectedGenre ?: "<all>"} items=${current.items.size} nextSkip=${current.nextSkip}"
            }
            return
        }

        discoverSources = sources
        lastDiscoverRequestKey = requestKey
        if (sources.isEmpty()) {
            activeDiscoverJob?.cancel()
            discoverGeneration += 1L
            log.d { "Discover refresh found no compatible discover catalogs" }
            _discoverUiState.value = DiscoverUiState(
                isLoading = hasPendingAddonManifests,
                emptyStateReason = when {
                    hasPendingAddonManifests -> null
                    addonManifestErrorMessage != null -> DiscoverEmptyStateReason.RequestFailed
                    activeAddons.isEmpty() -> DiscoverEmptyStateReason.NoActiveAddons
                    else -> DiscoverEmptyStateReason.NoDiscoverCatalogs
                },
                errorMessage = addonManifestErrorMessage.takeUnless { hasPendingAddonManifests },
            )
            return
        }

        val preferredCatalogKey = loadPreferredCatalogKey()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val selectedCatalog = requireNotNull(
            resolveDiscoverCatalog(
                sources = sources,
                preferredCatalogKey = preferredCatalogKey,
                currentCatalogKey = current.selectedCatalogKey,
            ),
        )
        val typeOptions = sources.map { it.type }.distinct()
        val selectedType = selectedCatalog.type
        val catalogOptions = sources.filter { it.type == selectedType }
        val selectedGenre = selectedCatalog.resolveGenreSelection(current.selectedGenre)
        val retainedItems = current.items.takeIf {
            current.selectedCatalogKey == selectedCatalog.key && current.selectedGenre == selectedGenre
        }.orEmpty()

        _discoverUiState.value = DiscoverUiState(
            typeOptions = typeOptions,
            selectedType = selectedType,
            catalogOptions = catalogOptions,
            selectedCatalogKey = selectedCatalog.key,
            selectedGenre = selectedGenre,
            items = retainedItems,
            isLoading = false,
            nextSkip = null,
            emptyStateReason = null,
            errorMessage = null,
        )

        log.d {
            "Discover refresh prepared type=$selectedType catalog=${selectedCatalog.key} " +
                "genre=${selectedGenre ?: "<all>"} sources=${sources.size}"
        }

        if (readyManifestUrls != null &&
            (selectedCatalog.manifestUrl !in readyManifestUrls ||
                enabledAddons.any { it.manifestUrl == selectedCatalog.manifestUrl && it.isRefreshing })
        ) {
            activeDiscoverJob?.cancel()
            discoverGeneration += 1L
            _discoverUiState.value = _discoverUiState.value.copy(
                isLoading = hasPendingAddonManifests || retainedItems.isEmpty(),
                nextSkip = current.nextSkip.takeIf { current.selectedCatalogKey == selectedCatalog.key },
            )
            return
        }

        loadDiscoverFeed(
            reset = true,
            forceRefresh = forceRefresh,
        )
    }

    fun refreshAfterRecovery(addons: List<ManagedAddon>, readyManifestUrls: Set<String>? = null) {
        refreshDiscover(
            addons = addons,
            forceRefresh = true,
            readyManifestUrls = readyManifestUrls,
        )
        currentQuery?.takeIf(String::isNotBlank)?.let { query ->
            search(
                query = query,
                addons = addons,
                forceRefresh = true,
                readyManifestUrls = readyManifestUrls,
            )
        }
    }

    fun selectDiscoverType(type: String) {
        val current = _discoverUiState.value
        if (current.selectedType == type) return

        val catalogOptions = discoverSources.filter { it.type == type }
        val selectedCatalog = catalogOptions.firstOrNull() ?: run {
            _discoverUiState.value = current.copy(
                selectedType = type,
                catalogOptions = emptyList(),
                selectedCatalogKey = null,
                selectedGenre = null,
                items = emptyList(),
                isLoading = false,
                nextSkip = null,
                emptyStateReason = DiscoverEmptyStateReason.NoDiscoverCatalogs,
                errorMessage = null,
            )
            return
        }

        _discoverUiState.value = current.copy(
            selectedType = type,
            catalogOptions = catalogOptions,
            selectedCatalogKey = selectedCatalog.key,
            selectedGenre = selectedCatalog.resolveGenreSelection(null),
            items = emptyList(),
            isLoading = false,
            nextSkip = null,
            emptyStateReason = null,
            errorMessage = null,
        )
        savePreferredCatalogKey(selectedCatalog.key)
        loadDiscoverFeed(
            reset = true,
            forceRefresh = false,
        )
    }

    fun selectDiscoverCatalog(catalogKey: String) {
        val current = _discoverUiState.value
        if (current.selectedCatalogKey == catalogKey) return

        val selectedCatalog = current.catalogOptions.firstOrNull { it.key == catalogKey } ?: return
        _discoverUiState.value = current.copy(
            selectedCatalogKey = selectedCatalog.key,
            selectedGenre = selectedCatalog.resolveGenreSelection(null),
            items = emptyList(),
            isLoading = false,
            nextSkip = null,
            emptyStateReason = null,
            errorMessage = null,
        )
        savePreferredCatalogKey(selectedCatalog.key)
        loadDiscoverFeed(
            reset = true,
            forceRefresh = false,
        )
    }

    fun selectDiscoverGenre(genre: String?) {
        val current = _discoverUiState.value
        val selectedCatalog = current.selectedCatalog ?: return
        val normalizedGenre = selectedCatalog.resolveGenreSelection(genre)
        if (current.selectedGenre == normalizedGenre) return

        _discoverUiState.value = current.copy(
            selectedGenre = normalizedGenre,
            items = emptyList(),
            isLoading = false,
            nextSkip = null,
            emptyStateReason = null,
            errorMessage = null,
        )
        loadDiscoverFeed(
            reset = true,
            forceRefresh = false,
        )
    }

    fun loadMoreDiscover() {
        val current = _discoverUiState.value
        if (current.isLoading || current.nextSkip == null) return
        loadDiscoverFeed(
            reset = false,
            forceRefresh = false,
        )
    }

    private fun buildSearchRequests(
        addons: List<ManagedAddon>,
        query: String,
    ): List<SearchCatalogRequest> =
        addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            addon to manifest
        }.flatMap { (addon, manifest) ->
            manifest.catalogs
                .filter { catalog -> catalog.supportsSearch() }
                .map { catalog ->
                    SearchCatalogRequest(
                        addon = addon,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.type,
                        query = query,
                        supportsPagination = catalog.supportsPagination(),
                    )
                }
        }

    private fun buildDiscoverSources(addons: List<ManagedAddon>): List<DiscoverCatalogOption> =
        addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            addon to manifest
        }.flatMap { (addon, manifest) ->
            manifest.catalogs
                .filter { catalog -> catalog.supportsDiscover() }
                .map { catalog ->
                    val genreExtra = catalog.genreExtra()
                    DiscoverCatalogOption(
                        key = "${manifest.id}:${catalog.type}:${catalog.id}",
                        addonName = addon.displayTitle,
                        manifestUrl = addon.manifestUrl,
                        type = catalog.type,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        genreOptions = genreExtra?.options.orEmpty(),
                        genreRequired = genreExtra?.isRequired == true,
                        supportsPagination = catalog.supportsPagination(),
                    )
                }
        }

    private fun loadDiscoverFeed(
        reset: Boolean,
        forceRefresh: Boolean,
    ) {
        activeDiscoverJob?.cancel()
        val generation = ++discoverGeneration
        val current = _discoverUiState.value
        val selectedCatalog = current.selectedCatalog ?: return
        val requestedSkip = if (reset) 0 else current.nextSkip ?: return
        val requestUrl = buildCatalogUrl(
            manifestUrl = selectedCatalog.manifestUrl,
            type = selectedCatalog.type,
            catalogId = selectedCatalog.catalogId,
            genre = current.selectedGenre,
            search = null,
            skip = requestedSkip.takeIf { it > 0 },
        )

        log.d {
            "Discover request reset=$reset addon=${selectedCatalog.addonName} type=${selectedCatalog.type} " +
                "catalogId=${selectedCatalog.catalogId} catalogKey=${selectedCatalog.key} " +
                "genre=${current.selectedGenre ?: "<all>"} skip=$requestedSkip url=$requestUrl"
        }

        _discoverUiState.value = current.copy(
            isLoading = true,
            items = current.items,
            nextSkip = if (reset) null else current.nextSkip,
            consecutiveDuplicatePages = if (reset) 0 else current.consecutiveDuplicatePages,
            emptyStateReason = null,
            errorMessage = null,
        )

        activeDiscoverJob = scope.launch {
            runCatching {
                loadDiscoverPage(selectedCatalog, current.selectedGenre, requestedSkip.takeIf { it > 0 }, forceRefresh)
            }.fold(
                onSuccess = { page ->
                    if (generation != discoverGeneration) return@fold
                    val latest = _discoverUiState.value
                    if (latest.selectedCatalogKey != selectedCatalog.key || latest.selectedGenre != current.selectedGenre) {
                        return@fold
                    }
                    val mergedItems = if (reset) {
                        page.items
                    } else {
                        mergeCatalogItems(latest.items, page.items)
                    }
                    val supportsPagination = selectedCatalog.supportsPagination || page.rawItemCount >= CATALOG_PAGE_SIZE
                    val loadedNewItems = reset || mergedItems.size > latest.items.size
                    val paginationState = nextCatalogPaginationState(
                        supportsPagination = supportsPagination,
                        requestedSkip = requestedSkip,
                        page = page,
                        loadedNewItems = loadedNewItems,
                        consecutiveDuplicatePages = if (reset) 0 else latest.consecutiveDuplicatePages,
                    )
                    log.d {
                        "Discover response catalogKey=${selectedCatalog.key} returned=${page.items.size} " +
                            "merged=${mergedItems.size} rawItemCount=${page.rawItemCount} nextSkip=${page.nextSkip} " +
                            "sample=${page.items.previewNames()}"
                    }
                    _discoverUiState.value = latest.copy(
                        items = mergedItems,
                        isLoading = false,
                        nextSkip = paginationState.nextSkip,
                        consecutiveDuplicatePages = paginationState.consecutiveDuplicatePages,
                        emptyStateReason = if (mergedItems.isEmpty()) DiscoverEmptyStateReason.NoResults else null,
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) {
                        log.d {
                            "Discover request cancelled catalogKey=${selectedCatalog.key} addon=${selectedCatalog.addonName} " +
                                "type=${selectedCatalog.type} catalogId=${selectedCatalog.catalogId} " +
                                "genre=${current.selectedGenre ?: "<all>"} skip=$requestedSkip"
                        }
                        return@fold
                    }

                    if (generation != discoverGeneration) return@fold

                    val latest = _discoverUiState.value
                    if (latest.selectedCatalogKey != selectedCatalog.key || latest.selectedGenre != current.selectedGenre) {
                        return@fold
                    }
                    log.e(error) {
                        "Discover request failed catalogKey=${selectedCatalog.key} addon=${selectedCatalog.addonName} " +
                            "type=${selectedCatalog.type} catalogId=${selectedCatalog.catalogId} " +
                            "genre=${current.selectedGenre ?: "<all>"} skip=$requestedSkip url=$requestUrl"
                    }
                    _discoverUiState.value = latest.copy(
                        items = latest.items,
                        isLoading = false,
                        nextSkip = null,
                        emptyStateReason = DiscoverEmptyStateReason.RequestFailed.takeIf { latest.items.isEmpty() },
                        errorMessage = error.message ?: getString(Res.string.discover_empty_load_failed_message),
                    )
                },
            )
        }
    }
}

private data class IndexedSearchResult(
    val index: Int,
    val section: HomeCatalogSection? = null,
    val error: Throwable? = null,
)

private fun CatalogPage.withUnreleasedFilter(): CatalogPage {
    if (!HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) return this
    val filteredItems = items.filterReleasedItems(CurrentDateProvider.todayIsoDate())
    return if (filteredItems.size == items.size) this else copy(items = filteredItems)
}

internal data class SearchCatalogKey(val manifestUrl: String, val type: String, val catalogId: String)

internal data class SearchCatalogRequest(
    val addon: ManagedAddon,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val query: String,
    val supportsPagination: Boolean,
) {
    val key: SearchCatalogKey get() = SearchCatalogKey(addon.manifestUrl, type, catalogId)
}

private suspend fun SearchCatalogRequest.toSection(forceRefresh: Boolean): HomeCatalogSection {
    val manifest = requireNotNull(addon.manifest)
    val page = fetchCatalogPage(
        manifestUrl = manifest.transportUrl,
        type = type,
        catalogId = catalogId,
        search = query,
        forceRefresh = forceRefresh,
    ).withUnreleasedFilter()
    val items = page.items
    require(items.isNotEmpty()) {
        getString(Res.string.search_error_no_results_for_catalog, catalogName)
    }

    return HomeCatalogSection(
        key = "${manifest.id}:search:$type:$catalogId:${query.lowercase()}",
        title = getString(Res.string.discover_catalog_context, catalogName, type.displayLabel()),
        subtitle = addon.displayTitle,
        addonName = addon.displayTitle,
        target = CatalogTarget.Addon(
            manifestUrl = manifest.transportUrl,
            contentType = type,
            catalogId = catalogId,
            supportsPagination = supportsPagination,
        ),
        items = items,
        availableItemCount = page.rawItemCount,
        hasMore = supportsPagination && page.nextSkip != null,
    )
}

private fun AddonCatalog.supportsSearch(): Boolean =
    extra.any { property -> property.name == "search" } &&
        extra.none { property -> property.isRequired && property.name != "search" }

private fun AddonCatalog.supportsDiscover(): Boolean {
    if (extra.any { property -> property.name == "search" && property.isRequired }) {
        return false
    }

    return extra.none { property ->
        when (property.name) {
            "genre" -> property.isRequired && property.options.isEmpty()
            "skip" -> false
            "search" -> false
            else -> property.isRequired
        }
    }
}

private fun AddonCatalog.genreExtra(): AddonExtraProperty? =
    extra.firstOrNull { property -> property.name == "genre" }

private fun DiscoverCatalogOption.resolveGenreSelection(requestedGenre: String?): String? =
    when {
        genreOptions.isEmpty() -> null
        requestedGenre != null && genreOptions.contains(requestedGenre) -> requestedGenre
        genreRequired -> genreOptions.firstOrNull()
        else -> null
    }

private fun List<MetaPreview>.previewNames(limit: Int = 5): String {
    if (isEmpty()) return "[]"
    return take(limit).joinToString(prefix = "[", postfix = if (size > limit) ", ...]" else "]") { item ->
        item.name
    }
}

private fun String.displayLabel(): String =
    localizedMediaTypeLabel(this)

private fun String.typeSortKey(): String =
    when (lowercase()) {
        "movie" -> "0_movie"
        "series" -> "1_series"
        "anime" -> "2_anime"
        else -> "9_$this"
    }
