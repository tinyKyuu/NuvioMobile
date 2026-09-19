package com.nuvio.app.features.simkl

internal interface SimklAuthStore {
    fun loadMetadataPayload(profileId: Int): String?
    fun saveMetadataPayload(profileId: Int, payload: String)
    fun loadAccessToken(profileId: Int): String?
    fun saveAccessToken(profileId: Int, value: String?)
    fun loadRefreshToken(profileId: Int): String?
    fun saveRefreshToken(profileId: Int, value: String?)
    fun loadCodeVerifier(profileId: Int): String?
    fun saveCodeVerifier(profileId: Int, value: String?)
    fun removeProfile(profileId: Int)
}

internal object PlatformSimklAuthStore : SimklAuthStore {
    override fun loadMetadataPayload(profileId: Int): String? = SimklAuthStorage.loadMetadataPayload(profileId)
    override fun saveMetadataPayload(profileId: Int, payload: String) =
        SimklAuthStorage.saveMetadataPayload(profileId, payload)
    override fun loadAccessToken(profileId: Int): String? = SimklAuthStorage.loadAccessToken(profileId)
    override fun saveAccessToken(profileId: Int, value: String?) = SimklAuthStorage.saveAccessToken(profileId, value)
    override fun loadRefreshToken(profileId: Int): String? = SimklAuthStorage.loadRefreshToken(profileId)
    override fun saveRefreshToken(profileId: Int, value: String?) = SimklAuthStorage.saveRefreshToken(profileId, value)
    override fun loadCodeVerifier(profileId: Int): String? = SimklAuthStorage.loadCodeVerifier(profileId)
    override fun saveCodeVerifier(profileId: Int, value: String?) = SimklAuthStorage.saveCodeVerifier(profileId, value)
    override fun removeProfile(profileId: Int) = SimklAuthStorage.removeProfile(profileId)
}

internal interface SimklAuthNetwork {
    suspend fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): SimklOAuthToken

    suspend fun refreshAccessToken(clientId: String, refreshToken: String): SimklOAuthToken
    suspend fun fetchUserSettings(): SimklApiResponse
    suspend fun revoke(clientId: String, token: String)
}

internal object PlatformSimklAuthNetwork : SimklAuthNetwork {
    override suspend fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): SimklOAuthToken = SimklOAuthApi.client.exchangeAuthorizationCode(
        clientId = clientId,
        code = code,
        redirectUri = redirectUri,
        codeVerifier = codeVerifier,
    )

    override suspend fun refreshAccessToken(clientId: String, refreshToken: String): SimklOAuthToken =
        SimklOAuthApi.client.refreshAccessToken(clientId, refreshToken)

    override suspend fun fetchUserSettings(): SimklApiResponse = SimklApi.client.execute(
        SimklApiRequest(
            method = SimklHttpMethod.GET,
            path = "/users/settings",
        ),
    )

    override suspend fun revoke(clientId: String, token: String) = SimklOAuthApi.client.revoke(clientId, token)
}

internal enum class SimklAuthCommitPoint {
    EXCHANGE,
    REFRESH,
}

internal data class SimklAuthRuntime(
    val activeProfileId: () -> Int,
    val storage: SimklAuthStore,
    val network: SimklAuthNetwork,
    val credentialsConfigured: () -> Boolean,
    val beforeCommit: suspend (SimklAuthCommitPoint) -> Unit = {},
    val onAuthorizationCommitted: () -> Unit = {},
)

internal data class SimklAuthRepositorySnapshot(
    val loadedProfileId: Int,
    val accessToken: String?,
    val refreshToken: String?,
    val storedState: SimklStoredAuthState,
    val uiState: SimklAuthUiState,
)
