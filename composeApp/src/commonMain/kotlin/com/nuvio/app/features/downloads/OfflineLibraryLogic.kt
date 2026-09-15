package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails

internal enum class OfflineRefreshReason {
    Fresh,
    Due,
    Missing,
    BackingOff,
}

internal data class OfflineRefreshDecision(
    val shouldRefresh: Boolean,
    val reason: OfflineRefreshReason,
)

internal fun decideOfflineRefresh(
    record: OfflineTitleRecord,
    nowEpochMs: Long,
    manual: Boolean,
): OfflineRefreshDecision {
    if (manual) return OfflineRefreshDecision(true, OfflineRefreshReason.Due)
    val missing = !record.metadataComplete
    if (missing) {
        val retryAt = record.nextRetryEpochMs
        return if (retryAt != null && nowEpochMs < retryAt) {
            OfflineRefreshDecision(false, OfflineRefreshReason.BackingOff)
        } else {
            OfflineRefreshDecision(true, OfflineRefreshReason.Missing)
        }
    }
    val lastAttempt = listOfNotNull(
        record.lastAutomaticAttemptEpochMs,
        record.lastSuccessfulRefreshEpochMs,
    ).maxOrNull()
    return if (lastAttempt == null || nowEpochMs - lastAttempt >= OfflineMetadataFreshnessMs) {
        OfflineRefreshDecision(true, OfflineRefreshReason.Due)
    } else {
        OfflineRefreshDecision(false, OfflineRefreshReason.Fresh)
    }
}

internal fun offlineRetryDelayMs(failureCount: Int): Long {
    val exponent = (failureCount - 1).coerceIn(0, 6)
    return (30_000L * (1L shl exponent)).coerceAtMost(30L * 60L * 1_000L)
}

internal data class OfflineRecordReconciliation(
    val recordsToUpsert: List<OfflineTitleRecord>,
    val keysToDelete: Set<String>,
    val artworkToDelete: List<OfflineArtworkRef>,
)

internal fun reconcileOfflineRecords(
    existingRecords: Collection<OfflineTitleRecord>,
    ownerProfileKey: String,
    downloads: List<DownloadItem>,
    localeTag: String?,
    nowEpochMs: Long,
): OfflineRecordReconciliation {
    val existing = existingRecords.associateBy(OfflineTitleRecord::key)
    val grouped = downloads.groupBy { item ->
        offlineTitleKey(
            ownerProfileKey = ownerProfileKey,
            type = canonicalOfflineMetaType(item.parentMetaType.ifBlank { item.contentType }),
            id = item.parentMetaId,
        )
    }
    val removedArtwork = mutableListOf<OfflineArtworkRef>()
    val candidates = grouped.map { (key, titleDownloads) ->
        val previous = existing[key]
        val minimal = buildMinimalOfflineMetadata(titleDownloads)
        val downloadIds = titleDownloads.mapTo(linkedSetOf(), DownloadItem::id)
        val providers = titleDownloads.mapNotNullTo(linkedSetOf(), DownloadItem::providerAddonId)
        if (previous == null) {
            OfflineTitleRecord(
                key = key,
                ownerProfileKey = ownerProfileKey,
                metaType = minimal.type,
                metaId = minimal.id,
                providerAddonIds = providers,
                providerMetaIds = setOf(minimal.id),
                localeTag = localeTag,
                downloadIds = downloadIds,
                metadata = minimal,
                createdAtEpochMs = titleDownloads.minOf(DownloadItem::createdAtEpochMs),
                updatedAtEpochMs = nowEpochMs,
            )
        } else {
            val referencesChanged = previous.downloadIds != downloadIds
            val metadata = mergeOfflineMetadata(previous.metadata, incoming = null, downloads = titleDownloads)
            val requiredArtwork = requiredOfflineArtwork(metadata, titleDownloads)
            val retainedArtwork = previous.artwork.filter { (role, reference) ->
                requiredArtwork[role] == reference.remoteUrl
            }
            removedArtwork += previous.artwork
                .filterKeys { role -> role !in retainedArtwork }
                .values
            previous.copy(
                providerAddonIds = previous.providerAddonIds + providers,
                localeTag = previous.localeTag ?: localeTag,
                downloadIds = downloadIds,
                metadata = metadata,
                artwork = retainedArtwork,
                artworkComplete = previous.artworkComplete &&
                    requiredArtwork.keys.all(retainedArtwork::containsKey),
                generation = if (referencesChanged) previous.generation + 1L else previous.generation,
                updatedAtEpochMs = if (referencesChanged) nowEpochMs else previous.updatedAtEpochMs,
            )
        }
    }
    val deleted = existing.keys - grouped.keys
    removedArtwork += deleted.flatMap { key -> existing.getValue(key).artwork.values }
    return OfflineRecordReconciliation(
        recordsToUpsert = candidates.filter { candidate -> existing[candidate.key] != candidate },
        keysToDelete = deleted,
        artworkToDelete = removedArtwork,
    )
}

internal fun canApplyOfflineRefresh(
    record: OfflineTitleRecord?,
    capturedOwnerProfileKey: String,
    capturedGeneration: Long,
    capturedDownloadIds: Set<String>,
    loadedOwnerProfileKey: String?,
): Boolean = record != null &&
    loadedOwnerProfileKey == capturedOwnerProfileKey &&
    record.ownerProfileKey == capturedOwnerProfileKey &&
    record.generation == capturedGeneration &&
    record.downloadIds == capturedDownloadIds &&
    record.downloadIds.isNotEmpty()

