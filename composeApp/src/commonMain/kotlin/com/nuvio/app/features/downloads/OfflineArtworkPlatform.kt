package com.nuvio.app.features.downloads

internal data class OfflineArtworkResponse(
    val statusCode: Int,
    val bytes: ByteArray = byteArrayOf(),
    val contentType: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
)

internal expect object OfflineArtworkPlatform {
    suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
    ): OfflineArtworkResponse

    fun save(assetKey: String, bytes: ByteArray): String?
    fun localUri(assetKey: String): String?
    fun delete(assetKey: String)
    fun deleteAll()
}

internal fun validOfflineArtwork(
    response: OfflineArtworkResponse,
    maxBytes: Int,
): Boolean {
    if (response.statusCode !in 200..299) return false
    if (response.bytes.isEmpty() || response.bytes.size > maxBytes) return false
    val contentType = response.contentType?.substringBefore(';')?.trim()?.lowercase()
    if (contentType != null && !contentType.startsWith("image/")) return false
    return response.bytes.hasSupportedImageSignature()
}

internal fun offlineArtworkTemporaryName(assetKey: String, nonce: String): String =
    ".$assetKey.$nonce.tmp"

internal fun commitOfflineArtworkReplacement(
    writeTemporary: () -> Boolean,
    replaceAtomically: () -> Boolean,
    cleanupTemporary: () -> Unit,
): Boolean = try {
    writeTemporary() && replaceAtomically()
} finally {
    cleanupTemporary()
}

private fun ByteArray.hasSupportedImageSignature(): Boolean {
    if (size < 4) return false
    val isJpeg = this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()
    val isPng = size >= 8 &&
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() && this[2] == 0x4E.toByte() &&
        this[3] == 0x47.toByte() && this[4] == 0x0D.toByte() && this[5] == 0x0A.toByte() &&
        this[6] == 0x1A.toByte() && this[7] == 0x0A.toByte()
    val isGif = size >= 6 && decodeToString(0, 6) in setOf("GIF87a", "GIF89a")
    val isWebp = size >= 12 && decodeToString(0, 4) == "RIFF" && decodeToString(8, 12) == "WEBP"
    val isSvg = take(512).toByteArray().decodeToString().trimStart().let { prefix ->
        prefix.startsWith("<svg", ignoreCase = true) ||
            prefix.startsWith("<?xml", ignoreCase = true) && prefix.contains("<svg", ignoreCase = true)
    }
    return isJpeg || isPng || isGif || isWebp || isSvg
}
