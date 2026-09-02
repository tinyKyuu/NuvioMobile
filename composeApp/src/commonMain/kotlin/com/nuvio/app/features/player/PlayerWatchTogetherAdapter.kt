package com.nuvio.app.features.player

import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerAdapter
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerCapability
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerCommand
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerCommandResult
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

internal class PlayerWatchTogetherAdapter(
    private val runtime: PlayerScreenRuntime,
    private val scope: CoroutineScope,
) : WatchTogetherPlayerAdapter {
    private val monotonicOrigin = TimeSource.Monotonic.markNow()
    private var rateRestoreJob: Job? = null

    override val capabilities: Set<WatchTogetherPlayerCapability> = setOf(
        WatchTogetherPlayerCapability.Pause,
        WatchTogetherPlayerCapability.Resume,
        WatchTogetherPlayerCapability.AbsoluteSeek,
        WatchTogetherPlayerCapability.TemporaryRate,
    )

    override suspend fun snapshot(): WatchTogetherPlayerSnapshot {
        val snapshot = runtime.playbackSnapshot
        return WatchTogetherPlayerSnapshot(
            localPositionMs = snapshot.positionMs.coerceAtLeast(0L),
            durationMs = snapshot.durationMs.takeIf { it > 0L },
            isPlaying = snapshot.isPlaying,
            capturedAtLocalTimeMs = monotonicOrigin.elapsedNow().inWholeMilliseconds,
        )
    }

    override suspend fun execute(
        command: WatchTogetherPlayerCommand,
    ): WatchTogetherPlayerCommandResult {
        val controller = runtime.playerController
            ?: return WatchTogetherPlayerCommandResult.Failed("Player is not ready")
        return try {
            when (command) {
                WatchTogetherPlayerCommand.Pause -> {
                    runtime.shouldPlay = false
                    controller.pause()
                }
                WatchTogetherPlayerCommand.Resume -> {
                    runtime.shouldPlay = true
                    controller.play()
                }
                is WatchTogetherPlayerCommand.SeekTo -> {
                    controller.seekTo(command.localPositionMs.coerceAtLeast(0L))
                    runtime.scheduleProgressSyncAfterSeek()
                }
                is WatchTogetherPlayerCommand.SetTemporaryRate -> {
                    rateRestoreJob?.cancel()
                    controller.setPlaybackSpeed(command.rate.toFloat())
                    rateRestoreJob = scope.launch {
                        delay(command.durationMs)
                        runtime.playerController?.setPlaybackSpeed(1f)
                    }
                }
            }
            WatchTogetherPlayerCommandResult.Applied
        } catch (error: Exception) {
            WatchTogetherPlayerCommandResult.Failed(error.message ?: "Player command failed")
        }
    }
}
