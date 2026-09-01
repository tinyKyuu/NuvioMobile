package com.nuvio.app.features.downloads

import app.cash.sqldelight.driver.native.inMemoryDriver
import com.nuvio.app.features.downloads.db.DownloadsDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadsCatalogStoreIosTest {
    @Test
    fun `sqlite store round trips versioned profile-owned records`() {
        withStore { store ->
            val record = testRecord(
                id = "round-trip",
                ownerProfileKey = "profile:2",
                state = DownloadInternalState.Paused,
            ).copy(
                recordVersion = DOWNLOAD_RECORD_VERSION,
                relativeMediaPath = "movie.mp4",
                platformSessionId = "session-v1",
                platformTaskIdentifier = 42L,
                lastEventId = "pause-1",
            )

            store.commit(recordsToUpsert = listOf(record))

            assertEquals(
                record.copy(
                    item = record.item.copy(
                        sourceUrl = "",
                        sourceHeaders = emptyMap(),
                        sourceResponseHeaders = emptyMap(),
                        localFileUri = null,
                    ),
                ),
                store.recordById(record.downloadId),
            )
            assertEquals(listOf(record.downloadId), store.recordsForProfile("profile:2").map { it.downloadId })
            assertEquals(emptyList(), store.recordsForProfile("profile:1"))
        }
    }

    @Test
    fun `replacement commit is atomic from the catalog perspective`() {
        withStore { store ->
            val old = testRecord(
                id = "old",
                ownerProfileKey = "profile:1",
                state = DownloadInternalState.Completed,
            )
            val replacement = testRecord(
                id = "replacement",
                ownerProfileKey = "profile:1",
                state = DownloadInternalState.Downloading,
            )
            store.commit(recordsToUpsert = listOf(old))

            store.commit(
                recordsToUpsert = listOf(replacement),
                downloadIdsToDelete = listOf(old.downloadId),
            )

            assertNull(store.recordById(old.downloadId))
            assertEquals(listOf(replacement.downloadId), store.recordsForProfile("profile:1").map { it.downloadId })
        }
    }

    @Test
    fun `replace profile does not alter another profile`() {
        withStore { store ->
            val firstProfile = testRecord(
                id = "profile-1-item",
                ownerProfileKey = "profile:1",
                state = DownloadInternalState.Paused,
            )
            val secondProfile = testRecord(
                id = "profile-2-item",
                ownerProfileKey = "profile:2",
                state = DownloadInternalState.Completed,
            )
            store.commit(recordsToUpsert = listOf(firstProfile, secondProfile))

            store.replaceProfile("profile:1", emptyList())

            assertEquals(emptyList(), store.recordsForProfile("profile:1"))
            assertEquals(listOf(secondProfile.downloadId), store.recordsForProfile("profile:2").map { it.downloadId })
        }
    }
}

private fun withStore(block: (SqlDownloadsCatalogStore) -> Unit) {
    val driver = inMemoryDriver(DownloadsDatabase.Schema)
    try {
        block(SqlDownloadsCatalogStore(DownloadsDatabase(driver)))
    } finally {
        driver.close()
    }
}
