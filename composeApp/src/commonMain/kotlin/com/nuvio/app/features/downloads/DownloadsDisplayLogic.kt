package com.nuvio.app.features.downloads

internal enum class CompletedDownloadSort {
    RecentlyAdded,
    TitleAscending,
    LargestFirst,
    SmallestFirst,
}

internal data class CompletedDownloadShowGroup(
    val representative: DownloadItem,
    val episodes: List<DownloadItem>,
) {
    val addedAtEpochMs: Long
        get() = episodes.maxOfOrNull(DownloadItem::createdAtEpochMs) ?: 0L

    val storedBytes: Long
        get() = episodes.sumOf(DownloadItem::storedBytesForDisplay)
}

internal fun currentDownloadsForDisplay(items: List<DownloadItem>): List<DownloadItem> =
    items
        .filter { it.status != DownloadStatus.Completed }
        .sortedWith(
            compareBy<DownloadItem> { it.currentDownloadDisplayRank() }
                .thenBy { it.createdAtEpochMs }
                .thenBy { it.id },
        )

internal fun liveStatusItemsForDisplay(items: List<DownloadItem>): List<DownloadItem> =
    currentDownloadsForDisplay(items).filter { item ->
        item.status == DownloadStatus.Downloading ||
            item.status == DownloadStatus.WaitingForNetwork ||
            item.status == DownloadStatus.Finalizing ||
            item.status == DownloadStatus.Queued
    }

internal fun completedMoviesForDisplay(
    items: List<DownloadItem>,
    sort: CompletedDownloadSort,
): List<DownloadItem> = sortCompletedMovies(
    items = items.filter { it.status == DownloadStatus.Completed && !it.isEpisode },
    sort = sort,
)

internal fun completedShowsForDisplay(
    items: List<DownloadItem>,
    sort: CompletedDownloadSort,
): List<CompletedDownloadShowGroup> {
    val groups = items
        .filter { it.status == DownloadStatus.Completed && it.isEpisode }
        .groupBy(DownloadItem::parentMetaId)
        .mapNotNull { (_, episodes) ->
            val sortedEpisodes = episodes.sortedForSeriesDownloads()
            sortedEpisodes.firstOrNull()?.let { representative ->
                CompletedDownloadShowGroup(
                    representative = representative,
                    episodes = sortedEpisodes,
                )
            }
        }

    return when (sort) {
        CompletedDownloadSort.RecentlyAdded -> groups.sortedWith(
            compareByDescending<CompletedDownloadShowGroup> { it.addedAtEpochMs }
                .thenBy { it.titleSortKey() }
                .thenBy { it.representative.parentMetaId },
        )
        CompletedDownloadSort.TitleAscending -> groups.sortedWith(
            compareBy<CompletedDownloadShowGroup> { it.titleSortKey() }
                .thenBy { it.representative.parentMetaId },
        )
        CompletedDownloadSort.LargestFirst -> groups.sortedWith(
            compareByDescending<CompletedDownloadShowGroup> { it.storedBytes }
                .thenBy { it.titleSortKey() }
                .thenBy { it.representative.parentMetaId },
        )
        CompletedDownloadSort.SmallestFirst -> groups.sortedWith(
            compareBy<CompletedDownloadShowGroup> { it.storedBytes }
                .thenBy { it.titleSortKey() }
                .thenBy { it.representative.parentMetaId },
        )
    }
}

private fun sortCompletedMovies(
    items: List<DownloadItem>,
    sort: CompletedDownloadSort,
): List<DownloadItem> = when (sort) {
    CompletedDownloadSort.RecentlyAdded -> items.sortedWith(
        compareByDescending<DownloadItem> { it.createdAtEpochMs }
            .thenBy { it.titleSortKey() }
            .thenBy { it.id },
    )
    CompletedDownloadSort.TitleAscending -> items.sortedWith(
        compareBy<DownloadItem> { it.titleSortKey() }
            .thenBy { it.id },
    )
    CompletedDownloadSort.LargestFirst -> items.sortedWith(
        compareByDescending<DownloadItem> { it.storedBytesForDisplay() }
            .thenBy { it.titleSortKey() }
            .thenBy { it.id },
    )
    CompletedDownloadSort.SmallestFirst -> items.sortedWith(
        compareBy<DownloadItem> { it.storedBytesForDisplay() }
            .thenBy { it.titleSortKey() }
            .thenBy { it.id },
    )
}

private fun DownloadItem.currentDownloadDisplayRank(): Int = when (status) {
    DownloadStatus.Downloading,
    DownloadStatus.WaitingForNetwork,
    DownloadStatus.Finalizing,
    -> 0
    DownloadStatus.Queued -> 1
    DownloadStatus.Paused -> 2
    DownloadStatus.Failed -> 3
    DownloadStatus.Completed -> 4
}

private fun DownloadItem.titleSortKey(): String = title.trim().lowercase()

private fun CompletedDownloadShowGroup.titleSortKey(): String =
    representative.title.trim().lowercase()

private fun DownloadItem.storedBytesForDisplay(): Long =
    (totalBytes ?: downloadedBytes).coerceAtLeast(0L)
