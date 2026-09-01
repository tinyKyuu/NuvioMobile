package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    initialShowId: String? = null,
    onNavigateToShow: ((showId: String, title: String) -> Unit)? = null,
    onBackFromShow: (() -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var downloadPendingDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showManagement by rememberSaveable { mutableStateOf(false) }
    var completedSortName by rememberSaveable {
        mutableStateOf(CompletedDownloadSort.RecentlyAdded.name)
    }
    val completedSort = CompletedDownloadSort.entries.firstOrNull { it.name == completedSortName }
        ?: CompletedDownloadSort.RecentlyAdded
    val networkPolicy by DownloadsNetworkPolicyRepository.policy.collectAsStateWithLifecycle()
    val exportFailedText = stringResource(Res.string.downloads_export_failed)

    val completedEpisodes = remember(uiState.items) {
        uiState.completedItems
            .filter { it.isEpisode }
            .sortedForSeriesDownloads()
    }

    val selectedShowTitle = remember(selectedShowId, completedEpisodes) {
        selectedShowId?.let { showId ->
            completedEpisodes.firstOrNull { it.parentMetaId == showId }?.title
        }
    }

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = if (selectedShowId == null) {
                    stringResource(Res.string.compose_settings_root_downloads_title)
                } else {
                    selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
                },
                onBack = {
                    if (selectedShowId != null) {
                        onBackFromShow?.invoke() ?: run { selectedShowId = null }
                    } else {
                        onBack()
                    }
                },
                actions = {
                    if (selectedShowId == null) {
                        IconButton(onClick = { showManagement = !showManagement }) {
                            Icon(
                                imageVector = Icons.Rounded.Storage,
                                contentDescription = stringResource(Res.string.downloads_manage_storage),
                                tint = MaterialTheme.nuvio.colors.textPrimary,
                            )
                        }
                    }
                },
            )
        }

        if (selectedShowId == null) {
            downloadsRootContent(
                uiState = uiState,
                showManagement = showManagement,
                networkPolicy = networkPolicy,
                onNetworkPolicyChanged = DownloadsNetworkPolicyRepository::update,
                completedSort = completedSort,
                onCompletedSortChanged = { completedSortName = it.name },
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                },
                onDeleteDownload = { downloadPendingDeletionId = it },
                onExportDownload = { item ->
                    val localFileUri = DownloadsRepository.playableLocalFileUri(item)
                    if (localFileUri == null || !DownloadsPlatformDownloader.exportFile(localFileUri)) {
                        NuvioToastController.show(exportFailedText)
                    }
                },
            )
        } else {
            downloadsShowContent(
                showId = selectedShowId.orEmpty(),
                episodes = completedEpisodes,
                onOpenDownload = onOpenDownload,
                onDeleteDownload = { downloadPendingDeletionId = it },
                onExportDownload = { item ->
                    val localFileUri = DownloadsRepository.playableLocalFileUri(item)
                    if (localFileUri == null || !DownloadsPlatformDownloader.exportFile(localFileUri)) {
                        NuvioToastController.show(exportFailedText)
                    }
                },
            )
        }
    }

    val pendingDeletionId = downloadPendingDeletionId
    if (pendingDeletionId != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.cancelDownload(pendingDeletionId)
                downloadPendingDeletionId = null
            },
            onDismiss = { downloadPendingDeletionId = null },
        )
    }
}

private fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    showManagement: Boolean,
    networkPolicy: DownloadNetworkPolicy,
    onNetworkPolicyChanged: (DownloadNetworkPolicy) -> Unit,
    completedSort: CompletedDownloadSort,
    onCompletedSortChanged: (CompletedDownloadSort) -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onExportDownload: (DownloadItem) -> Unit,
) {
    val currentDownloads = currentDownloadsForDisplay(uiState.items)
    val movies = completedMoviesForDisplay(uiState.items, completedSort)
    val shows = completedShowsForDisplay(uiState.items, completedSort)

    if (showManagement) {
        item {
            DownloadsManagementCard(
                items = uiState.items,
                policy = networkPolicy,
                onPolicyChanged = onNetworkPolicyChanged,
            )
        }
    }

    if (currentDownloads.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_active))
        }
        items(
            items = currentDownloads,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
                onExport = { onExportDownload(item) },
            )
        }
    }

    if (movies.isNotEmpty() || shows.isNotEmpty()) {
        item {
            DownloadedSectionHeader(
                selectedSort = completedSort,
                onSortChanged = onCompletedSortChanged,
            )
        }
    }

    if (movies.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_movies))
        }
        items(
            items = movies,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
                onExport = { onExportDownload(item) },
            )
        }
    }

    if (shows.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_shows))
        }
        items(
            items = shows,
            key = { it.representative.parentMetaId },
        ) { group ->
            val item = group.representative
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable { onOpenShow(item.parentMetaId, item.title) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                Res.string.downloads_episode_count,
                                group.episodes.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (uiState.items.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DownloadedSectionHeader(
    selectedSort: CompletedDownloadSort,
    onSortChanged: (CompletedDownloadSort) -> Unit,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.downloads_section_downloaded),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )

        Box {
            TextButton(onClick = { sortMenuExpanded = true }) {
                Text(
                    text = completedSortLabel(selectedSort),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = stringResource(Res.string.downloads_sort_content_description),
                )
            }
            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false },
            ) {
                CompletedDownloadSort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(completedSortLabel(sort)) },
                        onClick = {
                            onSortChanged(sort)
                            sortMenuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun completedSortLabel(sort: CompletedDownloadSort): String = when (sort) {
    CompletedDownloadSort.RecentlyAdded -> stringResource(Res.string.downloads_sort_recently_added)
    CompletedDownloadSort.TitleAscending -> stringResource(Res.string.downloads_sort_title_ascending)
    CompletedDownloadSort.LargestFirst -> stringResource(Res.string.downloads_sort_largest_first)
    CompletedDownloadSort.SmallestFirst -> stringResource(Res.string.downloads_sort_smallest_first)
}

private fun LazyListScope.downloadsShowContent(
    showId: String,
    episodes: List<DownloadItem>,
    onOpenDownload: (DownloadItem) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onExportDownload: (DownloadItem) -> Unit,
) {
    val showEpisodes = episodes
        .filter { it.parentMetaId == showId }
        .sortedForSeriesDownloads()

    val seasons = showEpisodes
        .groupBy { it.seasonNumber ?: 0 }
        .toList()
        .sortedWith(
            compareBy<Pair<Int, List<DownloadItem>>> { (season, _) ->
                if (season == 0) 0 else 1
            }.thenBy { (season, _) -> if (season == 0) 0 else season },
        )

    if (seasons.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    seasons.forEach { (seasonNumber, entries) ->
        item {
            SectionTitle(
                if (seasonNumber == 0) {
                    stringResource(Res.string.episodes_specials)
                } else {
                    stringResource(Res.string.episodes_season, seasonNumber)
                },
            )
        }

        val sortedEpisodes = entries.sortedForSeriesDownloads()

        items(
            items = sortedEpisodes,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
                onExport = { onExportDownload(item) },
            )
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(
        item = item,
        displayTitle = displayTitle,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(enabled = item.isPlayable, onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = displaySubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (item.status) {
                        DownloadStatus.Queued,
                        DownloadStatus.Downloading,
                        DownloadStatus.WaitingForNetwork,
                        -> {
                            IconButton(onClick = onPause) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = stringResource(Res.string.compose_action_pause),
                                )
                            }
                        }
                        DownloadStatus.Paused -> {
                            IconButton(onClick = onResume) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_resume),
                                )
                            }
                        }
                        DownloadStatus.Failed -> {
                            IconButton(onClick = onRetry) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.action_retry),
                                )
                            }
                        }
                        DownloadStatus.Finalizing -> Unit
                        DownloadStatus.Completed -> {
                            IconButton(onClick = onOpen) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_play),
                                )
                            }
                            IconButton(onClick = onExport) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = stringResource(Res.string.downloads_export),
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(Res.string.action_delete),
                        )
                    }
                }
            }

            if (
                item.status == DownloadStatus.Downloading ||
                item.status == DownloadStatus.WaitingForNetwork
            ) {
                if (item.totalBytes != null && item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = item.progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsManagementCard(
    items: List<DownloadItem>,
    policy: DownloadNetworkPolicy,
    onPolicyChanged: (DownloadNetworkPolicy) -> Unit,
) {
    val storedBytes = items.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_manage_storage),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    Res.string.downloads_manage_storage_description,
                    items.size,
                    formatBytes(storedBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DownloadPolicySwitch(
                title = stringResource(Res.string.downloads_network_wifi_only),
                description = stringResource(Res.string.downloads_network_wifi_only_description),
                checked = policy.wifiOnly,
                onCheckedChange = { onPolicyChanged(policy.copy(wifiOnly = it)) },
            )
            DownloadPolicySwitch(
                title = stringResource(Res.string.downloads_network_allow_cellular),
                description = stringResource(Res.string.downloads_network_allow_cellular_description),
                checked = policy.allowCellular,
                enabled = !policy.wifiOnly,
                onCheckedChange = { onPolicyChanged(policy.copy(allowCellular = it)) },
            )
            DownloadPolicySwitch(
                title = stringResource(Res.string.downloads_network_allow_expensive),
                description = stringResource(Res.string.downloads_network_allow_expensive_description),
                checked = policy.allowExpensiveNetworks,
                onCheckedChange = { onPolicyChanged(policy.copy(allowExpensiveNetworks = it)) },
            )
            DownloadPolicySwitch(
                title = stringResource(Res.string.downloads_network_allow_constrained),
                description = stringResource(Res.string.downloads_network_allow_constrained_description),
                checked = policy.allowConstrainedNetworks,
                onCheckedChange = { onPolicyChanged(policy.copy(allowConstrainedNetworks = it)) },
            )
            Text(
                text = stringResource(Res.string.downloads_network_policy_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadPolicySwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

private fun DownloadItem.displayTitle(): String =
    if (isEpisode) {
        episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }

@Composable
private fun downloadDisplaySubtitle(
    item: DownloadItem,
    displayTitle: String,
): String {
    val seasonNumber = item.seasonNumber
    val episodeNumber = item.episodeNumber
    if (seasonNumber == null || episodeNumber == null) {
        return item.displaySubtitle
    }

    val episodeCode = stringResource(
        Res.string.compose_player_episode_code_full,
        seasonNumber,
        episodeNumber,
    )
    return listOf(
        episodeCode,
        item.episodeTitle?.trim().orEmpty().takeIf { it.isNotBlank() && it != displayTitle },
        item.title.trim().takeIf { it.isNotBlank() && it != displayTitle },
    ).filterNotNull().joinToString(" • ")
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun statusText(item: DownloadItem): String {
    val size = if (item.totalBytes != null && item.totalBytes > 0L) {
        val progressPercent = ((item.downloadedBytes.toDouble() / item.totalBytes.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
        "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)} • $progressPercent%"
    } else {
        formatBytes(item.downloadedBytes)
    }

    return when (item.status) {
        DownloadStatus.Queued -> stringResource(Res.string.downloads_status_queued)
        DownloadStatus.Downloading -> stringResource(Res.string.downloads_status_downloading, size)
        DownloadStatus.WaitingForNetwork -> stringResource(
            Res.string.downloads_status_waiting_for_network,
            size,
        )
        DownloadStatus.Paused -> stringResource(Res.string.downloads_status_paused, size)
        DownloadStatus.Finalizing -> stringResource(Res.string.downloads_status_finalizing, size)
        DownloadStatus.Completed -> stringResource(
            Res.string.downloads_status_completed,
            formatBytes(item.totalBytes ?: item.downloadedBytes),
        )
        DownloadStatus.Failed -> item.errorMessage ?: stringResource(Res.string.downloads_status_failed)
    }
}

private fun formatBytes(bytes: Long): String {
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
