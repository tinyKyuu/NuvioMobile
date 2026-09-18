package com.nuvio.app.features.library

import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class LibraryLayoutMode {
    HORIZONTAL,
    VERTICAL,
}

enum class LibrarySortOption {
    DEFAULT,
    ADDED_DESC,
    ADDED_ASC,
    TITLE_ASC,
    TITLE_DESC,
}

enum class LibraryWatchedFilter {
    ALL,
    UNWATCHED,
    WATCHED,
}

data class LibraryDisplaySettingsUiState(
    val layoutMode: LibraryLayoutMode = LibraryLayoutMode.HORIZONTAL,
    val sortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val watchedFilter: LibraryWatchedFilter = LibraryWatchedFilter.ALL,
)

object LibraryDisplaySettingsRepository {
    private val _uiState = MutableStateFlow(LibraryDisplaySettingsUiState())
    val uiState: StateFlow<LibraryDisplaySettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        _uiState.value = LibraryDisplaySettingsUiState()
    }

    fun setLayoutMode(layoutMode: LibraryLayoutMode) {
        ensureLoaded()
        if (_uiState.value.layoutMode == layoutMode) return
        _uiState.value = _uiState.value.copy(layoutMode = layoutMode)
        persist()
    }

    fun setSortOption(sortOption: LibrarySortOption) {
        ensureLoaded()
        if (_uiState.value.sortOption == sortOption) return
        _uiState.value = _uiState.value.copy(sortOption = sortOption)
        persist()
    }

    fun setWatchedFilter(watchedFilter: LibraryWatchedFilter) {
        ensureLoaded()
        if (_uiState.value.watchedFilter == watchedFilter) return
        _uiState.value = _uiState.value.copy(watchedFilter = watchedFilter)
        persist()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        _uiState.value = decodeLibraryDisplaySettings(LibraryDisplaySettingsStorage.loadPayload())
    }

    private fun persist() {
        LibraryDisplaySettingsStorage.savePayload(encodeLibraryDisplaySettings(_uiState.value))
    }
}

internal data class LibraryVerticalEntry(
    val item: LibraryItem,
    val section: LibrarySection,
)

internal data class LibraryVerticalProjection(
    val availableSections: List<LibrarySection>,
    val selectedSectionKey: String?,
    val availableTypes: List<String>,
    val selectedType: String?,
    val entries: List<LibraryVerticalEntry>,
)

internal fun availableLibrarySortOptions(sourceMode: LibrarySourceMode): List<LibrarySortOption> =
    if (sourceMode.isRemoteTrackingSource) {
        LibrarySortOption.entries
    } else {
        LibrarySortOption.entries.filterNot { it == LibrarySortOption.DEFAULT }
    }

internal fun effectiveLibrarySortOption(
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
): LibrarySortOption =
    if (selected == LibrarySortOption.DEFAULT && sourceMode == LibrarySourceMode.LOCAL) {
        LibrarySortOption.ADDED_DESC
    } else {
        selected
    }

