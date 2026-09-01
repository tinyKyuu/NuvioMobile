package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerDownloadActionSheet(
    item: DownloadItem?,
    exactSource: Boolean,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onPlayDownloadedCopy: () -> Unit,
    onExport: () -> Unit,
    onReplace: () -> Unit,
    onOpenDownloads: (() -> Unit)?,
) {
    if (item == null) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    fun dismissAfter(action: () -> Unit) {
        action()
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(MaterialTheme.nuvio.spacing.screenHorizontal)),
        ) {
            PlayerDownloadSheetHeader(item = item, exactSource = exactSource)

            if (!exactSource) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.SwapHoriz,
                    title = stringResource(Res.string.downloads_replace_action),
                    onClick = { dismissAfter(onReplace) },
                )
            }

            when (item.status) {
                DownloadStatus.Queued,
                DownloadStatus.Downloading,
                DownloadStatus.WaitingForNetwork,
                -> {
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Rounded.Pause,
                        title = stringResource(Res.string.compose_action_pause),
                        onClick = { dismissAfter(onPause) },
                    )
                }
                DownloadStatus.Paused -> {
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Rounded.PlayArrow,
                        title = stringResource(Res.string.action_resume),
                        onClick = { dismissAfter(onResume) },
                    )
                }
                DownloadStatus.Failed -> {
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Rounded.Refresh,
                        title = stringResource(Res.string.action_retry),
                        onClick = { dismissAfter(onRetry) },
                    )
                }
                DownloadStatus.Completed -> {
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Rounded.PlayArrow,
                        title = stringResource(Res.string.player_download_play_copy),
                        onClick = { dismissAfter(onPlayDownloadedCopy) },
                    )
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Rounded.Share,
                        title = stringResource(Res.string.downloads_export),
                        onClick = { dismissAfter(onExport) },
                    )
                }
                DownloadStatus.Finalizing -> Unit
            }

            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Rounded.Delete,
                title = stringResource(
                    if (
                        item.status == DownloadStatus.Paused ||
                        item.status == DownloadStatus.Failed ||
                        item.status == DownloadStatus.Completed
                    ) {
                        Res.string.action_delete
                    } else {
                        Res.string.player_download_cancel
                    },
                ),
                onClick = { dismissAfter(onDelete) },
            )

            if (onOpenDownloads != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.VideoLibrary,
                    title = stringResource(Res.string.player_download_open_downloads),
                    onClick = { dismissAfter(onOpenDownloads) },
                )
            }
        }
    }
}

@Composable
private fun PlayerDownloadSheetHeader(
    item: DownloadItem,
    exactSource: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = if (item.isEpisode) {
                val episodeCode = stringResource(
                    Res.string.compose_player_episode_code_full,
                    item.seasonNumber ?: 0,
                    item.episodeNumber ?: 0,
                )
                listOfNotNull(episodeCode, item.episodeTitle).joinToString(" • ")
            } else {
                item.title
            },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.nuvio.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = playerDownloadStatusText(item),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textSecondary,
        )
        Text(
            text = listOf(item.streamTitle, item.providerName)
                .filter(String::isNotBlank)
                .joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nuvio.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!exactSource) {
            Text(
                text = stringResource(Res.string.player_download_different_source),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.nuvio.colors.accent,
            )
        }
    }
}

@Composable
private fun playerDownloadStatusText(item: DownloadItem): String {
    val size = if (item.totalBytes != null && item.totalBytes > 0L) {
        val percent = (item.progressFraction * 100f).toInt().coerceIn(0, 100)
        "${formatPlayerDownloadBytes(item.downloadedBytes)} / " +
            "${formatPlayerDownloadBytes(item.totalBytes)} • $percent%"
    } else {
        formatPlayerDownloadBytes(item.downloadedBytes)
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
            formatPlayerDownloadBytes(item.totalBytes ?: item.downloadedBytes),
        )
        DownloadStatus.Failed -> item.errorMessage ?: stringResource(Res.string.downloads_status_failed)
    }
}

private fun formatPlayerDownloadBytes(bytes: Long): String {
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
