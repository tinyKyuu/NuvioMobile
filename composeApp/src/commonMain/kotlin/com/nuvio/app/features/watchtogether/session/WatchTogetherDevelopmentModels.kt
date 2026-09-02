package com.nuvio.app.features.watchtogether.session

import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServerMessage
import kotlinx.serialization.Serializable

internal enum class WatchTogetherDevelopmentStatus {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    Error,
}

internal data class WatchTogetherDevelopmentParticipant(
    val displayName: String,
    val isHost: Boolean,
    val isConnected: Boolean,
)

internal data class WatchTogetherDevelopmentState(
    val status: WatchTogetherDevelopmentStatus = WatchTogetherDevelopmentStatus.Disconnected,
    val endpoint: String = "",
    val displayName: String = "",
    val roomCode: String? = null,
    val participants: List<WatchTogetherDevelopmentParticipant> = emptyList(),
    val canonicalPositionMs: Long? = null,
    val driftMs: Long? = null,
    val roundTripMs: Long? = null,
    val clockSampleCount: Int = 0,
    val correction: String = "None",
    val reconnectCount: Int = 0,
    val errorMessage: String? = null,
) {
    val isActive: Boolean
        get() = status == WatchTogetherDevelopmentStatus.Connected ||
            status == WatchTogetherDevelopmentStatus.Reconnecting
}

@Serializable
internal data class DevelopmentSessionReadyFrame(
    val type: String,
    val requestId: String? = null,
    val roomId: String,
    val roomCode: String,
    val participantId: String,
    val sessionId: String,
    val reconnectToken: String,
    val roundId: String,
    val snapshot: WatchTogetherServerMessage,
)

@Serializable
internal data class DevelopmentProtocolMessageFrame(
    val type: String,
    val requestId: String? = null,
    val message: WatchTogetherServerMessage,
)

@Serializable
internal data class DevelopmentClockPongFrame(
    val type: String,
    val requestId: String? = null,
    val clientSentAtMs: Long,
    val relayTimeMs: Long,
)

@Serializable
internal data class DevelopmentRelayErrorFrame(
    val type: String,
    val requestId: String? = null,
    val code: String,
    val message: String,
)
