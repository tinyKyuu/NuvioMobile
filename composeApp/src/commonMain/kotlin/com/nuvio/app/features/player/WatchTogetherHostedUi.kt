package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.watchtogether.hosted.WatchTogetherHostedParticipant
import com.nuvio.app.features.watchtogether.hosted.WatchTogetherHostedSession
import com.nuvio.app.features.watchtogether.hosted.WatchTogetherHostedSessionRepository
import com.nuvio.app.features.watchtogether.hosted.WatchTogetherHostedState
import com.nuvio.app.features.watchtogether.hosted.WatchTogetherHostedStatus
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherAdmissionStatus
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoundStatus
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

internal class WatchTogetherHostedDialogDraft {
    var manifestUrl by mutableStateOf("")
    var hostEmail by mutableStateOf("")
    var otp by mutableStateOf("")
    var displayName by mutableStateOf("")
    var capacity by mutableStateOf("2")
    var roomCode by mutableStateOf("")
    var invitationSecret by mutableStateOf("")
    var offset by mutableStateOf("0")
}

@Composable
internal fun PlayerScreenRuntime.WatchTogetherDialog() {
    if (watchTogetherUseDevelopmentRelay) {
        WatchTogetherDevelopmentDialog()
    } else {
        val session = hostedWatchTogetherSession ?: return
        WatchTogetherHostedDialog(
            state = hostedWatchTogetherState,
            session = session,
            draft = hostedWatchTogetherDialogDraft,
            onDismiss = { showWatchTogetherPanel = false },
            onUseDevelopmentRelay = { watchTogetherUseDevelopmentRelay = true },
        )
    }
}

