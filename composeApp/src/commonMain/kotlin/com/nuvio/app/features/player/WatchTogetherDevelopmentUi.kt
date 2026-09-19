package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.watchtogether.session.WatchTogetherDevelopmentState
import com.nuvio.app.features.watchtogether.session.WatchTogetherDevelopmentStatus
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerScreenRuntime.WatchTogetherDevelopmentDialog() {
    val state = watchTogetherState
    val session = watchTogetherSession ?: return
    val clipboard = LocalClipboardManager.current
    val tokens = MaterialTheme.nuvio
    val busy = state.status == WatchTogetherDevelopmentStatus.Connecting ||
        state.status == WatchTogetherDevelopmentStatus.Reconnecting
    val active = state.isActive || state.status == WatchTogetherDevelopmentStatus.Connected

    BasicAlertDialog(onDismissRequest = { showWatchTogetherPanel = false }) {
        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 560.dp),
            color = tokens.colors.surfaceDialog,
            contentColor = tokens.colors.textPrimary,
            shape = RoundedCornerShape(NuvioTokens.Radius.xl),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Watch Together",
                        style = MaterialTheme.typography.headlineSmall,
                        color = tokens.colors.textPrimary,
                    )
                    TextButton(onClick = { showWatchTogetherPanel = false }) {
                        Text("Close", color = tokens.colors.textSecondary)
                    }
                }

                ScrollablePane(
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    if (active) {
                        ActiveRoomContent(
                            state = state,
                            onCopyCode = {
                                state.roomCode?.let {
                                    clipboard.setText(AnnotatedString(formatRoomCode(it)))
                                }
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
                            onEndpointChange = { watchTogetherEndpointInput = it },
                            onPasteEndpoint = {
                                clipboard.getText()?.text?.trim()?.let {
                                    watchTogetherEndpointInput = it
                                }
                            },
                            onUseSimulatorEndpoint = {
                                watchTogetherEndpointInput = "ws://127.0.0.1:8787"
                            },
                            onDisplayNameChange = {
                                watchTogetherDisplayNameInput = it.take(40)
                            },
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
                            onUseHostedService = {
                                watchTogetherUseDevelopmentRelay = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SetupContent(
    endpoint: String,
    displayName: String,
    roomCode: String,
    busy: Boolean,
    errorMessage: String?,
    onEndpointChange: (String) -> Unit,
    onPasteEndpoint: () -> Unit,
    onUseSimulatorEndpoint: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onRoomCodeChange: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onUseHostedService: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = "Development relay only. Nuvio shares timing and room names, never the title or source.",
        style = MaterialTheme.typography.bodySmall,
        color = tokens.colors.textMuted,
    )
    WatchTogetherTextField(
        value = endpoint,
        onValueChange = onEndpointChange,
        label = "Relay WebSocket URL",
        example = "ws://192.168.1.10:8787",
        enabled = !busy,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton(onClick = onPasteEndpoint, enabled = !busy) {
            Text("Paste", color = tokens.colors.textSecondary)
        }
        TextButton(onClick = onUseSimulatorEndpoint, enabled = !busy) {
            Text("Use simulator relay", color = tokens.colors.accent)
        }
    }
    WatchTogetherTextField(
        value = displayName,
        onValueChange = onDisplayNameChange,
        label = "Your name",
        example = "e.g. Alex",
        enabled = !busy,
    )
    PrimaryActionButton(
        label = "Create room",
        enabled = !busy,
        onClick = onCreate,
    )
    Text(
        text = "Or join an existing room",
        style = MaterialTheme.typography.labelLarge,
        color = tokens.colors.textSecondary,
    )
    WatchTogetherTextField(
        value = formatRoomCode(roomCode),
        onValueChange = onRoomCodeChange,
        label = "Room code",
        example = "ABCD-EFGH",
        enabled = !busy,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = FontFamily.Monospace,
            color = tokens.colors.textPrimary,
        ),
    )
    PrimaryActionButton(
        label = "Join room",
        enabled = !busy && roomCode.length == 8,
        onClick = onJoin,
    )
    errorMessage?.let { ErrorText(it) }
    TextButton(
        onClick = onUseHostedService,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Use hosted pilot service", color = tokens.colors.textSecondary)
    }
}

@Composable
private fun ColumnScope.ActiveRoomContent(
    state: WatchTogetherDevelopmentState,
    onCopyCode: () -> Unit,
    onTestReconnect: () -> Unit,
    onLeave: () -> Unit,
) {
    RoomSummaryCard(state, onCopyCode)
    RoomMetricsCard(state)
    SecondaryActionButton(
        label = if (state.status == WatchTogetherDevelopmentStatus.Reconnecting) {
            "Reconnecting…"
        } else {
            "Test reconnect"
        },
        enabled = state.status == WatchTogetherDevelopmentStatus.Connected,
        onClick = onTestReconnect,
    )
    SecondaryActionButton(label = "Leave room", onClick = onLeave)
}

@Composable
private fun RoomSummaryCard(
    state: WatchTogetherDevelopmentState,
    onCopyCode: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.colors.surfaceCard,
        contentColor = tokens.colors.textPrimary,
        shape = RoundedCornerShape(NuvioTokens.Radius.lg),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = "Room code",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatRoomCode(state.roomCode.orEmpty()),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = tokens.colors.textPrimary,
                )
                TextButton(onClick = onCopyCode, enabled = state.roomCode != null) {
                    Text("Copy", color = tokens.colors.accent)
                }
            }
            Text(
                text = statusLabel(state.status),
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(state.status),
            )
            state.participants.forEach { participant ->
                val role = if (participant.isHost) "Host" else "Guest"
                val connection = if (participant.isConnected) "connected" else "disconnected"
                Text(
                    text = "$role · ${participant.displayName} · $connection",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun RoomMetricsCard(state: WatchTogetherDevelopmentState) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.colors.surfaceCard,
        contentColor = tokens.colors.textPrimary,
        shape = RoundedCornerShape(NuvioTokens.Radius.lg),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
    }
}

@Composable
internal fun WatchTogetherTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    example: String,
    enabled: Boolean,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val tokens = MaterialTheme.nuvio
    var focused by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(top = 7.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            placeholder = {
                Text(
                    text = example,
                    color = tokens.colors.textMuted,
                    style = textStyle,
                )
            },
            enabled = enabled,
            singleLine = true,
            shape = RoundedCornerShape(NuvioTokens.Radius.lg),
            textStyle = textStyle.copy(color = tokens.colors.textPrimary),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = tokens.colors.textPrimary,
                unfocusedTextColor = tokens.colors.textPrimary,
                disabledTextColor = tokens.colors.textDisabled,
                focusedBorderColor = tokens.colors.borderFocus,
                unfocusedBorderColor = tokens.colors.borderDefault,
                disabledBorderColor = tokens.colors.borderSubtle,
                focusedContainerColor = tokens.colors.surfaceCard,
                unfocusedContainerColor = tokens.colors.surfaceCard,
                disabledContainerColor = tokens.colors.surfaceCard,
                cursorColor = tokens.colors.accent,
                focusedPlaceholderColor = tokens.colors.textMuted,
                unfocusedPlaceholderColor = tokens.colors.textMuted,
                disabledPlaceholderColor = tokens.colors.textDisabled,
            ),
        )
        Text(
            text = label,
            modifier = Modifier
                .padding(start = 12.dp)
                .offset(y = (-7).dp)
                .background(
                    color = tokens.colors.surfaceCard,
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) tokens.colors.accent else tokens.colors.textSecondary,
        )
    }
}

