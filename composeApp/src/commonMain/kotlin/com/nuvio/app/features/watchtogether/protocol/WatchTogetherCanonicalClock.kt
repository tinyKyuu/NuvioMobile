package com.nuvio.app.features.watchtogether.protocol

internal object WatchTogetherCanonicalClock {
    fun positionAt(
        playback: WatchTogetherPlaybackAnchor,
        relayTimeMs: Long,
    ): Long {
        require(relayTimeMs in 0L..WATCH_TOGETHER_MAX_SAFE_INTEGER) {
            "relayTimeMs is outside the protocol integer range"
        }
        require(playback.anchorRelayTimeMs in 0L..WATCH_TOGETHER_MAX_SAFE_INTEGER) {
            "anchorRelayTimeMs is outside the protocol integer range"
        }
        require(playback.anchorPositionMs in 0L..WATCH_TOGETHER_MAX_SAFE_INTEGER) {
            "anchorPositionMs is outside the protocol integer range"
        }
        require(playback.rate == 1) { "protocol v1 requires playback rate 1" }
        require(relayTimeMs >= playback.anchorRelayTimeMs) {
            "relayTimeMs cannot precede the playback anchor"
        }
        if (playback.mode == WatchTogetherPlaybackMode.Paused) {
            return playback.anchorPositionMs
        }

        val elapsedMs = relayTimeMs - playback.anchorRelayTimeMs
        require(playback.anchorPositionMs <= WATCH_TOGETHER_MAX_SAFE_INTEGER - elapsedMs) {
            "canonical playback position exceeds the protocol integer range"
        }
        return playback.anchorPositionMs + elapsedMs
    }
}