@Composable
internal fun WatchTogetherHostedLobbyDialog(onDismiss: () -> Unit) {
    val session = remember { WatchTogetherHostedSessionRepository.session }
    val state by session.state.collectAsStateWithLifecycle()
    val draft = remember { WatchTogetherHostedDialogDraft() }
    WatchTogetherHostedDialog(
        state = state,
        session = session,
        draft = draft,
        onDismiss = onDismiss,
        onUseDevelopmentRelay = null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchTogetherHostedDialog(
    state: WatchTogetherHostedState,
    session: WatchTogetherHostedSession,
    draft: WatchTogetherHostedDialogDraft,
    onDismiss: () -> Unit,
    onUseDevelopmentRelay: (() -> Unit)?,
) {
    val clipboard = LocalClipboardManager.current
    val tokens = MaterialTheme.nuvio
    val busy = state.status in hostedBusyStatuses
    val scope = rememberCoroutineScope()

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 580.dp),
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
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = tokens.colors.textSecondary)
                    }
                }

                ScrollablePane(modifier = Modifier.weight(1f, fill = false)) {
                    when {
                        state.isActive -> HostedActiveRoomContent(
                            state = state,
                            offsetInput = draft.offset,
                            onOffsetInputChange = {
                                draft.offset = normalizeOffsetInput(it)
                            },
                            onCopyCode = {
                                state.roomCode?.let { code ->
                                    clipboard.setText(AnnotatedString(formatRoomCode(code)))
                                }
                            },
                            onCopySecret = {
                                state.invitationSecret?.let { secret ->
                                    clipboard.setText(AnnotatedString(secret))
                                }
                            },
                            onCopyInvitation = {
                                val code = state.roomCode
                                val secret = state.invitationSecret
                                if (code != null && secret != null) {
                                    clipboard.setText(
                                        AnnotatedString("${formatRoomCode(code)}\n$secret"),
                                    )
                                }
                            },
                            onResolveJoin = { participantId, approve ->
                                scope.launch { session.resolveJoinRequest(participantId, approve) }
                            },
                            onToggleReady = { session.setViewerReady(!state.selfViewerReady) },
                            onAcknowledgeMismatch = { session.acknowledgeDurationMismatch() },
                            onBeginCountdown = {
                                scope.launch { session.beginCountdown(force = false) }
                            },
                            onForceCountdown = {
                                scope.launch { session.beginCountdown(force = true) }
                            },
                            onCancelCountdown = {
                                scope.launch { session.cancelCountdown() }
                            },
                            onApplyOffset = {
                                draft.offset
                                    .toDoubleOrNull()
                                    ?.times(1_000.0)
                                    ?.roundToLong()
                                    ?.let(session::setLocalSourceOffset)
                            },
                            onBeginNextRound = {
                                scope.launch { session.beginNextRound() }
                            },
                            onToggleAdmission = {
                                scope.launch {
                                    session.setAdmissionOpen(
                                        state.admission != WatchTogetherAdmissionStatus.Open,
                                    )
                                }
                            },
                            onRotateInvitation = {
                                scope.launch { session.rotateInvitation() }
                            },
                            onLeave = {
                                scope.launch { session.leaveRoom() }
                            },
                        )

                        !state.isConfigured -> HostedManifestSetupContent(
                            manifestUrl = draft.manifestUrl,
                            busy = busy,
                            errorMessage = state.errorMessage,
                            onManifestUrlChange = {
                                draft.manifestUrl = it.take(2_048)
                            },
                            onPaste = {
                                clipboard.getText()?.text?.trim()?.let {
                                    draft.manifestUrl = it.take(2_048)
                                }
                            },
                            onInstall = {
                                scope.launch {
                                    session.loadService(draft.manifestUrl)
                                }
                            },
                            onUseDevelopmentRelay = onUseDevelopmentRelay,
                        )

                        else -> HostedLobbySetupContent(
                            state = state,
                            busy = busy,
                            hostEmail = draft.hostEmail,
                            otp = draft.otp,
                            displayName = draft.displayName,
                            capacity = draft.capacity,
                            roomCode = draft.roomCode,
                            invitationSecret = draft.invitationSecret,
                            onHostEmailChange = {
                                draft.hostEmail = it.take(254)
                            },
                            onOtpChange = {
                                draft.otp = it.filter(Char::isDigit).take(8)
                            },
                            onDisplayNameChange = {
                                draft.displayName = it.take(40)
                            },
                            onCapacityChange = {
                                draft.capacity = it.filter(Char::isDigit).take(1)
                            },
                            onRoomCodeChange = {
                                draft.roomCode = normalizeRoomCode(it)
                            },
                            onInvitationSecretChange = {
                                draft.invitationSecret = it.trim().take(128)
                            },
                            onPasteInvitation = {
                                clipboard.getText()?.text?.trim()?.let { pasted ->
                                    parseHostedInvitation(pasted)?.let { invitation ->
                                        draft.roomCode = invitation.first
                                        draft.invitationSecret = invitation.second
                                    } ?: run {
                                        draft.invitationSecret = pasted.take(128)
                                    }
                                }
                            },
                            onRequestOtp = {
                                scope.launch {
                                    session.requestHostEmailOtp(draft.hostEmail)
                                }
                            },
                            onVerifyOtp = {
                                scope.launch {
                                    session.verifyHostEmailOtp(
                                        draft.hostEmail,
                                        draft.otp,
                                    )
                                }
                            },
                            onCreateRoom = {
                                scope.launch {
                                    session.createRoom(
                                        draft.displayName,
                                        draft.capacity.toIntOrNull() ?: 2,
                                    )
                                }
                            },
                            onJoinRoom = {
                                scope.launch {
                                    session.joinRoom(
                                        draft.roomCode,
                                        draft.invitationSecret,
                                        draft.displayName,
                                    )
                                }
                            },
                            onChangeService = {
                                scope.launch {
                                    session.unloadService()
                                    draft.manifestUrl = ""
                                }
                            },
                            onUseDevelopmentRelay = onUseDevelopmentRelay,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.HostedManifestSetupContent(
    manifestUrl: String,
    busy: Boolean,
    errorMessage: String?,
    onManifestUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onInstall: () -> Unit,
    onUseDevelopmentRelay: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = "Install a compatible service manifest. The client verifies its protocol, privacy, authentication, and transport declarations before connecting.",
        style = MaterialTheme.typography.bodySmall,
        color = tokens.colors.textMuted,
    )
    WatchTogetherTextField(
        value = manifestUrl,
        onValueChange = onManifestUrlChange,
        label = "Service manifest URL",
        example = "https://example.org/watch-together/manifest.json",
        enabled = !busy,
    )
    TextButton(
        onClick = onPaste,
        enabled = !busy,
        modifier = Modifier.align(Alignment.End),
    ) {
        Text("Paste", color = tokens.colors.textSecondary)
    }
    PrimaryActionButton(
        label = if (busy) "Checking service…" else "Install service",
        enabled = !busy && manifestUrl.trim().startsWith("https://"),
        onClick = onInstall,
    )
    errorMessage?.let { ErrorText(it) }
    if (onUseDevelopmentRelay != null) {
        TextButton(
            onClick = onUseDevelopmentRelay,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Use local development relay", color = tokens.colors.textSecondary)
        }
    }
}

@Composable
private fun ColumnScope.HostedLobbySetupContent(
    state: WatchTogetherHostedState,
    busy: Boolean,
    hostEmail: String,
    otp: String,
    displayName: String,
    capacity: String,
    roomCode: String,
    invitationSecret: String,
    onHostEmailChange: (String) -> Unit,
    onOtpChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onCapacityChange: (String) -> Unit,
    onRoomCodeChange: (String) -> Unit,
    onInvitationSecretChange: (String) -> Unit,
    onPasteInvitation: () -> Unit,
    onRequestOtp: () -> Unit,
    onVerifyOtp: () -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onChangeService: () -> Unit,
    onUseDevelopmentRelay: (() -> Unit)?,
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = state.serviceName.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = "Compatible hosted service · content-blind timing only",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }

    Text(
        text = "Host a room",
        style = MaterialTheme.typography.titleMedium,
        color = tokens.colors.textPrimary,
    )
    Text(
        text = "Pilot hosts use an approved email and one-time code. This service account is separate from Nuvio.",
        style = MaterialTheme.typography.bodySmall,
        color = tokens.colors.textMuted,
    )
    WatchTogetherTextField(
        value = hostEmail,
        onValueChange = onHostEmailChange,
        label = "Approved host email",
        example = "you@example.com",
        enabled = !busy,
    )
    PrimaryActionButton(
        label = when (state.status) {
            WatchTogetherHostedStatus.SendingHostCode -> "Sending code…"
            WatchTogetherHostedStatus.AwaitingHostCode -> "Send code again"
            else -> "Send one-time code"
        },
        enabled = !busy && hostEmail.contains("@"),
        onClick = onRequestOtp,
    )
    WatchTogetherTextField(
        value = otp,
        onValueChange = onOtpChange,
        label = "One-time code",
        example = "123456",
        enabled = !busy,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = FontFamily.Monospace,
            color = tokens.colors.textPrimary,
        ),
    )
    SecondaryActionButton(
        label = when {
            state.status == WatchTogetherHostedStatus.AuthenticatingHost -> "Verifying…"
            state.hostAuthenticated -> "Host code verified"
            else -> "Verify host code"
        },
        enabled = !busy && otp.length >= 6,
        onClick = onVerifyOtp,
    )
    WatchTogetherTextField(
        value = displayName,
        onValueChange = onDisplayNameChange,
        label = "Your room name",
        example = "e.g. Alex",
        enabled = !busy,
    )
    WatchTogetherTextField(
        value = capacity,
        onValueChange = onCapacityChange,
        label = "Room capacity",
        example = "2 to 8",
        enabled = !busy,
    )
    PrimaryActionButton(
        label = if (state.status == WatchTogetherHostedStatus.CreatingRoom) {
            "Creating room…"
        } else {
            "Create room"
        },
        enabled = !busy &&
            displayName.trim().isNotEmpty() &&
            (capacity.toIntOrNull() ?: 0) in 2..8,
        onClick = onCreateRoom,
    )

    Text(
        text = "Join a room",
        style = MaterialTheme.typography.titleMedium,
        color = tokens.colors.textPrimary,
    )
    Text(
        text = "Guests do not create a product account. The host sees only your room name and approves the request.",
        style = MaterialTheme.typography.bodySmall,
        color = tokens.colors.textMuted,
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
    WatchTogetherTextField(
        value = invitationSecret,
        onValueChange = onInvitationSecretChange,
        label = "Invitation secret",
        example = "Paste the private invitation secret",
        enabled = !busy,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            color = tokens.colors.textPrimary,
        ),
    )
    TextButton(
        onClick = onPasteInvitation,
        enabled = !busy,
        modifier = Modifier.align(Alignment.End),
    ) {
        Text("Paste invitation", color = tokens.colors.textSecondary)
    }
    PrimaryActionButton(
        label = if (state.status == WatchTogetherHostedStatus.JoiningRoom) {
            "Requesting access…"
        } else {
            "Request to join"
        },
        enabled = !busy &&
            displayName.trim().isNotEmpty() &&
            roomCode.length == 8 &&
            invitationSecret.length >= 32,
        onClick = onJoinRoom,
    )
    state.errorMessage?.let { ErrorText(it) }
    TextButton(
        onClick = onChangeService,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Change hosted service", color = tokens.colors.textSecondary)
    }
    if (onUseDevelopmentRelay != null) {
        TextButton(
            onClick = onUseDevelopmentRelay,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Use local development relay", color = tokens.colors.textSecondary)
        }
    }
}

