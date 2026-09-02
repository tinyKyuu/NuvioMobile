package com.nuvio.app.features.watchtogether.protocol

internal enum class WatchTogetherPlayerCapability {
    Pause,
    Resume,
    AbsoluteSeek,
    TemporaryRate,
}

internal data class WatchTogetherPlayerSnapshot(
    val localPositionMs: Long,
    val durationMs: Long?,
    val isPlaying: Boolean,
    val capturedAtLocalTimeMs: Long,
)

internal sealed interface WatchTogetherPlayerCommand {
    data object Pause : WatchTogetherPlayerCommand
    data object Resume : WatchTogetherPlayerCommand
    data class SeekTo(val localPositionMs: Long) : WatchTogetherPlayerCommand

    data class SetTemporaryRate(
        val rate: Double,
        val durationMs: Long,
    ) : WatchTogetherPlayerCommand
}

internal sealed interface WatchTogetherPlayerCommandResult {
    data object Applied : WatchTogetherPlayerCommandResult
    data class Unsupported(val capability: WatchTogetherPlayerCapability) :
        WatchTogetherPlayerCommandResult
    data class Failed(val reason: String) : WatchTogetherPlayerCommandResult
}

internal interface WatchTogetherPlayerAdapter {
    val capabilities: Set<WatchTogetherPlayerCapability>

    suspend fun snapshot(): WatchTogetherPlayerSnapshot

    suspend fun execute(
        command: WatchTogetherPlayerCommand,
    ): WatchTogetherPlayerCommandResult
}

internal fun WatchTogetherPlayerCommand.requiredCapability(): WatchTogetherPlayerCapability =
    when (this) {
        WatchTogetherPlayerCommand.Pause -> WatchTogetherPlayerCapability.Pause
        WatchTogetherPlayerCommand.Resume -> WatchTogetherPlayerCapability.Resume
        is WatchTogetherPlayerCommand.SeekTo -> WatchTogetherPlayerCapability.AbsoluteSeek
        is WatchTogetherPlayerCommand.SetTemporaryRate -> WatchTogetherPlayerCapability.TemporaryRate
    }
