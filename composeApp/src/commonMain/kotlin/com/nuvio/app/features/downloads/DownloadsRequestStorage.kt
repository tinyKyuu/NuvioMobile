package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class StoredDownloadRequest(
    val sourceUrl: String,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val providerAddonId: String? = null,
)

internal fun DownloadItem.toStoredDownloadRequest(): StoredDownloadRequest = StoredDownloadRequest(
    sourceUrl = sourceUrl,
    sourceHeaders = sourceHeaders,
    sourceResponseHeaders = sourceResponseHeaders,
    providerAddonId = providerAddonId,
)

internal fun DownloadRecord.withStoredDownloadRequest(request: StoredDownloadRequest): DownloadRecord = copy(
    item = item.copy(
        sourceUrl = request.sourceUrl,
        sourceHeaders = request.sourceHeaders,
        sourceResponseHeaders = request.sourceResponseHeaders,
        providerAddonId = request.providerAddonId,
    ),
)

internal object DownloadsRequestCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(request: StoredDownloadRequest): String = json.encodeToString(request)

    fun decodeOrNull(payload: String): StoredDownloadRequest? = runCatching {
        json.decodeFromString<StoredDownloadRequest>(payload)
    }.getOrNull()
}

internal expect object DownloadsRequestStorage {
    fun loadPayload(downloadId: String): String?

    fun savePayload(downloadId: String, payload: String): Boolean

    fun remove(downloadId: String)
}
