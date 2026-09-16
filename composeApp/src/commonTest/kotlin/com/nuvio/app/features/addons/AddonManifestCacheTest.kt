package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonManifestCacheTest {
    @Test
    fun `cache round trips valid entries and rejects incompatible or malformed blobs`() {
        val entries = mapOf(
            "https://one.example/manifest.json" to CachedAddonManifest("{\"id\":\"one\"}", 100L),
        )
        val encoded = AddonManifestCacheCodec.encode(entries)

        assertEquals(entries, AddonManifestCacheCodec.decode(encoded))
        assertTrue(
            AddonManifestCacheCodec.decode(
                encoded.replaceFirst("\"version\":1", "\"version\":2"),
            ).isEmpty(),
        )
        assertTrue(AddonManifestCacheCodec.decode("not-json").isEmpty())
        assertTrue(AddonManifestCacheCodec.decode(null).isEmpty())
    }

    @Test
    fun `cache freshness changes at the exact six hour boundary`() {
        val fetchedAt = 1_000L
        val cached = CachedAddonManifest(payload = "{}", fetchedAtEpochMs = fetchedAt)

        assertFalse(cached.isStale(fetchedAt + ADDON_MANIFEST_FRESHNESS_MS - 1L))
        assertTrue(cached.isStale(fetchedAt + ADDON_MANIFEST_FRESHNESS_MS))
        assertTrue(cached.isStale(fetchedAt - 1L))
    }

    @Test
    fun `cache bounds entry count payload size and serialized size`() {
        val entries = (1..40).associate { index ->
            "https://$index.example/manifest.json" to CachedAddonManifest(
                payload = "x".repeat(ADDON_MANIFEST_CACHE_MAX_ENTRY_BYTES),
                fetchedAtEpochMs = index.toLong(),
            )
        }
        val bounded = boundedAddonManifestCache(entries)
        val encoded = AddonManifestCacheCodec.encode(entries)

        assertTrue(bounded.size <= ADDON_MANIFEST_CACHE_MAX_ENTRIES)
        assertTrue(encoded.encodeToByteArray().size <= ADDON_MANIFEST_CACHE_MAX_TOTAL_BYTES)
        assertEquals(bounded, AddonManifestCacheCodec.decode(encoded))
        assertEquals(
            emptyMap(),
            upsertCachedAddonManifest(
                entries = emptyMap(),
                manifestUrl = "https://oversized.example/manifest.json",
                payload = "x".repeat(ADDON_MANIFEST_CACHE_MAX_ENTRY_BYTES + 1),
                fetchedAtEpochMs = 1L,
            ),
        )
    }

    @Test
    fun `serialized escaping cannot push cache past total byte limit`() {
        val escapedPayload = "\\".repeat(ADDON_MANIFEST_CACHE_MAX_ENTRY_BYTES / 2)
        val entries = (1..16).associate { index ->
            "https://escaped-$index.example/manifest.json" to CachedAddonManifest(
                payload = escapedPayload,
                fetchedAtEpochMs = index.toLong(),
            )
        }

        val encoded = AddonManifestCacheCodec.encode(entries)
        val decoded = AddonManifestCacheCodec.decode(encoded)

        assertTrue(encoded.encodeToByteArray().size <= ADDON_MANIFEST_CACHE_MAX_TOTAL_BYTES)
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.size < entries.size)
    }

    @Test
    fun `startup selection hydrates fresh cache and refreshes missing stale or failed manifests`() {
        val now = ADDON_MANIFEST_FRESHNESS_MS + 10_000L
        val freshUrl = "https://fresh.example/manifest.json"
        val staleUrl = "https://stale.example/manifest.json"
        val missingUrl = "https://missing.example/manifest.json"
        val failedUrl = "https://failed.example/manifest.json"
        val disabledUrl = "https://disabled.example/manifest.json"
        val addons = listOf(
            managedAddon(freshUrl),
            managedAddon(staleUrl),
            ManagedAddon(manifestUrl = missingUrl, isRefreshing = true),
            managedAddon(failedUrl).copy(errorMessage = "temporary failure"),
            ManagedAddon(manifestUrl = disabledUrl, enabled = false),
        )
        val cache = mapOf(
            freshUrl to CachedAddonManifest("{}", now - ADDON_MANIFEST_FRESHNESS_MS + 1L),
            staleUrl to CachedAddonManifest("{}", now - ADDON_MANIFEST_FRESHNESS_MS),
            failedUrl to CachedAddonManifest("{}", now),
        )

        assertEquals(
            setOf(staleUrl, missingUrl, failedUrl),
            selectAddonManifestRefreshUrls(addons, cache, now),
        )
        assertEquals(
            setOf(freshUrl, staleUrl, missingUrl, failedUrl),
            selectAddonManifestRefreshUrls(addons, cache, now, forceAll = true),
        )
    }

    @Test
    fun `cold startup keeps stale manifest usable while scheduling validation`() {
        val url = "https://stale.example/manifest.json"
        val addon = managedAddon(url)
        val staleCache = mapOf(
            url to CachedAddonManifest(
                payload = "{}",
                fetchedAtEpochMs = 1L,
            ),
        )

        assertEquals(
            setOf(url),
            selectAddonManifestRefreshUrls(
                addons = listOf(addon),
                cache = staleCache,
                nowEpochMs = ADDON_MANIFEST_FRESHNESS_MS + 1L,
            ),
        )
        assertEquals(url, addon.manifest?.transportUrl)
    }

    @Test
    fun `cold startup without cache schedules missing manifest recovery`() {
        val url = "https://first-launch.example/manifest.json"
        val pending = ManagedAddon(manifestUrl = url, isRefreshing = true)

        assertEquals(
            setOf(url),
            selectAddonManifestRefreshUrls(
                addons = listOf(pending),
                cache = emptyMap(),
                nowEpochMs = 1L,
            ),
        )
    }

    @Test
    fun `cache removal and profile storage keys are isolated`() {
        val firstUrl = "https://one.example/manifest.json"
        val secondUrl = "https://two.example/manifest.json"
        val entry = CachedAddonManifest("{}", 1L)

        assertEquals(
            mapOf(secondUrl to entry),
            removeCachedAddonManifest(
                entries = mapOf(firstUrl to entry, secondUrl to entry),
                manifestUrl = firstUrl,
            ),
        )
        assertEquals("addon_manifest_cache_1", addonManifestCacheStorageKey(1))
        assertEquals("addon_manifest_cache_2", addonManifestCacheStorageKey(2))
        assertTrue(addonManifestCacheStorageKey(1) != addonManifestCacheStorageKey(2))
    }

    private fun managedAddon(url: String): ManagedAddon = ManagedAddon(
        manifestUrl = url,
        manifest = AddonManifest(
            id = url,
            name = url,
            description = "",
            version = "1",
            resources = emptyList(),
            types = emptyList(),
            transportUrl = url,
        ),
    )
}
