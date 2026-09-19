package com.nuvio.app.features.tracking

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingAuthConfigurationTest {
    @Test
    fun `configuration is ready when required values and supported redirect are present`() {
        assertEquals(
            TrackingAuthConfigurationStatus.READY,
            trackingAuthConfigurationStatus(
                requiredValues = listOf("client", "secret"),
                redirectUri = "nuvio://auth/trakt",
                supportedRedirectUri = "nuvio://auth/trakt",
            ),
        )
    }

    @Test
    fun `blank required values make configuration unavailable`() {
        assertEquals(
            TrackingAuthConfigurationStatus.MISSING_REQUIRED_VALUES,
            trackingAuthConfigurationStatus(
                requiredValues = listOf("client", "  "),
                redirectUri = "nuvio://auth/trakt",
                supportedRedirectUri = "nuvio://auth/trakt",
            ),
        )
    }

    @Test
    fun `unregistered redirect makes configuration unavailable`() {
        assertEquals(
            TrackingAuthConfigurationStatus.UNSUPPORTED_REDIRECT_URI,
            trackingAuthConfigurationStatus(
                requiredValues = listOf("client"),
                redirectUri = "other://auth/simkl",
                supportedRedirectUri = "nuvio://auth/simkl",
            ),
        )
    }

    @Test
    fun `case-only redirect mismatch makes configuration unavailable`() {
        assertEquals(
            TrackingAuthConfigurationStatus.UNSUPPORTED_REDIRECT_URI,
            trackingAuthConfigurationStatus(
                requiredValues = listOf("client"),
                redirectUri = "com.tinykyuu.nuvio://auth/Simkl",
                supportedRedirectUri = "com.tinykyuu.nuvio://auth/simkl",
            ),
        )
    }
}
