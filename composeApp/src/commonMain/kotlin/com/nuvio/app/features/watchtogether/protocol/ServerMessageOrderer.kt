package com.nuvio.app.features.watchtogether.protocol

internal sealed interface ServerMessageDecision {
    data class Accepted(
        val snapshotRevisionGap: Long,
    ) : ServerMessageDecision

    data object Duplicate : ServerMessageDecision
    data object ConflictingMessageId : ServerMessageDecision
    data object StaleSnapshot : ServerMessageDecision
    data object UnsupportedProtocol : ServerMessageDecision
    data object WrongRoom : ServerMessageDecision
}

internal class ServerMessageOrderer(
    private val roomId: String,
    private val protocolVersion: String,
    private val rememberedMessageLimit: Int = 256,
) {
    private val rememberedMessages = LinkedHashMap<String, WatchTogetherServerMessage>()
    private var lastSnapshotRevision: Long? = null

    init {
        require(rememberedMessageLimit > 0)
    }

    fun evaluate(message: WatchTogetherServerMessage): ServerMessageDecision {
        if (message.protocolVersion != protocolVersion) {
            return ServerMessageDecision.UnsupportedProtocol
        }
        if (message.roomId != roomId) return ServerMessageDecision.WrongRoom

        val remembered = rememberedMessages[message.messageId]
        if (remembered != null) {
            return if (remembered == message) {
                ServerMessageDecision.Duplicate
            } else {
                ServerMessageDecision.ConflictingMessageId
            }
        }
        remember(message)

        if (message.type != WatchTogetherServerMessageType.StateSnapshot) {
            return ServerMessageDecision.Accepted(snapshotRevisionGap = 0L)
        }

        val previousRevision = lastSnapshotRevision
        if (previousRevision != null && message.revision < previousRevision) {
            return ServerMessageDecision.StaleSnapshot
        }
        val gap = if (previousRevision == null || message.revision <= previousRevision) {
            0L
        } else {
            message.revision - previousRevision - 1L
        }
        lastSnapshotRevision = message.revision
        return ServerMessageDecision.Accepted(snapshotRevisionGap = gap)
    }

    fun reset() {
        rememberedMessages.clear()
        lastSnapshotRevision = null
    }

    private fun remember(message: WatchTogetherServerMessage) {
        rememberedMessages[message.messageId] = message
        while (rememberedMessages.size > rememberedMessageLimit) {
            val oldestKey = rememberedMessages.keys.first()
            rememberedMessages.remove(oldestKey)
        }
    }
}
