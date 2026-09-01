package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class DownloadsPlatformDownloaderIosTest {
    @Test
    fun `direct file and redirect complete through the native downloader`() = runBlocking {
        val baseUrl = downloadFixtureBaseUrl()

        val direct = awaitDownload(
            request = testRequest(
                id = "phase0-direct",
                sourceUrl = "$baseUrl/file?size=1048576",
                fileName = "Phase 2 direct file.mp4",
            ),
        )
        assertNull(direct.error)
        assertTrue(direct.didFinalize)
        assertEquals(1_048_576L, direct.totalBytes)
        assertEquals("profile:test/phase0-direct/Phase 2 direct file.mp4", direct.relativeMediaPath)
        assertNotNull(direct.taskIdentifier)
        assertEquals(1_048_576L, fileSizeForUri(assertNotNull(direct.localFileUri)))
        assertTrue(assertNotNull(direct.localFileUri).contains("Library/Application%20Support/Downloads"))
        val coldLaunchUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = null,
            relativeMediaPath = assertNotNull(direct.relativeMediaPath),
        )
        assertNotNull(coldLaunchUri)
        assertEquals(1_048_576L, fileSizeForUri(coldLaunchUri))
        assertTrue(DownloadsPlatformDownloader.removeFile(coldLaunchUri))

        val redirected = awaitDownload(
            request = testRequest(
                id = "phase0-redirect",
                sourceUrl = "$baseUrl/redirect?size=262144",
            ),
        )
        assertNull(redirected.error)
        assertEquals(262_144L, fileSizeForUri(assertNotNull(redirected.localFileUri)))
        assertTrue(DownloadsPlatformDownloader.removeFile(redirected.localFileUri))
    }

    @Test
    fun `range edge case endpoint still completes as a fresh task`() = runBlocking {
        val baseUrl = downloadFixtureBaseUrl()
        val fileName = "phase0-range-416.mp4"

        val outcome = awaitDownload(
            request = testRequest(
                id = "phase0-range-416",
                sourceUrl = "$baseUrl/range-416?size=524288",
                fileName = fileName,
            ),
        )

        assertNull(outcome.error)
        assertEquals(524_288L, outcome.totalBytes)
        assertEquals(524_288L, fileSizeForUri(assertNotNull(outcome.localFileUri)))
        assertTrue(DownloadsPlatformDownloader.removeFile(outcome.localFileUri))
    }

    @Test
    fun `server that ignores range completes as a fresh task`() = runBlocking {
        val baseUrl = downloadFixtureBaseUrl()
        val fileName = "phase0-ignore-range.mp4"

        val outcome = awaitDownload(
            request = testRequest(
                id = "phase0-ignore-range",
                sourceUrl = "$baseUrl/ignore-range?size=393216",
                fileName = fileName,
            ),
        )

        assertNull(outcome.error)
        assertEquals(393_216L, outcome.totalBytes)
        assertEquals(393_216L, fileSizeForUri(assertNotNull(outcome.localFileUri)))
        assertTrue(DownloadsPlatformDownloader.removeFile(outcome.localFileUri))
    }

    @Test
    fun `custom headers succeed and expired signed urls fail without losing state`() = runBlocking {
        val baseUrl = downloadFixtureBaseUrl()
        val authorized = awaitDownload(
            request = testRequest(
                id = "phase0-header",
                sourceUrl = "$baseUrl/headers?size=131072",
                sourceHeaders = mapOf("X-Nuvio-Test" to "phase0"),
            ),
        )
        assertNull(authorized.error)
        assertEquals(131_072L, fileSizeForUri(assertNotNull(authorized.localFileUri)))
        assertTrue(DownloadsPlatformDownloader.removeFile(authorized.localFileUri))

        val expired = awaitDownload(
            request = testRequest(
                id = "phase0-expired",
                sourceUrl = "$baseUrl/signed?token=phase0&expires=1&size=65536",
            ),
        )
        assertNull(expired.localFileUri)
        assertTrue(expired.error?.isNotBlank() == true)
        assertTrue(DownloadsPlatformDownloader.removePartialFile("phase0-expired", "phase0-expired.mp4"))
    }

    @Test
    fun `disconnect never finalizes an incomplete media file`() = runBlocking {
        val baseUrl = downloadFixtureBaseUrl()
        val fileName = "phase0-disconnect.mp4"

        val outcome = awaitDownload(
            request = testRequest(
                id = "phase0-disconnect",
                sourceUrl = "$baseUrl/disconnect?size=524288&after=65536",
                fileName = fileName,
            ),
        )

        assertNull(outcome.localFileUri)
        assertTrue(outcome.error?.isNotBlank() == true)
        assertNull(
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = null,
                relativeMediaPath = "profile:test/phase0-disconnect/$fileName",
            ),
        )
        assertTrue(DownloadsPlatformDownloader.removePartialFile("phase0-disconnect", fileName))
    }

    @Test
    fun `paused slow transfer resumes to one completed file`() = runBlocking {
        val baseUrl = downloadFixtureBaseUrl()
        val fileName = "phase0-slow-cancel.mp4"
        val completion = CompletableDeferred<DownloadOutcome>()
        val handle = DownloadsPlatformDownloader.start(
            request = testRequest(
                id = "phase0-slow-cancel",
                sourceUrl = "$baseUrl/slow?size=2097152&chunk_size=32768&delay_ms=30",
                fileName = fileName,
            ),
            onTaskCreated = { _, _ -> },
            onWaitingForConnectivity = {},
            onProgress = { _, _ -> },
            onFinalizing = {},
            onSuccess = { localFileUri, relativeMediaPath, totalBytes ->
                completion.complete(
                    DownloadOutcome(
                        localFileUri = localFileUri,
                        relativeMediaPath = relativeMediaPath,
                        totalBytes = totalBytes,
                        error = null,
                    ),
                )
            },
            onFailure = { message ->
                completion.complete(DownloadOutcome(error = message))
            },
        )

        delay(250)
        handle.pause()
        delay(500)

        assertTrue(!completion.isCompleted)
        val resumed = awaitDownload(
            request = testRequest(
                id = "phase0-slow-cancel",
                sourceUrl = "$baseUrl/slow?size=2097152&chunk_size=32768&delay_ms=30",
                fileName = fileName,
            ),
        )

        assertNull(resumed.error)
        assertEquals(2_097_152L, fileSizeForUri(assertNotNull(resumed.localFileUri)))
        assertTrue(DownloadsPlatformDownloader.removeFile(resumed.localFileUri))
        assertTrue(DownloadsPlatformDownloader.removePartialFile("phase0-slow-cancel", fileName))
    }

    @Test
    fun `legacy absolute uri recovers by destination filename`() {
        downloadFixtureBaseUrl()
        val fileName = "phase0-relative-recovery.mp4"
        val currentPath = "${downloadsDirectoryPath()}/$fileName"
        writeBytes(currentPath, ByteArray(1024) { it.toByte() })

        val resolved = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = "file:///old-simulator-container/$fileName",
            relativeMediaPath = fileName,
        )

        assertNotNull(resolved)
        assertEquals(1_024L, fileSizeForUri(resolved))
        assertTrue(DownloadsPlatformDownloader.removeFile(resolved))
    }
}

