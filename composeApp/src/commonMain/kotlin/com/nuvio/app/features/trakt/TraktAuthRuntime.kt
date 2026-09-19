package com.nuvio.app.features.trakt

import com.nuvio.app.features.addons.RawHttpResponse
import org.jetbrains.compose.resources.StringResource

internal interface TraktAuthStore {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
    fun removeProfile(profileId: Int)
}

internal object PlatformTraktAuthStore : TraktAuthStore {
    override fun loadPayload(profileId: Int): String? = TraktAuthStorage.loadPayload(profileId)
    override fun savePayload(profileId: Int, payload: String) = TraktAuthStorage.savePayload(profileId, payload)
    override fun removeProfile(profileId: Int) = TraktAuthStorage.removeProfile(profileId)
}

internal interface TraktAuthNetwork {
    suspend fun exchangeAuthorizationCode(body: String): String
    suspend fun refreshAccessToken(body: String): RawHttpResponse
    suspend fun fetchUserSettings(headers: Map<String, String>): String
    suspend fun revoke(body: String)
}

internal enum class TraktAuthCommitPoint {
    EXCHANGE,
    REFRESH,
}

internal data class TraktAuthRuntime(
    val activeProfileId: () -> Int,
    val storage: TraktAuthStore,
    val network: TraktAuthNetwork,
    val credentialsConfigured: () -> Boolean,
    val localize: (StringResource) -> String,
    val beforeCommit: suspend (TraktAuthCommitPoint) -> Unit = {},
)

internal data class TraktAuthRepositorySnapshot(
    val loadedProfileId: Int,
    val authState: TraktAuthState,
    val uiState: TraktAuthUiState,
)