internal fun sortLibraryItems(
    items: List<LibraryItem>,
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
): List<LibraryItem> =
    when (effectiveLibrarySortOption(selected, sourceMode)) {
        LibrarySortOption.DEFAULT -> items.sortedWith(
            compareBy<LibraryItem> { it.traktRank ?: Int.MAX_VALUE }
                .thenByDescending { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.ADDED_DESC -> items.sortedWith(
            compareByDescending<LibraryItem> { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.ADDED_ASC -> items.sortedWith(
            compareBy<LibraryItem> { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.TITLE_ASC -> items.sortedWith(
            compareBy<LibraryItem> { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.TITLE_DESC -> items.sortedWith(
            compareByDescending<LibraryItem> { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
    }

internal fun sortLibrarySections(
    sections: List<LibrarySection>,
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
): List<LibrarySection> =
    sections.map { section ->
        section.copy(items = sortLibraryItems(section.items, selected, sourceMode))
    }

internal fun filterLibrarySectionsByWatchedState(
    sections: List<LibrarySection>,
    filter: LibraryWatchedFilter,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
): List<LibrarySection> {
    if (filter == LibraryWatchedFilter.ALL) return sections
    val keepWatched = filter == LibraryWatchedFilter.WATCHED
    return sections.mapNotNull { section ->
        val items = section.items.filter { item ->
            val isWatched = WatchingState.isPosterWatched(
                watchedKeys = watchedKeys,
                item = item.toMetaPreview(),
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            )
            isWatched == keepWatched
        }
        section.copy(items = items).takeIf { items.isNotEmpty() }
    }
}

internal fun mergeLibraryTitleItems(
    savedItems: List<LibraryItem>,
    downloadedItems: List<LibraryItem>,
): List<LibraryItem> {
    val itemsByKey = linkedMapOf<String, LibraryItem>()
    savedItems.forEach { item -> itemsByKey[libraryDisplayItemKey(item)] = item }
    downloadedItems.forEach { downloaded ->
        val key = libraryDisplayItemKey(downloaded)
        val saved = itemsByKey[key]
        itemsByKey[key] = if (saved == null) downloaded else saved.withDownloadFallback(downloaded)
    }
    return itemsByKey.values.toList()
}

internal fun mergeAllLibraryTitleItems(
    localItems: List<LibraryItem>,
    selectedSourceItems: List<LibraryItem>,
    downloadedItems: List<LibraryItem>,
): List<LibraryItem> = mergeLibraryTitleItems(
    savedItems = localItems + selectedSourceItems,
    downloadedItems = downloadedItems,
)

private fun LibraryItem.withDownloadFallback(downloaded: LibraryItem): LibraryItem = copy(
    name = name.ifBlank { downloaded.name },
    poster = poster ?: downloaded.poster,
    banner = banner ?: downloaded.banner,
    logo = logo ?: downloaded.logo,
    description = description ?: downloaded.description,
    releaseInfo = releaseInfo ?: downloaded.releaseInfo,
    imdbRating = imdbRating ?: downloaded.imdbRating,
    genres = genres.ifEmpty { downloaded.genres },
    imdbId = imdbId ?: downloaded.imdbId,
    tmdbId = tmdbId ?: downloaded.tmdbId,
    traktId = traktId ?: downloaded.traktId,
)

internal fun buildLibraryVerticalProjection(
    sections: List<LibrarySection>,
    sourceMode: LibrarySourceMode,
    selectedSectionKey: String?,
    selectedType: String?,
    sortOption: LibrarySortOption,
): LibraryVerticalProjection {
    val availableSections = if (sourceMode.isRemoteTrackingSource) sections else emptyList()
    val selectedSection = if (sourceMode.isRemoteTrackingSource) {
        sections.firstOrNull { it.type == selectedSectionKey } ?: sections.firstOrNull()
    } else {
        null
    }
    val baseEntries = if (selectedSection != null) {
        selectedSection.items.map { item -> LibraryVerticalEntry(item, selectedSection) }
    } else {
        sections.flatMap { section ->
            section.items.map { item -> LibraryVerticalEntry(item, section) }
        }
    }
    val deduplicatedEntries = LinkedHashMap<String, LibraryVerticalEntry>()
    baseEntries.forEach { entry ->
        val key = libraryDisplayItemKey(entry.item)
        if (key !in deduplicatedEntries) {
            deduplicatedEntries[key] = entry
        }
    }
    val availableTypes = deduplicatedEntries.values
        .map { entry -> (entry.item.mediaCategory ?: entry.item.type).normalizedLibraryType() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
    val effectiveType = selectedType
        ?.normalizedLibraryType()
        ?.takeIf { it in availableTypes }
    val filteredEntries = deduplicatedEntries.values.filter { entry ->
        effectiveType == null || (entry.item.mediaCategory ?: entry.item.type).normalizedLibraryType() == effectiveType
    }
    val entryByKey = filteredEntries.associateBy { entry -> libraryDisplayItemKey(entry.item) }
    val sortedEntries = sortLibraryItems(
        items = filteredEntries.map { entry -> entry.item },
        selected = sortOption,
        sourceMode = sourceMode,
    ).mapNotNull { item -> entryByKey[libraryDisplayItemKey(item)] }

    return LibraryVerticalProjection(
        availableSections = availableSections,
        selectedSectionKey = selectedSection?.type,
        availableTypes = availableTypes,
        selectedType = effectiveType,
        entries = sortedEntries,
    )
}

internal fun encodeLibraryDisplaySettings(state: LibraryDisplaySettingsUiState): String =
    LibraryDisplaySettingsJson.encodeToString(
        StoredLibraryDisplaySettings(
            layoutMode = state.layoutMode.name,
            sortOption = state.sortOption.name,
            watchedFilter = state.watchedFilter.name,
        ),
    )

internal fun decodeLibraryDisplaySettings(payload: String?): LibraryDisplaySettingsUiState {
    val stored = payload
        ?.takeIf { it.isNotBlank() }
        ?.let { value ->
            runCatching {
                LibraryDisplaySettingsJson.decodeFromString<StoredLibraryDisplaySettings>(value)
            }.getOrNull()
        }
    return LibraryDisplaySettingsUiState(
        layoutMode = stored?.layoutMode
            ?.let { value -> LibraryLayoutMode.entries.firstOrNull { it.name == value } }
            ?: LibraryLayoutMode.HORIZONTAL,
        sortOption = stored?.sortOption
            ?.let { value -> LibrarySortOption.entries.firstOrNull { it.name == value } }
            ?: LibrarySortOption.DEFAULT,
        watchedFilter = stored?.watchedFilter
            ?.let { value -> LibraryWatchedFilter.entries.firstOrNull { it.name == value } }
            ?: LibraryWatchedFilter.ALL,
    )
}

private val LibraryDisplaySettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val LeadingLibraryTitleArticle = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)

private fun libraryTitleSortKey(item: LibraryItem): String =
    libraryTitleTieBreakKey(item)
        .trim()
        .replace(LeadingLibraryTitleArticle, "")

private fun libraryTitleTieBreakKey(item: LibraryItem): String =
    item.name
        .ifBlank { item.id }
        .lowercase()

internal fun libraryDisplayItemKey(item: LibraryItem): String =
    "${item.type.canonicalLibraryContentType()}:${item.id.trim().lowercase()}"

private fun String.normalizedLibraryType(): String = trim().lowercase()

internal fun String.canonicalLibraryContentType(): String = when (normalizedLibraryType()) {
    "movie", "film" -> "movie"
    "series", "show", "tv", "tvshow", "anime" -> "series"
    else -> normalizedLibraryType()
}

internal val LibrarySourceMode.isRemoteTrackingSource: Boolean
    get() = this != LibrarySourceMode.LOCAL

@Serializable
private data class StoredLibraryDisplaySettings(
    @SerialName("layout_mode") val layoutMode: String = LibraryLayoutMode.HORIZONTAL.name,
    @SerialName("sort_option") val sortOption: String = LibrarySortOption.DEFAULT.name,
    @SerialName("watched_filter") val watchedFilter: String = LibraryWatchedFilter.ALL.name,
)
