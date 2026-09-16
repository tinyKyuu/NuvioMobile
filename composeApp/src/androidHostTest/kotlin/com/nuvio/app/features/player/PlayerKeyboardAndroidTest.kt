package com.nuvio.app.features.player

import android.view.InputDevice
import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerKeyboardAndroidTest {
    @Test
    fun acceptsHardwareKeyboardAndRejectsDpadAndGamepadSources() {
        assertTrue(isAcceptedAndroidPlayerKeyboardSource(InputDevice.SOURCE_KEYBOARD, false, false))
        assertFalse(isAcceptedAndroidPlayerKeyboardSource(InputDevice.SOURCE_DPAD, false, false))
        assertFalse(isAcceptedAndroidPlayerKeyboardSource(InputDevice.SOURCE_GAMEPAD, false, false))
        assertFalse(isAcceptedAndroidPlayerKeyboardSource(InputDevice.SOURCE_KEYBOARD, true, false))
    }

    @Test
    fun reportsNativeKeyRepeats() {
        assertFalse(isAndroidPlayerKeyboardRepeat(0))
        assertTrue(isAndroidPlayerKeyboardRepeat(2))
    }

    @Test
    fun mapsOnlyApprovedAndroidKeyCodes() {
        assertEquals(PlayerKeyboardShortcut.TogglePlayback, playerKeyboardShortcutForAndroidKeyCode(KeyEvent.KEYCODE_SPACE))
        assertEquals(PlayerKeyboardShortcut.SeekBackward, playerKeyboardShortcutForAndroidKeyCode(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlayerKeyboardShortcut.SeekForward, playerKeyboardShortcutForAndroidKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PlayerKeyboardShortcut.Exit, playerKeyboardShortcutForAndroidKeyCode(KeyEvent.KEYCODE_ESCAPE))
        assertNull(playerKeyboardShortcutForAndroidKeyCode(KeyEvent.KEYCODE_DPAD_UP))
    }
}
