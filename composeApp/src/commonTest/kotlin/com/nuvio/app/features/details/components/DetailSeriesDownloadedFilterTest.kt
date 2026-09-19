package com.nuvio.app.features.details.components

import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailSeriesDownloadedFilterTest {
    @Test
    fun `downloaded filter drops unavailable episodes and empty seasons`() {
        val grouped = mapOf(
            1 to listOf(episode(1, 1), episode(1, 2)),
            2 to listOf(episode(2, 1)),
            3 to listOf(episode(3, 1), episode(3, 2)),
        )

        val filtered = downloadedEpisodeGroups(
            groupedEpisodes = grouped,
            downloadedEpisodeKeys = setOf(1 to 2, 3 to 1),
        )

        assertEquals(listOf(1, 3), filtered.keys.toList())
        assertEquals(listOf(2), filtered.getValue(1).map(MetaVideo::episode))
        assertEquals(listOf(1), filtered.getValue(3).map(MetaVideo::episode))
    }

    @Test
    fun `downloaded-only control needs offline mode plus a useful reduction`() {
        val all = mapOf(1 to listOf(episode(1, 1), episode(1, 2)))
        val partial = mapOf(1 to listOf(episode(1, 1)))

        assertTrue(shouldOfferDownloadedOnlyFilter(true, all, partial))
        assertFalse(shouldOfferDownloadedOnlyFilter(false, all, partial))
        assertFalse(shouldOfferDownloadedOnlyFilter(true, all, emptyMap()))
        assertFalse(shouldOfferDownloadedOnlyFilter(true, all, all))
    }

    @Test
    fun `season animation content keeps its episodes when the filtered map changes`() {
        val allEpisodes = mapOf(
            1 to listOf(episode(1, 1)),
            2 to listOf(episode(2, 1), episode(2, 2)),
        )
        val outgoing = seasonEpisodeContent(season = 2, groupedEpisodes = allEpisodes)

        val downloadedEpisodes = mapOf(1 to listOf(episode(1, 1)))
        val incoming = seasonEpisodeContent(season = 1, groupedEpisodes = downloadedEpisodes)

        assertEquals(2, outgoing.season)
        assertEquals(listOf(1, 2), outgoing.episodes.map(MetaVideo::episode))
        assertEquals(1, incoming.season)
        assertEquals(listOf(1), incoming.episodes.map(MetaVideo::episode))
    }

    @Test
    fun `season selector stays visible when filtering leaves one of several seasons`() {
        assertTrue(shouldKeepSeasonSelector(allSeasonCount = 4))
        assertFalse(shouldKeepSeasonSelector(allSeasonCount = 1))
    }

    @Test
    fun `season label padding aligns the first chip text with its heading`() {
        assertEquals(20.dp, seasonSelectorStartPadding(40.dp, 20.dp))
        assertEquals(0.dp, seasonSelectorStartPadding(16.dp, 20.dp))
    }

    private fun episode(season: Int, episode: Int) = MetaVideo(
        id = "s${season}e$episode",
        title = "Episode $episode",
        season = season,
        episode = episode,
    )
}
