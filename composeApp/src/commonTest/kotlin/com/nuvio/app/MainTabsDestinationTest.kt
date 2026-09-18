package com.nuvio.app

import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkRecoveryPhase
import com.nuvio.app.core.network.NetworkRecoveryUiState
import com.nuvio.app.core.network.NetworkStatusUiState
import com.nuvio.app.features.home.shouldShowOfflineHomeConnectionCard
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.core.ui.NuvioAdaptiveHeaderPresentation
import com.nuvio.app.core.ui.resolveAdaptiveHeaderPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MainTabsDestinationTest {

    @Test
    fun `tablet floating navigation reserves only bottom overlay space`() {
        val padding = rootNavigationOverlayPadding(
            isTabletLayout = true,
            useNativeBottomTabs = false,
            navBarStyle = NavBarStyle.ADAPTIVE,
        )

        assertEquals(0.dp, padding.top)
        assertEquals(72.dp, padding.bottom)
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
    fun `suppressed root navigation releases its overlay clearance`() {
        assertEquals(
            RootNavigationOverlayPadding(top = 0.dp, bottom = 0.dp),
            rootNavigationOverlayPadding(
                isTabletLayout = true,
                useNativeBottomTabs = false,
                navBarStyle = NavBarStyle.ADAPTIVE,
                navigationVisible = false,
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
    fun `reconnect presentation keeps long status text out of visible button content`() {
        assertEquals(
            RootConnectionVisual.ReconnectWithIcon,
            rootConnectionVisual(ReconnectControlState.Offline, showStatusGraphic = true),
        )
        assertEquals(
            RootConnectionVisual.ReconnectText,
            rootConnectionVisual(ReconnectControlState.Failed, showStatusGraphic = false),
        )
        assertEquals(
            RootConnectionVisual.RestoringWithSpinner,
            rootConnectionVisual(ReconnectControlState.Restoring, showStatusGraphic = true),
        )
        assertEquals(
            RootConnectionVisual.Spinner,
            rootConnectionVisual(ReconnectControlState.Probing, showStatusGraphic = false),
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
    fun `root headers remove the status graphic before stacking the connection control`() {
        assertEquals(
            NuvioAdaptiveHeaderPresentation.FullInline,
            resolveAdaptiveHeaderPresentation(
                availableWidthPx = 220,
                naturalTitleWidthPx = 100,
                fullActionsWidthPx = 100,
                compactActionsWidthPx = 80,
                minimumInlineTitleWidthPx = 96,
                spacingPx = 10,
            ),
        )
        assertEquals(
            NuvioAdaptiveHeaderPresentation.CompactInline,
            resolveAdaptiveHeaderPresentation(
                availableWidthPx = 220,
                naturalTitleWidthPx = 150,
                fullActionsWidthPx = 100,
                compactActionsWidthPx = 80,
                minimumInlineTitleWidthPx = 96,
                spacingPx = 10,
            ),
        )
        assertEquals(
            NuvioAdaptiveHeaderPresentation.CompactStacked,
            resolveAdaptiveHeaderPresentation(
                availableWidthPx = 180,
                naturalTitleWidthPx = 150,
                fullActionsWidthPx = 100,
                compactActionsWidthPx = 80,
                minimumInlineTitleWidthPx = 96,
                spacingPx = 10,
            ),
        )
    }

    @Test
    fun `tablet dock progressively removes labels only when measured content stops fitting`() {
        assertEquals(
            TabletDockPresentation.Compact,
            tabletDockPresentationForMeasuredContent(
                availableWidthPx = 499,
                fullWidthPx = 700,
                settingsCompactWidthPx = 500,
            ),
        )
        assertEquals(
            TabletDockPresentation.SettingsCompact,
            tabletDockPresentationForMeasuredContent(
                availableWidthPx = 500,
                fullWidthPx = 700,
                settingsCompactWidthPx = 500,
            ),
        )
        assertEquals(
            TabletDockPresentation.SettingsCompact,
            tabletDockPresentationForMeasuredContent(
                availableWidthPx = 699,
                fullWidthPx = 700,
                settingsCompactWidthPx = 500,
            ),
        )
        assertEquals(
            TabletDockPresentation.Full,
            tabletDockPresentationForMeasuredContent(
                availableWidthPx = 700,
                fullWidthPx = 700,
                settingsCompactWidthPx = 500,
            ),
        )
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
