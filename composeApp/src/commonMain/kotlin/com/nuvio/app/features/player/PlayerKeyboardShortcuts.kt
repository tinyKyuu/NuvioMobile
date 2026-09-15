package com.nuvio.app.features.player

import androidx.compose.foundation.focusable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

// Adapted from Luqman Fadlli's GPLv3 NuvioMobile-Enhanced keyboard change,
// c4e7d4213052d0dd467be45f52fbfa79665a9b77. Native handlers report actions;
// playback, Watch Together and progress remain owned by PlayerScreenRuntime.
enum class PlayerKeyboardShortcut(val wireCode: String) {
    TogglePlayback("toggle_playback"),
    SeekBackward("seek_backward"),
    SeekForward("seek_forward"),
    Exit("exit");

    companion object {
        fun fromWireCode(code: String): PlayerKeyboardShortcut? =
            entries.firstOrNull { it.wireCode == code }
    }
}

/** One action per unmodified press, including when a platform delivers a duplicate down. */
internal class PlayerKeyboardPressTracker {
    private val pressed = mutableSetOf<PlayerKeyboardShortcut>()

    fun reset() = pressed.clear()

    fun handle(
        shortcut: PlayerKeyboardShortcut?,
        isDown: Boolean,
        isUp: Boolean,
        enabled: Boolean,
        modified: Boolean,
        repeated: Boolean,
        onShortcut: (PlayerKeyboardShortcut) -> Unit,
    ): Boolean {
        if (shortcut == null) return false
        if (isUp) return pressed.remove(shortcut) && enabled
        if (!isDown || !enabled || modified) return false
        if (repeated || !pressed.add(shortcut)) return true
        onShortcut(shortcut)
        return true
    }
}

internal expect fun KeyEvent.isPlayerHardwareKeyboardEvent(): Boolean
internal expect fun KeyEvent.isPlayerKeyboardRepeat(): Boolean
internal expect fun KeyEvent.toPlayerKeyboardShortcut(): PlayerKeyboardShortcut?

internal fun Modifier.playerKeyboardShortcuts(
    focusRequester: FocusRequester,
    tracker: PlayerKeyboardPressTracker,
    enabledState: State<Boolean>,
    onShortcutState: State<(PlayerKeyboardShortcut) -> Unit>,
): Modifier = onPreviewKeyEvent { event ->
    if (!event.isPlayerHardwareKeyboardEvent()) return@onPreviewKeyEvent false
    tracker.handle(
        shortcut = event.toPlayerKeyboardShortcut(),
        isDown = event.type == KeyEventType.KeyDown,
        isUp = event.type == KeyEventType.KeyUp,
        enabled = enabledState.value,
        modified = event.isShiftPressed || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed,
        repeated = event.isPlayerKeyboardRepeat(),
        onShortcut = onShortcutState.value,
    )
}.onFocusChanged { if (!it.hasFocus) tracker.reset() }
    .focusRequester(focusRequester)
    .focusable()
