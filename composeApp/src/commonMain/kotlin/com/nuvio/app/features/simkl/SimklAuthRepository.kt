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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
    private val stateLock = SynchronizedObject()
    private var runtime = SimklAuthRuntime(
        activeProfileId = { ProfileRepository.activeProfileId },
        storage = PlatformSimklAuthStore,
        network = PlatformSimklAuthNetwork,
        credentialsConfigured = {
            configurationStatus() == TrackingAuthConfigurationStatus.READY
        },
        onAuthorizationCommitted = {
            SimklSyncRepository.refreshAsync(
                intent = TrackingRefreshIntent.INVALIDATED,
                origin = SimklRefreshOrigin.AUTHORIZATION,
            )
        },
    )

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
    private val profileSessions = TrackingAuthProfileSessionGuard(runtime.activeProfileId())
    private var storedState = SimklStoredAuthState()
    private var accessToken: String? = null
    private var refreshToken: String? = null

    init {
        TrackingProviderRegistry.register(this)
    }

    override fun ensureLoaded() {
        synchronized(stateLock) {
            val profileId = runtime.activeProfileId()
            if (hasLoaded && profileSessions.profileId == profileId) return@synchronized
            loadFromDiskLocked(profileId)
        }
    }

    override fun onProfileChanged() {
        synchronized(stateLock) {
            loadFromDiskLocked(runtime.activeProfileId())
        }
    }

    override fun clearLocalState() {
        synchronized(stateLock) {
            hasLoaded = false
            profileSessions.invalidate()
            storedState = SimklStoredAuthState()
            accessToken = null
            refreshToken = null
            publishLocked()
        }
    }

    override fun removeStoredProfile(profileId: Int) {
        runtime.storage.removeProfile(profileId)
    }

    fun snapshot(): SimklAuthUiState {
        ensureLoaded()
        return synchronized(stateLock) { uiState.value }
    }

    internal fun installRuntimeForTesting(nextRuntime: SimklAuthRuntime): SimklAuthRuntime =
        synchronized(stateLock) {
            val previous = runtime
            runtime = nextRuntime
            resetInMemoryStateLocked(nextRuntime.activeProfileId())
            previous
        }

    internal fun restoreRuntimeAfterTesting(previous: SimklAuthRuntime) {
        synchronized(stateLock) {
            runtime = previous
            resetInMemoryStateLocked(previous.activeProfileId())
        }
    }

    internal fun repositorySnapshotForTesting(): SimklAuthRepositorySnapshot = synchronized(stateLock) {
        SimklAuthRepositorySnapshot(
            loadedProfileId = profileSessions.profileId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            storedState = storedState,
            uiState = _uiState.value,
        )
    }

    internal suspend fun completeAuthorizationForTesting(callback: SimklAuthCallback.AuthorizationCode) {
        ensureLoaded()
        val session = synchronized(stateLock) { profileSessions.capture() }
        completeAuthorization(callback, session)
    }

    internal suspend fun refreshAccessTokenForTesting(): String? = authorizationMutex.withLock {
        ensureLoaded()
        val session = synchronized(stateLock) { profileSessions.capture() }
        refreshAccessToken(session)
    }

    internal fun configurationStatus(): TrackingAuthConfigurationStatus =
        trackingAuthConfigurationStatus(
            requiredValues = listOf(SimklConfig.CLIENT_ID),
            redirectUri = SimklConfig.REDIRECT_URI,
            supportedRedirectUri = SUPPORTED_REDIRECT_URI,
        )

    fun hasRequiredCredentials(): Boolean =
        runtime.credentialsConfigured()

    fun onConnectRequested(): String? {
        ensureLoaded()
        if (!hasRequiredCredentials()) {
            synchronized(stateLock) { publishLocked(error = SimklAuthError.MISSING_CLIENT_ID) }
            return null
        }

        val material = generateSimklPkceMaterial()
        synchronized(stateLock) {
            val session = profileSessions.capture()
            if (!isActiveSessionLocked(session)) return@synchronized
            runtime.storage.saveCodeVerifier(session.profileId, material.verifier)
            storedState = storedState.copy(
                pendingAuthorizationState = material.state,
                pendingAuthorizationStartedAtEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            persistMetadataLocked(session.profileId)
            publishLocked(error = null)
        }
        return authorizationUrl(material)
    }

    fun pendingAuthorizationUrl(): String? {
        ensureLoaded()
        return synchronized(stateLock) {
            val session = profileSessions.capture()
            if (!isActiveSessionLocked(session)) return@synchronized null
            val state = storedState.pendingAuthorizationState?.takeIf(String::isNotBlank)
                ?: return@synchronized null
            val verifier = runtime.storage.loadCodeVerifier(session.profileId)?.takeIf(String::isNotBlank)
                ?: run {
                    clearPendingAuthorizationLocked(session.profileId)
                    persistMetadataLocked(session.profileId)
                    publishLocked(error = SimklAuthError.AUTHORIZATION_EXPIRED)
                    return@synchronized null
                }
            if (isSimklAuthorizationExpired(
                    startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            ) {
                clearPendingAuthorizationLocked(session.profileId)
                persistMetadataLocked(session.profileId)
                publishLocked(error = SimklAuthError.AUTHORIZATION_EXPIRED)
                return@synchronized null
            }
            authorizationUrl(
                SimklPkceMaterial(
                    verifier = verifier,
                    challenge = SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding(),
                    state = state,
                ),
            )
        }
    }

    fun onCancelAuthorization() {
        ensureLoaded()
        synchronized(stateLock) {
            val session = profileSessions.capture()
            if (!isActiveSessionLocked(session)) return@synchronized
            clearPendingAuthorizationLocked(session.profileId)
            persistMetadataLocked(session.profileId)
            publishLocked(error = null)
        }
    }

    override fun handleAuthCallback(url: String): Boolean {
        ensureLoaded()
        val session = synchronized(stateLock) {
            profileSessions.capture().takeIf(::isActiveSessionLocked)
        } ?: return false
        return when (val callback = parseSimklAuthCallback(url, SimklConfig.REDIRECT_URI)) {
            SimklAuthCallback.NotSimkl -> false
            SimklAuthCallback.Invalid -> {
                synchronized(stateLock) {
                    if (!isActiveSessionLocked(session)) return@synchronized
                    clearPendingAuthorizationLocked(session.profileId)
                    persistMetadataLocked(session.profileId)
                    publishLocked(error = SimklAuthError.INVALID_CALLBACK)
                }
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
        val tokenToRevoke = synchronized(stateLock) {
            val session = profileSessions.capture()
            if (!isActiveSessionLocked(session)) return@synchronized null
            val token = refreshToken?.takeIf(String::isNotBlank)
                ?: accessToken?.takeIf(String::isNotBlank)
            clearCredentialsLocked(session, error = null)
            token
        }
        tokenToRevoke?.let(::revokeGrantAsync)
    }

    internal suspend fun authorizedAccessToken(): String? {
        ensureLoaded()
        val initial = synchronized(stateLock) {
            val session = profileSessions.capture()
            if (!isActiveSessionLocked(session)) return@synchronized null
            val token = accessToken?.takeIf(String::isNotBlank) ?: return@synchronized null
            SimklAccessSnapshot(session, token, storedState.tokenExpiresAtEpochMs)
        } ?: return null
        val token = initial.accessToken
        val expiresAt = initial.expiresAtEpochMs
        if (expiresAt != null && SimklPlatformClock.nowEpochMs() >= expiresAt - TOKEN_EXPIRY_SKEW_MS) {
            return authorizationMutex.withLock {
                val current = synchronized(stateLock) {
                    if (!isActiveSessionLocked(initial.session)) return@synchronized null
                    val currentToken = accessToken?.takeIf(String::isNotBlank) ?: return@synchronized null
                    SimklAccessSnapshot(initial.session, currentToken, storedState.tokenExpiresAtEpochMs)
                } ?: return@withLock null
                if (current.accessToken != token && current.expiresAtEpochMs?.let(::isAccessTokenUsable) == true) {
                    return@withLock current.accessToken
                }
                refreshAccessToken(current.session)
            }
        }
        return token
    }

    internal suspend fun refreshAccessTokenAfterUnauthorized(rejectedAccessToken: String): String? =
        authorizationMutex.withLock {
            ensureLoaded()
            val snapshot = synchronized(stateLock) {
                val session = profileSessions.capture()
                if (!isActiveSessionLocked(session)) return@synchronized null
                val currentToken = accessToken?.takeIf(String::isNotBlank) ?: return@synchronized null
                SimklAccessSnapshot(session, currentToken, storedState.tokenExpiresAtEpochMs)
            } ?: return@withLock null
            val currentToken = snapshot.accessToken
            if (currentToken != rejectedAccessToken) return@withLock currentToken
            refreshAccessToken(snapshot.session)
        }

    internal fun onAuthorizationLost() {
        ensureLoaded()
        synchronized(stateLock) {
            val session = profileSessions.capture()
            if (isActiveSessionLocked(session)) {
                clearCredentialsLocked(session, SimklAuthError.AUTHORIZATION_REVOKED)
            }
        }
    }

    suspend fun refreshUserSettings(): String? {
        authorizedAccessToken() ?: return null
        val session = synchronized(stateLock) { profileSessions.capture() }
        if (!fetchAndStoreUserSettings(session)) return null
        return synchronized(stateLock) {
            if (isActiveSessionLocked(session)) storedState.username else null
        }
    }

    internal suspend fun synchronizeUserSettings(activityWatermark: String?) {
        authorizedAccessToken() ?: return
        val decision = synchronized(stateLock) {
            val session = profileSessions.capture()
            if (!isActiveSessionLocked(session)) return@synchronized null
            session to simklSettingsRefreshAction(storedState, activityWatermark)
        } ?: return
        val session = decision.first
        when (decision.second) {
            SimklSettingsRefreshAction.NONE -> Unit
            SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                synchronized(stateLock) {
                    if (!isActiveSessionLocked(session)) return@synchronized
                    storedState = storedState.copy(settingsActivityWatermark = activityWatermark)
                    persistMetadataLocked(session.profileId)
                }
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
            val request = synchronized(stateLock) {
                if (!isActiveSessionLocked(session)) return@synchronized null
                publishLocked(isLoading = true, error = null)
                val expectedState = storedState.pendingAuthorizationState
                val verifier = runtime.storage.loadCodeVerifier(session.profileId)
                val isExpired = isSimklAuthorizationExpired(
                    startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
                val preparationError = when {
                    expectedState.isNullOrBlank() || verifier.isNullOrBlank() || isExpired -> {
                        SimklAuthError.AUTHORIZATION_EXPIRED
                    }
                    !constantTimeEquals(callback.state, expectedState) -> SimklAuthError.INVALID_CALLBACK_STATE
                    callback.issuer != SIMKL_ISSUER -> SimklAuthError.INVALID_CALLBACK_ISSUER
                    else -> null
                }
                if (preparationError != null) {
                    clearPendingAuthorizationLocked(session.profileId)
                    persistMetadataLocked(session.profileId)
                    publishLocked(isLoading = false, error = preparationError)
                    return@synchronized null
                }
                SimklAuthorizationRequest(expectedState.orEmpty(), verifier.orEmpty())
            } ?: return@withLock

            val token = try {
                runtime.network.exchangeAuthorizationCode(
                    clientId = SimklConfig.CLIENT_ID,
                    code = callback.code,
                    redirectUri = SimklConfig.REDIRECT_URI,
                    codeVerifier = request.verifier,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Simkl token exchange failed: ${error.message}" }
                synchronized(stateLock) {
                    if (!isCurrentAuthorizationLocked(session, request.expectedState, request.verifier)) {
                        return@synchronized
                    }
                    clearPendingAuthorizationLocked(session.profileId)
                    persistMetadataLocked(session.profileId)
                    publishLocked(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
                }
                return@withLock
            }
            val validToken = isValidSimklOAuthToken(token)
            runtime.beforeCommit(SimklAuthCommitPoint.EXCHANGE)
            val committed = synchronized(stateLock) {
                if (!isCurrentAuthorizationLocked(session, request.expectedState, request.verifier)) {
                    return@synchronized false
                }
                if (!validToken) {
                    clearPendingAuthorizationLocked(session.profileId)
                    persistMetadataLocked(session.profileId)
                    publishLocked(
                        isLoading = false,
                        error = if (hasRequiredSimklScope(token.scope)) {
                            SimklAuthError.INVALID_TOKEN_RESPONSE
                        } else {
                            SimklAuthError.INSUFFICIENT_SCOPE
                        },
                    )
                    return@synchronized false
                }
                clearPendingAuthorizationLocked(session.profileId)
                storeTokenLocked(session, token)
                publishLocked(isLoading = false, error = null)
                true
            }
            if (!committed) {
                (token.refreshToken ?: token.accessToken)
                    .takeIf(String::isNotBlank)
                    ?.let { revokeStaleGrant(it) }
                return@withLock
            }
            fetchAndStoreUserSettings(session)
            val remainsCurrent = synchronized(stateLock) { isActiveSessionLocked(session) }
            if (!remainsCurrent) return@withLock
            runtime.onAuthorizationCommitted()
        }

    private suspend fun completeAuthorizationError(
        callback: SimklAuthCallback.AuthorizationError,
        session: TrackingAuthProfileSession,
    ) =
        authorizationMutex.withLock {
            synchronized(stateLock) {
                if (!isActiveSessionLocked(session)) return@synchronized
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
                clearPendingAuthorizationLocked(session.profileId)
                persistMetadataLocked(session.profileId)
                publishLocked(isLoading = false, error = callbackError)
            }
        }

    private suspend fun fetchAndStoreUserSettings(
        session: TrackingAuthProfileSession,
        activityWatermark: String? = null,
    ): Boolean {
        val expectedRefreshToken = synchronized(stateLock) {
            if (!isActiveSessionLocked(session)) return@synchronized null
            refreshToken?.takeIf(String::isNotBlank)
        } ?: return false
        val response = try {
            runtime.network.fetchUserSettings()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to fetch Simkl user settings: ${error.message}" }
            return false
        }
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }
            .getOrNull() ?: return false
        return synchronized(stateLock) {
            if (!isActiveSessionLocked(session) || refreshToken != expectedRefreshToken) {
                return@synchronized false
            }
            storedState = storedState.copy(
                username = settings.user?.name,
                accountId = settings.account?.id,
                hasFetchedUserSettings = true,
                settingsActivityWatermark = activityWatermark ?: storedState.settingsActivityWatermark,
            )
            persistMetadataLocked(session.profileId)
            publishLocked(error = null)
            true
        }
    }

    private fun loadFromDiskLocked(profileId: Int) {
        profileSessions.moveTo(profileId)
        hasLoaded = true
        storedState = runtime.storage.loadMetadataPayload(profileId)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { json.decodeFromString<SimklStoredAuthState>(payload) }
                    .onFailure { error -> log.w { "Failed to parse Simkl auth metadata: ${error.message}" } }
                    .getOrNull()
            }
            ?: SimklStoredAuthState()
        accessToken = runtime.storage.loadAccessToken(profileId)?.takeIf(String::isNotBlank)
        refreshToken = runtime.storage.loadRefreshToken(profileId)?.takeIf(String::isNotBlank)
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
            clearCredentialsLocked(profileSessions.capture(), error = null)
            return
        }
        if (storedState.hasPendingAuthorization && isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorizationLocked(profileId)
            persistMetadataLocked(profileId)
        }
        publishLocked(error = null)
    }

    private fun resetInMemoryStateLocked(profileId: Int) {
        hasLoaded = false
        profileSessions.moveTo(profileId)
        storedState = SimklStoredAuthState()
        accessToken = null
        refreshToken = null
        publishLocked(isLoading = false, error = null)
    }

    private fun clearCredentialsLocked(session: TrackingAuthProfileSession, error: SimklAuthError?) {
        if (!isActiveSessionLocked(session)) return
        accessToken = null
        refreshToken = null
        runtime.storage.saveAccessToken(session.profileId, null)
        runtime.storage.saveRefreshToken(session.profileId, null)
        clearPendingAuthorizationLocked(session.profileId)
        storedState = SimklStoredAuthState()
        persistMetadataLocked(session.profileId)
        SimklSyncRepository.clearLocalState()
        publishLocked(isLoading = false, error = error)
    }

    private suspend fun refreshAccessToken(session: TrackingAuthProfileSession): String? {
        val refreshRequest = synchronized(stateLock) {
            if (!isActiveSessionLocked(session)) return@synchronized null
            val currentRefreshToken = refreshToken?.takeIf(String::isNotBlank)
            val refreshExpiresAt = storedState.refreshTokenExpiresAtEpochMs
            if (currentRefreshToken == null ||
                refreshExpiresAt == null ||
                SimklPlatformClock.nowEpochMs() >= refreshExpiresAt
            ) {
                clearCredentialsLocked(session, SimklAuthError.AUTHORIZATION_EXPIRED)
                return@synchronized null
            }
            SimklRefreshRequest(currentRefreshToken)
        } ?: return null

        val token = try {
            runtime.network.refreshAccessToken(
                clientId = SimklConfig.CLIENT_ID,
                refreshToken = refreshRequest.refreshToken,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimklApiException) {
            if (error.status == 400 || error.status == 401) {
                synchronized(stateLock) {
                    if (isActiveSessionLocked(session) && refreshToken == refreshRequest.refreshToken) {
                        clearCredentialsLocked(session, SimklAuthError.AUTHORIZATION_REVOKED)
                    }
                }
                return synchronized(stateLock) {
                    if (isActiveSessionLocked(session)) accessToken else null
                }
            }
            throw error
        }
        val validToken = isValidSimklOAuthToken(token, existingRefreshToken = refreshRequest.refreshToken)
        runtime.beforeCommit(SimklAuthCommitPoint.REFRESH)
        val committedToken = synchronized(stateLock) {
            if (!isActiveSessionLocked(session) || refreshToken != refreshRequest.refreshToken) {
                return@synchronized null
            }
            if (!validToken) {
                clearCredentialsLocked(
                    session,
                    if (hasRequiredSimklScope(token.scope)) {
                        SimklAuthError.INVALID_TOKEN_RESPONSE
                    } else {
                        SimklAuthError.INSUFFICIENT_SCOPE
                    },
                )
                return@synchronized null
            }
            storeTokenLocked(session, token, existingRefreshToken = refreshRequest.refreshToken)
            publishLocked(isLoading = false, error = null)
            accessToken
        }
        if (committedToken == null) {
            (token.refreshToken ?: token.accessToken)
                .takeIf(String::isNotBlank)
                ?.let { revokeStaleGrant(it) }
            return null
        }
        return committedToken
    }

    private fun storeTokenLocked(
        session: TrackingAuthProfileSession,
        token: SimklOAuthToken,
        existingRefreshToken: String? = null,
    ) {
        check(isActiveSessionLocked(session)) { "Cannot store Simkl credentials for a stale profile session" }
        val now = SimklPlatformClock.nowEpochMs()
        val nextRefreshToken = token.refreshToken?.takeIf(String::isNotBlank)
            ?: existingRefreshToken?.takeIf(String::isNotBlank)
            ?: error("Simkl OAuth response did not include a refresh token")
        accessToken = token.accessToken
        refreshToken = nextRefreshToken
        runtime.storage.saveAccessToken(session.profileId, token.accessToken)
        runtime.storage.saveRefreshToken(session.profileId, nextRefreshToken)
        storedState = storedState.copy(
            tokenExpiresAtEpochMs = now + token.expiresInSeconds.orZero() * 1_000L,
            refreshTokenExpiresAtEpochMs = now + SIMKL_REFRESH_TOKEN_LIFETIME_MS,
            grantedScope = token.scope,
        )
        persistMetadataLocked(session.profileId)
    }

    private fun isAccessTokenUsable(expiresAtEpochMs: Long): Boolean =
        SimklPlatformClock.nowEpochMs() < expiresAtEpochMs - TOKEN_EXPIRY_SKEW_MS

    private fun isCurrentAuthorizationLocked(
        session: TrackingAuthProfileSession,
        expectedState: String,
        verifier: String,
    ): Boolean = isActiveSessionLocked(session) &&
        storedState.pendingAuthorizationState == expectedState &&
        runtime.storage.loadCodeVerifier(session.profileId) == verifier

    private fun isActiveSessionLocked(session: TrackingAuthProfileSession): Boolean =
        profileSessions.isCurrent(session) && runtime.activeProfileId() == session.profileId

    private suspend fun revokeStaleGrant(token: String) {
        if (!hasRequiredCredentials()) return
        runCatching {
            runtime.network.revoke(
                clientId = SimklConfig.CLIENT_ID,
                token = token,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Simkl stale grant revocation request failed: ${error.message}" }
        }
    }

    private fun revokeGrantAsync(token: String) {
        if (!hasRequiredCredentials()) return
        scope.launch {
            runCatching {
                runtime.network.revoke(
                    clientId = SimklConfig.CLIENT_ID,
                    token = token,
                )
            }.onFailure { error ->
                log.w { "Simkl grant revocation request failed: ${error.message}" }
            }
        }
    }

    private fun clearPendingAuthorizationLocked(profileId: Int) {
        runtime.storage.saveCodeVerifier(profileId, null)
        storedState = storedState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = null,
        )
    }

    private fun persistMetadataLocked(profileId: Int) {
        runtime.storage.saveMetadataPayload(profileId, json.encodeToString(storedState))
    }

    private fun publishLocked(
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

private data class SimklAccessSnapshot(
    val session: TrackingAuthProfileSession,
    val accessToken: String,
    val expiresAtEpochMs: Long?,
)

private data class SimklAuthorizationRequest(
    val expectedState: String,
    val verifier: String,
)

private data class SimklRefreshRequest(
    val refreshToken: String,
)

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
