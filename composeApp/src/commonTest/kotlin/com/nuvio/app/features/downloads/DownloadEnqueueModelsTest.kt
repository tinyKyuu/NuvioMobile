package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class DownloadEnqueueModelsTest {
    @Test
    fun `direct http sources remain eligible without a file extension`() {
        assertEquals(
            DownloadEligibility.Eligible,
            evaluateDownloadEligibility(request(sourceUrl = "https://media.test/playback?id=1")),
        )
    }

    @Test
    fun `unsupported playback sources return explicit reasons`() {
        val cases = listOf(
            request(sourceUrl = "") to DownloadEligibilityReason.MissingUrl,
            request(sourceUrl = "file:///movie.mp4") to DownloadEligibilityReason.LocalFile,
            request(sourceUrl = "magnet:?xt=urn:btih:abc") to DownloadEligibilityReason.P2p,
            request(sourceUrl = "https://media.test/movie.m3u8?token=1") to DownloadEligibilityReason.Hls,
            request(sourceUrl = "https://media.test/movie.mpd#fragment") to DownloadEligibilityReason.Dash,
            request(sourceUrl = "https://media.test/movie.torrent") to DownloadEligibilityReason.TorrentFile,
            request(sourceUrl = "https://media.test/movie.mp4", isP2p = true) to DownloadEligibilityReason.P2p,
            request(sourceUrl = "https://media.test/movie.mp4", hasSeparateAudioSource = true) to
                DownloadEligibilityReason.SeparateAudio,
            request(sourceUrl = "https://media.test/movie.mp4", isExternalOnly = true) to
                DownloadEligibilityReason.ExternalOnly,
        )

        cases.forEach { (candidate, expectedReason) ->
            val result = assertIs<DownloadEligibility.Ineligible>(
                evaluateDownloadEligibility(candidate),
            )
            assertEquals(expectedReason, result.reason)
        }
    }

    @Test
    fun `fingerprint is stable across header order and case`() {
        val first = request(
            sourceHeaders = linkedMapOf(
                "Referer" to "https://app.test",
                "Authorization" to "Bearer secret",
                "Range" to "bytes=0-10",
            ),
            sourceResponseHeaders = linkedMapOf("Content-Type" to "video/mp4", "X-Test" to "1"),
        )
        val reordered = first.copy(
            sourceHeaders = linkedMapOf(
                "authorization" to "Bearer secret",
                "referer" to "https://app.test",
            ),
            sourceResponseHeaders = linkedMapOf("x-test" to "1", "content-type" to "video/mp4"),
        )

        assertEquals(first.sourceFingerprint(), reordered.sourceFingerprint())
        assertNotEquals(
            first.sourceFingerprint(),
            first.copy(sourceHeaders = mapOf("Authorization" to "Bearer changed")).sourceFingerprint(),
        )
        assertNotEquals(
            first.sourceFingerprint(),
            first.copy(sourceUrl = "https://media.test/other.mp4").sourceFingerprint(),
        )
    }

    @Test
    fun `enqueue decision distinguishes exact different legacy and profile state`() {
        val requested = request()
        val exact = item(
            id = "exact",
            sourceFingerprint = requested.sourceFingerprint(),
        )
        assertEquals(
            DownloadEnqueueDecision.Enqueue,
            decideDownloadEnqueue(emptyList(), requested, activeProfileId = 1),
        )
        assertEquals(
            DownloadEnqueueDecision.ExistingExact(exact),
            decideDownloadEnqueue(listOf(exact), requested, activeProfileId = 1),
        )
        assertEquals(
            DownloadEnqueueDecision.ConfirmReplacement(exact.copy(sourceFingerprint = "different")),
            decideDownloadEnqueue(
                listOf(exact.copy(sourceFingerprint = "different")),
                requested,
                activeProfileId = 1,
            ),
        )
        assertEquals(
            DownloadEnqueueDecision.ConfirmReplacement(exact.copy(sourceFingerprint = null)),
            decideDownloadEnqueue(
                listOf(exact.copy(sourceFingerprint = null)),
                requested,
                activeProfileId = 1,
            ),
        )
        assertEquals(
            DownloadEnqueueDecision.ProfileChanged,
            decideDownloadEnqueue(listOf(exact), requested, activeProfileId = 2),
        )
    }
}

private fun request(
    sourceUrl: String = "https://media.test/movie.mp4",
    sourceHeaders: Map<String, String> = emptyMap(),
    sourceResponseHeaders: Map<String, String> = emptyMap(),
    isP2p: Boolean = false,
    hasSeparateAudioSource: Boolean = false,
    isExternalOnly: Boolean = false,
): DownloadEnqueueRequest = DownloadEnqueueRequest(
    profileId = 1,
    contentType = "movie",
    videoId = "movie-1",
    parentMetaId = "movie-1",
    parentMetaType = "movie",
    title = "Movie",
    streamTitle = "Source",
    providerName = "Provider",
    providerAddonId = "provider-id",
    sourceUrl = sourceUrl,
    sourceHeaders = sourceHeaders,
    sourceResponseHeaders = sourceResponseHeaders,
    isP2p = isP2p,
    hasSeparateAudioSource = hasSeparateAudioSource,
    isExternalOnly = isExternalOnly,
)

private fun item(
    id: String,
    sourceFingerprint: String?,
): DownloadItem = DownloadItem(
    id = id,
    contentType = "movie",
    parentMetaId = "movie-1",
    parentMetaType = "movie",
    videoId = "movie-1",
    title = "Movie",
    streamTitle = "Source",
    providerName = "Provider",
    sourceFingerprint = sourceFingerprint,
    sourceUrl = "",
    fileName = "movie.mp4",
    status = DownloadStatus.Paused,
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
)
