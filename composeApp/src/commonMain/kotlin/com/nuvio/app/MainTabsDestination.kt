package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
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
import com.nuvio.app.core.ui.NuvioNavigationBarVisualStyle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.rememberNuvioNavBarScrollState
import com.nuvio.app.core.ui.rememberReliableImeVisibility
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
    onRootNavigationSuppressedChange: ((Boolean) -> Unit)? = null,
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
        val tabConnectionState = rootConnectionStateForTab(
            selectedTab = selectedTab,
            rootRouteActive = tabsRouteActive,
            state = reconnectControlState,
        )
        val navBarScrollState = rememberNuvioNavBarScrollState()
        val navBarHazeState = rememberHazeState()
        val navBarStyleSetting by remember { ThemeSettingsRepository.navBarStyle }.collectAsStateWithLifecycle()
        val effectiveNavBarStyle = effectiveRootNavigationStyle(
            isTabletLayout = isTabletLayout,
            isIosPlatform = isIos,
            storedStyle = navBarStyleSetting,
        )
        val usesClassicComposeNavigation = !useNativeBottomTabs && effectiveNavBarStyle == NavBarStyle.CLASSIC
        val usesFloatingComposeNavigation = !useNativeBottomTabs && !usesClassicComposeNavigation
        val isImeVisible = rememberReliableImeVisibility(
            windowInsetsVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0,
        )
        val suppressionState = remember { RootNavigationSuppressionState() }
        val onLibraryDownloadManagementActiveChange = remember(suppressionState) {
            { active: Boolean -> suppressionState.onLibraryDownloadManagementActiveChanged(active) }
        }
        val rootNavigationSuppressed = suppressionState.isSuppressed(
            selectedTab = selectedTab,
            imeVisible = isImeVisible,
        )
        val customNavigationVisible = !rootNavigationSuppressed
        val navigationOverlayPadding = rootNavigationOverlayPadding(
            useNativeBottomTabs = useNativeBottomTabs,
            navBarStyle = effectiveNavBarStyle,
            navigationVisible = !rootNavigationSuppressed,
        )
        var previousSelectedTab by remember { mutableStateOf(selectedTab) }
        val homeLabel = stringResource(Res.string.compose_nav_home)
        val searchLabel = stringResource(Res.string.compose_nav_search)
        val libraryLabel = stringResource(Res.string.compose_nav_library)
        val settingsLabel = stringResource(Res.string.compose_settings_page_root)
        val tabletDockPresentation = if (isTabletLayout) {
            tabletDockPresentationForLabels(
                availableWidth = maxWidth,
                labels = listOf(homeLabel, searchLabel, libraryLabel, settingsLabel),
            )
        } else {
            TabletDockPresentation.Full
        }

        LaunchedEffect(selectedTab, effectiveNavBarStyle, isTabletLayout) {
            if (previousSelectedTab != selectedTab) {
                suppressionState.onTabChanged(previousSelectedTab, selectedTab)
                previousSelectedTab = selectedTab
            }
            when (effectiveNavBarStyle) {
                NavBarStyle.EXPANDED -> navBarScrollState.expand()
                NavBarStyle.COMPACT -> navBarScrollState.collapse()
                NavBarStyle.ADAPTIVE -> if (isTabletLayout) navBarScrollState.expand()
                NavBarStyle.CLASSIC -> Unit
            }
        }
        LaunchedEffect(rootNavigationSuppressed, onRootNavigationSuppressedChange) {
            onRootNavigationSuppressedChange?.invoke(rootNavigationSuppressed)
        }
        DisposableEffect(onRootNavigationSuppressedChange) {
            onDispose { onRootNavigationSuppressedChange?.invoke(false) }
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (initialHomeReady) 1f else 0f),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (customNavigationVisible && usesClassicComposeNavigation) {
                    NuvioClassicNavigationBar {
                        NavItem(
                            selected = selectedTab == AppScreenTab.Home,
                            onClick = { onTabSelected(AppScreenTab.Home) },
                            icon = Icons.Filled.Home,
                            contentDescription = homeLabel,
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Search,
                            onClick = { onTabSelected(AppScreenTab.Search) },
                            icon = Res.drawable.sidebar_search,
                            contentDescription = searchLabel,
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Library,
                            onClick = { onTabSelected(AppScreenTab.Library) },
                            icon = Res.drawable.sidebar_library,
                            contentDescription = libraryLabel,
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
                        networkCondition = networkStatus.condition,
                        reconnectControlState = tabConnectionState,
                        onNetworkRetry = onNetworkRetry,
                        onLibraryDownloadManagementActiveChange = onLibraryDownloadManagementActiveChange,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (usesFloatingComposeNavigation) Modifier.hazeSource(state = navBarHazeState) else Modifier)
                            .then(if (effectiveNavBarStyle == NavBarStyle.ADAPTIVE) Modifier.nestedScroll(navBarScrollState.nestedScrollConnection) else Modifier)
                            .padding(innerPadding),
                    )
                }

                if (customNavigationVisible && usesFloatingComposeNavigation) {
                    val showPrimaryLabels = !isTabletLayout || tabletDockPresentation != TabletDockPresentation.Compact
                    val showSettingsLabel = !isTabletLayout || tabletDockPresentation == TabletDockPresentation.Full
                    NuvioNavigationBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        scrollState = navBarScrollState,
                        hazeState = navBarHazeState,
                        maxWidth = if (isTabletLayout) TabletNavigationMaxWidth else null,
                        horizontalPadding = if (isTabletLayout) TabletNavigationHorizontalPadding else null,
                        visualStyle = if (isTabletLayout && isIos) {
                            NuvioNavigationBarVisualStyle.IosTablet
                        } else {
                            NuvioNavigationBarVisualStyle.Standard
                        },
                    ) {
                        NavItem(
                            selected = selectedTab == AppScreenTab.Home,
                            onClick = { onTabSelected(AppScreenTab.Home) },
                            icon = Icons.Filled.Home,
                            contentDescription = homeLabel,
                            label = homeLabel.takeIf { showPrimaryLabels },
                            reserveLabelSpace = showPrimaryLabels,
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Search,
                            onClick = { onTabSelected(AppScreenTab.Search) },
                            icon = Res.drawable.sidebar_search,
                            contentDescription = searchLabel,
                            label = searchLabel.takeIf { showPrimaryLabels },
                            reserveLabelSpace = showPrimaryLabels,
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Library,
                            onClick = { onTabSelected(AppScreenTab.Library) },
                            icon = Res.drawable.sidebar_library,
                            contentDescription = libraryLabel,
                            label = libraryLabel.takeIf { showPrimaryLabels },
                            reserveLabelSpace = showPrimaryLabels,
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Settings,
                            onClick = { onTabSelected(AppScreenTab.Settings) },
                            label = settingsLabel.takeIf { showSettingsLabel },
                            reserveLabelSpace = showPrimaryLabels,
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
    useNativeBottomTabs: Boolean,
    navBarStyle: NavBarStyle,
    navigationVisible: Boolean = true,
): RootNavigationOverlayPadding = when {
    !navigationVisible -> RootNavigationOverlayPadding(top = 0.dp, bottom = 0.dp)
    useNativeBottomTabs -> RootNavigationOverlayPadding(top = 0.dp, bottom = 49.dp)
    navBarStyle != NavBarStyle.CLASSIC -> RootNavigationOverlayPadding(top = 0.dp, bottom = 72.dp)
    else -> RootNavigationOverlayPadding(top = 0.dp, bottom = 0.dp)
}

internal fun effectiveRootNavigationStyle(
    isTabletLayout: Boolean,
    isIosPlatform: Boolean,
    storedStyle: NavBarStyle,
): NavBarStyle = if (isTabletLayout && isIosPlatform) {
    NavBarStyle.EXPANDED
} else {
    storedStyle
}

internal class RootNavigationSuppressionState {
    private var libraryDownloadManagementActive by mutableStateOf(false)

    fun onLibraryDownloadManagementActiveChanged(active: Boolean) {
        libraryDownloadManagementActive = active
    }

    fun onTabChanged(previous: AppScreenTab, current: AppScreenTab) {
        if (previous == AppScreenTab.Library && current != AppScreenTab.Library) {
            libraryDownloadManagementActive = false
        }
    }

    fun isSuppressed(
        selectedTab: AppScreenTab,
        imeVisible: Boolean,
    ): Boolean = imeVisible ||
        (selectedTab == AppScreenTab.Library && libraryDownloadManagementActive)
}

enum class ReconnectControlState {
    Hidden,
    Offline,
    Probing,
    Restoring,
    Failed,
}

internal enum class RootConnectionVisual {
    Hidden,
    ReconnectWithIcon,
    ReconnectText,
    RestoringWithSpinner,
    Spinner,
}

internal fun rootConnectionVisual(
    state: ReconnectControlState,
    showStatusGraphic: Boolean,
): RootConnectionVisual = when {
    state == ReconnectControlState.Hidden -> RootConnectionVisual.Hidden
    state == ReconnectControlState.Probing || state == ReconnectControlState.Restoring -> {
        if (showStatusGraphic) {
            RootConnectionVisual.RestoringWithSpinner
        } else {
            RootConnectionVisual.Spinner
        }
    }
    showStatusGraphic -> RootConnectionVisual.ReconnectWithIcon
    else -> RootConnectionVisual.ReconnectText
}

internal fun reconnectControlState(
    networkStatus: NetworkStatusUiState,
    recovery: NetworkRecoveryUiState,
): ReconnectControlState = when {
    networkStatus.isProbing &&
        (networkStatus.usesOfflinePresentation || recovery.phase == NetworkRecoveryPhase.Failed) ->
        ReconnectControlState.Probing
    recovery.phase == NetworkRecoveryPhase.Failed -> ReconnectControlState.Failed
    recovery.isRecovering ||
        (networkStatus.isOnline && networkStatus.keepOfflinePresentation) ->
        ReconnectControlState.Restoring
    networkStatus.isOfflineLike -> ReconnectControlState.Offline
    else -> ReconnectControlState.Hidden
}

internal fun rootConnectionStateForTab(
    selectedTab: AppScreenTab,
    rootRouteActive: Boolean,
    state: ReconnectControlState,
): ReconnectControlState = if (
    rootRouteActive && selectedTab != AppScreenTab.Settings
) {
    state
} else {
    ReconnectControlState.Hidden
}

internal enum class TabletDockPresentation {
    Full,
    SettingsCompact,
    Compact,
}

private val TabletNavigationMaxWidth = 560.dp
private val TabletNavigationHorizontalPadding = NuvioTokens.Space.s16

@Composable
private fun tabletDockPresentationForLabels(
    availableWidth: Dp,
    labels: List<String>,
): TabletDockPresentation {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = NuvioTokens.Type.labelXs,
        lineHeight = NuvioTokens.LineHeight.labelXs,
    )
    val compactItemWidthPx = with(density) { NuvioTokens.Space.s56.roundToPx() }
    val labelPaddingPx = with(density) { (NuvioTokens.Space.s6 * 2).roundToPx() }
    val barPaddingPx = with(density) { (NuvioTokens.Space.s6 * 2).roundToPx() }
    fun labeledItemWidthPx(label: String): Int = maxOf(
        compactItemWidthPx,
        textMeasurer.measure(
            text = AnnotatedString(label),
            style = labelStyle,
            maxLines = 1,
        ).size.width + labelPaddingPx,
    )
    val primaryItemWidthPx = labels.take(3).maxOf(::labeledItemWidthPx)
    val settingsItemWidthPx = labeledItemWidthPx(labels.last())
    val settingsCompactWidthPx = barPaddingPx + maxOf(primaryItemWidthPx, compactItemWidthPx) * 4
    val fullWidthPx = barPaddingPx + maxOf(primaryItemWidthPx, settingsItemWidthPx) * 4
    val availableContentWidth = minOf(
        availableWidth - TabletNavigationHorizontalPadding * 2,
        TabletNavigationMaxWidth,
    )
    return tabletDockPresentationForMeasuredContent(
        availableWidthPx = with(density) { availableContentWidth.coerceAtLeast(0.dp).roundToPx() },
        fullWidthPx = fullWidthPx,
        settingsCompactWidthPx = settingsCompactWidthPx,
    )
}

internal fun tabletDockPresentationForMeasuredContent(
    availableWidthPx: Int,
    fullWidthPx: Int,
    settingsCompactWidthPx: Int,
): TabletDockPresentation = when {
    fullWidthPx <= availableWidthPx -> TabletDockPresentation.Full
    settingsCompactWidthPx <= availableWidthPx -> TabletDockPresentation.SettingsCompact
    else -> TabletDockPresentation.Compact
}
