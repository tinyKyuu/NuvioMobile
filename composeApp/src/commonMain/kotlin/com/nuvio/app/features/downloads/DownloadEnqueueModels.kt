package com.nuvio.app.features.downloads

internal data class DownloadEnqueueRequest(
    val profileId: Int,
    val contentType: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    val sourceUrl: String,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val hasSeparateAudioSource: Boolean = false,
    val isP2p: Boolean = false,
    val isExternalOnly: Boolean = false,
) {
    val logicalContentKey: String
        get() = if (seasonNumber != null && episodeNumber != null) {
            "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
        } else {
            "${parentMetaId.trim()}|movie"
        }

    fun normalized(): DownloadEnqueueRequest = copy(
        contentType = contentType.trim(),
        videoId = videoId.trim(),
        parentMetaId = parentMetaId.trim(),
        parentMetaType = parentMetaType.trim(),
        title = title.trim(),
        logo = logo?.trim()?.takeIf(String::isNotBlank),
        poster = poster?.trim()?.takeIf(String::isNotBlank),
        background = background?.trim()?.takeIf(String::isNotBlank),
        episodeTitle = episodeTitle?.trim()?.takeIf(String::isNotBlank),
        episodeThumbnail = episodeThumbnail?.trim()?.takeIf(String::isNotBlank),
        streamTitle = streamTitle.trim(),
        streamSubtitle = streamSubtitle?.trim()?.takeIf(String::isNotBlank),
        providerName = providerName.trim(),
        providerAddonId = providerAddonId?.trim()?.takeIf(String::isNotBlank),
        sourceUrl = sourceUrl.trim(),
        sourceHeaders = sanitizeDownloadRequestHeaders(sourceHeaders),
        sourceResponseHeaders = sanitizeDownloadResponseHeaders(sourceResponseHeaders),
        streamType = streamType?.trim()?.lowercase()?.takeIf(String::isNotBlank),
    )

    fun sourceFingerprint(): String = DownloadSourceFingerprint.sha256Hex(
        canonicalSourceFingerprintInput(),
    )

    private fun canonicalSourceFingerprintInput(): String = buildString {
        appendFingerprintPart("url", sourceUrl.trim())
        appendFingerprintPart("provider", providerAddonId?.trim().orEmpty())
        sanitizeDownloadRequestHeaders(sourceHeaders)
            .normalizedFingerprintHeaders()
            .forEach { (key, value) -> appendFingerprintPart("request:$key", value) }
        sanitizeDownloadResponseHeaders(sourceResponseHeaders)
            .normalizedFingerprintHeaders()
            .forEach { (key, value) -> appendFingerprintPart("response:$key", value) }
    }
}

internal enum class DownloadEligibilityReason {
    MissingUrl,
    UnsupportedScheme,
    LocalFile,
    P2p,
    Hls,
    Dash,
    TorrentFile,
    ExternalOnly,
    SeparateAudio,
}

internal sealed interface DownloadEligibility {
    data object Eligible : DownloadEligibility

    data class Ineligible(
        val reason: DownloadEligibilityReason,
    ) : DownloadEligibility
}

internal sealed interface DownloadEnqueueDecision {
    data object Enqueue : DownloadEnqueueDecision

    data class ExistingExact(
        val item: DownloadItem,
    ) : DownloadEnqueueDecision

    data class ConfirmReplacement(
        val item: DownloadItem,
    ) : DownloadEnqueueDecision

    data class Ineligible(
        val reason: DownloadEligibilityReason,
    ) : DownloadEnqueueDecision

    data object ProfileChanged : DownloadEnqueueDecision
}

