package com.nuvio.app.features.watchtogether.protocol

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

internal sealed interface WatchTogetherManifestLoadResult {
    data class Compatible(
        val manifest: WatchTogetherServiceManifest,
        val protocolVersion: String,
        val transport: WatchTogetherServiceTransport,
    ) : WatchTogetherManifestLoadResult

    data class Rejected(
        val reason: WatchTogetherManifestLoadFailure,
        val validationIssues: List<WatchTogetherValidationIssue> = emptyList(),
    ) : WatchTogetherManifestLoadResult
}

internal enum class WatchTogetherManifestLoadFailure {
    InvalidManifestUrl,
    DownloadFailed,
    DocumentTooLarge,
    MalformedDocument,
    UnsupportedManifestVersion,
    InvalidServiceProtocolVersion,
    InvalidManifest,
    NoCommonProtocolVersion,
    NoSupportedTransportProfile,
    ContentBlindnessNotGuaranteed,
}

internal class WatchTogetherServiceManifestLoader(
    private val httpClient: HttpClient,
) {
    suspend fun load(manifestUrl: String): WatchTogetherManifestLoadResult {
        if (!WatchTogetherServiceManifestResolver.isValidManifestUrl(manifestUrl)) {
            return WatchTogetherManifestLoadResult.Rejected(
                WatchTogetherManifestLoadFailure.InvalidManifestUrl,
            )
        }

        val document = try {
            val response = httpClient.get(manifestUrl)
            if (!response.status.isSuccess()) {
                return WatchTogetherManifestLoadResult.Rejected(
                    WatchTogetherManifestLoadFailure.DownloadFailed,
                )
            }
            response.body<String>()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return WatchTogetherManifestLoadResult.Rejected(
                WatchTogetherManifestLoadFailure.DownloadFailed,
            )
        }

        return WatchTogetherServiceManifestResolver.resolve(manifestUrl, document)
    }
}

internal object WatchTogetherServiceManifestResolver {
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private val manifestUrlPattern = Regex("^https://(?![^/?#]*@)[^?#]+$")

    fun isValidManifestUrl(manifestUrl: String): Boolean =
        manifestUrlPattern.matches(manifestUrl)

    fun resolve(
        manifestUrl: String,
        document: String,
    ): WatchTogetherManifestLoadResult {
        if (!isValidManifestUrl(manifestUrl)) {
            return WatchTogetherManifestLoadResult.Rejected(
                WatchTogetherManifestLoadFailure.InvalidManifestUrl,
            )
        }
        if (document.encodeToByteArray().size > MAX_MANIFEST_BYTES) {
            return WatchTogetherManifestLoadResult.Rejected(
                WatchTogetherManifestLoadFailure.DocumentTooLarge,
            )
        }

        val manifest = try {
            WatchTogetherJson.decodeFromString<WatchTogetherServiceManifest>(document)
        } catch (_: SerializationException) {
            return WatchTogetherManifestLoadResult.Rejected(
                WatchTogetherManifestLoadFailure.MalformedDocument,
            )
        } catch (_: IllegalArgumentException) {
            return WatchTogetherManifestLoadResult.Rejected(
                WatchTogetherManifestLoadFailure.MalformedDocument,
            )
        }

        return when (val compatibility = WatchTogetherProtocolNegotiator.negotiate(manifest)) {
            is WatchTogetherCompatibility.Compatible -> WatchTogetherManifestLoadResult.Compatible(
                manifest = manifest,
                protocolVersion = compatibility.protocolVersion,
                transport = compatibility.transport,
            )

            is WatchTogetherCompatibility.LocalPlaybackOnly -> {
                val validationIssues = if (
                    compatibility.reason == WatchTogetherCompatibilityReason.InvalidManifest
                ) {
                    WatchTogetherContractValidator.validate(manifest).issues
                } else {
                    emptyList()
                }
                WatchTogetherManifestLoadResult.Rejected(
                    reason = compatibility.reason.toLoadFailure(),
                    validationIssues = validationIssues,
                )
            }
        }
    }

    private fun WatchTogetherCompatibilityReason.toLoadFailure(): WatchTogetherManifestLoadFailure =
        when (this) {
            WatchTogetherCompatibilityReason.UnsupportedManifestVersion ->
                WatchTogetherManifestLoadFailure.UnsupportedManifestVersion
            WatchTogetherCompatibilityReason.InvalidServiceProtocolVersion ->
                WatchTogetherManifestLoadFailure.InvalidServiceProtocolVersion
            WatchTogetherCompatibilityReason.InvalidManifest ->
                WatchTogetherManifestLoadFailure.InvalidManifest
            WatchTogetherCompatibilityReason.NoCommonProtocolVersion ->
                WatchTogetherManifestLoadFailure.NoCommonProtocolVersion
            WatchTogetherCompatibilityReason.NoSupportedTransportProfile ->
                WatchTogetherManifestLoadFailure.NoSupportedTransportProfile
            WatchTogetherCompatibilityReason.ContentBlindnessNotGuaranteed ->
                WatchTogetherManifestLoadFailure.ContentBlindnessNotGuaranteed
        }
}