@Composable
private fun ColumnScope.HostedActiveRoomContent(
    state: WatchTogetherHostedState,
    offsetInput: String,
    onOffsetInputChange: (String) -> Unit,
    onCopyCode: () -> Unit,
    onCopySecret: () -> Unit,
    onCopyInvitation: () -> Unit,
    onResolveJoin: (String, Boolean) -> Unit,
    onToggleReady: () -> Unit,
    onAcknowledgeMismatch: () -> Unit,
    onBeginCountdown: () -> Unit,
    onForceCountdown: () -> Unit,
    onCancelCountdown: () -> Unit,
    onApplyOffset: () -> Unit,
    onBeginNextRound: () -> Unit,
    onToggleAdmission: () -> Unit,
    onRotateInvitation: () -> Unit,
    onLeave: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    HostedRoomSummaryCard(
        state = state,
        onCopyCode = onCopyCode,
        onCopySecret = onCopySecret,
        onCopyInvitation = onCopyInvitation,
    )

    if (state.status == WatchTogetherHostedStatus.AwaitingApproval) {
        Text(
            text = "Waiting for the host to approve ${state.displayName}. You cannot receive room playback updates until you are admitted.",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
        state.errorMessage?.let { ErrorText(it) }
        SecondaryActionButton(label = "Cancel join request", onClick = onLeave)
        return
    }

    if (state.pendingParticipants.isNotEmpty()) {
        Text(
            text = "Join requests",
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
        )
        state.pendingParticipants.forEach { participant ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = tokens.colors.surfaceCard,
                shape = RoundedCornerShape(NuvioTokens.Radius.lg),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = participant.displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { onResolveJoin(participant.participantId, false) }) {
                        Text("Decline", color = tokens.colors.danger)
                    }
                    TextButton(onClick = { onResolveJoin(participant.participantId, true) }) {
                        Text("Approve", color = tokens.colors.accent)
                    }
                }
            }
        }
    }

    HostedParticipantsCard(state.participants)
    HostedReadinessCard(
        state = state,
        onToggleReady = onToggleReady,
        onAcknowledgeMismatch = onAcknowledgeMismatch,
        onBeginCountdown = onBeginCountdown,
        onForceCountdown = onForceCountdown,
        onCancelCountdown = onCancelCountdown,
    )

    Text(
        text = "Different edit or dub?",
        style = MaterialTheme.typography.titleMedium,
        color = tokens.colors.textPrimary,
    )
    Text(
        text = "Set only this device's offset. For example, +10 means this source's matching scene is ten seconds later. The offset never leaves the device.",
        style = MaterialTheme.typography.bodySmall,
        color = tokens.colors.textMuted,
    )
    WatchTogetherTextField(
        value = offsetInput,
        onValueChange = onOffsetInputChange,
        label = "Local source offset (seconds)",
        example = "0 or +10 or -5.5",
        enabled = true,
    )
    SecondaryActionButton(
        label = "Apply local offset",
        enabled = offsetInput.toDoubleOrNull() != null,
        onClick = onApplyOffset,
    )

    if (state.isHost) {
        Text(
            text = "Host controls",
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
        )
        SecondaryActionButton(label = "Start next episode round", onClick = onBeginNextRound)
        SecondaryActionButton(
            label = if (state.admission == WatchTogetherAdmissionStatus.Open) {
                "Pause new join requests"
            } else {
                "Resume new join requests"
            },
            onClick = onToggleAdmission,
        )
        SecondaryActionButton(label = "Rotate invitation secret", onClick = onRotateInvitation)
    }

    HostedMetricsCard(state)
    state.errorMessage?.let { ErrorText(it) }
    SecondaryActionButton(label = "Leave room", onClick = onLeave)
}

