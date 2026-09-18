package com.nuvio.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.titleForEmptyState
import com.nuvio.app.core.ui.DisintegrationRequest
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeScreen
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryScreen
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileBackgroundBackdrop
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import com.nuvio.app.features.search.SearchScreen
import com.nuvio.app.features.settings.AppBrandWordmark
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.NuvioNavigator
import kotlinx.coroutines.flow.Flow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_brand_name
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.compose_settings_page_root
import nuvio.composeapp.generated.resources.network_reconnect
import nuvio.composeapp.generated.resources.network_reconnecting
import nuvio.composeapp.generated.resources.network_online
import nuvio.composeapp.generated.resources.network_restore_failed_reconnect
import nuvio.composeapp.generated.resources.network_restoring_content
import nuvio.composeapp.generated.resources.network_restoring_short
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun rememberGuardedPopBackStack(
    navController: NuvioNavigator,
    route: AppRoute,
    beforePop: () -> Unit = {},
): () -> Unit {
    var popHandled by remember(route) { mutableStateOf(false) }

    return remember(navController, route, popHandled, beforePop) {
        {
            if (!popHandled && navController.currentRoute == route) {
                popHandled = true
                beforePop()
                navController.popBackStack(expectedRoute = route)
            }
        }
    }
}

internal data class AppTabState(
    val searchListState: LazyListState,
    val homeContentGeneration: Int = 0,
    val homePresentationResetGeneration: Long = 0L,
    val searchFocusRequestCount: Int = 0,
    val rootActionsEnabled: Boolean = true,
    val animateHomeCollectionGifs: Boolean = true,
    val libraryDisintegrationRequest: DisintegrationRequest<String>? = null,
    val continueWatchingDisintegrationRequest: DisintegrationRequest<String>? = null,
    val requestedSettingsPageName: String? = null,
    val openLibraryDownloadsRequest: Int = 0,
)

internal data class AppTabRequests(
    val homeScrollToTopRequests: Flow<Unit>,
    val searchScrollToTopRequests: Flow<Unit>,
    val libraryScrollToTopRequests: Flow<Unit>,
    val settingsRootActionRequests: Flow<Unit>,
)

internal data class AppTabActions(
    val onHomePresentationResetConsumed: (Long) -> Boolean = { false },
    val onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    val onPosterClick: ((MetaPreview) -> Unit)? = null,
    val onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    val onLibraryPosterClick: ((LibraryItem) -> Unit)? = null,
    val onLibraryPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)? = null,
    val onLibrarySectionViewAllClick: ((LibrarySection, LibrarySortOption) -> Unit)? = null,
    val onCloudFilePlay: ((CloudLibraryItem, CloudLibraryFile) -> Unit)? = null,
    val onConnectCloudClick: (() -> Unit)? = null,
    val onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    val onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    val onSwitchProfile: (() -> Unit)? = null,
    val onSettingsPageClick: ((pageName: String, title: String) -> Unit)? = null,
    val onHomescreenSettingsClick: () -> Unit = {},
    val onMetaScreenSettingsClick: () -> Unit = {},
    val onContinueWatchingSettingsClick: () -> Unit = {},
    val onDownloadActivityClick: () -> Unit = {},
    val onDownloadsSettingsClick: () -> Unit = {},
    val onPlayDownloaded: ((com.nuvio.app.features.downloads.DownloadItem) -> Unit)? = null,
    val onAddonsSettingsClick: () -> Unit = {},
    val onPluginsSettingsClick: () -> Unit = {},
    val onAccountSettingsClick: () -> Unit = {},
    val onSupportersContributorsSettingsClick: () -> Unit = {},
    val onLicensesAttributionsSettingsClick: () -> Unit = {},
    val onCheckForUpdatesClick: (() -> Unit)? = null,
    val onTestUpdateBannerClick: (() -> Unit)? = null,
    val onCollectionsSettingsClick: () -> Unit = {},
    val onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
    val onRequestedSettingsPageConsumed: () -> Unit = {},
    val onInitialHomeContentRendered: () -> Unit = {},
)

