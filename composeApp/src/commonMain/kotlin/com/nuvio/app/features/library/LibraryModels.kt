package com.nuvio.app.features.library

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.tracking.TrackingAttributedItem
import kotlinx.serialization.Serializable

@Serializable
data class LibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val posterShape: PosterShape = PosterShape.Poster,
    val addonBaseUrl: String? = null,
    val listKeys: Set<String> = emptySet(),
    val traktRank: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val traktId: Int? = null,
    /** Original media category from the tracking provider (e.g. "anime").
     *  Used for UI filtering while [type] stays as "movie"/"series" for meta addon compatibility. */
    val mediaCategory: String? = null,
    override val trackingProviderId: String? = null,
    override val trackingProviderItemId: String? = null,
    override val trackingSourceUrl: String? = null,
    val savedAtEpochMs: Long,
) : TrackingAttributedItem {
    override val trackingContentId: String
        get() = id
}

data class LibrarySection(
    val type: String,
    val displayTitle: String,
    val items: List<LibraryItem>,
)

internal fun librarySectionItemKey(sectionType: String, item: LibraryItem): String =
    "$sectionType|${item.type}|${item.id}"

enum class LibrarySourceMode {
    LOCAL,
    TRAKT,
    SIMKL,
}

data class LibraryUiState(
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val items: List<LibraryItem> = emptyList(),
    val sections: List<LibrarySection> = emptyList(),
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

internal data class LibraryArtworkFallback(
    val type: String,
    val ids: Set<String>,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
)

internal fun LibraryItem.withArtworkFallback(
    candidates: List<LibraryArtworkFallback>,
): LibraryItem {
    val itemType = type.normalizedLibraryArtworkType()
    val itemIds = buildSet {
        add(id.normalizedLibraryArtworkId())
        imdbId?.let { add(it.normalizedLibraryArtworkId()) }
        tmdbId?.let { value ->
            add("tmdb:$value")
        }
        traktId?.let { value ->
            add("trakt:$value")
        }
    }.filterTo(linkedSetOf(), String::isNotBlank)
    val fallback = candidates.firstOrNull { candidate ->
        candidate.type.normalizedLibraryArtworkType() == itemType &&
            candidate.ids.any { id -> id.normalizedLibraryArtworkId() in itemIds }
    } ?: return this
    return copy(
        poster = fallback.poster ?: poster,
        banner = fallback.banner ?: banner,
        logo = fallback.logo ?: logo,
    )
}

private fun String.normalizedLibraryArtworkType(): String = when (trim().lowercase()) {
    "show", "tv", "tvshow" -> "series"
    else -> trim().lowercase()
}

private fun String.normalizedLibraryArtworkId(): String = trim().lowercase()

fun MetaDetails.toLibraryItem(savedAtEpochMs: Long): LibraryItem =
    LibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        posterShape = PosterShape.Poster,
        imdbId = id.takeIf { it.startsWith("tt") },
        savedAtEpochMs = savedAtEpochMs,
    )

fun MetaPreview.toLibraryItem(savedAtEpochMs: Long): LibraryItem =
    LibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = banner,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        posterShape = posterShape,
        imdbId = id.takeIf { it.startsWith("tt") },
        savedAtEpochMs = savedAtEpochMs,
    )

fun LibraryItem.toMetaPreview(): MetaPreview =
    MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = banner,
        logo = logo,
        posterShape = posterShape,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
    )
