package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PosterAvailabilityPlacementTest {
    @Test
    fun `landscape poster moves availability away from bottom-start branding`() {
        assertEquals(
            NuvioPosterAvailabilityPlacement.BottomEnd,
            posterAvailabilityPlacement(
                isLandscape = true,
                hasBottomStartContent = true,
            ),
        )
    }

    @Test
    fun `poster availability keeps its established position without a collision`() {
        assertEquals(
            NuvioPosterAvailabilityPlacement.BottomStart,
            posterAvailabilityPlacement(
                isLandscape = true,
                hasBottomStartContent = false,
            ),
        )
        assertEquals(
            NuvioPosterAvailabilityPlacement.BottomStart,
            posterAvailabilityPlacement(
                isLandscape = false,
                hasBottomStartContent = true,
            ),
        )
    }
}
