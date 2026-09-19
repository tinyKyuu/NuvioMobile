package com.nuvio.app.features.trakt

internal expect object TraktOAuthStateCrypto {
    fun secureRandomBytes(size: Int): ByteArray
}

internal fun generateTraktOauthState(): String =
    createTraktOauthState(TraktOAuthStateCrypto.secureRandomBytes(32))

internal fun createTraktOauthState(entropy: ByteArray): String {
    require(entropy.size >= 32) { "OAuth state entropy must be at least 32 bytes" }
    val alphabet = "0123456789abcdef"
    return buildString(entropy.size * 2) {
        entropy.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}
