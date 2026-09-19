package com.nuvio.app.features.watchtogether.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchTogetherConformanceTest {
    @Test
    fun `Kotlin client core passes every public protocol fixture`() {
        assertEquals(
            setOf(
                "pause-resume.json",
                "readiness-countdown.json",
                "readiness-next-round.json",
                "reconnect-stale-sequence.json",
                "seek-idempotency.json",
            ),
            WatchTogetherConformanceFixtureJson.keys,
        )

        WatchTogetherConformanceFixtureJson.forEach { (fileName, fixtureJson) ->
            val fixture = WatchTogetherJson.decodeFromString<ConformanceFixture>(fixtureJson)
            val finalState = FixtureRunner(fixture).run()
            val encodedState = WatchTogetherJson.encodeToString(finalState)
            val decodedState = WatchTogetherJson.decodeFromString<WatchTogetherRoomState>(
                encodedState
            )

            assertEquals(finalState, decodedState, fileName)
            assertFalse(encodedState.contains("contentId"), fileName)
            assertFalse(encodedState.contains("streamUrl"), fileName)
        }
    }
}

@Serializable
private data class ConformanceFixture(
    @SerialName("\$schema")
    val schema: String,
    val name: String,
    val clockStartMs: Long,
    val room: FixtureRoom,
    val participants: List<FixtureParticipant>,
    val steps: List<JsonObject>,
)

@Serializable
private data class FixtureRoom(
    val roomId: String,
    val hostParticipantId: String,
    val hostDisplayName: String,
    val hostSessionId: String,
    val roundId: String,
    val capacity: Int,
    val lifetimeMs: Long,
)

@Serializable
private data class FixtureParticipant(
    val participantId: String,
    val displayName: String,
    val sessionId: String,
)

