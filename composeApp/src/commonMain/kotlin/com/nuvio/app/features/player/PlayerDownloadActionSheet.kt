package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private data class PlayerDownloadMenuAction(
    val icon: ImageVector,
    val label: String,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
internal fun PlayerDownloadActionMenu(
    item: DownloadItem,
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
    val actions = mutableListOf<PlayerDownloadMenuAction>()

    if (!exactSource) {
        actions += PlayerDownloadMenuAction(
            icon = Icons.Rounded.SwapHoriz,
            label = stringResource(Res.string.downloads_replace_action),
            onClick = onReplace,
        )
    }

    when (item.status) {
        DownloadStatus.Queued,
        DownloadStatus.Downloading,
        DownloadStatus.WaitingForNetwork,
        -> actions += PlayerDownloadMenuAction(
            icon = Icons.Rounded.Pause,
            label = stringResource(Res.string.compose_action_pause),
            onClick = onPause,
        )
        DownloadStatus.Paused -> actions += PlayerDownloadMenuAction(
            icon = Icons.Rounded.PlayArrow,
            label = stringResource(Res.string.action_resume),
            onClick = onResume,
        )
        DownloadStatus.Failed -> actions += PlayerDownloadMenuAction(
            icon = Icons.Rounded.Refresh,
            label = stringResource(Res.string.action_retry),
            onClick = onRetry,
        )
        DownloadStatus.Completed -> {
            actions += PlayerDownloadMenuAction(
                icon = Icons.Rounded.PlayArrow,
                label = stringResource(Res.string.player_download_play_copy),
                onClick = onPlayDownloadedCopy,
            )
            actions += PlayerDownloadMenuAction(
                icon = Icons.Rounded.Share,
                label = stringResource(Res.string.downloads_export),
                onClick = onExport,
            )
        }
        DownloadStatus.Finalizing -> Unit
    }

    actions += PlayerDownloadMenuAction(
        icon = Icons.Rounded.Delete,
        label = stringResource(
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
        isDestructive = true,
        onClick = onDelete,
    )

    if (onOpenDownloads != null) {
        actions += PlayerDownloadMenuAction(
            icon = Icons.Rounded.VideoLibrary,
            label = stringResource(Res.string.player_download_open_downloads),
            onClick = onOpenDownloads,
        )
    }

    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(320.dp),
        containerColor = MaterialTheme.nuvio.colors.surfacePopover,
        shape = MaterialTheme.nuvio.shapes.compactCard,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayerDownloadMenuHeader(item = item, exactSource = exactSource)
            PlayerDownloadMenuActions(
                actions = actions,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun PlayerDownloadMenuHeader(
    item: DownloadItem,
    exactSource: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = playerDownloadStatusText(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nuvio.colors.textSecondary,
        )
        val sourceLabel = listOf(item.streamTitle, item.providerName)
            .filter(String::isNotBlank)
            .joinToString(" • ")
        if (sourceLabel.isNotBlank()) {
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.nuvio.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
private fun PlayerDownloadMenuActions(
    actions: List<PlayerDownloadMenuAction>,
    onDismiss: () -> Unit,
) {
    val columnCount = when (actions.size) {
        4 -> 2
        else -> actions.size.coerceAtMost(3).coerceAtLeast(1)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(columnCount).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowActions.forEach { action ->
                    PlayerDownloadMenuActionButton(
                        action = action,
                        onClick = {
                            action.onClick()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columnCount - rowActions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PlayerDownloadMenuActionButton(
    action: PlayerDownloadMenuAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val contentColor = if (action.isDestructive) tokens.colors.danger else tokens.colors.textSecondary

    Column(
        modifier = modifier
            .heightIn(min = 70.dp)
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = action.label,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
