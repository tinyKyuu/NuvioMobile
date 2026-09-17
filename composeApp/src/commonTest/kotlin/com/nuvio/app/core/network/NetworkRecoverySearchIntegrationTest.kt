package com.nuvio.app.core.network

import com.nuvio.app.features.addons.ManifestRecoveryEvent
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.search.DiscoverEmptyStateReason
import com.nuvio.app.features.search.SearchCatalogRequest
import com.nuvio.app.features.search.SearchEmptyStateReason
import com.nuvio.app.features.search.SearchRepositoryController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkRecoverySearchIntegrationTest {
    @Test
    fun `partial recovery preserves warm Search and selected Discover while stale provider hangs then fails`(): Unit = runBlocking {
        assertWarmRecovery(dropPendingManifest = false)
    }

    @Test
    fun `partial recovery retains cached sources when the selected provider manifest is temporarily missing`(): Unit = runBlocking {
        assertWarmRecovery(dropPendingManifest = true)
    }

    private suspend fun assertWarmRecovery(dropPendingManifest: Boolean) {
        val fixture = Fixture()
        try {
            fixture.seed()
            assertEquals(listOf("a-warm", "b-warm"), fixture.searchItems())
            assertEquals(listOf("b-warm"), fixture.discoverItems())

            fixture.recovering = true
            fixture.addons = fixture.addons.map { addon ->
                addon.copy(
                    isRefreshing = true,
                    manifest = addon.manifest.takeUnless { dropPendingManifest && addon.manifestUrl == B_URL },
                )
            }
            fixture.reconnect()
            withTimeout(5_000L) {
                fixture.repository.uiState.first { it.sections.firstOrNull()?.items?.firstOrNull()?.id == "a-fresh" }
            }
            assertFalse(fixture.manifestHold.isCompleted)
            assertEquals(listOf("a-fresh", "b-warm"), fixture.searchItems())
            assertEquals(B_KEY, fixture.repository.discoverUiState.value.selectedCatalogKey)
            assertEquals(listOf("b-warm"), fixture.discoverItems())
            assertEquals(setOf(A_URL, B_URL), fixture.repository.discoverUiState.value.catalogOptions.map { it.manifestUrl }.toSet())
            assertTrue(fixture.repository.discoverUiState.value.isLoading)
            assertNull(fixture.repository.uiState.value.emptyStateReason)
            assertNull(fixture.repository.discoverUiState.value.emptyStateReason)
            assertEquals(listOf(A_URL), fixture.recoverySearchRequests)
            assertTrue(fixture.recoveryDiscoverRequests.isEmpty())

            // Final reconciliation starts only after B's manifest failure. Its catalog
            // can still hang or fail, without evicting the already visible B results.
            fixture.manifestHold.complete(false)
            withTimeout(5_000L) {
                fixture.controller.uiState.first { it.phase == NetworkRecoveryPhase.Completed }
            }
            assertEquals(listOf(setOf(A_URL), null), fixture.passes)
            assertEquals(listOf("a-fresh", "b-warm"), fixture.searchItems())
            assertEquals(B_KEY, fixture.repository.discoverUiState.value.selectedCatalogKey)
            assertEquals(listOf("b-warm"), fixture.discoverItems())
            assertEquals(listOf(B_URL), fixture.recoveryDiscoverRequests)
            fixture.searchHold.complete(Unit)
            fixture.discoverHold.complete(Unit)
            withTimeout(5_000L) {
                fixture.repository.uiState.first { !it.isLoading }
                fixture.repository.discoverUiState.first { !it.isLoading }
            }
            assertEquals(listOf("a-fresh", "b-warm"), fixture.searchItems())
            assertEquals(listOf("b-warm"), fixture.discoverItems())
            assertNull(fixture.repository.uiState.value.emptyStateReason)
            assertNull(fixture.repository.discoverUiState.value.emptyStateReason)

            // A later valid full result replaces the retained data for the same query
            // and selection, rather than leaving a permanently stale cache.
            fixture.failSlowCatalogs = false
            fixture.addons = listOf(addon("a"), addon("b"))
            fixture.repository.refreshAfterRecovery(fixture.addons)
            withTimeout(5_000L) {
                fixture.repository.uiState.first { it.sections.lastOrNull()?.items?.firstOrNull()?.id == "b-fresh" }
                fixture.repository.discoverUiState.first { it.items.firstOrNull()?.id == "b-fresh" }
            }
            assertEquals(listOf("a-fresh", "b-fresh"), fixture.searchItems())
            assertEquals(B_KEY, fixture.repository.discoverUiState.value.selectedCatalogKey)

            // Retention is scoped to the query and enabled provider set.
            fixture.repository.search("different query", fixture.addons.filter { it.manifestUrl == A_URL })
            assertEquals(listOf("a-fresh"), fixture.searchItems())
            assertTrue(fixture.repository.uiState.value.sections.all { it.key.contains("different query") })
            fixture.repository.refreshDiscover(fixture.addons.filter { it.manifestUrl == A_URL })
            assertEquals("a:movie:catalog", fixture.repository.discoverUiState.value.selectedCatalogKey)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `pending providers prevent false empty states until final successful reconciliation`(): Unit = runBlocking {
        assertPendingRecovery(manifestSucceeds = true)
    }

    @Test
    fun `pending providers prevent false empty states until final failed reconciliation`(): Unit = runBlocking {
        assertPendingRecovery(manifestSucceeds = false)
    }

    @Test
    fun `known catalogs deferred by a partial pass stay loading until final reconciliation`() {
        val fixture = Fixture()
        try {
            val noCatalogAddon = addon("a", hasCatalog = false)
            fixture.repository.search("query", listOf(noCatalogAddon, addon("b").copy(manifest = null, isRefreshing = true)))
            fixture.recovering = true
            fixture.failSlowCatalogs = false
            fixture.addons = listOf(noCatalogAddon, addon("b"))
            fixture.repository.refreshAfterRecovery(fixture.addons, setOf(A_URL))
            assertTrue(fixture.repository.uiState.value.isLoading)
            assertTrue(fixture.repository.discoverUiState.value.isLoading)
            assertNull(fixture.repository.uiState.value.emptyStateReason)
            assertNull(fixture.repository.discoverUiState.value.emptyStateReason)
            assertTrue(fixture.recoverySearchRequests.isEmpty())
            assertTrue(fixture.recoveryDiscoverRequests.isEmpty())
            fixture.repository.refreshAfterRecovery(fixture.addons)
            assertEquals(listOf("b-fresh"), fixture.searchItems())
            assertEquals(listOf("b-fresh"), fixture.discoverItems())
        } finally {
            fixture.close()
        }
    }

    private suspend fun assertPendingRecovery(manifestSucceeds: Boolean) {
        val fixture = Fixture()
        try {
            fixture.addons = listOf(addon("a", hasCatalog = false), addon("b").copy(manifest = null, isRefreshing = true))
            fixture.repository.search("query", fixture.addons)
            fixture.repository.refreshDiscover(fixture.addons)
            fixture.recovering = true
            fixture.failSlowCatalogs = false
            fixture.reconnect()

            assertTrue(fixture.repository.uiState.value.isLoading)
            assertTrue(fixture.repository.discoverUiState.value.isLoading)
            assertNull(fixture.repository.uiState.value.emptyStateReason)
            assertNull(fixture.repository.discoverUiState.value.emptyStateReason)
            assertTrue(fixture.recoverySearchRequests.isEmpty())
            assertTrue(fixture.recoveryDiscoverRequests.isEmpty())

            fixture.manifestHold.complete(manifestSucceeds)
            withTimeout(5_000L) {
                fixture.controller.uiState.first { it.phase == NetworkRecoveryPhase.Completed }
                fixture.repository.uiState.first { !it.isLoading }
                fixture.repository.discoverUiState.first { !it.isLoading }
            }
            if (manifestSucceeds) {
                assertEquals(listOf("b-fresh"), fixture.searchItems())
                assertEquals(listOf("b-fresh"), fixture.discoverItems())
                assertNull(fixture.repository.uiState.value.emptyStateReason)
                assertNull(fixture.repository.discoverUiState.value.emptyStateReason)
                assertEquals(listOf(setOf(A_URL), setOf(B_URL), null), fixture.passes)
            } else {
                assertEquals(SearchEmptyStateReason.RequestFailed, fixture.repository.uiState.value.emptyStateReason)
                assertEquals(DiscoverEmptyStateReason.RequestFailed, fixture.repository.discoverUiState.value.emptyStateReason)
                assertEquals(listOf(setOf(A_URL), null), fixture.passes)
            }
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var addons = listOf(addon("a"), addon("b"))
        var recovering = false
        var failSlowCatalogs = true
        var preferredKey = B_KEY
        val manifestHold = CompletableDeferred<Boolean>()
        val searchHold = CompletableDeferred<Unit>()
        val discoverHold = CompletableDeferred<Unit>()
        val recoverySearchRequests = mutableListOf<String>()
        val recoveryDiscoverRequests = mutableListOf<String>()
        val passes = mutableListOf<Set<String>?>()
        val repository = SearchRepositoryController(
            scope = scope,
            loadPreferredCatalogKey = { preferredKey },
            savePreferredCatalogKey = { preferredKey = it },
            loadSearchSection = { request, _ ->
                if (recovering) {
                    recoverySearchRequests += request.addon.manifestUrl
                    if (request.addon.manifestUrl == B_URL && failSlowCatalogs) {
                        searchHold.await()
                        error("B search unavailable")
                    }
                }
                section(request, if (recovering) "fresh" else "warm")
            },
            loadDiscoverPage = { source, _, _, _ ->
                if (recovering) {
                    recoveryDiscoverRequests += source.manifestUrl
                    if (source.manifestUrl == B_URL && failSlowCatalogs) {
                        discoverHold.await()
                        error("B discover unavailable")
                    }
                }
                val id = "${if (source.manifestUrl == B_URL) "b" else "a"}-${if (recovering) "fresh" else "warm"}"
                CatalogPage(listOf(item(id)), rawItemCount = 1, nextSkip = null)
            },
        )
        val controller = NetworkRecoveryController(
            scope = scope,
            activeProfileId = { 1 },
            requestFreshProbe = { 3L },
            operations = object : NetworkRecoveryOperations {
                override suspend fun recoverManifests(
                    profileId: Int,
                    generation: Long,
                    forceAll: Boolean,
                    onManifestEvent: suspend (ManifestRecoveryEvent) -> Unit,
                ): ManifestRecoveryOutcome {
                    addons = addons.map { if (it.manifestUrl == A_URL) it.copy(isRefreshing = false) else it }
                    onManifestEvent(ManifestRecoveryEvent.CachedProviderAdmitted(A_URL))
                    val success = manifestHold.await()
                    addons = addons.map {
                        if (it.manifestUrl != B_URL) it
                        else if (success) addon("b")
                        else it.copy(isRefreshing = false, errorMessage = "B manifest unavailable")
                    }
                    if (success) onManifestEvent(ManifestRecoveryEvent.MissingProviderRecovered(B_URL))
                    return ManifestRecoveryOutcome(
                        recoveredUrls = if (success) setOf(A_URL, B_URL) else setOf(A_URL),
                        failedUrls = if (success) emptySet() else setOf(B_URL),
                    )
                }

                override suspend fun refreshCatalogs(
                    profileId: Int,
                    generation: Long,
                    readyManifestUrls: Set<String>?,
                ) {
                    passes += readyManifestUrls
                    repository.refreshAfterRecovery(addons, readyManifestUrls)
                }
            },
        )

        fun seed() {
            repository.search("query", addons)
            repository.refreshDiscover(addons)
        }

        fun reconnect() {
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.NoInternet, 1L))
            controller.onNetworkState(NetworkStatusUiState(NetworkCondition.Online, 2L))
        }

        fun searchItems() = repository.uiState.value.sections.flatMap { it.items }.map { it.id }
        fun discoverItems() = repository.discoverUiState.value.items.map { it.id }
        fun close() = scope.cancel()
    }

    companion object {
        private const val A_URL = "https://a.example/manifest.json"
        private const val B_URL = "https://b.example/manifest.json"
        private const val B_KEY = "b:movie:catalog"

        private fun addon(id: String, hasCatalog: Boolean = true): ManagedAddon {
            val url = "https://$id.example/manifest.json"
            return ManagedAddon(
                manifestUrl = url,
                manifest = AddonManifest(
                    id = id,
                    name = id,
                    description = "",
                    version = "1",
                    resources = emptyList(),
                    types = listOf("movie"),
                    catalogs = if (hasCatalog) listOf(
                        AddonCatalog("movie", "catalog", "Catalog", extra = listOf(AddonExtraProperty("search"))),
                    ) else emptyList(),
                    transportUrl = url,
                ),
            )
        }

        private fun item(id: String) = MetaPreview(id = id, type = "movie", name = id)

        private fun section(request: SearchCatalogRequest, version: String): HomeCatalogSection = HomeCatalogSection(
            key = "${request.addon.manifest!!.id}:${request.query}",
            title = request.catalogName,
            subtitle = "",
            addonName = request.addon.displayTitle,
            target = CatalogTarget.Addon(request.addon.manifestUrl, request.type, request.catalogId),
            items = listOf(item("${request.addon.manifest.id}-$version")),
        )
    }
}
