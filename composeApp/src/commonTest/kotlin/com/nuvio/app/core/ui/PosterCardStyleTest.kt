package com.nuvio.app.core.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PosterCardStyleTest {
    @Test
    fun `poster overlays grow only when rendered cards are genuinely large`() {
        assertEquals(NuvioPosterOverlayScale.Regular, posterOverlayScaleForWidth(149.dp))
        assertEquals(NuvioPosterOverlayScale.Large, posterOverlayScaleForWidth(150.dp))
        assertEquals(NuvioPosterOverlayScale.Large, posterOverlayScaleForWidth(220.dp))
    }

    @Test
    fun `automatic uses balanced on phones regardless of window width`() {
        val narrow = resolvePosterCardDimensions(PosterSizePreference(), false, 390f)
        val landscape = resolvePosterCardDimensions(PosterSizePreference(), false, 900f)

        assertEquals(PosterCardDimensions(126, 189), narrow)
        assertEquals(PosterCardDimensions(126, 189), landscape)
    }

    @Test
    fun `automatic uses large on tablets at the usable width boundary`() {
        val belowBoundary = resolvePosterCardDimensions(
            preference = PosterSizePreference(),
            isTabletFormFactor = true,
            usableWindowWidthDp = AutomaticTabletLargeMinimumWidthDp - 1f,
        )
        val atBoundary = resolvePosterCardDimensions(
            preference = PosterSizePreference(),
            isTabletFormFactor = true,
            usableWindowWidthDp = AutomaticTabletLargeMinimumWidthDp,
        )

        assertEquals(PosterCardDimensions(126, 189), belowBoundary)
        assertEquals(PosterCardDimensions(140, 210), atBoundary)
    }

    @Test
    fun `every fixed preset keeps the two by three poster ratio`() {
        val widths = listOf(
            CompactPosterCardWidthDp,
            DensePosterCardWidthDp,
            StandardPosterCardWidthDp,
            DefaultPosterCardWidthDp,
            ComfortPosterCardWidthDp,
            LargePosterCardWidthDp,
            ExtraLargePosterCardWidthDp,
        )

        assertEquals(
            listOf(156, 168, 180, 189, 201, 210, 240),
            widths.map { width ->
                resolvePosterCardDimensions(
                    preference = PosterSizePreference(PosterSizeMode.Manual, width),
                    isTabletFormFactor = false,
                    usableWindowWidthDp = 390f,
                ).heightDp
            },
        )
    }

    @Test
    fun `missing and balanced legacy widths migrate to automatic`() {
        val missing = loadPosterSizePreference(localPayload = null, legacyWidthDp = null)
        val balanced = loadPosterSizePreference(
            localPayload = null,
            legacyWidthDp = DefaultPosterCardWidthDp,
        )

        assertEquals(PosterSizePreference(), missing.preference)
        assertEquals(PosterSizePreference(), balanced.preference)
        assertNotNull(missing.payloadToPersist)
        assertNotNull(balanced.payloadToPersist)
    }

    @Test
    fun `local manual migration survives restart profile switch and remote legacy width`() {
        val firstLoad = loadPosterSizePreference(
            localPayload = null,
            legacyWidthDp = ComfortPosterCardWidthDp,
        )
        val restartAfterRemoteProfileChange = loadPosterSizePreference(
            localPayload = firstLoad.payloadToPersist,
            legacyWidthDp = CompactPosterCardWidthDp,
        )

        assertEquals(
            PosterSizePreference(PosterSizeMode.Manual, ComfortPosterCardWidthDp),
            firstLoad.preference,
        )
        assertEquals(firstLoad.preference, restartAfterRemoteProfileChange.preference)
        assertNull(restartAfterRemoteProfileChange.payloadToPersist)
    }

    @Test
    fun `automatic migration is idempotent after its local payload is saved`() {
        val firstLoad = loadPosterSizePreference(
            localPayload = null,
            legacyWidthDp = DefaultPosterCardWidthDp,
        )
        val secondLoad = loadPosterSizePreference(
            localPayload = firstLoad.payloadToPersist,
            legacyWidthDp = ExtraLargePosterCardWidthDp,
        )

        assertEquals(PosterSizePreference(), secondLoad.preference)
        assertNull(secondLoad.payloadToPersist)
    }

    @Test
    fun `manual selection persists across restart profile switch and remote apply`() {
        val savedLocalPayload = encodePosterSizePreference(
            PosterSizePreference(
                mode = PosterSizeMode.Manual,
                manualWidthDp = ExtraLargePosterCardWidthDp,
            ),
        )
        val afterRestart = loadPosterSizePreference(
            localPayload = savedLocalPayload,
            legacyWidthDp = CompactPosterCardWidthDp,
        )

        assertEquals(
            PosterSizePreference(PosterSizeMode.Manual, ExtraLargePosterCardWidthDp),
            afterRestart.preference,
        )
        assertNull(afterRestart.payloadToPersist)
    }

    @Test
    fun `profile sync identity excludes installation-local poster size`() {
        val automatic = PosterCardStyleUiState(
            sizeMode = PosterSizeMode.Automatic,
            widthDp = DefaultPosterCardWidthDp,
            heightDp = DefaultPosterCardHeightDp,
            cornerRadiusDp = 8,
            catalogLandscapeModeEnabled = true,
            hideLabelsEnabled = true,
        )
        val localManual = automatic.copy(
            sizeMode = PosterSizeMode.Manual,
            manualWidthDp = ExtraLargePosterCardWidthDp,
            widthDp = ExtraLargePosterCardWidthDp,
            heightDp = 240,
        )

        assertEquals(automatic.profileSyncState(), localManual.profileSyncState())
    }
}
