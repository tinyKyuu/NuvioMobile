package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OfflineArtworkPlatformIosTest {
    @Test
    fun `saving the same artwork key replaces it at a stable local uri`() {
        val assetKey = "offline-artwork-atomic-replacement-test.jpg"
        OfflineArtworkPlatform.delete(assetKey)
        try {
            val firstUri = OfflineArtworkPlatform.save(
                assetKey = assetKey,
                bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01),
            )
            val replacementUri = OfflineArtworkPlatform.save(
                assetKey = assetKey,
                bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x02, 0x03),
            )

            assertNotNull(firstUri)
            assertEquals(firstUri, replacementUri)
            assertEquals(replacementUri, OfflineArtworkPlatform.localUri(assetKey))
        } finally {
            OfflineArtworkPlatform.delete(assetKey)
        }
    }
}
