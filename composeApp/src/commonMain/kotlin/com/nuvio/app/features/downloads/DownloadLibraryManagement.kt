package com.nuvio.app.features.downloads

import androidx.compose.runtime.saveable.listSaver

internal enum class DownloadGroupSelectionState {
    None,
    Partial,
    Full,
}

internal sealed interface DownloadManagerRoute {
    data object Root : DownloadManagerRoute
    data class Show(val showId: String) : DownloadManagerRoute
    data class Season(val showId: String, val seasonNumber: Int) : DownloadManagerRoute
}

internal data class DownloadLibraryManagementState(
    val isManaging: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isExpanded: Boolean = false,
    val route: DownloadManagerRoute = DownloadManagerRoute.Root,
)

internal val DownloadLibraryManagementStateSaver = listSaver<DownloadLibraryManagementState, Any?>(
    save = { state -> state.toSavePayload() },
    restore = ::restoreDownloadLibraryManagementState,
)

internal fun DownloadLibraryManagementState.toSavePayload(): List<Any?> {
    val routeKind: String
    val showId: String?
    val seasonNumber: Int?
    when (val currentRoute = route) {
        DownloadManagerRoute.Root -> {
            routeKind = "root"
            showId = null
            seasonNumber = null
        }
        is DownloadManagerRoute.Show -> {
            routeKind = "show"
            showId = currentRoute.showId
            seasonNumber = null
        }
        is DownloadManagerRoute.Season -> {
            routeKind = "season"
            showId = currentRoute.showId
            seasonNumber = currentRoute.seasonNumber
        }
    }
    return listOf(
        isManaging,
        selectedIds.sorted(),
        isExpanded,
        routeKind,
        showId,
        seasonNumber,
    )
}

internal fun restoreDownloadLibraryManagementState(payload: List<Any?>): DownloadLibraryManagementState? {
    val isManaging = payload.getOrNull(0) as? Boolean ?: return null
    val selectedIds = (payload.getOrNull(1) as? List<*>)
        ?.filterIsInstance<String>()
        ?.toCollection(linkedSetOf())
        ?: return null
    val isExpanded = payload.getOrNull(2) as? Boolean ?: return null
    val routeKind = payload.getOrNull(3) as? String ?: return null
    val showId = payload.getOrNull(4) as? String
    val seasonNumber = payload.getOrNull(5) as? Int
    val route = when (routeKind) {
        "root" -> DownloadManagerRoute.Root
        "show" -> showId?.let(DownloadManagerRoute::Show) ?: return null
        "season" -> if (showId != null && seasonNumber != null) {
            DownloadManagerRoute.Season(showId, seasonNumber)
        } else {
            return null
        }
        else -> return null
    }
    return DownloadLibraryManagementState(
        isManaging = isManaging,
        selectedIds = selectedIds,
        isExpanded = isExpanded,
        route = route,
    )
}

internal sealed interface DownloadLibraryManagementEvent {
    data object EnterManage : DownloadLibraryManagementEvent
    data object Done : DownloadLibraryManagementEvent
    data object LeaveDownloads : DownloadLibraryManagementEvent
    data object ProfileChanged : DownloadLibraryManagementEvent
    data object Clear : DownloadLibraryManagementEvent
    data object Collapse : DownloadLibraryManagementEvent
    data object ExpandRoot : DownloadLibraryManagementEvent
    data object Back : DownloadLibraryManagementEvent
    data class Add(val ids: Set<String>) : DownloadLibraryManagementEvent
    data class Toggle(val ids: Set<String>) : DownloadLibraryManagementEvent
    data class OpenShow(val showId: String) : DownloadLibraryManagementEvent
    data class OpenSeason(val showId: String, val seasonNumber: Int) : DownloadLibraryManagementEvent
    data class ItemsChanged(val items: List<DownloadItem>) : DownloadLibraryManagementEvent
}

internal data class CompletedDownloadMovie(
    val item: DownloadItem,
    val bytes: Long,
)

internal data class CompletedDownloadSeason(
    val seasonNumber: Int,
    val episodes: List<DownloadItem>,
    val bytes: Long,
) {
    val downloadIds: Set<String> = episodes.mapTo(linkedSetOf(), DownloadItem::id)
}