internal fun canonicalOfflineMetaType(type: String): String = when (type.trim().lowercase()) {
    "show", "tv", "tvshow" -> "series"
    else -> type.trim().lowercase().ifBlank { "movie" }
}

internal fun buildMinimalOfflineMetadata(items: List<DownloadItem>): OfflineMetaSnapshot {
    val first = requireNotNull(items.minByOrNull(DownloadItem::createdAtEpochMs))
    val type = canonicalOfflineMetaType(first.parentMetaType.ifBlank { first.contentType })
    return OfflineMetaSnapshot(
        id = first.parentMetaId,
        type = type,
        name = first.title.ifBlank { first.parentMetaId },
        poster = first.poster,
        background = first.background,
        logo = first.logo,
        videos = items.mapNotNull(DownloadItem::toOfflineVideo).distinctBy(OfflineVideo::identityKey),
    )
}

internal fun mergeOfflineMetadata(
    previous: OfflineMetaSnapshot,
    incoming: MetaDetails?,
    downloads: List<DownloadItem>,
): OfflineMetaSnapshot {
    val next = incoming?.toOfflineSnapshot() ?: previous
    val downloadedVideos = downloads.mapNotNull(DownloadItem::toOfflineVideo)
    val downloadedKeys = downloadedVideos.mapTo(hashSetOf(), OfflineVideo::identityKey)
    val retainedPreviousDownloads = previous.videos.filter { it.identityKey() in downloadedKeys }
    val mergedVideos = buildList {
        addAll(next.videos)
        addAll(retainedPreviousDownloads)
        addAll(downloadedVideos)
    }.distinctBy(OfflineVideo::identityKey)
    return next.copy(
        id = next.id.ifBlank { previous.id },
        type = canonicalOfflineMetaType(next.type.ifBlank { previous.type }),
        name = next.name.ifBlank { previous.name },
        poster = next.poster ?: previous.poster,
        background = next.background ?: previous.background,
        logo = next.logo ?: previous.logo,
        description = next.description ?: previous.description,
        releaseInfo = next.releaseInfo ?: previous.releaseInfo,
        lastAirDate = next.lastAirDate ?: previous.lastAirDate,
        status = next.status ?: previous.status,
        imdbRating = next.imdbRating ?: previous.imdbRating,
        ageRating = next.ageRating ?: previous.ageRating,
        runtime = next.runtime ?: previous.runtime,
        externalRatings = next.externalRatings.ifEmpty { previous.externalRatings },
        genres = next.genres.ifEmpty { previous.genres },
        director = next.director.ifEmpty { previous.director },
        writer = next.writer.ifEmpty { previous.writer },
        cast = next.cast.ifEmpty { previous.cast },
        productionCompanies = next.productionCompanies.ifEmpty { previous.productionCompanies },
        networks = next.networks.ifEmpty { previous.networks },
        country = next.country ?: previous.country,
        awards = next.awards ?: previous.awards,
        language = next.language ?: previous.language,
        website = next.website ?: previous.website,
        defaultVideoId = next.defaultVideoId ?: previous.defaultVideoId,
        seasonPosters = previous.seasonPosters + next.seasonPosters,
        videos = mergedVideos,
    )
}

private fun DownloadItem.toOfflineVideo(): OfflineVideo? {
    if (!isEpisode) return null
    return OfflineVideo(
        id = videoId,
        title = episodeTitle ?: "Episode ${episodeNumber ?: 0}",
        thumbnail = episodeThumbnail,
        season = seasonNumber,
        episode = episodeNumber,
    )
}

private fun OfflineVideo.identityKey(): String =
    if (season != null || episode != null) "${season ?: -1}:${episode ?: -1}" else id

internal fun requiredOfflineArtwork(
    metadata: OfflineMetaSnapshot,
    downloads: List<DownloadItem>,
): Map<String, String> = buildMap {
    metadata.poster?.takeIf(String::isNotBlank)?.let { put(offlinePosterRole, it) }
    metadata.background?.takeIf(String::isNotBlank)?.let { put(offlineBackgroundRole, it) }
    metadata.logo?.takeIf(String::isNotBlank)?.let { put(offlineLogoRole, it) }
    metadata.seasonPosters.entries.sortedBy { entry -> entry.key }.forEach { (season, url) ->
        url.takeIf(String::isNotBlank)?.let { put(offlineSeasonPosterRole(season), it) }
    }
    val downloadedEpisodes = downloads.mapTo(hashSetOf()) { item ->
        item.seasonNumber to item.episodeNumber
    }
    metadata.videos.forEach { video ->
        if ((video.season to video.episode) in downloadedEpisodes) {
            video.thumbnail?.takeIf(String::isNotBlank)?.let { url ->
                put(offlineEpisodeThumbnailRole(video.season, video.episode), url)
            }
        }
    }
}.entries.take(64).associate { it.key to it.value }

internal fun offlineArtworkAssetKey(url: String, contentType: String?): String {
    val extension = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        else -> "jpg"
    }
    return "${DownloadSourceFingerprint.sha256Hex(url)}.$extension"
}
