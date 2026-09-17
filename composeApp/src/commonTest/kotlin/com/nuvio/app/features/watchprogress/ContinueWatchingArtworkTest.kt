package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinueWatchingArtworkTest {
    @Test
    fun `stale saved local path is replaced by current identity checked local asset`() {
        val item = item().copy(
            imageUrl = "file:///stale-container/episode.jpg",
            poster = "file:///stale-container/poster.jpg",
            episodeThumbnail = "file:///stale-container/episode.jpg",
        )
        val resolved = item.withResolvedArtwork(
            resolution = resolution(),
            allowRemote = true,
        )

        assertNull(resolved.imageUrl)
        assertEquals("https://images.test/poster.jpg", resolved.poster)
        assertEquals("https://images.test/episode.jpg", resolved.episodeThumbnail)
        assertEquals("file:///current-container/poster.jpg", resolved.localArtwork?.poster)
        assertEquals(
            "file:///current-container/episode.jpg",
            resolved.localArtwork?.episodeThumbnail,
        )
    }

    @Test
    fun `episode thumbnail style tries current local then remote for each preferred role`() {
        val candidates = item()
            .withResolvedArtwork(resolution(), allowRemote = true)
            .artworkCandidates(
                presentation = ContinueWatchingArtworkPresentation.Default,
                useEpisodeThumbnails = true,
            )

        assertEquals(
            listOf(
                "file:///current-container/episode.jpg",
                "https://images.test/episode.jpg",
                "file:///current-container/poster.jpg",
                "https://images.test/poster.jpg",
                "file:///current-container/background.jpg",
                "https://images.test/background.jpg",
            ),
            candidates.map(ContinueWatchingArtworkCandidate::url),
        )
        assertEquals(
            listOf(
                ContinueWatchingArtworkSourceCategory.CurrentLocal,
                ContinueWatchingArtworkSourceCategory.Remote,
            ),
            candidates.take(2).map(ContinueWatchingArtworkCandidate::sourceCategory),
        )
    }

    @Test
    fun `poster style preserves poster preference and deduplicates episode image alias`() {
        val candidates = item()
            .withResolvedArtwork(resolution(), allowRemote = true)
            .copy(imageUrl = "https://images.test/episode.jpg")
            .artworkCandidates(
                presentation = ContinueWatchingArtworkPresentation.Poster,
                useEpisodeThumbnails = true,
            )

        assertEquals(
            listOf(
                ContinueWatchingArtworkRole.Poster,
                ContinueWatchingArtworkRole.Poster,
                ContinueWatchingArtworkRole.Background,
                ContinueWatchingArtworkRole.Background,
                ContinueWatchingArtworkRole.EpisodeThumbnail,
                ContinueWatchingArtworkRole.EpisodeThumbnail,
            ),
            candidates.map(ContinueWatchingArtworkCandidate::role),
        )
        assertEquals(candidates.size, candidates.map(ContinueWatchingArtworkCandidate::url).toSet().size)
    }

    @Test
    fun `offline mode never exposes remote artwork and total local failure uses placeholder`() {
        val resolved = item().withResolvedArtwork(
            resolution = ContinueWatchingArtworkResolution(
                remote = resolution().remote,
            ),
            allowRemote = false,
        )

        assertTrue(
            resolved.artworkCandidates(
                presentation = ContinueWatchingArtworkPresentation.LandscapeCard,
                useEpisodeThumbnails = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `retry traversal is bounded and never loops`() {
        val visited = mutableListOf(0)
        var index = 0
        while (true) {
            val next = nextContinueWatchingArtworkCandidateIndex(index, candidateCount = 3) ?: break
            visited += next
            index = next
        }

        assertEquals(listOf(0, 1, 2), visited)
        assertNull(nextContinueWatchingArtworkCandidateIndex(currentIndex = 2, candidateCount = 3))
        assertNull(nextContinueWatchingArtworkCandidateIndex(currentIndex = 0, candidateCount = 0))
    }

    @Test
    fun `persisted progress strips local paths without changing playback history`() {
        val previous = progress().copy(
            poster = "https://images.test/previous-poster.jpg",
            background = "https://images.test/previous-background.jpg",
        )
        val candidate = progress().copy(
            logo = "file:///container/logo.png",
            poster = "file:///container/poster.jpg",
            background = "https://images.test/new-background.jpg",
            episodeThumbnail = "content://artwork/episode",
        )

        val sanitized = candidate.withDurableArtwork(
            resolution = resolution(),
            previous = previous,
        )

        assertEquals("https://images.test/logo.png", sanitized.logo)
        assertEquals("https://images.test/poster.jpg", sanitized.poster)
        assertEquals("https://images.test/new-background.jpg", sanitized.background)
        assertEquals("https://images.test/episode.jpg", sanitized.episodeThumbnail)
        assertEquals(candidate.lastPositionMs, sanitized.lastPositionMs)
        assertEquals(candidate.durationMs, sanitized.durationMs)
        assertEquals(candidate.lastUpdatedEpochMs, sanitized.lastUpdatedEpochMs)
        assertEquals(candidate.progressKey, sanitized.progressKey)
    }

    private fun resolution() = ContinueWatchingArtworkResolution(
        local = ContinueWatchingArtworkSet(
            poster = "file:///current-container/poster.jpg",
            background = "file:///current-container/background.jpg",
            episodeThumbnail = "file:///current-container/episode.jpg",
        ),
        remote = ContinueWatchingArtworkSet(
            logo = "https://images.test/logo.png",
            poster = "https://images.test/poster.jpg",
            background = "https://images.test/background.jpg",
            episodeThumbnail = "https://images.test/episode.jpg",
        ),
    )

    private fun item() = ContinueWatchingItem(
        parentMetaId = "show-id",
        parentMetaType = "series",
        videoId = "show-id:1:4",
        title = "Show",
        subtitle = "S1E4",
        imageUrl = null,
        seasonNumber = 1,
        episodeNumber = 4,
        episodeTitle = "Episode",
        isNextUp = false,
        resumePositionMs = 120_000L,
        durationMs = 1_000_000L,
        progressFraction = 0.12f,
    )

    private fun progress() = WatchProgressEntry(
        contentType = "series",
        parentMetaId = "show-id",
        parentMetaType = "series",
        videoId = "show-id:1:4",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = 4,
        episodeTitle = "Episode",
        lastPositionMs = 120_000L,
        durationMs = 1_000_000L,
        lastUpdatedEpochMs = 500L,
        progressKey = "stable-progress-key",
    )
}
