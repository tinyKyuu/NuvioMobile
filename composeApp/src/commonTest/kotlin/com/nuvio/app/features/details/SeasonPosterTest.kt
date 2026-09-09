package com.nuvio.app.features.details

import com.nuvio.app.features.details.components.resolveSeasonPoster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeasonPosterTest {
    @Test
    fun mapsOrdinarySeasonsInSortedOrderDespiteDuplicateVideos() {
        assertEquals(mapOf(1 to "one", 2 to "two"), parse(listOf(2, 1, 2), """["one","two"]""").seasonPosters)
    }

    @Test
    fun mapsGapsToObservedSeasonsWhenLengthsMatch() {
        assertEquals(mapOf(1 to "one", 3 to "three"), parse(listOf(3, 1), """["one","three"]""").seasonPosters)
        assertEquals(mapOf(1 to "one", 3 to "three"), parse(listOf(0, 3, 1), """["one","three"]""").seasonPosters)
    }

    @Test
    fun incompleteVideoListFallsBackToOneBasedIndicesWhenLengthsDiffer() {
        assertEquals(mapOf(1 to "one", 2 to "two", 3 to "three"), parse(listOf(3), """["one","two","three"]""").seasonPosters)
    }

    @Test
    fun equalLengthIncompleteVideoListUsesObservedSeasonAsUpstreamDoes() {
        // The array does not identify its seasons. A single observed season wins
        // even if the addon intended its sole poster to represent season one.
        assertEquals(mapOf(3 to "only"), parse(listOf(3), """["only"]""").seasonPosters)
    }

    @Test
    fun nullAndBlankPosterSlotsDoNotShiftLaterSeasons() {
        assertEquals(mapOf(3 to "three"), parse(listOf(0, 1, 2, 3), """[null,"","  "," three "]""").seasonPosters)
        assertEquals(mapOf(1 to "one"), parse(listOf(0, 1), """[null,"one"]""").seasonPosters)
    }

    @Test
    fun absentEmptyNullOrMalformedPosterContainerIsEmpty() {
        for (posters in listOf(null, "[]", "null", "{}", "true", "\"poster\"")) {
            assertEquals(emptyMap(), parse(listOf(0, 1), posters).seasonPosters, posters)
        }
        assertEquals(emptyMap(), MetaDetailsParser.parse("""{"id":"show","type":"series","name":"Show"}""").seasonPosters)
    }

    @Test
    fun objectAndArrayPosterSlotsAreIgnoredWithoutShifting() {
        assertEquals(mapOf(3 to "three"), parse(emptyList(), """[{},[],"three"]""").seasonPosters)
    }

    @Test
    fun missingOrNegativeVideoSeasonDoesNotIdentifySpecialsArtwork() {
        assertEquals(mapOf(1 to "one"), parse(listOf(null, -1), """["one"]""").seasonPosters)
    }

    @Test
    fun nonblankEpisodePosterPrecedesAddonAndSkipsBlankEpisodes() {
        val meta = parse(listOf(0, 1), """["addon-special","addon-one"]""")
        for (season in listOf(0, 1)) {
            val grouped = mapOf(season to listOf(video(season, null), video(season, " "), video(season, "episode")))
            assertEquals("episode", resolveSeasonPoster(season, grouped, meta))
        }
    }

    @Test
    fun addonPosterUsedWhenEpisodeArtworkIsAbsentOrBlank() {
        val meta = parse(listOf(0, 1), """["addon-special","addon-one"]""")
        assertEquals("addon-special", resolveSeasonPoster(0, mapOf(0 to listOf(video(0, ""))), meta))
        assertEquals("addon-one", resolveSeasonPoster(1, emptyMap(), meta))
    }

    @Test
    fun noSeasonArtworkReturnsNullSoUiCanUseOverallFallback() {
        val meta = MetaDetails(id = "show", type = "series", name = "Show", poster = "overall", background = "background", seasonPosters = mapOf(1 to " "))
        assertNull(resolveSeasonPoster(1, mapOf(1 to listOf(video(1, " "))), meta))
        assertNull(resolveSeasonPoster(0, emptyMap(), meta))
    }

    @Test
    fun seasonSwitchingDoesNotLeakArtworkFromAnotherSeason() {
        val meta = parse(listOf(0, 1, 3), """["special","one","three"]""")
        val grouped = mapOf(1 to listOf(video(1, "episode-one")))
        assertEquals("episode-one", resolveSeasonPoster(1, grouped, meta))
        assertEquals("three", resolveSeasonPoster(3, grouped, meta))
        assertEquals("special", resolveSeasonPoster(0, grouped, meta))
        assertEquals("episode-one", resolveSeasonPoster(1, grouped, meta))
    }

    private fun video(season: Int, poster: String?) =
        MetaVideo(id = "show:$season:1", title = "Episode", season = season, episode = 1, seasonPoster = poster)

    private fun parse(seasons: List<Int?>, posters: String?): MetaDetails {
        val videos = seasons.mapIndexed { index, season ->
            """{"id":"video-$index","title":"Episode","season":$season,"episode":1}"""
        }.joinToString(",")
        val extras = posters?.let { """"seasonPosters":$it""" }.orEmpty()
        return MetaDetailsParser.parse("""{"meta":{"id":"show","type":"series","name":"Show","videos":[$videos],"app_extras":{$extras}}}""")
    }
}
