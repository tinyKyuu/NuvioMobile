package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberReliableImeVisibility(windowInsetsVisible: Boolean): Boolean =
    windowInsetsVisible
