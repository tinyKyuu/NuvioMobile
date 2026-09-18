package com.nuvio.app.features.details.components

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

    private fun episode(season: Int, episode: Int) = MetaVideo(
        id = "s${season}e$episode",
        title = "Episode $episode",
        season = season,
        episode = episode,
    )
}
