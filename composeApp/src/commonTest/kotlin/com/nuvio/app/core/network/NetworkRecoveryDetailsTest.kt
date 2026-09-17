package com.nuvio.app.core.network

import com.nuvio.app.features.addons.ManifestRecoveryEvent
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsLoadResult
import com.nuvio.app.features.details.MetaDetailsRepositoryController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import kotlin.coroutines.CoroutineContext

class NetworkRecoveryDetailsTest {
    @Test
    fun `held pre-recovery details failure triggers one replacement without a screen retry`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.repo.load("movie", "title")
            fixture.repo.onRecoveryGeneration(1)
            fixture.finish(0, success = false)
            assertEquals(2, fixture.requests.size, "Recovery must survive the old in-flight request's failure")
            fixture.finish(1, success = true)
            fixture.awaitMeta("title")
            fixture.repo.onRecoveryGeneration(1)
            assertEquals(2, fixture.requests.size)
        } finally { fixture.close() }
    }

    @Test
    fun `held pre-recovery details success satisfies recovery without a duplicate fetch`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.repo.load("movie", "title")
            fixture.repo.onRecoveryGeneration(1)
            fixture.finish(0, success = true)
            fixture.awaitMeta("title")
            assertEquals(1, fixture.requests.size)
        } finally { fixture.close() }
    }

    @Test
    fun `recovery arriving during asynchronous failure presentation still retries once`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val messageReady = CompletableDeferred<Unit>()
        var attempts = 0
        val repo = MetaDetailsRepositoryController(
            scope = scope, activeProfileId = { 1 },
            metadataLoader = { _, id -> attempts++; MetaDetailsLoadResult(null, id) },
            captureSnapshot = { _, _, _ -> },
            failureMessage = { messageReady.await(); "Fixture failure" },
        )
        try {
            repo.load("movie", "title")
            assertTrue(repo.uiState.value.isLoading)
            repo.onRecoveryGeneration(1)
            messageReady.complete(Unit)
            assertEquals(2, attempts)
            assertFalse(repo.uiState.value.isLoading)
            repo.onRecoveryGeneration(1)
            assertEquals(2, attempts)
        } finally { scope.cancel() }
    }

    @Test
    fun `screen first opened during manifest recovery retries its delayed failure after final reconciliation`(): Unit = runBlocking {
        val fixture = Fixture()
        val manifests = CompletableDeferred<Unit>()
        val controller = NetworkRecoveryController(
            fixture.scope, activeProfileId = { fixture.profile }, requestFreshProbe = { 3L },
            operations = object : NetworkRecoveryOperations {
                override suspend fun recoverManifests(profileId: Int, generation: Long, forceAll: Boolean, onManifestEvent: suspend (ManifestRecoveryEvent) -> Unit): ManifestRecoveryOutcome {
                    manifests.await()
                    return ManifestRecoveryOutcome()
                }
                override suspend fun refreshCatalogs(profileId: Int, generation: Long, readyManifestUrls: Set<String>?) {
                    if (readyManifestUrls == null) fixture.repo.onRecoveryGeneration(generation)
                }
            },
        )
        try {
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.NoInternet, 1))
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.Online, 2))
            fixture.repo.load("movie", "title")
            assertEquals(NetworkRecoveryPhase.RestoringAddons, controller.uiState.value.phase)
            manifests.complete(Unit)
            assertEquals(NetworkRecoveryPhase.Completed, controller.uiState.value.phase)
            fixture.finish(0, success = false)
            assertEquals(2, fixture.requests.size)
            fixture.finish(1, success = true)
            fixture.awaitMeta("title")
        } finally { fixture.close() }
    }

    @Test
    fun `new load after reconciliation does not inherit a redundant retry`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.repo.onRecoveryGeneration(2)
            fixture.repo.load("movie", "title")
            fixture.repo.onRecoveryGeneration(2)
            fixture.finish(0, success = false)
            assertEquals(1, fixture.requests.size)
            assertFalse(fixture.repo.uiState.value.isLoading)
            assertNotNull(fixture.repo.uiState.value.errorMessage)
        } finally { fixture.close() }
    }

    @Test
    fun `new title or profile before queued recovery dispatch cannot inherit an old request retry`() {
        for (switchProfile in listOf(false, true)) {
            val dispatcher = QueuedDispatcher()
            val fixture = Fixture(dispatcher)
            try {
                fixture.repo.load("movie", "old")
                dispatcher.drain()
                fixture.repo.onRecoveryGeneration(1)
                if (switchProfile) {
                    fixture.profile = 2
                    fixture.repo.clear()
                }
                fixture.repo.load("movie", "new")
                dispatcher.drain()
                fixture.finish(0, success = false)
                fixture.finish(1, success = false)
                dispatcher.drain()
                assertEquals(2, fixture.requests.size)
                assertFalse(fixture.repo.uiState.value.isLoading)
                assertNotNull(fixture.repo.uiState.value.errorMessage)
                assertTrue(fixture.snapshots.isEmpty())
            } finally { fixture.close(); dispatcher.drain() }
        }
    }

    @Test
    fun `failed idle details recover once and failed follow-up cannot loop`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.repo.load("movie", "title")
            fixture.finish(0, success = false)
            fixture.repo.onRecoveryGeneration(1)
            assertEquals(2, fixture.requests.size)
            fixture.finish(1, success = false)
            fixture.repo.onRecoveryGeneration(1)
            assertEquals(2, fixture.requests.size)
            assertFalse(fixture.repo.uiState.value.isLoading)
        } finally { fixture.close() }
    }

    @Test
    fun `rapid recovery completions coalesce to one useful follow-up`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.repo.load("movie", "title")
            fixture.repo.onRecoveryGeneration(1)
            fixture.repo.onRecoveryGeneration(2)
            fixture.repo.onRecoveryGeneration(2)
            fixture.finish(0, success = false)
            assertEquals(2, fixture.requests.size)
            fixture.finish(1, success = false)
            assertEquals(2, fixture.requests.size)
            assertFalse(fixture.repo.uiState.value.isLoading)
        } finally { fixture.close() }
    }

    @Test
    fun `title change discards pending recovery and late success or failure`(): Unit = runBlocking {
        for (oldSuccess in listOf(false, true)) {
            val fixture = Fixture()
            try {
                fixture.repo.load("movie", "old")
                fixture.repo.onRecoveryGeneration(1)
                fixture.repo.load("movie", "new")
                fixture.finish(1, success = true)
                fixture.awaitMeta("new")
                fixture.finish(0, success = oldSuccess)
                assertEquals("new", fixture.repo.uiState.value.meta?.id)
                assertEquals(listOf("new"), fixture.snapshots.map { it.id })
                assertEquals(2, fixture.requests.size)
            } finally { fixture.close() }
        }
    }

    @Test
    fun `profile switch rejects old same-title completions and clears pending recovery`(): Unit = runBlocking {
        for (oldSuccess in listOf(false, true)) {
            val fixture = Fixture()
            try {
                fixture.repo.load("movie", "title")
                fixture.repo.onRecoveryGeneration(1)
                fixture.profile = 2
                fixture.repo.clear()
                fixture.repo.load("movie", "title")
                fixture.finish(1, success = true)
                fixture.awaitMeta("title")
                fixture.finish(0, success = oldSuccess)
                assertEquals("profile-2", fixture.repo.uiState.value.meta?.name)
                assertEquals(listOf("profile-2"), fixture.snapshots.map { it.name })
                assertEquals(2, fixture.requests.size)
            } finally { fixture.close() }
        }
    }

    @Test
    fun `warm metadata and its captured snapshot survive interrupted and failed recovery reloads`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.repo.load("movie", "title")
            fixture.finish(0, success = true)
            fixture.awaitMeta("title")
            fixture.repo.load("movie", "title", force = true)
            fixture.repo.onRecoveryGeneration(1)
            fixture.finish(1, success = false)
            assertEquals(3, fixture.requests.size)
            assertEquals("title", fixture.repo.uiState.value.meta?.id)
            fixture.finish(2, success = false)
            assertEquals("title", fixture.repo.uiState.value.meta?.id)
            assertEquals("title", fixture.repo.peek("movie", "title")?.id)
            assertEquals(1, fixture.snapshots.size)
            assertFalse(fixture.repo.uiState.value.isLoading)
        } finally { fixture.close() }
    }

    private class Fixture(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var profile = 1
        val requests = mutableListOf<Triple<String, Int, CompletableDeferred<Boolean>>>()
        val snapshots = mutableListOf<MetaDetails>()
        val repo = MetaDetailsRepositoryController(
            scope = scope,
            activeProfileId = { profile },
            metadataLoader = { type, id ->
                val owner = profile
                val hold = CompletableDeferred<Boolean>()
                requests += Triple(id, owner, hold)
                withContext(NonCancellable) {
                    if (hold.await()) MetaDetailsLoadResult(MetaDetails(id, type, "profile-$owner"), id)
                    else MetaDetailsLoadResult(null, id)
                }
            },
            captureSnapshot = { _, _, meta -> snapshots += meta },
            failureMessage = { "Fixture failure" },
        )
        fun finish(index: Int, success: Boolean) { requests[index].third.complete(success) }
        suspend fun awaitMeta(id: String) {
            withTimeout(5_000) { repo.uiState.first { !it.isLoading && it.meta?.id == id } }
        }
        fun close() {
            scope.cancel()
            requests.toList().forEach { it.third.complete(false) }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
}
