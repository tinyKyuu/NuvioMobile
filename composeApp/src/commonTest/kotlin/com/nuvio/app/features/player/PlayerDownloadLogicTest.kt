package com.nuvio.app.features.player

import androidx.compose.ui.unit.dp
import com.nuvio.app.features.downloads.DownloadEnqueueRequest
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerDownloadLogicTest {
    @Test
    fun `header actions use compact spacing below 768 dp`() {
        assertEquals(6.dp, PlayerLayoutMetrics.fromWidth(767.dp).headerActionSpacing)
        assertEquals(10.dp, PlayerLayoutMetrics.fromWidth(768.dp).headerActionSpacing)
    }

    @Test
    fun `eligible source is available and unsupported source is hidden`() {
        assertIs<PlayerDownloadActionState.Available>(
            derivePlayerDownloadActionState(request(), emptyList()),
        )
        assertEquals(
            PlayerDownloadActionState.Hidden,
            derivePlayerDownloadActionState(request(sourceUrl = "https://media.test/movie.m3u8"), emptyList()),
        )
    }

    @Test
    fun `logical item is scoped and reports exact source`() {
        val request = request()
        val unrelated = item(
            id = "unrelated",
            parentMetaId = "other",
            sourceFingerprint = request.sourceFingerprint(),
        )
        val exact = item(
            id = "exact",
            sourceFingerprint = request.sourceFingerprint(),
        )

        val state = assertIs<PlayerDownloadActionState.Existing>(
            derivePlayerDownloadActionState(request, listOf(unrelated, exact)),
        )
        assertEquals(exact, state.item)
        assertTrue(state.exactSource)

        val changed = assertIs<PlayerDownloadActionState.Existing>(
            derivePlayerDownloadActionState(request, listOf(exact.copy(sourceFingerprint = "other"))),
        )
        assertFalse(changed.exactSource)
    }

    @Test
    fun `status indicators distinguish known and unknown progress`() {
        val request = request()
        val known = assertIs<PlayerDownloadActionState.Existing>(
            derivePlayerDownloadActionState(
                request,
                listOf(
                    item(
                        status = DownloadStatus.Downloading,
                        downloadedBytes = 25L,
                        totalBytes = 100L,
                        sourceFingerprint = request.sourceFingerprint(),
                    ),
                ),
            ),
        ).indicatorPresentation()
        assertEquals(PlayerDownloadIndicatorIcon.Download, known?.icon)
        assertEquals(0.25f, known?.progressFraction)
        assertFalse(known?.showIndeterminateProgress ?: true)

        val unknown = assertIs<PlayerDownloadActionState.Existing>(
            derivePlayerDownloadActionState(
                request,
                listOf(
                    item(
                        status = DownloadStatus.Downloading,
                        totalBytes = null,
                        sourceFingerprint = request.sourceFingerprint(),
                    ),
                ),
            ),
        ).indicatorPresentation()
        assertNull(unknown?.progressFraction)
        assertTrue(unknown?.showIndeterminateProgress == true)
    }

    @Test
    fun `every terminal and queued status maps to its intended icon`() {
        val request = request()
        val expected = mapOf(
            DownloadStatus.Queued to PlayerDownloadIndicatorIcon.Queued,
            DownloadStatus.WaitingForNetwork to PlayerDownloadIndicatorIcon.WaitingForNetwork,
            DownloadStatus.Paused to PlayerDownloadIndicatorIcon.Paused,
            DownloadStatus.Failed to PlayerDownloadIndicatorIcon.Failed,
            DownloadStatus.Finalizing to PlayerDownloadIndicatorIcon.Finalizing,
            DownloadStatus.Completed to PlayerDownloadIndicatorIcon.Completed,
        )
        expected.forEach { (status, icon) ->
            val state = assertIs<PlayerDownloadActionState.Existing>(
                derivePlayerDownloadActionState(
                    request,
                    listOf(item(status = status, sourceFingerprint = request.sourceFingerprint())),
                ),
            )
            assertEquals(icon, state.indicatorPresentation()?.icon)
        }
    }
}

private fun request(
    sourceUrl: String = "https://media.test/movie.mp4",
): DownloadEnqueueRequest = DownloadEnqueueRequest(
    profileId = 1,
    contentType = "movie",
    videoId = "movie-1",
    parentMetaId = "movie-1",
    parentMetaType = "movie",
    title = "Movie",
    streamTitle = "Source",
    providerName = "Provider",
    sourceUrl = sourceUrl,
)

private fun item(
    id: String = "download-1",
    parentMetaId: String = "movie-1",
    status: DownloadStatus = DownloadStatus.Paused,
    downloadedBytes: Long = 0L,
    totalBytes: Long? = null,
    sourceFingerprint: String?,
): DownloadItem = DownloadItem(
    id = id,
    contentType = "movie",
    parentMetaId = parentMetaId,
    parentMetaType = "movie",
    videoId = parentMetaId,
    title = "Movie",
    streamTitle = "Source",
    providerName = "Provider",
    sourceFingerprint = sourceFingerprint,
    sourceUrl = "",
    fileName = "$id.mp4",
    status = status,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
)