@Composable
internal fun AppTabHost(
    selectedTab: AppScreenTab,
    isTabletLayout: Boolean,
    requests: AppTabRequests,
    state: AppTabState,
    actions: AppTabActions,
    networkCondition: NetworkCondition,
    reconnectControlState: ReconnectControlState,
    onNetworkRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabStateHolder = rememberSaveableStateHolder()
    val stickyHeaderListTopPadding = rootListTopPaddingForStickyHeader(
        isTabletLayout = isTabletLayout,
        screenTopPadding = MaterialTheme.nuvio.spacing.screenTop,
    )

    Box(modifier = modifier.fillMaxSize()) {
        tabStateHolder.SaveableStateProvider(selectedTab.name) {
            when (selectedTab) {
                AppScreenTab.Home -> {
                    key(state.homeContentGeneration) {
                        HomeScreen(
                            modifier = Modifier.fillMaxSize(),
                            topPadding = stickyHeaderListTopPadding,
                            networkCondition = networkCondition,
                            reconnectControlState = reconnectControlState,
                            onNetworkRetry = onNetworkRetry,
                            animateCollectionGifs = state.animateHomeCollectionGifs,
                            scrollToTopRequests = requests.homeScrollToTopRequests,
                            presentationResetGeneration = state.homePresentationResetGeneration,
                            onPresentationResetConsumed = actions.onHomePresentationResetConsumed,
                            onCatalogClick = actions.onCatalogClick,
                            onPosterClick = actions.onPosterClick,
                            onPosterLongClick = actions.onPosterLongClick,
                            onContinueWatchingClick = actions.onContinueWatchingClick,
                            onContinueWatchingLongPress = actions.onContinueWatchingLongPress,
                            continueWatchingDisintegrationRequest = state.continueWatchingDisintegrationRequest,
                            onFolderClick = actions.onFolderClick,
                            onFirstCatalogRendered = actions.onInitialHomeContentRendered,
                        )
                    }
                }

                AppScreenTab.Search -> {
                    SearchScreen(
                        modifier = Modifier.fillMaxSize(),
                        listState = state.searchListState,
                        topPadding = stickyHeaderListTopPadding,
                        onPosterClick = actions.onPosterClick,
                        onPosterLongClick = actions.onPosterLongClick,
                        searchFocusRequestCount = state.searchFocusRequestCount,
                        scrollToTopRequests = requests.searchScrollToTopRequests,
                        networkCondition = networkCondition,
                        reconnectControlState = reconnectControlState,
                        onNetworkRetry = onNetworkRetry,
                    )
                }

                AppScreenTab.Library -> {
                    LibraryScreen(
                        modifier = Modifier.fillMaxSize(),
                        topPadding = stickyHeaderListTopPadding,
                        scrollToTopRequests = requests.libraryScrollToTopRequests,
                        onPosterClick = actions.onLibraryPosterClick,
                        onPosterLongClick = actions.onLibraryPosterLongClick,
                        onSectionViewAllClick = actions.onLibrarySectionViewAllClick,
                        onCloudFilePlay = actions.onCloudFilePlay,
                        onConnectCloudClick = actions.onConnectCloudClick,
                        onDownloadsClick = actions.onDownloadActivityClick,
                        onPlayDownloaded = actions.onPlayDownloaded,
                        openDownloadsRequest = state.openLibraryDownloadsRequest,
                        disintegrationRequest = state.libraryDisintegrationRequest,
                        networkCondition = networkCondition,
                        reconnectControlState = reconnectControlState,
                        onNetworkRetry = onNetworkRetry,
                    )
                }

                AppScreenTab.Settings -> {
                    SettingsScreen(
                        modifier = Modifier.fillMaxSize(),
                        rootActionRequests = requests.settingsRootActionRequests,
                        requestedPageName = state.requestedSettingsPageName,
                        onRequestedPageConsumed = actions.onRequestedSettingsPageConsumed,
                        rootActionsEnabled = state.rootActionsEnabled,
                        onNavigatePage = actions.onSettingsPageClick,
                        onSwitchProfile = actions.onSwitchProfile,
                        onHomescreenClick = actions.onHomescreenSettingsClick,
                        onMetaScreenClick = actions.onMetaScreenSettingsClick,
                        onContinueWatchingClick = actions.onContinueWatchingSettingsClick,
                        onDownloadsClick = actions.onDownloadsSettingsClick,
                        onAddonsClick = actions.onAddonsSettingsClick,
                        onPluginsClick = actions.onPluginsSettingsClick,
                        onAccountClick = actions.onAccountSettingsClick,
                        onSupportersContributorsClick = actions.onSupportersContributorsSettingsClick,
                        onLicensesAttributionsClick = actions.onLicensesAttributionsSettingsClick,
                        onCheckForUpdatesClick = actions.onCheckForUpdatesClick,
                        onTestUpdateBannerClick = actions.onTestUpdateBannerClick,
                        onCollectionsClick = actions.onCollectionsSettingsClick,
                    )
                }
            }
        }
    }
}

