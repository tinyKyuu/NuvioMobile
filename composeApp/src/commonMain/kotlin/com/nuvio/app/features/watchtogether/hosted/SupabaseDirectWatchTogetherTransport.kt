package com.nuvio.app.features.watchtogether.hosted

import com.nuvio.app.features.watchtogether.protocol.WATCH_TOGETHER_MAX_SAFE_INTEGER
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherClientCommand
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherContractValidator
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherJson
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoomState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServerMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal class SupabaseDirectWatchTogetherTransport(
    private val backend: WatchTogetherSupabaseBackend,
) : WatchTogetherHostedTransport {
    private val activeSubscriptions = mutableSetOf<ManagedRoomSubscription>()
    private var closed = false

    override suspend fun signInAnonymously() = withOpenBackend {
        backend.signInAnonymously()
    }

    override suspend fun requestHostEmailOtp(email: String) = withOpenBackend {
        backend.requestHostEmailOtp(normalizeEmail(email))
    }

    override suspend fun verifyHostEmailOtp(email: String, token: String) = withOpenBackend {
        val normalizedToken = token.trim()
        require(normalizedToken.matches(Regex("^[0-9]{6}$"))) {
            "Host email code must contain six digits"
        }
        backend.verifyHostEmailOtp(normalizeEmail(email), normalizedToken)
    }

    override suspend fun createRoom(
        request: WatchTogetherCreateRoomRequest,
    ): WatchTogetherCreatedRoom = withOpenBackend {
        requireDisplayName(request.displayName)
        require(request.capacity in 2..8) { "Room capacity must be between 2 and 8" }
        requireInviteSecret(request.inviteSecret)
        require(request.initialPositionMs in 0L..WATCH_TOGETHER_MAX_SAFE_INTEGER) {
            "Initial position must be a non-negative safe integer"
        }
        val result = decode<WatchTogetherCreatedRoom>(
            backend.invoke(
                function = "wt_create_room",
                parameters = buildJsonObject {
                    put("p_host_display_name", request.displayName.trim())
                    put("p_capacity", request.capacity)
                    put("p_invite_secret", request.inviteSecret)
                    put("p_initial_position_ms", request.initialPositionMs)
                },
            ),
        )
        requireValidState(result.state)
        require(result.roomId == result.state.roomId) { "Created room identity is inconsistent" }
        require(result.participantId == result.state.hostParticipantId) {
            "Created host identity is inconsistent"
        }
        require(result.roundId == result.state.round.roundId) { "Created round identity is inconsistent" }
        require(result.expiresAtMs == result.state.expiresAtMs) { "Created expiry is inconsistent" }
        result
    }

    override suspend fun requestJoin(
        request: WatchTogetherJoinRoomRequest,
    ): WatchTogetherJoinResult = withOpenBackend {
        requireDisplayName(request.displayName)
        requireInviteSecret(request.inviteSecret)
        val roomCode = normalizeRoomCode(request.roomCode)
        val result = decode<WatchTogetherJoinResult>(
            backend.invoke(
                function = "wt_request_join",
                parameters = buildJsonObject {
                    put("p_room_code", roomCode)
                    put("p_invite_secret", request.inviteSecret)
                    put("p_display_name", request.displayName.trim())
                },
            ),
        )
        validateJoinResult(result)
    }

    override suspend fun pollJoin(
        roomId: String,
        participantId: String,
    ): WatchTogetherJoinResult = withOpenBackend {
        val result = decode<WatchTogetherJoinResult>(
            backend.invoke(
                function = "wt_join_status",
                parameters = buildJsonObject {
                    put("p_room_id", requireUuid("roomId", roomId))
                    put("p_participant_id", requireUuid("participantId", participantId))
                },
            ),
        )
        validateJoinResult(result)
    }

    private fun validateJoinResult(result: WatchTogetherJoinResult): WatchTogetherJoinResult {
        when (result.status) {
            WatchTogetherJoinStatus.Pending,
            WatchTogetherJoinStatus.Admitted,
            -> {
                requireUuid("roomId", result.roomId)
                requireUuid("participantId", result.participantId)
                requireUuid("sessionId", result.sessionId)
                require(result.code == null) { "Accepted join result must not contain a rejection code" }
            }

            WatchTogetherJoinStatus.Rejected -> {
                require(!result.code.isNullOrBlank()) { "Rejected join result must contain a code" }
                require(result.roomId == null && result.participantId == null && result.sessionId == null) {
                    "Rejected join result must not contain room credentials"
                }
            }
        }
        return result
    }

    override suspend fun pendingParticipants(
        roomId: String,
    ): List<WatchTogetherPendingParticipant> = withOpenBackend {
        val normalizedRoomId = requireUuid("roomId", roomId)
        decode<List<WatchTogetherPendingParticipant>>(
            invokeRoomFunction("wt_pending_participants", normalizedRoomId),
        ).onEach { participant ->
            requireUuid("participantId", participant.participantId)
            requireDisplayName(participant.displayName)
            require(participant.requestedAtMs in 0L..WATCH_TOGETHER_MAX_SAFE_INTEGER) {
                "Pending participant timestamp is invalid"
            }
        }
    }

    override suspend fun resolveJoinRequest(
        roomId: String,
        participantId: String,
        approve: Boolean,
    ): WatchTogetherResolvedJoin = withOpenBackend {
        val normalizedRoomId = requireUuid("roomId", roomId)
        val normalizedParticipantId = requireUuid("participantId", participantId)
        val result = decode<WatchTogetherResolvedJoin>(
            backend.invoke(
                function = "wt_resolve_join_request",
                parameters = buildJsonObject {
                    put("p_room_id", normalizedRoomId)
                    put("p_participant_id", normalizedParticipantId)
                    put("p_approve", approve)
                },
            ),
        )
        require(result.participantId == normalizedParticipantId) {
            "Resolved participant identity is inconsistent"
        }
        requireValidState(result.state, normalizedRoomId)
        result
    }

    override suspend fun fetchSnapshot(roomId: String): WatchTogetherServerMessage = withOpenBackend {
        val normalizedRoomId = requireUuid("roomId", roomId)
        decodeServerMessage(
            raw = invokeRoomFunction("wt_fetch_snapshot", normalizedRoomId),
            expectedRoomId = normalizedRoomId,
        )
    }

    override suspend fun applyCommand(
        command: WatchTogetherClientCommand,
    ): WatchTogetherServerMessage = withOpenBackend {
        val validation = WatchTogetherContractValidator.validate(command)
        require(validation.isValid) {
            "Client command is invalid: ${validation.issues.first().path} ${validation.issues.first().message}"
        }
        decodeServerMessage(
            raw = backend.invoke(
                function = "wt_apply_command",
                parameters = buildJsonObject {
                    put("p_command", WatchTogetherJson.encodeToJsonElement(command))
                },
            ),
            expectedRoomId = command.roomId,
        )
    }

    override suspend fun rotateInvitation(
        roomId: String,
        newInviteSecret: String,
    ): WatchTogetherRotatedInvite = withOpenBackend {
        val normalizedRoomId = requireUuid("roomId", roomId)
        requireInviteSecret(newInviteSecret)
        val result = decode<WatchTogetherRotatedInvite>(
            backend.invoke(
                function = "wt_rotate_invitation",
                parameters = buildJsonObject {
                    put("p_room_id", normalizedRoomId)
                    put("p_new_invite_secret", newInviteSecret)
                },
            ),
        )
        requireValidState(result.state, normalizedRoomId)
        require(result.inviteGeneration == result.state.admission.inviteGeneration) {
            "Rotated invitation generation is inconsistent"
        }
        result
    }

    override suspend fun closeRoom(roomId: String): WatchTogetherRoomState =
        roomStateFunction("wt_close_room", roomId)

    override suspend fun beginCountdown(roomId: String, force: Boolean): WatchTogetherRoomState =
        roomStateFunction(
            function = "wt_begin_countdown",
            roomId = roomId,
            extraParameters = buildJsonObject { put("p_force", force) },
        )

    override suspend fun cancelCountdown(roomId: String): WatchTogetherRoomState =
        roomStateFunction("wt_cancel_countdown", roomId)

    override suspend fun completeCountdown(roomId: String): WatchTogetherRoomState =
        roomStateFunction("wt_complete_countdown", roomId)

    override suspend fun beginRound(roomId: String): WatchTogetherRoomState =
        roomStateFunction("wt_begin_round", roomId)

    override suspend fun setConnection(
        roomId: String,
        participantId: String,
        sessionId: String,
        connected: Boolean,
    ): WatchTogetherRoomState = withOpenBackend {
        val normalizedRoomId = requireUuid("roomId", roomId)
        val result = decode<WatchTogetherRoomState>(
            backend.invoke(
                function = "wt_set_connection",
                parameters = buildJsonObject {
                    put("p_room_id", normalizedRoomId)
                    put("p_participant_id", requireUuid("participantId", participantId))
                    put("p_session_id", requireUuid("sessionId", sessionId))
                    put("p_connected", connected)
                },
            ),
        )
        requireValidState(result, normalizedRoomId)
        result
    }

    override suspend fun setAdmission(roomId: String, open: Boolean): WatchTogetherRoomState =
        roomStateFunction(
            function = "wt_set_admission",
            roomId = roomId,
            extraParameters = buildJsonObject { put("p_open", open) },
        )

    override suspend fun subscribeToRoom(
        roomId: String,
    ): WatchTogetherHostedRoomSubscription = withOpenBackend {
        val normalizedRoomId = requireUuid("roomId", roomId)
        val backendSubscription = backend.subscribePrivateRoom(
            topic = "room:$normalizedRoomId",
            event = "state.snapshot",
        )
        ManagedRoomSubscription(
            backendSubscription = backendSubscription,
            messages = backendSubscription.payloads.map { raw ->
                decodeServerMessage(raw, normalizedRoomId)
            },
            onClosed = { subscription -> activeSubscriptions.remove(subscription) },
        ).also(activeSubscriptions::add)
    }

    override suspend fun clearSession() {
        if (closed) return
        activeSubscriptions.toList().forEach { it.close() }
        backend.clearSession()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            activeSubscriptions.toList().forEach { it.close() }
        } finally {
            activeSubscriptions.clear()
            backend.close()
        }
    }

    private suspend fun roomStateFunction(
        function: String,
        roomId: String,
        extraParameters: JsonObject = JsonObject(emptyMap()),
    ): WatchTogetherRoomState = withOpenBackend {
        val normalizedRoomId = requireUuid("roomId", roomId)
        val result = decode<WatchTogetherRoomState>(
            backend.invoke(
                function = function,
                parameters = buildJsonObject {
                    put("p_room_id", normalizedRoomId)
                    extraParameters.forEach { (key, value) -> put(key, value) }
                },
            ),
        )
        requireValidState(result, normalizedRoomId)
        result
    }

    private suspend fun invokeRoomFunction(function: String, roomId: String): String =
        backend.invoke(
            function = function,
            parameters = buildJsonObject { put("p_room_id", roomId) },
        )

    private fun decodeServerMessage(raw: String, expectedRoomId: String): WatchTogetherServerMessage {
        val message = decode<WatchTogetherServerMessage>(raw)
        val validation = WatchTogetherContractValidator.validate(message)
        require(validation.isValid) {
            "Hosted server message is invalid: ${validation.issues.first().path} ${validation.issues.first().message}"
        }
        require(message.roomId == expectedRoomId) { "Hosted server message belongs to another room" }
        return message
    }

    private fun requireValidState(state: WatchTogetherRoomState, expectedRoomId: String? = null) {
        val validation = WatchTogetherContractValidator.validate(state)
        require(validation.isValid) {
            "Hosted room state is invalid: ${validation.issues.first().path} ${validation.issues.first().message}"
        }
        expectedRoomId?.let {
            require(state.roomId == it) { "Hosted room state belongs to another room" }
        }
    }

    private inline fun <reified T> decode(raw: String): T =
        try {
            WatchTogetherJson.decodeFromString(raw)
        } catch (error: Exception) {
            throw IllegalArgumentException("Hosted service returned an invalid response", error)
        }

    private suspend inline fun <T> withOpenBackend(block: () -> T): T {
        check(!closed) { "Watch Together hosted transport is closed" }
        return block()
    }
}

