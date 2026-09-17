package com.nuvio.app.features.downloads

import com.nuvio.app.navigation.DownloadActivityRoute
import com.nuvio.app.navigation.DownloadsSettingsRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadNavigationDecisionTest {
    @Test
    fun activityRouteStaysOnTheCallingTabWhilePolicyBelongsToSettings() {
        assertNull(DownloadActivityRoute().preferredTabName)
        assertEquals("Settings", DownloadsSettingsRoute().preferredTabName)
    }

    @Test
    fun `settings defaults to policy while non-settings callers default to activity`() {
        assertEquals(
            DownloadNavigationTarget.Policy,
            resolveDownloadNavigationTarget(DownloadEntrySource.Settings),
        )
        assertEquals(
            DownloadNavigationTarget.Activity,
            resolveDownloadNavigationTarget(DownloadEntrySource.Library),
        )
        assertEquals(
            DownloadNavigationTarget.Activity,
            resolveDownloadNavigationTarget(DownloadEntrySource.Player),
        )
        assertEquals(
            DownloadNavigationTarget.Activity,
            resolveDownloadNavigationTarget(DownloadEntrySource.DeepLink),
        )
    }

    @Test
    fun `legacy completed routes redirect to the Library downloads catalog`() {
        assertEquals(
            DownloadNavigationTarget.CompletedLibrary,
            resolveDownloadNavigationTarget(DownloadEntrySource.LegacyShowRoute),
        )
        assertEquals(
            DownloadNavigationTarget.CompletedLibrary,
            resolveDownloadNavigationTarget(
                source = DownloadEntrySource.Settings,
                explicitDestination = "legacy",
            ),
        )
    }

    @Test
    fun `explicit destination wins over the caller default`() {
        assertEquals(
            DownloadNavigationTarget.Policy,
            resolveDownloadNavigationTarget(
                source = DownloadEntrySource.Player,
                explicitDestination = "policy",
            ),
        )
        assertEquals(
            DownloadNavigationTarget.Activity,
            resolveDownloadNavigationTarget(
                source = DownloadEntrySource.Settings,
                explicitDestination = "activity",
            ),
        )
    }

    @Test
    fun `activity Back exits selection before returning to its calling Library tab`() {
        assertEquals(
            DownloadsBackAction.ExitSelection,
            resolveDownloadsBackAction(
                mode = DownloadsScreenMode.Activity,
                selectionMode = true,
                isShowingCompletedSeries = false,
            ),
        )
        assertEquals(
            DownloadsBackAction.NavigateBack,
            resolveDownloadsBackAction(
                mode = DownloadsScreenMode.Activity,
                selectionMode = false,
                isShowingCompletedSeries = false,
            ),
        )
        assertNull(DownloadActivityRoute().preferredTabName)
    }

    @Test
    fun `policy Back never exposes a selection state`() {
        assertEquals(
            DownloadsBackAction.NavigateBack,
            resolveDownloadsBackAction(
                mode = DownloadsScreenMode.Policy,
                selectionMode = true,
                isShowingCompletedSeries = false,
            ),
        )
    }
}