@Composable
private fun HostedRoomSummaryCard(
    state: WatchTogetherHostedState,
    onCopyCode: () -> Unit,
    onCopySecret: () -> Unit,
    onCopyInvitation: () -> Unit,
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
                text = state.serviceName.orEmpty(),
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
                    Text("Copy code", color = tokens.colors.accent)
                }
            }
            Text(
                text = hostedStatusLabel(state.status),
                style = MaterialTheme.typography.labelLarge,
                color = hostedStatusColor(state.status),
            )
            if (state.isHost) {
                Text(
                    text = "Invitation secret",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                )
                Text(
                    text = state.invitationSecret.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onCopySecret) {
                        Text("Copy secret", color = tokens.colors.textSecondary)
                    }
                    TextButton(onClick = onCopyInvitation) {
                        Text("Copy invitation", color = tokens.colors.accent)
                    }
                }
                Text(
                    text = "Invitation links and QR codes arrive with the reviewed Nuvio deep-link route. For now, share the copied code and secret.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun HostedParticipantsCard(participants: List<WatchTogetherHostedParticipant>) {
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
                text = "People (${participants.size})",
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
            )
            participants.forEach { participant ->
                val role = if (participant.isHost) "Host" else "Guest"
                val connection = if (participant.isConnected) "connected" else "disconnected"
                val readiness = when {
                    !participant.sourceReady -> "choosing a source"
                    participant.viewerReady -> "ready"
                    else -> "source loaded"
                }
                Text(
                    text = "$role · ${participant.displayName} · $connection · $readiness",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (participant.isConnected) {
                        tokens.colors.textSecondary
                    } else {
                        tokens.colors.warning
                    },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.HostedReadinessCard(
    state: WatchTogetherHostedState,
    onToggleReady: () -> Unit,
    onAcknowledgeMismatch: () -> Unit,
    onBeginCountdown: () -> Unit,
    onForceCountdown: () -> Unit,
    onCancelCountdown: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val readiness = state.readiness
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
            Text(
                text = when (state.roundStatus) {
                    WatchTogetherRoundStatus.Preparing -> "Getting ready"
                    WatchTogetherRoundStatus.Countdown -> {
                        val seconds = ((state.countdownRemainingMs ?: 0L) + 999L) / 1_000L
                        "Starting in $seconds…"
                    }
                    WatchTogetherRoundStatus.Active -> "Watching together"
                    WatchTogetherRoundStatus.Ended -> "Round finished"
                    null -> "Loading round"
                },
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
            )
            when {
                !state.playerAttached -> Text(
                    text = "Choose and load a source on this device to become ready.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.warning,
                )
                readiness?.durationMismatch == true -> Text(
                    text = "Source lengths differ by ${formatOffsetDuration(readiness.durationSpreadMs)}. Confirm that your scenes are aligned, or set a local offset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.warning,
                )
                readiness?.ready == true -> Text(
                    text = "Everyone is ready.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.success,
                )
                else -> Text(
                    text = "Playback stays paused until every connected participant has a source and marks ready.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            if (state.roundStatus == WatchTogetherRoundStatus.Preparing) {
                PrimaryActionButton(
                    label = if (state.selfViewerReady) "Mark not ready" else "I'm ready",
                    enabled = state.playerAttached,
                    onClick = onToggleReady,
                )
                if (
                    readiness?.durationMismatch == true &&
                    !state.selfDurationMismatchAcknowledged
                ) {
                    SecondaryActionButton(
                        label = "Accept duration difference",
                        onClick = onAcknowledgeMismatch,
                    )
                }
                SecondaryActionButton(
                    label = "Start 5-second countdown",
                    enabled = readiness?.ready == true,
                    onClick = onBeginCountdown,
                )
                if (state.isHost) {
                    TextButton(
                        onClick = onForceCountdown,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Force start as host", color = tokens.colors.warning)
                    }
                }
            }
            if (state.roundStatus == WatchTogetherRoundStatus.Countdown) {
                SecondaryActionButton(
                    label = "Cancel countdown",
                    onClick = onCancelCountdown,
                )
            }
        }
    }
}

@Composable
private fun HostedMetricsCard(state: WatchTogetherHostedState) {
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
            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
            )
            DevelopmentMetric(
                "Canonical",
                state.canonicalPositionMs?.let(::formatPlaybackTime),
            )
            DevelopmentMetric(
                "Drift",
                state.driftMs?.let { drift ->
                    val prefix = if (drift > 0L) "+" else ""
                    "$prefix$drift ms (${if (abs(drift) <= 250L) "in range" else "correcting"})"
                },
            )
            DevelopmentMetric("Relay RTT", state.roundTripMs?.let { "$it ms" })
            DevelopmentMetric("Clock samples", state.clockSampleCount.toString())
            DevelopmentMetric("Correction", state.correction)
            DevelopmentMetric("Reconnects", state.reconnectCount.toString())
            DevelopmentMetric(
                "Local offset",
                formatSignedSeconds(state.localSourceOffsetMs),
            )
        }
    }
}

