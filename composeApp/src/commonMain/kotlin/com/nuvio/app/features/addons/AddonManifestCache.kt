package com.nuvio.app.features.addons

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val ADDON_MANIFEST_CACHE_VERSION = 1
internal const val ADDON_MANIFEST_FRESHNESS_MS = 6L * 60L * 60L * 1_000L
internal const val ADDON_MANIFEST_CACHE_MAX_ENTRIES = 32
internal const val ADDON_MANIFEST_CACHE_MAX_ENTRY_BYTES = 512 * 1_024
internal const val ADDON_MANIFEST_CACHE_MAX_TOTAL_BYTES = 4 * 1_024 * 1_024
private const val ADDON_MANIFEST_CACHE_MAX_URL_BYTES = 4 * 1_024
private const val ADDON_MANIFEST_CACHE_SERIALIZATION_RESERVE_BYTES = 256 * 1_024
private const val ADDON_MANIFEST_CACHE_PAYLOAD_BUDGET_BYTES =
    ADDON_MANIFEST_CACHE_MAX_TOTAL_BYTES - ADDON_MANIFEST_CACHE_SERIALIZATION_RESERVE_BYTES

@Serializable
internal data class CachedAddonManifest(
    val payload: String,
    val fetchedAtEpochMs: Long,
)

@Serializable
private data class AddonManifestCacheBlob(
    val version: Int,
    val entries: Map<String, CachedAddonManifest> = emptyMap(),
)

internal object AddonManifestCacheCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(entries: Map<String, CachedAddonManifest>): String {
        var bounded = boundedAddonManifestCache(entries)
        while (true) {
            val encoded = json.encodeToString(
                AddonManifestCacheBlob.serializer(),
                AddonManifestCacheBlob(
                    version = ADDON_MANIFEST_CACHE_VERSION,
                    entries = bounded,
                ),
            )
            if (encoded.encodeToByteArray().size <= ADDON_MANIFEST_CACHE_MAX_TOTAL_BYTES || bounded.isEmpty()) {
                return encoded
            }
            bounded = bounded.entries
                .toList()
                .dropLast(1)
                .associateTo(linkedMapOf()) { it.toPair() }
        }
    }

    fun decode(blob: String?): Map<String, CachedAddonManifest> {
        if (blob.isNullOrBlank()) return emptyMap()
        if (blob.encodeToByteArray().size > ADDON_MANIFEST_CACHE_MAX_TOTAL_BYTES) return emptyMap()

        val decoded = runCatching {
            json.decodeFromString(AddonManifestCacheBlob.serializer(), blob)
        }.getOrNull() ?: return emptyMap()
        if (decoded.version != ADDON_MANIFEST_CACHE_VERSION) return emptyMap()
        return boundedAddonManifestCache(decoded.entries)
    }
}

internal fun CachedAddonManifest.isStale(nowEpochMs: Long): Boolean =
    fetchedAtEpochMs <= 0L ||
        fetchedAtEpochMs > nowEpochMs ||
        nowEpochMs - fetchedAtEpochMs >= ADDON_MANIFEST_FRESHNESS_MS

internal fun upsertCachedAddonManifest(
    entries: Map<String, CachedAddonManifest>,
    manifestUrl: String,
    payload: String,
    fetchedAtEpochMs: Long,
): Map<String, CachedAddonManifest> {
    if (manifestUrl.isBlank() || fetchedAtEpochMs <= 0L) return entries
    if (payload.isBlank() || payload.encodeToByteArray().size > ADDON_MANIFEST_CACHE_MAX_ENTRY_BYTES) {
        return entries
    }
    return boundedAddonManifestCache(
        entries + (
            manifestUrl to CachedAddonManifest(
                payload = payload,
                fetchedAtEpochMs = fetchedAtEpochMs,
            )
        ),
    )
}

internal fun selectAddonManifestRefreshUrls(
    addons: List<ManagedAddon>,
    cache: Map<String, CachedAddonManifest>,
    nowEpochMs: Long,
    forceAll: Boolean = false,
): Set<String> = addons
    .asSequence()
    .filter(ManagedAddon::enabled)
    .filter { addon ->
        forceAll ||
            addon.manifest == null ||
            addon.errorMessage != null ||
            cache[addon.manifestUrl]?.isStale(nowEpochMs) != false
    }
    .map(ManagedAddon::manifestUrl)
    .toCollection(linkedSetOf())

internal fun removeCachedAddonManifest(
    entries: Map<String, CachedAddonManifest>,
    manifestUrl: String,
): Map<String, CachedAddonManifest> = entries - manifestUrl

internal fun boundedAddonManifestCache(
    entries: Map<String, CachedAddonManifest>,
): Map<String, CachedAddonManifest> {
    var retainedBytes = 0
    val retained = linkedMapOf<String, CachedAddonManifest>()
    entries.entries
        .asSequence()
        .filter { (url, entry) ->
            url.isNotBlank() &&
                url.encodeToByteArray().size <= ADDON_MANIFEST_CACHE_MAX_URL_BYTES &&
                entry.fetchedAtEpochMs > 0L &&
                entry.payload.isNotBlank() &&
                entry.payload.encodeToByteArray().size <= ADDON_MANIFEST_CACHE_MAX_ENTRY_BYTES
        }
        .sortedWith(
            compareByDescending<Map.Entry<String, CachedAddonManifest>> { it.value.fetchedAtEpochMs }
                .thenBy { it.key },
        )
        .take(ADDON_MANIFEST_CACHE_MAX_ENTRIES)
        .forEach { (url, entry) ->
            val entryBytes = url.encodeToByteArray().size + entry.payload.encodeToByteArray().size
            if (retainedBytes + entryBytes <= ADDON_MANIFEST_CACHE_PAYLOAD_BUDGET_BYTES) {
                retained[url] = entry
                retainedBytes += entryBytes
            }
        }
    return retained
}
