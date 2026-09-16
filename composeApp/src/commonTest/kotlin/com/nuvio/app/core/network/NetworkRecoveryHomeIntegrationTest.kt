package com.nuvio.app.core.network

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.collectManifestRecoveryResults
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogDefinition
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkRecoveryHomeIntegrationTest {
    @Test
    fun `healthy Home rows publish before stale provider catalog settles and final pass still runs`() = runBlocking {
        val healthyUrl = "https://healthy.example/manifest.json"
        val slowUrl = "https://slow.example/manifest.json"
        val healthyManifest = manifest(healthyUrl, "healthy")
        val staleSlowManifest = manifest(slowUrl, "slow")
        var addons = listOf(
            ManagedAddon(
                manifestUrl = healthyUrl,
                manifest = null,
                isRefreshing = true,
            ),
            ManagedAddon(
                manifestUrl = slowUrl,
                manifest = staleSlowManifest,
                isRefreshing = true,
            ),
        )
        val healthyManifestRequest = CompletableDeferred(true)
        val slowManifestRequest = CompletableDeferred<Boolean>()
        val slowCatalogStarted = CompletableDeferred<Unit>()
        val releaseSlowCatalog = CompletableDeferred<Unit>()
        val finalReconciliationStarted = CompletableDeferred<Unit>()

        HomeRepository.clear()
        try {
            val operations = object : NetworkRecoveryOperations {
                override suspend fun recoverManifests(
                    profileId: Int,
                    generation: Long,
                    forceAll: Boolean,
                    onManifestRecovered: suspend (String) -> Unit,
                ): ManifestRecoveryOutcome {
                    val result = collectManifestRecoveryResults(
                        requests = linkedMapOf(
                            healthyUrl to healthyManifestRequest,
                            slowUrl to slowManifestRequest,
                        ),
                        isCurrent = { true },
                        onManifestRecovered = { manifestUrl ->
                            addons = addons.map { addon ->
                                if (addon.manifestUrl == manifestUrl) {
                                    addon.copy(
                                        manifest = healthyManifest,
                                        isRefreshing = false,
                                    )
                                } else {
                                    addon
                                }
                            }
                            onManifestRecovered(manifestUrl)
                        },
                    )
                    addons = addons.map { addon ->
                        if (addon.manifestUrl == slowUrl) {
                            addon.copy(
                                isRefreshing = false,
                                errorMessage = "timeout",
                            )
                        } else {
                            addon
                        }
                    }
                    return ManifestRecoveryOutcome(
                        attemptedUrls = result.attemptedUrls,
                        recoveredUrls = result.recoveredUrls,
                        failedUrls = result.failedUrls,
                        stale = result.stale,
                    )
                }

                override suspend fun refreshCatalogs(
                    profileId: Int,
                    generation: Long,
                    readyManifestUrls: Set<String>?,
                ) {
                    val recoveryAddons = addonsForRecoveryPass(
                        addons = addons,
                        readyManifestUrls = readyManifestUrls,
                    )
                    if (readyManifestUrls == null) {
                        finalReconciliationStarted.complete(Unit)
                    }
                    HomeRepository.refreshWithLoader(
                        addons = recoveryAddons,
                        force = true,
                        buildDefinitions = { readyAddons ->
                            readyAddons.mapNotNull(::definition)
                        },
                    ) { definition, _ ->
                        if (definition.manifestUrl == slowUrl) {
                            slowCatalogStarted.complete(Unit)
                            releaseSlowCatalog.await()
                            emptySection(definition)
                        } else {
                            healthySection(definition)
                        }
                    }
                }
            }

            val recovery = async {
                runOrderedNetworkRecovery(
                    profileId = 1,
                    generation = 9L,
                    forceAllManifests = false,
                    operations = operations,
                    isCurrent = { true },
                    onPhase = { _, _ -> },
                )
            }

            val partialState = withTimeout(5_000L) {
                HomeRepository.uiState.first { state ->
                    state.sections.any { section -> section.items.any { item -> item.id == "healthy-item" } }
                }
            }
            assertTrue(partialState.sections.any { section -> section.items.any { it.id == "healthy-item" } })
            assertFalse(slowCatalogStarted.isCompleted)
            assertFalse(recovery.isCompleted)

            slowManifestRequest.complete(false)
            withTimeout(5_000L) {
                finalReconciliationStarted.await()
                slowCatalogStarted.await()
            }
            assertEquals(NetworkRecoveryRunResult.Completed, recovery.await())
            assertTrue(HomeRepository.uiState.value.sections.any { section ->
                section.items.any { item -> item.id == "healthy-item" }
            })

            releaseSlowCatalog.complete(Unit)
            withTimeout(5_000L) {
                HomeRepository.uiState.first { state -> !state.isLoading }
            }
            assertTrue(HomeRepository.uiState.value.sections.any { section ->
                section.items.any { item -> item.id == "healthy-item" }
            })
        } finally {
            releaseSlowCatalog.complete(Unit)
            HomeRepository.clear()
        }
    }

    private fun manifest(url: String, id: String): AddonManifest = AddonManifest(
        id = id,
        name = id,
        description = "",
        version = "1",
        resources = emptyList(),
        types = listOf("movie"),
        catalogs = listOf(
            AddonCatalog(
                type = "movie",
                id = "$id-catalog",
                name = id,
            ),
        ),
        transportUrl = url,
    )

    private fun healthySection(definition: HomeCatalogDefinition): HomeCatalogSection =
        section(
            definition = definition,
            items = listOf(
                MetaPreview(
                    id = "healthy-item",
                    type = "movie",
                    name = "Healthy item",
                ),
            ),
        )

    private fun definition(addon: ManagedAddon): HomeCatalogDefinition? {
        val manifest = addon.manifest ?: return null
        val catalog = manifest.catalogs.single()
        return HomeCatalogDefinition(
            key = "${manifest.id}:${catalog.type}:${catalog.id}",
            defaultTitle = catalog.name,
            catalogName = catalog.name,
            addonName = manifest.name,
            manifestUrl = addon.manifestUrl,
            type = catalog.type,
            catalogId = catalog.id,
            supportsPagination = false,
            descriptorSignature = "${addon.manifestUrl}:${addon.isRefreshing}:${addon.errorMessage}",
        )
    }

    private fun emptySection(definition: HomeCatalogDefinition): HomeCatalogSection =
        section(definition = definition, items = emptyList())

    private fun section(
        definition: HomeCatalogDefinition,
        items: List<MetaPreview>,
    ): HomeCatalogSection = HomeCatalogSection(
        key = definition.key,
        title = definition.defaultTitle,
        subtitle = definition.addonName,
        addonName = definition.addonName,
        target = CatalogTarget.Addon(
            manifestUrl = definition.manifestUrl,
            contentType = definition.type,
            catalogId = definition.catalogId,
            supportsPagination = definition.supportsPagination,
        ),
        items = items,
        availableItemCount = items.size,
        hasMore = false,
    )
}
