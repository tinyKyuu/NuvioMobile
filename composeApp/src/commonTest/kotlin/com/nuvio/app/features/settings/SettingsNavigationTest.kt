package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsNavigationTest {
    @Test
    fun `nested discovery and integration pages return to their immediate parent`() {
        assertEquals(SettingsPage.ContentDiscovery, SettingsPage.Addons.previousPage())
        assertEquals(SettingsPage.ContentDiscovery, SettingsPage.Plugins.previousPage())
        assertEquals(SettingsPage.Integrations, SettingsPage.TmdbEnrichment.previousPage())
        assertEquals(SettingsPage.Integrations, SettingsPage.MdbListRatings.previousPage())
        assertEquals(SettingsPage.Integrations, SettingsPage.Debrid.previousPage())
    }

    @Test
    fun `top level settings pages return to the settings root`() {
        assertEquals(SettingsPage.Root, SettingsPage.Account.previousPage())
        assertEquals(SettingsPage.Root, SettingsPage.ContentDiscovery.previousPage())
        assertEquals(SettingsPage.Root, SettingsPage.Integrations.previousPage())
        assertNull(SettingsPage.Root.previousPage())
    }
}
