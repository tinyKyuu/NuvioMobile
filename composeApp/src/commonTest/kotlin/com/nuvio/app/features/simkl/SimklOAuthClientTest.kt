package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklOAuthClientTest {
    @Test
    fun `authorization exchange uses V2 PKCE form without a client secret`() = runBlocking {
        val engine = RecordingOAuthEngine(
            response(
                status = 200,
                body = """
                    {
                      "access_token":"simkl_at_access",
                      "refresh_token":"simkl_rt_refresh",
                      "token_type":"Bearer",
                      "expires_in":604800,
                      "scope":"media:read media:write"
                    }
                """.trimIndent(),
            ),
        )

        val token = SimklOAuthClient(engine).exchangeAuthorizationCode(
            clientId = "public client",
            code = "one+time/code",
            redirectUri = "com.tinykyuu.nuvio://auth/simkl",
            codeVerifier = "verifier value",
        )

        assertEquals("simkl_at_access", token.accessToken)
        assertEquals("simkl_rt_refresh", token.refreshToken)
        val request = engine.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.simkl.com/oauth2/token", request.url)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertFalse("Authorization" in request.headers)
        assertTrue("grant_type=authorization_code" in request.body)
        assertTrue("client_id=public" in request.body)
        assertTrue("code_verifier=verifier" in request.body)
        assertFalse("client_secret" in request.body)
    }

    @Test
    fun `refresh and revoke use V2 forms without exposing an authorization header`() = runBlocking {
        val engine = RecordingOAuthEngine(
            response(
                status = 200,
                body = """
                    {
                      "access_token":"simkl_at_replacement",
                      "token_type":"bearer",
                      "expires_in":604800,
                      "scope":"media:write media:read"
                    }
                """.trimIndent(),
            ),
            response(status = 200),
        )
        val client = SimklOAuthClient(engine)

        val token = client.refreshAccessToken("public-client", "simkl_rt_refresh")
        client.revoke("public-client", "simkl_rt_refresh")

        assertTrue(isValidSimklOAuthToken(token, existingRefreshToken = "simkl_rt_refresh"))
        assertEquals(
            listOf(
                "https://api.simkl.com/oauth2/token",
                "https://api.simkl.com/oauth2/revoke",
            ),
            engine.requests.map { it.url },
        )
        assertTrue("grant_type=refresh_token" in engine.requests[0].body)
        assertTrue("token=simkl_rt_refresh" in engine.requests[1].body)
        assertTrue(engine.requests.all { "Authorization" !in it.headers })
    }

    @Test
    fun `token validation requires bearer expiry refresh credential and both scopes`() {
        val valid = SimklOAuthToken(
            accessToken = "simkl_at_access",
            refreshToken = "simkl_rt_refresh",
            tokenType = "Bearer",
            expiresInSeconds = 604_800,
            scope = "media:write media:read",
        )

        assertTrue(isValidSimklOAuthToken(valid))
        assertFalse(isValidSimklOAuthToken(valid.copy(scope = "media:read")))
        assertFalse(isValidSimklOAuthToken(valid.copy(refreshToken = null)))
        assertFalse(isValidSimklOAuthToken(valid.copy(expiresInSeconds = 0)))
        assertFalse(isValidSimklOAuthToken(valid.copy(tokenType = "MAC")))
    }

    @Test
    fun `oauth failures keep provider status and error code`() = runBlocking {
        val engine = RecordingOAuthEngine(
            response(
                status = 400,
                body = """{"error":"invalid_grant","error_description":"Grant expired"}""",
            ),
        )

        val error = assertFailsWith<SimklApiException> {
            SimklOAuthClient(engine).refreshAccessToken("public-client", "expired-token")
        }

        assertEquals(400, error.status)
        assertEquals("invalid_grant", error.errorCode)
        assertEquals("Grant expired", error.message)
    }

    private class RecordingOAuthEngine(vararg responses: RawHttpResponse) : SimklHttpEngine {
        private val queuedResponses = responses.toMutableList()
        val requests = mutableListOf<RecordedOAuthRequest>()

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String,
        ): RawHttpResponse {
            requests += RecordedOAuthRequest(method, url, headers, body)
            return queuedResponses.removeAt(0)
        }
    }

    private data class RecordedOAuthRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private companion object {
        fun response(
            status: Int,
            body: String = "{}",
        ) = RawHttpResponse(
            status = status,
            statusText = "",
            url = "https://api.simkl.com/oauth2/test",
            body = body,
            headers = emptyMap(),
        )
    }
}
