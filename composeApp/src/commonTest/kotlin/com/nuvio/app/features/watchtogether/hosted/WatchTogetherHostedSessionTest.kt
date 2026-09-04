package com.nuvio.app.features.watchtogether.hosted

import com.nuvio.app.features.watchtogether.protocol.WATCH_TOGETHER_PROTOCOL_VERSION
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherAdmissionState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherAdmissionStatus
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherClientCommand
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherClientCommandType
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherCommandAcceptedPayload
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherConnectionState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherJson
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherParticipantRole
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherParticipantState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlaybackAnchor
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlaybackMode
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerAdapter
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerCapability
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerCommand
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerCommandResult
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherPlayerSnapshot
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherReadinessState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoomState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoomStatus
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoundState
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherRoundStatus
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServerMessage
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServerMessageType
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherStateSnapshotPayload
import com.nuvio.app.features.watchtogether.protocol.readinessPayload
import com.nuvio.app.features.watchtogether.protocol.seekPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTogetherHostedSessionTest {
    @Test
    fun `host authentication and room creation preserve the player position`() = runBlocking {
        val transport = FakeHostedTransport()
        val connector = FakeHostedConnector(transport)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = WatchTogetherHostedSession(
            scope,
            connector,
            FakeRoomCredentialStore(),
            FakeServiceConfigurationStore(),
        )
        val player = FakeHostedPlayer(positionMs = 42_321L, durationMs = 3_600_499L)
        session.attachPlayer(player)

        try {
            session.loadService(manifestUrl)
            session.requestHostEmailOtp(" HOST@example.test ")
            session.verifyHostEmailOtp(" HOST@example.test ", " 123456 ")
            session.createRoom(" Pilot Host ", 4)
            waitUntil { transport.appliedCommands.any { it.type == WatchTogetherClientCommandType.ParticipantReadiness } }

            assertEquals(listOf("host@example.test"), transport.otpRequests)
            assertEquals(listOf("host@example.test" to "123456"), transport.otpVerifications)
            assertEquals(42_321L, transport.createRequests.single().initialPositionMs)
            assertEquals(4, transport.createRequests.single().capacity)
            assertEquals(WatchTogetherHostedStatus.Connected, session.state.value.status)
            assertEquals("ABCD1234", session.state.value.roomCode)
            assertTrue(session.state.value.isHost)
            assertTrue(session.state.value.invitationSecret.orEmpty().matches(inviteSecretPattern))

            val readiness = transport.appliedCommands
                .first { it.type == WatchTogetherClientCommandType.ParticipantReadiness }
                .readinessPayload()
            assertEquals(3_600_000L, readiness.durationMs)
            assertFalse(readiness.viewerReady)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test
    fun `a room can be created before a source is selected`() = runBlocking {
        val transport = FakeHostedTransport()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = WatchTogetherHostedSession(
            scope,
            FakeHostedConnector(transport),
            FakeRoomCredentialStore(),
            FakeServiceConfigurationStore(),
        )

        try {
            session.loadService(manifestUrl)
            session.createRoom("Pilot Host", 2)

            assertEquals(0L, transport.createRequests.single().initialPositionMs)
            assertFalse(session.state.value.playerAttached)

            session.attachPlayer(
                FakeHostedPlayer(positionMs = 0L, durationMs = 1_800_499L),
            )
            waitUntil {
                transport.appliedCommands
                    .filter { it.type == WatchTogetherClientCommandType.ParticipantReadiness }
                    .map { it.readinessPayload() }
                    .any { it.sourceReady && it.durationMs == 1_800_000L }
            }

            assertTrue(session.state.value.playerAttached)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test
    fun `pending guest can observe an explicit host rejection`() = runBlocking {
        val transport = FakeHostedTransport().apply {
            joinResults += WatchTogetherJoinResult(
                status = WatchTogetherJoinStatus.Pending,
                roomId = roomId,
                participantId = guestParticipantId,
                sessionId = guestSessionId,
            )
            joinResults += WatchTogetherJoinResult(
                status = WatchTogetherJoinStatus.Rejected,
                code = "HOST_REJECTED",
            )
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = WatchTogetherHostedSession(
            scope,
            FakeHostedConnector(transport),
            FakeRoomCredentialStore(),
            FakeServiceConfigurationStore(),
        )
        val player = FakeHostedPlayer(positionMs = 12_000L, durationMs = 1_800_000L)
        session.attachPlayer(player)

        try {
            session.loadService(manifestUrl)
            session.joinRoom("ABCD-1234", inviteSecret, "Pilot Guest")

            assertEquals(1, transport.anonymousSignIns)
            assertEquals(WatchTogetherHostedStatus.AwaitingApproval, session.state.value.status)
            assertEquals(1, player.commands.size)
            assertTrue(player.commands.single() is WatchTogetherPlayerCommand.Pause)

            session.refreshPendingAdmission()

            assertEquals(WatchTogetherHostedStatus.Error, session.state.value.status)
            assertEquals("The host declined this join request.", session.state.value.errorMessage)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test
    fun `local source offsets map seeks and outgoing commands remain serialized`() = runBlocking {
        val transport = FakeHostedTransport(applyDelayMs = 15L)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = WatchTogetherHostedSession(
            scope,
            FakeHostedConnector(transport),
            FakeRoomCredentialStore(),
            FakeServiceConfigurationStore(),
        )
        session.attachPlayer(FakeHostedPlayer(positionMs = 42_000L, durationMs = 3_600_000L))

        try {
            session.loadService(manifestUrl)
            session.createRoom("Pilot Host", 2)
            waitUntil { transport.appliedCommands.any { it.type == WatchTogetherClientCommandType.ParticipantReadiness } }
            val commandCountBeforeSeeks = transport.appliedCommands.size

            session.setLocalSourceOffset(10_000L)
            assertTrue(session.requestSeek(25_000L))
            assertTrue(session.requestSeek(30_000L))
            assertTrue(session.requestSeek(35_000L))
            waitUntil { transport.appliedCommands.size >= commandCountBeforeSeeks + 3 }

            val seeks = transport.appliedCommands
                .filter { it.type == WatchTogetherClientCommandType.PlaybackSeek }
                .takeLast(3)
            assertEquals(listOf(15_000L, 20_000L, 25_000L), seeks.map { it.seekPayload().positionMs })
            assertEquals(seeks.map { it.sequence }.sorted(), seeks.map { it.sequence })
            assertEquals(1, transport.maxConcurrentApplies)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test
    fun `room credentials restore an active room after a client restart`() = runBlocking {
        val roomStore = FakeRoomCredentialStore()
        val serviceStore = FakeServiceConfigurationStore()
        val firstScope = CoroutineScope(coroutineContext + SupervisorJob())
        val firstSession = WatchTogetherHostedSession(
            firstScope,
            FakeHostedConnector(FakeHostedTransport()),
            roomStore,
            serviceStore,
        )
        firstSession.attachPlayer(
            FakeHostedPlayer(positionMs = 42_000L, durationMs = 3_600_000L),
        )
        firstSession.loadService(manifestUrl)
        firstSession.createRoom("Pilot Host", 2)
        waitUntil { roomStore.load(serviceId) != null }
        firstScope.cancel()

        val restoredTransport = FakeHostedTransport()
        val restoredScope = CoroutineScope(coroutineContext + SupervisorJob())
        val restoredSession = WatchTogetherHostedSession(
            restoredScope,
            FakeHostedConnector(restoredTransport),
            roomStore,
            serviceStore,
        )
        restoredSession.attachPlayer(
            FakeHostedPlayer(positionMs = 42_000L, durationMs = 3_600_000L),
        )

        try {
            restoredSession.restoreInstalledService()

            assertEquals(WatchTogetherHostedStatus.Connected, restoredSession.state.value.status)
            assertEquals("ABCD1234", restoredSession.state.value.roomCode)
            assertTrue(restoredSession.state.value.isHost)
            assertEquals("Pilot Host", restoredSession.state.value.displayName)
        } finally {
            restoredSession.close()
            restoredScope.cancel()
        }
    }

    @Test
    fun `rotating an invitation updates the stored reconnect credential`() = runBlocking {
        val roomStore = FakeRoomCredentialStore()
        val transport = FakeHostedTransport()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = WatchTogetherHostedSession(
            scope,
            FakeHostedConnector(transport),
            roomStore,
            FakeServiceConfigurationStore(),
        )

        try {
            session.loadService(manifestUrl)
            session.createRoom("Pilot Host", 2)
            val originalSecret = roomStore.load(serviceId)?.invitationSecret

            session.rotateInvitation()

            val rotatedSecret = roomStore.load(serviceId)?.invitationSecret
            assertTrue(originalSecret.orEmpty().matches(inviteSecretPattern))
            assertTrue(rotatedSecret.orEmpty().matches(inviteSecretPattern))
            assertTrue(rotatedSecret != originalSecret)
            assertEquals(listOf(rotatedSecret), transport.rotatedInviteSecrets)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test
    fun `a closed room stops the active session and removes reconnect credentials`() = runBlocking {
        val roomStore = FakeRoomCredentialStore()
        val transport = FakeHostedTransport()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = WatchTogetherHostedSession(
            scope,
            FakeHostedConnector(transport),
            roomStore,
            FakeServiceConfigurationStore(),
        )
        val player = FakeHostedPlayer(positionMs = 42_000L, durationMs = 3_600_000L)
        session.attachPlayer(player)

        try {
            session.loadService(manifestUrl)
            session.createRoom("Pilot Host", 2)
            waitUntil { transport.roomSubscriberCount > 0 }

            transport.emitClosedRoom()
            waitUntil { session.state.value.status == WatchTogetherHostedStatus.Error }

            assertFalse(session.state.value.isActive)
            assertEquals("This room has closed.", session.state.value.errorMessage)
            assertEquals(null, roomStore.load(serviceId))
            waitUntil { player.commands.any { it is WatchTogetherPlayerCommand.Pause } }
        } finally {
            session.close()
            scope.cancel()
        }
    }
}

private class FakeHostedConnector(
    private val transport: WatchTogetherHostedTransport,
) : WatchTogetherHostedServiceConnector {
    override suspend fun connect(manifestUrl: String): WatchTogetherHostedServiceConnection {
        assertEquals(CompanionManifestUrl, manifestUrl)
        return WatchTogetherHostedServiceConnection.Connected(
            serviceId = serviceId,
            serviceName = "Pilot service",
            transport = transport,
        )
    }

    override fun close() = Unit

    private companion object {
        const val CompanionManifestUrl = "https://watch.example.test/manifest.json"
    }
}

private class FakeRoomCredentialStore : WatchTogetherRoomCredentialStore {
    private val values = mutableMapOf<String, WatchTogetherStoredRoomCredential>()

    override fun load(serviceId: String): WatchTogetherStoredRoomCredential? = values[serviceId]

    override fun save(serviceId: String, credential: WatchTogetherStoredRoomCredential) {
        values[serviceId] = credential
    }

    override fun delete(serviceId: String) {
        values.remove(serviceId)
    }
}

private class FakeServiceConfigurationStore : WatchTogetherServiceConfigurationStore {
    private var manifestUrl: String? = null

    override fun loadManifestUrl(): String? = manifestUrl

    override fun saveManifestUrl(manifestUrl: String) {
        this.manifestUrl = manifestUrl
    }

    override fun deleteManifestUrl() {
        manifestUrl = null
    }
}

private class FakeHostedTransport(
    private val applyDelayMs: Long = 0L,
) : WatchTogetherHostedTransport {
    val otpRequests = mutableListOf<String>()
    val otpVerifications = mutableListOf<Pair<String, String>>()
    val createRequests = mutableListOf<WatchTogetherCreateRoomRequest>()
    val joinResults = mutableListOf<WatchTogetherJoinResult>()
    val appliedCommands = mutableListOf<WatchTogetherClientCommand>()
    val rotatedInviteSecrets = mutableListOf<String>()
    private val roomMessages = MutableSharedFlow<WatchTogetherServerMessage>(
        extraBufferCapacity = 8,
    )
    val roomSubscriberCount: Int
        get() = roomMessages.subscriptionCount.value
    var anonymousSignIns = 0
    var maxConcurrentApplies = 0
    private var concurrentApplies = 0
    private var responseNumber = 0L
    private var currentState = roomState()

    override suspend fun signInAnonymously() {
        anonymousSignIns += 1
    }

    override suspend fun requestHostEmailOtp(email: String) {
        otpRequests += email
    }

    override suspend fun verifyHostEmailOtp(email: String, token: String) {
        otpVerifications += email to token
    }

    override suspend fun createRoom(request: WatchTogetherCreateRoomRequest): WatchTogetherCreatedRoom {
        createRequests += request
        currentState = roomState(initialPositionMs = request.initialPositionMs)
        return WatchTogetherCreatedRoom(
            roomCode = "ABCD1234",
            roomId = roomId,
            participantId = hostParticipantId,
            sessionId = hostSessionId,
            roundId = roundId,
            expiresAtMs = currentState.expiresAtMs,
            state = currentState,
        )
    }

    override suspend fun requestJoin(request: WatchTogetherJoinRoomRequest): WatchTogetherJoinResult =
        joinResults.removeAt(0)

    override suspend fun pollJoin(
        roomId: String,
        participantId: String,
    ): WatchTogetherJoinResult = joinResults.removeAt(0)

    override suspend fun pendingParticipants(roomId: String): List<WatchTogetherPendingParticipant> =
        emptyList()

    override suspend fun resolveJoinRequest(
        roomId: String,
        participantId: String,
        approve: Boolean,
    ): WatchTogetherResolvedJoin = WatchTogetherResolvedJoin(
        participantId = participantId,
        status = if (approve) WatchTogetherJoinResolution.Admitted else WatchTogetherJoinResolution.Rejected,
        state = currentState,
    )

    override suspend fun fetchSnapshot(roomId: String): WatchTogetherServerMessage =
        snapshot(currentState, messageId = "snapshot_${responseNumber++}")

    override suspend fun applyCommand(command: WatchTogetherClientCommand): WatchTogetherServerMessage {
        concurrentApplies += 1
        maxConcurrentApplies = maxOf(maxConcurrentApplies, concurrentApplies)
        try {
            if (applyDelayMs > 0L) delay(applyDelayMs)
            appliedCommands += command
            return WatchTogetherServerMessage(
                protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
                messageId = "accepted_${responseNumber++}",
                roomId = roomId,
                revision = currentState.revision,
                relayTimeMs = 1_000_000L + responseNumber,
                type = WatchTogetherServerMessageType.CommandAccepted,
                payload = WatchTogetherJson.encodeToJsonElement(
                    WatchTogetherCommandAcceptedPayload(command.messageId, applied = true),
                ).jsonObject,
            )
        } finally {
            concurrentApplies -= 1
        }
    }

    override suspend fun rotateInvitation(
        roomId: String,
        newInviteSecret: String,
    ): WatchTogetherRotatedInvite {
        rotatedInviteSecrets += newInviteSecret
        return WatchTogetherRotatedInvite(2L, currentState)
    }

    override suspend fun closeRoom(roomId: String): WatchTogetherRoomState =
        currentState.copy(status = WatchTogetherRoomStatus.Closed)

    override suspend fun beginCountdown(roomId: String, force: Boolean): WatchTogetherRoomState = currentState

    override suspend fun cancelCountdown(roomId: String): WatchTogetherRoomState = currentState

    override suspend fun completeCountdown(roomId: String): WatchTogetherRoomState = currentState

    override suspend fun beginRound(roomId: String): WatchTogetherRoomState = currentState

    override suspend fun setConnection(
        roomId: String,
        participantId: String,
        sessionId: String,
        connected: Boolean,
    ): WatchTogetherRoomState = currentState

    override suspend fun setAdmission(roomId: String, open: Boolean): WatchTogetherRoomState = currentState

    override suspend fun subscribeToRoom(roomId: String): WatchTogetherHostedRoomSubscription =
        object : WatchTogetherHostedRoomSubscription {
            override val messages: Flow<WatchTogetherServerMessage> = roomMessages
            override suspend fun close() = Unit
        }

    suspend fun emitClosedRoom() {
        currentState = currentState.copy(
            revision = currentState.revision + 1L,
            status = WatchTogetherRoomStatus.Closed,
        )
        roomMessages.emit(snapshot(currentState, messageId = "closed_${responseNumber++}"))
    }

    override suspend fun clearSession() = Unit

    override suspend fun close() = Unit
}

private class FakeHostedPlayer(
    positionMs: Long,
    durationMs: Long,
) : WatchTogetherPlayerAdapter {
    override val capabilities: Set<WatchTogetherPlayerCapability> =
        WatchTogetherPlayerCapability.entries.toSet()
    val commands = mutableListOf<WatchTogetherPlayerCommand>()
    private var snapshot = WatchTogetherPlayerSnapshot(
        localPositionMs = positionMs,
        durationMs = durationMs,
        isPlaying = false,
        capturedAtLocalTimeMs = 0L,
    )

    override suspend fun snapshot(): WatchTogetherPlayerSnapshot = snapshot

    override suspend fun execute(command: WatchTogetherPlayerCommand): WatchTogetherPlayerCommandResult {
        commands += command
        snapshot = when (command) {
            WatchTogetherPlayerCommand.Pause -> snapshot.copy(isPlaying = false)
            WatchTogetherPlayerCommand.Resume -> snapshot.copy(isPlaying = true)
            is WatchTogetherPlayerCommand.SeekTo -> snapshot.copy(localPositionMs = command.localPositionMs)
            is WatchTogetherPlayerCommand.SetTemporaryRate -> snapshot
        }
        return WatchTogetherPlayerCommandResult.Applied
    }
}

private fun roomState(initialPositionMs: Long = 42_000L): WatchTogetherRoomState =
    WatchTogetherRoomState(
        protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
        roomId = roomId,
        revision = 1L,
        status = WatchTogetherRoomStatus.Open,
        createdAtMs = 1_000_000L,
        expiresAtMs = 4_000_000_000_000L,
        capacity = 4,
        hostParticipantId = hostParticipantId,
        admission = WatchTogetherAdmissionState(WatchTogetherAdmissionStatus.Open, 1L),
        participants = listOf(
            WatchTogetherParticipantState(
                participantId = hostParticipantId,
                displayName = "Pilot Host",
                role = WatchTogetherParticipantRole.Host,
                connection = WatchTogetherConnectionState.Connected,
                readiness = WatchTogetherReadinessState(
                    roundId = roundId,
                    sourceReady = false,
                    viewerReady = false,
                    durationMs = null,
                    durationMismatchAcknowledged = false,
                ),
            ),
        ),
        round = WatchTogetherRoundState(
            roundId = roundId,
            generation = 1L,
            status = WatchTogetherRoundStatus.Preparing,
            playback = WatchTogetherPlaybackAnchor(
                mode = WatchTogetherPlaybackMode.Paused,
                anchorPositionMs = initialPositionMs,
                anchorRelayTimeMs = 1_000_000L,
                rate = 1,
            ),
        ),
    )

private fun snapshot(state: WatchTogetherRoomState, messageId: String): WatchTogetherServerMessage =
    WatchTogetherServerMessage(
        protocolVersion = WATCH_TOGETHER_PROTOCOL_VERSION,
        messageId = messageId,
        roomId = state.roomId,
        revision = state.revision,
        relayTimeMs = 1_000_000L,
        type = WatchTogetherServerMessageType.StateSnapshot,
        payload = WatchTogetherJson.encodeToJsonElement(
            WatchTogetherStateSnapshotPayload(state),
        ).jsonObject,
    )

private suspend fun waitUntil(predicate: () -> Boolean) {
    repeat(200) {
        if (predicate()) return
        delay(10L)
    }
    assertTrue(predicate(), "Timed out waiting for hosted-session work")
}

private const val manifestUrl = "https://watch.example.test/manifest.json"
private const val serviceId = "org.example.watch-together"
private const val roomId = "00000000-0000-4000-8000-000000000001"
private const val roundId = "00000000-0000-4000-8000-000000000002"
private const val hostParticipantId = "00000000-0000-4000-8000-000000000003"
private const val guestParticipantId = "00000000-0000-4000-8000-000000000004"
private const val hostSessionId = "00000000-0000-4000-8000-000000000005"
private const val guestSessionId = "00000000-0000-4000-8000-000000000006"
private const val inviteSecret = "0123456789_abcdefghijklmnopqrstuvwxyz-ABCDE"
private val inviteSecretPattern = Regex("^[A-Za-z0-9_-]{43}$")
