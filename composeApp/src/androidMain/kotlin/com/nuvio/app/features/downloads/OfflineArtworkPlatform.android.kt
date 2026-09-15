package com.nuvio.app.features.downloads

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File

internal actual object OfflineArtworkPlatform {
    private val client = OkHttpClient()
    private var directory: File? = null

    fun initialize(context: Context) {
        directory = context.filesDir.resolve("offline_library/artwork").apply { mkdirs() }
    }

    actual suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
    ): OfflineArtworkResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            val declaredLength = body.contentLength()
            if (declaredLength > maxBytes) {
                return@withContext OfflineArtworkResponse(statusCode = 413)
            }
            val bytes = if (response.code == 304) {
                byteArrayOf()
            } else {
                val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
                body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes) return@withContext OfflineArtworkResponse(statusCode = 413)
                        output.write(buffer, 0, read)
                    }
                }
                output.toByteArray()
            }
            OfflineArtworkResponse(
                statusCode = response.code,
                bytes = bytes,
                contentType = body.contentType()?.toString(),
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
            )
        }
    }

    actual fun save(assetKey: String, bytes: ByteArray): String? {
        val target = directory?.resolve(assetKey) ?: return null
        val temporary = target.parentFile?.resolve(".${target.name}.tmp") ?: return null
        return runCatching {
            target.parentFile?.mkdirs()
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target.toURI().toString()
        }.getOrNull()
    }

    actual fun localUri(assetKey: String): String? =
        directory?.resolve(assetKey)?.takeIf { it.isFile && it.length() > 0L }?.toURI()?.toString()

    actual fun delete(assetKey: String) {
        directory?.resolve(assetKey)?.delete()
    }

    actual fun deleteAll() {
        directory?.deleteRecursively()
        directory?.mkdirs()
    }
}
