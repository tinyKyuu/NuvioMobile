package com.nuvio.app.features.addons

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class AddonManifestCacheOwner(val profileId: Int, val generation: Long)

internal class AddonManifestCacheStore(
    private val read: (Int) -> String?,
    private val write: (Int, String?) -> Unit,
) {
    private val lock = SynchronizedObject()
    private var currentOwner = AddonManifestCacheOwner(profileId = 1, generation = 0L)
    private var entries: Map<String, CachedAddonManifest> = emptyMap()

    val owner: AddonManifestCacheOwner
        get() = synchronized(lock) { currentOwner }

    fun switchProfile(profileId: Int, onSwitch: () -> Unit = {}) = synchronized(lock) {
        currentOwner = AddonManifestCacheOwner(profileId, currentOwner.generation + 1L)
        entries = emptyMap()
        onSwitch()
    }

    fun <T> withOwner(expected: AddonManifestCacheOwner, block: () -> T): T? = synchronized(lock) {
        if (currentOwner != expected) return@synchronized null
        block()
    }

    fun snapshot(expected: AddonManifestCacheOwner = owner): Map<String, CachedAddonManifest> =
        withOwner(expected) { entries } ?: emptyMap()

    fun load(expected: AddonManifestCacheOwner) = withOwner(expected) {
        entries = AddonManifestCacheCodec.decode(read(expected.profileId))
    }

    fun update(
        expected: AddonManifestCacheOwner,
        transform: (Map<String, CachedAddonManifest>) -> Map<String, CachedAddonManifest>,
    ): Boolean = withOwner(expected) {
        val updated = transform(entries)
        if (updated != entries) {
            val payload = updated.takeIf { it.isNotEmpty() }?.let(AddonManifestCacheCodec::encode)
            write(expected.profileId, payload)
            entries = updated
        }
        true
    } ?: false

    fun upsert(expected: AddonManifestCacheOwner, manifestUrl: String, payload: String, fetchedAtEpochMs: Long): Boolean =
        update(expected) { current ->
            upsertCachedAddonManifest(current, manifestUrl, payload, fetchedAtEpochMs)
        }

    fun deleteProfile(profileId: Int) = synchronized(lock) {
        if (currentOwner.profileId == profileId) {
            currentOwner = currentOwner.copy(generation = currentOwner.generation + 1L)
            entries = emptyMap()
        }
        write(profileId, null)
    }
}
