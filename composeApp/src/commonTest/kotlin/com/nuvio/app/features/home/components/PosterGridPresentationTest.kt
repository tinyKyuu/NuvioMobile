package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PosterGridPresentationTest {
    @Test
    fun `library grid metadata combines localized type and release year`() {
        assertEquals("Movie: 2024", posterGridMediaDetail("movie", "2024", "Movie", "Series"))
        assertEquals("Series: 2019", posterGridMediaDetail("series", "2019", "Movie", "Series"))
        assertEquals("Movie", posterGridMediaDetail("movie", null, "Movie", "Series"))
        assertNull(posterGridMediaDetail("other", null, "Movie", "Series"))
    }
}
