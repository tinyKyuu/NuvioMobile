package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal sealed interface DownloadLibraryMenuTarget {
    val title: String
    val bytes: Long

    data class Movie(val movie: CompletedDownloadMovie) : DownloadLibraryMenuTarget {
        override val title: String = movie.item.title
        override val bytes: Long = movie.bytes
    }

    data class Show(val show: CompletedDownloadShow) : DownloadLibraryMenuTarget {
        override val title: String = show.title
        override val bytes: Long = show.bytes
    }

    data class Episode(val episode: DownloadItem) : DownloadLibraryMenuTarget {
        override val title: String = episode.episodeTitle?.takeIf(String::isNotBlank) ?: episode.title
        override val bytes: Long = episode.knownCompletedBytes()
    }
}

@Composable
internal fun BoxScope.DownloadLibraryManagementHost(
    items: List<DownloadItem>,
    state: DownloadLibraryManagementState,
    isTablet: Boolean,
    menuTarget: DownloadLibraryMenuTarget?,
    onMenuDismiss: () -> Unit,
    onStateChange: (DownloadLibraryManagementState) -> Unit,
    onPlay: (DownloadItem) -> Unit,
) {
    val library = remember(items) { buildCompletedDownloadLibrary(items) }
    val summary = remember(state.selectedIds, items) {
        summarizeCompletedDownloadSelection(state.selectedIds, items)
    }
    var pendingRemovalIds by remember { mutableStateOf<Set<String>?>(null) }
    var panelMenuTarget by remember { mutableStateOf<DownloadLibraryMenuTarget?>(null) }
    var removalFeedback by remember { mutableStateOf<DownloadBatchRemovalResult?>(null) }

    fun dispatch(event: DownloadLibraryManagementEvent) {
        onStateChange(reduceDownloadLibraryManagement(state, event))
    }

    if (state.isManaging) {
        DownloadManagerCollapsedBar(
            summary = summary,
            onExpand = { dispatch(DownloadLibraryManagementEvent.ExpandRoot) },
            onClear = { dispatch(DownloadLibraryManagementEvent.Clear) },
            onRemove = { pendingRemovalIds = summary.selectedIds },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }

    if (state.isExpanded) {
        DownloadManagerExpandedContainer(
            isTablet = isTablet,
            onDismiss = { dispatch(DownloadLibraryManagementEvent.Collapse) },
        ) {
            DownloadManagerPanel(
                library = library,
                state = state,
                summary = summary,
                onEvent = { event -> dispatch(event) },
                onMenu = { target -> panelMenuTarget = target },
                onEpisodeMenu = { episode -> panelMenuTarget = DownloadLibraryMenuTarget.Episode(episode) },
                onRemove = { pendingRemovalIds = summary.selectedIds },
            )
        }
    }

    if (panelMenuTarget != null) {
        DownloadLibraryActionSheet(
            target = panelMenuTarget!!,
            onDismiss = { panelMenuTarget = null },
            onPlay = onPlay,
            onSelect = { ids -> dispatch(DownloadLibraryManagementEvent.Add(ids)) },
            onChooseEpisodes = { showId -> dispatch(DownloadLibraryManagementEvent.OpenShow(showId)) },
            onRemove = { ids ->
                panelMenuTarget = null
                pendingRemovalIds = ids
            },
        )
    }

    if (menuTarget != null) {
        DownloadLibraryActionSheet(
            target = menuTarget,
            onDismiss = onMenuDismiss,
            onPlay = onPlay,
            onSelect = { ids ->
                dispatch(DownloadLibraryManagementEvent.Add(ids))
                onMenuDismiss()
            },
            onChooseEpisodes = { showId ->
                dispatch(DownloadLibraryManagementEvent.OpenShow(showId))
                onMenuDismiss()
            },
            onRemove = { ids ->
                pendingRemovalIds = ids
                onMenuDismiss()
            },
        )
    }

    val pending = pendingRemovalIds
    if (pending != null) {
        val pendingSummary = summarizeCompletedDownloadSelection(pending, items)
        if (pendingSummary.fileCount == 0) {
            androidx.compose.runtime.LaunchedEffect(pending, items) {
                pendingRemovalIds = null
            }
        } else {
            NuvioStatusModal(
                title = stringResource(Res.string.downloads_bulk_delete_title),
                message = stringResource(
                    Res.string.downloads_remove_confirm_message,
                    pendingSummary.fileCount,
                    formatDownloadBytes(pendingSummary.bytes),
                ),
                isVisible = true,
                confirmText = stringResource(Res.string.downloads_remove_download),
                dismissText = stringResource(Res.string.action_cancel),
                onConfirm = {
                    val result = DownloadsRepository.cancelDownloads(pendingSummary.selectedIds)
                    onStateChange(applyDownloadRemovalResult(state, result))
                    removalFeedback = result
                    pendingRemovalIds = null
                },
                onDismiss = { pendingRemovalIds = null },
            )
        }
    }

    val feedback = removalFeedback
    if (feedback != null) {
        val message = if (feedback.failures.isEmpty()) {
            stringResource(
                Res.string.downloads_remove_succeeded,
                feedback.removedCount,
                formatDownloadBytes(feedback.bytesReclaimed),
            )
        } else {
            stringResource(
                Res.string.downloads_remove_failed,
                feedback.removedCount,
                feedback.failedIds.size,
            )
        }
        androidx.compose.runtime.LaunchedEffect(feedback) {
            NuvioToastController.show(message)
            removalFeedback = null
        }
    }
}

@Composable
private fun DownloadManagerCollapsedBar(
    summary: CompletedDownloadSelectionSummary,
    onExpand: () -> Unit,
    onClear: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier
            .padding(
                start = NuvioTokens.Space.s12,
                end = NuvioTokens.Space.s12,
                bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s14),
            )
            .pointerInput(Unit) {
                var drag = 0f
                detectVerticalDragGestures(
                    onVerticalDrag = { _, amount -> drag += amount },
                    onDragEnd = {
                        if (drag < -24f) onExpand()
                        drag = 0f
                    },
                )
            },
        shape = tokens.shapes.card,
        color = tokens.colors.surfaceElevated,
        tonalElevation = tokens.elevation.modal,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s10),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.ExpandLess,
                contentDescription = stringResource(Res.string.downloads_manager_expand),
                tint = tokens.colors.accent,
            )
            Text(
                text = downloadSelectionSummaryText(summary),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(enabled = summary.fileCount > 0, onClick = onClear) {
                Text(stringResource(Res.string.action_clear))
            }
            TextButton(
                enabled = summary.fileCount > 0,
                onClick = onRemove,
                colors = ButtonDefaults.textButtonColors(contentColor = tokens.colors.danger),
            ) {
                Text(stringResource(Res.string.downloads_remove_download))
            }
        }
    }
}

