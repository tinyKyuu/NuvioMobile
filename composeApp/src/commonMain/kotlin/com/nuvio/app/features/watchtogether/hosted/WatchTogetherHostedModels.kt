package com.nuvio.app.features.watchtogether.hosted

import com.nuvio.app.features.watchtogether.protocol.WatchTogetherAdmissionStatus
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherReadinessEvaluation
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoundStatus

internal enum class WatchTogetherHostedStatus {
    Unconfigured,
    LoadingService,
    Ready,
    SendingHostCode,
    AwaitingHostCode,
    AuthenticatingHost,
    CreatingRoom,
    JoiningRoom,
    AwaitingApproval,
    Connected,
    Reconnecting,
    Error,
}

internal data class WatchTogetherHostedParticipant(
    val participantId: String,
    val displayName: String,
    val isHost: Boolean,
    val isConnected: Boolean,
    val sourceReady: Boolean,
    val viewerReady: Boolean,
    val durationMs: Long?,
    val durationMismatchAcknowledged: Boolean,
)

internal data class WatchTogetherHostedState(
    val status: WatchTogetherHostedStatus = WatchTogetherHostedStatus.Unconfigured,
    val manifestUrl: String = "",
    val serviceName: String? = null,
    val hostEmail: String? = null,
    val hostAuthenticated: Boolean = false,
    val displayName: String = "",
    val roomCode: String? = null,
    val roomCapacity: Int? = null,
    val invitationSecret: String? = null,
    val isHost: Boolean = false,
    val admission: WatchTogetherAdmissionStatus? = null,
    val participants: List<WatchTogetherHostedParticipant> = emptyList(),
    val pendingParticipants: List<WatchTogetherPendingParticipant> = emptyList(),
    val roundStatus: WatchTogetherRoundStatus? = null,
    val readiness: WatchTogetherReadinessEvaluation? = null,
    val playerAttached: Boolean = false,
    val selfViewerReady: Boolean = false,
    val selfDurationMismatchAcknowledged: Boolean = false,
    val countdownRemainingMs: Long? = null,
    val localSourceOffsetMs: Long = 0L,
    val canonicalPositionMs: Long? = null,
    val driftMs: Long? = null,
    val roundTripMs: Long? = null,
    val clockSampleCount: Int = 0,
    val correction: String = "None",
    val reconnectCount: Int = 0,
    val errorMessage: String? = null,
) {
    val isConfigured: Boolean
        get() = serviceName != null

    val isActive: Boolean
        get() = status == WatchTogetherHostedStatus.AwaitingApproval ||
            status == WatchTogetherHostedStatus.Connected ||
            status == WatchTogetherHostedStatus.Reconnecting
}
