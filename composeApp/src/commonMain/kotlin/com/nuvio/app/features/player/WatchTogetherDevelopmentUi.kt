package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.watchtogether.session.WatchTogetherDevelopmentStatus
import kotlin.math.abs

@Composable
internal fun PlayerScreenRuntime.WatchTogetherDevelopmentDialog() {
    val state = watchTogetherState
    val session = watchTogetherSession ?: return
    val busy = state.status == WatchTogetherDevelopmentStatus.Connecting ||
        state.status == WatchTogetherDevelopmentStatus.Reconnecting

    AlertDialog(
        onDismissRequest = { showWatchTogetherPanel = false },
        title = { Text("Watch Together · Phase 3") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Development relay only. Nuvio sends playback timing, room-scoped names, " +
                        "and session identifiers—never the title or stream source.",
                    style = MaterialTheme.typography.bodySmall,
                )

                if (!state.isActive && state.status != WatchTogetherDevelopmentStatus.Connected) {
                    OutlinedTextField(
                        value = watchTogetherEndpointInput,
                        onValueChange = { watchTogetherEndpointInput = it },
                        label = { Text("Relay WebSocket URL") },
                        placeholder = { Text("ws://192.168.1.10:8787") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = watchTogetherDisplayNameInput,
                        onValueChange = { watchTogetherDisplayNameInput = it.take(40) },
                        label = { Text("Room display name") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            session.createRoom(
                                watchTogetherEndpointInput,
                                watchTogetherDisplayNameInput,
                            )
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Create room")
                    }
                    OutlinedTextField(
                        value = watchTogetherRoomCodeInput,
                        onValueChange = {
                            watchTogetherRoomCodeInput = it
                                .uppercase()
                                .filter(Char::isLetterOrDigit)
                                .take(8)
                        },
                        label = { Text("8-character room code") },
                        singleLine = true,
                        enabled = !busy,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            session.joinRoom(
                                watchTogetherEndpointInput,
                                watchTogetherDisplayNameInput,
                                watchTogetherRoomCodeInput,
                            )
                        },
                        enabled = !busy && watchTogetherRoomCodeInput.length == 8,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Join room")
                    }
                } else {
                    Text(
                        text = state.roomCode.orEmpty(),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(statusLabel(state.status), style = MaterialTheme.typography.labelLarge)
                    state.participants.forEach { participant ->
                        val role = if (participant.isHost) "Host" else "Guest"
                        val connection = if (participant.isConnected) "connected" else "disconnected"
                        Text("$role · ${participant.displayName} · $connection")
                    }
                    DevelopmentMetric("Canonical", state.canonicalPositionMs?.let(::formatPlaybackTime))
                    DevelopmentMetric(
                        "Drift",
                        state.driftMs?.let { drift ->
                            val prefix = if (drift > 0L) "+" else ""
                            "$prefix${drift} ms (${if (abs(drift) <= 250L) "in range" else "correcting"})"
                        },
                    )
                    DevelopmentMetric("Relay RTT", state.roundTripMs?.let { "$it ms" })
                    DevelopmentMetric("Clock samples", state.clockSampleCount.toString())
                    DevelopmentMetric("Last correction", state.correction)
                    DevelopmentMetric("Reconnects", state.reconnectCount.toString())
                    Button(
                        onClick = { session.leave() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Leave room")
                    }
                }

                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showWatchTogetherPanel = false }) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun DevelopmentMetric(label: String, value: String?) {
    if (value == null) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun statusLabel(status: WatchTogetherDevelopmentStatus): String = when (status) {
    WatchTogetherDevelopmentStatus.Disconnected -> "Disconnected"
    WatchTogetherDevelopmentStatus.Connecting -> "Connecting…"
    WatchTogetherDevelopmentStatus.Connected -> "Connected"
    WatchTogetherDevelopmentStatus.Reconnecting -> "Reconnecting…"
    WatchTogetherDevelopmentStatus.Error -> "Connection error"
}
