package com.nuvio.app.features.simkl

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

internal const val SIMKL_AUTHORIZATION_TIMEOUT_MS = 10L * 60L * 1_000L

internal fun parseSimklAuthCallback(
    callbackUrl: String,
    redirectUri: String,
): SimklAuthCallback {
    if (!callbackUrl.equals(redirectUri, ignoreCase = true) &&
        !callbackUrl.startsWith("$redirectUri?", ignoreCase = true)
    ) {
        return SimklAuthCallback.NotSimkl
    }
    val parsed = runCatching { Url(callbackUrl) }.getOrNull() ?: return SimklAuthCallback.Invalid
    val state = parsed.parameters["state"].orEmpty().trim()
    val issuer = parsed.parameters["iss"].orEmpty().trim()
    if (state.isBlank() || issuer.isBlank()) return SimklAuthCallback.Invalid

    val error = parsed.parameters["error"].orEmpty().trim()
    if (error.isNotBlank()) {
        return SimklAuthCallback.AuthorizationError(
            error = error,
            state = state,
            issuer = issuer,
        )
    }

    val code = parsed.parameters["code"].orEmpty().trim()
    if (code.isBlank()) return SimklAuthCallback.Invalid
    return SimklAuthCallback.AuthorizationCode(code = code, state = state, issuer = issuer)
}

internal fun isSimklAuthorizationExpired(
    startedAtEpochMs: Long?,
    nowEpochMs: Long,
): Boolean = startedAtEpochMs == null ||
    nowEpochMs < startedAtEpochMs ||
    nowEpochMs - startedAtEpochMs > SIMKL_AUTHORIZATION_TIMEOUT_MS

internal fun constantTimeEquals(left: String, right: String): Boolean {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    val length = maxOf(leftBytes.size, rightBytes.size)
    var difference = leftBytes.size xor rightBytes.size
    for (index in 0 until length) {
        val leftByte = leftBytes.getOrElse(index) { 0 }.toInt()
        val rightByte = rightBytes.getOrElse(index) { 0 }.toInt()
        difference = difference or (leftByte xor rightByte)
    }
    return difference == 0
}

internal fun buildSimklAuthorizationUrl(
    clientId: String,
    redirectUri: String,
    material: SimklPkceMaterial,
): String = buildString {
    append(SIMKL_AUTHORIZE_URL)
    append("?response_type=code")
    append("&scope=")
    append(SIMKL_REQUIRED_SCOPE.encodeURLParameter())
    append("&client_id=")
    append(clientId.encodeURLParameter())
    append("&redirect_uri=")
    append(redirectUri.encodeURLParameter())
    append("&code_challenge=")
    append(material.challenge.encodeURLParameter())
    append("&code_challenge_method=S256")
    append("&state=")
    append(material.state.encodeURLParameter())
}
