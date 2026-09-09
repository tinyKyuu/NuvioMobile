package com.nuvio.app.features.watchtogether.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WatchTogetherServiceManifestLoaderTest {
    @Test
    fun `resolves a strict content-blind Supabase service manifest`() {
        val result = WatchTogetherServiceManifestResolver.resolve(
            manifestUrl = "https://watch.example.test/manifest.json",
            document = validManifestDocument(),
        )

        val compatible = assertIs<WatchTogetherManifestLoadResult.Compatible>(result)
        assertEquals(WATCH_TOGETHER_PROTOCOL_VERSION, compatible.protocolVersion)
        assertEquals(
            WatchTogetherTransportProfile.SupabaseDirectV1,
            compatible.transport.profile,
        )
    }

    @Test
    fun `rejects unsafe manifest URLs before parsing`() {
        val tokenized = WatchTogetherServiceManifestResolver.resolve(
            manifestUrl = "https://watch.example.test/manifest.json?token=secret",
            document = validManifestDocument(),
        )
        val credentialed = WatchTogetherServiceManifestResolver.resolve(
            manifestUrl = "https://user:password@watch.example.test/manifest.json",
            document = validManifestDocument(),
        )

        assertEquals(
            WatchTogetherManifestLoadFailure.InvalidManifestUrl,
            assertIs<WatchTogetherManifestLoadResult.Rejected>(tokenized).reason,
        )
        assertEquals(
            WatchTogetherManifestLoadFailure.InvalidManifestUrl,
            assertIs<WatchTogetherManifestLoadResult.Rejected>(credentialed).reason,
        )
    }

    @Test
    fun `rejects oversized malformed and non-content-blind documents`() {
        val oversized = WatchTogetherServiceManifestResolver.resolve(
            manifestUrl = "https://watch.example.test/manifest.json",
            document = " ".repeat(64 * 1024 + 1),
        )
        val malformed = WatchTogetherServiceManifestResolver.resolve(
            manifestUrl = "https://watch.example.test/manifest.json",
            document = "not-json",
        )
        val contentAware = WatchTogetherServiceManifestResolver.resolve(
            manifestUrl = "https://watch.example.test/manifest.json",
            document = validManifestDocument().replace(
                "\"contentBlind\": true",
                "\"contentBlind\": false",
            ),
        )

        assertEquals(
            WatchTogetherManifestLoadFailure.DocumentTooLarge,
            assertIs<WatchTogetherManifestLoadResult.Rejected>(oversized).reason,
        )
        assertEquals(
            WatchTogetherManifestLoadFailure.MalformedDocument,
            assertIs<WatchTogetherManifestLoadResult.Rejected>(malformed).reason,
        )
        assertEquals(
            WatchTogetherManifestLoadFailure.ContentBlindnessNotGuaranteed,
            assertIs<WatchTogetherManifestLoadResult.Rejected>(contentAware).reason,
        )
    }
}

private fun validManifestDocument(): String =
    """
    {
      "${'$'}schema": "urn:watch-together:manifest:v1",
      "schemaVersion": "1.0",
      "id": "example.watch-together",
      "name": "Example",
      "description": "Example service",
      "canonicalOrigin": "https://watch.example.test",
      "protocolVersions": ["1.0"],
      "transports": [{
        "profile": "supabase_direct_v1",
        "projectUrl": "https://example-project.supabase.co",
        "publishableKey": "sb_publishable_example_public_key_0000000000"
      }],
      "endpoints": {},
      "operator": {
        "name": "Example",
        "websiteUrl": "https://watch.example.test"
      },
      "privacy": {
        "policyUrl": "https://watch.example.test/privacy",
        "contentBlind": true,
        "operationalDataCategories": []
      },
      "authentication": {
        "host": { "mode": "email_otp", "accountRequired": true },
        "guest": { "mode": "room_credential", "accountRequired": false }
      },
      "capabilities": ["room.create", "room.join", "playback.pause"]
    }
    """.trimIndent()
