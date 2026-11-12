package com.notesis

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class ChromePaletteTest {
    private fun legible(ink: Color, ground: Color) {
        assertTrue("${ink.toArgb().toUInt().toString(16)} on ${ground.toArgb().toUInt().toString(16)}",
            chromeContrast(ink, ground) >= 4.499f)
    }

    @Test fun `Material text and action pairs stay readable across accent choices and raised surfaces`() {
        for (dark in listOf(false, true)) for (accent in listOf(0xFF000000, 0xFFFFFFFF, 0xFFFFFF00, 0xFF00FF00, 0xFF0000FF, 0xFFFF00FF, 0xFF6750A4)) {
            val look = SkinSettings(themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT, accent = accent.toInt())
            val scheme = skinColors(lookScheme(null, look), look)
            for (surface in listOf(scheme.background, scheme.surface, scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)) {
                legible(scheme.onSurface, surface)
                legible(scheme.onSurfaceVariant, surface)
                legible(scheme.primary, surface)
            }
            for ((ink, body) in listOf(scheme.onPrimary to scheme.primary, scheme.onSecondary to scheme.secondary,
                scheme.onTertiary to scheme.tertiary, scheme.onError to scheme.error,
                scheme.onPrimaryContainer to scheme.primaryContainer, scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiaryContainer to scheme.tertiaryContainer, scheme.onErrorContainer to scheme.errorContainer)) legible(ink, body)
        }
    }

    @Test fun `AMOLED and high contrast keep their choices without modifying stored settings`() {
        val look = SkinSettings(themeMode = AppThemeMode.DARK, highContrast = true, amoled = true)
        val before = look.toJson()
        val scheme = skinColors(lookScheme(null, look), look)
        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.White, scheme.onSurface)
        assertEquals(Color.White, scheme.onSurfaceVariant)
        assertEquals(before, look.toJson())
    }

    @Test fun `unreadable user chrome ink is corrected without changing the saved ink colour`() {
        val look = SkinSettings(content = Color.White.toArgb())
        val scheme = skinColors(lookScheme(null, look), look)
        legible(scheme.onSurface, scheme.surfaceContainerHighest)
        assertEquals(Color.White.toArgb(), look.content)
        assertEquals(1f, scheme.onSurfaceVariant.alpha, 0f)
    }
}