internal fun rootListTopPaddingForStickyHeader(
    isTabletLayout: Boolean,
    screenTopPadding: Dp,
): Dp? = if (isTabletLayout) screenTopPadding else null

@Composable
internal fun TabletFloatingBottomDock(
    selectedTab: AppScreenTab,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val homeLabel = stringResource(Res.string.compose_nav_home)
    val searchLabel = stringResource(Res.string.compose_nav_search)
    val libraryLabel = stringResource(Res.string.compose_nav_library)
    val settingsLabel = stringResource(Res.string.compose_settings_page_root)
    val labelStyle = MaterialTheme.typography.labelMedium
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s16)
            .padding(bottom = nuvioSafeBottomPadding(tokens.spacing.controlGap)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val indicatorWidth = NuvioTokens.Space.s48
        val itemHorizontalPadding = NuvioTokens.Space.s6
        val itemMinimumWidth = NuvioTokens.Space.s56
        val dockHorizontalPadding = NuvioTokens.Space.s10
        val itemPaddingPx = with(density) { (itemHorizontalPadding * 2).roundToPx() }
        val indicatorWidthPx = with(density) { indicatorWidth.roundToPx() }
        val compactItemWidthPx = maxOf(
            with(density) { itemMinimumWidth.roundToPx() },
            indicatorWidthPx + itemPaddingPx,
        )
        val dockPaddingPx = with(density) { (dockHorizontalPadding * 2).roundToPx() }
        val dockGapsPx = with(density) { (tokens.spacing.controlGap * 3).roundToPx() }
        fun labeledItemWidthPx(label: String): Int = maxOf(
            compactItemWidthPx,
            maxOf(
                indicatorWidthPx,
                textMeasurer.measure(
                    text = AnnotatedString(label),
                    style = labelStyle,
                    maxLines = 1,
                ).size.width,
            ) + itemPaddingPx,
        )
        val primaryLabeledWidthPx = listOf(homeLabel, searchLabel, libraryLabel)
            .sumOf(::labeledItemWidthPx)
        val settingsCompactWidthPx = dockPaddingPx + dockGapsPx +
            primaryLabeledWidthPx + compactItemWidthPx
        val fullWidthPx = dockPaddingPx + dockGapsPx + primaryLabeledWidthPx +
            labeledItemWidthPx(settingsLabel)
        val presentation = tabletDockPresentationForMeasuredContent(
            availableWidthPx = with(density) { maxWidth.roundToPx() },
            fullWidthPx = fullWidthPx,
            settingsCompactWidthPx = settingsCompactWidthPx,
        )
        val showPrimaryLabels = presentation != TabletDockPresentation.Compact
        val showSettingsLabel = presentation == TabletDockPresentation.Full

        Surface(
            color = tokens.colors.surface.copy(alpha = tokens.opacity.visible - tokens.opacity.subtle),
            shape = tokens.shapes.chip,
            tonalElevation = tokens.elevation.playerControls,
            shadowElevation = tokens.elevation.overlay,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = dockHorizontalPadding,
                    vertical = tokens.spacing.controlGap,
                ),
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabletDockDestination(
                    label = homeLabel,
                    showLabel = showPrimaryLabels,
                    reserveLabelSpace = showPrimaryLabels,
                    selected = selectedTab == AppScreenTab.Home,
                    onClick = { onTabSelected(AppScreenTab.Home) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = stringResource(Res.string.compose_nav_home),
                            modifier = Modifier.size(NuvioTokens.Space.s18),
                            tint = if (selectedTab == AppScreenTab.Home) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    },
                )
                TabletDockDestination(
                    label = searchLabel,
                    showLabel = showPrimaryLabels,
                    reserveLabelSpace = showPrimaryLabels,
                    selected = selectedTab == AppScreenTab.Search,
                    onClick = { onTabSelected(AppScreenTab.Search) },
                    icon = {
                        Icon(
                            painter = painterResource(Res.drawable.sidebar_search),
                            contentDescription = stringResource(Res.string.compose_nav_search),
                            modifier = Modifier.size(NuvioTokens.Space.s18),
                            tint = if (selectedTab == AppScreenTab.Search) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    },
                )
                TabletDockDestination(
                    label = libraryLabel,
                    showLabel = showPrimaryLabels,
                    reserveLabelSpace = showPrimaryLabels,
                    selected = selectedTab == AppScreenTab.Library,
                    onClick = { onTabSelected(AppScreenTab.Library) },
                    icon = {
                        Icon(
                            painter = painterResource(Res.drawable.sidebar_library),
                            contentDescription = stringResource(Res.string.compose_nav_library),
                            modifier = Modifier.size(NuvioTokens.Space.s18),
                            tint = if (selectedTab == AppScreenTab.Library) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    },
                )
                ProfileSwitcherTab(
                    selected = selectedTab == AppScreenTab.Settings,
                    onClick = { onTabSelected(AppScreenTab.Settings) },
                    onProfileSelected = onProfileSelected,
                    onAddProfileRequested = onAddProfileRequested,
                    triggerContent = { selected ->
                        TabletDockDestinationContent(
                            label = settingsLabel,
                            showLabel = showSettingsLabel,
                            reserveLabelSpace = showPrimaryLabels,
                            selected = selected,
                            icon = {
                                ActiveProfileMiniAvatar(
                                    profile = profileState.activeProfile,
                                    avatars = avatars,
                                    selected = selected,
                                    size = 24,
                                )
                            },
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RootConnectionControl(
    condition: NetworkCondition,
    state: ReconnectControlState,
    onRetry: () -> Unit,
    showStatusGraphic: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (state == ReconnectControlState.Hidden) return
    val tokens = MaterialTheme.nuvio
    val reconnectText = stringResource(Res.string.network_reconnect)
    val restoringText = stringResource(Res.string.network_restoring_short)
    val statusText = when (state) {
        ReconnectControlState.Probing -> stringResource(Res.string.network_reconnecting)
        ReconnectControlState.Restoring -> stringResource(Res.string.network_restoring_content)
        ReconnectControlState.Failed -> stringResource(Res.string.network_restore_failed_reconnect)
        ReconnectControlState.Offline -> reconnectText
        ReconnectControlState.Hidden -> return
    }
    val conditionText = if (condition == NetworkCondition.Online) {
        stringResource(Res.string.network_online)
    } else {
        condition.titleForEmptyState()
    }
    val tooltipText = "$conditionText · $statusText"
    val actionEnabled = state == ReconnectControlState.Offline || state == ReconnectControlState.Failed
    val visual = rootConnectionVisual(state, showStatusGraphic)
    val labelStyle = MaterialTheme.typography.labelMedium
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val horizontalPadding = NuvioTokens.Space.s8
    val contentGap = NuvioTokens.Space.s4
    val labelWidthPx = if (showStatusGraphic) {
        maxOf(
            textMeasurer.measure(AnnotatedString(reconnectText), labelStyle, maxLines = 1).size.width,
            textMeasurer.measure(AnnotatedString(restoringText), labelStyle, maxLines = 1).size.width,
        )
    } else {
        textMeasurer.measure(AnnotatedString(reconnectText), labelStyle, maxLines = 1).size.width
    }
    val controlWidth = with(density) {
        val fixedContentWidthPx = if (showStatusGraphic) {
            labelWidthPx + NuvioTokens.Icon.sm.roundToPx() + contentGap.roundToPx()
        } else {
            labelWidthPx
        }
        (fixedContentWidthPx + horizontalPadding.roundToPx() * 2).toDp()
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isBusy = state == ReconnectControlState.Probing || state == ReconnectControlState.Restoring
    val containerColor by animateColorAsState(
        targetValue = when {
            isPressed -> tokens.colors.overlayPressed.compositeOver(tokens.colors.surface)
            isBusy -> tokens.colors.overlaySelected
            else -> Color.Transparent
        },
        label = "ReconnectButtonColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isBusy || isPressed) tokens.colors.textPrimary else tokens.colors.textMuted,
        label = "ReconnectContentColor",
    )

    TooltipBox(
        modifier = modifier,
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip {
                Text(tooltipText)
            }
        },
        state = rememberTooltipState(),
    ) {
        Surface(
            color = containerColor,
            shape = tokens.shapes.compactCard,
            modifier = Modifier
                .width(controlWidth)
                .heightIn(min = 44.dp)
                .semantics { contentDescription = tooltipText }
                .clickable(
                    enabled = actionEnabled,
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClickLabel = reconnectText,
                    role = Role.Button,
                    onClick = onRetry,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = horizontalPadding,
                        vertical = NuvioTokens.Space.s8,
                    ),
                horizontalArrangement = Arrangement.spacedBy(contentGap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (visual) {
                    RootConnectionVisual.RestoringWithSpinner -> {
                        Text(
                            text = restoringText,
                            style = labelStyle,
                            color = contentColor,
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.size(NuvioTokens.Icon.sm),
                            color = contentColor,
                            strokeWidth = 2.dp,
                        )
                    }
                    RootConnectionVisual.Spinner -> CircularProgressIndicator(
                        modifier = Modifier.size(NuvioTokens.Icon.sm),
                        color = contentColor,
                        strokeWidth = 2.dp,
                    )
                    RootConnectionVisual.ReconnectWithIcon -> {
                        Text(
                            text = reconnectText,
                            style = labelStyle,
                            color = contentColor,
                        )
                        Icon(
                            imageVector = Icons.Rounded.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(NuvioTokens.Icon.sm),
                            tint = contentColor,
                        )
                    }
                    RootConnectionVisual.ReconnectText -> Text(
                        text = reconnectText,
                        style = labelStyle,
                        color = contentColor,
                    )
                    RootConnectionVisual.Hidden -> Unit
                }
            }
        }
    }
}

internal fun ContinueWatchingItem.isCloudLibraryContinueWatchingItem(): Boolean =
    parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)

@Composable
private fun TabletDockDestination(
    label: String,
    showLabel: Boolean,
    reserveLabelSpace: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .clip(tokens.shapes.compactCard)
            .clickable(
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        TabletDockDestinationContent(
            label = label,
            showLabel = showLabel,
            reserveLabelSpace = reserveLabelSpace,
            selected = selected,
            icon = icon,
        )
    }
}

@Composable
private fun TabletDockDestinationContent(
    label: String,
    showLabel: Boolean,
    reserveLabelSpace: Boolean,
    selected: Boolean,
    icon: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .widthIn(min = NuvioTokens.Space.s56)
            .padding(horizontal = NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = if (selected) tokens.colors.overlaySelected else Color.Transparent,
            shape = tokens.shapes.chip,
            tonalElevation = if (selected) tokens.elevation.raised else tokens.elevation.flat,
            modifier = Modifier
                .width(NuvioTokens.Space.s48)
                .height(NuvioTokens.Space.s28),
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        if (reserveLabelSpace) {
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s2))
            if (showLabel) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
                    maxLines = 1,
                )
            } else {
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s18))
            }
        }
    }
}

@Composable
internal fun AppLoadingContent(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppBrandWordmark(
                contentDescription = stringResource(Res.string.app_brand_name),
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(44.dp),
            )
            Spacer(modifier = Modifier.height(tokens.spacing.sectionGap))
            NuvioLoadingIndicator(color = tokens.colors.accent)
        }
    }
}

@Composable
internal fun AppLaunchOverlay(
    profile: NuvioProfile?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.zIndex(NuvioTokens.Z.dialog),
    ) {
        ProfileBackgroundBackdrop(
            profile = profile,
            modifier = Modifier.fillMaxSize(),
        )
        AppLoadingContent(modifier = Modifier.fillMaxSize())
    }
}
