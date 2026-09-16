package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaCompany
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.library.LibraryItem
import kotlinx.serialization.Serializable

internal const val OfflineTitleRecordVersion = 1
internal const val OfflineMetadataFreshnessMs = 24L * 60L * 60L * 1_000L

@Serializable
internal data class OfflineTitleRecord(
    val recordVersion: Int = OfflineTitleRecordVersion,
    val key: String,
    val ownerProfileKey: String,
    val metaType: String,
    val metaId: String,
    val providerAddonIds: Set<String> = emptySet(),
    val providerMetaIds: Set<String> = emptySet(),
    val localeTag: String? = null,
    val downloadIds: Set<String> = emptySet(),
    val metadata: OfflineMetaSnapshot,
    val artwork: Map<String, OfflineArtworkRef> = emptyMap(),
    val metadataSourceUrl: String? = null,
    val metadataEtag: String? = null,
    val metadataLastModified: String? = null,
    val lastSuccessfulRefreshEpochMs: Long? = null,
    val lastAutomaticAttemptEpochMs: Long? = null,
    val lastRefreshFailureEpochMs: Long? = null,
    val refreshFailureCount: Int = 0,
    val nextRetryEpochMs: Long? = null,
    val metadataComplete: Boolean = false,
    val artworkComplete: Boolean = false,
    val lastArtworkAttemptEpochMs: Long? = null,
    val lastArtworkFailureEpochMs: Long? = null,
    val artworkFailureCount: Int = 0,
    val nextArtworkRetryEpochMs: Long? = null,
    val generation: Long = 1L,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
internal data class OfflineArtworkRef(
    val role: String,
    val remoteUrl: String,
    val assetKey: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val updatedAtEpochMs: Long,
)

@Serializable
internal data class OfflineMetaSnapshot(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val lastAirDate: String? = null,
    val status: String? = null,
    val imdbRating: String? = null,
    val ageRating: String? = null,
    val runtime: String? = null,
    val externalRatings: List<OfflineExternalRating> = emptyList(),
    val genres: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val writer: List<String> = emptyList(),
    val cast: List<OfflinePerson> = emptyList(),
    val productionCompanies: List<OfflineCompany> = emptyList(),
    val networks: List<OfflineCompany> = emptyList(),
    val country: String? = null,
    val awards: String? = null,
    val language: String? = null,
    val website: String? = null,
    val hasScheduledVideos: Boolean = false,
    val defaultVideoId: String? = null,
    val seasonPosters: Map<Int, String> = emptyMap(),
    val videos: List<OfflineVideo> = emptyList(),
)

@Serializable
internal data class OfflineExternalRating(val source: String, val value: Double)

@Serializable
internal data class OfflinePerson(
    val name: String,
    val role: String? = null,
    val photo: String? = null,
    val tmdbId: Int? = null,
)

@Serializable
internal data class OfflineCompany(
    val name: String,
    val logo: String? = null,
    val tmdbId: Int? = null,
)

@Serializable
internal data class OfflineVideo(
    val id: String,
    val title: String,
    val released: String? = null,
    val available: Boolean = true,
    val thumbnail: String? = null,
    val seasonPoster: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    val rating: Double? = null,
)

internal data class OfflineTitle(
    val record: OfflineTitleRecord,
    val downloads: List<DownloadItem>,
) {
    val playableDownloads: List<DownloadItem> = downloads.filter(DownloadItem::isPlayable)
    val isPlayable: Boolean get() = playableDownloads.isNotEmpty()

    fun toMetaDetails(): MetaDetails = record.metadata.toMetaDetails(record.artwork).copy(
        id = record.metaId,
        type = record.metaType,
    )

    fun toMetaPreview(): MetaPreview {
        val meta = toMetaDetails()
        return MetaPreview(
            id = meta.id,
            type = meta.type,
            name = meta.name,
            poster = meta.poster,
            banner = meta.background,
            logo = meta.logo,
            posterShape = PosterShape.Poster,
            description = meta.description,
            releaseInfo = meta.releaseInfo,
            imdbRating = meta.imdbRating,
            genres = meta.genres,
        )
    }

    fun toLibraryItem(): LibraryItem {
        val meta = toMetaDetails()
        return LibraryItem(
            id = meta.id,
            type = meta.type,
            name = meta.name,
            poster = meta.poster,
            banner = meta.background,
            logo = meta.logo,
            description = meta.description,
            releaseInfo = meta.releaseInfo,
            imdbRating = meta.imdbRating,
            genres = meta.genres,
            posterShape = PosterShape.Poster,
            imdbId = meta.id.takeIf { it.startsWith("tt") },
            savedAtEpochMs = record.createdAtEpochMs,
        )
    }
}

internal data class OfflinePlaybackArtwork(
    val poster: String?,
    val background: String?,
    val logo: String?,
    val episodeThumbnail: String?,
)

internal fun OfflineTitle.localPlaybackArtwork(
    seasonNumber: Int?,
    episodeNumber: Int?,
): OfflinePlaybackArtwork = OfflinePlaybackArtwork(
    poster = record.artwork.localArtwork(offlinePosterRole),
    background = record.artwork.localArtwork(offlineBackgroundRole),
    logo = record.artwork.localArtwork(offlineLogoRole),
    episodeThumbnail = if (seasonNumber != null || episodeNumber != null) {
        record.artwork.localArtwork(offlineEpisodeThumbnailRole(seasonNumber, episodeNumber))
    } else {
        null
    },
)

internal data class OfflineLibraryUiState(
    val titles: List<OfflineTitle> = emptyList(),
    val refreshingKeys: Set<String> = emptySet(),
)

internal fun offlineTitleKey(ownerProfileKey: String, type: String, id: String): String =
    listOf(ownerProfileKey.trim(), type.trim().lowercase(), id.trim()).joinToString("|")

/**
 * Snapshot conversion is adapted from AKRusso/NuvioMobile-Enhanced at
 * ac03c46a159e7e4bbbcd77ad4bdf6ea604b34107. The storage and ownership model is
 * intentionally different: this snapshot belongs to one profile/title record,
 * not to each downloaded episode.
 */
internal fun MetaDetails.toOfflineSnapshot(): OfflineMetaSnapshot = OfflineMetaSnapshot(
    id = id,
    type = type,
    name = name,
    poster = poster,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    lastAirDate = lastAirDate,
    status = status,
    imdbRating = imdbRating,
    ageRating = ageRating,
    runtime = runtime,
    externalRatings = externalRatings.map { OfflineExternalRating(it.source, it.value) },
    genres = genres,
    director = director,
    writer = writer,
    cast = cast.map { OfflinePerson(it.name, it.role, it.photo, it.tmdbId) },
    productionCompanies = productionCompanies.map { OfflineCompany(it.name, it.logo, it.tmdbId) },
    networks = networks.map { OfflineCompany(it.name, it.logo, it.tmdbId) },
    country = country,
    awards = awards,
    language = language,
    website = website,
    hasScheduledVideos = hasScheduledVideos,
    defaultVideoId = defaultVideoId,
    seasonPosters = seasonPosters,
    videos = videos.map { video ->
        OfflineVideo(
            id = video.id,
            title = video.title,
            released = video.released,
            available = video.available,
            thumbnail = video.thumbnail,
            seasonPoster = video.seasonPoster,
            season = video.season,
            episode = video.episode,
            overview = video.overview,
            runtime = video.runtime,
            rating = video.rating,
        )
    },
)

internal fun OfflineMetaSnapshot.toMetaDetails(artwork: Map<String, OfflineArtworkRef>): MetaDetails =
    MetaDetails(
        id = id,
        type = type,
        name = name,
        poster = artwork.localArtwork(offlinePosterRole) ?: poster,
        background = artwork.localArtwork(offlineBackgroundRole) ?: background,
        logo = artwork.localArtwork(offlineLogoRole) ?: logo,
        description = description,
        releaseInfo = releaseInfo,
        lastAirDate = lastAirDate,
        status = status,
        imdbRating = imdbRating,
        ageRating = ageRating,
        runtime = runtime,
        externalRatings = externalRatings.map { MetaExternalRating(it.source, it.value) },
        genres = genres,
        director = director,
        writer = writer,
        cast = cast.map { MetaPerson(it.name, it.role, it.photo, it.tmdbId) },
        productionCompanies = productionCompanies.map { MetaCompany(it.name, it.logo, it.tmdbId) },
        networks = networks.map { MetaCompany(it.name, it.logo, it.tmdbId) },
        country = country,
        awards = awards,
        language = language,
        website = website,
        hasScheduledVideos = hasScheduledVideos,
        defaultVideoId = defaultVideoId,
        seasonPosters = seasonPosters.mapValues { (season, remoteUrl) ->
            artwork.localArtwork(offlineSeasonPosterRole(season)) ?: remoteUrl
        },
        videos = videos.map { video ->
            MetaVideo(
                id = video.id,
                title = video.title,
                released = video.released,
                available = video.available,
                thumbnail = artwork.localArtwork(offlineEpisodeThumbnailRole(video.season, video.episode))
                    ?: video.thumbnail,
                seasonPoster = video.season?.let { season ->
                    artwork.localArtwork(offlineSeasonPosterRole(season))
                } ?: video.seasonPoster,
                season = video.season,
                episode = video.episode,
                overview = video.overview,
                runtime = video.runtime,
                rating = video.rating,
            )
        },
        isOfflineSnapshot = true,
    )

private fun Map<String, OfflineArtworkRef>.localArtwork(role: String): String? =
    get(role)?.assetKey?.let(OfflineArtworkPlatform::localUri)

internal const val offlinePosterRole = "poster"
internal const val offlineBackgroundRole = "background"
internal const val offlineLogoRole = "logo"
internal fun offlineSeasonPosterRole(season: Int): String = "season:$season"
internal fun offlineEpisodeThumbnailRole(season: Int?, episode: Int?): String =
    "episode:${season ?: -1}:${episode ?: -1}"