@Composable
internal fun PrimaryActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.colors.accent,
            contentColor = tokens.colors.onAccent,
            disabledContainerColor = tokens.colors.surfaceCard,
            disabledContentColor = tokens.colors.textDisabled,
        ),
    ) {
        Text(label)
    }
}

@Composable
internal fun SecondaryActionButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.colors.surfaceCard,
            contentColor = tokens.colors.textPrimary,
            disabledContainerColor = tokens.colors.surfaceCard,
            disabledContentColor = tokens.colors.textDisabled,
        ),
    ) {
        Text(label)
    }
}

@Composable
internal fun ScrollablePane(
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
internal fun DevelopmentMetric(label: String, value: String?) {
    if (value == null) return
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ErrorText(message: String) {
    Text(
        text = message,
        color = MaterialTheme.nuvio.colors.danger,
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

@Composable
private fun statusColor(status: WatchTogetherDevelopmentStatus) = when (status) {
    WatchTogetherDevelopmentStatus.Connected -> MaterialTheme.nuvio.colors.success
    WatchTogetherDevelopmentStatus.Connecting,
    WatchTogetherDevelopmentStatus.Reconnecting,
    -> MaterialTheme.nuvio.colors.warning
    WatchTogetherDevelopmentStatus.Disconnected -> MaterialTheme.nuvio.colors.textSecondary
    WatchTogetherDevelopmentStatus.Error -> MaterialTheme.nuvio.colors.danger
}

private fun statusLabel(status: WatchTogetherDevelopmentStatus): String = when (status) {
    WatchTogetherDevelopmentStatus.Disconnected -> "Disconnected"
    WatchTogetherDevelopmentStatus.Connecting -> "Connecting…"
    WatchTogetherDevelopmentStatus.Connected -> "Connected"
    WatchTogetherDevelopmentStatus.Reconnecting -> "Reconnecting…"
    WatchTogetherDevelopmentStatus.Error -> "Connection error"
}

private const val ROOM_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
