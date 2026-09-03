package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.watchtogether.session.WatchTogetherDevelopmentState
import com.nuvio.app.features.watchtogether.session.WatchTogetherDevelopmentStatus
import kotlin.math.abs

@Composable
internal fun PlayerScreenRuntime.WatchTogetherDevelopmentDialog() {
    val state = watchTogetherState
    val session = watchTogetherSession ?: return
    val clipboard = LocalClipboardManager.current
    val busy = state.status == WatchTogetherDevelopmentStatus.Connecting ||
        state.status == WatchTogetherDevelopmentStatus.Reconnecting
    val active = state.isActive || state.status == WatchTogetherDevelopmentStatus.Connected

    PlayerSidePanel(
        visible = true,
        onDismiss = { showWatchTogetherPanel = false },
        width = 720.dp,
    ) {
        PlayerPanelHeader(
            title = "Watch Together",
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
        ) {
            TextButton(onClick = { showWatchTogetherPanel = false }) {
                Text("Close")
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            val useCompactColumns = maxWidth >= 560.dp && maxHeight <= 500.dp
            if (active) {
                ActiveRoomContent(
                    state = state,
                    useCompactColumns = useCompactColumns,
                    onCopyCode = {
                        state.roomCode?.let { clipboard.setText(AnnotatedString(formatRoomCode(it))) }
                    },
                    onTestReconnect = { session.simulateConnectionLoss() },
                    onLeave = { session.leave() },
                )
            } else {
                SetupContent(
                    endpoint = watchTogetherEndpointInput,
                    displayName = watchTogetherDisplayNameInput,
                    roomCode = watchTogetherRoomCodeInput,
                    busy = busy,
                    errorMessage = state.errorMessage,
                    useCompactColumns = useCompactColumns,
                    onEndpointChange = { watchTogetherEndpointInput = it },
                    onPasteEndpoint = {
                        clipboard.getText()?.text?.trim()?.let { watchTogetherEndpointInput = it }
                    },
                    onUseSimulatorEndpoint = {
                        watchTogetherEndpointInput = "ws://127.0.0.1:8787"
                    },
                    onDisplayNameChange = { watchTogetherDisplayNameInput = it.take(40) },
                    onRoomCodeChange = {
                        watchTogetherRoomCodeInput = normalizeRoomCode(it)
                    },
                    onCreate = {
                        session.createRoom(
                            watchTogetherEndpointInput,
                            watchTogetherDisplayNameInput,
                        )
                    },
                    onJoin = {
                        session.joinRoom(
                            watchTogetherEndpointInput,
                            watchTogetherDisplayNameInput,
                            watchTogetherRoomCodeInput,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupContent(
    endpoint: String,
    displayName: String,
    roomCode: String,
    busy: Boolean,
    errorMessage: String?,
    useCompactColumns: Boolean,
    onEndpointChange: (String) -> Unit,
    onPasteEndpoint: () -> Unit,
    onUseSimulatorEndpoint: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onRoomCodeChange: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    if (useCompactColumns) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ScrollablePane(modifier = Modifier.weight(1f)) {
                ConnectionFields(
                    endpoint = endpoint,
                    displayName = displayName,
                    busy = busy,
                    onEndpointChange = onEndpointChange,
                    onPasteEndpoint = onPasteEndpoint,
                    onUseSimulatorEndpoint = onUseSimulatorEndpoint,
                    onDisplayNameChange = onDisplayNameChange,
                )
            }
            ScrollablePane(modifier = Modifier.weight(1f)) {
                RoomActions(
                    roomCode = roomCode,
                    busy = busy,
                    errorMessage = errorMessage,
                    onRoomCodeChange = onRoomCodeChange,
                    onCreate = onCreate,
                    onJoin = onJoin,
                )
            }
        }
    } else {
        ScrollablePane(modifier = Modifier.fillMaxSize()) {
            ConnectionFields(
                endpoint = endpoint,
                displayName = displayName,
                busy = busy,
                onEndpointChange = onEndpointChange,
                onPasteEndpoint = onPasteEndpoint,
                onUseSimulatorEndpoint = onUseSimulatorEndpoint,
                onDisplayNameChange = onDisplayNameChange,
            )
            RoomActions(
                roomCode = roomCode,
                busy = busy,
                errorMessage = errorMessage,
                onRoomCodeChange = onRoomCodeChange,
                onCreate = onCreate,
                onJoin = onJoin,
            )
        }
    }
}

@Composable
private fun ColumnScope.ConnectionFields(
    endpoint: String,
    displayName: String,
    busy: Boolean,
    onEndpointChange: (String) -> Unit,
    onPasteEndpoint: () -> Unit,
    onUseSimulatorEndpoint: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
) {
    Text(
        "Development relay only. Nuvio shares timing and room names, never the title or source.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = endpoint,
        onValueChange = onEndpointChange,
        label = { Text("Relay WebSocket URL") },
        placeholder = { Text("ws://192.168.1.10:8787") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton(onClick = onPasteEndpoint, enabled = !busy) {
            Text("Paste")
        }
        TextButton(onClick = onUseSimulatorEndpoint, enabled = !busy) {
            Text("Use simulator relay")
        }
    }
    OutlinedTextField(
        value = displayName,
        onValueChange = onDisplayNameChange,
        label = { Text("Your name") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.RoomActions(
    roomCode: String,
    busy: Boolean,
    errorMessage: String?,
    onRoomCodeChange: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    Button(
        onClick = onCreate,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Create room")
    }
    Text("Or join an existing room", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(
        value = formatRoomCode(roomCode),
        onValueChange = onRoomCodeChange,
        label = { Text("Room code") },
        placeholder = { Text("ABCD-EFGH") },
        singleLine = true,
        enabled = !busy,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = FontFamily.Monospace,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onJoin,
        enabled = !busy && roomCode.length == 8,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Join room")
    }
    errorMessage?.let { ErrorText(it) }
}

@Composable
private fun ActiveRoomContent(
    state: WatchTogetherDevelopmentState,
    useCompactColumns: Boolean,
    onCopyCode: () -> Unit,
    onTestReconnect: () -> Unit,
    onLeave: () -> Unit,
) {
    if (useCompactColumns) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ScrollablePane(modifier = Modifier.weight(1f)) {
                RoomSummary(state, onCopyCode)
            }
            ScrollablePane(modifier = Modifier.weight(1f)) {
                RoomMetrics(state)
                RoomSessionActions(state, onTestReconnect, onLeave)
            }
        }
    } else {
        ScrollablePane(modifier = Modifier.fillMaxSize()) {
            RoomSummary(state, onCopyCode)
            RoomMetrics(state)
            RoomSessionActions(state, onTestReconnect, onLeave)
        }
    }
}

@Composable
private fun ColumnScope.RoomSummary(
    state: WatchTogetherDevelopmentState,
    onCopyCode: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatRoomCode(state.roomCode.orEmpty()),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
        )
        TextButton(onClick = onCopyCode, enabled = state.roomCode != null) {
            Text("Copy")
        }
    }
    Text(statusLabel(state.status), style = MaterialTheme.typography.labelLarge)
    state.participants.forEach { participant ->
        val role = if (participant.isHost) "Host" else "Guest"
        val connection = if (participant.isConnected) "connected" else "disconnected"
        Text("$role · ${participant.displayName} · $connection")
    }
}

@Composable
private fun ColumnScope.RoomMetrics(state: WatchTogetherDevelopmentState) {
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
    state.errorMessage?.let { ErrorText(it) }
}

@Composable
private fun ColumnScope.RoomSessionActions(
    state: WatchTogetherDevelopmentState,
    onTestReconnect: () -> Unit,
    onLeave: () -> Unit,
) {
    Button(
        onClick = onTestReconnect,
        enabled = state.status == WatchTogetherDevelopmentStatus.Connected,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Test reconnect")
    }
    Button(
        onClick = onLeave,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Leave room")
    }
}

@Composable
private fun ScrollablePane(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
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

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

internal fun normalizeRoomCode(value: String): String = value
    .uppercase()
    .filter { it in ROOM_CODE_ALPHABET }
    .take(8)

internal fun formatRoomCode(value: String): String {
    val normalized = normalizeRoomCode(value)
    return if (normalized.length > 4) {
        "${normalized.take(4)}-${normalized.drop(4)}"
    } else {
        normalized
    }
}

private fun statusLabel(status: WatchTogetherDevelopmentStatus): String = when (status) {
    WatchTogetherDevelopmentStatus.Disconnected -> "Disconnected"
    WatchTogetherDevelopmentStatus.Connecting -> "Connecting…"
    WatchTogetherDevelopmentStatus.Connected -> "Connected"
    WatchTogetherDevelopmentStatus.Reconnecting -> "Reconnecting…"
    WatchTogetherDevelopmentStatus.Error -> "Connection error"
}

private const val ROOM_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
