package com.nuvio.app.features.home.components

import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AutomaticTabletLargeMinimumWidthDp
import com.nuvio.app.core.ui.CompactPosterCardWidthDp
import com.nuvio.app.core.ui.ComfortPosterCardWidthDp
import com.nuvio.app.core.ui.DefaultPosterCardWidthDp
import com.nuvio.app.core.ui.DensePosterCardWidthDp
import com.nuvio.app.core.ui.ExtraLargePosterCardWidthDp
import com.nuvio.app.core.ui.LargePosterCardWidthDp
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.core.ui.PosterSizePreference
import com.nuvio.app.core.ui.StandardPosterCardWidthDp
import com.nuvio.app.core.ui.resolvePosterCardDimensions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContinueWatchingPosterLayoutTest {
    @Test
    fun `continue watching poster dimensions and radius match catalog presets`() {
        val widths = listOf(
            CompactPosterCardWidthDp,
            DensePosterCardWidthDp,
            StandardPosterCardWidthDp,
            DefaultPosterCardWidthDp,
            ComfortPosterCardWidthDp,
            LargePosterCardWidthDp,
            ExtraLargePosterCardWidthDp,
        )

        widths.forEach { widthDp ->
            val heightDp = (widthDp * 3) / 2
            val layout = rememberContinueWatchingLayout(
                maxWidthDp = 390f,
                posterCardStyle = PosterCardStyleUiState(
                    widthDp = widthDp,
                    heightDp = heightDp,
                    cornerRadiusDp = 8,
                ),
            )

            assertEquals(widthDp.dp, layout.posterCardWidth)
            assertEquals(heightDp.dp, layout.posterCardHeight)
            assertEquals(8.dp, layout.cardRadius)
        }
    }

    @Test
    fun `automatic phone tablet and narrow tablet dimensions reach continue watching`() {
        val cases = listOf(
            Triple(false, 390f, DefaultPosterCardWidthDp),
            Triple(true, AutomaticTabletLargeMinimumWidthDp, LargePosterCardWidthDp),
            Triple(true, AutomaticTabletLargeMinimumWidthDp - 1f, DefaultPosterCardWidthDp),
        )

        cases.forEach { (isTablet, usableWidthDp, expectedWidthDp) ->
            val dimensions = resolvePosterCardDimensions(
                preference = PosterSizePreference(),
                isTabletFormFactor = isTablet,
                usableWindowWidthDp = usableWidthDp,
            )
            val layout = rememberContinueWatchingLayout(
                maxWidthDp = usableWidthDp,
                posterCardStyle = PosterCardStyleUiState(
                    widthDp = dimensions.widthDp,
                    heightDp = dimensions.heightDp,
                ),
            )

            assertEquals(expectedWidthDp.dp, layout.posterCardWidth)
            assertEquals((expectedWidthDp * 3 / 2).dp, layout.posterCardHeight)
        }
    }

    @Test
    fun `progress and wide layout stay usable at minimum and maximum sizes`() {
        val minimum = rememberContinueWatchingLayout(
            maxWidthDp = 390f,
            posterCardStyle = PosterCardStyleUiState(
                widthDp = CompactPosterCardWidthDp,
                heightDp = 156,
            ),
        )
        val maximum = rememberContinueWatchingLayout(
            maxWidthDp = 1024f,
            posterCardStyle = PosterCardStyleUiState(
                widthDp = ExtraLargePosterCardWidthDp,
                heightDp = 240,
            ),
        )

        assertTrue(minimum.posterProgressWidth >= 48.dp)
        assertTrue(minimum.wideCardWidth >= 260.dp)
        assertTrue(minimum.wideCardHeight >= 112.dp)
        assertTrue(maximum.posterProgressWidth < maximum.posterCardWidth)
        assertTrue(maximum.wideCardWidth > minimum.wideCardWidth)
        assertTrue(maximum.wideCardHeight > minimum.wideCardHeight)
    }
}