private class FixtureRunner(
    private val fixture: ConformanceFixture,
) {
    private data class SessionState(
        var activeSessionId: String?,
        var lastSequence: Long,
    )

    private data class FixtureResponse(
        val serverMessageId: String,
        val type: String,
        val revision: Long,
        val applied: Boolean? = null,
        val code: String? = null,
    )

    private data class ProcessedCommand(
        val command: WatchTogetherClientCommand,
        val response: FixtureResponse,
    )

    private var nowMs = fixture.clockStartMs
    private var responseCounter = 0L
    private val sessions = mutableMapOf<String, SessionState>()
    private val processedCommands = mutableMapOf<String, ProcessedCommand>()
    private val usedRoundIds = mutableSetOf(fixture.room.roundId)
    private val labelledResponses = mutableMapOf<String, FixtureResponse>()
    private var state = initialRoomState()

    fun run(): WatchTogetherRoomState {
        sessions[fixture.room.hostParticipantId] = SessionState(
            activeSessionId = fixture.room.hostSessionId,
            lastSequence = 0L,
        )
        fixture.participants.forEach(::joinParticipant)

        fixture.steps.forEachIndexed { index, step ->
            runCatching { applyStep(step) }
                .getOrElse { error ->
                    throw AssertionError(
                        "${fixture.name} step ${index + 1}: " +
                            "${error::class.simpleName}: ${error.message}",
                    )
                }
        }
        return state
    }

    private fun initialRoomState(): WatchTogetherRoomState = WatchTogetherRoomState(
        protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
        roomId = fixture.room.roomId,
        revision = 1L,
        status = WatchTogetherRoomStatus.Open,
        createdAtMs = fixture.clockStartMs,
        expiresAtMs = fixture.clockStartMs + fixture.room.lifetimeMs,
        capacity = fixture.room.capacity,
        hostParticipantId = fixture.room.hostParticipantId,
        admission = WatchTogetherAdmissionState(
            state = WatchTogetherAdmissionStatus.Open,
            inviteGeneration = 1L,
        ),
        participants = listOf(
            WatchTogetherParticipantState(
                participantId = fixture.room.hostParticipantId,
                displayName = fixture.room.hostDisplayName,
                role = WatchTogetherParticipantRole.Host,
                connection = WatchTogetherConnectionState.Connected,
                readiness = emptyReadiness(fixture.room.roundId),
            )
        ),
        round = WatchTogetherRoundState(
            roundId = fixture.room.roundId,
            generation = 1L,
            status = WatchTogetherRoundStatus.Preparing,
            playback = WatchTogetherPlaybackAnchor(
                mode = WatchTogetherPlaybackMode.Paused,
                anchorPositionMs = 0L,
                anchorRelayTimeMs = fixture.clockStartMs,
                rate = 1,
            ),
        ),
    )

    private fun joinParticipant(participant: FixtureParticipant) {
        require(state.participants.size < state.capacity)
        state = state.copy(
            revision = state.revision + 1L,
            participants = state.participants + WatchTogetherParticipantState(
                participantId = participant.participantId,
                displayName = participant.displayName,
                role = WatchTogetherParticipantRole.Guest,
                connection = WatchTogetherConnectionState.Connected,
                readiness = emptyReadiness(state.round.roundId),
            ),
        )
        sessions[participant.participantId] = SessionState(
            activeSessionId = participant.sessionId,
            lastSequence = 0L,
        )
    }

    private fun applyStep(step: JsonObject) {
        when (step.requiredString("op")) {
            "advance" -> nowMs += step.requiredLong("byMs")
            "command" -> applyCommandStep(step)
            "disconnect" -> disconnect(step)
            "reconnect" -> reconnect(step)
            "nextRound" -> nextRound(step)
            "beginCountdown" -> beginCountdown(step)
            "completeCountdown" -> completeCountdown(step)
            "assert" -> assertState(step.requiredObject("expect"))
            else -> error("Unsupported fixture operation")
        }
    }

    private fun applyCommandStep(step: JsonObject) {
        val command = WatchTogetherJson.decodeFromJsonElement<WatchTogetherClientCommand>(
            step.getValue("command")
        )
        val response = applyCommand(command)
        val expectation = step.requiredObject("expect")

        assertEquals(expectation.requiredString("type"), response.type)
        assertEquals(expectation.requiredLong("revision"), response.revision)
        expectation.optionalBoolean("applied")?.let { assertEquals(it, response.applied) }
        expectation.optionalString("code")?.let { assertEquals(it, response.code) }
        expectation.optionalString("sameAs")?.let { label ->
            assertEquals(labelledResponses[label], response)
        }
        step.optionalString("label")?.let { labelledResponses[it] = response }
    }

    private fun applyCommand(command: WatchTogetherClientCommand): FixtureResponse {
        require(command.roomId == state.roomId)
        if (nowMs >= state.expiresAtMs) return rejected(command, "ROOM_EXPIRED")
        if (command.protocolVersion != WATCH_TOGETHER_PROTOCOL_VERSION) {
            return rejected(command, "UNSUPPORTED_PROTOCOL")
        }

        val processed = processedCommands[command.messageId]
        if (processed != null) {
            return if (processed.command == command) {
                processed.response
            } else {
                rejected(command, "MESSAGE_ID_REUSE", remember = false)
            }
        }

        val session = sessions[command.participantId]
            ?: return rejected(command, "PARTICIPANT_NOT_FOUND")
        if (session.activeSessionId != command.sessionId) {
            return rejected(command, "SESSION_NOT_ACTIVE")
        }
        if (command.sequence <= session.lastSequence) {
            return rejected(command, "STALE_SEQUENCE")
        }
        if (command.roundId != state.round.roundId) {
            return rejected(command, "ROUND_MISMATCH")
        }

        val applied = applyAcceptedCommand(command)
        session.lastSequence = command.sequence
        val response = FixtureResponse(
            serverMessageId = nextResponseId(),
            type = "command.accepted",
            revision = state.revision,
            applied = applied,
        )
        processedCommands[command.messageId] = ProcessedCommand(command, response)
        return response
    }

    private fun applyAcceptedCommand(command: WatchTogetherClientCommand): Boolean =
        when (command.type) {
            WatchTogetherClientCommandType.PlaybackPause -> pause()
            WatchTogetherClientCommandType.PlaybackResume -> resume()
            WatchTogetherClientCommandType.PlaybackSeek -> seek(command.seekPayload().positionMs)
            WatchTogetherClientCommandType.ParticipantReadiness -> {
                readiness(command.participantId, command.readinessPayload())
            }
        }

    private fun pause(): Boolean {
        val playback = state.round.playback
        if (playback.mode == WatchTogetherPlaybackMode.Paused) return false
        val positionMs = WatchTogetherCanonicalClock.positionAt(playback, nowMs)
        mutateRound(
            state.round.copy(
                playback = WatchTogetherPlaybackAnchor(
                    mode = WatchTogetherPlaybackMode.Paused,
                    anchorPositionMs = positionMs,
                    anchorRelayTimeMs = nowMs,
                    rate = 1,
                )
            )
        )
        return true
    }

    private fun resume(): Boolean {
        val playback = state.round.playback
        if (playback.mode == WatchTogetherPlaybackMode.Playing) return false
        mutateRound(
            state.round.copy(
                status = WatchTogetherRoundStatus.Active,
                playback = playback.copy(
                    mode = WatchTogetherPlaybackMode.Playing,
                    anchorRelayTimeMs = nowMs,
                ),
            )
        )
        return true
    }

    private fun seek(positionMs: Long): Boolean {
        val playback = state.round.playback
        val applied = positionMs != WatchTogetherCanonicalClock.positionAt(playback, nowMs) ||
            playback.anchorRelayTimeMs != nowMs
        if (!applied) return false
        mutateRound(
            state.round.copy(
                playback = playback.copy(
                    anchorPositionMs = positionMs,
                    anchorRelayTimeMs = nowMs,
                )
            )
        )
        return true
    }

    private fun readiness(
        participantId: String,
        payload: WatchTogetherReadinessPayload,
    ): Boolean {
        val participant = participant(participantId)
        val nextReadiness = WatchTogetherReadinessState(
            roundId = state.round.roundId,
            sourceReady = payload.sourceReady,
            viewerReady = payload.viewerReady,
            durationMs = payload.durationMs,
            durationMismatchAcknowledged = payload.durationMismatchAcknowledged,
        )
        if (participant.readiness == nextReadiness) return false
        replaceParticipant(participant.copy(readiness = nextReadiness), incrementRevision = true)
        return true
    }

    private fun rejected(
        command: WatchTogetherClientCommand,
        code: String,
        remember: Boolean = true,
    ): FixtureResponse {
        val response = FixtureResponse(
            serverMessageId = nextResponseId(),
            type = "command.rejected",
            revision = state.revision,
            code = code,
        )
        if (remember) {
            processedCommands[command.messageId] = ProcessedCommand(command, response)
        }
        return response
    }

    private fun disconnect(step: JsonObject) {
        val participantId = step.requiredString("participantId")
        val session = assertNotNull(sessions[participantId])
        assertEquals(step.requiredString("sessionId"), session.activeSessionId)
        session.activeSessionId = null
        val participant = participant(participantId)
        if (participant.connection != WatchTogetherConnectionState.Disconnected) {
            replaceParticipant(
                participant.copy(connection = WatchTogetherConnectionState.Disconnected),
                incrementRevision = true,
            )
        }
        assertEquals(step.requiredLong("revision"), state.revision)
    }

    private fun reconnect(step: JsonObject) {
        val participantId = step.requiredString("participantId")
        val session = assertNotNull(sessions[participantId])
        session.activeSessionId = step.requiredString("sessionId")
        session.lastSequence = 0L
        val participant = participant(participantId)
        if (participant.connection != WatchTogetherConnectionState.Connected) {
            replaceParticipant(
                participant.copy(connection = WatchTogetherConnectionState.Connected),
                incrementRevision = true,
            )
        }
        assertEquals(step.requiredLong("revision"), state.revision)
    }

    private fun nextRound(step: JsonObject) {
        val roundId = step.requiredString("roundId")
        assertTrue(usedRoundIds.add(roundId))
        state = state.copy(
            revision = state.revision + 1L,
            participants = state.participants.map { participant ->
                participant.copy(readiness = emptyReadiness(roundId))
            },
            round = WatchTogetherRoundState(
                roundId = roundId,
                generation = state.round.generation + 1L,
                status = WatchTogetherRoundStatus.Preparing,
                playback = WatchTogetherPlaybackAnchor(
                    mode = WatchTogetherPlaybackMode.Paused,
                    anchorPositionMs = 0L,
                    anchorRelayTimeMs = nowMs,
                    rate = 1,
                ),
            ),
        )
        assertEquals(step.requiredLong("revision"), state.revision)
    }

    private fun beginCountdown(step: JsonObject) {
        assertEquals(WatchTogetherRoundStatus.Preparing, state.round.status)
        assertEquals(WatchTogetherPlaybackMode.Paused, state.round.playback.mode)
        assertTrue(WatchTogetherReadinessGate.evaluate(state).ready)
        state = state.copy(
            revision = state.revision + 1L,
            round = state.round.copy(
                status = WatchTogetherRoundStatus.Countdown,
                countdown = WatchTogetherCountdownState(
                    startedAtRelayTimeMs = nowMs,
                    endsAtRelayTimeMs = nowMs + WATCH_TOGETHER_DEFAULT_COUNTDOWN_DURATION_MS,
                ),
                playback = state.round.playback.copy(anchorRelayTimeMs = nowMs),
            ),
        )
        assertEquals(step.requiredLong("revision"), state.revision)
    }

    private fun completeCountdown(step: JsonObject) {
        val countdown = assertNotNull(state.round.countdown)
        assertTrue(nowMs >= countdown.endsAtRelayTimeMs)
        state = state.copy(
            revision = state.revision + 1L,
            round = state.round.copy(
                status = WatchTogetherRoundStatus.Active,
                countdown = null,
                playback = state.round.playback.copy(
                    mode = WatchTogetherPlaybackMode.Playing,
                    anchorRelayTimeMs = countdown.endsAtRelayTimeMs,
                ),
            ),
        )
        assertEquals(step.requiredLong("revision"), state.revision)
    }

    private fun assertState(expectation: JsonObject) {
        expectation.optionalLong("revision")?.let { assertEquals(it, state.revision) }
        expectation.optionalString("roundId")?.let { assertEquals(it, state.round.roundId) }
        expectation.optionalLong("roundGeneration")?.let {
            assertEquals(it, state.round.generation)
        }
        expectation.optionalString("roundStatus")?.let {
            assertEquals(it, state.round.status.wireName())
        }
        expectation.optionalString("playbackMode")?.let {
            assertEquals(it, state.round.playback.mode.wireName())
        }
        expectation.optionalLong("canonicalPositionMs")?.let {
            assertEquals(it, WatchTogetherCanonicalClock.positionAt(state.round.playback, nowMs))
        }
        expectation.optionalLong("countdownStartedAtRelayTimeMs")?.let {
            assertEquals(it, state.round.countdown?.startedAtRelayTimeMs)
        }
        expectation.optionalLong("countdownEndsAtRelayTimeMs")?.let {
            assertEquals(it, state.round.countdown?.endsAtRelayTimeMs)
        }
        expectation["participant"]?.jsonObject?.let { participantExpectation ->
            val participant = participant(participantExpectation.requiredString("participantId"))
            participantExpectation.optionalString("connection")?.let {
                assertEquals(it, participant.connection.wireName())
            }
            participantExpectation["readiness"]?.jsonObject?.let { readiness ->
                assertEquals(readiness.requiredBoolean("sourceReady"), participant.readiness.sourceReady)
                assertEquals(readiness.requiredBoolean("viewerReady"), participant.readiness.viewerReady)
                assertEquals(readiness.optionalLong("durationMs"), participant.readiness.durationMs)
                assertEquals(
                    readiness.requiredBoolean("durationMismatchAcknowledged"),
                    participant.readiness.durationMismatchAcknowledged,
                )
            }
        }
    }

    private fun mutateRound(round: WatchTogetherRoundState) {
        state = state.copy(
            revision = state.revision + 1L,
            round = round,
        )
    }

    private fun participant(participantId: String): WatchTogetherParticipantState =
        assertNotNull(state.participants.firstOrNull { it.participantId == participantId })

    private fun replaceParticipant(
        participant: WatchTogetherParticipantState,
        incrementRevision: Boolean,
    ) {
        state = state.copy(
            revision = state.revision + if (incrementRevision) 1L else 0L,
            participants = state.participants.map { current ->
                if (current.participantId == participant.participantId) participant else current
            },
        )
    }

    private fun nextResponseId(): String {
        responseCounter += 1L
        return "fixture_response_$responseCounter"
    }
}

