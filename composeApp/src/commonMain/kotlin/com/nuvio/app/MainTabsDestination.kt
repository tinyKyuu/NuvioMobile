package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.NetworkRecoveryPhase
import com.nuvio.app.core.network.NetworkRecoveryUiState
import com.nuvio.app.core.network.NetworkStatusUiState
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.LocalNuvioNavBarScrollState
import com.nuvio.app.core.ui.LocalNuvioTopNavigationOverlayPadding
import com.nuvio.app.core.ui.NuvioClassicNavigationBar
import com.nuvio.app.core.ui.NuvioNavigationBar
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.rememberNuvioNavBarScrollState
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.settings.ThemeSettingsRepository
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.compose_settings_page_root
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MainTabsDestination(
    selectedTab: AppScreenTab,
    initialHomeReady: Boolean,
    rootRouteActive: Boolean,
    useTabletFloatingTabBar: Boolean,
    useNativeNavigation: Boolean,
    useNativeTabBar: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    liquidGlassNativeTabBarEnabled: Boolean,
    networkStatus: NetworkStatusUiState,
    networkRecovery: NetworkRecoveryUiState,
    requests: AppTabRequests,
    state: AppTabState,
    actions: (isTabletLayout: Boolean) -> AppTabActions,
    onBack: () -> Unit,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    onNetworkRetry: () -> Unit,
) {
    PlatformBackHandler(enabled = true, onBack = onBack)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isTabletLayout = useTabletFloatingTabBar || maxWidth >= 768.dp
        val useNativeBottomTabs = if (useNativeNavigation) {
            useNativeTabBar
        } else {
            liquidGlassNativeTabBarSupported && liquidGlassNativeTabBarEnabled && initialHomeReady
        }
        val tabsRouteActive = rootRouteActive
        val reconnectControlState = reconnectControlState(networkStatus, networkRecovery)
        val offlineStatusPresentation = rootOfflineStatusPresentation(
            rootRouteActive = tabsRouteActive,
            state = reconnectControlState,
            isTabletLayout = isTabletLayout,
            showRetryLabel = maxWidth >= 900.dp,
        )
        val navBarScrollState = rememberNuvioNavBarScrollState()
        val navBarHazeState = rememberHazeState()
        val navBarStyleSetting by remember { ThemeSettingsRepository.navBarStyle }.collectAsStateWithLifecycle()
        val navigationOverlayPadding = rootNavigationOverlayPadding(
            isTabletLayout = isTabletLayout,
            useNativeBottomTabs = useNativeBottomTabs,
            navBarStyle = navBarStyleSetting,
        )

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (initialHomeReady) 1f else 0f),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!isTabletLayout && !useNativeBottomTabs && navBarStyleSetting == NavBarStyle.CLASSIC) {
                    NuvioClassicNavigationBar {
                        NavItem(
                            selected = selectedTab == AppScreenTab.Home,
                            onClick = { onTabSelected(AppScreenTab.Home) },
                            icon = Icons.Filled.Home,
                            contentDescription = stringResource(Res.string.compose_nav_home),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Search,
                            onClick = { onTabSelected(AppScreenTab.Search) },
                            icon = Res.drawable.sidebar_search,
                            contentDescription = stringResource(Res.string.compose_nav_search),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Library,
                            onClick = { onTabSelected(AppScreenTab.Library) },
                            icon = Res.drawable.sidebar_library,
                            contentDescription = stringResource(Res.string.compose_nav_library),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Settings,
                            onClick = { onTabSelected(AppScreenTab.Settings) },
                        ) {
                            ProfileSwitcherTab(
                                selected = selectedTab == AppScreenTab.Settings,
                                onClick = { onTabSelected(AppScreenTab.Settings) },
                                onProfileSelected = onProfileSelected,
                                onAddProfileRequested = onAddProfileRequested,
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalNuvioBottomNavigationOverlayPadding provides navigationOverlayPadding.bottom,
                    LocalNuvioTopNavigationOverlayPadding provides navigationOverlayPadding.top,
                    LocalNuvioNavBarScrollState provides navBarScrollState,
                ) {
                    AppTabHost(
                        selectedTab = selectedTab,
                        isTabletLayout = isTabletLayout,
                        requests = requests,
                        state = state,
                        actions = actions(isTabletLayout),
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (navBarStyleSetting != NavBarStyle.CLASSIC) Modifier.hazeSource(state = navBarHazeState) else Modifier)
                            .then(if (navBarStyleSetting == NavBarStyle.ADAPTIVE) Modifier.nestedScroll(navBarScrollState.nestedScrollConnection) else Modifier)
                            .padding(innerPadding),
                    )
                }

                if (isTabletLayout && !useNativeBottomTabs) {
                    TabletFloatingBottomDock(
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected,
                        onProfileSelected = onProfileSelected,
                        onAddProfileRequested = onAddProfileRequested,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                if (offlineStatusPresentation != RootOfflineStatusPresentation.Hidden) {
                    RootOfflineStatusPill(
                        condition = networkStatus.condition,
                        state = reconnectControlState,
                        showRetryLabel = offlineStatusPresentation == RootOfflineStatusPresentation.RetryPill,
                        onRetry = onNetworkRetry,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }

                if (!isTabletLayout && !useNativeBottomTabs && navBarStyleSetting != NavBarStyle.CLASSIC) {
                    when (navBarStyleSetting) {
                        NavBarStyle.EXPANDED -> navBarScrollState.expand()
                        NavBarStyle.COMPACT -> navBarScrollState.collapse()
                        else -> {}
                    }
                    NuvioNavigationBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        scrollState = navBarScrollState,
                        hazeState = navBarHazeState,
                    ) {
                        NavItem(
                            selected = selectedTab == AppScreenTab.Home,
                            onClick = { onTabSelected(AppScreenTab.Home) },
                            icon = Icons.Filled.Home,
                            contentDescription = stringResource(Res.string.compose_nav_home),
                            label = stringResource(Res.string.compose_nav_home),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Search,
                            onClick = { onTabSelected(AppScreenTab.Search) },
                            icon = Res.drawable.sidebar_search,
                            contentDescription = stringResource(Res.string.compose_nav_search),
                            label = stringResource(Res.string.compose_nav_search),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Library,
                            onClick = { onTabSelected(AppScreenTab.Library) },
                            icon = Res.drawable.sidebar_library,
                            contentDescription = stringResource(Res.string.compose_nav_library),
                            label = stringResource(Res.string.compose_nav_library),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Settings,
                            onClick = { onTabSelected(AppScreenTab.Settings) },
                            label = stringResource(Res.string.compose_settings_page_root),
                        ) {
                            ProfileSwitcherTab(
                                selected = selectedTab == AppScreenTab.Settings,
                                onClick = { onTabSelected(AppScreenTab.Settings) },
                                onProfileSelected = onProfileSelected,
                                onAddProfileRequested = onAddProfileRequested,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal data class RootNavigationOverlayPadding(
    val top: Dp,
    val bottom: Dp,
)

internal fun rootNavigationOverlayPadding(
    isTabletLayout: Boolean,
    useNativeBottomTabs: Boolean,
    navBarStyle: NavBarStyle,
): RootNavigationOverlayPadding = when {
    useNativeBottomTabs -> RootNavigationOverlayPadding(top = 0.dp, bottom = 49.dp)
    isTabletLayout -> RootNavigationOverlayPadding(top = 0.dp, bottom = 64.dp)
    navBarStyle != NavBarStyle.CLASSIC -> RootNavigationOverlayPadding(top = 0.dp, bottom = 72.dp)
    else -> RootNavigationOverlayPadding(top = 0.dp, bottom = 0.dp)
}

internal fun shouldShowRootOfflineStatus(
    rootRouteActive: Boolean,
    state: ReconnectControlState,
): Boolean = rootRouteActive && state != ReconnectControlState.Hidden

internal enum class ReconnectControlState {
    Hidden,
    Offline,
    Probing,
    Restoring,
    Failed,
}

internal fun reconnectControlState(
    networkStatus: NetworkStatusUiState,
    recovery: NetworkRecoveryUiState,
): ReconnectControlState = when {
    networkStatus.isProbing &&
        (networkStatus.isOfflineLike || recovery.phase == NetworkRecoveryPhase.Failed) ->
        ReconnectControlState.Probing
    recovery.phase == NetworkRecoveryPhase.Failed -> ReconnectControlState.Failed
    recovery.isRecovering -> ReconnectControlState.Restoring
    networkStatus.isOfflineLike -> ReconnectControlState.Offline
    else -> ReconnectControlState.Hidden
}

internal enum class RootOfflineStatusPresentation {
    Hidden,
    CompactIcon,
    RetryPill,
}

internal fun rootOfflineStatusPresentation(
    rootRouteActive: Boolean,
    state: ReconnectControlState,
    isTabletLayout: Boolean,
    showRetryLabel: Boolean,
): RootOfflineStatusPresentation = when {
    !shouldShowRootOfflineStatus(rootRouteActive, state) -> RootOfflineStatusPresentation.Hidden
    isTabletLayout && showRetryLabel -> RootOfflineStatusPresentation.RetryPill
    else -> RootOfflineStatusPresentation.CompactIcon
}
