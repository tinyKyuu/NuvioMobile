package com.nuvio.app.features.downloads

import com.nuvio.app.features.home.resolveHomeContinueWatchingForOffline
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ContinueWatchingArtworkResolutionTest {
    @Test
    fun `stable identity recovers current local artwork across aliases`() {
        val title = offlineTitle()

        val resolved = listOf(title).resolveContinueWatchingArtwork(
            profileId = 1,
            parentMetaId = "canonical-show-id",
            parentMetaType = "tv",
            mediaTitle = "Display Alias",
            seasonNumber = 1,
            episodeNumber = 4,
            resolveLocalArtwork = { assetKey -> "file:///current-container/$assetKey" },
        )

        assertEquals(
            "file:///current-container/poster.asset",
            resolved?.local?.poster,
        )
        assertEquals(
            "file:///current-container/episode.asset",
            resolved?.local?.episodeThumbnail,
        )
        assertEquals("https://images.test/poster.jpg", resolved?.remote?.poster)
        assertEquals("https://images.test/episode.jpg", resolved?.remote?.episodeThumbnail)
    }

    @Test
    fun `missing local asset keeps remote recovery available`() {
        val resolved = listOf(offlineTitle()).resolveContinueWatchingArtwork(
            profileId = 1,
            parentMetaId = "provider-show-id",
            parentMetaType = "series",
            mediaTitle = "Canonical Show",
            seasonNumber = 1,
            episodeNumber = 4,
            resolveLocalArtwork = { null },
        )

        assertNull(resolved?.local?.poster)
        assertNull(resolved?.local?.episodeThumbnail)
        assertEquals("https://images.test/poster.jpg", resolved?.remote?.poster)
        assertEquals("https://images.test/episode.jpg", resolved?.remote?.episodeThumbnail)
    }

    @Test
    fun `profile title and exact episode must all match`() {
        val titles = listOf(offlineTitle())
        val localResolver: (String) -> String? = { "file:///current-container/$it" }

        assertNull(
            titles.resolveContinueWatchingArtwork(
                profileId = 2,
                parentMetaId = "provider-show-id",
                parentMetaType = "series",
                mediaTitle = "Canonical Show",
                seasonNumber = 1,
                episodeNumber = 4,
                resolveLocalArtwork = localResolver,
            ),
        )
        assertNull(
            titles.resolveContinueWatchingArtwork(
                profileId = 1,
                parentMetaId = "provider-show-id",
                parentMetaType = "series",
                mediaTitle = "Different Show",
                seasonNumber = 1,
                episodeNumber = 4,
                resolveLocalArtwork = localResolver,
            ),
        )
        assertNull(
            titles.resolveContinueWatchingArtwork(
                profileId = 1,
                parentMetaId = "provider-show-id",
                parentMetaType = "series",
                mediaTitle = "Canonical Show",
                seasonNumber = 1,
                episodeNumber = 5,
                resolveLocalArtwork = localResolver,
            ),
        )
    }

    @Test
    fun `offline home keeps a playable provider alias and uses local artwork only`() {
        val title = offlineTitle()
        val item = ContinueWatchingItem(
            parentMetaId = "canonical-show-id",
            parentMetaType = "tv",
            videoId = "canonical-show-id:1:4",
            title = "Display Alias",
            subtitle = "S1 E4",
            imageUrl = "https://images.test/remote-card.jpg",
            poster = "https://images.test/remote-poster.jpg",
            seasonNumber = 1,
            episodeNumber = 4,
            episodeTitle = "Episode",
            episodeThumbnail = "https://images.test/remote-episode.jpg",
            resumePositionMs = 120_000L,
            durationMs = 1_000_000L,
            progressFraction = 0.12f,
        )

        val resolved = resolveHomeContinueWatchingForOffline(
            items = listOf(item),
            downloads = title.downloads,
            offlineTitles = listOf(title),
            profileId = 1,
        ).single()

        assertNull(resolved.imageUrl)
        assertNull(resolved.poster)
        assertNull(resolved.episodeThumbnail)
        assertNotNull(resolved.localArtwork)
        assertEquals(120_000L, resolved.resumePositionMs)
        assertEquals(0.12f, resolved.progressFraction)
    }

    private fun offlineTitle(): OfflineTitle {
        val episodeRole = offlineEpisodeThumbnailRole(1, 4)
        val download = DownloadItem(
            id = "download-1",
            contentType = "series",
            parentMetaId = "provider-show-id",
            parentMetaType = "series",
            videoId = "provider-specific-video-id",
            title = "Display Alias",
            seasonNumber = 1,
            episodeNumber = 4,
            episodeTitle = "Episode",
            streamTitle = "Fixture",
            providerName = "Fixture",
            sourceUrl = "https://video.test/episode.mp4",
            localFileUri = "file:///downloads/episode.mp4",
            fileName = "episode.mp4",
            status = DownloadStatus.Completed,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
        )
        return OfflineTitle(
            record = OfflineTitleRecord(
                key = "profile:1|series|provider-show-id",
                ownerProfileKey = "profile:1",
                metaType = "series",
                metaId = "provider-show-id",
                providerMetaIds = setOf("canonical-show-id"),
                downloadIds = setOf(download.id),
                metadata = OfflineMetaSnapshot(
                    id = "metadata-show-id",
                    type = "series",
                    name = "Canonical Show",
                    poster = "https://images.test/poster-fallback.jpg",
                    videos = listOf(
                        OfflineVideo(
                            id = "metadata-video-id",
                            title = "Episode",
                            thumbnail = "https://images.test/episode-fallback.jpg",
                            season = 1,
                            episode = 4,
                        ),
                    ),
                ),
                artwork = mapOf(
                    offlinePosterRole to artwork(
                        role = offlinePosterRole,
                        remoteUrl = "https://images.test/poster.jpg",
                        assetKey = "poster.asset",
                    ),
                    episodeRole to artwork(
                        role = episodeRole,
                        remoteUrl = "https://images.test/episode.jpg",
                        assetKey = "episode.asset",
                    ),
                ),
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 2L,
            ),
            downloads = listOf(download),
        )
    }

    private fun artwork(
        role: String,
        remoteUrl: String,
        assetKey: String,
    ): OfflineArtworkRef = OfflineArtworkRef(
        role = role,
        remoteUrl = remoteUrl,
        assetKey = assetKey,
        updatedAtEpochMs = 1L,
    )
}
