package com.nuvio.app.features.watchtogether.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

internal const val WATCH_TOGETHER_PROTOCOL_VERSION = "1.0"
internal const val WATCH_TOGETHER_MANIFEST_VERSION = "1.0"
internal const val WATCH_TOGETHER_MAX_SAFE_INTEGER = 9_007_199_254_740_991L

@Serializable
internal data class WatchTogetherServiceManifest(
    @SerialName("\$schema")
    val schema: String? = null,
    val schemaVersion: String,
    val id: String,
    val name: String,
    val description: String,
    val canonicalOrigin: String,
    val protocolVersions: List<String>,
    val transports: List<WatchTogetherServiceTransport>,
    val endpoints: WatchTogetherServiceEndpoints,
    val operator: WatchTogetherServiceOperator,
    val privacy: WatchTogetherPrivacyDeclaration,
    val authentication: WatchTogetherAuthenticationDeclaration,
    val capabilities: List<WatchTogetherServiceCapability>,
)

@Serializable
internal data class WatchTogetherServiceTransport(
    val profile: WatchTogetherTransportProfile,
    val projectUrl: String,
    val publishableKey: String,
)

@Serializable
internal enum class WatchTogetherTransportProfile {
    @SerialName("supabase_direct_v1")
    SupabaseDirectV1,
}

@Serializable
internal data class WatchTogetherServiceEndpoints(
    val accountLinkUrl: String? = null,
    val statusUrl: String? = null,
    val supportUrl: String? = null,
)

@Serializable
internal data class WatchTogetherServiceOperator(
    val name: String,
    val websiteUrl: String,
    val contactUrl: String? = null,
)

@Serializable
internal data class WatchTogetherPrivacyDeclaration(
    val policyUrl: String,
    val contentBlind: Boolean,
    val operationalDataCategories: List<WatchTogetherOperationalDataCategory>,
)

@Serializable
internal enum class WatchTogetherOperationalDataCategory {
    @SerialName("network_address")
    NetworkAddress,

    @SerialName("account_identifier")
    AccountIdentifier,

    @SerialName("device_session_identifier")
    DeviceSessionIdentifier,

    @SerialName("room_display_name")
    RoomDisplayName,

    @SerialName("room_operational_metrics")
    RoomOperationalMetrics,

    @SerialName("client_platform")
    ClientPlatform,

    @SerialName("diagnostic_errors")
    DiagnosticErrors,
}

@Serializable
internal data class WatchTogetherAuthenticationDeclaration(
    val host: WatchTogetherHostAuthentication,
    val guest: WatchTogetherGuestAuthentication,
)

@Serializable
internal data class WatchTogetherHostAuthentication(
    val mode: WatchTogetherHostAuthenticationMode,
    val accountRequired: Boolean,
)

@Serializable
internal enum class WatchTogetherHostAuthenticationMode {
    @SerialName("none")
    None,

    @SerialName("email_otp")
    EmailOtp,

    @SerialName("email_otp_device_link")
    EmailOtpDeviceLink,
}

@Serializable
internal data class WatchTogetherGuestAuthentication(
    val mode: WatchTogetherGuestAuthenticationMode,
    val accountRequired: Boolean,
)

@Serializable
internal enum class WatchTogetherGuestAuthenticationMode {
    @SerialName("room_credential")
    RoomCredential,
}

@Serializable
internal enum class WatchTogetherServiceCapability {
    @SerialName("room.create")
    RoomCreate,

    @SerialName("room.join")
    RoomJoin,

    @SerialName("room.approve_guest")
    RoomApproveGuest,

    @SerialName("room.rotate_invitation")
    RoomRotateInvitation,

    @SerialName("playback.pause")
    PlaybackPause,

    @SerialName("playback.resume")
    PlaybackResume,

    @SerialName("playback.seek")
    PlaybackSeek,

    @SerialName("participant.readiness")
    ParticipantReadiness,

    @SerialName("round.readiness_gate")
    RoundReadinessGate,

    @SerialName("round.countdown")
    RoundCountdown,

    @SerialName("round.multiple")
    MultipleRounds,

    @SerialName("source.local_offset")
    LocalSourceOffset,
}

@Serializable
internal data class WatchTogetherClientCommand(
    @SerialName("\$schema")
    val schema: String? = null,
    val protocolVersion: String,
    val messageId: String,
    val roomId: String,
    val roundId: String,
    val participantId: String,
    val sessionId: String,
    val sequence: Long,
    val sentAtMs: Long,
    val type: WatchTogetherClientCommandType,
    val payload: JsonObject,
)

@Serializable
internal enum class WatchTogetherClientCommandType {
    @SerialName("playback.pause")
    PlaybackPause,

    @SerialName("playback.resume")
    PlaybackResume,

    @SerialName("playback.seek")
    PlaybackSeek,

    @SerialName("participant.readiness")
    ParticipantReadiness,
}

@Serializable
internal data class WatchTogetherSeekPayload(
    val positionMs: Long,
)

