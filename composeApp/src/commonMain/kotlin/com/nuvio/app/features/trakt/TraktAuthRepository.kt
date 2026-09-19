package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingAuthConfigurationStatus
import com.nuvio.app.features.tracking.TrackingAuthProfileSession
import com.nuvio.app.features.tracking.TrackingAuthProfileSessionGuard
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.trackingAuthConfigurationStatus
import io.ktor.http.encodeURLParameter
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.runBlocking

object TraktAuthRepository : TrackingAuthProvider {
    private const val BASE_URL = "https://api.trakt.tv"
    private const val AUTHORIZE_URL = "https://trakt.tv/oauth/authorize"
    private const val API_VERSION = "2"
    private const val SUPPORTED_REDIRECT_URI = "nuvio://auth/trakt"

    private val log = Logger.withTag("TraktAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()
    private val stateLock = SynchronizedObject()
    private var runtime = TraktAuthRuntime(
        activeProfileId = { ProfileRepository.activeProfileId },
        storage = PlatformTraktAuthStore,
        network = object : TraktAuthNetwork {
            override suspend fun exchangeAuthorizationCode(body: String): String = httpPostJsonWithHeaders(
                url = "$BASE_URL/oauth/token",
                body = body,
                headers = emptyMap(),
            )

            override suspend fun refreshAccessToken(body: String) = httpRequestRaw(
                method = "POST",
                url = "$BASE_URL/oauth/token",
                body = body,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                ),
            )

            override suspend fun fetchUserSettings(headers: Map<String, String>): String =
                httpGetTextWithHeaders("$BASE_URL/users/settings", headers)

            override suspend fun revoke(body: String) {
                httpPostJsonWithHeaders(
                    url = "$BASE_URL/oauth/revoke",
                    body = body,
                    headers = emptyMap(),
                )
            }
        },
        credentialsConfigured = {
            configurationStatus() == TrackingAuthConfigurationStatus.READY
        },
        localize = ::localizedString,
    )

