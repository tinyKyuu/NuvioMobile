package com.nuvio.app.features.watchtogether.protocol

import kotlinx.serialization.json.decodeFromJsonElement

internal fun WatchTogetherClientCommand.seekPayload(): WatchTogetherSeekPayload {
    require(type == WatchTogetherClientCommandType.PlaybackSeek)
    return WatchTogetherJson.decodeFromJsonElement(payload)
}

internal fun WatchTogetherClientCommand.readinessPayload(): WatchTogetherReadinessPayload {
    require(type == WatchTogetherClientCommandType.ParticipantReadiness)
    return WatchTogetherJson.decodeFromJsonElement(payload)
}

internal fun WatchTogetherServerMessage.acceptedPayload(): WatchTogetherCommandAcceptedPayload {
    require(type == WatchTogetherServerMessageType.CommandAccepted)
    return WatchTogetherJson.decodeFromJsonElement(payload)
}

internal fun WatchTogetherServerMessage.rejectedPayload(): WatchTogetherCommandRejectedPayload {
    require(type == WatchTogetherServerMessageType.CommandRejected)
    return WatchTogetherJson.decodeFromJsonElement(payload)
}

internal fun WatchTogetherServerMessage.snapshotPayload(): WatchTogetherStateSnapshotPayload {
    require(type == WatchTogetherServerMessageType.StateSnapshot)
    return WatchTogetherJson.decodeFromJsonElement(payload)
}
