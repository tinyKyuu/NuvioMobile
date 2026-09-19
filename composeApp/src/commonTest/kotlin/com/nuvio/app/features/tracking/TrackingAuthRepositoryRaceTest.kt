package com.nuvio.app.features.tracking

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.simkl.SIMKL_ISSUER
import com.nuvio.app.features.simkl.SIMKL_REFRESH_TOKEN_LIFETIME_MS
import com.nuvio.app.features.simkl.SIMKL_REQUIRED_SCOPE
import com.nuvio.app.features.simkl.SimklApiResponse
import com.nuvio.app.features.simkl.SimklAuthCallback
import com.nuvio.app.features.simkl.SimklAuthCommitPoint
import com.nuvio.app.features.simkl.SimklAuthNetwork
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklAuthRuntime
import com.nuvio.app.features.simkl.SimklAuthStore
import com.nuvio.app.features.simkl.SimklConnectionMode
import com.nuvio.app.features.simkl.SimklOAuthToken
import com.nuvio.app.features.simkl.SimklPlatformClock
import com.nuvio.app.features.simkl.SimklStoredAuthState
import com.nuvio.app.features.trakt.TraktAuthCallback
import com.nuvio.app.features.trakt.TraktAuthCommitPoint
import com.nuvio.app.features.trakt.TraktAuthNetwork
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktAuthRuntime
import com.nuvio.app.features.trakt.TraktAuthState
import com.nuvio.app.features.trakt.TraktAuthStore
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackingAuthRepositoryRaceTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `Simkl exchange rejected after validation cannot cross profiles`() = runBlocking {
        var activeProfileId = 1
        val storage = FakeSimklAuthStore().apply {
            seedPending(profileId = 1, state = "profile-one-state", verifier = "profile-one-verifier")
            seedConnected(profileId = 2, accessToken = "profile-two-access", refreshToken = "profile-two-refresh")
        }
        val profileOneMetadata = storage.metadata.getValue(1)
        val profileTwoMetadata = storage.metadata.getValue(2)
        val network = FakeSimklAuthNetwork(exchangeToken = simklToken("late-access", "late-refresh"))
        val commitReached = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val previous = SimklAuthRepository.installRuntimeForTesting(
            SimklAuthRuntime(
                activeProfileId = { activeProfileId },
                storage = storage,
                network = network,
                credentialsConfigured = { true },
                beforeCommit = { point ->
                    if (point == SimklAuthCommitPoint.EXCHANGE) {
                        commitReached.complete(Unit)
                        releaseCommit.await()
                    }
                },
            ),
        )

        try {
            SimklAuthRepository.onProfileChanged()
            val exchange = launch {
                SimklAuthRepository.completeAuthorizationForTesting(
                    SimklAuthCallback.AuthorizationCode(
                        code = "one-time-code",
                        state = "profile-one-state",
                        issuer = SIMKL_ISSUER,
                    ),
                )
            }

            withTimeout(5_000) { commitReached.await() }
            activeProfileId = 2
            SimklAuthRepository.onProfileChanged()
            releaseCommit.complete(Unit)
            exchange.join()

            assertNull(storage.accessTokens[1])
            assertNull(storage.refreshTokens[1])
            assertEquals("profile-one-verifier", storage.codeVerifiers[1])
            assertEquals("profile-one-state", storage.state(1).pendingAuthorizationState)
            assertEquals(profileOneMetadata, storage.metadata[1])
            assertEquals("profile-two-access", storage.accessTokens[2])
            assertEquals("profile-two-refresh", storage.refreshTokens[2])
            assertEquals(profileTwoMetadata, storage.metadata[2])
            assertEquals(listOf("late-refresh"), network.revokedTokens)
            val snapshot = SimklAuthRepository.repositorySnapshotForTesting()
            assertEquals(2, snapshot.loadedProfileId)
            assertEquals("profile-two-access", snapshot.accessToken)
            assertEquals(SimklConnectionMode.CONNECTED, snapshot.uiState.mode)
        } finally {
            SimklAuthRepository.restoreRuntimeAfterTesting(previous)
        }
    }

    @Test
    fun `Simkl refresh rejected after validation cannot cross profiles`() = runBlocking {
        var activeProfileId = 1
        val storage = FakeSimklAuthStore().apply {
            seedConnected(profileId = 1, accessToken = "profile-one-access", refreshToken = "profile-one-refresh")
            seedConnected(profileId = 2, accessToken = "profile-two-access", refreshToken = "profile-two-refresh")
        }
        val profileOneMetadata = storage.metadata.getValue(1)
        val profileTwoMetadata = storage.metadata.getValue(2)
        val network = FakeSimklAuthNetwork(refreshToken = simklToken("late-access", "late-refresh"))
        val commitReached = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val previous = SimklAuthRepository.installRuntimeForTesting(
            SimklAuthRuntime(
                activeProfileId = { activeProfileId },
                storage = storage,
                network = network,
                credentialsConfigured = { true },
                beforeCommit = { point ->
                    if (point == SimklAuthCommitPoint.REFRESH) {
                        commitReached.complete(Unit)
                        releaseCommit.await()
                    }
                },
            ),
        )

        try {
            SimklAuthRepository.onProfileChanged()
            val refresh = launch { SimklAuthRepository.refreshAccessTokenForTesting() }

            withTimeout(5_000) { commitReached.await() }
            activeProfileId = 2
            SimklAuthRepository.onProfileChanged()
            releaseCommit.complete(Unit)
            refresh.join()

            assertEquals("profile-one-access", storage.accessTokens[1])
            assertEquals("profile-one-refresh", storage.refreshTokens[1])
            assertEquals(profileOneMetadata, storage.metadata[1])
            assertEquals("profile-two-access", storage.accessTokens[2])
            assertEquals("profile-two-refresh", storage.refreshTokens[2])
            assertEquals(profileTwoMetadata, storage.metadata[2])
            assertNull(storage.state(1).pendingAuthorizationState)
            assertNull(storage.state(2).pendingAuthorizationState)
            assertEquals(listOf("late-refresh"), network.revokedTokens)
            val snapshot = SimklAuthRepository.repositorySnapshotForTesting()
            assertEquals(2, snapshot.loadedProfileId)
            assertEquals("profile-two-access", snapshot.accessToken)
            assertEquals(SimklConnectionMode.CONNECTED, snapshot.uiState.mode)
        } finally {
            SimklAuthRepository.restoreRuntimeAfterTesting(previous)
        }
    }

    @Test
    fun `Simkl same-profile exchange and refresh commit credentials metadata and UI`() = runBlocking {
        var activeProfileId = 1
        val storage = FakeSimklAuthStore().apply {
            seedPending(profileId = 1, state = "same-profile-state", verifier = "same-profile-verifier")
        }
        val network = FakeSimklAuthNetwork(
            exchangeToken = simklToken("exchange-access", "exchange-refresh"),
            refreshToken = simklToken("refreshed-access", "refreshed-refresh"),
        )
        val previous = SimklAuthRepository.installRuntimeForTesting(
            SimklAuthRuntime(
                activeProfileId = { activeProfileId },
                storage = storage,
                network = network,
                credentialsConfigured = { true },
            ),
        )

        try {
            SimklAuthRepository.onProfileChanged()
            SimklAuthRepository.completeAuthorizationForTesting(
                SimklAuthCallback.AuthorizationCode(
                    code = "one-time-code",
                    state = "same-profile-state",
                    issuer = SIMKL_ISSUER,
                ),
            )

            assertEquals("exchange-access", storage.accessTokens[1])
            assertEquals("exchange-refresh", storage.refreshTokens[1])
            assertNull(storage.codeVerifiers[1])
            assertNull(storage.state(1).pendingAuthorizationState)
            assertEquals(SimklConnectionMode.CONNECTED, SimklAuthRepository.repositorySnapshotForTesting().uiState.mode)

            assertEquals("refreshed-access", SimklAuthRepository.refreshAccessTokenForTesting())
            assertEquals("refreshed-access", storage.accessTokens[1])
            assertEquals("refreshed-refresh", storage.refreshTokens[1])
            assertEquals("Simkl tester", storage.state(1).username)
            assertTrue(network.revokedTokens.isEmpty())
            assertEquals(1, activeProfileId)
        } finally {
            SimklAuthRepository.restoreRuntimeAfterTesting(previous)
        }
    }

    @Test
    fun `Trakt exchange rejected after validation cannot cross profiles`() = runBlocking {
        var activeProfileId = 1
        val storage = FakeTraktAuthStore().apply {
            seedPending(profileId = 1, state = "profile-one-state")
            seedConnected(profileId = 2, accessToken = "profile-two-access", refreshToken = "profile-two-refresh")
        }
        val profileOnePayload = storage.payloads.getValue(1)
        val profileTwoPayload = storage.payloads.getValue(2)
        val network = FakeTraktAuthNetwork(exchangeBody = traktTokenBody("late-access", "late-refresh"))
        val commitReached = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val previous = TraktAuthRepository.installRuntimeForTesting(
            TraktAuthRuntime(
                activeProfileId = { activeProfileId },
                storage = storage,
                network = network,
                credentialsConfigured = { true },
                localize = { "test" },
                beforeCommit = { point ->
                    if (point == TraktAuthCommitPoint.EXCHANGE) {
                        commitReached.complete(Unit)
                        releaseCommit.await()
                    }
                },
            ),
        )

        try {
            TraktAuthRepository.onProfileChanged()
            val exchange = launch {
                TraktAuthRepository.completeAuthorizationForTesting(
                    TraktAuthCallback.AuthorizationCode("one-time-code", "profile-one-state"),
                )
            }

            withTimeout(5_000) { commitReached.await() }
            activeProfileId = 2
            TraktAuthRepository.onProfileChanged()
            releaseCommit.complete(Unit)
            exchange.join()

            assertEquals("profile-one-state", storage.state(1).pendingAuthorizationState)
            assertNull(storage.state(1).accessToken)
            assertEquals(profileOnePayload, storage.payloads[1])
            assertEquals("profile-two-access", storage.state(2).accessToken)
            assertEquals("profile-two-refresh", storage.state(2).refreshToken)
            assertEquals(profileTwoPayload, storage.payloads[2])
            assertEquals(listOf("late-access"), network.revokedTokens)
            val snapshot = TraktAuthRepository.repositorySnapshotForTesting()
            assertEquals(2, snapshot.loadedProfileId)
            assertEquals("profile-two-access", snapshot.authState.accessToken)
            assertEquals(TraktConnectionMode.CONNECTED, snapshot.uiState.mode)
        } finally {
            TraktAuthRepository.restoreRuntimeAfterTesting(previous)
        }
    }

    @Test
    fun `Trakt refresh rejected after validation cannot cross profiles`() = runBlocking {
        var activeProfileId = 1
        val storage = FakeTraktAuthStore().apply {
            seedConnected(profileId = 1, accessToken = "profile-one-access", refreshToken = "profile-one-refresh")
            seedConnected(profileId = 2, accessToken = "profile-two-access", refreshToken = "profile-two-refresh")
        }
        val profileOnePayload = storage.payloads.getValue(1)
        val profileTwoPayload = storage.payloads.getValue(2)
        val network = FakeTraktAuthNetwork(refreshBody = traktTokenBody("late-access", "late-refresh"))
        val commitReached = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val previous = TraktAuthRepository.installRuntimeForTesting(
            TraktAuthRuntime(
                activeProfileId = { activeProfileId },
                storage = storage,
                network = network,
                credentialsConfigured = { true },
                localize = { "test" },
                beforeCommit = { point ->
                    if (point == TraktAuthCommitPoint.REFRESH) {
                        commitReached.complete(Unit)
                        releaseCommit.await()
                    }
                },
            ),
        )

        try {
            TraktAuthRepository.onProfileChanged()
            val refresh = launch { TraktAuthRepository.refreshAccessTokenForTesting() }

            withTimeout(5_000) { commitReached.await() }
            activeProfileId = 2
            TraktAuthRepository.onProfileChanged()
            releaseCommit.complete(Unit)
            refresh.join()

            assertEquals("profile-one-access", storage.state(1).accessToken)
            assertEquals("profile-one-refresh", storage.state(1).refreshToken)
            assertEquals(profileOnePayload, storage.payloads[1])
            assertEquals("profile-two-access", storage.state(2).accessToken)
            assertEquals("profile-two-refresh", storage.state(2).refreshToken)
            assertEquals(profileTwoPayload, storage.payloads[2])
            assertNull(storage.state(1).pendingAuthorizationState)
            assertNull(storage.state(2).pendingAuthorizationState)
            assertEquals(listOf("late-access"), network.revokedTokens)
            val snapshot = TraktAuthRepository.repositorySnapshotForTesting()
            assertEquals(2, snapshot.loadedProfileId)
            assertEquals("profile-two-access", snapshot.authState.accessToken)
            assertEquals(TraktConnectionMode.CONNECTED, snapshot.uiState.mode)
        } finally {
            TraktAuthRepository.restoreRuntimeAfterTesting(previous)
        }
    }

    @Test
    fun `Trakt same-profile exchange and refresh commit credentials metadata and UI`() = runBlocking {
        var activeProfileId = 1
        val storage = FakeTraktAuthStore().apply {
            seedPending(profileId = 1, state = "same-profile-state")
        }
        val network = FakeTraktAuthNetwork(
            exchangeBody = traktTokenBody("exchange-access", "exchange-refresh"),
            refreshBody = traktTokenBody("refreshed-access", "refreshed-refresh"),
        )
        val previous = TraktAuthRepository.installRuntimeForTesting(
            TraktAuthRuntime(
                activeProfileId = { activeProfileId },
                storage = storage,
                network = network,
                credentialsConfigured = { true },
                localize = { "test" },
            ),
        )

        try {
            TraktAuthRepository.onProfileChanged()
            TraktAuthRepository.completeAuthorizationForTesting(
                TraktAuthCallback.AuthorizationCode("one-time-code", "same-profile-state"),
            )

            assertEquals("exchange-access", storage.state(1).accessToken)
            assertEquals("exchange-refresh", storage.state(1).refreshToken)
            assertNull(storage.state(1).pendingAuthorizationState)
            assertEquals("Trakt tester", storage.state(1).username)
            assertEquals(TraktConnectionMode.CONNECTED, TraktAuthRepository.repositorySnapshotForTesting().uiState.mode)

            assertTrue(TraktAuthRepository.refreshAccessTokenForTesting())
            assertEquals("refreshed-access", storage.state(1).accessToken)
            assertEquals("refreshed-refresh", storage.state(1).refreshToken)
            assertTrue(network.revokedTokens.isEmpty())
            assertEquals(1, activeProfileId)
        } finally {
            TraktAuthRepository.restoreRuntimeAfterTesting(previous)
        }
    }

    private fun simklToken(accessToken: String, refreshToken: String) = SimklOAuthToken(
        accessToken = accessToken,
        refreshToken = refreshToken,
        tokenType = "Bearer",
        expiresInSeconds = 604_800,
        scope = SIMKL_REQUIRED_SCOPE,
    )

    private fun traktTokenBody(accessToken: String, refreshToken: String): String = """
        {
          "access_token":"$accessToken",
          "refresh_token":"$refreshToken",
          "token_type":"bearer",
          "expires_in":7776000,
          "created_at":1900000000
        }
    """.trimIndent()

    private inner class FakeSimklAuthStore : SimklAuthStore {
        val metadata = mutableMapOf<Int, String>()
        val accessTokens = mutableMapOf<Int, String>()
        val refreshTokens = mutableMapOf<Int, String>()
        val codeVerifiers = mutableMapOf<Int, String>()

        override fun loadMetadataPayload(profileId: Int): String? = metadata[profileId]
        override fun saveMetadataPayload(profileId: Int, payload: String) {
            metadata[profileId] = payload
        }
        override fun loadAccessToken(profileId: Int): String? = accessTokens[profileId]
        override fun saveAccessToken(profileId: Int, value: String?) = putOrRemove(accessTokens, profileId, value)
        override fun loadRefreshToken(profileId: Int): String? = refreshTokens[profileId]
        override fun saveRefreshToken(profileId: Int, value: String?) = putOrRemove(refreshTokens, profileId, value)
        override fun loadCodeVerifier(profileId: Int): String? = codeVerifiers[profileId]
        override fun saveCodeVerifier(profileId: Int, value: String?) = putOrRemove(codeVerifiers, profileId, value)
        override fun removeProfile(profileId: Int) {
            metadata.remove(profileId)
            accessTokens.remove(profileId)
            refreshTokens.remove(profileId)
            codeVerifiers.remove(profileId)
        }

        fun seedPending(profileId: Int, state: String, verifier: String) {
            metadata[profileId] = json.encodeToString(
                SimklStoredAuthState(
                    pendingAuthorizationState = state,
                    pendingAuthorizationStartedAtEpochMs = SimklPlatformClock.nowEpochMs(),
                ),
            )
            codeVerifiers[profileId] = verifier
        }

        fun seedConnected(profileId: Int, accessToken: String, refreshToken: String) {
            val now = SimklPlatformClock.nowEpochMs()
            metadata[profileId] = json.encodeToString(
                SimklStoredAuthState(
                    tokenExpiresAtEpochMs = now + 604_800_000L,
                    refreshTokenExpiresAtEpochMs = now + SIMKL_REFRESH_TOKEN_LIFETIME_MS,
                    grantedScope = SIMKL_REQUIRED_SCOPE,
                ),
            )
            accessTokens[profileId] = accessToken
            refreshTokens[profileId] = refreshToken
        }

        fun state(profileId: Int): SimklStoredAuthState =
            json.decodeFromString(metadata.getValue(profileId))
    }

    private class FakeSimklAuthNetwork(
        private val exchangeToken: SimklOAuthToken = defaultSimklToken("exchange-access", "exchange-refresh"),
        private val refreshToken: SimklOAuthToken = defaultSimklToken("refresh-access", "refresh-refresh"),
    ) : SimklAuthNetwork {
        val revokedTokens = mutableListOf<String>()

        override suspend fun exchangeAuthorizationCode(
            clientId: String,
            code: String,
            redirectUri: String,
            codeVerifier: String,
        ): SimklOAuthToken = exchangeToken

        override suspend fun refreshAccessToken(clientId: String, refreshToken: String): SimklOAuthToken =
            this.refreshToken

        override suspend fun fetchUserSettings(): SimklApiResponse = SimklApiResponse(
            status = 200,
            body = """{"user":{"name":"Simkl tester"},"account":{"id":42}}""",
            headers = emptyMap(),
        )

        override suspend fun revoke(clientId: String, token: String) {
            revokedTokens += token
        }

        companion object {
            private fun defaultSimklToken(accessToken: String, refreshToken: String) = SimklOAuthToken(
                accessToken = accessToken,
                refreshToken = refreshToken,
                tokenType = "Bearer",
                expiresInSeconds = 604_800,
                scope = SIMKL_REQUIRED_SCOPE,
            )
        }
    }

    private inner class FakeTraktAuthStore : TraktAuthStore {
        val payloads = mutableMapOf<Int, String>()

        override fun loadPayload(profileId: Int): String? = payloads[profileId]
        override fun savePayload(profileId: Int, payload: String) {
            payloads[profileId] = payload
        }
        override fun removeProfile(profileId: Int) {
            payloads.remove(profileId)
        }

        fun seedPending(profileId: Int, state: String) {
            payloads[profileId] = json.encodeToString(
                TraktAuthState(
                    pendingAuthorizationState = state,
                    pendingAuthorizationStartedAtMillis = TraktPlatformClock.nowEpochMs(),
                ),
            )
        }

        fun seedConnected(profileId: Int, accessToken: String, refreshToken: String) {
            payloads[profileId] = json.encodeToString(
                TraktAuthState(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    tokenType = "bearer",
                    createdAt = 1_900_000_000L,
                    expiresIn = 7_776_000,
                ),
            )
        }

        fun state(profileId: Int): TraktAuthState = json.decodeFromString(payloads.getValue(profileId))
    }

    private class FakeTraktAuthNetwork(
        private val exchangeBody: String = "",
        private val refreshBody: String = "",
    ) : TraktAuthNetwork {
        val revokedTokens = mutableListOf<String>()

        override suspend fun exchangeAuthorizationCode(body: String): String = exchangeBody

        override suspend fun refreshAccessToken(body: String): RawHttpResponse = RawHttpResponse(
            status = 200,
            statusText = "OK",
            url = "https://example.invalid/oauth/token",
            body = refreshBody,
            headers = emptyMap(),
        )

        override suspend fun fetchUserSettings(headers: Map<String, String>): String =
            """{"user":{"username":"Trakt tester","ids":{"slug":"trakt-tester"}}}"""

        override suspend fun revoke(body: String) {
            val token = Regex(""""token"\s*:\s*"([^"]+)"""")
                .find(body)
                ?.groupValues
                ?.get(1)
            if (token != null) revokedTokens += token
        }
    }

    private fun putOrRemove(target: MutableMap<Int, String>, profileId: Int, value: String?) {
        if (value == null) target.remove(profileId) else target[profileId] = value
    }
}
