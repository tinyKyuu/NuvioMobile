package com.nuvio.app.features.watchtogether.protocol

internal const val WATCH_TOGETHER_DEFAULT_COUNTDOWN_DURATION_MS = 5_000L
internal const val WATCH_TOGETHER_DEFAULT_DURATION_TOLERANCE_MS = 3_000L

internal data class WatchTogetherReadinessEvaluation(
    val ready: Boolean,
    val durationToleranceMs: Long,
    val durationSpreadMs: Long,
    val durationMismatch: Boolean,
    val disconnectedParticipantIds: List<String>,
    val sourcePendingParticipantIds: List<String>,
    val viewerPendingParticipantIds: List<String>,
    val durationPendingParticipantIds: List<String>,
    val mismatchUnacknowledgedParticipantIds: List<String>,
)

internal object WatchTogetherReadinessGate {
    fun evaluate(
        state: WatchTogetherRoomState,
        durationToleranceMs: Long = WATCH_TOGETHER_DEFAULT_DURATION_TOLERANCE_MS,
    ): WatchTogetherReadinessEvaluation {
        require(durationToleranceMs in 0L..WATCH_TOGETHER_MAX_SAFE_INTEGER)
        val participants = state.participants
        val disconnectedParticipantIds = participants
            .filter { it.connection != WatchTogetherConnectionState.Connected }
            .map(WatchTogetherParticipantState::participantId)
        val sourcePendingParticipantIds = participants
            .filterNot { it.readiness.sourceReady }
            .map(WatchTogetherParticipantState::participantId)
        val viewerPendingParticipantIds = participants
            .filterNot { it.readiness.viewerReady }
            .map(WatchTogetherParticipantState::participantId)
        val durationPendingParticipantIds = participants
            .filter { it.readiness.durationMs == null }
            .map(WatchTogetherParticipantState::participantId)
        val durations = participants.mapNotNull { it.readiness.durationMs }
        val durationSpreadMs = if (durations.size > 1) {
            durations.max() - durations.min()
        } else {
            0L
        }
        val durationMismatch = durationSpreadMs > durationToleranceMs
        val mismatchUnacknowledgedParticipantIds = if (durationMismatch) {
            participants
                .filterNot { it.readiness.durationMismatchAcknowledged }
                .map(WatchTogetherParticipantState::participantId)
        } else {
            emptyList()
        }

        return WatchTogetherReadinessEvaluation(
            ready = disconnectedParticipantIds.isEmpty() &&
                sourcePendingParticipantIds.isEmpty() &&
                viewerPendingParticipantIds.isEmpty() &&
                durationPendingParticipantIds.isEmpty() &&
                mismatchUnacknowledgedParticipantIds.isEmpty(),
            durationToleranceMs = durationToleranceMs,
            durationSpreadMs = durationSpreadMs,
            durationMismatch = durationMismatch,
            disconnectedParticipantIds = disconnectedParticipantIds,
            sourcePendingParticipantIds = sourcePendingParticipantIds,
            viewerPendingParticipantIds = viewerPendingParticipantIds,
            durationPendingParticipantIds = durationPendingParticipantIds,
            mismatchUnacknowledgedParticipantIds = mismatchUnacknowledgedParticipantIds,
        )
    }
}
