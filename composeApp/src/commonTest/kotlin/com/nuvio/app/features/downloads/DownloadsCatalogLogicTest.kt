package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadsCatalogLogicTest {
    @Test
    fun `playable movie lookup requires a completed file`() {
        val missing = downloadItem(
            id = "missing",
            status = DownloadStatus.Completed,
            parentMetaId = "movie-1",
            localFileUri = "file:///old/missing.mp4",
        )
        val downloading = downloadItem(
            id = "active",
            status = DownloadStatus.Downloading,
            parentMetaId = "movie-1",
        )
        val playable = downloadItem(
            id = "playable",
            status = DownloadStatus.Completed,
            parentMetaId = "movie-1",
            localFileUri = "file:///current/playable.mp4",
        )

        val result = selectPlayableDownload(
            items = listOf(missing, downloading, playable),
            parentMetaId = " movie-1 ",
            resolveLocalFileUri = resolverFor("playable.mp4"),
        )

        assertEquals(playable, result)
    }

    @Test
    fun `video id wins over parent and episode matching`() {
        val episodeMatch = downloadItem(
            id = "episode-match",
            status = DownloadStatus.Completed,
            parentMetaId = "show-1",
            videoId = "episode-1",
            seasonNumber = 1,
            episodeNumber = 1,
            localFileUri = "file:///current/episode-1.mp4",
            fileName = "episode-1.mp4",
        )
        val videoIdMatch = downloadItem(
            id = "video-match",
            status = DownloadStatus.Completed,
            parentMetaId = "other-show",
            videoId = "preferred-video",
            seasonNumber = 4,
            episodeNumber = 8,
            localFileUri = "file:///current/preferred.mp4",
            fileName = "preferred.mp4",
        )

        val result = selectPlayableDownload(
            items = listOf(episodeMatch, videoIdMatch),
            parentMetaId = "show-1",
            seasonNumber = 1,
            episodeNumber = 1,
            videoId = " preferred-video ",
            resolveLocalFileUri = resolverFor("episode-1.mp4", "preferred.mp4"),
        )

        assertEquals(videoIdMatch, result)
    }

    @Test
    fun `logical replacement preserves the existing first-match behavior`() {
        val oldPaused = downloadItem(
            id = "old-paused",
            status = DownloadStatus.Paused,
            parentMetaId = "show-1",
            seasonNumber = 2,
            episodeNumber = 3,
        )
        val oldCompleted = oldPaused.copy(
            id = "old-completed",
            status = DownloadStatus.Completed,
            localFileUri = "file:///current/old.mp4",
        )
        val unrelated = downloadItem(
            id = "unrelated",
            status = DownloadStatus.Completed,
            parentMetaId = "movie-2",
            localFileUri = "file:///current/unrelated.mp4",
        )
        val replacement = oldPaused.copy(
            id = "replacement",
            status = DownloadStatus.Downloading,
            downloadedBytes = 0L,
        )

        val result = replaceDownloadForLogicalContent(
            currentItems = listOf(oldPaused, unrelated, oldCompleted),
            replacement = replacement,
        )

        assertEquals(listOf(replacement, unrelated, oldCompleted), result.items)
        assertEquals(listOf(oldPaused), result.replacedItems)
    }

    @Test
    fun `cold launch pauses active records and recovers completed file locations`() {
        val active = downloadItem(
            id = "active",
            status = DownloadStatus.Downloading,
            errorMessage = "stale error",
            downloadedBytes = 64L,
        )
        val completed = downloadItem(
            id = "completed",
            status = DownloadStatus.Completed,
            localFileUri = "file:///old-container/movie.mp4",
            fileName = "movie.mp4",
        )

        val result = normalizeLoadedDownloads(
            items = listOf(active, completed),
            resolveLocalFileUri = { _, fileName ->
                if (fileName == "movie.mp4") "file:///new-container/movie.mp4" else null
            },
        )

        assertTrue(result.changed)
        assertEquals(DownloadStatus.Paused, result.items[0].status)
        assertNull(result.items[0].errorMessage)
        assertEquals(64L, result.items[0].downloadedBytes)
        assertEquals("file:///new-container/movie.mp4", result.items[1].localFileUri)
    }

    @Test
    fun `cold launch leaves stable records unchanged`() {
        val paused = downloadItem(id = "paused", status = DownloadStatus.Paused)

        val result = normalizeLoadedDownloads(
            items = listOf(paused),
            resolveLocalFileUri = { _, _ -> null },
        )

        assertFalse(result.changed)
        assertSame(paused, result.items.single())
    }

    @Test
    fun `existing state transitions preserve current behavior`() {
        val downloading = downloadItem(
            id = "transition",
            status = DownloadStatus.Downloading,
            downloadedBytes = 10L,
            totalBytes = 100L,
            errorMessage = "old",
        )

        val progressed = downloading.withProgress(
            downloadedBytes = 40L,
            totalBytes = 120L,
            nowEpochMs = 2L,
        )
        assertEquals(40L, progressed.downloadedBytes)
        assertEquals(120L, progressed.totalBytes)
        assertNull(progressed.errorMessage)

        val paused = progressed.pausedAt(nowEpochMs = 3L)
        assertEquals(DownloadStatus.Paused, paused.status)
        assertEquals(3L, paused.updatedAtEpochMs)

        val resumed = paused.copy(localFileUri = "file:///stale.mp4").resumedAt(nowEpochMs = 4L)
        assertEquals(DownloadStatus.Queued, resumed.status)
        assertNull(resumed.localFileUri)

        val completed = resumed.completedAt(
            localFileUri = "file:///current.mp4",
            totalBytes = 120L,
            nowEpochMs = 5L,
        )
        assertEquals(DownloadStatus.Completed, completed.status)
        assertEquals(120L, completed.downloadedBytes)
        assertEquals("file:///current.mp4", completed.localFileUri)

        assertSame(completed, completed.failedAt(message = "late failure", nowEpochMs = 6L))
        assertSame(completed, completed.pausedAt(nowEpochMs = 6L))

        val failed = downloading.failedAt(message = "network", nowEpochMs = 7L)
        assertEquals(DownloadStatus.Failed, failed.status)
        assertEquals("network", failed.errorMessage)
    }

    @Test
    fun `stored payload round trips and ignores future fields`() {
        val item = downloadItem(
            id = "stored",
            status = DownloadStatus.Paused,
            sourceHeaders = mapOf("Referer" to "https://example.test"),
        )

        val encoded = DownloadsCodec.encodeItems(listOf(item))
        val withFutureField = encoded.replaceFirst("{", "{\"futureVersion\":2,")

        assertEquals(listOf(item), DownloadsCodec.decodeItems(withFutureField))
        assertTrue(DownloadsCodec.decodeItems("not-json").isEmpty())
    }
}

private fun resolverFor(vararg existingFileNames: String): DownloadFileResolver = { _, fileName ->
    fileName.takeIf(existingFileNames::contains)?.let { "file:///current/$it" }
}

private fun downloadItem(
    id: String,
    status: DownloadStatus,
    parentMetaId: String = "movie-1",
    videoId: String = "video-1",
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    localFileUri: String? = null,
    fileName: String = "$id.mp4",
    downloadedBytes: Long = 0L,
    totalBytes: Long? = null,
    errorMessage: String? = null,
    sourceHeaders: Map<String, String> = emptyMap(),
): DownloadItem = DownloadItem(
    id = id,
    contentType = if (seasonNumber == null) "movie" else "series",
    parentMetaId = parentMetaId,
    parentMetaType = if (seasonNumber == null) "movie" else "series",
    videoId = videoId,
    title = "Test title",
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeTitle = episodeNumber?.let { "Episode $it" },
    streamTitle = "Test stream",
    providerName = "Test provider",
    sourceUrl = "https://example.test/video.mp4",
    sourceHeaders = sourceHeaders,
    localFileUri = localFileUri,
    fileName = fileName,
    status = status,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    errorMessage = errorMessage,
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
)
