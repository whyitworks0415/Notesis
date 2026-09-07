package com.notesis

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinSchemeTest {
    @Test
    fun darkModeUsesDarkSurfacesWithReadableForegrounds() {
        val light = schemeFrom(SkinSettings.DEFAULT_ACCENT)
        val dark = schemeFrom(SkinSettings.DEFAULT_ACCENT, dark = true)

        assertTrue(dark.surface.luminance() < light.surface.luminance())
        assertTrue(dark.onSurface.luminance() > dark.surface.luminance())
        assertTrue(dark.onBackground.luminance() > dark.background.luminance())
    }
}
