package com.nuvio.app.features.player

// These actions run on the player UI scope. Each request also captures the media
// identity so a delayed resolver cannot switch away from a newer episode.
internal fun PlayerScreenRuntime.beginNextEpisodeRequest(): () -> Boolean {
    val request = Any()
    val video = activeVideoId
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    nextEpisodeRequest = request
    return {
        nextEpisodeRequest === request && activeVideoId == video &&
            activeSeasonNumber == season && activeEpisodeNumber == episode
    }
}

internal fun PlayerScreenRuntime.cancelNextEpisodeAutoPlay() {
    nextEpisodeRequest = null
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    if (nextEpisodeAutoPlaySearching) PlayerStreamsRepository.clearEpisodeStreams()
    nextEpisodeAutoPlaySearching = false
    nextEpisodeAutoPlaySourceName = null
    nextEpisodeAutoPlayCountdown = null
}

internal fun PlayerScreenRuntime.dismissNextEpisode() {
    nextEpisodeCardDismissed = true
    showNextEpisodeCard = false
    cancelNextEpisodeAutoPlay()
}

internal fun PlayerScreenRuntime.resetNextEpisodeForCurrentMedia() {
    cancelNextEpisodeAutoPlay()
    nextEpisodeCardDismissed = false
    showNextEpisodeCard = false
}

internal fun PlayerScreenRuntime.showNextEpisodeIfEligible(eligible: Boolean): Boolean {
    if (!eligible || showNextEpisodeCard || nextEpisodeCardDismissed || nextEpisodeRequest != null) return false
    showNextEpisodeCard = true
    return true
}

internal fun PlayerScreenRuntime.finishNextEpisodeRequest(isCurrentRequest: () -> Boolean) {
    if (!isCurrentRequest()) return
    // Includes a failed/abandoned handoff. Manual selection can still retry;
    // automatic progress/end effects wait for the next media identity.
    nextEpisodeCardDismissed = true
    showNextEpisodeCard = false
    nextEpisodeRequest = null
    nextEpisodeAutoPlayJob = null
    nextEpisodeAutoPlaySearching = false
    nextEpisodeAutoPlaySourceName = null
    nextEpisodeAutoPlayCountdown = null
}