private fun emptyReadiness(roundId: String): WatchTogetherReadinessState =
    WatchTogetherReadinessState(
        roundId = roundId,
        sourceReady = false,
        viewerReady = false,
        durationMs = null,
        durationMismatchAcknowledged = false,
    )

private fun JsonObject.requiredObject(name: String): JsonObject =
    getValue(name).jsonObject

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.optionalString(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.long

private fun JsonObject.optionalLong(name: String): Long? =
    get(name)?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject.requiredBoolean(name: String): Boolean =
    getValue(name).jsonPrimitive.boolean

private fun JsonObject.optionalBoolean(name: String): Boolean? =
    get(name)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun WatchTogetherRoundStatus.wireName(): String = when (this) {
    WatchTogetherRoundStatus.Preparing -> "preparing"
    WatchTogetherRoundStatus.Countdown -> "countdown"
    WatchTogetherRoundStatus.Active -> "active"
    WatchTogetherRoundStatus.Ended -> "ended"
}

private fun WatchTogetherPlaybackMode.wireName(): String = when (this) {
    WatchTogetherPlaybackMode.Paused -> "paused"
    WatchTogetherPlaybackMode.Playing -> "playing"
}

private fun WatchTogetherConnectionState.wireName(): String = when (this) {
    WatchTogetherConnectionState.Connected -> "connected"
    WatchTogetherConnectionState.Disconnected -> "disconnected"
}