    private val _uiState = MutableStateFlow(TraktAuthUiState())
    val uiState: StateFlow<TraktAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.TRAKT,
        displayName = "Trakt",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
            TrackingCapability.COMMENTS,
            TrackingCapability.RECOMMENDATIONS,
        ),
    )

    init {
        TrackingProviderRegistry.register(this)
    }

    private var hasLoaded = false
    private val profileSessions = TrackingAuthProfileSessionGuard(runtime.activeProfileId())
    private val currentProfileId: Int get() = synchronized(stateLock) { profileSessions.profileId }
    private var authState = TraktAuthState()

    override fun ensureLoaded() {
        ensureLoaded(runtime.activeProfileId())
    }

    override fun onProfileChanged() {
        onProfileChanged(runtime.activeProfileId())
    }

    fun ensureLoaded(profileId: Int) {
        if (runtime.activeProfileId() != profileId) return
        synchronized(stateLock) {
            if (runtime.activeProfileId() != profileId) return@synchronized
            if (hasLoaded && profileSessions.profileId == profileId) return@synchronized
            loadFromDiskLocked(profileId)
        }
    }

    fun onProfileChanged(profileId: Int) {
        if (runtime.activeProfileId() != profileId) return
        synchronized(stateLock) {
            if (runtime.activeProfileId() == profileId) loadFromDiskLocked(profileId)
        }
    }

    override fun clearLocalState() {
        TraktWatchedShowSnapshotRepository.clear()
        synchronized(stateLock) {
            hasLoaded = false
            profileSessions.invalidate()
            authState = TraktAuthState()
            publishLocked()
        }
    }

    override fun removeStoredProfile(profileId: Int) {
        runtime.storage.removeProfile(profileId)
    }

    fun snapshot(profileId: Int = runtime.activeProfileId()): TraktAuthUiState {
        ensureLoaded(profileId)
        return synchronized(stateLock) { _uiState.value }
    }

    internal fun installRuntimeForTesting(nextRuntime: TraktAuthRuntime): TraktAuthRuntime =
        synchronized(stateLock) {
            val previous = runtime
            runtime = nextRuntime
            resetInMemoryStateLocked(nextRuntime.activeProfileId())
            previous
        }

    internal fun restoreRuntimeAfterTesting(previous: TraktAuthRuntime) {
        synchronized(stateLock) {
            runtime = previous
            resetInMemoryStateLocked(previous.activeProfileId())
        }
    }

    internal fun repositorySnapshotForTesting(): TraktAuthRepositorySnapshot = synchronized(stateLock) {
        TraktAuthRepositorySnapshot(
            loadedProfileId = profileSessions.profileId,
            authState = authState,
            uiState = _uiState.value,
        )
    }

    internal suspend fun completeAuthorizationForTesting(callback: TraktAuthCallback) {
        ensureLoaded()
        val session = synchronized(stateLock) { profileSessions.capture() }
        completeAuthorizationFromCallback(callback, session)
    }

    internal suspend fun refreshAccessTokenForTesting(): Boolean =
        refreshTokenIfNeeded(force = true, profileId = runtime.activeProfileId())

    internal fun configurationStatus(): TrackingAuthConfigurationStatus =
        trackingAuthConfigurationStatus(
            requiredValues = listOf(TraktConfig.CLIENT_ID, TraktConfig.CLIENT_SECRET),
            redirectUri = TraktConfig.REDIRECT_URI,
            supportedRedirectUri = SUPPORTED_REDIRECT_URI,
        )

    fun hasRequiredCredentials(): Boolean =
        runtime.credentialsConfigured()

    fun onConnectRequested(profileId: Int = runtime.activeProfileId()): String? {
        ensureLoaded(profileId)
        if (!hasRequiredCredentials()) {
            synchronized(stateLock) { publishLocked(errorMessage = null) }
            return null
        }

        val oauthState = generateTraktOauthState()
        synchronized(stateLock) {
            val session = profileSessions.capture()
            if (!isActiveSessionLocked(session) || session.profileId != profileId) return@synchronized
            authState = authState.copy(
                pendingAuthorizationState = oauthState,
                pendingAuthorizationStartedAtMillis = TraktPlatformClock.nowEpochMs(),
            )
            persistLocked(profileId)
            publishLocked(
                statusMessage = runtime.localize(Res.string.trakt_complete_sign_in_browser),
                errorMessage = null,
            )
        }

        return buildAuthorizationUrl(oauthState)
    }

    fun pendingAuthorizationUrl(profileId: Int = runtime.activeProfileId()): String? {
        ensureLoaded(profileId)
        return synchronized(stateLock) {
            val session = profileSessions.capture()
            if (!isActiveSessionLocked(session) || session.profileId != profileId) return@synchronized null
            val oauthState = authState.pendingAuthorizationState ?: return@synchronized null
            if (isTraktAuthorizationExpired(
                    startedAtEpochMs = authState.pendingAuthorizationStartedAtMillis,
                    nowEpochMs = TraktPlatformClock.nowEpochMs(),
                )
            ) {
                clearPendingAuthorizationLocked()
                persistLocked(profileId)
                publishLocked(
                    statusMessage = null,
                    errorMessage = runtime.localize(Res.string.trakt_authorization_expired_reconnect),
                )
                return@synchronized null
            }
            buildAuthorizationUrl(oauthState)
        }
    }

    fun onCancelAuthorization(profileId: Int = runtime.activeProfileId()) {
        ensureLoaded(profileId)
        synchronized(stateLock) {
            val session = profileSessions.capture()
            if (!isActiveSessionLocked(session) || session.profileId != profileId) return@synchronized
            clearPendingAuthorizationLocked()
            persistLocked(profileId)
            publishLocked(statusMessage = null, errorMessage = null)
        }
    }

    fun onCancelDeviceFlow(profileId: Int = runtime.activeProfileId()) {
        onCancelAuthorization(profileId)
    }

    fun onAuthLaunchFailed(reason: String) {
        synchronized(stateLock) { publishLocked(errorMessage = reason) }
    }

    fun onAuthCallbackReceived(callbackUrl: String) {
        val profileId = runtime.activeProfileId()
        ensureLoaded(profileId)
        val session = synchronized(stateLock) {
            profileSessions.capture().takeIf(::isActiveSessionLocked)
        } ?: return
        val callback = parseTraktAuthCallback(callbackUrl, TraktConfig.REDIRECT_URI)
        if (callback == TraktAuthCallback.NotTrakt) return

        scope.launch {
            completeAuthorizationFromCallback(callback, session)
        }
    }

    override fun handleAuthCallback(url: String): Boolean {
        if (parseTraktAuthCallback(url, TraktConfig.REDIRECT_URI) == TraktAuthCallback.NotTrakt) {
            return false
        }
        onAuthCallbackReceived(url)
        return true
    }

    suspend fun authorizedHeaders(profileId: Int = currentProfileId): Map<String, String>? {
        if (runtime.activeProfileId() != profileId) return null
        ensureLoaded(profileId)
        val initialSession = synchronized(stateLock) {
            val session = profileSessions.capture()
            session.takeIf { isActiveSessionLocked(it) && it.profileId == profileId && authState.isAuthenticated }
        } ?: return null

        val hasValidToken = refreshTokenIfNeeded(force = false, profileId = profileId)
        if (!hasValidToken) return null

        return synchronized(stateLock) {
            if (!isActiveSessionLocked(initialSession)) return@synchronized null
            val accessToken = authState.accessToken?.trim().orEmpty()
            if (accessToken.isBlank()) return@synchronized null
            mapOf(
                "trakt-api-version" to API_VERSION,
                "trakt-api-key" to TraktConfig.CLIENT_ID,
                "Authorization" to "Bearer $accessToken",
            )
        }
    }

    suspend fun refreshUserSettings(profileId: Int = currentProfileId): String? {
        if (runtime.activeProfileId() != profileId) return null
        ensureLoaded(profileId)
        val session = synchronized(stateLock) {
            profileSessions.capture().takeIf { isActiveSessionLocked(it) && it.profileId == profileId }
        } ?: return null
        val headers = authorizedHeaders(profileId) ?: return null
        return refreshUserSettingsForSession(session, headers)
    }

    private suspend fun refreshUserSettingsForSession(
        session: TrackingAuthProfileSession,
        headers: Map<String, String>,
    ): String? {
        val response = runCatching {
            runtime.network.fetchUserSettings(headers)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to fetch Trakt user settings: ${error.message}" }
        }.getOrNull() ?: return null

        val parsed = runCatching {
            json.decodeFromString<TraktUserSettingsResponse>(response)
        }.getOrNull() ?: return null

        return synchronized(stateLock) {
            if (!isActiveSessionLocked(session)) return@synchronized null
            authState = authState.copy(
                username = parsed.user?.username,
                userSlug = parsed.user?.ids?.slug,
            )
            persistLocked(session.profileId)
            publishLocked()
            authState.username
        }
    }

    fun onDisconnectRequested(profileId: Int = currentProfileId) {
        ensureLoaded(profileId)
        scope.launch {
            disconnect(profileId)
        }
    }

    private suspend fun completeAuthorizationFromCallback(
        callback: TraktAuthCallback,
        session: TrackingAuthProfileSession,
    ) {
        val profileId = session.profileId
        val expectedState = synchronized(stateLock) {
            if (!isActiveSessionLocked(session)) return@synchronized null
            publishLocked(isLoading = true, errorMessage = null)

            if (callback == TraktAuthCallback.Invalid || callback == TraktAuthCallback.NotTrakt) {
                clearPendingAuthorizationLocked()
                persistLocked(profileId)
                publishLocked(
                    isLoading = false,
                    errorMessage = runtime.localize(Res.string.trakt_invalid_callback),
                )
                return@synchronized null
            }

            val pendingState = authState.pendingAuthorizationState
            if (isTraktAuthorizationExpired(
                    startedAtEpochMs = authState.pendingAuthorizationStartedAtMillis,
                    nowEpochMs = TraktPlatformClock.nowEpochMs(),
                )
            ) {
                clearPendingAuthorizationLocked()
                persistLocked(profileId)
                publishLocked(
                    isLoading = false,
                    statusMessage = null,
                    errorMessage = runtime.localize(Res.string.trakt_authorization_expired_reconnect),
                )
                return@synchronized null
            }
            if (!isTraktCallbackStateValid(callback.state, pendingState)) {
                publishLocked(
                    isLoading = false,
                    errorMessage = runtime.localize(Res.string.trakt_invalid_callback_state),
                )
                return@synchronized null
            }
            pendingState
        } ?: return

        when (callback) {
            is TraktAuthCallback.ProviderError -> {
                synchronized(stateLock) {
                    if (!isCurrentAuthorizationLocked(session, expectedState)) return@synchronized
                    clearPendingAuthorizationLocked()
                    persistLocked(profileId)
                    publishLocked(
                        isLoading = false,
                        errorMessage = callback.description
                            ?: runtime.localize(Res.string.trakt_authorization_denied),
                    )
                }
            }
            is TraktAuthCallback.AuthorizationCode ->
                exchangeAuthorizationCode(
                    code = callback.code,
                    session = session,
                    expectedState = expectedState,
                )
            TraktAuthCallback.Invalid,
            TraktAuthCallback.NotTrakt,
            -> Unit
        }
    }

    private suspend fun exchangeAuthorizationCode(
        code: String,
        session: TrackingAuthProfileSession,
        expectedState: String,
    ) {
        val profileId = session.profileId
        val body = json.encodeToString(
            TraktAuthorizationCodeRequest(
                code = code,
                clientId = TraktConfig.CLIENT_ID,
                clientSecret = TraktConfig.CLIENT_SECRET,
                redirectUri = TraktConfig.REDIRECT_URI,
            ),
        )

        val response = runCatching {
            runtime.network.exchangeAuthorizationCode(body)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to exchange Trakt auth code: ${error.message}" }
        }.getOrNull()

        if (response == null) {
            synchronized(stateLock) {
                if (!isCurrentAuthorizationLocked(session, expectedState)) return@synchronized
                clearPendingAuthorizationLocked()
                persistLocked(profileId)
                publishLocked(
                    isLoading = false,
                    errorMessage = runtime.localize(Res.string.trakt_sign_in_complete_failed),
                )
            }
            return
        }

        val parsed = runCatching {
            json.decodeFromString<TraktTokenResponse>(response)
        }.getOrNull()

        if (parsed == null) {
            synchronized(stateLock) {
                if (!isCurrentAuthorizationLocked(session, expectedState)) return@synchronized
                clearPendingAuthorizationLocked()
                persistLocked(profileId)
                publishLocked(
                    isLoading = false,
                    errorMessage = runtime.localize(Res.string.trakt_invalid_token_response),
                )
            }
            return
        }

        runtime.beforeCommit(TraktAuthCommitPoint.EXCHANGE)
        val headers = synchronized(stateLock) {
            if (!isCurrentAuthorizationLocked(session, expectedState)) return@synchronized null
            authState = authState.copy(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                tokenType = parsed.tokenType,
                createdAt = parsed.createdAt,
                expiresIn = parsed.expiresIn,
                pendingAuthorizationState = null,
                pendingAuthorizationStartedAtMillis = null,
            )
            persistLocked(profileId)
            publishLocked(
                isLoading = false,
                statusMessage = runtime.localize(Res.string.trakt_connected_status),
                errorMessage = null,
            )
            mapOf(
                "trakt-api-version" to API_VERSION,
                "trakt-api-key" to TraktConfig.CLIENT_ID,
                "Authorization" to "Bearer ${parsed.accessToken}",
            )
        }
        if (headers == null) {
            revokeStaleGrant(parsed.accessToken)
            return
        }
        refreshUserSettingsForSession(session, headers)
    }

    private suspend fun disconnect(profileId: Int = currentProfileId) {
        if (runtime.activeProfileId() != profileId) return
        ensureLoaded(profileId)
        val request = synchronized(stateLock) {
            val session = profileSessions.capture()
            if (!isActiveSessionLocked(session) || session.profileId != profileId) return@synchronized null
            publishLocked(isLoading = true, errorMessage = null)
            session to authState.accessToken?.takeIf(String::isNotBlank)
        } ?: return
        val session = request.first
        val token = request.second
        if (!token.isNullOrBlank() && hasRequiredCredentials()) {
            runCatching {
                revokeGrant(token)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Failed to revoke Trakt token: ${error.message}" }
            }
        }

        synchronized(stateLock) {
            if (!isActiveSessionLocked(session)) return@synchronized
            authState = TraktAuthState()
            persistLocked(profileId)
            publishLocked(
                isLoading = false,
                statusMessage = runtime.localize(Res.string.trakt_disconnected_status),
                errorMessage = null,
            )
        }
    }

    private suspend fun refreshTokenIfNeeded(force: Boolean, profileId: Int = currentProfileId): Boolean = refreshMutex.withLock {
        if (runtime.activeProfileId() != profileId) return@withLock false
        ensureLoaded(profileId)
        if (!hasRequiredCredentials()) return@withLock false
        val request = synchronized(stateLock) {
            val session = profileSessions.capture()
            if (!isActiveSessionLocked(session) || session.profileId != profileId) return@synchronized null
            val refreshToken = authState.refreshToken?.takeIf(String::isNotBlank)
                ?: return@synchronized null
            if (!force && !isTokenExpiredOrExpiring(authState)) {
                return@synchronized TraktRefreshRequest(session, refreshToken, refreshRequired = false)
            }
            TraktRefreshRequest(session, refreshToken, refreshRequired = true)
        } ?: return@withLock false
        if (!request.refreshRequired) return@withLock true

        val body = json.encodeToString(
            TraktRefreshTokenRequest(
                refreshToken = request.refreshToken,
                clientId = TraktConfig.CLIENT_ID,
                clientSecret = TraktConfig.CLIENT_SECRET,
                redirectUri = TraktConfig.REDIRECT_URI,
            ),
        )

        val response = runCatching {
            runtime.network.refreshAccessToken(body)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Trakt token refresh transport failure: ${error.message}" }
        }.getOrNull() ?: return@withLock false

        when (traktTokenRefreshResponseAction(response.status)) {
            TraktTokenRefreshResponseAction.INVALIDATE -> {
                log.w { "Trakt rejected the refresh token with HTTP 400; clearing local credentials" }
                synchronized(stateLock) {
                    if (isActiveSessionLocked(request.session) && authState.refreshToken == request.refreshToken) {
                        invalidateCredentialsLocked(profileId)
                    }
                }
                return@withLock false
            }

            TraktTokenRefreshResponseAction.TRANSIENT_FAILURE -> {
                log.w { "Trakt token refresh failed with HTTP ${response.status}" }
                return@withLock false
            }

            TraktTokenRefreshResponseAction.ACCEPT -> Unit
        }

        val parsed = runCatching {
            json.decodeFromString<TraktTokenResponse>(response.body)
        }.getOrNull() ?: return@withLock false

        runtime.beforeCommit(TraktAuthCommitPoint.REFRESH)
        val committed = synchronized(stateLock) {
            if (!isActiveSessionLocked(request.session) || authState.refreshToken != request.refreshToken) {
                return@synchronized false
            }
            authState = authState.copy(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                tokenType = parsed.tokenType,
                createdAt = parsed.createdAt,
                expiresIn = parsed.expiresIn,
            )
            persistLocked(profileId)
            publishLocked()
            true
        }
        if (!committed) {
            revokeStaleGrant(parsed.accessToken)
            return@withLock false
        }
        true
    }

    private fun invalidateCredentialsLocked(profileId: Int) {
        authState = TraktAuthState()
        persistLocked(profileId)
        publishLocked(
            isLoading = false,
            statusMessage = null,
            errorMessage = runtime.localize(Res.string.trakt_authorization_expired_reconnect),
        )
    }

    private fun loadFromDiskLocked(profileId: Int) {
        profileSessions.moveTo(profileId)
        hasLoaded = true
        val payload = runtime.storage.loadPayload(profileId).orEmpty().trim()
        authState = if (payload.isBlank()) {
            TraktAuthState()
        } else {
            runCatching { json.decodeFromString<TraktAuthState>(payload) }
                .getOrElse {
                    log.w { "Failed to parse Trakt auth payload: ${it.message}" }
                    TraktAuthState()
                }
        }
        if (!authState.pendingAuthorizationState.isNullOrBlank() &&
            isTraktAuthorizationExpired(
                startedAtEpochMs = authState.pendingAuthorizationStartedAtMillis,
                nowEpochMs = TraktPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorizationLocked()
            persistLocked(profileId)
        }
        publishLocked(statusMessage = null, errorMessage = null)
    }

    private fun resetInMemoryStateLocked(profileId: Int) {
        hasLoaded = false
        profileSessions.moveTo(profileId)
        authState = TraktAuthState()
        publishLocked(isLoading = false, statusMessage = null, errorMessage = null)
    }

    private fun clearPendingAuthorizationLocked() {
        authState = authState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtMillis = null,
        )
    }

    private fun publishLocked(
        isLoading: Boolean = _uiState.value.isLoading,
        statusMessage: String? = _uiState.value.statusMessage,
        errorMessage: String? = _uiState.value.errorMessage,
    ) {
        val tokenExpiresAtMillis = authState.createdAt
            ?.let { createdAtSeconds ->
                authState.expiresIn?.let { expiresInSeconds ->
                    (createdAtSeconds + expiresInSeconds) * 1_000L
                }
            }

        val mode = when {
            authState.isAuthenticated -> TraktConnectionMode.CONNECTED
            !authState.pendingAuthorizationState.isNullOrBlank() -> TraktConnectionMode.AWAITING_APPROVAL
            else -> TraktConnectionMode.DISCONNECTED
        }

        _isAuthenticated.value = authState.isAuthenticated
        _uiState.value = TraktAuthUiState(
            mode = mode,
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = authState.username,
            tokenExpiresAtMillis = tokenExpiresAtMillis,
            pendingAuthorizationStartedAtMillis = authState.pendingAuthorizationStartedAtMillis,
            statusMessage = statusMessage,
            errorMessage = errorMessage,
        )
    }

    private fun persistLocked(profileId: Int = profileSessions.profileId) {
        runtime.storage.savePayload(profileId, json.encodeToString(authState))
    }

    private fun isCurrentAuthorizationLocked(
        session: TrackingAuthProfileSession,
        expectedState: String,
    ): Boolean = isActiveSessionLocked(session) &&
        authState.pendingAuthorizationState == expectedState

    private fun isActiveSessionLocked(session: TrackingAuthProfileSession): Boolean =
        profileSessions.isCurrent(session) && runtime.activeProfileId() == session.profileId

    private suspend fun revokeStaleGrant(token: String) {
        if (!hasRequiredCredentials()) return
        runCatching { revokeGrant(token) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Failed to revoke a stale Trakt grant: ${error.message}" }
            }
    }

    private suspend fun revokeGrant(token: String) {
        val body = json.encodeToString(
            TraktRevokeRequest(
                token = token,
                clientId = TraktConfig.CLIENT_ID,
                clientSecret = TraktConfig.CLIENT_SECRET,
            ),
        )
        runtime.network.revoke(body)
    }

    private fun buildAuthorizationUrl(state: String): String {
        val responseType = "code"
        val encodedClientId = TraktConfig.CLIENT_ID.encodeURLParameter()
        val encodedRedirectUri = TraktConfig.REDIRECT_URI.encodeURLParameter()
        val encodedState = state.encodeURLParameter()
        return "$AUTHORIZE_URL?response_type=$responseType&client_id=$encodedClientId&redirect_uri=$encodedRedirectUri&state=$encodedState"
    }

    private fun isTokenExpiredOrExpiring(state: TraktAuthState): Boolean {
        val createdAt = state.createdAt ?: return true
        val expiresIn = state.expiresIn ?: return true
        val expiresAtSeconds = createdAt + expiresIn
        val nowSeconds = TraktPlatformClock.nowEpochMs() / 1_000L
        return nowSeconds >= (expiresAtSeconds - 60)
    }

    private fun localizedString(resource: StringResource): String = runBlocking { getString(resource) }
}

private data class TraktRefreshRequest(
    val session: TrackingAuthProfileSession,
    val refreshToken: String,
    val refreshRequired: Boolean,
)

internal enum class TraktTokenRefreshResponseAction {
    ACCEPT,
    INVALIDATE,
    TRANSIENT_FAILURE,
}

internal fun traktTokenRefreshResponseAction(status: Int): TraktTokenRefreshResponseAction = when {
    status == 400 -> TraktTokenRefreshResponseAction.INVALIDATE
    status in 200..299 -> TraktTokenRefreshResponseAction.ACCEPT
    else -> TraktTokenRefreshResponseAction.TRANSIENT_FAILURE
}

@Serializable
private data class TraktAuthorizationCodeRequest(
    @SerialName("code") val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("grant_type") val grantType: String = "authorization_code",
)

@Serializable
private data class TraktRefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
private data class TraktRevokeRequest(
    @SerialName("token") val token: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)

@Serializable
private data class TraktTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
private data class TraktUserSettingsResponse(
    val user: TraktUserDto? = null,
)

@Serializable
private data class TraktUserDto(
    val username: String? = null,
    val ids: TraktUserIdsDto? = null,
)

@Serializable
private data class TraktUserIdsDto(
    val slug: String? = null,
)
