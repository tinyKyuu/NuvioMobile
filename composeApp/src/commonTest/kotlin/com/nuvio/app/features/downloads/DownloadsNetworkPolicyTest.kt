package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadsNetworkPolicyTest {
    @Test
    fun `wifi only always disables cellular access`() {
        assertFalse(
            DownloadNetworkPolicy(
                wifiOnly = true,
                allowCellular = true,
            ).effectiveAllowsCellular,
        )
    }

    @Test
    fun `cellular access requires explicit allowance`() {
        assertTrue(DownloadNetworkPolicy().effectiveAllowsCellular)
        assertFalse(DownloadNetworkPolicy(allowCellular = false).effectiveAllowsCellular)
    }
}
