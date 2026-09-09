package com.nuvio.app.features.watchtogether.hosted

import com.nuvio.app.features.watchtogether.protocol.RelayClockEstimator
import com.nuvio.app.features.watchtogether.protocol.RelayClockSample
import com.nuvio.app.features.watchtogether.protocol.ServerMessageDecision
import com.nuvio.app.features.watchtogether.protocol.ServerMessageOrderer
import com.nuvio.app.features.watchtogether.protocol.SourceTimeMapper
import com.nuvio.app.features.watchtogether.protocol.WATCH_TOGETHER_MAX_SAFE_INTEGER
import com.nuvio.app.features.watchtogether.protocol.WATCH_TOGETHER_PROTOCOL_VERSION
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherCanonicalClock
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherClientCommand
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherClientCommandType
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherConnectionState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherJson
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherParticipantRole
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlaybackMode
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerAdapter
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerCommand
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerCommandResult
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherReadinessGate
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherReadinessPayload
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoomState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoomStatus
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoundStatus
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherSeekPayload
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServerMessage
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServerMessageType
import com.nuvio.app.features.watchtogether.protocol.acceptedPayload
import com.nuvio.app.features.watchtogether.protocol.rejectedPayload
import com.nuvio.app.features.watchtogether.protocol.snapshotPayload
import com.nuvio.app.features.watchtogether.session.DriftCorrectionDecision
import com.nuvio.app.features.watchtogether.session.DriftCorrectionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.math.abs
import kotlin.time.TimeSource

internal interface WatchTogetherPlaybackSession {
    fun requestPlayback(shouldPlay: Boolean): Boolean

    fun requestSeek(localPositionMs: Long): Boolean
}