internal data class CompletedDownloadShow(
    val showId: String,
    val title: String,
    val episodes: List<DownloadItem>,
    val seasons: List<CompletedDownloadSeason>,
    val bytes: Long,
) {
    val downloadIds: Set<String> = episodes.mapTo(linkedSetOf(), DownloadItem::id)
}

internal data class CompletedDownloadLibrary(
    val movies: List<CompletedDownloadMovie>,
    val shows: List<CompletedDownloadShow>,
) {
    val allItems: List<DownloadItem> = buildList {
        addAll(movies.map(CompletedDownloadMovie::item))
        shows.forEach { addAll(it.episodes) }
    }
    val allIds: Set<String> = allItems.mapTo(linkedSetOf(), DownloadItem::id)
}

internal data class CompletedDownloadSelectionSummary(
    val selectedIds: Set<String>,
    val movieCount: Int,
    val episodeCount: Int,
    val fileCount: Int,
    val bytes: Long,
)

internal fun buildCompletedDownloadLibrary(items: Collection<DownloadItem>): CompletedDownloadLibrary {
    val completed = items
        .asSequence()
        .filter { it.status == DownloadStatus.Completed }
        .distinctBy(DownloadItem::id)
        .toList()
    val movies = completed
        .filterNot(DownloadItem::isEpisode)
        .sortedWith(compareBy<DownloadItem> { it.title.lowercase() }.thenBy(DownloadItem::id))
        .map { item -> CompletedDownloadMovie(item, item.knownCompletedBytes()) }
    val shows = completed
        .filter(DownloadItem::isEpisode)
        .groupBy(DownloadItem::parentMetaId)
        .map { (showId, episodes) ->
            val sortedEpisodes = episodes.sortedForSeriesDownloads()
            val seasons = sortedEpisodes
                .groupBy { it.seasonNumber ?: 0 }
                .toList()
                .sortedBy { (seasonNumber, _) -> seasonNumber }
                .map { (seasonNumber, seasonEpisodes) ->
                    CompletedDownloadSeason(
                        seasonNumber = seasonNumber,
                        episodes = seasonEpisodes,
                        bytes = seasonEpisodes.sumOf(DownloadItem::knownCompletedBytes),
                    )
                }
            CompletedDownloadShow(
                showId = showId,
                title = sortedEpisodes.firstOrNull()?.title.orEmpty().ifBlank { showId },
                episodes = sortedEpisodes,
                seasons = seasons,
                bytes = sortedEpisodes.sumOf(DownloadItem::knownCompletedBytes),
            )
        }
        .sortedWith(compareBy<CompletedDownloadShow> { it.title.lowercase() }.thenBy(CompletedDownloadShow::showId))
    return CompletedDownloadLibrary(movies = movies, shows = shows)
}

