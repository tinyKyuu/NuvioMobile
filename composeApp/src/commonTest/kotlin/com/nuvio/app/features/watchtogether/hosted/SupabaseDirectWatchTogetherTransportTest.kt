package com.nuvio.app.features.watchtogether.hosted

import com.nuvio.app.features.watchtogether.protocol.WATCH_TOGETHER_PROTOCOL_VERSION
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherAdmissionState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherAdmissionStatus
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherClientCommand
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherClientCommandType
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherConnectionState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherJson
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherParticipantRole
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherParticipantState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlaybackAnchor
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlaybackMode
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherReadinessState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoomState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoomStatus
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoundState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoundStatus
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServerMessage
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServerMessageType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupabaseDirectWatchTogetherTransportTest {
    @Test
    fun `normalizes auth input and maps hosted RPC parameters`() = runBlocking {
        val backend = FakeWatchTogetherSupabaseBackend().apply {
            enqueue("wt_create_room", encoded(createdRoom()))
            enqueue(
                "wt_request_join",
                """{"status":"rejected","code":"INVALID_INVITE"}""",
            )
            enqueue(
                "wt_join_status",
                encoded(
                    WatchTogetherJoinResult(
                        status = WatchTogetherJoinStatus.Admitted,
                        roomId = roomId,
                        participantId = guestParticipantId,
                        sessionId = sessionId,
                    ),
                ),
            )
            enqueue("wt_pending_participants", encoded(listOf(pendingParticipant())))
            enqueue("wt_resolve_join_request", encoded(resolvedJoin()))
            enqueue("wt_fetch_snapshot", encoded(snapshotMessage()))
            enqueue("wt_apply_command", encoded(commandAcceptedMessage()))
            enqueue("wt_rotate_invitation", encoded(rotatedInvite()))
            repeat(7) { enqueue(roomFunctions[it], encoded(roomState())) }
        }
        val transport = SupabaseDirectWatchTogetherTransport(backend)

        transport.signInAnonymously()
        transport.requestHostEmailOtp(" HOST@Example.Test ")
        transport.verifyHostEmailOtp(" HOST@Example.Test ", " 123456 ")
        transport.createRoom(
            WatchTogetherCreateRoomRequest(
                displayName = " Pilot Host ",
                capacity = 4,
                inviteSecret = inviteSecret,
                initialPositionMs = 42_000L,
            ),
        )
        val rejected = transport.requestJoin(
            WatchTogetherJoinRoomRequest(
                roomCode = "ABCD-EFGH",
                inviteSecret = inviteSecret,
                displayName = " Pilot Guest ",
            ),
        )
        val admitted = transport.pollJoin(roomId, guestParticipantId)
        transport.pendingParticipants(roomId)
        transport.resolveJoinRequest(roomId, guestParticipantId, approve = true)
        transport.fetchSnapshot(roomId)
        transport.applyCommand(command())
        transport.rotateInvitation(roomId, replacementInviteSecret)
        transport.closeRoom(roomId)
        transport.beginCountdown(roomId, force = true)
        transport.cancelCountdown(roomId)
        transport.completeCountdown(roomId)
        transport.beginRound(roomId)
        transport.setConnection(roomId, hostParticipantId, sessionId, connected = false)
        transport.setAdmission(roomId, open = false)

        assertEquals(1, backend.anonymousSignIns)
        assertEquals(listOf("host@example.test"), backend.otpRequests)
        assertEquals(listOf("host@example.test" to "123456"), backend.otpVerifications)
        assertEquals(WatchTogetherJoinStatus.Rejected, rejected.status)
        assertEquals("INVALID_INVITE", rejected.code)
        assertEquals(WatchTogetherJoinStatus.Admitted, admitted.status)
        assertEquals(
            listOf(
                "wt_create_room",
                "wt_request_join",
                "wt_join_status",
                "wt_pending_participants",
                "wt_resolve_join_request",
                "wt_fetch_snapshot",
                "wt_apply_command",
                "wt_rotate_invitation",
            ) + roomFunctions,
            backend.invocations.map(Invocation::function),
        )

        val create = backend.invocations.first().parameters
        assertEquals("Pilot Host", create.getValue("p_host_display_name").jsonPrimitive.content)
        assertEquals("4", create.getValue("p_capacity").jsonPrimitive.content)
        assertEquals("42000", create.getValue("p_initial_position_ms").jsonPrimitive.content)
        val join = backend.invocations[1].parameters
        assertEquals("ABCDEFGH", join.getValue("p_room_code").jsonPrimitive.content)
        assertEquals("Pilot Guest", join.getValue("p_display_name").jsonPrimitive.content)
        val countdown = backend.invocations.first { it.function == "wt_begin_countdown" }.parameters
        assertEquals("true", countdown.getValue("p_force").jsonPrimitive.content)
        val connection = backend.invocations.first { it.function == "wt_set_connection" }.parameters
        assertEquals(sessionId, connection.getValue("p_session_id").jsonPrimitive.content)
        assertFalse(connection.getValue("p_connected").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `private room subscription validates messages and closes once`() = runBlocking {
        val backend = FakeWatchTogetherSupabaseBackend().apply {
            subscriptionPayloads = listOf(encoded(snapshotMessage()))
        }
        val transport = SupabaseDirectWatchTogetherTransport(backend)

        val subscription = transport.subscribeToRoom(roomId)
        val message = subscription.messages.first()
        subscription.close()
        subscription.close()

        assertEquals(roomId, message.roomId)
        assertEquals("room:$roomId", backend.subscriptionTopic)
        assertEquals("state.snapshot", backend.subscriptionEvent)
        assertEquals(1, backend.subscriptionCloseCount)
    }

    @Test
    fun `clearing a service session closes channels before deleting auth`() = runBlocking {
        val backend = FakeWatchTogetherSupabaseBackend().apply {
            subscriptionPayloads = listOf(encoded(snapshotMessage()))
        }
        val transport = SupabaseDirectWatchTogetherTransport(backend)
        transport.subscribeToRoom(roomId)

        transport.clearSession()

        assertEquals(listOf("subscription.close", "auth.clear"), backend.lifecycleEvents)
        assertEquals(1, backend.subscriptionCloseCount)
        assertEquals(1, backend.sessionClears)
    }

    @Test
    fun `rejects malformed or cross-room service responses`() = runBlocking {
        val backend = FakeWatchTogetherSupabaseBackend().apply {
            enqueue("wt_fetch_snapshot", "not-json")
        }
        val transport = SupabaseDirectWatchTogetherTransport(backend)

        assertFailsWith<IllegalArgumentException> { transport.fetchSnapshot(roomId) }

        val otherState = roomState().copy(roomId = otherRoomId)
        backend.enqueue("wt_fetch_snapshot", encoded(snapshotMessage(otherState)))
        val error = assertFailsWith<IllegalArgumentException> {
            transport.fetchSnapshot(roomId)
        }
        assertTrue(error.message.orEmpty().contains("another room"))
    }

    @Test
    fun `closing the transport is idempotent and blocks later requests`() = runBlocking {
        val backend = FakeWatchTogetherSupabaseBackend().apply {
            subscriptionPayloads = listOf(encoded(snapshotMessage()))
        }
        val transport = SupabaseDirectWatchTogetherTransport(backend)
        transport.subscribeToRoom(roomId)

        transport.close()
        transport.close()

        assertEquals(1, backend.subscriptionCloseCount)
        assertEquals(1, backend.closes)
        assertFailsWith<IllegalStateException> { transport.signInAnonymously() }
        Unit
    }
}

private data class Invocation(
    val function: String,
    val parameters: JsonObject,
)

private class FakeWatchTogetherSupabaseBackend : WatchTogetherSupabaseBackend {
    private val responses = mutableMapOf<String, MutableList<String>>()
    val invocations = mutableListOf<Invocation>()
    val otpRequests = mutableListOf<String>()
    val otpVerifications = mutableListOf<Pair<String, String>>()
    val lifecycleEvents = mutableListOf<String>()
    var subscriptionPayloads: List<String> = emptyList()
    var subscriptionTopic: String? = null
    var subscriptionEvent: String? = null
    var anonymousSignIns = 0
    var subscriptionCloseCount = 0
    var sessionClears = 0
    var closes = 0

    fun enqueue(function: String, response: String) {
        responses.getOrPut(function, ::mutableListOf).add(response)
    }

    override suspend fun signInAnonymously() {
        anonymousSignIns += 1
    }

    override suspend fun requestHostEmailOtp(email: String) {
        otpRequests += email
    }

    override suspend fun verifyHostEmailOtp(email: String, token: String) {
        otpVerifications += email to token
    }

    override suspend fun invoke(function: String, parameters: JsonObject): String {
        invocations += Invocation(function, parameters)
        return responses[function]?.removeAt(0) ?: error("No fake response for $function")
    }

    override suspend fun subscribePrivateRoom(
        topic: String,
        event: String,
    ): WatchTogetherSupabaseSubscription {
        subscriptionTopic = topic
        subscriptionEvent = event
        return object : WatchTogetherSupabaseSubscription {
            override val payloads: Flow<String> = flowOf(*subscriptionPayloads.toTypedArray())

            override suspend fun close() {
                subscriptionCloseCount += 1
                lifecycleEvents += "subscription.close"
            }
        }
    }

    override suspend fun clearSession() {
        sessionClears += 1
        lifecycleEvents += "auth.clear"
    }

    override suspend fun close() {
        closes += 1
    }
}

private val roomFunctions = listOf(
    "wt_close_room",
    "wt_begin_countdown",
    "wt_cancel_countdown",
    "wt_complete_countdown",
    "wt_begin_round",
    "wt_set_connection",
    "wt_set_admission",
)

private const val roomId = "11111111-1111-4111-8111-111111111111"
private const val otherRoomId = "99999999-9999-4999-8999-999999999999"
private const val hostParticipantId = "22222222-2222-4222-8222-222222222222"
private const val guestParticipantId = "33333333-3333-4333-8333-333333333333"
private const val sessionId = "44444444-4444-4444-8444-444444444444"
private const val roundId = "55555555-5555-4555-8555-555555555555"
private const val inviteSecret = "invite-secret-with-at-least-thirty-two-characters"
private const val replacementInviteSecret = "replacement-secret-with-at-least-thirty-two-characters"

private inline fun <reified T> encoded(value: T): String = WatchTogetherJson.encodeToString(value)

private fun createdRoom() = WatchTogetherCreatedRoom(
    roomCode = "ABCDEFGH",
    roomId = roomId,
    participantId = hostParticipantId,
    sessionId = sessionId,
    roundId = roundId,
    expiresAtMs = roomState().expiresAtMs,
    state = roomState(),
)

private fun pendingParticipant() = WatchTogetherPendingParticipant(
    participantId = guestParticipantId,
    displayName = "Pilot Guest",
    requestedAtMs = 2_000L,
)

private fun resolvedJoin() = WatchTogetherResolvedJoin(
    participantId = guestParticipantId,
    status = WatchTogetherJoinResolution.Admitted,
    state = roomState(),
)

private fun rotatedInvite() = WatchTogetherRotatedInvite(
    inviteGeneration = 1L,
    state = roomState(),
)

private fun snapshotMessage(state: WatchTogetherRoomState = roomState()) = WatchTogetherServerMessage(
    protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
    messageId = "server_message_0001",
    roomId = state.roomId,
    revision = state.revision,
    relayTimeMs = 12_000L,
    type = WatchTogetherServerMessageType.StateSnapshot,
    payload = buildJsonObject {
        put("state", WatchTogetherJson.encodeToJsonElement(state))
    },
)

private fun commandAcceptedMessage() = WatchTogetherServerMessage(
    protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
    messageId = "server_message_0002",
    roomId = roomId,
    revision = roomState().revision,
    relayTimeMs = 12_000L,
    type = WatchTogetherServerMessageType.CommandAccepted,
    payload = buildJsonObject {
        put("commandMessageId", "client_message_0001")
        put("applied", true)
    },
)

private fun command() = WatchTogetherClientCommand(
    protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
    messageId = "client_message_0001",
    roomId = roomId,
    roundId = roundId,
    participantId = hostParticipantId,
    sessionId = sessionId,
    sequence = 1L,
    sentAtMs = 10_000L,
    type = WatchTogetherClientCommandType.PlaybackPause,
    payload = JsonObject(emptyMap()),
)

private fun roomState() = WatchTogetherRoomState(
    protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
    roomId = roomId,
    revision = 1L,
    status = WatchTogetherRoomStatus.Open,
    createdAtMs = 1_000L,
    expiresAtMs = 61_000L,
    capacity = 4,
    hostParticipantId = hostParticipantId,
    admission = WatchTogetherAdmissionState(
        state = WatchTogetherAdmissionStatus.Open,
        inviteGeneration = 1L,
    ),
    participants = listOf(
        WatchTogetherParticipantState(
            participantId = hostParticipantId,
            displayName = "Pilot Host",
            role = WatchTogetherParticipantRole.Host,
            connection = WatchTogetherConnectionState.Connected,
            readiness = WatchTogetherReadinessState(
                roundId = roundId,
                sourceReady = false,
                viewerReady = false,
                durationMs = null,
                durationMismatchAcknowledged = false,
            ),
        ),
    ),
    round = WatchTogetherRoundState(
        roundId = roundId,
        generation = 1L,
        status = WatchTogetherRoundStatus.Preparing,
        playback = WatchTogetherPlaybackAnchor(
            mode = WatchTogetherPlaybackMode.Paused,
            anchorPositionMs = 42_000L,
            anchorRelayTimeMs = 1_000L,
            rate = 1,
        ),
    ),
)
