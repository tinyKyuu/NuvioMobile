package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OfflineLibraryLogicTest {
    @Test
    fun `multiple episode downloads share one title snapshot and keep specials`() {
        val downloads = listOf(
            download(id = "d1", season = 0, episode = 1, title = "Special"),
            download(id = "d2", season = 1, episode = 2, title = "Second"),
            download(id = "d3", season = 2, episode = 3, title = "Third"),
        )
        val minimal = buildMinimalOfflineMetadata(downloads)
        val enriched = mergeOfflineMetadata(
            previous = minimal,
            incoming = MetaDetails(
                id = "tt123",
                type = "series",
                name = "A Show",
                seasonPosters = mapOf(
                    0 to "https://images.test/specials.jpg",
                    1 to "https://images.test/s1.jpg",
                    2 to "https://images.test/s2.jpg",
                ),
                videos = listOf(
                    MetaVideo(id = "remote-s1e2", title = "Second, refreshed", season = 1, episode = 2),
                ),
            ),
            downloads = downloads,
        )

        assertEquals(setOf(0, 1, 2), enriched.seasonPosters.keys)
        assertEquals(3, enriched.videos.size)
        assertTrue(enriched.videos.any { it.season == 0 && it.episode == 1 })
        assertTrue(enriched.videos.any { it.season == 2 && it.episode == 3 })
    }

    @Test
    fun `new provider metadata cannot drop a downloaded episode`() {
        val download = download(id = "d1", season = 4, episode = 7, title = "Retained")
        val previous = buildMinimalOfflineMetadata(listOf(download)).copy(
            description = "Last valid description",
        )
        val merged = mergeOfflineMetadata(
            previous = previous,
            incoming = MetaDetails(
                id = "tt123",
                type = "series",
                name = "A Show",
                videos = emptyList(),
            ),
            downloads = listOf(download),
        )

        assertEquals("Last valid description", merged.description)
        assertNotNull(merged.videos.singleOrNull { it.season == 4 && it.episode == 7 })
    }

    @Test
    fun `automatic refresh is limited to one check per day for valid metadata`() {
        val now = 2_000_000_000L
        val recent = record(
            metadataComplete = true,
            lastAutomaticAttemptEpochMs = now - OfflineMetadataFreshnessMs + 1,
        ).copy(lastSuccessfulRefreshEpochMs = now - OfflineMetadataFreshnessMs + 1)
        val stale = recent.copy(lastSuccessfulRefreshEpochMs = now - OfflineMetadataFreshnessMs)

        assertFalse(decideOfflineRefresh(recent, now, manual = false).shouldRefresh)
        assertTrue(decideOfflineRefresh(stale, now, manual = false).shouldRefresh)
        assertTrue(decideOfflineRefresh(recent, now, manual = true).shouldRefresh)
    }

    @Test
    fun `successful normal fetch also starts the freshness window`() {
        val now = 2_000_000_000L
        val recent = record(
            metadataComplete = true,
            lastAutomaticAttemptEpochMs = null,
        ).copy(lastSuccessfulRefreshEpochMs = now - OfflineMetadataFreshnessMs + 1)

        assertFalse(decideOfflineRefresh(recent, now, manual = false).shouldRefresh)
    }

    @Test
    fun `recent manual success supersedes an old automatic attempt`() {
        val now = 2_000_000_000L
        val record = record(
            metadataComplete = true,
            lastAutomaticAttemptEpochMs = now - OfflineMetadataFreshnessMs,
        ).copy(lastSuccessfulRefreshEpochMs = now)

        assertFalse(decideOfflineRefresh(record, now, manual = false).shouldRefresh)
    }

    @Test
    fun `missing metadata retries only after bounded backoff`() {
        val now = 100_000L
        val waiting = record(
            metadataComplete = false,
            nextRetryEpochMs = now + 1,
        )
        assertFalse(decideOfflineRefresh(waiting, now, manual = false).shouldRefresh)
        assertTrue(decideOfflineRefresh(waiting, now + 1, manual = false).shouldRefresh)
        assertEquals(30_000L, offlineRetryDelayMs(1))
        assertEquals(30L * 60L * 1_000L, offlineRetryDelayMs(20))
    }

    @Test
    fun `complete stale metadata retries at failure backoff boundary`() {
        val successAt = 1_000_000L
        val failureAt = successAt + OfflineMetadataFreshnessMs + 1L
        val retryAt = failureAt + 30_000L
        val failed = record(
            metadataComplete = true,
            lastAutomaticAttemptEpochMs = failureAt,
            nextRetryEpochMs = retryAt,
        ).copy(
            lastSuccessfulRefreshEpochMs = successAt,
            lastRefreshFailureEpochMs = failureAt,
            refreshFailureCount = 1,
        )

        assertFalse(decideOfflineRefresh(failed, retryAt - 1L, manual = false).shouldRefresh)
        assertTrue(decideOfflineRefresh(failed, retryAt, manual = false).shouldRefresh)
    }

    @Test
    fun `partial artwork failure backs off then completes without replacing good artwork`() {
        val now = 5_000_000L
        val poster = artwork(offlinePosterRole, "poster")
        val background = artwork(offlineBackgroundRole, "background")
        val required = mapOf(
            offlinePosterRole to poster.remoteUrl,
            offlineBackgroundRole to background.remoteUrl,
        )
        val partial = finishOfflineArtworkAttempt(
            record = record(metadataComplete = true),
            artwork = mapOf(offlinePosterRole to poster),
            requiredArtwork = required,
            locallyAvailableAssetKeys = setOf(poster.assetKey),
            nowEpochMs = now,
        )

        assertFalse(partial.artworkComplete)
        assertEquals(poster, partial.artwork[offlinePosterRole])
        assertEquals(1, partial.artworkFailureCount)
        assertEquals(now + 30_000L, partial.nextArtworkRetryEpochMs)
        assertFalse(decideOfflineArtworkRefresh(partial, now + 29_999L).shouldRefresh)
        assertTrue(decideOfflineArtworkRefresh(partial, now + 30_000L).shouldRefresh)
        val freshPartial = partial.copy(lastSuccessfulRefreshEpochMs = now)
        val beforeRetry = planOfflineAutomaticRefresh(
            records = listOf(freshPartial),
            activeMetadataKeys = emptySet(),
            activeArtworkKeys = emptySet(),
            nowEpochMs = now + 29_999L,
            maxTitles = 8,
        )
        val atRetry = planOfflineAutomaticRefresh(
            records = listOf(freshPartial),
            activeMetadataKeys = emptySet(),
            activeArtworkKeys = emptySet(),
            nowEpochMs = now + 30_000L,
            maxTitles = 8,
        )
        assertTrue(beforeRetry.metadataKeys.isEmpty())
        assertTrue(beforeRetry.artwork.isEmpty())
        assertTrue(atRetry.metadataKeys.isEmpty())
        assertEquals(listOf(freshPartial.key to freshPartial.generation), atRetry.artwork)

        val completed = finishOfflineArtworkAttempt(
            record = freshPartial,
            artwork = freshPartial.artwork + (offlineBackgroundRole to background),
            requiredArtwork = required,
            locallyAvailableAssetKeys = setOf(poster.assetKey, background.assetKey),
            nowEpochMs = now + 30_000L,
        )

        assertTrue(completed.artworkComplete)
        assertEquals(poster, completed.artwork[offlinePosterRole])
        assertEquals(background, completed.artwork[offlineBackgroundRole])
        assertEquals(0, completed.artworkFailureCount)
        assertEquals(null, completed.nextArtworkRetryEpochMs)
        assertFalse(decideOfflineArtworkRefresh(completed, now + 30_000L).shouldRefresh)
    }

    @Test
    fun `failed atomic artwork replacement preserves target and cleans unique temporary file`() {
        var target = "last-good"
        var temporary: String? = null
        val firstName = offlineArtworkTemporaryName("poster.jpg", "one")
        val secondName = offlineArtworkTemporaryName("poster.jpg", "two")

        val replaced = commitOfflineArtworkReplacement(
            writeTemporary = {
                temporary = "replacement"
                true
            },
            replaceAtomically = { false },
            cleanupTemporary = { temporary = null },
        )

        assertFalse(replaced)
        assertEquals("last-good", target)
        assertEquals(null, temporary)
        assertFalse(firstName == secondName)

        val successful = commitOfflineArtworkReplacement(
            writeTemporary = {
                temporary = "replacement"
                true
            },
            replaceAtomically = {
                target = requireNotNull(temporary)
                true
            },
            cleanupTemporary = { temporary = null },
        )
        assertTrue(successful)
        assertEquals("replacement", target)
        assertEquals(null, temporary)
    }

    @Test
    fun `artwork plan fetches season posters and only downloaded episode thumbnails`() {
        val metadata = OfflineMetaSnapshot(
            id = "tt123",
            type = "series",
            name = "A Show",
            poster = "https://images.test/poster.jpg",
            seasonPosters = mapOf(0 to "https://images.test/specials.jpg", 1 to "https://images.test/s1.jpg"),
            videos = listOf(
                OfflineVideo(
                    id = "e1",
                    title = "One",
                    season = 1,
                    episode = 1,
                    thumbnail = "https://images.test/e1.jpg",
                ),
                OfflineVideo(
                    id = "e2",
                    title = "Two",
                    season = 1,
                    episode = 2,
                    thumbnail = "https://images.test/e2.jpg",
                ),
            ),
        )
        val plan = requiredOfflineArtwork(
            metadata = metadata,
            downloads = listOf(download(id = "d1", season = 1, episode = 1, title = "One")),
        )

        assertTrue(offlinePosterRole in plan)
        assertTrue(offlineSeasonPosterRole(0) in plan)
        assertTrue(offlineSeasonPosterRole(1) in plan)
        assertTrue(offlineEpisodeThumbnailRole(1, 1) in plan)
        assertFalse(offlineEpisodeThumbnailRole(1, 2) in plan)
    }

    @Test
    fun `record codec keeps ownership refresh and download references`() {
        val record = record(
            metadataComplete = true,
            lastAutomaticAttemptEpochMs = 123L,
        ).copy(
            providerAddonIds = setOf("provider.one"),
            downloadIds = setOf("d1", "d2"),
            artwork = mapOf(
                offlinePosterRole to OfflineArtworkRef(
                    role = offlinePosterRole,
                    remoteUrl = "https://images.test/poster.jpg",
                    assetKey = "poster.jpg",
                    etag = "etag",
                    updatedAtEpochMs = 456L,
                ),
            ),
            lastArtworkAttemptEpochMs = 400L,
            lastArtworkFailureEpochMs = 410L,
            artworkFailureCount = 2,
            nextArtworkRetryEpochMs = 470L,
        )

        assertEquals(record, OfflineTitleRecordCodec.decode(OfflineTitleRecordCodec.encode(record)))
    }

    @Test
    fun `reconciliation groups repeated downloads into one idempotent title record`() {
        val downloads = listOf(
            download(id = "d1", season = 1, episode = 1, title = "One"),
            download(id = "d2", season = 2, episode = 3, title = "Three"),
        )
        val first = reconcileOfflineRecords(
            existingRecords = emptyList(),
            ownerProfileKey = "profile:1",
            downloads = downloads,
            localeTag = "de-DE",
            nowEpochMs = 100L,
        )
        val record = first.recordsToUpsert.single()
        val repeated = reconcileOfflineRecords(
            existingRecords = listOf(record),
            ownerProfileKey = "profile:1",
            downloads = downloads,
            localeTag = "de-DE",
            nowEpochMs = 200L,
        )

        assertEquals(setOf("d1", "d2"), record.downloadIds)
        assertEquals(setOf("provider.one"), record.providerAddonIds)
        assertEquals("de-DE", record.localeTag)
        assertEquals(2, record.metadata.videos.size)
        assertTrue(repeated.recordsToUpsert.isEmpty())
        assertTrue(repeated.keysToDelete.isEmpty())
    }

    @Test
    fun `one episode deletion prunes only its thumbnail and last deletion removes the title`() {
        val d1 = download(id = "d1", season = 1, episode = 1, title = "One")
        val d2 = download(id = "d2", season = 1, episode = 2, title = "Two")
        val poster = artwork(offlinePosterRole, "poster")
        val firstThumb = artwork(offlineEpisodeThumbnailRole(1, 1), "one")
        val secondThumb = artwork(offlineEpisodeThumbnailRole(1, 2), "two")
        val existing = record(metadataComplete = true).copy(
            downloadIds = setOf("d1", "d2"),
            metadata = buildMinimalOfflineMetadata(listOf(d1, d2)).copy(
                poster = "https://images.test/poster.jpg",
            ),
            artwork = mapOf(
                poster.role to poster,
                firstThumb.role to firstThumb,
                secondThumb.role to secondThumb,
            ),
            artworkComplete = true,
        )

        val oneRemoved = reconcileOfflineRecords(
            existingRecords = listOf(existing),
            ownerProfileKey = "profile:1",
            downloads = listOf(d2),
            localeTag = "en-US",
            nowEpochMs = 20L,
        )
        val retained = oneRemoved.recordsToUpsert.single()

        assertEquals(setOf("d2"), retained.downloadIds)
        assertEquals(existing.generation + 1L, retained.generation)
        assertTrue(offlinePosterRole in retained.artwork)
        assertTrue(offlineEpisodeThumbnailRole(1, 2) in retained.artwork)
        assertFalse(offlineEpisodeThumbnailRole(1, 1) in retained.artwork)
        assertEquals(listOf(firstThumb), oneRemoved.artworkToDelete)

        val lastRemoved = reconcileOfflineRecords(
            existingRecords = listOf(retained),
            ownerProfileKey = "profile:1",
            downloads = emptyList(),
            localeTag = "en-US",
            nowEpochMs = 30L,
        )
        assertEquals(setOf(existing.key), lastRemoved.keysToDelete)
        assertEquals(retained.artwork.values.toSet(), lastRemoved.artworkToDelete.toSet())
    }

    @Test
    fun `multiple movie variants share title data`() {
        val variants = listOf(
            download(id = "movie-720", season = null, episode = null, title = "Movie"),
            download(id = "movie-4k", season = null, episode = null, title = "Movie"),
        )
        val result = reconcileOfflineRecords(
            existingRecords = emptyList(),
            ownerProfileKey = "profile:1",
            downloads = variants,
            localeTag = "en-US",
            nowEpochMs = 10L,
        )

        assertEquals(1, result.recordsToUpsert.size)
        assertEquals(setOf("movie-720", "movie-4k"), result.recordsToUpsert.single().downloadIds)
        assertTrue(result.recordsToUpsert.single().metadata.videos.isEmpty())
    }

    @Test
    fun `offline details use the downloaded parent identity while retaining provider ids`() {
        val title = OfflineTitle(
            record = record(metadataComplete = true).copy(
                metaId = "download-parent-id",
                providerMetaIds = setOf("provider-meta-id"),
                metadata = OfflineMetaSnapshot(
                    id = "provider-meta-id",
                    type = "tv",
                    name = "A Show",
                ),
            ),
            downloads = emptyList(),
        )

        val details = title.toMetaDetails()

        assertEquals("download-parent-id", details.id)
        assertEquals("series", details.type)
        assertEquals(setOf("provider-meta-id"), title.record.providerMetaIds)
    }

    @Test
    fun `refresh result is rejected after profile switch deletion or generation change`() {
        val current = record(metadataComplete = true).copy(downloadIds = setOf("d1"), generation = 7L)

        assertTrue(
            canApplyOfflineRefresh(current, "profile:1", 7L, setOf("d1"), "profile:1"),
        )
        assertFalse(
            canApplyOfflineRefresh(current, "profile:1", 7L, setOf("d1"), "profile:2"),
        )
        assertFalse(
            canApplyOfflineRefresh(current.copy(generation = 8L), "profile:1", 7L, setOf("d1"), "profile:1"),
        )
        assertFalse(
            canApplyOfflineRefresh(null, "profile:1", 7L, setOf("d1"), "profile:1"),
        )
    }

    @Test
    fun `artwork validation rejects oversized and non-image responses`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)

        assertTrue(
            validOfflineArtwork(
                OfflineArtworkResponse(statusCode = 200, bytes = jpeg, contentType = "image/jpeg"),
                maxBytes = jpeg.size,
            ),
        )
        assertFalse(
            validOfflineArtwork(
                OfflineArtworkResponse(statusCode = 200, bytes = jpeg, contentType = "text/html"),
                maxBytes = jpeg.size,
            ),
        )
        assertFalse(
            validOfflineArtwork(
                OfflineArtworkResponse(statusCode = 200, bytes = jpeg, contentType = "image/jpeg"),
                maxBytes = jpeg.size - 1,
            ),
        )
        assertFalse(validOfflineArtwork(OfflineArtworkResponse(statusCode = 304), maxBytes = 10))
    }

    @Test
    fun `canonical type aliases cannot split a show record`() {
        assertEquals("series", canonicalOfflineMetaType("show"))
        assertEquals("series", canonicalOfflineMetaType("TV"))
        assertEquals(
            offlineTitleKey("profile:1", "series", "tt123"),
            offlineTitleKey("profile:1", canonicalOfflineMetaType("tvshow"), "tt123"),
        )
    }

    private fun record(
        metadataComplete: Boolean,
        lastAutomaticAttemptEpochMs: Long? = null,
        nextRetryEpochMs: Long? = null,
    ) = OfflineTitleRecord(
        key = "profile:1|series|tt123",
        ownerProfileKey = "profile:1",
        metaType = "series",
        metaId = "tt123",
        metadata = OfflineMetaSnapshot(id = "tt123", type = "series", name = "A Show"),
        metadataComplete = metadataComplete,
        lastAutomaticAttemptEpochMs = lastAutomaticAttemptEpochMs,
        nextRetryEpochMs = nextRetryEpochMs,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    private fun download(
        id: String,
        season: Int?,
        episode: Int?,
        title: String,
    ) = DownloadItem(
        id = id,
        contentType = if (season != null && episode != null) "series" else "movie",
        parentMetaId = "tt123",
        parentMetaType = if (season != null && episode != null) "series" else "movie",
        videoId = if (season != null && episode != null) "tt123:$season:$episode" else id,
        title = "A Show",
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = title.takeIf { season != null && episode != null },
        episodeThumbnail = if (season != null && episode != null) {
            "https://images.test/$season-$episode.jpg"
        } else {
            null
        },
        streamTitle = "Source",
        providerName = "Provider",
        providerAddonId = "provider.one",
        sourceUrl = "https://video.test/$season-$episode.mp4",
        fileName = "$id.mp4",
        status = DownloadStatus.Completed,
        localFileUri = "file:///downloads/$id.mp4",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
    )

    private fun artwork(role: String, name: String) = OfflineArtworkRef(
        role = role,
        remoteUrl = when {
            role == offlinePosterRole -> "https://images.test/poster.jpg"
            role == offlineBackgroundRole -> "https://images.test/background.jpg"
            name == "one" -> "https://images.test/1-1.jpg"
            else -> "https://images.test/1-2.jpg"
        },
        assetKey = "$name.jpg",
        updatedAtEpochMs = 1L,
    )
}
