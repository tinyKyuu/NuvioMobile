@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.features.downloads

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.readByteArray
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.rename

internal actual object OfflineArtworkPlatform {
    private val client = HttpClient(Darwin) { expectSuccess = false }
    private val directory = "${NSHomeDirectory()}/Library/Application Support/NuvioOfflineLibrary/Artwork"

    actual suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
    ): OfflineArtworkResponse {
        val response = client.get(url) {
            headers.forEach { (name, value) -> header(name, value) }
        }
        val declaredLength = response.headers["Content-Length"]?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxBytes) {
            return OfflineArtworkResponse(statusCode = 413)
        }
        val bytes = if (response.status.value == 304) {
            byteArrayOf()
        } else {
            response.bodyAsChannel()
                .readRemaining((maxBytes + 1L).coerceAtLeast(1L))
                .readByteArray()
        }
        if (bytes.size > maxBytes) return OfflineArtworkResponse(statusCode = 413)
        return OfflineArtworkResponse(
            statusCode = response.status.value,
            bytes = bytes,
            contentType = response.headers["Content-Type"],
            etag = response.headers["ETag"],
            lastModified = response.headers["Last-Modified"],
        )
    }

    actual fun save(assetKey: String, bytes: ByteArray): String? {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val target = "$directory/$assetKey"
        val temporary = "$directory/${offlineArtworkTemporaryName(assetKey, NSUUID().UUIDString)}"
        val replaced = commitOfflineArtworkReplacement(
            writeTemporary = { bytes.writeToFile(temporary) },
            replaceAtomically = { rename(temporary, target) == 0 },
            cleanupTemporary = { NSFileManager.defaultManager.removeItemAtPath(temporary, null) },
        )
        return if (replaced) {
            NSURL.fileURLWithPath(target).absoluteString ?: "file://$target"
        } else {
            null
        }
    }

    actual fun localUri(assetKey: String): String? {
        val path = "$directory/$assetKey"
        val fileSize = NSFileManager.defaultManager
            .attributesOfItemAtPath(path, error = null)
            ?.get("NSFileSize")
            ?.let { value -> (value as? Number)?.toLong() }
        return if (fileSize != null && fileSize > 0L) {
            NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
        } else {
            null
        }
    }

    actual fun delete(assetKey: String) {
        NSFileManager.defaultManager.removeItemAtPath("$directory/$assetKey", null)
    }

    actual fun deleteAll() {
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
    }

    private fun ByteArray.writeToFile(path: String): Boolean {
        val file = fopen(path, "wb") ?: return false
        return try {
            if (isEmpty()) return true
            usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.convert(), size.convert(), file).toLong() == size.toLong()
            }
        } finally {
            fclose(file)
        }
    }
}
