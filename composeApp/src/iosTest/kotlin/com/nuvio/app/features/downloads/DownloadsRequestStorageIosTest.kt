package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadsRequestStorageIosTest {
    @Ignore
    @Test
    fun `keychain storage round trips and removes request payload`() {
        val downloadId = "phase1-keychain-test"
        val request = StoredDownloadRequest(
            sourceUrl = "https://example.test/video.mp4?token=secret",
            sourceHeaders = mapOf("Authorization" to "Bearer secret"),
        )
        val payload = DownloadsRequestCodec.encode(request)

        DownloadsRequestStorage.remove(downloadId)
        try {
            DownloadsRequestStorage.savePayload(downloadId, payload)
            assertEquals(payload, DownloadsRequestStorage.loadPayload(downloadId))
        } finally {
            DownloadsRequestStorage.remove(downloadId)
        }

        assertNull(DownloadsRequestStorage.loadPayload(downloadId))
    }
}
