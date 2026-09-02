package com.nuvio.app.features.watchtogether.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtocolCompatibilityTest {
    @Test
    fun `negotiator selects the exact common protocol version`() {
        val result = WatchTogetherProtocolNegotiator.negotiate(
            manifest(protocolVersions = listOf("0.9", "1.0"))
        )

        assertEquals(
            WATCH_TOGETHER_PROTOCOL_VERSION,
            assertIs<WatchTogetherCompatibility.Compatible>(result).protocolVersion,
        )
    }

    @Test
    fun `incompatible services fall back to local playback`() {
        val unsupported = WatchTogetherProtocolNegotiator.negotiate(
            manifest(protocolVersions = listOf("2.0"))
        )
        val contentAware = WatchTogetherProtocolNegotiator.negotiate(
            manifest(contentBlind = false)
        )

        assertEquals(
            WatchTogetherCompatibilityReason.NoCommonProtocolVersion,
            assertIs<WatchTogetherCompatibility.LocalPlaybackOnly>(unsupported).reason,
        )
        assertEquals(
            WatchTogetherCompatibilityReason.ContentBlindnessNotGuaranteed,
            assertIs<WatchTogetherCompatibility.LocalPlaybackOnly>(contentAware).reason,
        )
    }

    @Test
    fun `invalid trust-sensitive manifest fields fall back to local playback`() {
        val insecure = WatchTogetherProtocolNegotiator.negotiate(
            manifest().copy(
                endpoints = WatchTogetherServiceEndpoints(
                    relayWebSocketUrl = "ws://relay.example.test/v1",
                )
            )
        )

        assertEquals(
            WatchTogetherCompatibilityReason.InvalidManifest,
            assertIs<WatchTogetherCompatibility.LocalPlaybackOnly>(insecure).reason,
        )
    }
}

private fun manifest(
    protocolVersions: List<String> = listOf(WATCH_TOGETHER_PROTOCOL_VERSION),
    contentBlind: Boolean = true,
): WatchTogetherServiceManifest = WatchTogetherServiceManifest(
    schema = "urn:watch-together:manifest:v1",
    schemaVersion = WATCH_TOGETHER_MANIFEST_VERSION,
    id = "example.watch-together",
    name = "Example",
    description = "Example service",
    canonicalOrigin = "https://example.test",
    protocolVersions = protocolVersions,
    endpoints = WatchTogetherServiceEndpoints(
        relayWebSocketUrl = "wss://relay.example.test/v1",
    ),
    operator = WatchTogetherServiceOperator(
        name = "Example",
        websiteUrl = "https://example.test",
    ),
    privacy = WatchTogetherPrivacyDeclaration(
        policyUrl = "https://example.test/privacy",
        contentBlind = contentBlind,
        operationalDataCategories = emptyList(),
    ),
    authentication = WatchTogetherAuthenticationDeclaration(
        host = WatchTogetherHostAuthentication(
            mode = WatchTogetherHostAuthenticationMode.None,
            accountRequired = false,
        ),
        guest = WatchTogetherGuestAuthentication(
            mode = WatchTogetherGuestAuthenticationMode.RoomCredential,
            accountRequired = false,
        ),
    ),
    capabilities = listOf(WatchTogetherServiceCapability.PlaybackPause),
)