@Composable
private fun hostedStatusColor(status: WatchTogetherHostedStatus) = when (status) {
    WatchTogetherHostedStatus.Connected -> MaterialTheme.nuvio.colors.success
    WatchTogetherHostedStatus.LoadingService,
    WatchTogetherHostedStatus.SendingHostCode,
    WatchTogetherHostedStatus.AuthenticatingHost,
    WatchTogetherHostedStatus.CreatingRoom,
    WatchTogetherHostedStatus.JoiningRoom,
    WatchTogetherHostedStatus.AwaitingApproval,
    WatchTogetherHostedStatus.Reconnecting,
    -> MaterialTheme.nuvio.colors.warning
    WatchTogetherHostedStatus.Error -> MaterialTheme.nuvio.colors.danger
    WatchTogetherHostedStatus.Unconfigured,
    WatchTogetherHostedStatus.Ready,
    WatchTogetherHostedStatus.AwaitingHostCode,
    -> MaterialTheme.nuvio.colors.textSecondary
}

private fun hostedStatusLabel(status: WatchTogetherHostedStatus): String = when (status) {
    WatchTogetherHostedStatus.Unconfigured -> "Service not installed"
    WatchTogetherHostedStatus.LoadingService -> "Checking service…"
    WatchTogetherHostedStatus.Ready -> "Ready"
    WatchTogetherHostedStatus.SendingHostCode -> "Sending host code…"
    WatchTogetherHostedStatus.AwaitingHostCode -> "Waiting for host code"
    WatchTogetherHostedStatus.AuthenticatingHost -> "Verifying host…"
    WatchTogetherHostedStatus.CreatingRoom -> "Creating room…"
    WatchTogetherHostedStatus.JoiningRoom -> "Requesting access…"
    WatchTogetherHostedStatus.AwaitingApproval -> "Waiting for host approval"
    WatchTogetherHostedStatus.Connected -> "Connected"
    WatchTogetherHostedStatus.Reconnecting -> "Reconnecting…"
    WatchTogetherHostedStatus.Error -> "Service error"
}

