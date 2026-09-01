package com.nuvio.app.features.downloads

import java.security.MessageDigest

internal actual object DownloadSourceFingerprint {
    actual fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString(separator = "") { byte ->
                byte.toUByte().toString(16).padStart(2, '0')
            }
}
