package com.nuvio.app.features.watchtogether.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTogetherReadinessGateTest {
    @Test
    fun `accepts connected ready participants within the duration tolerance`() {
        val evaluation = WatchTogetherReadinessGate.evaluate(
            roomState(
                host = participant("participant_host", 3_600_000L),
                guest = participant("participant_guest", 3_602_000L),
            )
        )

        assertTrue(evaluation.ready)
        assertFalse(evaluation.durationMismatch)
        assertEquals(2_000L, evaluation.durationSpreadMs)
    }

    @Test
    fun `requires every participant to acknowledge a duration mismatch`() {
        val blocked = WatchTogetherReadinessGate.evaluate(
            roomState(
                host = participant("participant_host", 3_600_000L),
                guest = participant("participant_guest", 3_606_000L),
            )
        )

        assertFalse(blocked.ready)
        assertTrue(blocked.durationMismatch)
        assertEquals(
            listOf("participant_host", "participant_guest"),
            blocked.mismatchUnacknowledgedParticipantIds,
        )

        val accepted = WatchTogetherReadinessGate.evaluate(
            roomState(
                host = participant("participant_host", 3_600_000L, acknowledged = true),
                guest = participant("participant_guest", 3_606_000L, acknowledged = true),
            )
        )
        assertTrue(accepted.ready)
    }

    @Test
    fun `blocks missing duration and disconnected participants`() {
        val evaluation = WatchTogetherReadinessGate.evaluate(
            roomState(
                host = participant("participant_host", 3_600_000L),
                guest = participant(
                    id = "participant_guest",
                    durationMs = null,
                    connection = WatchTogetherConnectionState.Disconnected,
                ),
            )
        )

        assertFalse(evaluation.ready)
        assertEquals(listOf("participant_guest"), evaluation.disconnectedParticipantIds)
        assertEquals(listOf("participant_guest"), evaluation.durationPendingParticipantIds)
    }
}

private fun participant(
    id: String,
    durationMs: Long?,
    acknowledged: Boolean = false,
    connection: WatchTogetherConnectionState = WatchTogetherConnectionState.Connected,
): WatchTogetherParticipantState = WatchTogetherParticipantState(
    participantId = id,
    displayName = id,
    role = if (id == "participant_host") {
        WatchTogetherParticipantRole.Host
    } else {
        WatchTogetherParticipantRole.Guest
    },
    connection = connection,
    readiness = WatchTogetherReadinessState(
        roundId = "round_test_0001",
        sourceReady = true,
        viewerReady = true,
        durationMs = durationMs,
        durationMismatchAcknowledged = acknowledged,
    ),
)

private fun roomState(
    host: WatchTogetherParticipantState,
    guest: WatchTogetherParticipantState,
): WatchTogetherRoomState = WatchTogetherRoomState(
    protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
    roomId = "room_test_0001",
    revision = 1L,
    status = WatchTogetherRoomStatus.Open,
    createdAtMs = 1_000L,
    expiresAtMs = 21_601_000L,
    capacity = 2,
    hostParticipantId = host.participantId,
    admission = WatchTogetherAdmissionState(
        state = WatchTogetherAdmissionStatus.Open,
        inviteGeneration = 1L,
    ),
    participants = listOf(host, guest),
    round = WatchTogetherRoundState(
        roundId = "round_test_0001",
        generation = 1L,
        status = WatchTogetherRoundStatus.Preparing,
        playback = WatchTogetherPlaybackAnchor(
            mode = WatchTogetherPlaybackMode.Paused,
            anchorPositionMs = 0L,
            anchorRelayTimeMs = 1_000L,
            rate = 1,
        ),
    ),
)
