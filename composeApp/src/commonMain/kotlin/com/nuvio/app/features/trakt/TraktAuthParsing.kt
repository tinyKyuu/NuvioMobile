package com.nuvio.app.features.trakt

import io.ktor.http.Url

internal sealed interface TraktAuthCallback {
    val state: String

    data class AuthorizationCode(
        val code: String,
        override val state: String,
    ) : TraktAuthCallback

    data class ProviderError(
        val description: String?,
        override val state: String,
    ) : TraktAuthCallback

    data object Invalid : TraktAuthCallback {
        override val state: String = ""
    }

    data object NotTrakt : TraktAuthCallback {
        override val state: String = ""
    }
}

internal fun parseTraktAuthCallback(
    callbackUrl: String,
    redirectUri: String,
): TraktAuthCallback {
    if (callbackUrl != redirectUri &&
        !callbackUrl.startsWith("$redirectUri?")
    ) {
        return TraktAuthCallback.NotTrakt
    }

    val parsed = runCatching { Url(callbackUrl) }.getOrNull()
        ?: return TraktAuthCallback.Invalid
    val state = parsed.parameters["state"].orEmpty().trim()
    if (state.isBlank()) return TraktAuthCallback.Invalid

    val error = parsed.parameters["error"].orEmpty().trim()
    if (error.isNotBlank()) {
        return TraktAuthCallback.ProviderError(
            description = parsed.parameters["error_description"]?.trim()?.takeIf(String::isNotBlank),
            state = state,
        )
    }

    val code = parsed.parameters["code"].orEmpty().trim()
    if (code.isBlank()) return TraktAuthCallback.Invalid
    return TraktAuthCallback.AuthorizationCode(code = code, state = state)
}

internal fun isTraktCallbackStateValid(
    callbackState: String,
    expectedState: String?,
): Boolean = !expectedState.isNullOrBlank() && callbackState == expectedState

internal const val TRAKT_AUTHORIZATION_TIMEOUT_MS = 10L * 60L * 1_000L

internal fun isTraktAuthorizationExpired(
    startedAtEpochMs: Long?,
    nowEpochMs: Long,
): Boolean = startedAtEpochMs == null ||
    nowEpochMs < startedAtEpochMs ||
    nowEpochMs - startedAtEpochMs >= TRAKT_AUTHORIZATION_TIMEOUT_MS
