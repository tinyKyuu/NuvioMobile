package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingAuthConfigurationStatus
import com.nuvio.app.features.tracking.TrackingAuthProfileSession
import com.nuvio.app.features.tracking.TrackingAuthProfileSessionGuard
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.trackingAuthConfigurationStatus
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SimklAuthRepository : TrackingAuthProvider {
    private const val SUPPORTED_REDIRECT_URI = "com.tinykyuu.nuvio://auth/simkl"
    private val log = Logger.withTag("SimklAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val authorizationMutex = Mutex()

    private val _uiState = MutableStateFlow(SimklAuthUiState())
    val uiState: StateFlow<SimklAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
        ),
    )

    private var hasLoaded = false
    private val profileSessions = TrackingAuthProfileSessionGuard(ProfileRepository.activeProfileId)
    private var storedState = SimklStoredAuthState()
    private var accessToken: String? = null
    private var refreshToken: String? = null

    init {
        TrackingProviderRegistry.register(this)
    }

    override fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (hasLoaded && profileSessions.profileId == profileId) return
        loadFromDisk(profileId)
    }

    override fun onProfileChanged() {
        loadFromDisk(ProfileRepository.activeProfileId)
    }

    override fun clearLocalState() {
        hasLoaded = false
        profileSessions.invalidate()
        storedState = SimklStoredAuthState()
        accessToken = null
        refreshToken = null
        publish()
    }

    override fun removeStoredProfile(profileId: Int) {
        SimklAuthStorage.removeProfile(profileId)
    }

    fun snapshot(): SimklAuthUiState {
        ensureLoaded()
        return uiState.value
    }

    internal fun configurationStatus(): TrackingAuthConfigurationStatus =
        trackingAuthConfigurationStatus(
            requiredValues = listOf(SimklConfig.CLIENT_ID),
            redirectUri = SimklConfig.REDIRECT_URI,
            supportedRedirectUri = SUPPORTED_REDIRECT_URI,
        )

    fun hasRequiredCredentials(): Boolean =
        configurationStatus() == TrackingAuthConfigurationStatus.READY

    fun onConnectRequested(): String? {
        ensureLoaded()
        if (!hasRequiredCredentials()) {
            publish(error = SimklAuthError.MISSING_CLIENT_ID)
            return null
        }

        val material = generateSimklPkceMaterial()
        val session = profileSessions.capture()
        SimklAuthStorage.saveCodeVerifier(session.profileId, material.verifier)
        storedState = storedState.copy(
            pendingAuthorizationState = material.state,
            pendingAuthorizationStartedAtEpochMs = SimklPlatformClock.nowEpochMs(),
        )
        persistMetadata(session.profileId)
        publish(error = null)
        return authorizationUrl(material)
    }

    fun pendingAuthorizationUrl(): String? {
        ensureLoaded()
        val session = profileSessions.capture()
        val state = storedState.pendingAuthorizationState?.takeIf(String::isNotBlank) ?: return null
        val verifier = SimklAuthStorage.loadCodeVerifier(session.profileId)?.takeIf(String::isNotBlank) ?: run {
            clearPendingAuthorization(session.profileId)
            persistMetadata(session.profileId)
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        if (isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorization(session.profileId)
            persistMetadata(session.profileId)
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        return authorizationUrl(
            SimklPkceMaterial(
                verifier = verifier,
                challenge = SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding(),
                state = state,
            ),
        )
    }

    fun onCancelAuthorization() {
        ensureLoaded()
        val profileId = profileSessions.profileId
        clearPendingAuthorization(profileId)
        persistMetadata(profileId)
        publish(error = null)
    }

    override fun handleAuthCallback(url: String): Boolean {
        ensureLoaded()
        val session = profileSessions.capture()
        return when (val callback = parseSimklAuthCallback(url, SimklConfig.REDIRECT_URI)) {
            SimklAuthCallback.NotSimkl -> false
            SimklAuthCallback.Invalid -> {
                clearPendingAuthorization(session.profileId)
                persistMetadata(session.profileId)
                publish(error = SimklAuthError.INVALID_CALLBACK)
                true
            }
            is SimklAuthCallback.AuthorizationCode -> {
                scope.launch { completeAuthorization(callback, session) }
                true
            }
            is SimklAuthCallback.AuthorizationError -> {
                scope.launch { completeAuthorizationError(callback, session) }
                true
            }
        }
    }

    fun onDisconnectRequested() {
        ensureLoaded()
        val tokenToRevoke = refreshToken?.takeIf(String::isNotBlank)
            ?: accessToken?.takeIf(String::isNotBlank)
        clearCredentials(profileSessions.capture(), error = null)
        tokenToRevoke?.let(::revokeGrantAsync)
    }

    internal suspend fun authorizedAccessToken(): String? {
        ensureLoaded()
        val session = profileSessions.capture()
        val token = accessToken?.takeIf(String::isNotBlank) ?: return null
        val expiresAt = storedState.tokenExpiresAtEpochMs
        if (expiresAt != null && SimklPlatformClock.nowEpochMs() >= expiresAt - TOKEN_EXPIRY_SKEW_MS) {
            return authorizationMutex.withLock {
                if (!profileSessions.isCurrent(session)) return@withLock null
                val currentToken = accessToken?.takeIf(String::isNotBlank) ?: return@withLock null
                val currentExpiry = storedState.tokenExpiresAtEpochMs
                if (currentToken != token && currentExpiry?.let(::isAccessTokenUsable) == true) {
                    return@withLock currentToken
                }
                refreshAccessTokenLocked(session)
            }
        }
        return token
    }

    internal suspend fun refreshAccessTokenAfterUnauthorized(rejectedAccessToken: String): String? =
        authorizationMutex.withLock {
            ensureLoaded()
            val session = profileSessions.capture()
            val currentToken = accessToken?.takeIf(String::isNotBlank) ?: return@withLock null
            if (currentToken != rejectedAccessToken) return@withLock currentToken
            refreshAccessTokenLocked(session)
        }

    internal fun onAuthorizationLost() {
        ensureLoaded()
        invalidateCredentials(profileSessions.capture(), SimklAuthError.AUTHORIZATION_REVOKED)
    }

    suspend fun refreshUserSettings(): String? {
        authorizedAccessToken() ?: return null
        val session = profileSessions.capture()
        return if (fetchAndStoreUserSettings(session)) storedState.username else null
    }

    internal suspend fun synchronizeUserSettings(activityWatermark: String?) {
        authorizedAccessToken() ?: return
        val session = profileSessions.capture()
        if (!profileSessions.isCurrent(session)) return
        when (simklSettingsRefreshAction(storedState, activityWatermark)) {
            SimklSettingsRefreshAction.NONE -> Unit
            SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                storedState = storedState.copy(settingsActivityWatermark = activityWatermark)
                persistMetadata(session.profileId)
            }
            SimklSettingsRefreshAction.FETCH -> {
                fetchAndStoreUserSettings(session, activityWatermark)
            }
        }
    }

    private suspend fun completeAuthorization(
        callback: SimklAuthCallback.AuthorizationCode,
        session: TrackingAuthProfileSession,
    ) =
        authorizationMutex.withLock {
            if (!profileSessions.isCurrent(session)) return@withLock
            publish(isLoading = true, error = null)
            val expectedState = storedState.pendingAuthorizationState
            val verifier = SimklAuthStorage.loadCodeVerifier(session.profileId)
            val isExpired = isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (expectedState.isNullOrBlank() || verifier.isNullOrBlank() || isExpired) {
                clearPendingAuthorization(session.profileId)
                persistMetadata(session.profileId)
                publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
                return@withLock
            }
            if (!constantTimeEquals(callback.state, expectedState)) {
                clearPendingAuthorization(session.profileId)
                persistMetadata(session.profileId)
                publish(isLoading = false, error = SimklAuthError.INVALID_CALLBACK_STATE)
                return@withLock
            }
            if (callback.issuer != SIMKL_ISSUER) {
                clearPendingAuthorization(session.profileId)
                persistMetadata(session.profileId)
                publish(isLoading = false, error = SimklAuthError.INVALID_CALLBACK_ISSUER)
                return@withLock
            }

            val token = try {
                SimklOAuthApi.client.exchangeAuthorizationCode(
                    clientId = SimklConfig.CLIENT_ID,
                    code = callback.code,
                    redirectUri = SimklConfig.REDIRECT_URI,
                    codeVerifier = verifier,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentAuthorization(session, expectedState, verifier)) return@withLock
                log.w { "Simkl token exchange failed: ${error.message}" }
                clearPendingAuthorization(session.profileId)
                persistMetadata(session.profileId)
                publish(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
                return@withLock
            }
            if (!isCurrentAuthorization(session, expectedState, verifier)) {
                (token.refreshToken ?: token.accessToken)
                    .takeIf(String::isNotBlank)
                    ?.let(::revokeGrantAsync)
                return@withLock
            }
            if (!isValidSimklOAuthToken(token)) {
                (token.refreshToken ?: token.accessToken)
                    .takeIf(String::isNotBlank)
                    ?.let(::revokeGrantAsync)
                clearPendingAuthorization(session.profileId)
                persistMetadata(session.profileId)
                publish(
                    isLoading = false,
                    error = if (hasRequiredSimklScope(token.scope)) {
                        SimklAuthError.INVALID_TOKEN_RESPONSE
                    } else {
                        SimklAuthError.INSUFFICIENT_SCOPE
                    },
                )
                return@withLock
            }

            clearPendingAuthorization(session.profileId)
            storeToken(session, token)
            publish(isLoading = false, error = null)
            fetchAndStoreUserSettings(session)
            if (!profileSessions.isCurrent(session)) return@withLock
            SimklSyncRepository.refreshAsync(
                intent = TrackingRefreshIntent.INVALIDATED,
                origin = SimklRefreshOrigin.AUTHORIZATION,
            )
        }

    private suspend fun completeAuthorizationError(
        callback: SimklAuthCallback.AuthorizationError,
        session: TrackingAuthProfileSession,
    ) =
        authorizationMutex.withLock {
            if (!profileSessions.isCurrent(session)) return@withLock
            val expectedState = storedState.pendingAuthorizationState
            val isExpired = isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            val callbackError = when {
                expectedState.isNullOrBlank() || isExpired -> SimklAuthError.AUTHORIZATION_EXPIRED
                !constantTimeEquals(callback.state, expectedState) -> SimklAuthError.INVALID_CALLBACK_STATE
                callback.issuer != SIMKL_ISSUER -> SimklAuthError.INVALID_CALLBACK_ISSUER
                else -> SimklAuthError.AUTHORIZATION_DENIED
            }
            clearPendingAuthorization(session.profileId)
            persistMetadata(session.profileId)
            publish(isLoading = false, error = callbackError)
        }

    private suspend fun fetchAndStoreUserSettings(
        session: TrackingAuthProfileSession,
        activityWatermark: String? = null,
    ): Boolean {
        if (!profileSessions.isCurrent(session)) return false
        val expectedRefreshToken = refreshToken?.takeIf(String::isNotBlank) ?: return false
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.GET,
                    path = "/users/settings",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to fetch Simkl user settings: ${error.message}" }
            return false
        }
        if (!profileSessions.isCurrent(session) || refreshToken != expectedRefreshToken) return false
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }
            .getOrNull() ?: return false
        if (!profileSessions.isCurrent(session) || refreshToken != expectedRefreshToken) return false
        storedState = storedState.copy(
            username = settings.user?.name,
            accountId = settings.account?.id,
            hasFetchedUserSettings = true,
            settingsActivityWatermark = activityWatermark ?: storedState.settingsActivityWatermark,
        )
        persistMetadata(session.profileId)
        publish(error = null)
        return true
    }

    private fun loadFromDisk(profileId: Int) {
        profileSessions.moveTo(profileId)
        hasLoaded = true
        storedState = SimklAuthStorage.loadMetadataPayload(profileId)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { json.decodeFromString<SimklStoredAuthState>(payload) }
                    .onFailure { error -> log.w { "Failed to parse Simkl auth metadata: ${error.message}" } }
                    .getOrNull()
            }
            ?: SimklStoredAuthState()
        accessToken = SimklAuthStorage.loadAccessToken(profileId)?.takeIf(String::isNotBlank)
        refreshToken = SimklAuthStorage.loadRefreshToken(profileId)?.takeIf(String::isNotBlank)
        val now = SimklPlatformClock.nowEpochMs()
        val hasIncompleteV2Credentials = (accessToken != null || refreshToken != null) && (
            accessToken == null ||
                refreshToken == null ||
                storedState.tokenExpiresAtEpochMs == null ||
                storedState.refreshTokenExpiresAtEpochMs == null ||
                !hasRequiredSimklScope(storedState.grantedScope)
            )
        val refreshExpired = storedState.refreshTokenExpiresAtEpochMs?.let { it <= now } == true
        if (hasIncompleteV2Credentials || refreshExpired) {
            clearCredentials(profileSessions.capture(), error = null)
            return
        }
        if (storedState.hasPendingAuthorization && isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorization(profileId)
            persistMetadata(profileId)
        }
        publish(error = null)
    }

    private fun invalidateCredentials(session: TrackingAuthProfileSession, error: SimklAuthError) {
        if (!profileSessions.isCurrent(session)) return
        clearCredentials(session, error)
    }

    private fun clearCredentials(session: TrackingAuthProfileSession, error: SimklAuthError?) {
        if (!profileSessions.isCurrent(session)) return
        accessToken = null
        refreshToken = null
        SimklAuthStorage.saveAccessToken(session.profileId, null)
        SimklAuthStorage.saveRefreshToken(session.profileId, null)
        clearPendingAuthorization(session.profileId)
        storedState = SimklStoredAuthState()
        persistMetadata(session.profileId)
        SimklSyncRepository.clearLocalState()
        publish(isLoading = false, error = error)
    }

    private suspend fun refreshAccessTokenLocked(session: TrackingAuthProfileSession): String? {
        if (!profileSessions.isCurrent(session)) return null
        val currentRefreshToken = refreshToken?.takeIf(String::isNotBlank) ?: run {
            invalidateCredentials(session, SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        val refreshExpiresAt = storedState.refreshTokenExpiresAtEpochMs
        if (refreshExpiresAt == null || SimklPlatformClock.nowEpochMs() >= refreshExpiresAt) {
            invalidateCredentials(session, SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }

        val token = try {
            SimklOAuthApi.client.refreshAccessToken(
                clientId = SimklConfig.CLIENT_ID,
                refreshToken = currentRefreshToken,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimklApiException) {
            if (error.status == 400 || error.status == 401) {
                if (profileSessions.isCurrent(session) && refreshToken == currentRefreshToken) {
                    invalidateCredentials(session, SimklAuthError.AUTHORIZATION_REVOKED)
                }
                return if (profileSessions.isCurrent(session)) accessToken else null
            }
            throw error
        }
        if (!profileSessions.isCurrent(session) || refreshToken != currentRefreshToken) {
            (token.refreshToken ?: token.accessToken)
                .takeIf(String::isNotBlank)
                ?.let(::revokeGrantAsync)
            return null
        }
        if (!isValidSimklOAuthToken(token, existingRefreshToken = currentRefreshToken)) {
            invalidateCredentials(
                session,
                if (hasRequiredSimklScope(token.scope)) {
                    SimklAuthError.INVALID_TOKEN_RESPONSE
                } else {
                    SimklAuthError.INSUFFICIENT_SCOPE
                },
            )
            return null
        }
        storeToken(session, token, existingRefreshToken = currentRefreshToken)
        publish(isLoading = false, error = null)
        return accessToken
    }

    private fun storeToken(
        session: TrackingAuthProfileSession,
        token: SimklOAuthToken,
        existingRefreshToken: String? = null,
    ) {
        check(profileSessions.isCurrent(session)) { "Cannot store Simkl credentials for a stale profile session" }
        val now = SimklPlatformClock.nowEpochMs()
        val nextRefreshToken = token.refreshToken?.takeIf(String::isNotBlank)
            ?: existingRefreshToken?.takeIf(String::isNotBlank)
            ?: error("Simkl OAuth response did not include a refresh token")
        accessToken = token.accessToken
        refreshToken = nextRefreshToken
        SimklAuthStorage.saveAccessToken(session.profileId, token.accessToken)
        SimklAuthStorage.saveRefreshToken(session.profileId, nextRefreshToken)
        storedState = storedState.copy(
            tokenExpiresAtEpochMs = now + token.expiresInSeconds.orZero() * 1_000L,
            refreshTokenExpiresAtEpochMs = now + SIMKL_REFRESH_TOKEN_LIFETIME_MS,
            grantedScope = token.scope,
        )
        persistMetadata(session.profileId)
    }

    private fun isAccessTokenUsable(expiresAtEpochMs: Long): Boolean =
        SimklPlatformClock.nowEpochMs() < expiresAtEpochMs - TOKEN_EXPIRY_SKEW_MS

    private fun isCurrentAuthorization(
        session: TrackingAuthProfileSession,
        expectedState: String,
        verifier: String,
    ): Boolean = profileSessions.isCurrent(session) &&
        storedState.pendingAuthorizationState == expectedState &&
        SimklAuthStorage.loadCodeVerifier(session.profileId) == verifier

    private fun revokeGrantAsync(token: String) {
        if (!hasRequiredCredentials()) return
        scope.launch {
            runCatching {
                SimklOAuthApi.client.revoke(
                    clientId = SimklConfig.CLIENT_ID,
                    token = token,
                )
            }.onFailure { error ->
                log.w { "Simkl grant revocation request failed: ${error.message}" }
            }
        }
    }

    private fun clearPendingAuthorization(profileId: Int) {
        SimklAuthStorage.saveCodeVerifier(profileId, null)
        storedState = storedState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = null,
        )
    }

    private fun persistMetadata(profileId: Int) {
        SimklAuthStorage.saveMetadataPayload(profileId, json.encodeToString(storedState))
    }

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        error: SimklAuthError? = _uiState.value.error,
    ) {
        val authenticated = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
        _isAuthenticated.value = authenticated
        _uiState.value = SimklAuthUiState(
            mode = when {
                authenticated -> SimklConnectionMode.CONNECTED
                storedState.hasPendingAuthorization -> SimklConnectionMode.AWAITING_APPROVAL
                else -> SimklConnectionMode.DISCONNECTED
            },
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = storedState.username,
            accountId = storedState.accountId,
            tokenExpiresAtEpochMs = storedState.tokenExpiresAtEpochMs,
            pendingAuthorizationStartedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
            error = error,
        )
    }

    private fun authorizationUrl(material: SimklPkceMaterial): String =
        buildSimklAuthorizationUrl(
            clientId = SimklConfig.CLIENT_ID,
            redirectUri = SimklConfig.REDIRECT_URI,
            material = material,
        )

    private fun Long?.orZero(): Long = this ?: 0L

    private const val TOKEN_EXPIRY_SKEW_MS = 24L * 60L * 60L * 1_000L
}

@Serializable
private data class SimklUserSettingsResponse(
    val user: SimklUser? = null,
    val account: SimklAccount? = null,
)

@Serializable
private data class SimklUser(
    val name: String? = null,
)

@Serializable
private data class SimklAccount(
    val id: Long? = null,
)
