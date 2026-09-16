package com.nuvio.app.features.player

import androidx.compose.ui.input.key.KeyEvent

// UIKit is the sole iOS event owner. Do not dispatch the same press via Compose.
internal actual fun KeyEvent.isPlayerHardwareKeyboardEvent(): Boolean = false
internal actual fun KeyEvent.isPlayerKeyboardRepeat(): Boolean = false
internal actual fun KeyEvent.toPlayerKeyboardShortcut(): PlayerKeyboardShortcut? = null
