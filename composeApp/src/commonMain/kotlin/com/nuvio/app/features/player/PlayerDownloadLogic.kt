package com.nuvio.app.features.player

import com.nuvio.app.features.downloads.DownloadEligibility
import com.nuvio.app.features.downloads.DownloadEnqueueRequest
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.evaluateDownloadEligibility

internal sealed interface PlayerDownloadActionState {
    data object Hidden : PlayerDownloadActionState

    data class Available(
        val request: DownloadEnqueueRequest,
    ) : PlayerDownloadActionState

    data class Existing(
        val request: DownloadEnqueueRequest,
        val item: DownloadItem,
        val exactSource: Boolean,
    ) : PlayerDownloadActionState
}

internal enum class PlayerDownloadIndicatorIcon {
    Download,
    Queued,
    WaitingForNetwork,
    Paused,
    Failed,
    Finalizing,
    Completed,
}

internal data class PlayerDownloadIndicatorPresentation(
    val icon: PlayerDownloadIndicatorIcon,
    val progressFraction: Float? = null,
    val showIndeterminateProgress: Boolean = false,
    val isDifferentSource: Boolean = false,
)

internal fun derivePlayerDownloadActionState(
    request: DownloadEnqueueRequest?,
    items: List<DownloadItem>,
): PlayerDownloadActionState {
    val normalizedRequest = request?.normalized() ?: return PlayerDownloadActionState.Hidden
    if (evaluateDownloadEligibility(normalizedRequest) !is DownloadEligibility.Eligible) {
        return PlayerDownloadActionState.Hidden
    }

    val existing = items.firstOrNull { item ->
        item.logicalContentKey == normalizedRequest.logicalContentKey
    } ?: return PlayerDownloadActionState.Available(normalizedRequest)
    val exactSource = existing.sourceFingerprint?.takeIf(String::isNotBlank) ==
        normalizedRequest.sourceFingerprint()
    return PlayerDownloadActionState.Existing(
        request = normalizedRequest,
        item = existing,
        exactSource = exactSource,
    )
}

internal fun PlayerDownloadActionState.indicatorPresentation(): PlayerDownloadIndicatorPresentation? =
    when (this) {
        PlayerDownloadActionState.Hidden -> null
        is PlayerDownloadActionState.Available -> PlayerDownloadIndicatorPresentation(
            icon = PlayerDownloadIndicatorIcon.Download,
        )
        is PlayerDownloadActionState.Existing -> item.indicatorPresentation(
            isDifferentSource = !exactSource,
        )
    }

private fun DownloadItem.indicatorPresentation(
    isDifferentSource: Boolean,
): PlayerDownloadIndicatorPresentation = when (status) {
    DownloadStatus.Queued -> PlayerDownloadIndicatorPresentation(
        icon = PlayerDownloadIndicatorIcon.Queued,
        isDifferentSource = isDifferentSource,
    )
    DownloadStatus.Downloading -> PlayerDownloadIndicatorPresentation(
        icon = PlayerDownloadIndicatorIcon.Download,
        progressFraction = totalBytes?.takeIf { it > 0L }?.let { progressFraction },
        showIndeterminateProgress = totalBytes == null || totalBytes <= 0L,
        isDifferentSource = isDifferentSource,
    )
    DownloadStatus.WaitingForNetwork -> PlayerDownloadIndicatorPresentation(
        icon = PlayerDownloadIndicatorIcon.WaitingForNetwork,
        isDifferentSource = isDifferentSource,
    )
    DownloadStatus.Paused -> PlayerDownloadIndicatorPresentation(
        icon = PlayerDownloadIndicatorIcon.Paused,
        isDifferentSource = isDifferentSource,
    )
    DownloadStatus.Failed -> PlayerDownloadIndicatorPresentation(
        icon = PlayerDownloadIndicatorIcon.Failed,
        isDifferentSource = isDifferentSource,
    )
    DownloadStatus.Finalizing -> PlayerDownloadIndicatorPresentation(
        icon = PlayerDownloadIndicatorIcon.Finalizing,
        showIndeterminateProgress = true,
        isDifferentSource = isDifferentSource,
    )
    DownloadStatus.Completed -> PlayerDownloadIndicatorPresentation(
        icon = PlayerDownloadIndicatorIcon.Completed,
        isDifferentSource = isDifferentSource,
    )
}