internal fun reduceDownloadLibraryManagement(
    state: DownloadLibraryManagementState,
    event: DownloadLibraryManagementEvent,
): DownloadLibraryManagementState = when (event) {
    DownloadLibraryManagementEvent.EnterManage -> state.copy(isManaging = true)
    DownloadLibraryManagementEvent.Done,
    DownloadLibraryManagementEvent.LeaveDownloads,
    DownloadLibraryManagementEvent.ProfileChanged,
    -> DownloadLibraryManagementState()

    DownloadLibraryManagementEvent.Clear -> state.copy(selectedIds = emptySet())
    DownloadLibraryManagementEvent.Collapse -> state.copy(isExpanded = false)
    DownloadLibraryManagementEvent.ExpandRoot -> state.copy(
        isManaging = true,
        isExpanded = true,
        route = DownloadManagerRoute.Root,
    )
    DownloadLibraryManagementEvent.Back -> when (val route = state.route) {
        DownloadManagerRoute.Root -> state.copy(isExpanded = false)
        is DownloadManagerRoute.Show -> state.copy(route = DownloadManagerRoute.Root)
        is DownloadManagerRoute.Season -> state.copy(route = DownloadManagerRoute.Show(route.showId))
    }
    is DownloadLibraryManagementEvent.Add -> state.copy(
        isManaging = true,
        selectedIds = state.selectedIds + event.ids,
    )
    is DownloadLibraryManagementEvent.Toggle -> state.copy(
        isManaging = true,
        selectedIds = toggleGroupIds(state.selectedIds, event.ids),
    )
    is DownloadLibraryManagementEvent.OpenShow -> state.copy(
        isManaging = true,
        isExpanded = true,
        route = DownloadManagerRoute.Show(event.showId),
    )
    is DownloadLibraryManagementEvent.OpenSeason -> state.copy(
        isManaging = true,
        isExpanded = true,
        route = DownloadManagerRoute.Season(event.showId, event.seasonNumber),
    )
    is DownloadLibraryManagementEvent.ItemsChanged -> {
        val library = buildCompletedDownloadLibrary(event.items)
        val route = when (val currentRoute = state.route) {
            DownloadManagerRoute.Root -> currentRoute
            is DownloadManagerRoute.Show -> if (library.shows.any { it.showId == currentRoute.showId }) {
                currentRoute
            } else {
                DownloadManagerRoute.Root
            }
            is DownloadManagerRoute.Season -> if (
                library.shows
                    .firstOrNull { it.showId == currentRoute.showId }
                    ?.seasons
                    ?.any { it.seasonNumber == currentRoute.seasonNumber } == true
            ) {
                currentRoute
            } else {
                DownloadManagerRoute.Root
            }
        }
        state.copy(
            selectedIds = state.selectedIds.filterTo(linkedSetOf()) { it in library.allIds },
            route = route,
        )
    }
}

internal fun downloadGroupSelectionState(
    selectedIds: Collection<String>,
    groupIds: Collection<String>,
): DownloadGroupSelectionState {
    val group = groupIds.toSet()
    if (group.isEmpty()) return DownloadGroupSelectionState.None
    val selectedCount = group.count(selectedIds.toSet()::contains)
    return when (selectedCount) {
        0 -> DownloadGroupSelectionState.None
        group.size -> DownloadGroupSelectionState.Full
        else -> DownloadGroupSelectionState.Partial
    }
}

internal fun summarizeCompletedDownloadSelection(
    selectedIds: Collection<String>,
    items: Collection<DownloadItem>,
): CompletedDownloadSelectionSummary {
    val library = buildCompletedDownloadLibrary(items)
    val retained = selectedIds.filterTo(linkedSetOf()) { it in library.allIds }
    val selectedItems = library.allItems.filter { it.id in retained }
    val movies = selectedItems.count { !it.isEpisode }
    val episodes = selectedItems.size - movies
    return CompletedDownloadSelectionSummary(
        selectedIds = retained,
        movieCount = movies,
        episodeCount = episodes,
        fileCount = selectedItems.size,
        bytes = selectedItems.sumOf(DownloadItem::knownCompletedBytes),
    )
}

internal fun applyDownloadRemovalResult(
    state: DownloadLibraryManagementState,
    result: DownloadBatchRemovalResult,
): DownloadLibraryManagementState = state.copy(
    isManaging = state.isManaging || result.failedIds.isNotEmpty(),
    selectedIds = (state.selectedIds - result.successfulIds) + result.failedIds,
)

internal fun findExactDownloadedEpisode(
    items: Collection<DownloadItem>,
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): DownloadItem? {
    if (seasonNumber == null || episodeNumber == null) return null
    return items.firstOrNull { item ->
        item.status == DownloadStatus.Completed &&
            item.isEpisode &&
            item.parentMetaId == parentMetaId &&
            item.seasonNumber == seasonNumber &&
            item.episodeNumber == episodeNumber
    }
}

private fun toggleGroupIds(selectedIds: Set<String>, targetIds: Set<String>): Set<String> {
    if (targetIds.isEmpty()) return selectedIds
    return if (targetIds.all(selectedIds::contains)) {
        selectedIds - targetIds
    } else {
        selectedIds + targetIds
    }
}

internal fun DownloadItem.knownCompletedBytes(): Long = when {
    downloadedBytes > 0L -> downloadedBytes
    totalBytes != null -> totalBytes.coerceAtLeast(0L)
    else -> 0L
}
