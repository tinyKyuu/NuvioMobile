package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal const val SIMKL_REQUIRED_SCOPE = "media:read media:write"
internal const val SIMKL_REFRESH_TOKEN_LIFETIME_MS = 180L * 24L * 60L * 60L * 1_000L

internal data class SimklOAuthToken(
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String?,
    val expiresInSeconds: Long?,
    val scope: String?,
)

internal class SimklOAuthClient(
    private val engine: SimklHttpEngine,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): SimklOAuthToken = tokenRequest(
        fields = linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to codeVerifier,
        ),
    )

    suspend fun refreshAccessToken(
        clientId: String,
        refreshToken: String,
    ): SimklOAuthToken = tokenRequest(
        fields = linkedMapOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
        ),
    )

    suspend fun revoke(
        clientId: String,
        token: String,
    ) {
        val response = postForm(
            path = "/oauth2/revoke",
            fields = linkedMapOf(
                "client_id" to clientId,
                "token" to token,
            ),
        )
        if (response.status !in 200..299) throw response.toOAuthException(json)
    }

    private suspend fun tokenRequest(fields: Map<String, String>): SimklOAuthToken {
        val response = postForm(path = "/oauth2/token", fields = fields)
        if (response.status !in 200..299) throw response.toOAuthException(json)
        val payload = runCatching { json.decodeFromString<SimklOAuthTokenResponse>(response.body) }
            .getOrNull()
            ?: throw SimklApiException(
                status = response.status,
                errorCode = "invalid_token_response",
                message = "Simkl returned an invalid OAuth token response",
            )
        return SimklOAuthToken(
            accessToken = payload.accessToken,
            refreshToken = payload.refreshToken,
            tokenType = payload.tokenType,
            expiresInSeconds = payload.expiresIn,
            scope = payload.scope,
        )
    }

    private suspend fun postForm(
        path: String,
        fields: Map<String, String>,
    ): RawHttpResponse = try {
        engine.execute(
            method = SimklHttpMethod.POST.name,
            url = "$SIMKL_API_BASE_URL$path",
            headers = simklRequestHeaders().toMutableMap().apply {
                put("Content-Type", "application/x-www-form-urlencoded")
            },
            body = encodeSimklOAuthForm(fields),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: SimklApiException) {
        throw error
    } catch (error: Throwable) {
        throw SimklApiException(
            status = null,
            errorCode = "transport_failure",
            message = "Simkl OAuth request failed",
            cause = error,
        )
    }
}

internal fun encodeSimklOAuthForm(fields: Map<String, String>): String =
    fields.entries.joinToString("&") { (key, value) ->
        "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
    }

internal fun hasRequiredSimklScope(scope: String?): Boolean {
    val values = scope.orEmpty()
        .split(' ')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
    return "media:read" in values && "media:write" in values
}

internal fun isValidSimklOAuthToken(
    token: SimklOAuthToken,
    existingRefreshToken: String? = null,
): Boolean = token.accessToken.isNotBlank() &&
    token.tokenType.equals("Bearer", ignoreCase = true) &&
    token.expiresInSeconds?.let { it > 0L } == true &&
    !(token.refreshToken?.takeIf(String::isNotBlank)
        ?: existingRefreshToken?.takeIf(String::isNotBlank)).isNullOrBlank() &&
    hasRequiredSimklScope(token.scope)

internal object SimklOAuthApi {
    val client: SimklOAuthClient by lazy {
        SimklOAuthClient(
            engine = SimklHttpEngine { method, url, headers, body ->
                httpRequestRaw(
                    method = method,
                    url = url,
                    headers = headers,
                    body = body,
                    maxResponseBodyBytes = 128 * 1024,
                )
            },
        )
    }
}

private fun RawHttpResponse.toOAuthException(json: Json): SimklApiException {
    val envelope = body.takeIf(String::isNotBlank)?.let { payload ->
        runCatching { json.decodeFromString<SimklOAuthErrorEnvelope>(payload) }.getOrNull()
    }
    return SimklApiException(
        status = status,
        errorCode = envelope?.error,
        message = envelope?.errorDescription?.takeIf(String::isNotBlank)
            ?: envelope?.message?.takeIf(String::isNotBlank)
            ?: envelope?.error?.takeIf(String::isNotBlank)
            ?: "Simkl OAuth request failed with HTTP $status",
    )
}

@Serializable
private data class SimklOAuthTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null,
)

@Serializable
private data class SimklOAuthErrorEnvelope(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val message: String? = null,
)
