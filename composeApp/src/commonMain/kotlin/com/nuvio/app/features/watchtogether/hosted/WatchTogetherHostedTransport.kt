package com.nuvio.app.features.watchtogether.hosted

import com.nuvio.app.features.watchtogether.protocol.WatchTogetherClientCommand
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoomState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServerMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal interface WatchTogetherHostedTransport {
    suspend fun signInAnonymously()

    suspend fun requestHostEmailOtp(email: String)

    suspend fun verifyHostEmailOtp(email: String, token: String)

    suspend fun createRoom(request: WatchTogetherCreateRoomRequest): WatchTogetherCreatedRoom

    suspend fun requestJoin(request: WatchTogetherJoinRoomRequest): WatchTogetherJoinResult

    suspend fun pollJoin(roomId: String, participantId: String): WatchTogetherJoinResult

    suspend fun pendingParticipants(roomId: String): List<WatchTogetherPendingParticipant>

    suspend fun resolveJoinRequest(
        roomId: String,
        participantId: String,
        approve: Boolean,
    ): WatchTogetherResolvedJoin

    suspend fun fetchSnapshot(roomId: String): WatchTogetherServerMessage

    suspend fun applyCommand(command: WatchTogetherClientCommand): WatchTogetherServerMessage

    suspend fun rotateInvitation(roomId: String, newInviteSecret: String): WatchTogetherRotatedInvite

    suspend fun closeRoom(roomId: String): WatchTogetherRoomState

    suspend fun beginCountdown(roomId: String, force: Boolean): WatchTogetherRoomState

    suspend fun cancelCountdown(roomId: String): WatchTogetherRoomState

    suspend fun completeCountdown(roomId: String): WatchTogetherRoomState

    suspend fun beginRound(roomId: String): WatchTogetherRoomState

    suspend fun setConnection(
        roomId: String,
        participantId: String,
        sessionId: String,
        connected: Boolean,
    ): WatchTogetherRoomState

    suspend fun setAdmission(roomId: String, open: Boolean): WatchTogetherRoomState

    suspend fun subscribeToRoom(roomId: String): WatchTogetherHostedRoomSubscription

    suspend fun clearSession()

    suspend fun close()
}

internal interface WatchTogetherHostedRoomSubscription {
    val messages: Flow<WatchTogetherServerMessage>

    suspend fun close()
}

internal data class WatchTogetherCreateRoomRequest(
    val displayName: String,
    val capacity: Int,
    val inviteSecret: String,
    val initialPositionMs: Long,
)

internal data class WatchTogetherJoinRoomRequest(
    val roomCode: String,
    val inviteSecret: String,
    val displayName: String,
)

@Serializable
internal data class WatchTogetherCreatedRoom(
    val roomCode: String,
    val roomId: String,
    val participantId: String,
    val sessionId: String,
    val roundId: String,
    val expiresAtMs: Long,
    val state: WatchTogetherRoomState,
)

@Serializable
internal data class WatchTogetherJoinResult(
    val status: WatchTogetherJoinStatus,
    val code: String? = null,
    val roomId: String? = null,
    val participantId: String? = null,
    val sessionId: String? = null,
)

@Serializable
internal enum class WatchTogetherJoinStatus {
    @SerialName("pending")
    Pending,

    @SerialName("admitted")
    Admitted,

    @SerialName("rejected")
    Rejected,
}

@Serializable
internal data class WatchTogetherPendingParticipant(
    val participantId: String,
    val displayName: String,
    val requestedAtMs: Long,
)

@Serializable
internal data class WatchTogetherResolvedJoin(
    val participantId: String,
    val status: WatchTogetherJoinResolution,
    val state: WatchTogetherRoomState,
)

@Serializable
internal enum class WatchTogetherJoinResolution {
    @SerialName("admitted")
    Admitted,

    @SerialName("rejected")
    Rejected,
}

@Serializable
internal data class WatchTogetherRotatedInvite(
    val inviteGeneration: Long,
    val state: WatchTogetherRoomState,
)
