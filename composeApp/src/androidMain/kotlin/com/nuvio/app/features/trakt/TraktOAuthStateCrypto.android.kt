package com.nuvio.app.features.trakt

import java.security.SecureRandom

internal actual object TraktOAuthStateCrypto {
    private val secureRandom = SecureRandom()

    actual fun secureRandomBytes(size: Int): ByteArray {
        require(size > 0)
        return ByteArray(size).also(secureRandom::nextBytes)
    }
}