private data class DownloadOutcome(
    val localFileUri: String? = null,
    val relativeMediaPath: String? = null,
    val totalBytes: Long? = null,
    val error: String? = null,
    val sessionIdentifier: String? = null,
    val taskIdentifier: Long? = null,
    val didFinalize: Boolean = false,
)

private fun testRequest(
    id: String,
    sourceUrl: String,
    fileName: String = "$id.mp4",
    sourceHeaders: Map<String, String> = emptyMap(),
): DownloadPlatformRequest = DownloadPlatformRequest(
    downloadId = id,
    ownerProfileKey = "profile:test",
    sourceUrl = sourceUrl,
    sourceHeaders = sourceHeaders,
    destinationFileName = fileName,
)

private suspend fun awaitDownload(request: DownloadPlatformRequest): DownloadOutcome {
    val completion = CompletableDeferred<DownloadOutcome>()
    var sessionIdentifier: String? = null
    var taskIdentifier: Long? = null
    var didFinalize = false
    DownloadsPlatformDownloader.start(
        request = request,
        onTaskCreated = { session, task ->
            sessionIdentifier = session
            taskIdentifier = task
        },
        onWaitingForConnectivity = {},
        onProgress = { _, _ -> },
        onFinalizing = { didFinalize = true },
        onSuccess = { localFileUri, relativeMediaPath, totalBytes ->
            completion.complete(
                DownloadOutcome(
                    localFileUri = localFileUri,
                    relativeMediaPath = relativeMediaPath,
                    totalBytes = totalBytes,
                    sessionIdentifier = sessionIdentifier,
                    taskIdentifier = taskIdentifier,
                    didFinalize = didFinalize,
                ),
            )
        },
        onFailure = { message ->
            completion.complete(
                DownloadOutcome(
                    error = message,
                    sessionIdentifier = sessionIdentifier,
                    taskIdentifier = taskIdentifier,
                ),
            )
        },
    )
    return withTimeout(20_000) { completion.await() }
}

@OptIn(ExperimentalForeignApi::class)
private fun downloadFixtureBaseUrl(): String {
    val value = getenv("NUVIO_DOWNLOAD_TEST_BASE_URL")
        ?.toKString()
        ?.trim()
        ?.trimEnd('/')
        ?: error(
            "Set -Pnuvio.download.test.baseUrl=http://127.0.0.1:<port> " +
                "and start scripts/phase0_download_test_server.py",
        )
    require(value.startsWith("http://127.0.0.1:")) {
        "The iOS download fixture must use a 127.0.0.1 URL"
    }
    return value
}

@OptIn(ExperimentalForeignApi::class)
private fun downloadsDirectoryPath(): String {
    val path = "${NSHomeDirectory().trimEnd('/')}/Documents/nuvio_downloads"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

@OptIn(ExperimentalForeignApi::class)
private fun writeBytes(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb") ?: error("Unable to create test file")
    try {
        if (bytes.isEmpty()) return
        val written = bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
        }
        check(written.toLong() == bytes.size.toLong())
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeForUri(localFileUri: String): Long? {
    val path = NSURL(string = localFileUri).path ?: return null
    return fileSizeAtPath(path)
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeAtPath(path: String): Long? {
    val value = NSFileManager.defaultManager
        .attributesOfItemAtPath(path, error = null)
        ?.get("NSFileSize")
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        else -> null
    }
}