internal interface WatchTogetherSupabaseBackend {
    suspend fun signInAnonymously()

    suspend fun requestHostEmailOtp(email: String)

    suspend fun verifyHostEmailOtp(email: String, token: String)

    suspend fun invoke(function: String, parameters: JsonObject): String

    suspend fun subscribePrivateRoom(
        topic: String,
        event: String,
    ): WatchTogetherSupabaseSubscription

    suspend fun clearSession()

    suspend fun close()
}

internal interface WatchTogetherSupabaseSubscription {
    val payloads: Flow<String>

    suspend fun close()
}

private class ManagedRoomSubscription(
    private val backendSubscription: WatchTogetherSupabaseSubscription,
    override val messages: Flow<WatchTogetherServerMessage>,
    private val onClosed: (ManagedRoomSubscription) -> Unit,
) : WatchTogetherHostedRoomSubscription {
    private var closed = false

    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            backendSubscription.close()
        } finally {
            onClosed(this)
        }
    }
}

private val uuidPattern = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
)
private val roomCodePattern = Regex("^[A-HJ-NP-Z2-9]{8}$")

private fun normalizeEmail(email: String): String = email.trim().lowercase().also {
    require(it.length in 3..254 && '@' in it && it.none(Char::isWhitespace)) {
        "Host email address is invalid"
    }
}

private fun normalizeRoomCode(roomCode: String): String =
    roomCode.trim().replace("-", "").uppercase().also {
        require(roomCodePattern.matches(it)) { "Room code must contain eight valid characters" }
    }

private fun requireInviteSecret(secret: String) {
    require(secret.length in 32..256) { "Invitation secret must contain between 32 and 256 characters" }
}

private fun requireDisplayName(displayName: String) {
    val normalized = displayName.trim()
    require(
        normalized.length in 1..40 &&
            normalized.any { !it.isWhitespace() } &&
            normalized.none { it.code in 0..31 || it.code == 127 },
    ) { "Display name must contain between 1 and 40 visible characters" }
}

private fun requireUuid(field: String, value: String?): String {
    require(value != null && uuidPattern.matches(value)) { "$field must be a UUID" }
    return value.lowercase()
}
