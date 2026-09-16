package com.nuvio.app

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.features.home.shouldShowOfflineHomeConnectionCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTabsDestinationTest {

    @Test
    fun `offline status appears on root routes for connection failures`() {
        assertTrue(shouldShowRootOfflineStatus(true, NetworkCondition.NoInternet))
        assertTrue(shouldShowRootOfflineStatus(true, NetworkCondition.ServersUnreachable))
    }

    @Test
    fun `offline status stays hidden outside root routes and while checking`() {
        assertFalse(shouldShowRootOfflineStatus(false, NetworkCondition.NoInternet))
        assertFalse(shouldShowRootOfflineStatus(true, NetworkCondition.Online))
        assertFalse(shouldShowRootOfflineStatus(true, NetworkCondition.Checking))
    }

    @Test
    fun `offline phone with playable local content keeps compact retry reachable`() {
        assertFalse(
            shouldShowOfflineHomeConnectionCard(
                isOfflineLike = true,
                hasPlayableDownloads = true,
                hasContinueWatchingRows = false,
            ),
        )
        assertEquals(
            RootOfflineStatusPresentation.CompactIcon,
            rootOfflineStatusPresentation(
                rootRouteActive = true,
                condition = NetworkCondition.NoInternet,
                isTabletLayout = false,
                showRetryLabel = false,
            ),
        )
    }

    @Test
    fun `wide tablet keeps labeled retry pill on root routes`() {
        assertEquals(
            RootOfflineStatusPresentation.RetryPill,
            rootOfflineStatusPresentation(
                rootRouteActive = true,
                condition = NetworkCondition.ServersUnreachable,
                isTabletLayout = true,
                showRetryLabel = true,
            ),
        )
    }
}
