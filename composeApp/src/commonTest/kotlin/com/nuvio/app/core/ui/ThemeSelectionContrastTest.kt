package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemeSelectionContrastTest {
    @Test
    fun `every active theme supplies contrasting selection icons and separate state tokens`() {
        AppTheme.entries.forEach { theme ->
            val palette = ThemeColors.getColorPalette(theme)
            val tokens = defaultNuvioThemeTokens(
                palette = palette,
                amoled = false,
                colorScheme = null,
            )

            assertTrue(
                contrastRatio(tokens.colors.accent, tokens.colors.onAccent) >= 3f,
                "$theme on-accent content must reach 3 to 1 contrast",
            )
            assertNotEquals(
                tokens.colors.borderSelected,
                tokens.colors.borderFocus,
                "$theme selection and focus rings must remain distinct",
            )
            assertNotEquals(
                tokens.colors.borderSelected,
                tokens.colors.danger,
                "$theme selection and danger states must remain distinct",
            )
        }
    }

    @Test
    fun `bright accents retain a nontransparent dark separator token`() {
        val tokens = defaultNuvioThemeTokens(
            palette = ThemeColors.White,
            amoled = false,
            colorScheme = null,
        )

        assertTrue(tokens.colors.overlayScrim.alpha >= 0.4f)
        assertTrue(contrastRatio(ThemeColors.White.secondary, Color.Black) >= 3f)
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
