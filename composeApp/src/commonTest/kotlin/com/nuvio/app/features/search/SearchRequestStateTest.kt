package com.nuvio.app.features.search

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.home.HomeCatalogSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchRequestStateTest {
    @Test
    fun `matching request reuses completed state`() {
        assertTrue(
            canReuseRequestState(
                forceRefresh = false,
                requestKey = "query:addons:settings",
                cachedRequestKey = "query:addons:settings",
            ),
        )
    }

    @Test
    fun `changed or forced request reloads state`() {
        assertFalse(
            canReuseRequestState(
                forceRefresh = false,
                requestKey = "new-query",
                cachedRequestKey = "old-query",
            ),
        )
        assertFalse(
            canReuseRequestState(
                forceRefresh = true,
                requestKey = "same-query",
                cachedRequestKey = "same-query",
            ),
        )
    }

    @Test
    fun `changed query clears prior rows while same request recovery retains them`() {
        val previousSection = HomeCatalogSection(
            key = "old-query",
            title = "Old query",
            subtitle = "",
            addonName = "Fixture",
            target = CatalogTarget.Addon(
                manifestUrl = "https://example.com/manifest.json",
                contentType = "movie",
                catalogId = "search",
            ),
            items = emptyList(),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val hold = CompletableDeferred<Unit>()
        var shouldHold = false
        val repository = SearchRepositoryController(
            scope = scope,
            loadSearchSection = { _, _ ->
                if (shouldHold) hold.await()
                previousSection
            },
        )
        val addons = listOf(
            ManagedAddon(
                manifestUrl = "https://example.com/manifest.json",
                manifest = AddonManifest(
                    id = "fixture", name = "Fixture", description = "", version = "1",
                    resources = emptyList(), types = listOf("movie"),
                    catalogs = listOf(AddonCatalog("movie", "search", "Search", listOf(AddonExtraProperty("search")))),
                    transportUrl = "https://example.com/manifest.json",
                ),
            ),
        )
        try {
            repository.search("old-query", addons)
            assertEquals(listOf(previousSection), repository.uiState.value.sections)
            shouldHold = true
            repository.search("old-query", addons, forceRefresh = true)
            assertEquals(listOf(previousSection), repository.uiState.value.sections)
            repository.search("old-query", addons.map { it.copy(manifest = null) })
            assertEquals(listOf(previousSection), repository.uiState.value.sections)
            repository.search("old-query", emptyList())
            assertTrue(repository.uiState.value.sections.isEmpty())
            assertEquals(SearchEmptyStateReason.NoActiveAddons, repository.uiState.value.emptyStateReason)
            shouldHold = false
            repository.search("old-query", addons)
            assertEquals(listOf(previousSection), repository.uiState.value.sections)
            shouldHold = true
            repository.search("new-query", addons)
            assertTrue(repository.uiState.value.sections.isEmpty())
            assertTrue(repository.uiState.value.isLoading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `preferred discover catalog is restored ahead of current fallback`() {
        val fallback = discoverCatalog(key = "fallback", type = "movie")
        val preferred = discoverCatalog(key = "preferred", type = "series")

        val selected = resolveDiscoverCatalog(
            sources = listOf(fallback, preferred),
            preferredCatalogKey = preferred.key,
            currentCatalogKey = fallback.key,
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `current discover catalog remains when preference is unavailable`() {
        val current = discoverCatalog(key = "current", type = "movie")

        val selected = resolveDiscoverCatalog(
            sources = listOf(discoverCatalog(key = "first", type = "movie"), current),
            preferredCatalogKey = "unavailable",
            currentCatalogKey = current.key,
        )

        assertEquals(current, selected)
    }

    private fun discoverCatalog(key: String, type: String): DiscoverCatalogOption =
        DiscoverCatalogOption(
            key = key,
            addonName = "Addon",
            manifestUrl = "https://example.com/manifest.json",
            type = type,
            catalogId = key,
            catalogName = key,
        )
}
