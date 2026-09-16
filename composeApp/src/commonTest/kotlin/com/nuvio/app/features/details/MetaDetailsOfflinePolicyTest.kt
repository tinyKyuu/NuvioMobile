package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MetaDetailsOfflinePolicyTest {
    @Test
    fun `cold offline relaunch selects persisted snapshot and never schedules ordinary loading`() {
        val persisted = offlineSeries()
        val cachedOnline = persisted.copy(
            poster = "https://images.test/remote.jpg",
            isOfflineSnapshot = false,
        )
        val policy = resolveMetaDetailsDisplayPolicy(
            repositoryMeta = null,
            offlineMeta = persisted,
            cachedMeta = cachedOnline,
            isOfflineLike = true,
        )

        assertSame(persisted, policy.displayedMeta)
        assertFalse(policy.ordinaryOnlineRequestsAllowed)
        assertFalse(policy.deferredOnlineWorkAllowed)
        repeat(3) {
            assertFalse(
                shouldScheduleInitialMetaLoad(
                    policy = policy,
                    isLoading = false,
                    autoLoadAttempted = false,
                ),
            )
            assertFalse(
                shouldScheduleMetaEnrichment(
                    policy = policy,
                    isLoading = false,
                    attemptedFingerprint = null,
                    currentFingerprint = "settings-v1",
                ),
            )
        }
    }

    @Test
    fun `online to offline transition replaces repository meta and gates undownloaded episodes`() {
        val online = MetaDetails(
            id = "tt123",
            type = "series",
            name = "Online",
            poster = "https://images.test/remote.jpg",
            videos = listOf(
                MetaVideo(id = "online-1", title = "One", season = 1, episode = 1),
                MetaVideo(id = "online-2", title = "Two", season = 1, episode = 2),
            ),
        )
        val offline = offlineSeries()

        val onlinePolicy = resolveMetaDetailsDisplayPolicy(
            repositoryMeta = online,
            offlineMeta = offline,
            cachedMeta = null,
            isOfflineLike = false,
        )
        val offlinePolicy = resolveMetaDetailsDisplayPolicy(
            repositoryMeta = online,
            offlineMeta = offline,
            cachedMeta = null,
            isOfflineLike = true,
        )

        assertSame(online, onlinePolicy.displayedMeta)
        assertSame(offline, offlinePolicy.displayedMeta)
        assertTrue(offlinePolicy.displayedMeta?.isOfflineSnapshot == true)
        assertFalse(
            shouldScheduleMetaEnrichment(
                policy = offlinePolicy,
                isLoading = false,
                attemptedFingerprint = null,
                currentFingerprint = "settings-v1",
            ),
        )

        val availability = offline.videos.associate { episode ->
            requireNotNull(episode.episode) to !offlineEpisodeRequiresInternet(
                meta = offline,
                isOfflineLike = true,
                isDownloaded = episode.episode == 1,
            )
        }
        assertEquals(mapOf(1 to true, 2 to false), availability)
    }

    private fun offlineSeries() = MetaDetails(
        id = "tt123",
        type = "series",
        name = "Offline",
        poster = "file:///offline/poster.jpg",
        videos = listOf(
            MetaVideo(id = "offline-1", title = "One", season = 1, episode = 1),
            MetaVideo(id = "offline-2", title = "Two", season = 1, episode = 2),
        ),
        isOfflineSnapshot = true,
    )
}
