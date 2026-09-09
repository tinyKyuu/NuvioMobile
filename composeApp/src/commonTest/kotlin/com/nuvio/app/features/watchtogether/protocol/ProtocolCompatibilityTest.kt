package com.nuvio.app.features.watchtogether.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        assertEquals(
            WatchTogetherTransportProfile.SupabaseDirectV1,
            assertIs<WatchTogetherCompatibility.Compatible>(result).transport.profile,
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
                transports = listOf(
                    WatchTogetherServiceTransport(
                        profile = WatchTogetherTransportProfile.SupabaseDirectV1,
                        projectUrl = "https://example-project.supabase.co?token=secret",
                        publishableKey = "sb_publishable_example_public_key_0000000000",
                    )
                )
            )
        )

        assertEquals(
            WatchTogetherCompatibilityReason.InvalidManifest,
            assertIs<WatchTogetherCompatibility.LocalPlaybackOnly>(insecure).reason,
        )
    }

    @Test
    fun `validator rejects secret keys and duplicate transport profiles`() {
        val validTransport = manifest().transports.single()
        val secretKey = manifest().copy(
            transports = listOf(
                validTransport.copy(
                    publishableKey = "sb_secret_this_must_never_appear_in_a_manifest",
                )
            )
        )
        val duplicateProfile = manifest().copy(
            transports = listOf(validTransport, validTransport),
        )

        assertFalse(WatchTogetherContractValidator.validate(secretKey).isValid)
        assertFalse(WatchTogetherContractValidator.validate(duplicateProfile).isValid)
    }

    @Test
    fun `direct email OTP does not require a browser linking endpoint`() {
        val directOtp = manifest().copy(
            authentication = manifest().authentication.copy(
                host = WatchTogetherHostAuthentication(
                    mode = WatchTogetherHostAuthenticationMode.EmailOtp,
                    accountRequired = true,
                ),
            ),
        )
        val deviceLink = directOtp.copy(
            authentication = directOtp.authentication.copy(
                host = WatchTogetherHostAuthentication(
                    mode = WatchTogetherHostAuthenticationMode.EmailOtpDeviceLink,
                    accountRequired = true,
                ),
            ),
        )

        assertTrue(WatchTogetherContractValidator.validate(directOtp).isValid)
        assertFalse(WatchTogetherContractValidator.validate(deviceLink).isValid)
        assertTrue(
            WatchTogetherContractValidator.validate(
                deviceLink.copy(
                    endpoints = WatchTogetherServiceEndpoints(
                        accountLinkUrl = "https://example.test/link",
                    ),
                )
            ).isValid
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
    transports = listOf(
        WatchTogetherServiceTransport(
            profile = WatchTogetherTransportProfile.SupabaseDirectV1,
            projectUrl = "https://example-project.supabase.co",
            publishableKey = "sb_publishable_example_public_key_0000000000",
        )
    ),
    endpoints = WatchTogetherServiceEndpoints(),
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