@Composable
private fun downloadSelectionSummaryText(summary: CompletedDownloadSelectionSummary): String =
    if (summary.fileCount == 0) {
        stringResource(Res.string.downloads_manager_selection_empty)
    } else {
        stringResource(
            Res.string.downloads_manager_selection_mixed,
            summary.movieCount,
            summary.episodeCount,
            formatDownloadBytes(summary.bytes),
        )
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadManagerExpandedContainer(
    isTablet: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (isTablet) {
        BasicAlertDialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 520.dp, max = 760.dp)
                    .fillMaxHeight(0.82f),
                shape = MaterialTheme.nuvio.shapes.dialog,
                color = MaterialTheme.nuvio.colors.surfaceSheet,
                tonalElevation = MaterialTheme.nuvio.elevation.modal,
            ) {
                content()
            }
        }
    } else {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        NuvioModalBottomSheet(
            onDismissRequest = {
                scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) }
            },
            sheetState = sheetState,
            fullHeight = true,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun DownloadManagerPanel(
    library: CompletedDownloadLibrary,
    state: DownloadLibraryManagementState,
    summary: CompletedDownloadSelectionSummary,
    onEvent: (DownloadLibraryManagementEvent) -> Unit,
    onMenu: (DownloadLibraryMenuTarget) -> Unit,
    onEpisodeMenu: (DownloadItem) -> Unit,
    onRemove: () -> Unit,
) {
    val route = state.route
    val title = when (route) {
        DownloadManagerRoute.Root -> stringResource(Res.string.downloads_manager_all)
        is DownloadManagerRoute.Show -> library.shows.firstOrNull { it.showId == route.showId }?.title.orEmpty()
        is DownloadManagerRoute.Season -> if (route.seasonNumber == 0) {
            stringResource(Res.string.episodes_specials)
        } else {
            stringResource(Res.string.episodes_season, route.seasonNumber)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onEvent(DownloadLibraryManagementEvent.Back) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.action_back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = downloadSelectionSummaryText(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                enabled = summary.fileCount > 0,
                onClick = { onEvent(DownloadLibraryManagementEvent.Clear) },
            ) {
                Text(stringResource(Res.string.action_clear))
            }
        }
        HorizontalDivider(color = MaterialTheme.nuvio.colors.borderSubtle)
        LazyColumn(modifier = Modifier.weight(1f)) {
            when (route) {
                DownloadManagerRoute.Root -> {
                    if (library.movies.isEmpty() && library.shows.isEmpty()) {
                        item(key = "downloads-manager-empty") {
                            Text(
                                text = stringResource(Res.string.downloads_manager_empty),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 32.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.nuvio.colors.textMuted,
                            )
                        }
                    }
                    items(library.movies, key = { "movie:${it.item.id}" }) { movie ->
                        DownloadManagerMovieRow(
                            movie = movie,
                            selected = movie.item.id in state.selectedIds,
                            onToggle = {
                                onEvent(DownloadLibraryManagementEvent.Toggle(setOf(movie.item.id)))
                            },
                            onMenu = { onMenu(movie.let(DownloadLibraryMenuTarget::Movie)) },
                        )
                    }
                    items(library.shows, key = { "show:${it.showId}" }) { show ->
                        DownloadManagerShowRow(
                            show = show,
                            selection = downloadGroupSelectionState(state.selectedIds, show.downloadIds),
                            onToggle = {
                                onEvent(DownloadLibraryManagementEvent.Toggle(show.downloadIds))
                            },
                            onOpen = { onEvent(DownloadLibraryManagementEvent.OpenShow(show.showId)) },
                            onMenu = { onMenu(DownloadLibraryMenuTarget.Show(show)) },
                        )
                    }
                }
                is DownloadManagerRoute.Show -> {
                    val show = library.shows.firstOrNull { it.showId == route.showId }
                    if (show != null) {
                        items(show.seasons, key = { "season:${show.showId}:${it.seasonNumber}" }) { season ->
                            DownloadManagerSeasonRow(
                                season = season,
                                selection = downloadGroupSelectionState(state.selectedIds, season.downloadIds),
                                onToggle = {
                                    onEvent(DownloadLibraryManagementEvent.Toggle(season.downloadIds))
                                },
                                onOpen = {
                                    onEvent(
                                        DownloadLibraryManagementEvent.OpenSeason(
                                            show.showId,
                                            season.seasonNumber,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                is DownloadManagerRoute.Season -> {
                    val season = library.shows
                        .firstOrNull { it.showId == route.showId }
                        ?.seasons
                        ?.firstOrNull { it.seasonNumber == route.seasonNumber }
                    if (season != null) {
                        items(season.episodes, key = DownloadItem::id) { episode ->
                            DownloadManagerEpisodeRow(
                                episode = episode,
                                selected = episode.id in state.selectedIds,
                                onToggle = {
                                    onEvent(DownloadLibraryManagementEvent.Toggle(setOf(episode.id)))
                                },
                                onMenu = { onEpisodeMenu(episode) },
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.nuvio.colors.borderSubtle)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = downloadSelectionSummaryText(summary),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                enabled = summary.fileCount > 0,
                onClick = onRemove,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.nuvio.colors.danger,
                    contentColor = MaterialTheme.nuvio.colors.textInverse,
                ),
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(Res.string.downloads_remove_download))
            }
        }
    }
}

@Composable
private fun DownloadManagerMovieRow(
    movie: CompletedDownloadMovie,
    selected: Boolean,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
) {
    DownloadManagerRow(
        title = movie.item.title,
        subtitle = formatDownloadBytes(movie.bytes),
        selection = if (selected) DownloadGroupSelectionState.Full else DownloadGroupSelectionState.None,
        onToggle = onToggle,
        onMenu = onMenu,
    )
}

@Composable
private fun DownloadManagerShowRow(
    show: CompletedDownloadShow,
    selection: DownloadGroupSelectionState,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    DownloadManagerRow(
        title = show.title,
        subtitle = stringResource(
            Res.string.downloads_show_summary,
            show.episodes.size,
            formatDownloadBytes(show.bytes),
        ),
        selection = selection,
        onToggle = onToggle,
        onOpen = onOpen,
        onMenu = onMenu,
    )
}

@Composable
private fun DownloadManagerSeasonRow(
    season: CompletedDownloadSeason,
    selection: DownloadGroupSelectionState,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    DownloadManagerRow(
        title = if (season.seasonNumber == 0) {
            stringResource(Res.string.episodes_specials)
        } else {
            stringResource(Res.string.episodes_season, season.seasonNumber)
        },
        subtitle = stringResource(
            Res.string.downloads_season_summary,
            season.episodes.size,
            formatDownloadBytes(season.bytes),
        ),
        selection = selection,
        onToggle = onToggle,
        onOpen = onOpen,
    )
}

@Composable
private fun DownloadManagerEpisodeRow(
    episode: DownloadItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
) {
    val code = "S${episode.seasonNumber ?: 0}E${episode.episodeNumber ?: 0}"
    DownloadManagerRow(
        title = episode.episodeTitle?.takeIf(String::isNotBlank) ?: episode.title,
        subtitle = stringResource(
            Res.string.downloads_episode_metadata,
            code,
            formatDownloadBytes(episode.knownCompletedBytes()),
        ),
        selection = if (selected) DownloadGroupSelectionState.Full else DownloadGroupSelectionState.None,
        onToggle = onToggle,
        onMenu = onMenu,
    )
}

@Composable
private fun DownloadManagerRow(
    title: String,
    subtitle: String,
    selection: DownloadGroupSelectionState,
    onToggle: () -> Unit,
    onOpen: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TriStateCheckbox(
            state = selection.toToggleableState(),
            onClick = onToggle,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onOpen != null) {
            IconButton(onClick = onOpen) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(Res.string.downloads_choose_episodes),
                )
            }
        }
        if (onMenu != null) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.downloads_menu, title))
            }
        }
    }
}

private fun DownloadGroupSelectionState.toToggleableState(): ToggleableState = when (this) {
    DownloadGroupSelectionState.None -> ToggleableState.Off
    DownloadGroupSelectionState.Partial -> ToggleableState.Indeterminate
    DownloadGroupSelectionState.Full -> ToggleableState.On
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadLibraryActionSheet(
    target: DownloadLibraryMenuTarget,
    onDismiss: () -> Unit,
    onPlay: (DownloadItem) -> Unit,
    onSelect: (Set<String>) -> Unit,
    onChooseEpisodes: (String) -> Unit,
    onRemove: (Set<String>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val exportFailedText = stringResource(Res.string.downloads_export_failed)
    fun dismissAfter(action: () -> Unit) {
        action()
        scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) }
    }
    NuvioModalBottomSheet(
        onDismissRequest = { scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) } },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = target.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (target) {
                        is DownloadLibraryMenuTarget.Movie -> formatDownloadBytes(target.bytes)
                        is DownloadLibraryMenuTarget.Show -> stringResource(
                            Res.string.downloads_show_summary,
                            target.show.episodes.size,
                            formatDownloadBytes(target.bytes),
                        )
                        is DownloadLibraryMenuTarget.Episode -> formatDownloadBytes(target.bytes)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            }
            when (target) {
                is DownloadLibraryMenuTarget.Movie -> {
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(Res.string.downloads_play_file),
                        onClick = { dismissAfter { onPlay(target.movie.item) } },
                    )
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.CheckCircle,
                        title = stringResource(Res.string.downloads_select),
                        onClick = { dismissAfter { onSelect(setOf(target.movie.item.id)) } },
                    )
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.DeleteOutline,
                        title = stringResource(Res.string.downloads_remove_download),
                        destructive = true,
                        onClick = { dismissAfter { onRemove(setOf(target.movie.item.id)) } },
                    )
                    NuvioBottomSheetDivider(modifier = Modifier.padding(top = 8.dp))
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.Share,
                        title = stringResource(Res.string.downloads_share),
                        onClick = {
                            dismissAfter {
                                if (!DownloadsRepository.exportDownload(target.movie.item)) {
                                    NuvioToastController.show(exportFailedText)
                                }
                            }
                        },
                    )
                }
                is DownloadLibraryMenuTarget.Show -> {
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        title = stringResource(Res.string.downloads_choose_episodes),
                        onClick = { dismissAfter { onChooseEpisodes(target.show.showId) } },
                    )
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.CheckCircle,
                        title = stringResource(
                            Res.string.downloads_select_all_episodes,
                            target.show.episodes.size,
                        ),
                        onClick = { dismissAfter { onSelect(target.show.downloadIds) } },
                    )
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.DeleteOutline,
                        title = stringResource(
                            Res.string.downloads_remove_all,
                            target.show.episodes.size,
                        ),
                        destructive = true,
                        onClick = { dismissAfter { onRemove(target.show.downloadIds) } },
                    )
                }
                is DownloadLibraryMenuTarget.Episode -> {
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(Res.string.downloads_play_file),
                        onClick = { dismissAfter { onPlay(target.episode) } },
                    )
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.CheckCircle,
                        title = stringResource(Res.string.downloads_select),
                        onClick = { dismissAfter { onSelect(setOf(target.episode.id)) } },
                    )
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.DeleteOutline,
                        title = stringResource(Res.string.downloads_remove_download),
                        destructive = true,
                        onClick = { dismissAfter { onRemove(setOf(target.episode.id)) } },
                    )
                    NuvioBottomSheetDivider(modifier = Modifier.padding(top = 8.dp))
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.Share,
                        title = stringResource(Res.string.downloads_share),
                        onClick = {
                            dismissAfter {
                                if (!DownloadsRepository.exportDownload(target.episode)) {
                                    NuvioToastController.show(exportFailedText)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

internal fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}
