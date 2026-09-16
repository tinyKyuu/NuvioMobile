package com.nuvio.app.core.network

import com.nuvio.app.features.addons.*
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.*
import com.nuvio.app.features.search.SearchEmptyStateReason
import com.nuvio.app.features.search.SearchRepositoryController
import com.nuvio.app.features.search.sectionFromPage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class NetworkRecoveryBoundaryTest {
    @Test
    fun `cold fresh provider publishes Home Search and selected Discover before missing manifest settles`(): Unit = runBlocking {
        mixedRecovery(warm = false, missing = true, selectReady = true)
    }

    @Test
    fun `warm fresh provider publishes before stale manifest and keeps selected pending Discover`(): Unit = runBlocking {
        mixedRecovery(warm = true, missing = false, selectReady = false)
    }

    @Test
    fun `warm fresh provider publishes before missing manifest and keeps selected ready Discover`(): Unit = runBlocking {
        mixedRecovery(warm = true, missing = true, selectReady = true)
    }

    private suspend fun mixedRecovery(warm: Boolean, missing: Boolean, selectReady: Boolean) {
        val fixture = Fixture(if (selectReady) "a" else "b")
        HomeRepository.clear()
        try {
            if (warm) {
                fixture.refresh(null)
                withTimeout(5_000) { HomeRepository.uiState.first { it.sections.size == 2 && !it.isLoading } }
            } else {
                // Establish the current query without loading any provider yet.
                fixture.search.search("query", listOf(addon("a").copy(manifest = null, isRefreshing = true)))
            }
            fixture.version = "fresh"
            fixture.addons = listOf(addon("a"), addon("b").let { it.copy(manifest = it.manifest.takeUnless { missing }) })
            val hold = CompletableDeferred<Boolean>()
            val attempted = CompletableDeferred<Set<String>>()
            val recovery = fixture.scope.async {
                runOrderedNetworkRecovery(1, 1, false, fixture.operations(
                    cache = mapOf(url("a") to CachedAddonManifest("{}", NOW - 1)),
                    transport = { assertEquals(url("b"), it); attempted.complete(setOf(it)); hold },
                ), isCurrent = { true }, onPhase = { _, _ -> })
            }
            assertEquals(setOf(url("b")), attempted.await())
            // This assertion fails on the reviewed implementation: A was never
            // admitted by the shared AddonRepository selection/collection operation.
            assertTrue(withTimeoutOrNull(1_000) {
                HomeRepository.uiState.first { it.sections.any { row -> row.items.any { item -> item.id == "a-fresh" } } }
                fixture.search.uiState.first { it.sections.any { row -> row.items.any { item -> item.id == "a-fresh" } } }
                true
            } == true, "Fresh A must publish while B's manifest is still held")
            assertFalse(hold.isCompleted)
            assertFalse(recovery.isCompleted)
            if (selectReady) {
                assertEquals(listOf("a-fresh"), fixture.search.discoverUiState.value.items.map { it.id })
            } else {
                assertEquals("b:movie:catalog", fixture.search.discoverUiState.value.selectedCatalogKey)
                assertEquals(listOf("b-warm"), fixture.search.discoverUiState.value.items.map { it.id })
                assertTrue(fixture.search.uiState.value.sections.any { it.items.single().id == "b-warm" })
            }
            hold.complete(false)
            assertEquals(NetworkRecoveryRunResult.Completed, recovery.await())
            assertEquals(null, fixture.passes.last())
        } finally {
            fixture.scope.cancel()
            HomeRepository.clear()
        }
    }

    @Test
    fun `all fresh manifests skip transport and reconcile full catalog state`(): Unit = runBlocking {
        val fixture = Fixture("a")
        HomeRepository.clear()
        try {
            val result = runOrderedNetworkRecovery(1, 1, false, fixture.operations(
                cache = listOf("a", "b").associate { url(it) to CachedAddonManifest("{}", NOW - 1) },
                transport = { error("Fresh manifests must not be fetched") },
            ), isCurrent = { true }, onPhase = { _, _ -> })
            assertEquals(NetworkRecoveryRunResult.Completed, result)
            assertEquals(listOf<Set<String>?>(null), fixture.passes)
            withTimeout(5_000) { HomeRepository.uiState.first { it.sections.size == 2 } }
        } finally { fixture.scope.cancel(); HomeRepository.clear() }
    }

    @Test
    fun `manual refresh fetches even fresh manifests and publishes first success while second is held`(): Unit = runBlocking {
        val fixture = Fixture("a")
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        HomeRepository.clear()
        try {
            val attempts = mutableListOf<String>()
            val run = fixture.scope.async {
                runOrderedNetworkRecovery(1, 9, true, fixture.operations(
                    cache = listOf("a", "b").associate { url(it) to CachedAddonManifest("{}", NOW - 1) },
                    transport = { attempts += it; if (it == url("a")) first else second },
                ), isCurrent = { true }, onPhase = { _, _ -> })
            }
            assertEquals(listOf(url("a"), url("b")), attempts)
            assertTrue(fixture.passes.isEmpty())
            first.complete(true)
            withTimeout(5_000) { HomeRepository.uiState.first { it.sections.isNotEmpty() } }
            assertFalse(run.isCompleted)
            assertEquals(setOf(url("a")), fixture.passes.first())
            second.complete(false)
            run.await()
            assertNull(fixture.passes.last())
        } finally { fixture.scope.cancel(); HomeRepository.clear() }
    }

    @Test
    fun `empty parsed Search success removes only its catalog while transport failure retains another`(): Unit = runBlocking {
        val fixture = Fixture("a")
        try {
            fixture.search.search("query", fixture.addons)
            assertEquals(2, fixture.search.uiState.value.sections.size)
            fixture.emptyProviders = setOf("a")
            fixture.failedProviders = setOf("b")
            fixture.search.refreshAfterRecovery(fixture.addons)
            assertEquals(listOf("b-warm"), fixture.search.uiState.value.sections.flatMap { it.items }.map { it.id })
            assertNull(fixture.search.uiState.value.emptyStateReason)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `all empty parsed Search successes clear old sections and publish NoResults`(): Unit = runBlocking {
        val fixture = Fixture("a")
        try {
            fixture.search.search("query", fixture.addons)
            fixture.emptyProviders = setOf("a", "b")
            fixture.search.refreshAfterRecovery(fixture.addons)
            assertTrue(fixture.search.uiState.value.sections.isEmpty())
            assertEquals(SearchEmptyStateReason.NoResults, fixture.search.uiState.value.emptyStateReason)
            assertNull(fixture.search.uiState.value.errorMessage)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `cold mixed empty and failed Search cannot claim all providers returned no results`() {
        val fixture = Fixture("a")
        try {
            fixture.emptyProviders = setOf("a")
            fixture.failedProviders = setOf("b")
            fixture.search.search("query", fixture.addons)
            assertTrue(fixture.search.uiState.value.sections.isEmpty())
            assertEquals(SearchEmptyStateReason.RequestFailed, fixture.search.uiState.value.emptyStateReason)
            assertEquals("Transport unavailable", fixture.search.uiState.value.errorMessage)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `empty Search with an unavailable manifest reports incomplete results rather than NoResults`() {
        val fixture = Fixture("a")
        try {
            fixture.addons = listOf(addon("a"), addon("b").copy(manifest = null, errorMessage = "Manifest unavailable"))
            fixture.emptyProviders = setOf("a")
            fixture.search.search("query", fixture.addons)
            assertTrue(fixture.search.uiState.value.sections.isEmpty())
            assertEquals(SearchEmptyStateReason.RequestFailed, fixture.search.uiState.value.emptyStateReason)
            assertEquals("Manifest unavailable", fixture.search.uiState.value.errorMessage)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `empty Search response publishes before another provider's delayed nonempty response`(): Unit = runBlocking {
        val fixture = Fixture("a")
        try {
            fixture.search.search("query", fixture.addons)
            fixture.version = "fresh"
            fixture.emptyProviders = setOf("a")
            val hold = CompletableDeferred<Unit>()
            fixture.searchDelays = mapOf("b" to hold)
            fixture.search.refreshAfterRecovery(fixture.addons)
            assertEquals(listOf("b-warm"), fixture.search.uiState.value.sections.flatMap { it.items }.map { it.id })
            assertTrue(fixture.search.uiState.value.isLoading)
            hold.complete(Unit)
            withTimeout(5_000) { fixture.search.uiState.first { !it.isLoading } }
            assertEquals(listOf("b-fresh"), fixture.search.uiState.value.sections.flatMap { it.items }.map { it.id })
            assertNull(fixture.search.uiState.value.emptyStateReason)
        } finally { fixture.scope.cancel() }
    }

    private class Fixture(selected: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var addons = listOf(addon("a"), addon("b"))
        var version = "warm"
        var emptyProviders = emptySet<String>()
        var failedProviders = emptySet<String>()
        var searchDelays = emptyMap<String, CompletableDeferred<Unit>>()
        val passes = mutableListOf<Set<String>?>()
        val search = SearchRepositoryController(
            scope = scope,
            loadPreferredCatalogKey = { "$selected:movie:catalog" },
            savePreferredCatalogKey = {},
            loadSearchSection = { request, _ ->
                val id = request.addon.manifest!!.id
                searchDelays[id]?.await()
                if (id in failedProviders) error("Transport unavailable")
                request.sectionFromPage(page(id, version, id in emptyProviders), "Fixture")
            },
            loadDiscoverPage = { source, _, _, _ -> page(source.manifestUrl.substringAfter("https://").substringBefore('.'), version) },
        )

        fun refresh(ready: Set<String>?) {
            passes += ready
            HomeRepository.refreshWithLoader(
                addonsForRecoveryPass(addons, ready), force = true, partial = ready != null,
                buildDefinitions = { list -> list.mapNotNull { addon ->
                    addon.manifest?.let { manifest ->
                        val catalog = manifest.catalogs.single()
                        HomeCatalogDefinition(
                            key = "${manifest.id}:movie:catalog", defaultTitle = "Fixture", catalogName = "Fixture",
                            addonName = manifest.name, manifestUrl = addon.manifestUrl, type = "movie", catalogId = "catalog",
                            supportsPagination = false, descriptorSignature = buildHomeCatalogDescriptorSignature(addon, manifest, catalog),
                        )
                    }
                } },
            ) { definition, _ ->
                val id = definition.manifestUrl.substringAfter("https://").substringBefore('.')
                HomeCatalogSection(definition.key, "Fixture", "", id, CatalogTarget.Addon(definition.manifestUrl, "movie", "catalog"), page(id, version).items)
            }
            search.refreshDiscover(addons, forceRefresh = true, readyManifestUrls = ready)
            search.search("query", addons, forceRefresh = true, readyManifestUrls = ready)
        }

        fun operations(
            cache: Map<String, CachedAddonManifest>,
            transport: (String) -> Deferred<Boolean>,
        ) = object : NetworkRecoveryOperations {
            override suspend fun recoverManifests(profileId: Int, generation: Long, forceAll: Boolean, onManifestRecovered: suspend (String) -> Unit): ManifestRecoveryOutcome {
                val result = recoverAddonManifestBatch(
                    addons, cache, NOW, forceAll, isCurrent = { true },
                    startRefresh = { manifestUrl ->
                        addons = addons.map { if (it.manifestUrl == manifestUrl) it.copy(isRefreshing = true) else it }
                        transport(manifestUrl)
                    },
                    onManifestRecovered = { manifestUrl ->
                        addons = addons.map { if (it.manifestUrl == manifestUrl) it.copy(isRefreshing = false) else it }
                        onManifestRecovered(manifestUrl)
                    },
                )
                addons = addons.map { it.copy(isRefreshing = false) }
                return ManifestRecoveryOutcome(result.attemptedUrls, result.recoveredUrls, result.failedUrls, result.stale)
            }
            override suspend fun refreshCatalogs(profileId: Int, generation: Long, readyManifestUrls: Set<String>?) = refresh(readyManifestUrls)
        }
    }

    companion object {
        private const val NOW = 30_000_000L
        private fun url(id: String) = "https://$id.example/manifest.json"
        private fun addon(id: String) = ManagedAddon(url(id), AddonManifest(
            id, id, "", "1", resources = emptyList(), types = listOf("movie"), transportUrl = url(id),
            catalogs = listOf(AddonCatalog("movie", "catalog", "Catalog", listOf(AddonExtraProperty("search")))),
        ))
        private fun page(id: String, version: String, empty: Boolean = false): CatalogPage {
            val payload = if (empty) """{"metas":[]}""" else """{"metas":[{"id":"$id-$version","type":"movie","name":"$id"}]}"""
            val parsed = HomeCatalogParser.parseCatalogResponse(payload)
            return CatalogPage(parsed.items, parsed.rawItemCount, null)
        }
    }
}
