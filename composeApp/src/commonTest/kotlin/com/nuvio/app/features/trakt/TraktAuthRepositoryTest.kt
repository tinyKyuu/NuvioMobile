package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TraktAuthRepositoryTest {

    @Test
    fun `callback parser accepts an exact authorization response`() {
        assertEquals(
            TraktAuthCallback.AuthorizationCode(code = "code", state = "state"),
            parseTraktAuthCallback(
                callbackUrl = "nuvio://auth/trakt?code=code&state=state",
                redirectUri = "nuvio://auth/trakt",
            ),
        )
    }

    @Test
    fun `callback parser keeps provider errors behind state validation`() {
        assertEquals(
            TraktAuthCallback.ProviderError(description = "Denied", state = "state"),
            parseTraktAuthCallback(
                callbackUrl = "nuvio://auth/trakt?error=access_denied&error_description=Denied&state=state",
                redirectUri = "nuvio://auth/trakt",
            ),
        )
    }

    @Test
    fun `callback parser rejects other routes and callbacks without state`() {
        assertIs<TraktAuthCallback.NotTrakt>(
            parseTraktAuthCallback(
                callbackUrl = "nuvio://auth/simkl?code=code&state=state",
                redirectUri = "nuvio://auth/trakt",
            ),
        )
        assertIs<TraktAuthCallback.Invalid>(
            parseTraktAuthCallback(
                callbackUrl = "nuvio://auth/trakt?code=code",
                redirectUri = "nuvio://auth/trakt",
            ),
        )
    }

    @Test
    fun `callback state requires an exact pending authorization`() {
        assertEquals(true, isTraktCallbackStateValid("state", "state"))
        assertEquals(false, isTraktCallbackStateValid("state", "other"))
        assertEquals(false, isTraktCallbackStateValid("state", null))
        assertEquals(false, isTraktCallbackStateValid("state", ""))
    }

    @Test
    fun `HTTP 400 permanently invalidates a rejected refresh token`() {
        assertEquals(
            TraktTokenRefreshResponseAction.INVALIDATE,
            traktTokenRefreshResponseAction(400),
        )
    }

    @Test
    fun `successful token responses are accepted`() {
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(200),
        )
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(201),
        )
    }

    @Test
    fun `non-400 failures preserve credentials for a later attempt`() {
        listOf(401, 429, 500, 503).forEach { status ->
            assertEquals(
                TraktTokenRefreshResponseAction.TRANSIENT_FAILURE,
                traktTokenRefreshResponseAction(status),
            )
        }
    }
}
