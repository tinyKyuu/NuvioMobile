package com.nuvio.app

import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.features.home.shouldShowOfflineHomeConnectionCard
import com.nuvio.app.features.settings.NavBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTabsDestinationTest {

    @Test
    fun `tablet floating navigation reserves only bottom overlay space`() {
        val padding = rootNavigationOverlayPadding(
            isTabletLayout = true,
            useNativeBottomTabs = false,
            navBarStyle = NavBarStyle.ADAPTIVE,
        )

        assertEquals(0.dp, padding.top)
        assertEquals(64.dp, padding.bottom)
    }

    @Test
    fun `phone and native tab overlay padding stays unchanged`() {
        assertEquals(
            RootNavigationOverlayPadding(top = 0.dp, bottom = 49.dp),
            rootNavigationOverlayPadding(
                isTabletLayout = false,
                useNativeBottomTabs = true,
                navBarStyle = NavBarStyle.CLASSIC,
            ),
        )
        assertEquals(
            RootNavigationOverlayPadding(top = 0.dp, bottom = 72.dp),
            rootNavigationOverlayPadding(
                isTabletLayout = false,
                useNativeBottomTabs = false,
                navBarStyle = NavBarStyle.ADAPTIVE,
            ),
        )
        assertEquals(
            RootNavigationOverlayPadding(top = 0.dp, bottom = 0.dp),
            rootNavigationOverlayPadding(
                isTabletLayout = false,
                useNativeBottomTabs = false,
                navBarStyle = NavBarStyle.CLASSIC,
            ),
        )
    }

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
