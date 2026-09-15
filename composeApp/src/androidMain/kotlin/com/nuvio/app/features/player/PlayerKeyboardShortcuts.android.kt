package com.nuvio.app.features.player

import android.view.InputDevice
import androidx.compose.ui.input.key.KeyEvent

// Remote/D-pad input must retain ordinary control navigation. Attached, USB and
// Bluetooth keyboard events share SOURCE_KEYBOARD regardless of transport.
internal actual fun KeyEvent.isPlayerHardwareKeyboardEvent(): Boolean =
    isAcceptedAndroidPlayerKeyboardSource(
        source = nativeKeyEvent.source,
        functionPressed = nativeKeyEvent.isFunctionPressed,
        symPressed = nativeKeyEvent.isSymPressed,
    )

internal actual fun KeyEvent.isPlayerKeyboardRepeat(): Boolean =
    isAndroidPlayerKeyboardRepeat(nativeKeyEvent.repeatCount)

internal actual fun KeyEvent.toPlayerKeyboardShortcut(): PlayerKeyboardShortcut? =
    playerKeyboardShortcutForAndroidKeyCode(nativeKeyEvent.keyCode)

internal fun playerKeyboardShortcutForAndroidKeyCode(keyCode: Int): PlayerKeyboardShortcut? = when (keyCode) {
    android.view.KeyEvent.KEYCODE_SPACE -> PlayerKeyboardShortcut.TogglePlayback
    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> PlayerKeyboardShortcut.SeekBackward
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerKeyboardShortcut.SeekForward
    android.view.KeyEvent.KEYCODE_ESCAPE -> PlayerKeyboardShortcut.Exit
    else -> null
}

internal fun isAcceptedAndroidPlayerKeyboardSource(
    source: Int,
    functionPressed: Boolean,
    symPressed: Boolean,
): Boolean = source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
    source and InputDevice.SOURCE_DPAD != InputDevice.SOURCE_DPAD &&
    source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD &&
    !functionPressed && !symPressed

internal fun isAndroidPlayerKeyboardRepeat(repeatCount: Int): Boolean = repeatCount > 0
