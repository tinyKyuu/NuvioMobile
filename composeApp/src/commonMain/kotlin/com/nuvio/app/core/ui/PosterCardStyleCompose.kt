package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
internal fun rememberPosterCardStyleUiState(): PosterCardStyleUiState {
    PosterCardStyleRepository.ensureLoaded()
    val uiState by PosterCardStyleRepository.uiState.collectAsState()
    val density = LocalDensity.current
    val windowWidthDp = with(density) {
        LocalWindowInfo.current.containerSize.width.toDp().value
    }
    val tabletFormFactor = isTabletFormFactor()
    return remember(uiState, tabletFormFactor, windowWidthDp) {
        resolvePosterCardStyle(
            state = uiState,
            isTabletFormFactor = tabletFormFactor,
            usableWindowWidthDp = windowWidthDp,
        )
    }
}