internal class WatchTogetherHostedSession(
    private val scope: CoroutineScope,
    private val connector: WatchTogetherHostedServiceConnector =
        ManifestWatchTogetherHostedServiceConnector(),
    private val roomCredentialStore: WatchTogetherRoomCredentialStore =
        WatchTogetherSecureRoomCredentialStore(),
    private val serviceConfigurationStore: WatchTogetherServiceConfigurationStore =
        WatchTogetherSecureServiceConfigurationStore(),
) : WatchTogetherPlaybackSession {
    private data class OutgoingCommand(
        val generation: Long,
        val command: WatchTogetherClientCommand,
    )

    private data class RoomCredentials(
        val roomId: String,
        val roomCode: String,
        val participantId: String,
        val sessionId: String,
        val isHost: Boolean,
    )

    private data class PendingJoin(
        val request: WatchTogetherJoinRoomRequest,
        val credentials: RoomCredentials,
    )

    private val monotonicOrigin = TimeSource.Monotonic.markNow()
    private val clockEstimator = RelayClockEstimator()
    private val correctionPolicy = DriftCorrectionPolicy()
    private val mutableState = MutableStateFlow(WatchTogetherHostedState())
    private var transport: WatchTogetherHostedTransport? = null
    private var currentServiceId: String? = null
    private var player: WatchTogetherPlayerAdapter? = null
    private var credentials: RoomCredentials? = null
    private var pendingJoin: PendingJoin? = null
    private var roomState: WatchTogetherRoomState? = null
    private var orderer: ServerMessageOrderer? = null
    private var roomSubscription: WatchTogetherHostedRoomSubscription? = null
    private var subscriptionJob: Job? = null
    private var pendingAdmissionJob: Job? = null
    private var pendingParticipantsJob: Job? = null
    private var reconcileJob: Job? = null
    private var countdownCompletionJob: Job? = null
    private var recoveryJob: Job? = null
    private val outgoingCommands = Channel<OutgoingCommand>(Channel.UNLIMITED)
    private val outgoingCommandJob = scope.launch {
        for (queued in outgoingCommands) {
            val room = credentials
            if (
                queued.generation != roomGeneration ||
                room == null ||
                queued.command.roomId != room.roomId ||
                queued.command.participantId != room.participantId ||
                queued.command.sessionId != room.sessionId
            ) {
                continue
            }
            val service = transport ?: continue
            val startedAtMs = monotonicNowMs()
            try {
                val response = service.applyCommand(queued.command)
                recordRelaySample(startedAtMs, monotonicNowMs(), response.relayTimeMs)
                handleServerMessage(response)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                beginRecovery()
            }
        }
    }
    private var nextSequence = 0L
    private var roomGeneration = 0L
    private var latestRelayTimeMs: Long? = null
    private var latestRelayReceivedAtMs: Long? = null
    private var seekSettlingUntilMs = 0L
    private var seekSettlementAnchorRelayTimeMs: Long? = null
    private var resumeIssuedWhileSettling = false
    private var closed = false

    val state: StateFlow<WatchTogetherHostedState> = mutableState.asStateFlow()

    suspend fun restoreInstalledService() {
        if (closed || mutableState.value.status != WatchTogetherHostedStatus.Unconfigured) return
        val manifestUrl = try {
            serviceConfigurationStore.loadManifestUrl()
        } catch (_: Exception) {
            null
        } ?: return
        loadService(manifestUrl)
    }

    suspend fun loadService(manifestUrl: String) {
        checkOpen()
        val normalizedUrl = manifestUrl.trim()
        leaveRoomInternal(notifyService = true, closeHostedRoom = false)
        transport?.close()
        transport = null
        currentServiceId = null
        mutableState.value = WatchTogetherHostedState(
            status = WatchTogetherHostedStatus.LoadingService,
            manifestUrl = normalizedUrl,
            playerAttached = player != null,
        )
        when (val result = connector.connect(normalizedUrl)) {
            is WatchTogetherHostedServiceConnection.Connected -> {
                transport = result.transport
                currentServiceId = result.serviceId
                mutableState.value = mutableState.value.copy(
                    status = WatchTogetherHostedStatus.Ready,
                    serviceName = result.serviceName,
                    errorMessage = null,
                )
                try {
                    serviceConfigurationStore.saveManifestUrl(normalizedUrl)
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = "The installed service could not be saved on this device.",
                    )
                }
                restoreRoomIfAvailable()
            }

            is WatchTogetherHostedServiceConnection.Rejected -> {
                mutableState.value = mutableState.value.copy(
                    status = WatchTogetherHostedStatus.Error,
                    errorMessage = "Service manifest rejected: ${result.reason.name}",
                )
            }
        }
    }

    suspend fun requestHostEmailOtp(email: String) {
        val service = requireTransport() ?: return
        val normalizedEmail = email.trim().lowercase()
        mutableState.value = mutableState.value.copy(
            status = WatchTogetherHostedStatus.SendingHostCode,
            hostEmail = normalizedEmail,
            errorMessage = null,
        )
        runServiceOperation {
            service.requestHostEmailOtp(normalizedEmail)
            mutableState.value = mutableState.value.copy(
                status = WatchTogetherHostedStatus.AwaitingHostCode,
            )
        }
    }

    suspend fun verifyHostEmailOtp(email: String, code: String) {
        val service = requireTransport() ?: return
        val normalizedEmail = email.trim().lowercase()
        mutableState.value = mutableState.value.copy(
            status = WatchTogetherHostedStatus.AuthenticatingHost,
            hostEmail = normalizedEmail,
            errorMessage = null,
        )
        runServiceOperation {
            service.verifyHostEmailOtp(normalizedEmail, code.trim())
            mutableState.value = mutableState.value.copy(
                status = WatchTogetherHostedStatus.Ready,
                hostAuthenticated = true,
            )
        }
    }

    suspend fun createRoom(displayName: String, capacity: Int) {
        val service = requireTransport() ?: return
        mutableState.value = mutableState.value.copy(
            status = WatchTogetherHostedStatus.CreatingRoom,
            errorMessage = null,
        )
        runServiceOperation {
            val initialPositionMs = player?.snapshot()?.localPositionMs
                ?.coerceIn(0L, WATCH_TOGETHER_MAX_SAFE_INTEGER)
                ?: 0L
            val inviteSecret = WatchTogetherInviteSecretGenerator.generate()
            val created = service.createRoom(
                WatchTogetherCreateRoomRequest(
                    displayName = displayName,
                    capacity = capacity,
                    inviteSecret = inviteSecret,
                    initialPositionMs = initialPositionMs,
                ),
            )
            connectRoom(
                newCredentials = RoomCredentials(
                    roomId = created.roomId,
                    roomCode = created.roomCode,
                    participantId = created.participantId,
                    sessionId = created.sessionId,
                    isHost = true,
                ),
                initialState = created.state,
                displayName = displayName.trim(),
                invitationSecret = inviteSecret,
            )
        }
    }

    suspend fun joinRoom(roomCode: String, invitationSecret: String, displayName: String) {
        val service = requireTransport() ?: return
        val request = WatchTogetherJoinRoomRequest(
            roomCode = roomCode,
            inviteSecret = invitationSecret,
            displayName = displayName,
        )
        mutableState.value = mutableState.value.copy(
            status = WatchTogetherHostedStatus.JoiningRoom,
            errorMessage = null,
        )
        runServiceOperation {
            leaveRoomInternal(notifyService = true, closeHostedRoom = false)
            service.clearSession()
            service.signInAnonymously()
            player?.execute(WatchTogetherPlayerCommand.Pause)
            handleJoinResult(service.requestJoin(request), request)
        }
    }

    suspend fun refreshPendingAdmission() {
        val service = transport ?: return
        val pending = pendingJoin ?: return
        try {
            handleJoinResult(
                service.pollJoin(
                    roomId = pending.credentials.roomId,
                    participantId = pending.credentials.participantId,
                ),
                pending.request,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                errorMessage = "Could not check the join request; retrying…",
            )
        }
    }

    suspend fun resolveJoinRequest(participantId: String, approve: Boolean) {
        val service = transport ?: return
        val room = credentials ?: return
        if (!room.isHost) return
        runServiceOperation(preserveStatus = true) {
            val result = service.resolveJoinRequest(room.roomId, participantId, approve)
            applyRoomState(result.state)
            refreshPendingParticipants()
        }
    }

    suspend fun refreshPendingParticipants() {
        val service = transport ?: return
        val room = credentials ?: return
        if (!room.isHost) return
        try {
            val pending = service.pendingParticipants(room.roomId)
            mutableState.value = mutableState.value.copy(pendingParticipants = pending)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                errorMessage = "Could not refresh pending guests; retrying…",
            )
        }
    }

    fun setViewerReady(ready: Boolean) {
        sendReadiness(viewerReady = ready)
    }

    fun acknowledgeDurationMismatch() {
        val self = selfParticipant() ?: return
        sendReadiness(
            viewerReady = self.readiness.viewerReady,
            acknowledgeMismatch = true,
        )
    }

    suspend fun beginCountdown(force: Boolean = false) {
        val service = transport ?: return
        val room = credentials ?: return
        runServiceOperation(preserveStatus = true) {
            applyRoomState(service.beginCountdown(room.roomId, force))
        }
    }

    suspend fun cancelCountdown() {
        val service = transport ?: return
        val room = credentials ?: return
        runServiceOperation(preserveStatus = true) {
            applyRoomState(service.cancelCountdown(room.roomId))
        }
    }

    suspend fun beginNextRound() {
        val service = transport ?: return
        val room = credentials ?: return
        if (!room.isHost) return
        runServiceOperation(preserveStatus = true) {
            applyRoomState(service.beginRound(room.roomId))
            publishCurrentReadiness(viewerReady = false, acknowledgeMismatch = false)
        }
    }

    suspend fun setAdmissionOpen(open: Boolean) {
        val service = transport ?: return
        val room = credentials ?: return
        if (!room.isHost) return
        runServiceOperation(preserveStatus = true) {
            applyRoomState(service.setAdmission(room.roomId, open))
        }
    }

    suspend fun rotateInvitation() {
        val service = transport ?: return
        val room = credentials ?: return
        if (!room.isHost) return
        runServiceOperation(preserveStatus = true) {
            val newSecret = WatchTogetherInviteSecretGenerator.generate()
            val result = service.rotateInvitation(room.roomId, newSecret)
            applyRoomState(result.state)
            mutableState.value = mutableState.value.copy(invitationSecret = newSecret)
            persistRoomCredential(mutableState.value.displayName, newSecret)
        }
    }

    fun setLocalSourceOffset(offsetMs: Long) {
        val bounded = offsetMs.coerceIn(
            -WATCH_TOGETHER_MAX_SAFE_INTEGER,
            WATCH_TOGETHER_MAX_SAFE_INTEGER,
        )
        mutableState.value = mutableState.value.copy(localSourceOffsetMs = bounded)
        scope.launch { reconcile() }
    }

    fun attachPlayer(adapter: WatchTogetherPlayerAdapter) {
        player = adapter
        mutableState.value = mutableState.value.copy(playerAttached = true)
        if (mutableState.value.status == WatchTogetherHostedStatus.Connected) {
            publishCurrentReadiness(viewerReady = false, acknowledgeMismatch = false)
            scope.launch { reconcile() }
        }
    }

    fun detachPlayer(adapter: WatchTogetherPlayerAdapter) {
        if (player !== adapter) return
        player = null
        mutableState.value = mutableState.value.copy(playerAttached = false)
        if (mutableState.value.status == WatchTogetherHostedStatus.Connected) {
            sendReadinessPayload(
                WatchTogetherReadinessPayload(
                    sourceReady = false,
                    viewerReady = false,
                    durationMs = null,
                    durationMismatchAcknowledged = false,
                ),
            )
        }
    }

    override fun requestPlayback(shouldPlay: Boolean): Boolean {
        if (mutableState.value.status != WatchTogetherHostedStatus.Connected || player == null) {
            return false
        }
        return when (roomState?.round?.status) {
            WatchTogetherRoundStatus.Preparing -> {
                if (shouldPlay) scope.launch { beginCountdown() }
                true
            }

            WatchTogetherRoundStatus.Countdown -> {
                if (!shouldPlay) scope.launch { cancelCountdown() }
                true
            }

            WatchTogetherRoundStatus.Active,
            WatchTogetherRoundStatus.Ended,
            null,
            -> {
                sendCommand(
                    type = if (shouldPlay) {
                        WatchTogetherClientCommandType.PlaybackResume
                    } else {
                        WatchTogetherClientCommandType.PlaybackPause
                    },
                    payload = JsonObject(emptyMap()),
                )
                true
            }
        }
    }

    override fun requestSeek(localPositionMs: Long): Boolean {
        if (mutableState.value.status != WatchTogetherHostedStatus.Connected || player == null) {
            return false
        }
        val canonicalPosition = SourceTimeMapper.canonicalPosition(
            localPositionMs = localPositionMs.coerceIn(0L, WATCH_TOGETHER_MAX_SAFE_INTEGER),
            sourceOffsetMs = mutableState.value.localSourceOffsetMs,
        ).positionMs
        sendCommand(
            type = WatchTogetherClientCommandType.PlaybackSeek,
            payload = WatchTogetherJson.encodeToJsonElement(
                WatchTogetherSeekPayload(canonicalPosition),
            ) as JsonObject,
        )
        return true
    }

    suspend fun leaveRoom() {
        leaveRoomInternal(notifyService = true, closeHostedRoom = true)
    }

    suspend fun clearServiceSession() {
        leaveRoomInternal(notifyService = true, closeHostedRoom = false)
        transport?.clearSession()
        mutableState.value = WatchTogetherHostedState(
            status = if (transport == null) {
                WatchTogetherHostedStatus.Unconfigured
            } else {
                WatchTogetherHostedStatus.Ready
            },
            manifestUrl = mutableState.value.manifestUrl,
            serviceName = mutableState.value.serviceName,
        )
    }

    suspend fun unloadService() {
        leaveRoomInternal(notifyService = true, closeHostedRoom = false)
        transport?.clearSession()
        transport?.close()
        transport = null
        currentServiceId = null
        try {
            serviceConfigurationStore.deleteManifestUrl()
        } catch (_: Exception) {
            // Unloading the in-memory service must still succeed.
        }
        mutableState.value = WatchTogetherHostedState(
            playerAttached = player != null,
        )
    }

    suspend fun close() {
        if (closed) return
        closed = true
        leaveRoomInternal(notifyService = true, closeHostedRoom = false)
        transport?.close()
        transport = null
        connector.close()
        outgoingCommands.close()
        outgoingCommandJob.cancel()
    }

    private suspend fun handleJoinResult(
        result: WatchTogetherJoinResult,
        request: WatchTogetherJoinRoomRequest,
    ) {
        when (result.status) {
            WatchTogetherJoinStatus.Rejected -> {
                pendingAdmissionJob?.cancel()
                pendingAdmissionJob = null
                pendingJoin = null
                credentials = null
                mutableState.value = mutableState.value.copy(
                    status = WatchTogetherHostedStatus.Error,
                    errorMessage = joinRejectionMessage(result.code),
                )
            }

            WatchTogetherJoinStatus.Pending -> {
                val pendingCredentials = RoomCredentials(
                    roomId = checkNotNull(result.roomId),
                    roomCode = normalizeRoomCode(request.roomCode),
                    participantId = checkNotNull(result.participantId),
                    sessionId = checkNotNull(result.sessionId),
                    isHost = false,
                )
                credentials = pendingCredentials
                pendingJoin = PendingJoin(request, pendingCredentials)
                mutableState.value = mutableState.value.copy(
                    status = WatchTogetherHostedStatus.AwaitingApproval,
                    displayName = request.displayName.trim(),
                    roomCode = pendingCredentials.roomCode,
                    invitationSecret = request.inviteSecret,
                    isHost = false,
                    errorMessage = null,
                )
                startPendingAdmissionPolling()
            }

            WatchTogetherJoinStatus.Admitted -> {
                val admittedCredentials = RoomCredentials(
                    roomId = checkNotNull(result.roomId),
                    roomCode = normalizeRoomCode(request.roomCode),
                    participantId = checkNotNull(result.participantId),
                    sessionId = checkNotNull(result.sessionId),
                    isHost = false,
                )
                connectRoom(
                    newCredentials = admittedCredentials,
                    initialState = null,
                    displayName = request.displayName.trim(),
                    invitationSecret = request.inviteSecret,
                )
            }
        }
    }

    private suspend fun connectRoom(
        newCredentials: RoomCredentials,
        initialState: WatchTogetherRoomState?,
        displayName: String,
        invitationSecret: String,
    ) {
        pendingAdmissionJob?.cancel()
        pendingAdmissionJob = null
        pendingJoin = null
        stopRoomJobs()
        roomGeneration += 1L
        credentials = newCredentials
        roomState = initialState
        orderer = ServerMessageOrderer(newCredentials.roomId, WATCH_TOGETHER_PROTOCOL_VERSION)
        nextSequence = (WatchTogetherPlatformSecurity.nowEpochMs() * 1_000L)
            .coerceAtMost(WATCH_TOGETHER_MAX_SAFE_INTEGER - 10_000L)
        clockEstimator.reset()
        correctionPolicy.reset()
        resetSeekSettlement()
        mutableState.value = mutableState.value.copy(
            status = WatchTogetherHostedStatus.Connected,
            displayName = displayName,
            roomCode = newCredentials.roomCode,
            invitationSecret = invitationSecret,
            isHost = newCredentials.isHost,
            reconnectCount = 0,
            errorMessage = null,
        )
        initialState?.let(::applyRoomState)
        subscribeAndRefresh()
        startRoomLoops()
        publishCurrentReadiness(viewerReady = false, acknowledgeMismatch = false)
        persistRoomCredential(displayName, invitationSecret)
    }

    private suspend fun subscribeAndRefresh() {
        val service = checkNotNull(transport)
        val room = checkNotNull(credentials)
        roomSubscription?.close()
        subscriptionJob?.cancel()
        val subscription = service.subscribeToRoom(room.roomId)
        roomSubscription = subscription
        subscriptionJob = scope.launch {
            try {
                subscription.messages.collect(::handleServerMessage)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                beginRecovery()
            }
        }
        val startedAtMs = monotonicNowMs()
        val snapshot = service.fetchSnapshot(room.roomId)
        recordRelaySample(startedAtMs, monotonicNowMs(), snapshot.relayTimeMs)
        handleServerMessage(snapshot)
        applyRoomState(
            service.setConnection(
                roomId = room.roomId,
                participantId = room.participantId,
                sessionId = room.sessionId,
                connected = true,
            ),
        )
    }

    private fun handleServerMessage(message: WatchTogetherServerMessage) {
        when (orderer?.evaluate(message)) {
            is ServerMessageDecision.Accepted -> Unit
            ServerMessageDecision.Duplicate,
            ServerMessageDecision.StaleSnapshot,
            -> return
            else -> {
                mutableState.value = mutableState.value.copy(
                    errorMessage = "The service sent an invalid room update.",
                )
                return
            }
        }
        latestRelayTimeMs = message.relayTimeMs
        latestRelayReceivedAtMs = monotonicNowMs()
        when (message.type) {
            WatchTogetherServerMessageType.StateSnapshot -> applyRoomState(
                message.snapshotPayload().state,
            )
            WatchTogetherServerMessageType.CommandRejected -> {
                mutableState.value = mutableState.value.copy(
                    errorMessage = "Room action rejected: ${message.rejectedPayload().code.name}",
                )
            }
            WatchTogetherServerMessageType.CommandAccepted -> {
                if (!message.acceptedPayload().applied) {
                    mutableState.value = mutableState.value.copy(errorMessage = null)
                }
            }
        }
    }

    private fun applyRoomState(next: WatchTogetherRoomState) {
        if (next.status == WatchTogetherRoomStatus.Closed) {
            transitionFromClosedRoom(next)
            return
        }
        val previousRoundId = roomState?.round?.roundId
        roomState = next
        val readiness = WatchTogetherReadinessGate.evaluate(next)
        val self = next.participants.firstOrNull {
            it.participantId == credentials?.participantId
        }
        mutableState.value = mutableState.value.copy(
            roomCapacity = next.capacity,
            admission = next.admission.state,
            participants = next.participants.map { participant ->
                WatchTogetherHostedParticipant(
                    participantId = participant.participantId,
                    displayName = participant.displayName,
                    isHost = participant.role == WatchTogetherParticipantRole.Host,
                    isConnected = participant.connection == WatchTogetherConnectionState.Connected,
                    sourceReady = participant.readiness.sourceReady,
                    viewerReady = participant.readiness.viewerReady,
                    durationMs = participant.readiness.durationMs,
                    durationMismatchAcknowledged =
                        participant.readiness.durationMismatchAcknowledged,
                )
            },
            roundStatus = next.round.status,
            readiness = readiness,
            playerAttached = player != null,
            selfViewerReady = self?.readiness?.viewerReady == true,
            selfDurationMismatchAcknowledged =
                self?.readiness?.durationMismatchAcknowledged == true,
            errorMessage = mutableState.value.errorMessage,
        )
        if (previousRoundId != null && previousRoundId != next.round.roundId) {
            correctionPolicy.reset()
            resetSeekSettlement()
        }
        scheduleCountdownCompletion(next)
        updateCountdownRemaining()
        scope.launch { reconcile() }
    }

    private fun transitionFromClosedRoom(closedState: WatchTogetherRoomState) {
        val serviceId = currentServiceId
        val subscription = roomSubscription
        pendingAdmissionJob?.cancel()
        pendingAdmissionJob = null
        pendingParticipantsJob?.cancel()
        pendingParticipantsJob = null
        reconcileJob?.cancel()
        reconcileJob = null
        countdownCompletionJob?.cancel()
        countdownCompletionJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        subscriptionJob?.cancel()
        subscriptionJob = null
        roomSubscription = null
        roomGeneration += 1L
        credentials = null
        pendingJoin = null
        roomState = closedState
        orderer = null
        latestRelayTimeMs = null
        latestRelayReceivedAtMs = null
        clockEstimator.reset()
        correctionPolicy.reset()
        resetSeekSettlement()
        serviceId?.let { storedServiceId ->
            try {
                roomCredentialStore.delete(storedServiceId)
            } catch (_: Exception) {
                // A closed room must still leave the active client state.
            }
        }
        mutableState.value = WatchTogetherHostedState(
            status = WatchTogetherHostedStatus.Error,
            manifestUrl = mutableState.value.manifestUrl,
            serviceName = mutableState.value.serviceName,
            hostEmail = mutableState.value.hostEmail,
            hostAuthenticated = mutableState.value.hostAuthenticated,
            displayName = mutableState.value.displayName,
            localSourceOffsetMs = mutableState.value.localSourceOffsetMs,
            playerAttached = player != null,
            errorMessage = "This room has closed.",
        )
        scope.launch {
            try {
                subscription?.close()
            } catch (_: Exception) {
                // The room is already closed, so local cleanup is authoritative.
            }
            player?.execute(WatchTogetherPlayerCommand.Pause)
        }
    }

    private fun startPendingAdmissionPolling() {
        if (pendingAdmissionJob?.isActive == true) return
        pendingAdmissionJob = scope.launch {
            while (pendingJoin != null && !closed) {
                delay(PENDING_POLL_INTERVAL_MS)
                refreshPendingAdmission()
            }
        }
    }

    private fun startRoomLoops() {
        reconcileJob?.cancel()
        reconcileJob = scope.launch {
            var tick = 0
            while (credentials != null && !closed) {
                updateCountdownRemaining()
                if (tick % 4 == 0) reconcile()
                tick += 1
                delay(COUNTDOWN_TICK_MS)
            }
        }
        if (credentials?.isHost == true) {
            pendingParticipantsJob = scope.launch {
                while (credentials?.isHost == true && !closed) {
                    refreshPendingParticipants()
                    delay(PENDING_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun scheduleCountdownCompletion(state: WatchTogetherRoomState) {
        countdownCompletionJob?.cancel()
        val endsAtMs = state.round.countdown?.endsAtRelayTimeMs ?: return
        val roomId = state.roomId
        countdownCompletionJob = scope.launch {
            val remainingMs = (endsAtMs - (relayNowMs() ?: endsAtMs)).coerceAtLeast(0L)
            delay(remainingMs + COUNTDOWN_COMPLETION_GRACE_MS)
            val currentRoom = credentials ?: return@launch
            if (currentRoom.roomId != roomId || roomState?.round?.status != WatchTogetherRoundStatus.Countdown) {
                return@launch
            }
            try {
                applyRoomState(checkNotNull(transport).completeCountdown(roomId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                beginRecovery()
            }
        }
    }

    private fun updateCountdownRemaining() {
        val countdown = roomState?.round?.countdown
        mutableState.value = mutableState.value.copy(
            countdownRemainingMs = countdown?.let { value ->
                (value.endsAtRelayTimeMs - (relayNowMs() ?: value.startedAtRelayTimeMs))
                    .coerceAtLeast(0L)
            },
        )
    }

    private fun beginRecovery() {
        if (recoveryJob?.isActive == true || credentials == null || closed) return
        recoveryJob = scope.launch {
            var attempt = 0
            while (credentials != null && !closed) {
                attempt += 1
                mutableState.value = mutableState.value.copy(
                    status = WatchTogetherHostedStatus.Reconnecting,
                    reconnectCount = mutableState.value.reconnectCount + 1,
                    errorMessage = "Connection lost; retrying…",
                )
                delay((500L * attempt).coerceAtMost(5_000L))
                try {
                    subscribeAndRefresh()
                    mutableState.value = mutableState.value.copy(
                        status = WatchTogetherHostedStatus.Connected,
                        errorMessage = null,
                    )
                    return@launch
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Retry while the room and service session remain active.
                }
            }
        }
    }

    private fun sendCommand(type: WatchTogetherClientCommandType, payload: JsonObject) {
        val room = credentials ?: return
        val roundId = roomState?.round?.roundId ?: return
        nextSequence += 1L
        val command = WatchTogetherClientCommand(
            protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
            messageId = "hosted_message_$nextSequence",
            roomId = room.roomId,
            roundId = roundId,
            participantId = room.participantId,
            sessionId = room.sessionId,
            sequence = nextSequence,
            sentAtMs = WatchTogetherPlatformSecurity.nowEpochMs(),
            type = type,
            payload = payload,
        )
        outgoingCommands.trySend(OutgoingCommand(roomGeneration, command))
    }

    private fun sendReadiness(viewerReady: Boolean, acknowledgeMismatch: Boolean? = null) {
        val self = selfParticipant() ?: return
        publishCurrentReadiness(
            viewerReady = viewerReady,
            acknowledgeMismatch = acknowledgeMismatch
                ?: self.readiness.durationMismatchAcknowledged,
        )
    }

    private fun publishCurrentReadiness(viewerReady: Boolean, acknowledgeMismatch: Boolean) {
        val attachedPlayer = player
        if (attachedPlayer == null) {
            sendReadinessPayload(
                WatchTogetherReadinessPayload(
                    sourceReady = false,
                    viewerReady = false,
                    durationMs = null,
                    durationMismatchAcknowledged = false,
                ),
            )
            return
        }
        scope.launch {
            val snapshot = attachedPlayer.snapshot()
            val durationMs = snapshot.durationMs?.takeIf { it > 0L }?.roundedToSecond()
            sendReadinessPayload(
                WatchTogetherReadinessPayload(
                    sourceReady = durationMs != null,
                    viewerReady = viewerReady && durationMs != null,
                    durationMs = durationMs,
                    durationMismatchAcknowledged = acknowledgeMismatch,
                ),
            )
        }
    }

    private fun sendReadinessPayload(payload: WatchTogetherReadinessPayload) {
        if (mutableState.value.status != WatchTogetherHostedStatus.Connected) return
        sendCommand(
            type = WatchTogetherClientCommandType.ParticipantReadiness,
            payload = WatchTogetherJson.encodeToJsonElement(payload) as JsonObject,
        )
    }

    private suspend fun reconcile() {
        if (mutableState.value.status != WatchTogetherHostedStatus.Connected) return
        val attachedPlayer = player ?: return
        val currentRoom = roomState ?: return
        val relayNowMs = relayNowMs() ?: return
        val canonicalPositionMs = try {
            WatchTogetherCanonicalClock.positionAt(currentRoom.round.playback, relayNowMs)
        } catch (_: IllegalArgumentException) {
            return
        }
        val localTargetMs = SourceTimeMapper.localPosition(
            canonicalPositionMs = canonicalPositionMs,
            sourceOffsetMs = mutableState.value.localSourceOffsetMs,
        ).positionMs
        val snapshot = attachedPlayer.snapshot()
        val driftMs = snapshot.localPositionMs - localTargetMs
        mutableState.value = mutableState.value.copy(
            canonicalPositionMs = canonicalPositionMs,
            driftMs = driftMs,
        )

        val desiredPlaying = currentRoom.round.playback.mode == WatchTogetherPlaybackMode.Playing &&
            currentRoom.round.status == WatchTogetherRoundStatus.Active
        val localNowMs = monotonicNowMs()
        val isSettling = localNowMs < seekSettlingUntilMs &&
            seekSettlementAnchorRelayTimeMs == currentRoom.round.playback.anchorRelayTimeMs
        if (!isSettling && seekSettlementAnchorRelayTimeMs != null) resetSeekSettlement()

        if (!desiredPlaying) {
            correctionPolicy.reset()
            if (snapshot.isPlaying) execute(WatchTogetherPlayerCommand.Pause, "Paused to room state")
            if (abs(driftMs) > 250L && !isSettling) {
                executeSeek(localTargetMs, currentRoom.round.playback.anchorRelayTimeMs, "Aligned while paused")
            } else if (isSettling) {
                mutableState.value = mutableState.value.copy(correction = "Waiting for player after seek")
            }
            return
        }

        if (isSettling) {
            if (!snapshot.isPlaying && !resumeIssuedWhileSettling) {
                resumeIssuedWhileSettling = true
                execute(WatchTogetherPlayerCommand.Resume, "Waiting for player after seek")
            }
            return
        }
        if (!snapshot.isPlaying) {
            if (abs(driftMs) > 250L) {
                executeSeek(localTargetMs, currentRoom.round.playback.anchorRelayTimeMs, "Aligned before resume")
            }
            execute(WatchTogetherPlayerCommand.Resume, "Resumed to room state")
            resumeIssuedWhileSettling = true
            return
        }

        when (val decision = correctionPolicy.evaluate(driftMs, localNowMs)) {
            DriftCorrectionDecision.None -> {
                if (abs(driftMs) <= 250L) {
                    mutableState.value = mutableState.value.copy(correction = "Within 250 ms")
                }
            }
            DriftCorrectionDecision.HardSeek -> executeSeek(
                localTargetMs,
                currentRoom.round.playback.anchorRelayTimeMs,
                "Hard seek",
            )
            is DriftCorrectionDecision.TemporaryRate -> {
                val result = attachedPlayer.execute(
                    WatchTogetherPlayerCommand.SetTemporaryRate(decision.rate, decision.durationMs),
                )
                if (result is WatchTogetherPlayerCommandResult.Applied) {
                    mutableState.value = mutableState.value.copy(
                        correction = "Temporary ${decision.rate}× correction",
                    )
                } else {
                    executeSeek(
                        localTargetMs,
                        currentRoom.round.playback.anchorRelayTimeMs,
                        "Rate unsupported; hard seek",
                    )
                }
            }
        }
    }

    private suspend fun executeSeek(positionMs: Long, anchorRelayTimeMs: Long, label: String) {
        val result = execute(WatchTogetherPlayerCommand.SeekTo(positionMs), label)
        if (result is WatchTogetherPlayerCommandResult.Applied) {
            seekSettlingUntilMs = monotonicNowMs() + SEEK_SETTLEMENT_MS
            seekSettlementAnchorRelayTimeMs = anchorRelayTimeMs
            resumeIssuedWhileSettling = false
            mutableState.value = mutableState.value.copy(correction = "$label; waiting for player")
        }
    }

    private suspend fun execute(
        command: WatchTogetherPlayerCommand,
        label: String,
    ): WatchTogetherPlayerCommandResult {
        val result = player?.execute(command)
            ?: return WatchTogetherPlayerCommandResult.Failed("No player attached")
        mutableState.value = mutableState.value.copy(
            correction = when (result) {
                WatchTogetherPlayerCommandResult.Applied -> label
                is WatchTogetherPlayerCommandResult.Failed -> "Correction failed"
                is WatchTogetherPlayerCommandResult.Unsupported -> "Correction unsupported"
            },
        )
        return result
    }

    private suspend fun leaveRoomInternal(notifyService: Boolean, closeHostedRoom: Boolean) {
        val service = transport
        val room = credentials
        val serviceId = currentServiceId
        stopRoomJobs()
        roomGeneration += 1L
        if (notifyService && service != null && room != null) {
            try {
                if (room.isHost && closeHostedRoom) {
                    service.closeRoom(room.roomId)
                } else {
                    service.setConnection(
                        room.roomId,
                        room.participantId,
                        room.sessionId,
                        connected = false,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Local cleanup must still complete when the service is unavailable.
            }
        }
        credentials = null
        pendingJoin = null
        roomState = null
        orderer = null
        latestRelayTimeMs = null
        latestRelayReceivedAtMs = null
        clockEstimator.reset()
        correctionPolicy.reset()
        resetSeekSettlement()
        serviceId?.let(roomCredentialStore::delete)
        mutableState.value = WatchTogetherHostedState(
            status = if (service == null) {
                WatchTogetherHostedStatus.Unconfigured
            } else {
                WatchTogetherHostedStatus.Ready
            },
            manifestUrl = mutableState.value.manifestUrl,
            serviceName = mutableState.value.serviceName,
            hostEmail = mutableState.value.hostEmail,
            hostAuthenticated = mutableState.value.hostAuthenticated,
            displayName = mutableState.value.displayName,
            localSourceOffsetMs = mutableState.value.localSourceOffsetMs,
            playerAttached = player != null,
        )
    }

    private suspend fun stopRoomJobs() {
        pendingAdmissionJob?.cancel()
        pendingAdmissionJob = null
        pendingParticipantsJob?.cancel()
        pendingParticipantsJob = null
        reconcileJob?.cancel()
        reconcileJob = null
        countdownCompletionJob?.cancel()
        countdownCompletionJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        subscriptionJob?.cancel()
        subscriptionJob = null
        roomSubscription?.close()
        roomSubscription = null
    }

    private fun selfParticipant() = roomState?.participants?.firstOrNull {
        it.participantId == credentials?.participantId
    }

    private suspend fun restoreRoomIfAvailable() {
        val serviceId = currentServiceId ?: return
        val stored = roomCredentialStore.load(serviceId) ?: return
        if (stored.expiresAtMs <= WatchTogetherPlatformSecurity.nowEpochMs()) {
            roomCredentialStore.delete(serviceId)
            return
        }
        mutableState.value = mutableState.value.copy(
            status = WatchTogetherHostedStatus.Reconnecting,
            errorMessage = null,
        )
        try {
            connectRoom(
                newCredentials = RoomCredentials(
                    roomId = stored.roomId,
                    roomCode = stored.roomCode,
                    participantId = stored.participantId,
                    sessionId = stored.sessionId,
                    isHost = stored.isHost,
                ),
                initialState = null,
                displayName = stored.displayName,
                invitationSecret = stored.invitationSecret,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            stopRoomJobs()
            credentials = null
            pendingJoin = null
            roomState = null
            orderer = null
            roomCredentialStore.delete(serviceId)
            mutableState.value = mutableState.value.copy(
                status = WatchTogetherHostedStatus.Ready,
                roomCode = null,
                invitationSecret = null,
                isHost = false,
                errorMessage = "The previous room could not be restored.",
            )
        }
    }

    private fun persistRoomCredential(displayName: String, invitationSecret: String) {
        val serviceId = currentServiceId ?: return
        val room = credentials ?: return
        val expiresAtMs = roomState?.expiresAtMs ?: return
        try {
            roomCredentialStore.save(
                serviceId,
                WatchTogetherStoredRoomCredential(
                    roomId = room.roomId,
                    roomCode = room.roomCode,
                    participantId = room.participantId,
                    sessionId = room.sessionId,
                    isHost = room.isHost,
                    displayName = displayName,
                    invitationSecret = if (room.isHost) invitationSecret else "",
                    expiresAtMs = expiresAtMs,
                ),
            )
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                errorMessage = "Reconnect credentials could not be stored on this device.",
            )
        }
    }

    private fun recordRelaySample(localSentAtMs: Long, localReceivedAtMs: Long, relayTimeMs: Long) {
        val estimate = try {
            clockEstimator.record(RelayClockSample(localSentAtMs, localReceivedAtMs, relayTimeMs))
        } catch (_: IllegalArgumentException) {
            return
        }
        mutableState.value = mutableState.value.copy(
            roundTripMs = estimate.roundTripMs,
            clockSampleCount = estimate.sampleCount,
        )
    }

    private fun relayNowMs(): Long? {
        val localNowMs = monotonicNowMs()
        clockEstimator.relayTimeAt(localNowMs)?.let { return it }
        val relayTimeMs = latestRelayTimeMs ?: return null
        val receivedAtMs = latestRelayReceivedAtMs ?: return relayTimeMs
        return relayTimeMs + (localNowMs - receivedAtMs).coerceAtLeast(0L)
    }

    private suspend fun runServiceOperation(
        preserveStatus: Boolean = false,
        operation: suspend () -> Unit,
    ) {
        try {
            operation()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                status = if (preserveStatus) {
                    mutableState.value.status
                } else {
                    WatchTogetherHostedStatus.Error
                },
                errorMessage = serviceErrorMessage(error),
            )
        }
    }

    private fun requireTransport(): WatchTogetherHostedTransport? {
        checkOpen()
        return transport.also { service ->
            if (service == null) {
                mutableState.value = mutableState.value.copy(
                    status = WatchTogetherHostedStatus.Error,
                    errorMessage = "Install a Watch Together service manifest first.",
                )
            }
        }
    }

    private fun checkOpen() {
        check(!closed) { "Watch Together hosted session is closed" }
    }

    private fun resetSeekSettlement() {
        seekSettlingUntilMs = 0L
        seekSettlementAnchorRelayTimeMs = null
        resumeIssuedWhileSettling = false
    }

    private fun monotonicNowMs(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds

    private fun Long.roundedToSecond(): Long =
        ((this + 500L) / 1_000L * 1_000L).coerceAtMost(WATCH_TOGETHER_MAX_SAFE_INTEGER)

    private fun serviceErrorMessage(error: Exception): String {
        val knownCode = knownServiceCodes.firstOrNull { code ->
            error.message.orEmpty().contains(code)
        }
        return knownCode?.replace('_', ' ')?.lowercase()?.replaceFirstChar(Char::uppercase)
            ?: "The Watch Together service could not complete the request."
    }

    private fun joinRejectionMessage(code: String?): String = when (code) {
        "INVALID_INVITE" -> "The room code or invitation secret is invalid."
        "JOIN_RATE_LIMITED" -> "Too many invalid attempts. Try again in ten minutes."
        "ROOM_EXPIRED" -> "This room has closed or expired."
        "ADMISSION_PAUSED" -> "This room is not accepting guests right now."
        "PENDING_LIMIT_REACHED" -> "This room has too many pending requests."
        "HOST_REJECTED" -> "The host declined this join request."
        else -> "The room could not accept this join request."
    }

    private fun normalizeRoomCode(value: String): String =
        value.filter(Char::isLetterOrDigit).uppercase()

    private companion object {
        const val PENDING_POLL_INTERVAL_MS = 2_000L
        const val COUNTDOWN_TICK_MS = 250L
        const val COUNTDOWN_COMPLETION_GRACE_MS = 75L
        const val SEEK_SETTLEMENT_MS = 4_000L

        val knownServiceCodes = listOf(
            "HOST_AUTH_REQUIRED",
            "HOST_NOT_APPROVED",
            "ACTIVE_ROOM_EXISTS",
            "ROOM_FULL",
            "ROOM_EXPIRED",
            "SESSION_NOT_ACTIVE",
            "READINESS_REQUIRED",
            "COUNTDOWN_ACTIVE",
        )
    }
}
