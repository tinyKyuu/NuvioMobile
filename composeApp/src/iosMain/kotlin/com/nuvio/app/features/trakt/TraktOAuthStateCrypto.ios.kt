package com.nuvio.app.features.trakt

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

internal actual object TraktOAuthStateCrypto {
    @OptIn(ExperimentalForeignApi::class)
    actual fun secureRandomBytes(size: Int): ByteArray {
        require(size > 0)
        val bytes = ByteArray(size)
        val status = SecRandomCopyBytes(kSecRandomDefault, size.toULong(), bytes.refTo(0))
        check(status == errSecSuccess) { "Secure random generation failed" }
        return bytes
    }
}