@Serializable
internal data class WatchTogetherReadinessPayload(
    val sourceReady: Boolean,
    val viewerReady: Boolean,
    val durationMs: Long?,
    val durationMismatchAcknowledged: Boolean,
)

@Serializable
internal data class WatchTogetherServerMessage(
    @SerialName("\$schema")
    val schema: String? = null,
    val protocolVersion: String,
    val messageId: String,
    val roomId: String,
    val revision: Long,
    val relayTimeMs: Long,
    val type: WatchTogetherServerMessageType,
    val payload: JsonObject,
)

@Serializable
internal enum class WatchTogetherServerMessageType {
    @SerialName("command.accepted")
    CommandAccepted,

    @SerialName("command.rejected")
    CommandRejected,

    @SerialName("state.snapshot")
    StateSnapshot,
}

@Serializable
internal data class WatchTogetherCommandAcceptedPayload(
    val commandMessageId: String,
    val applied: Boolean,
)

@Serializable
internal data class WatchTogetherCommandRejectedPayload(
    val commandMessageId: String,
    val code: WatchTogetherRejectionCode,
)

@Serializable
internal enum class WatchTogetherRejectionCode {
    @SerialName("ROOM_NOT_FOUND")
    RoomNotFound,

    @SerialName("ROOM_EXPIRED")
    RoomExpired,

    @SerialName("PARTICIPANT_NOT_FOUND")
    ParticipantNotFound,

    @SerialName("SESSION_NOT_ACTIVE")
    SessionNotActive,

    @SerialName("STALE_SEQUENCE")
    StaleSequence,

    @SerialName("ROUND_MISMATCH")
    RoundMismatch,

    @SerialName("READINESS_REQUIRED")
    ReadinessRequired,

    @SerialName("COUNTDOWN_ACTIVE")
    CountdownActive,

    @SerialName("MESSAGE_ID_REUSE")
    MessageIdReuse,

    @SerialName("UNSUPPORTED_PROTOCOL")
    UnsupportedProtocol,

    @SerialName("INVALID_COMMAND")
    InvalidCommand,
}

@Serializable
internal data class WatchTogetherStateSnapshotPayload(
    val state: WatchTogetherRoomState,
)

@Serializable
internal data class WatchTogetherRoomState(
    val protocolVersion: String,
    val roomId: String,
    val revision: Long,
    val status: WatchTogetherRoomStatus,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val capacity: Int,
    val hostParticipantId: String,
    val admission: WatchTogetherAdmissionState,
    val participants: List<WatchTogetherParticipantState>,
    val round: WatchTogetherRoundState,
)

@Serializable
internal enum class WatchTogetherRoomStatus {
    @SerialName("open")
    Open,

    @SerialName("closed")
    Closed,
}

@Serializable
internal data class WatchTogetherAdmissionState(
    val state: WatchTogetherAdmissionStatus,
    val inviteGeneration: Long,
)

@Serializable
internal enum class WatchTogetherAdmissionStatus {
    @SerialName("open")
    Open,

    @SerialName("paused")
    Paused,
}

@Serializable
internal data class WatchTogetherParticipantState(
    val participantId: String,
    val displayName: String,
    val role: WatchTogetherParticipantRole,
    val connection: WatchTogetherConnectionState,
    val readiness: WatchTogetherReadinessState,
)

@Serializable
internal enum class WatchTogetherParticipantRole {
    @SerialName("host")
    Host,

    @SerialName("guest")
    Guest,
}

@Serializable
internal enum class WatchTogetherConnectionState {
    @SerialName("connected")
    Connected,

    @SerialName("disconnected")
    Disconnected,
}

@Serializable
internal data class WatchTogetherReadinessState(
    val roundId: String,
    val sourceReady: Boolean,
    val viewerReady: Boolean,
    val durationMs: Long?,
    val durationMismatchAcknowledged: Boolean,
)

@Serializable
internal data class WatchTogetherRoundState(
    val roundId: String,
    val generation: Long,
    val status: WatchTogetherRoundStatus,
    val countdown: WatchTogetherCountdownState? = null,
    val playback: WatchTogetherPlaybackAnchor,
)

@Serializable
internal data class WatchTogetherCountdownState(
    val startedAtRelayTimeMs: Long,
    val endsAtRelayTimeMs: Long,
)

@Serializable
internal enum class WatchTogetherRoundStatus {
    @SerialName("preparing")
    Preparing,

    @SerialName("countdown")
    Countdown,

    @SerialName("active")
    Active,

    @SerialName("ended")
    Ended,
}

@Serializable
internal data class WatchTogetherPlaybackAnchor(
    val mode: WatchTogetherPlaybackMode,
    val anchorPositionMs: Long,
    val anchorRelayTimeMs: Long,
    val rate: Int,
)

@Serializable
internal enum class WatchTogetherPlaybackMode {
    @SerialName("paused")
    Paused,

    @SerialName("playing")
    Playing,
}
