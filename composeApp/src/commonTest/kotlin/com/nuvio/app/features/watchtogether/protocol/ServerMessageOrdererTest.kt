package com.nuvio.app.features.watchtogether.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerMessageOrdererTest {
    @Test
    fun `snapshots are ordered while command responses may reference older revisions`() {
        val orderer = ServerMessageOrderer(
            roomId = "room_test_0001",
            protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
        )

        assertEquals(
            ServerMessageDecision.Accepted(snapshotRevisionGap = 0L),
            orderer.evaluate(message(id = "server_0001", revision = 4L)),
        )
        assertEquals(
            ServerMessageDecision.Accepted(snapshotRevisionGap = 2L),
            orderer.evaluate(message(id = "server_0002", revision = 7L)),
        )
        assertEquals(
            ServerMessageDecision.StaleSnapshot,
            orderer.evaluate(message(id = "server_0003", revision = 6L)),
        )
        assertEquals(
            ServerMessageDecision.Accepted(snapshotRevisionGap = 0L),
            orderer.evaluate(
                message(
                    id = "server_0004",
                    revision = 3L,
                    type = WatchTogetherServerMessageType.CommandRejected,
                )
            ),
        )
    }

    @Test
    fun `message ids distinguish exact replay from conflicting reuse`() {
        val orderer = ServerMessageOrderer(
            roomId = "room_test_0001",
            protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
        )
        val original = message(id = "server_1001", revision = 2L)

        orderer.evaluate(original)

        assertEquals(ServerMessageDecision.Duplicate, orderer.evaluate(original))
        assertEquals(
            ServerMessageDecision.ConflictingMessageId,
            orderer.evaluate(original.copy(revision = 3L)),
        )
    }
}

private fun message(
    id: String,
    revision: Long,
    type: WatchTogetherServerMessageType = WatchTogetherServerMessageType.StateSnapshot,
): WatchTogetherServerMessage = WatchTogetherServerMessage(
    protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
    messageId = id,
    roomId = "room_test_0001",
    revision = revision,
    relayTimeMs = 10_000L,
    type = type,
    payload = buildJsonObject { put("test", true) },
)
