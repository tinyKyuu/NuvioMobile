package com.nuvio.app.core.network

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.CachedAddonManifest
import com.nuvio.app.features.addons.collectManifestRecoveryResults
import com.nuvio.app.features.addons.ManifestRefreshOutcome
import com.nuvio.app.features.addons.ManifestRecoveryEvent
import com.nuvio.app.features.addons.selectAddonManifestRefreshUrls
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogDefinition
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.buildHomeCatalogDescriptorSignature
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
        val healthyManifestRequest = CompletableDeferred(ManifestRefreshOutcome.Changed)
        val slowManifestRequest = CompletableDeferred<ManifestRefreshOutcome>()
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
                    onManifestEvent: suspend (ManifestRecoveryEvent) -> Unit,
                ): ManifestRecoveryOutcome {
                    val result = collectManifestRecoveryResults(
                        requests = linkedMapOf(
                            healthyUrl to healthyManifestRequest,
                            slowUrl to slowManifestRequest,
                        ),
                        isCurrent = { true },
                        initiallyMissingUrls = setOf(healthyUrl),
                        onManifestEvent = { event ->
                            addons = addons.map { addon ->
                                if (addon.manifestUrl == event.manifestUrl) {
                                    addon.copy(
                                        manifest = healthyManifest,
                                        isRefreshing = false,
                                    )
                                } else {
                                    addon
                                }
                            }
                            onManifestEvent(event)
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
                        partial = readyManifestUrls != null,
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

        slowManifestRequest.complete(ManifestRefreshOutcome.Failed)
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

    @Test
    fun `fresh cached manifests publish healthy Home row while another catalog hangs`(): Unit = runBlocking {
        val addons = listOf("healthy", "slow").map { id ->
            val url = "https://$id.example/manifest.json"
            ManagedAddon(manifestUrl = url, manifest = manifest(url, id))
        }
        val freshCache = addons.associate { it.manifestUrl to CachedAddonManifest("{}", 1_000L) }
        val slowStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        var partialPasses = 0
        HomeRepository.clear()
        try {
            val outcome = runOrderedNetworkRecovery(
                profileId = 1,
                generation = 10L,
                forceAllManifests = false,
                operations = object : NetworkRecoveryOperations {
                    override suspend fun recoverManifests(
                        profileId: Int,
                        generation: Long,
                        forceAll: Boolean,
                        onManifestEvent: suspend (ManifestRecoveryEvent) -> Unit,
                    ): ManifestRecoveryOutcome {
                        val attempted = selectAddonManifestRefreshUrls(addons, freshCache, 1_001L)
                        assertTrue(attempted.isEmpty())
                        return ManifestRecoveryOutcome(attemptedUrls = attempted)
                    }

                    override suspend fun refreshCatalogs(
                        profileId: Int,
                        generation: Long,
                        readyManifestUrls: Set<String>?,
                    ) {
                        if (readyManifestUrls != null) partialPasses++
                        HomeRepository.refreshWithLoader(
                            addons = addonsForRecoveryPass(addons, readyManifestUrls),
                            force = true,
                            partial = readyManifestUrls != null,
                            buildDefinitions = { it.mapNotNull(::definition) },
                        ) { definition, _ ->
                            if (definition.addonName == "slow") {
                                slowStarted.complete(Unit)
                                releaseSlow.await()
                                emptySection(definition)
                            } else {
                                healthySection(definition)
                            }
                        }
                    }
                },
                isCurrent = { true },
                onPhase = { _, _ -> },
            )
            assertEquals(NetworkRecoveryRunResult.Completed, outcome)
            assertEquals(0, partialPasses)
            withTimeout(5_000L) {
                slowStarted.await()
                HomeRepository.uiState.first { it.sections.any { row -> row.items.any { it.id == "healthy-item" } } }
            }
            assertFalse(releaseSlow.isCompleted)
            assertTrue(HomeRepository.uiState.value.isLoading)
            releaseSlow.complete(Unit)
            withTimeout(5_000L) { HomeRepository.uiState.first { !it.isLoading } }
        } finally {
            releaseSlow.complete(Unit)
            HomeRepository.clear()
        }
    }

    @Test
    fun `pending provider warm row survives partial recovery and hanging then failed final refresh`() = runBlocking {
        val addons = listOf("healthy", "pending").map { id ->
            val url = "https://$id.example/manifest.json"
            ManagedAddon(manifestUrl = url, manifest = manifest(url, id))
        }
        val releasePartial = CompletableDeferred<Unit>()
        val partialStarted = CompletableDeferred<Unit>()
        val releaseFinal = CompletableDeferred<Unit>()
        val finalStarted = CompletableDeferred<Unit>()
        fun assertWarmRow() = assertTrue(HomeRepository.uiState.value.sections.any { row ->
            row.items.any { it.id == "pending-warm" }
        })
        fun assertWarmHeroMetadata() {
            val item = HomeRepository.uiState.value.sections
                .flatMap(HomeCatalogSection::items)
                .single { it.id == "pending-warm" }
            assertEquals("https://pending.example/banner.jpg", item.banner)
            assertEquals("https://pending.example/poster.jpg", item.poster)
        }
        HomeRepository.clear()
        try {
            HomeRepository.refreshWithLoader(
                addons = addons,
                force = true,
                buildDefinitions = { it.mapNotNull(::definition) },
            ) { definition, _ ->
                section(
                    definition,
                    listOf(
                        MetaPreview(
                            id = "${definition.addonName}-warm",
                            type = "movie",
                            name = "Warm item",
                            banner = "https://${definition.addonName}.example/banner.jpg",
                            poster = "https://${definition.addonName}.example/poster.jpg",
                        ),
                    ),
                )
            }
            withTimeout(5_000L) { HomeRepository.uiState.first { !it.isLoading && it.sections.size == 2 } }
            assertWarmRow()
            assertWarmHeroMetadata()

            val pendingAddons = addons.map { if (it.manifest?.id == "pending") it.copy(isRefreshing = true) else it }
            HomeRepository.refreshWithLoader(
                addons = addonsForRecoveryPass(pendingAddons, setOf(addons.first().manifestUrl)),
                force = true,
                partial = true,
                buildDefinitions = { it.mapNotNull(::definition) },
            ) { definition, _ ->
                partialStarted.complete(Unit)
                releasePartial.await()
                healthySection(definition)
            }
            withTimeout(5_000L) { partialStarted.await() }
            assertWarmRow()
            assertWarmHeroMetadata()
            releasePartial.complete(Unit)
            withTimeout(5_000L) { HomeRepository.uiState.first { !it.isLoading } }
            assertWarmRow()
            assertWarmHeroMetadata()

            val failedAddons = pendingAddons.map {
                if (it.isRefreshing) it.copy(isRefreshing = false, errorMessage = "manifest timeout") else it
            }
            HomeRepository.refreshWithLoader(
                addons = addonsForRecoveryPass(failedAddons, null),
                force = true,
                buildDefinitions = { it.mapNotNull(::definition) },
            ) { definition, _ ->
                if (definition.addonName == "pending") {
                    finalStarted.complete(Unit)
                    releaseFinal.await()
                    error("catalog timeout")
                }
                healthySection(definition)
            }
            withTimeout(5_000L) { finalStarted.await() }
            assertWarmRow()
            assertWarmHeroMetadata()
            assertTrue(HomeRepository.uiState.value.isLoading)
            releaseFinal.complete(Unit)
            withTimeout(5_000L) { HomeRepository.uiState.first { !it.isLoading } }
            assertWarmRow()
            assertWarmHeroMetadata()
        } finally {
            releasePartial.complete(Unit)
            releaseFinal.complete(Unit)
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
            descriptorSignature = buildHomeCatalogDescriptorSignature(addon, manifest, catalog),
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