internal fun evaluateDownloadEligibility(request: DownloadEnqueueRequest): DownloadEligibility {
    val sourceUrl = request.sourceUrl.trim()
    if (sourceUrl.isBlank()) {
        return DownloadEligibility.Ineligible(DownloadEligibilityReason.MissingUrl)
    }
    if (request.isExternalOnly) {
        return DownloadEligibility.Ineligible(DownloadEligibilityReason.ExternalOnly)
    }
    if (request.isP2p) {
        return DownloadEligibility.Ineligible(DownloadEligibilityReason.P2p)
    }
    if (request.hasSeparateAudioSource) {
        return DownloadEligibility.Ineligible(DownloadEligibilityReason.SeparateAudio)
    }

    val normalizedUrl = sourceUrl.lowercase()
    if (normalizedUrl.startsWith("file:")) {
        return DownloadEligibility.Ineligible(DownloadEligibilityReason.LocalFile)
    }
    if (normalizedUrl.startsWith("magnet:") || normalizedUrl.startsWith("torrent:")) {
        return DownloadEligibility.Ineligible(DownloadEligibilityReason.P2p)
    }
    if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
        return DownloadEligibility.Ineligible(DownloadEligibilityReason.UnsupportedScheme)
    }

    val normalizedStreamType = request.streamType?.trim()?.lowercase().orEmpty()
    if (normalizedStreamType.isHlsStreamType()) {
        return DownloadEligibility.Ineligible(DownloadEligibilityReason.Hls)
    }
    if (normalizedStreamType.isDashStreamType()) {
        return DownloadEligibility.Ineligible(DownloadEligibilityReason.Dash)
    }

    val normalizedPath = normalizedUrl.substringBefore('?').substringBefore('#')
    return when {
        normalizedPath.endsWith(".m3u8") ->
            DownloadEligibility.Ineligible(DownloadEligibilityReason.Hls)
        normalizedPath.endsWith(".mpd") ->
            DownloadEligibility.Ineligible(DownloadEligibilityReason.Dash)
        normalizedPath.endsWith(".torrent") ->
            DownloadEligibility.Ineligible(DownloadEligibilityReason.TorrentFile)
        else -> DownloadEligibility.Eligible
    }
}

internal fun decideDownloadEnqueue(
    items: List<DownloadItem>,
    request: DownloadEnqueueRequest,
    activeProfileId: Int,
): DownloadEnqueueDecision {
    val normalizedRequest = request.normalized()
    val eligibility = evaluateDownloadEligibility(normalizedRequest)
    if (eligibility is DownloadEligibility.Ineligible) {
        return DownloadEnqueueDecision.Ineligible(eligibility.reason)
    }
    if (normalizedRequest.profileId != activeProfileId) {
        return DownloadEnqueueDecision.ProfileChanged
    }

    val existing = items.firstOrNull {
        it.logicalContentKey == normalizedRequest.logicalContentKey
    } ?: return DownloadEnqueueDecision.Enqueue

    val requestedFingerprint = normalizedRequest.sourceFingerprint()
    return if (
        !existing.sourceFingerprint.isNullOrBlank() &&
        existing.sourceFingerprint == requestedFingerprint
    ) {
        DownloadEnqueueDecision.ExistingExact(existing)
    } else {
        DownloadEnqueueDecision.ConfirmReplacement(existing)
    }
}

internal fun sanitizeDownloadRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

internal fun sanitizeDownloadResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun Map<String, String>.normalizedFingerprintHeaders(): List<Pair<String, String>> =
    entries
        .map { (key, value) -> key.trim().lowercase() to value.trim() }
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })

private fun StringBuilder.appendFingerprintPart(name: String, value: String) {
    append(name.length)
    append(':')
    append(name)
    append(':')
    append(value.length)
    append(':')
    append(value)
    append(';')
}

private fun String.isHlsStreamType(): Boolean =
    this == "hls" ||
        contains("mpegurl") ||
        contains("apple.mpeg")

private fun String.isDashStreamType(): Boolean =
    this == "dash" || contains("dash+xml")

internal expect object DownloadSourceFingerprint {
    fun sha256Hex(value: String): String
}
