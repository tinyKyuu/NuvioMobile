package com.nuvio.app.features.watchtogether.protocol

internal sealed interface WatchTogetherCompatibility {
    data class Compatible(
        val protocolVersion: String,
        val transport: WatchTogetherServiceTransport,
    ) : WatchTogetherCompatibility

    data class LocalPlaybackOnly(
        val reason: WatchTogetherCompatibilityReason,
    ) : WatchTogetherCompatibility
}

internal enum class WatchTogetherCompatibilityReason {
    UnsupportedManifestVersion,
    InvalidServiceProtocolVersion,
    InvalidManifest,
    NoCommonProtocolVersion,
    NoSupportedTransportProfile,
    ContentBlindnessNotGuaranteed,
}

internal object WatchTogetherProtocolNegotiator {
    private val clientVersions = setOf(WATCH_TOGETHER_PROTOCOL_VERSION)
    private val clientTransportProfiles = setOf(WatchTogetherTransportProfile.SupabaseDirectV1)
    private val versionPattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

    fun negotiate(manifest: WatchTogetherServiceManifest): WatchTogetherCompatibility {
        if (manifest.schemaVersion != WATCH_TOGETHER_MANIFEST_VERSION) {
            return WatchTogetherCompatibility.LocalPlaybackOnly(
                WatchTogetherCompatibilityReason.UnsupportedManifestVersion,
            )
        }
        if (!manifest.privacy.contentBlind) {
            return WatchTogetherCompatibility.LocalPlaybackOnly(
                WatchTogetherCompatibilityReason.ContentBlindnessNotGuaranteed,
            )
        }
        if (!WatchTogetherContractValidator.validate(manifest).isValid) {
            return WatchTogetherCompatibility.LocalPlaybackOnly(
                WatchTogetherCompatibilityReason.InvalidManifest,
            )
        }
        if (manifest.protocolVersions.any { !versionPattern.matches(it) }) {
            return WatchTogetherCompatibility.LocalPlaybackOnly(
                WatchTogetherCompatibilityReason.InvalidServiceProtocolVersion,
            )
        }
        val selected = manifest.protocolVersions
            .filter(clientVersions::contains)
            .maxWithOrNull(compareByVersion)
            ?: return WatchTogetherCompatibility.LocalPlaybackOnly(
                WatchTogetherCompatibilityReason.NoCommonProtocolVersion,
            )
        val selectedTransport = manifest.transports.firstOrNull {
            it.profile in clientTransportProfiles
        } ?: return WatchTogetherCompatibility.LocalPlaybackOnly(
            WatchTogetherCompatibilityReason.NoSupportedTransportProfile,
        )
        return WatchTogetherCompatibility.Compatible(selected, selectedTransport)
    }

    private val compareByVersion = Comparator<String> { left, right ->
        val leftParts = left.split('.').map(String::toInt)
        val rightParts = right.split('.').map(String::toInt)
        compareValuesBy(leftParts, rightParts, { it[0] }, { it[1] })
    }
}