private fun normalizeOffsetInput(value: String): String {
    val normalized = buildString {
        value.take(12).forEachIndexed { index, character ->
            when {
                character.isDigit() -> append(character)
                character == '.' && '.' !in this -> append(character)
                (character == '+' || character == '-') && index == 0 -> append(character)
            }
        }
    }
    return normalized
}

private fun parseHostedInvitation(value: String): Pair<String, String>? {
    val parts = value
        .lines()
        .flatMap { line -> line.trim().split(' ', '\t') }
        .filter(String::isNotBlank)
    if (parts.size != 2) return null
    val code = normalizeRoomCode(parts[0])
    val secret = parts[1].trim()
    return if (code.length == 8 && secret.length >= 32) code to secret else null
}

private fun formatOffsetDuration(milliseconds: Long): String =
    if (milliseconds % 1_000L == 0L) {
        "${milliseconds / 1_000L} seconds"
    } else {
        "${milliseconds / 1_000.0} seconds"
    }

private fun formatSignedSeconds(milliseconds: Long): String {
    val seconds = milliseconds / 1_000.0
    val prefix = if (seconds > 0.0) "+" else ""
    return "$prefix$seconds seconds"
}

private val hostedBusyStatuses = setOf(
    WatchTogetherHostedStatus.LoadingService,
    WatchTogetherHostedStatus.SendingHostCode,
    WatchTogetherHostedStatus.AuthenticatingHost,
    WatchTogetherHostedStatus.CreatingRoom,
    WatchTogetherHostedStatus.JoiningRoom,
)
