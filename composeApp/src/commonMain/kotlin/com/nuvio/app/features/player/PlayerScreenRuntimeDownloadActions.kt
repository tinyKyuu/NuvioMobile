package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.downloads.DownloadEnqueueDecision
import com.nuvio.app.features.downloads.DownloadEnqueueRequest
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId

internal fun PlayerScreenRuntime.currentDownloadEnqueueRequest(): DownloadEnqueueRequest =
    DownloadEnqueueRequest(
        profileId = profileId,
        contentType = contentType ?: parentMetaType,
        videoId = activeVideoId?.takeIf(String::isNotBlank) ?: buildPlaybackVideoId(
            parentMetaId = parentMetaId,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            fallbackVideoId = activeVideoId,
        ),
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        title = title,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        episodeThumbnail = activeEpisodeThumbnail,
        streamTitle = activeStreamTitle,
        streamSubtitle = activeStreamSubtitle,
        providerName = activeProviderName,
        providerAddonId = activeProviderAddonId,
        sourceUrl = activeSourceUrl,
        sourceHeaders = activeSourceHeaders,
        sourceResponseHeaders = activeSourceResponseHeaders,
        streamType = activeStreamType,
        hasSeparateAudioSource = !activeSourceAudioUrl.isNullOrBlank(),
        isP2p = activeTorrentInfoHash != null || p2pResolvedSourceUrl != null,
    )

internal fun PlayerScreenRuntime.currentPlayerDownloadActionState(): PlayerDownloadActionState =
    derivePlayerDownloadActionState(
        request = currentDownloadEnqueueRequest(),
        items = downloadsUiState.items,
    )

internal fun PlayerScreenRuntime.handlePlayerDownloadActionTap() {
    when (val state = currentPlayerDownloadActionState()) {
        PlayerDownloadActionState.Hidden -> Unit
        is PlayerDownloadActionState.Available -> {
            when (val decision = DownloadsRepository.evaluateEnqueue(state.request)) {
                DownloadEnqueueDecision.Enqueue -> {
                    showPlayerDownloadEnqueueResult(
                        DownloadsRepository.enqueue(state.request),
                    )
                }
                is DownloadEnqueueDecision.ExistingExact -> {
                    openPlayerDownloadSheet(state.request, decision.item)
                }
                is DownloadEnqueueDecision.ConfirmReplacement -> {
                    openPlayerDownloadSheet(state.request, decision.item)
                }
                is DownloadEnqueueDecision.Ineligible -> Unit
                DownloadEnqueueDecision.ProfileChanged -> {
                    showPlayerDownloadEnqueueResult(DownloadEnqueueResult.ProfileChanged)
                }
            }
        }
        is PlayerDownloadActionState.Existing -> {
            openPlayerDownloadSheet(state.request, state.item)
        }
    }
}

internal fun PlayerScreenRuntime.confirmPlayerDownloadReplacement(
    replacement: PendingPlayerDownloadReplacement,
) {
    val result = DownloadsRepository.enqueue(
        request = replacement.request,
        replacingDownloadId = replacement.expectedDownloadId,
    )
    playerDownloadPendingReplacement = null
    playerDownloadSheetRequest = null
    playerDownloadSheetItemId = null
    showPlayerDownloadEnqueueResult(result)
}

internal fun PlayerScreenRuntime.switchToDownloadedCurrentItem(item: DownloadItem) {
    val localFileUri = DownloadsRepository.playableLocalFileUri(item) ?: return
    val resumePositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    flushWatchProgress()
    stopActiveP2pStream()

    activeSourceUrl = localFileUri
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeSourceIdentityKey = null
    activeStreamTitle = item.streamTitle.ifBlank { activeStreamTitle }
    activeStreamSubtitle = item.streamSubtitle
    activeProviderName = item.providerName.ifBlank { downloadedLabel }
    activeProviderAddonId = item.providerAddonId
    currentStreamBingeGroup = null
    activeSeasonNumber = item.seasonNumber
    activeEpisodeNumber = item.episodeNumber
    activeEpisodeTitle = item.episodeTitle
    activeEpisodeThumbnail = item.episodeThumbnail
    activeVideoId = item.videoId
    activeInitialPositionMs = resumePositionMs
    activeInitialProgressFraction = null
    controlsVisible = true
    playerDownloadSheetRequest = null
    playerDownloadSheetItemId = null
}

private fun PlayerScreenRuntime.openPlayerDownloadSheet(
    request: DownloadEnqueueRequest,
    item: DownloadItem,
) {
    playerDownloadSheetRequest = request
    playerDownloadSheetItemId = item.id
    controlsVisible = true
}

private fun PlayerScreenRuntime.showPlayerDownloadEnqueueResult(result: DownloadEnqueueResult) {
    val opensDownloads = result == DownloadEnqueueResult.Started ||
        result == DownloadEnqueueResult.Replaced
    NuvioToastController.show(
        message = result.toastMessage(),
        durationMillis = if (opensDownloads) 4_000L else 2_500L,
        onClick = args.onOpenDownloads.takeIf { opensDownloads },
    )
}
