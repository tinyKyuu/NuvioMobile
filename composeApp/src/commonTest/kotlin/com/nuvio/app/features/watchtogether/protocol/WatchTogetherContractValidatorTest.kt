package com.nuvio.app.features.watchtogether.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTogetherContractValidatorTest {
    @Test
    fun `validates a content-blind canonical snapshot`() {
        val state = validRoomState()
        val message = WatchTogetherServerMessage(
            protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
            messageId = "message_snapshot_1",
            roomId = state.roomId,
            revision = state.revision,
            relayTimeMs = 12_000L,
            type = WatchTogetherServerMessageType.StateSnapshot,
            payload = buildJsonObject {
                put(
                    "state",
                    WatchTogetherJson.parseToJsonElement(WatchTogetherJson.encodeToString(state)),
                )
            },
        )

        assertTrue(WatchTogetherContractValidator.validate(state).isValid)
        assertTrue(WatchTogetherContractValidator.validate(message).isValid)
    }

    @Test
    fun `rejects inconsistent host readiness and snapshot revisions`() {
        val state = validRoomState().let { valid ->
            valid.copy(
                hostParticipantId = "participant_missing",
                participants = valid.participants.map { participant ->
                    participant.copy(
                        displayName = "   ",
                        readiness = participant.readiness.copy(
                            roundId = "round_stale_0001",
                            durationMs = 90_500L,
                        ),
                    )
                },
            )
        }
        val message = WatchTogetherServerMessage(
            protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
            messageId = "message_snapshot_2",
            roomId = state.roomId,
            revision = state.revision + 1L,
            relayTimeMs = 12_000L,
            type = WatchTogetherServerMessageType.StateSnapshot,
            payload = buildJsonObject {
                put(
                    "state",
                    WatchTogetherJson.parseToJsonElement(WatchTogetherJson.encodeToString(state)),
                )
            },
        )

        val paths = WatchTogetherContractValidator.validate(message).issues.map { it.path }.toSet()

        assertTrue("payload.state.hostParticipantId" in paths)
        assertTrue("payload.state.participants[0].displayName" in paths)
        assertTrue("payload.state.participants[0].readiness.roundId" in paths)
        assertTrue("payload.state.participants[0].readiness.durationMs" in paths)
        assertTrue("payload.state.revision" in paths)
    }

    @Test
    fun `validates command payload shape and safe integer limits`() {
        val invalid = WatchTogetherClientCommand(
            protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
            messageId = "message_command_1",
            roomId = "room_demo_0001",
            roundId = "round_demo_0001",
            participantId = "participant_demo_0001",
            sessionId = "session_demo_0001",
            sequence = 0L,
            sentAtMs = WATCH_TOGETHER_MAX_SAFE_INTEGER + 1L,
            type = WatchTogetherClientCommandType.PlaybackSeek,
            payload = buildJsonObject { put("unexpected", true) },
        )

        val result = WatchTogetherContractValidator.validate(invalid)

        assertFalse(result.isValid)
        assertEquals(setOf("sequence", "sentAtMs", "payload"), result.issues.map { it.path }.toSet())
    }

    @Test
    fun `requires countdown timing only while a round is counting down`() {
        val missingCountdown = validRoomState().copy(
            round = validRoomState().round.copy(
                status = WatchTogetherRoundStatus.Countdown,
                playback = validRoomState().round.playback.copy(
                    mode = WatchTogetherPlaybackMode.Paused,
                ),
            )
        )
        assertTrue(
            WatchTogetherContractValidator.validate(missingCountdown).issues
                .any { it.path == "round.countdown" }
        )

        val validCountdown = missingCountdown.copy(
            round = missingCountdown.round.copy(
                countdown = WatchTogetherCountdownState(
                    startedAtRelayTimeMs = 12_000L,
                    endsAtRelayTimeMs = 17_000L,
                ),
            )
        )
        assertTrue(WatchTogetherContractValidator.validate(validCountdown).isValid)
    }
}

private fun validRoomState(): WatchTogetherRoomState = WatchTogetherRoomState(
    protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
    roomId = "room_demo_0001",
    revision = 3L,
    status = WatchTogetherRoomStatus.Open,
    createdAtMs = 1_000L,
    expiresAtMs = 21_000L,
    capacity = 2,
    hostParticipantId = "participant_host_0001",
    admission = WatchTogetherAdmissionState(
        state = WatchTogetherAdmissionStatus.Open,
        inviteGeneration = 1L,
    ),
    participants = listOf(
        WatchTogetherParticipantState(
            participantId = "participant_host_0001",
            displayName = "Host",
            role = WatchTogetherParticipantRole.Host,
            connection = WatchTogetherConnectionState.Connected,
            readiness = WatchTogetherReadinessState(
                roundId = "round_demo_0001",
                sourceReady = true,
                viewerReady = true,
                durationMs = 90_000L,
                durationMismatchAcknowledged = false,
            ),
        )
    ),
    round = WatchTogetherRoundState(
        roundId = "round_demo_0001",
        generation = 1L,
        status = WatchTogetherRoundStatus.Active,
        playback = WatchTogetherPlaybackAnchor(
            mode = WatchTogetherPlaybackMode.Playing,
            anchorPositionMs = 2_000L,
            anchorRelayTimeMs = 10_000L,
            rate = 1,
        ),
    ),
)
