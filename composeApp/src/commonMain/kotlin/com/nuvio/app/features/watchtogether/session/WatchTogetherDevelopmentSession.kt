package com.nuvio.app.features.watchtogether.session

import com.nuvio.app.features.watchtogether.protocol.RelayClockEstimator
import com.nuvio.app.features.watchtogether.protocol.RelayClockSample
import com.nuvio.app.features.watchtogether.protocol.ServerMessageDecision
import com.nuvio.app.features.watchtogether.protocol.ServerMessageOrderer
import com.nuvio.app.features.watchtogether.protocol.WATCH_TOGETHER_PROTOCOL_VERSION
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherCanonicalClock
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherClientCommand
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherClientCommandType
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherConnectionState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherContractValidator
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherJson
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlaybackMode
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerAdapter
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerCommand
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerCommandResult
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoomState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherSeekPayload
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServerMessage
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServerMessageType
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherParticipantRole
import com.nuvio.app.features.watchtogether.protocol.rejectedPayload
import com.nuvio.app.features.watchtogether.protocol.snapshotPayload
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.time.TimeSource

internal class WatchTogetherDevelopmentSession(
    private val scope: CoroutineScope,
    private val player: WatchTogetherPlayerAdapter,
    private val client: HttpClient = HttpClient { install(WebSockets) },
) {
    private sealed interface InitialRequest {
        data class Create(val displayName: String) : InitialRequest
        data class Join(val displayName: String, val roomCode: String) : InitialRequest
        data class Reconnect(val credentials: Credentials) : InitialRequest
    }

    private data class Credentials(
        val roomId: String,
        val roomCode: String,
        val participantId: String,
        val sessionId: String,
        val reconnectToken: String,
        val roundId: String,
    )

    private data class OutgoingCommandFrame(
        val socket: DefaultClientWebSocketSession,
        val text: String,
    )

    private val clockEstimator = RelayClockEstimator()
    private val monotonicOrigin = TimeSource.Monotonic.markNow()
    private val mutableState = MutableStateFlow(WatchTogetherDevelopmentState())
    private var activeSocket: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private var clockJob: Job? = null
    private var reconcileJob: Job? = null
    private var credentials: Credentials? = null
    private var orderer: ServerMessageOrderer? = null
    private var latestRoomState: WatchTogetherRoomState? = null
    private var latestRelayTimeMs: Long? = null
    private var latestRelayReceivedAtMs: Long? = null
    private var nextSequence = 0L
    private var nextRequest = 0L
    private var manualDisconnect = false
    private var seekSettlingUntilMs = 0L
    private var seekSettlementAnchorRelayTimeMs: Long? = null
    private var resumeIssuedWhileSettling = false
    private val correctionPolicy = DriftCorrectionPolicy()
    private val outgoingCommandFrames = Channel<OutgoingCommandFrame>(Channel.UNLIMITED)
    private val commandSenderJob = scope.launch {
        for (frame in outgoingCommandFrames) {
            if (frame.socket !== activeSocket) continue
            try {
                frame.socket.send(Frame.Text(frame.text))
            } catch (_: Exception) {
                // The connection loop exposes the reconnect state.
            }
        }
    }

    val state: StateFlow<WatchTogetherDevelopmentState> = mutableState.asStateFlow()

    fun createRoom(endpoint: String, displayName: String) {
        begin(endpoint, displayName, InitialRequest.Create(displayName.trim()))
    }

    fun joinRoom(endpoint: String, displayName: String, roomCode: String) {
        begin(
            endpoint,
            displayName,
            InitialRequest.Join(displayName.trim(), roomCode.trim().uppercase()),
        )
    }

    fun requestPlayback(shouldPlay: Boolean): Boolean {
        if (mutableState.value.status != WatchTogetherDevelopmentStatus.Connected) return false
        val type = if (shouldPlay) {
            WatchTogetherClientCommandType.PlaybackResume
        } else {
            WatchTogetherClientCommandType.PlaybackPause
        }
        sendCommand(type = type, payload = JsonObject(emptyMap()))
        return true
    }

    fun requestSeek(positionMs: Long): Boolean {
        if (mutableState.value.status != WatchTogetherDevelopmentStatus.Connected) return false
        val payload = WatchTogetherJson.encodeToJsonElement(
            WatchTogetherSeekPayload(positionMs.coerceAtLeast(0L)),
        ).jsonObject
        sendCommand(type = WatchTogetherClientCommandType.PlaybackSeek, payload = payload)
        return true
    }

    fun simulateConnectionLoss(): Boolean {
        if (mutableState.value.status != WatchTogetherDevelopmentStatus.Connected) return false
        val socket = activeSocket ?: return false
        val frame = buildJsonObject {
            put("type", "session.drop")
            put("requestId", requestId())
        }
        mutableState.value = mutableState.value.copy(
            correction = "Starting reconnect test",
            errorMessage = null,
        )
        outgoingCommandFrames.trySend(
            OutgoingCommandFrame(socket = socket, text = frame.toString()),
        )
        return true
    }

    fun leave() {
        manualDisconnect = true
        connectionJob?.cancel()
        connectionJob = null
        clockJob?.cancel()
        clockJob = null
        reconcileJob?.cancel()
        reconcileJob = null
        scope.launch { activeSocket?.close() }
        activeSocket = null
        credentials = null
        orderer = null
        latestRoomState = null
        latestRelayTimeMs = null
        latestRelayReceivedAtMs = null
        clockEstimator.reset()
        correctionPolicy.reset()
        resetSeekSettlement()
        mutableState.value = WatchTogetherDevelopmentState(
            endpoint = mutableState.value.endpoint,
            displayName = mutableState.value.displayName,
        )
    }

    fun close() {
        leave()
        outgoingCommandFrames.close()
        commandSenderJob.cancel()
        client.close()
    }

    private fun begin(endpoint: String, displayName: String, request: InitialRequest) {
        val normalizedEndpoint = endpoint.trim()
        val normalizedName = displayName.trim()
        val error = when {
            !normalizedEndpoint.startsWith("ws://") && !normalizedEndpoint.startsWith("wss://") -> {
                "Relay URL must start with ws:// or wss://"
            }
            normalizedName.isEmpty() -> "Display name is required"
            normalizedName.length > 40 -> "Display name must contain at most 40 characters"
            request is InitialRequest.Join && request.roomCode.replace("-", "").length != 8 -> {
                "Room code must contain 8 characters"
            }
            else -> null
        }
        if (error != null) {
            mutableState.value = mutableState.value.copy(
                status = WatchTogetherDevelopmentStatus.Error,
                endpoint = normalizedEndpoint,
                displayName = normalizedName,
                errorMessage = error,
            )
            return
        }

        leave()
        manualDisconnect = false
        mutableState.value = WatchTogetherDevelopmentState(
            status = WatchTogetherDevelopmentStatus.Connecting,
            endpoint = normalizedEndpoint,
            displayName = normalizedName,
        )
        connectionJob = scope.launch { connectLoop(normalizedEndpoint, request) }
    }

    private suspend fun connectLoop(endpoint: String, firstRequest: InitialRequest) {
        var request = firstRequest
        var reconnectAttempt = 0
        while (!manualDisconnect) {
            try {
                client.webSocket(urlString = endpoint) {
                    activeSocket = this
                    sendInitialRequest(request)
                    for (frame in incoming) {
                        if (frame is Frame.Text) handleFrame(frame.readText())
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (credentials == null) {
                    mutableState.value = mutableState.value.copy(
                        status = WatchTogetherDevelopmentStatus.Error,
                        errorMessage = error.message ?: "Could not connect to the relay",
                    )
                    return
                }
            } finally {
                activeSocket = null
                clockJob?.cancel()
                clockJob = null
            }

            val reconnectCredentials = credentials ?: return
            if (manualDisconnect) return
            reconnectAttempt += 1
            mutableState.value = mutableState.value.copy(
                status = WatchTogetherDevelopmentStatus.Reconnecting,
                reconnectCount = mutableState.value.reconnectCount + 1,
                errorMessage = "Connection lost; retrying…",
            )
            delay((500L * reconnectAttempt).coerceAtMost(5_000L))
            request = InitialRequest.Reconnect(reconnectCredentials)
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendInitialRequest(request: InitialRequest) {
        val requestId = requestId()
        val frame = when (request) {
            is InitialRequest.Create -> buildJsonObject {
                put("type", "room.create")
                put("requestId", requestId)
                put("displayName", request.displayName)
            }
            is InitialRequest.Join -> buildJsonObject {
                put("type", "room.join")
                put("requestId", requestId)
                put("roomCode", request.roomCode)
                put("displayName", request.displayName)
            }
            is InitialRequest.Reconnect -> buildJsonObject {
                put("type", "session.reconnect")
                put("requestId", requestId)
                put("roomId", request.credentials.roomId)
                put("participantId", request.credentials.participantId)
                put("reconnectToken", request.credentials.reconnectToken)
            }
        }
        send(Frame.Text(frame.toString()))
    }

    private fun handleFrame(text: String) {
        val element = try {
            WatchTogetherJson.parseToJsonElement(text).jsonObject
        } catch (error: Exception) {
            fail("Relay sent invalid JSON: ${error.message ?: "unknown error"}")
            return
        }
        when (element["type"]?.jsonPrimitive?.content) {
            "session.ready" -> handleReady(
                WatchTogetherJson.decodeFromJsonElement<DevelopmentSessionReadyFrame>(element),
            )
            "protocol.message" -> handleProtocolMessage(
                WatchTogetherJson.decodeFromJsonElement<DevelopmentProtocolMessageFrame>(element).message,
            )
            "clock.pong" -> handleClockPong(
                WatchTogetherJson.decodeFromJsonElement<DevelopmentClockPongFrame>(element),
            )
            "relay.error" -> {
                val error = WatchTogetherJson.decodeFromJsonElement<DevelopmentRelayErrorFrame>(element)
                stopWithError("${error.code}: ${error.message}")
            }
            else -> fail("Relay sent an unsupported frame")
        }
    }

    private fun handleReady(frame: DevelopmentSessionReadyFrame) {
        credentials = Credentials(
            roomId = frame.roomId,
            roomCode = frame.roomCode,
            participantId = frame.participantId,
            sessionId = frame.sessionId,
            reconnectToken = frame.reconnectToken,
            roundId = frame.roundId,
        )
        nextSequence = 0L
        orderer = ServerMessageOrderer(frame.roomId, WATCH_TOGETHER_PROTOCOL_VERSION)
        resetSeekSettlement()
        mutableState.value = mutableState.value.copy(
            status = WatchTogetherDevelopmentStatus.Connected,
            roomCode = frame.roomCode,
            errorMessage = null,
        )
        handleProtocolMessage(frame.snapshot)
        startClockSampling()
        startReconciliation()
    }

    private fun handleProtocolMessage(message: WatchTogetherServerMessage) {
        val validation = WatchTogetherContractValidator.validate(message)
        if (!validation.isValid) {
            fail("Relay message failed protocol validation: ${validation.issues.first().message}")
            return
        }
        when (orderer?.evaluate(message)) {
            is ServerMessageDecision.Accepted -> Unit
            ServerMessageDecision.Duplicate,
            ServerMessageDecision.StaleSnapshot,
            -> return
            ServerMessageDecision.ConflictingMessageId,
            ServerMessageDecision.UnsupportedProtocol,
            ServerMessageDecision.WrongRoom,
            null,
            -> {
                fail("Relay message ordering or identity was invalid")
                return
            }
        }
        if (message.type == WatchTogetherServerMessageType.CommandRejected) {
            fail("Relay rejected a command: ${message.rejectedPayload().code}")
            return
        }
        if (message.type != WatchTogetherServerMessageType.StateSnapshot) return
        val room = message.snapshotPayload().state
        latestRoomState = room
        latestRelayTimeMs = message.relayTimeMs
        latestRelayReceivedAtMs = monotonicNowMs()
        mutableState.value = mutableState.value.copy(
            participants = room.participants.map { participant ->
                WatchTogetherDevelopmentParticipant(
                    displayName = participant.displayName,
                    isHost = participant.role == WatchTogetherParticipantRole.Host,
                    isConnected = participant.connection == WatchTogetherConnectionState.Connected,
                )
            },
        )
        scope.launch { reconcile() }
    }

    private fun handleClockPong(frame: DevelopmentClockPongFrame) {
        val receivedAtMs = monotonicNowMs()
        val estimate = try {
            clockEstimator.record(
                RelayClockSample(
                    localSentAtMs = frame.clientSentAtMs,
                    localReceivedAtMs = receivedAtMs,
                    relayTimeMs = frame.relayTimeMs,
                ),
            )
        } catch (error: IllegalArgumentException) {
            fail("Relay clock sample was invalid")
            return
        }
        mutableState.value = mutableState.value.copy(
            roundTripMs = estimate.roundTripMs,
            clockSampleCount = estimate.sampleCount,
        )
        scope.launch { reconcile() }
    }

    private fun startClockSampling() {
        clockJob?.cancel()
        clockJob = scope.launch {
            sendClockPing()
            delay(1_000L)
            sendClockPing()
            delay(2_000L)
            sendClockPing()
            while (true) {
                delay(30_000L)
                sendClockPing()
            }
        }
    }

    private fun startReconciliation() {
        if (reconcileJob?.isActive == true) return
        reconcileJob = scope.launch {
            while (true) {
                delay(1_000L)
                reconcile()
            }
        }
    }

    private suspend fun sendClockPing() {
        val socket = activeSocket ?: return
        val sentAtMs = monotonicNowMs()
        val frame = buildJsonObject {
            put("type", "clock.ping")
            put("requestId", requestId())
            put("clientSentAtMs", sentAtMs)
        }
        socket.send(Frame.Text(frame.toString()))
    }

    private fun sendCommand(type: WatchTogetherClientCommandType, payload: JsonObject) {
        val currentCredentials = credentials ?: return
        val socket = activeSocket ?: return
        nextSequence += 1L
        val command = WatchTogetherClientCommand(
            protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
            messageId = "client_${monotonicNowMs()}_$nextSequence",
            roomId = currentCredentials.roomId,
            roundId = currentCredentials.roundId,
            participantId = currentCredentials.participantId,
            sessionId = currentCredentials.sessionId,
            sequence = nextSequence,
            sentAtMs = monotonicNowMs(),
            type = type,
            payload = payload,
        )
        val validation = WatchTogetherContractValidator.validate(command)
        if (!validation.isValid) {
            fail("Client command failed protocol validation")
            return
        }
        val commandJson = WatchTogetherJson.encodeToJsonElement(command)
        val frame = buildJsonObject {
            put("type", "room.command")
            put("requestId", requestId())
            put("command", commandJson)
        }
        outgoingCommandFrames.trySend(
            OutgoingCommandFrame(socket = socket, text = frame.toString()),
        )
    }

    private suspend fun reconcile() {
        if (mutableState.value.status != WatchTogetherDevelopmentStatus.Connected) return
        val room = latestRoomState ?: return
        val relayNowMs = relayNowMs() ?: return
        val canonicalPositionMs = try {
            WatchTogetherCanonicalClock.positionAt(room.round.playback, relayNowMs)
        } catch (_: IllegalArgumentException) {
            return
        }
        val snapshot = player.snapshot()
        val driftMs = snapshot.localPositionMs - canonicalPositionMs
        mutableState.value = mutableState.value.copy(
            canonicalPositionMs = canonicalPositionMs,
            driftMs = driftMs,
        )

        val desiredPlaying = room.round.playback.mode == WatchTogetherPlaybackMode.Playing
        val localNowMs = monotonicNowMs()
        val isSettling = localNowMs < seekSettlingUntilMs &&
            seekSettlementAnchorRelayTimeMs == room.round.playback.anchorRelayTimeMs
        if (!isSettling && seekSettlementAnchorRelayTimeMs != null) resetSeekSettlement()

        if (!desiredPlaying) {
            correctionPolicy.reset()
            if (snapshot.isPlaying) execute(WatchTogetherPlayerCommand.Pause, "Paused to canonical state")
            if (abs(driftMs) > 250L && !isSettling) {
                executeSeek(
                    positionMs = canonicalPositionMs,
                    anchorRelayTimeMs = room.round.playback.anchorRelayTimeMs,
                    label = "Hard seek while paused",
                )
            } else if (isSettling) {
                mutableState.value = mutableState.value.copy(correction = "Waiting for player after seek")
            }
            return
        }

        if (isSettling) {
            if (!snapshot.isPlaying && !resumeIssuedWhileSettling) {
                resumeIssuedWhileSettling = true
                execute(WatchTogetherPlayerCommand.Resume, "Waiting for player after seek")
            } else {
                mutableState.value = mutableState.value.copy(correction = "Waiting for player after seek")
            }
            return
        }

        if (!snapshot.isPlaying) {
            if (abs(driftMs) > 250L) {
                executeSeek(
                    positionMs = canonicalPositionMs,
                    anchorRelayTimeMs = room.round.playback.anchorRelayTimeMs,
                    label = "Aligned before resume",
                )
            }
            execute(WatchTogetherPlayerCommand.Resume, "Resumed to canonical state")
            resumeIssuedWhileSettling = true
            return
        }

        when (val decision = correctionPolicy.evaluate(driftMs, localNowMs)) {
            DriftCorrectionDecision.None -> {
                if (abs(driftMs) <= 250L) {
                    mutableState.value = mutableState.value.copy(correction = "Within 250 ms")
                }
            }

            DriftCorrectionDecision.HardSeek -> {
                executeSeek(
                    positionMs = canonicalPositionMs,
                    anchorRelayTimeMs = room.round.playback.anchorRelayTimeMs,
                    label = "Hard seek",
                )
            }

            is DriftCorrectionDecision.TemporaryRate -> {
                val result = player.execute(
                    WatchTogetherPlayerCommand.SetTemporaryRate(
                        rate = decision.rate,
                        durationMs = decision.durationMs,
                    ),
                )
                if (result is WatchTogetherPlayerCommandResult.Applied) {
                    mutableState.value = mutableState.value.copy(
                        correction = "Temporary ${decision.rate}× correction",
                    )
                } else {
                    executeSeek(
                        positionMs = canonicalPositionMs,
                        anchorRelayTimeMs = room.round.playback.anchorRelayTimeMs,
                        label = "Rate unsupported; hard seek",
                    )
                }
            }
        }
    }

    private suspend fun executeSeek(
        positionMs: Long,
        anchorRelayTimeMs: Long,
        label: String,
    ) {
        val result = execute(WatchTogetherPlayerCommand.SeekTo(positionMs), label)
        if (result is WatchTogetherPlayerCommandResult.Applied) {
            seekSettlingUntilMs = monotonicNowMs() + SEEK_SETTLEMENT_MS
            seekSettlementAnchorRelayTimeMs = anchorRelayTimeMs
            resumeIssuedWhileSettling = false
            mutableState.value = mutableState.value.copy(
                correction = "$label; waiting for player",
            )
        }
    }

    private suspend fun execute(
        command: WatchTogetherPlayerCommand,
        label: String,
    ): WatchTogetherPlayerCommandResult {
        val result = player.execute(command)
        when (result) {
            WatchTogetherPlayerCommandResult.Applied -> {
                mutableState.value = mutableState.value.copy(correction = label)
            }
            is WatchTogetherPlayerCommandResult.Failed -> {
                mutableState.value = mutableState.value.copy(correction = "Correction failed")
            }
            is WatchTogetherPlayerCommandResult.Unsupported -> {
                mutableState.value = mutableState.value.copy(correction = "Correction unsupported")
            }
        }
        return result
    }

    private fun resetSeekSettlement() {
        seekSettlingUntilMs = 0L
        seekSettlementAnchorRelayTimeMs = null
        resumeIssuedWhileSettling = false
    }

    private fun relayNowMs(): Long? {
        val localNowMs = monotonicNowMs()
        clockEstimator.relayTimeAt(localNowMs)?.let { return it }
        val relayTimeMs = latestRelayTimeMs ?: return null
        val receivedAtMs = latestRelayReceivedAtMs ?: return relayTimeMs
        return relayTimeMs + (localNowMs - receivedAtMs).coerceAtLeast(0L)
    }

    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(
            status = if (credentials == null) {
                WatchTogetherDevelopmentStatus.Error
            } else {
                mutableState.value.status
            },
            errorMessage = message,
        )
    }

    private fun stopWithError(message: String) {
        manualDisconnect = true
        credentials = null
        clockJob?.cancel()
        reconcileJob?.cancel()
        scope.launch { activeSocket?.close() }
        mutableState.value = mutableState.value.copy(
            status = WatchTogetherDevelopmentStatus.Error,
            errorMessage = message,
        )
    }

    private fun requestId(): String {
        nextRequest += 1L
        return "request_${monotonicNowMs()}_$nextRequest"
    }

    private fun monotonicNowMs(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds

    private companion object {
        const val SEEK_SETTLEMENT_MS = 4_000L
    }
}
