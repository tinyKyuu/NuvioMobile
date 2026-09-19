package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

@Composable
internal expect fun rememberReliableImeVisibility(windowInsetsVisible: Boolean): Boolean

internal fun reconciledIosImeVisibility(
    windowInsetsVisible: Boolean,
    nativeVisibility: Boolean?,
): Boolean = windowInsetsVisible && nativeVisibility != false

internal fun keyboardLayoutOccludesContent(
    layoutHeight: Double,
    bottomSafeArea: Double,
): Boolean = layoutHeight > maxOf(
    bottomSafeArea + KeyboardLayoutVisibilityEpsilon,
    MinimumSoftwareKeyboardLayoutHeight,
)

private const val KeyboardLayoutVisibilityEpsilon = 1.0
private const val MinimumSoftwareKeyboardLayoutHeight = 100.0
