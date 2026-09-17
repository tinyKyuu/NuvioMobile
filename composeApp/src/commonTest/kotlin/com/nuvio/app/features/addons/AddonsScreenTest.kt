package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonsScreenTest {
    @Test
    fun `addon card refresh targets only its selected manifest`() {
        val refreshed = mutableListOf<String>()

        refreshAddonFromSettings("https://selected.example/manifest.json", refreshed::add)

        assertEquals(listOf("https://selected.example/manifest.json"), refreshed)
    }
}
