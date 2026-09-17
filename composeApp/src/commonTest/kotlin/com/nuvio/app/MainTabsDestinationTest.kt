package com.nuvio.app

import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkRecoveryPhase
import com.nuvio.app.core.network.NetworkRecoveryUiState
import com.nuvio.app.core.network.NetworkStatusUiState
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
    fun `tablet sticky root headers avoid a second status bar inset`() {
        assertEquals(10.dp, rootListTopPaddingForStickyHeader(true, 10.dp))
        assertEquals(null, rootListTopPaddingForStickyHeader(false, 10.dp))
    }

    @Test
    fun `offline status appears on root routes for connection failures`() {
        assertTrue(shouldShowRootOfflineStatus(true, ReconnectControlState.Offline))
        assertTrue(shouldShowRootOfflineStatus(true, ReconnectControlState.Failed))
    }

    @Test
    fun `offline status stays hidden outside root routes and while checking`() {
        assertFalse(shouldShowRootOfflineStatus(false, ReconnectControlState.Offline))
        assertFalse(shouldShowRootOfflineStatus(true, ReconnectControlState.Hidden))
    }

    @Test
    fun `reconnect control exposes probing restoring failure and completion states`() {
        assertEquals(
            ReconnectControlState.Probing,
            reconnectControlState(
                NetworkStatusUiState(NetworkCondition.NoInternet, isProbing = true),
                NetworkRecoveryUiState(),
            ),
        )
        assertEquals(
            ReconnectControlState.Restoring,
            reconnectControlState(
                NetworkStatusUiState(NetworkCondition.Online),
                NetworkRecoveryUiState(phase = NetworkRecoveryPhase.RefreshingCatalogs),
            ),
        )
        assertEquals(
            ReconnectControlState.Failed,
            reconnectControlState(
                NetworkStatusUiState(NetworkCondition.Online),
                NetworkRecoveryUiState(phase = NetworkRecoveryPhase.Failed),
            ),
        )
        assertEquals(
            ReconnectControlState.Hidden,
            reconnectControlState(
                NetworkStatusUiState(NetworkCondition.Online),
                NetworkRecoveryUiState(phase = NetworkRecoveryPhase.Completed),
            ),
        )
        assertEquals(
            ReconnectControlState.Probing,
            reconnectControlState(
                NetworkStatusUiState(NetworkCondition.Online, isProbing = true),
                NetworkRecoveryUiState(phase = NetworkRecoveryPhase.Failed),
            ),
        )
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
                state = ReconnectControlState.Offline,
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
                state = ReconnectControlState.Restoring,
                isTabletLayout = true,
                showRetryLabel = true,
            ),
        )
    }
}
