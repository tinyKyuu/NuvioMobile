package com.nuvio.app.features.addons

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonManifestCacheStoreTest {
    @Test
    fun `concurrent provider completions retain both entries in memory and storage`() = runBlocking {
        val persisted = mutableMapOf<Int, String?>()
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val secondCompletionStarted = CompletableDeferred<Unit>()
        val store = AddonManifestCacheStore(
            read = { persisted[it] },
            write = { profile, payload ->
                if (!firstWriteStarted.isCompleted) {
                    firstWriteStarted.complete(Unit)
                    runBlocking { withTimeout(5_000L) { releaseFirstWrite.await() } }
                }
                persisted[profile] = payload
            },
        )
        val owner = store.owner
        val first = async(Dispatchers.Default) { store.upsert(owner, "one", "one-payload", 1L) }
        try {
            withTimeout(5_000L) { firstWriteStarted.await() }
            val second = async(Dispatchers.Default) {
                secondCompletionStarted.complete(Unit)
                store.upsert(owner, "two", "two-payload", 2L)
            }
            withTimeout(5_000L) { secondCompletionStarted.await() }
            assertFalse(second.isCompleted)
            releaseFirstWrite.complete(Unit)
            assertTrue(withTimeout(5_000L) { first.await() })
            assertTrue(withTimeout(5_000L) { second.await() })
            val expected = mapOf(
                "one" to CachedAddonManifest("one-payload", 1L),
                "two" to CachedAddonManifest("two-payload", 2L),
            )
            assertEquals(expected, store.snapshot(owner))
            assertEquals(expected, AddonManifestCacheCodec.decode(persisted[1]))
        } finally {
            releaseFirstWrite.complete(Unit)
        }
    }

    @Test
    fun `profile switch during persistence cannot redirect an old completion`() = runBlocking {
        val newProfileCache = mapOf("new" to CachedAddonManifest("new-payload", 7L))
        val persisted = mutableMapOf<Int, String?>(2 to AddonManifestCacheCodec.encode(newProfileCache))
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val switchAttempted = CompletableDeferred<Unit>()
        val store = AddonManifestCacheStore(
            read = { persisted[it] },
            write = { profile, payload ->
                writeStarted.complete(Unit)
                runBlocking { withTimeout(5_000L) { releaseWrite.await() } }
                persisted[profile] = payload
            },
        )
        val oldOwner = store.owner
        val completion = async(Dispatchers.Default) { store.upsert(oldOwner, "old", "old-payload", 1L) }
        try {
            withTimeout(5_000L) { writeStarted.await() }
            val switching = async(Dispatchers.Default) {
                switchAttempted.complete(Unit)
                store.switchProfile(2)
                store.load(store.owner)
            }
            withTimeout(5_000L) { switchAttempted.await() }
            assertFalse(switching.isCompleted)
            releaseWrite.complete(Unit)
            assertTrue(withTimeout(5_000L) { completion.await() })
            withTimeout(5_000L) { switching.await() }
            assertEquals(mapOf("old" to CachedAddonManifest("old-payload", 1L)), AddonManifestCacheCodec.decode(persisted[1]))
            assertEquals(newProfileCache, AddonManifestCacheCodec.decode(persisted[2]))
            assertEquals(newProfileCache, store.snapshot())
            assertFalse(store.upsert(oldOwner, "late", "late-payload", 3L))
            assertEquals(newProfileCache, AddonManifestCacheCodec.decode(persisted[2]))
        } finally {
            releaseWrite.complete(Unit)
        }
    }

    @Test
    fun `profile switch before completion rejects captured ownership without persisting`() = runBlocking {
        val oldCache = mapOf("old" to CachedAddonManifest("old-payload", 1L))
        val newCache = mapOf("new" to CachedAddonManifest("new-payload", 2L))
        val persisted = mutableMapOf<Int, String?>(
            1 to AddonManifestCacheCodec.encode(oldCache),
            2 to AddonManifestCacheCodec.encode(newCache),
        )
        var writes = 0
        val store = AddonManifestCacheStore(
            read = { persisted[it] },
            write = { profile, payload -> writes++; persisted[profile] = payload },
        )
        store.load(store.owner)
        val capturedOwner = store.owner
        val fetched = CompletableDeferred<Unit>()
        val finishCompletion = CompletableDeferred<Unit>()
        val completion = async(Dispatchers.Default) {
            fetched.complete(Unit)
            finishCompletion.await()
            store.upsert(capturedOwner, "late", "late-payload", 3L)
        }
        withTimeout(5_000L) { fetched.await() }
        store.switchProfile(2)
        store.load(store.owner)
        finishCompletion.complete(Unit)
        assertFalse(withTimeout(5_000L) { completion.await() })
        assertEquals(0, writes)
        assertEquals(oldCache, AddonManifestCacheCodec.decode(persisted[1]))
        assertEquals(newCache, AddonManifestCacheCodec.decode(persisted[2]))
        assertEquals(newCache, store.snapshot())
    }
}
