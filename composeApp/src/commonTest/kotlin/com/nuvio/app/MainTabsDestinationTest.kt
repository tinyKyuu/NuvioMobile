package com.nuvio.app

import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkRecoveryPhase
import com.nuvio.app.core.network.NetworkRecoveryUiState
import com.nuvio.app.core.network.NetworkStatusUiState
import com.nuvio.app.features.home.shouldShowOfflineHomeConnectionCard
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.core.ui.NuvioScreenHeaderActionsLayout
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
    fun `offline phone with playable local content keeps header retry reachable`() {
        assertFalse(
            shouldShowOfflineHomeConnectionCard(
                isOfflineLike = true,
                hasPlayableDownloads = true,
                hasContinueWatchingRows = false,
            ),
        )
        assertEquals(
            ReconnectControlState.Offline,
            rootConnectionStateForTab(
                selectedTab = AppScreenTab.Home,
                rootRouteActive = true,
                state = ReconnectControlState.Offline,
            ),
        )
    }

    @Test
    fun `Home Search and Library receive the header control while Settings does not`() {
        listOf(AppScreenTab.Home, AppScreenTab.Search, AppScreenTab.Library).forEach { tab ->
            assertEquals(
                ReconnectControlState.Restoring,
                rootConnectionStateForTab(
                    selectedTab = tab,
                    rootRouteActive = true,
                    state = ReconnectControlState.Restoring,
                ),
            )
        }
        assertEquals(
            ReconnectControlState.Hidden,
            rootConnectionStateForTab(
                selectedTab = AppScreenTab.Settings,
                rootRouteActive = true,
                state = ReconnectControlState.Restoring,
            ),
        )
        assertEquals(
            ReconnectControlState.Hidden,
            rootConnectionStateForTab(
                selectedTab = AppScreenTab.Home,
                rootRouteActive = false,
                state = ReconnectControlState.Offline,
            ),
        )
    }

    @Test
    fun `root headers keep a visible connection control inline until the width is genuinely narrow`() {
        assertEquals(
            NuvioScreenHeaderActionsLayout.Stacked,
            rootHeaderActionsLayoutForWidth(340.dp, ReconnectControlState.Probing),
        )
        assertEquals(
            NuvioScreenHeaderActionsLayout.Inline,
            rootHeaderActionsLayoutForWidth(390.dp, ReconnectControlState.Probing),
        )
        assertEquals(
            NuvioScreenHeaderActionsLayout.Inline,
            rootHeaderActionsLayoutForWidth(768.dp, ReconnectControlState.Restoring),
        )
        assertEquals(
            NuvioScreenHeaderActionsLayout.Inline,
            rootHeaderActionsLayoutForWidth(320.dp, ReconnectControlState.Hidden),
        )
    }

    @Test
    fun `root connection control drops its status graphic before it stacks`() {
        assertFalse(rootConnectionControlShowsStatusGraphic(390.dp))
        assertTrue(rootConnectionControlShowsStatusGraphic(440.dp))
    }

    @Test
    fun `tablet dock hides labels when a tablet window is narrow`() {
        assertEquals(TabletDockPresentation.Compact, tabletDockPresentationForWidth(599.dp))
        assertEquals(TabletDockPresentation.Labeled, tabletDockPresentationForWidth(600.dp))
        assertEquals(TabletDockPresentation.Labeled, tabletDockPresentationForWidth(1_024.dp))
    }

    @Test
    fun `confirmed connectivity holds the restoring control until recovery publishes`() {
        assertEquals(
            ReconnectControlState.Restoring,
            reconnectControlState(
                NetworkStatusUiState(
                    condition = NetworkCondition.Online,
                    keepOfflinePresentation = true,
                ),
                NetworkRecoveryUiState(),
            ),
        )
    }
}
