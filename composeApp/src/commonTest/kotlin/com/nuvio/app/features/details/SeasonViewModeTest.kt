package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeasonViewModeTest {
    @Test
    fun `missing saved mode defaults to text`() {
        assertEquals(SeasonViewMode.Text, seasonViewModeOrDefault(null))
    }

    @Test
    fun `explicit posters mode remains posters`() {
        assertEquals(
            SeasonViewMode.Posters,
            seasonViewModeOrDefault(SeasonViewMode.parse("posters")),
        )
    }

    @Test
    fun `selected season heading is kept only for one season`() {
        assertTrue(shouldShowSelectedSeasonHeading(seasonCount = 1))
        assertFalse(shouldShowSelectedSeasonHeading(seasonCount = 2))
        assertFalse(shouldShowSelectedSeasonHeading(seasonCount = 21))
    }
}
