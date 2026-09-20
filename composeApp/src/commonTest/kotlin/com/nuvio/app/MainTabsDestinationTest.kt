package com.nuvio.app

import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioNavigationBarVisualStyle
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkRecoveryPhase
import com.nuvio.app.core.network.NetworkRecoveryUiState
import com.nuvio.app.core.network.NetworkStatusUiState
import com.nuvio.app.features.home.shouldShowOfflineHomeConnectionCard
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.core.ui.NuvioAdaptiveHeaderPresentation
import com.nuvio.app.core.ui.keyboardLayoutOccludesContent
import com.nuvio.app.core.ui.navigationBarBottomPadding
import com.nuvio.app.core.ui.reconciledIosImeVisibility
import com.nuvio.app.core.ui.resolveAdaptiveHeaderPresentation
import com.nuvio.app.core.ui.sharedSelectionIndicatorOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTabsDestinationTest {

    @Test
    fun `persistent Apple host disables every per-controller dock without duplicate clearance`() {
        for (nativeTabs in listOf(false, true)) {
            assertFalse(usesComposeRootNavigation(nativeTabs, hostOwnsRootDock = true))
            for (style in NavBarStyle.entries) {
                assertEquals(
                    RootNavigationOverlayPadding(top = 0.dp, bottom = 0.dp),
                    rootNavigationOverlayPadding(nativeTabs, style, hostOwnsRootDock = true),
                )
            }
        }
        assertTrue(usesComposeRootNavigation(false, hostOwnsRootDock = false))
        assertFalse(usesComposeRootNavigation(true, hostOwnsRootDock = false))
    }

    @Test
    fun `tablet floating navigation reserves only bottom overlay space`() {
        val padding = rootNavigationOverlayPadding(
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
                useNativeBottomTabs = true,
                navBarStyle = NavBarStyle.CLASSIC,
            ),
        )
        assertEquals(
            RootNavigationOverlayPadding(top = 0.dp, bottom = 72.dp),
            rootNavigationOverlayPadding(
                useNativeBottomTabs = false,
                navBarStyle = NavBarStyle.ADAPTIVE,
            ),
        )
        assertEquals(
            RootNavigationOverlayPadding(top = 0.dp, bottom = 0.dp),
            rootNavigationOverlayPadding(
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
                useNativeBottomTabs = false,
                navBarStyle = NavBarStyle.ADAPTIVE,
                navigationVisible = false,
            ),
        )
    }

    @Test
    fun `tablet classic navigation uses scaffold clearance instead of overlay clearance`() {
        assertEquals(
            RootNavigationOverlayPadding(top = 0.dp, bottom = 0.dp),
            rootNavigationOverlayPadding(
                useNativeBottomTabs = false,
                navBarStyle = NavBarStyle.CLASSIC,
            ),
        )
    }

    @Test
    fun `iPad keeps stable expanded labels while Android tablets honor stored navigation style`() {
        NavBarStyle.entries.forEach { storedStyle ->
            assertEquals(
                NavBarStyle.EXPANDED,
                effectiveRootNavigationStyle(
                    isTabletLayout = true,
                    isIosPlatform = true,
                    storedStyle = storedStyle,
                ),
            )
            assertEquals(
                storedStyle,
                effectiveRootNavigationStyle(
                    isTabletLayout = true,
                    isIosPlatform = false,
                    storedStyle = storedStyle,
                ),
            )
            assertEquals(
                storedStyle,
                effectiveRootNavigationStyle(
                    isTabletLayout = false,
                    isIosPlatform = true,
                    storedStyle = storedStyle,
                ),
            )
        }
    }

    @Test
    fun `navigation scroll state supports deterministic expanded and compact targets`() {
        val state = NuvioNavBarScrollState()

        state.collapse()
        assertEquals(0f, state.labelVisibility)

        state.expand()
        assertEquals(1f, state.labelVisibility)
    }

    @Test
    fun `IME dismissal restores root navigation`() {
        val state = RootNavigationSuppressionState()

        assertTrue(state.isSuppressed(AppScreenTab.Search, imeVisible = true))
        assertFalse(state.isSuppressed(AppScreenTab.Search, imeVisible = false))
    }

    @Test
    fun `download management exit restores Library navigation`() {
        val state = RootNavigationSuppressionState()

        state.onLibraryDownloadManagementActiveChanged(true)
        assertTrue(state.isSuppressed(AppScreenTab.Library, imeVisible = false))

        state.onLibraryDownloadManagementActiveChanged(false)
        assertFalse(state.isSuppressed(AppScreenTab.Library, imeVisible = false))
    }

    @Test
    fun `tab change releases Library management while preserving active IME suppression`() {
        val state = RootNavigationSuppressionState()
        state.onLibraryDownloadManagementActiveChanged(true)

        state.onTabChanged(AppScreenTab.Library, AppScreenTab.Home)
        assertTrue(state.isSuppressed(AppScreenTab.Home, imeVisible = true))

        assertFalse(state.isSuppressed(AppScreenTab.Home, imeVisible = false))
    }

    @Test
    fun `active IME remains authoritative until a hide signal arrives`() {
        val state = RootNavigationSuppressionState()
        assertTrue(state.isSuppressed(AppScreenTab.Home, imeVisible = true))
        assertFalse(state.isSuppressed(AppScreenTab.Home, imeVisible = false))
    }

    @Test
    fun `iOS IME reconciliation clears stale visibility from either source`() {
        assertFalse(
            reconciledIosImeVisibility(
                windowInsetsVisible = true,
                nativeVisibility = false,
            ),
        )
        assertFalse(
            reconciledIosImeVisibility(
                windowInsetsVisible = false,
                nativeVisibility = true,
            ),
        )
        assertTrue(
            reconciledIosImeVisibility(
                windowInsetsVisible = true,
                nativeVisibility = null,
            ),
        )
        assertTrue(
            reconciledIosImeVisibility(
                windowInsetsVisible = true,
                nativeVisibility = true,
            ),
        )
    }

    @Test
    fun `iOS keyboard layout ignores safe-area and accessory-only heights`() {
        assertFalse(keyboardLayoutOccludesContent(layoutHeight = 21.0, bottomSafeArea = 20.0))
        assertFalse(keyboardLayoutOccludesContent(layoutHeight = 68.5, bottomSafeArea = 20.0))
        assertTrue(keyboardLayoutOccludesContent(layoutHeight = 320.0, bottomSafeArea = 20.0))
    }

    @Test
    fun `iPad dock keeps the physical bottom inset while keyboard insets animate`() {
        assertEquals(
            20.dp,
            navigationBarBottomPadding(
                visualStyle = NuvioNavigationBarVisualStyle.IosTablet,
                windowInsetsBottom = 340.dp,
                physicalBottom = 20.dp,
            ),
        )
        assertEquals(
            340.dp,
            navigationBarBottomPadding(
                visualStyle = NuvioNavigationBarVisualStyle.Standard,
                windowInsetsBottom = 340.dp,
                physicalBottom = 20.dp,
            ),
        )
    }

    @Test
    fun `iPad shared selector travels by one equal tab slot`() {
        assertEquals(0.dp, sharedSelectionIndicatorOffset(100.dp, selectedIndex = 0, itemCount = 4))
        assertEquals(100.dp, sharedSelectionIndicatorOffset(100.dp, selectedIndex = 1, itemCount = 4))
        assertEquals(300.dp, sharedSelectionIndicatorOffset(100.dp, selectedIndex = 3, itemCount = 4))
    }

    @Test
    fun `traveling selector is shared by floating tablet docks only`() {
        assertTrue(
            usesTravelingDockSelectionIndicator(
                isTabletLayout = true,
                usesFloatingComposeNavigation = true,
            ),
        )
        assertFalse(
            usesTravelingDockSelectionIndicator(
                isTabletLayout = false,
                usesFloatingComposeNavigation = true,
            ),
        )
        assertFalse(
            usesTravelingDockSelectionIndicator(
                isTabletLayout = true,
                usesFloatingComposeNavigation = false,
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
