package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Scene-local safe controls, separate from the edge-to-edge drawing viewport. */
internal data class NativeRootContentInsets(
    val start: Float = 0f,
    val end: Float = 0f,
    val bottomDock: Float = 0f,
)

class NativeRootContentLayout {
    private val mutableInsets = MutableStateFlow(NativeRootContentInsets())
    internal val insets = mutableInsets.asStateFlow()

    fun update(start: Double, end: Double, bottomDock: Double) {
        mutableInsets.value = NativeRootContentInsets(
            start = start.safeInset(),
            end = end.safeInset(),
            bottomDock = bottomDock.safeInset(),
        )
    }
}

private fun Double.safeInset(): Float =
    takeIf { it.isFinite() && it in 0.0..Float.MAX_VALUE.toDouble() }?.toFloat() ?: 0f

internal val LocalNativeRootContentInsets = staticCompositionLocalOf { NativeRootContentInsets() }

@Composable
internal fun Modifier.nativeRootControlsPadding(): Modifier {
    val insets = LocalNativeRootContentInsets.current
    return this.then(Modifier.padding(start = insets.start.dp, end = insets.end.dp))
}

/** Only horizontal shelves opt out of the list's control-safe content padding. */
@Composable
internal fun Modifier.nativeRootShelfBleed(): Modifier {
    val insets = LocalNativeRootContentInsets.current
    if (insets.start == 0f && insets.end == 0f) return this
    return layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
        val start = insets.start.dp.roundToPx()
        val end = insets.end.dp.roundToPx()
        val placeable = measurable.measure(
            constraints.copy(maxWidth = constraints.maxWidth + start + end),
        )
        layout(constraints.constrainWidth(placeable.width - start - end), placeable.height) {
            placeable.placeRelative(-start, 0)
        }
    }
}

@Composable
internal fun PaddingValues.withNativeRootShelfClearance(): PaddingValues {
    val insets = LocalNativeRootContentInsets.current
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + insets.start.dp,
        end = calculateEndPadding(direction) + insets.end.dp,
        top = calculateTopPadding(),
        bottom = calculateBottomPadding(),
    )
}
