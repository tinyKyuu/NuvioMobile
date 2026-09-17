package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonsScreenTest {
    @Test
    fun `addon card refresh force reloads only its selected manifest`() {
        val refreshed = mutableListOf<Pair<String, Boolean>>()

        refreshAddonFromSettings("https://selected.example/manifest.json") { manifestUrl, forceRefresh ->
            refreshed += manifestUrl to forceRefresh
        }

        assertEquals(
            listOf("https://selected.example/manifest.json" to true),
            refreshed,
        )
    }
}
