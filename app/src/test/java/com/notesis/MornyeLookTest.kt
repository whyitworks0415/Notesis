package com.notesis

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MornyeLookTest {
    @Test fun `the accents are iOS's, red by default, and one follows the app`() {
        assertEquals(MornyeAccent.RED, MornyeGlass().accent)
        assertEquals(0xFFCC2E33.toInt(), MornyeAccent.RED.argb(dark = false, appAccent = 0))
        assertEquals(0xFFFF453A.toInt(), MornyeAccent.RED.argb(dark = true, appAccent = 0))
        assertEquals(0xFF0071E3.toInt(), MornyeAccent.BLUE.argb(dark = false, appAccent = 0))
        assertEquals(0xFF123456.toInt(), MornyeAccent.APP.argb(dark = true, appAccent = 0xFF123456.toInt()))
        assertEquals(MornyeAccent.RED, MornyeAccent.fromName("nothing"))
        assertEquals(MornyeAccent.PINK, MornyeAccent.fromName("PINK"))
    }

    @Test fun `its settings survive a round trip and come back in range`() {
        val set = MornyeGlass(clarity = 0.3f, outlines = false, accent = MornyeAccent.GREEN, corner = 20f,
            motion = ProjectMotion(press = false))
        assertEquals(set, MornyeGlass.fromJson(set.toJson()))
        assertEquals(MornyeGlass(), MornyeGlass.fromJson(null))
    }

    @Test fun `the blur clears as the clarity rises`() {
        assertEquals(16f, mornyeBlurSigma(0.3f), 0f)
        assertEquals(6f, mornyeBlurSigma(0.75f), 0f)
        assertEquals(2f, mornyeBlurSigma(0.95f), 0f)
        // Android's blur radius r gives sigma 0.57735 r + 0.5.
        assertEquals(6f, 0.57735f * blurRadiusForSigma(6f) + 0.5f, 0.0001f)
        assertEquals(0f, blurRadiusForSigma(0f), 0f)
    }

    @Test fun `a bar is clearer than a panel, and the light glass is white`() {
        val bar = mornyeFill(0.75f, panel = false, dark = false)
        val panel = mornyeFill(0.75f, panel = true, dark = false)
        assertTrue(bar.alpha < panel.alpha)
        assertTrue(bar.red > 0.95f && bar.green > 0.95f && bar.blue > 0.95f)
        // Fully clear, a bar is the clear fill: white at 46%.
        assertEquals(0.46f, mornyeFill(1f, panel = false, dark = false).alpha, 0.01f)
        // Muddier as the clarity falls.
        assertTrue(mornyeFill(0.1f, panel = true, dark = false).alpha >= panel.alpha)
        // The dark glass is dark.
        val dark = mornyeFill(0.75f, panel = true, dark = true)
        assertTrue(dark.red < 0.3f)
    }

    @Test fun `Mornye's scheme takes its accent and iOS's greys`() {
        val accent = MornyeAccent.TEAL.light
        val light = mornyeScheme(schemeFrom(accent), accent, dark = false)
        assertEquals(accent, light.primary.toArgb())
        assertEquals(Color(0xFFF2F2F7), light.surfaceContainer)
        assertEquals(Color.White, light.surface)
        val dark = mornyeScheme(schemeFrom(accent, dark = true), accent, dark = true)
        assertEquals(Color.Black, dark.surface)
        assertEquals(Color(0xFF2C2C2E), dark.surfaceContainerHigh)
    }

    @Test fun `AMOLED is black with near-black panels`() {
        val scheme = amoledScheme(schemeFrom(SkinSettings.DEFAULT_ACCENT, dark = true))
        assertEquals(Color.Black, scheme.surface)
        assertEquals(Color(0xFF111111), scheme.surfaceContainerLow)
        assertEquals(Color(0xFF2A2A2A), scheme.surfaceContainerHighest)
    }

    @Test fun `a dialog arrives from 0_85 under Material and 0_96 under glass`() {
        assertEquals(0.85f, dialogArrivalScale(0f, material = true), 0f)
        assertEquals(0.96f, dialogArrivalScale(0f, material = false), 0f)
        assertEquals(1f, dialogArrivalScale(1f, material = true), 0f)
    }

    @Test fun `every Reicons name has a Material Symbol`() {
        AppIconStyle.materialSymbols = true
        try {
            assertEquals(MaterialSymbols.Add, Reicons.Add)
            assertEquals(MaterialSymbols.Eraser, Reicons.Eraser)
        } finally {
            AppIconStyle.materialSymbols = false
        }
        assertTrue(Reicons.Add !== MaterialSymbols.Add)
    }

    @Test fun `the squircle reaches 1_528 r and meets the circle at 45 degrees`() {
        val r = 10f
        val zone = squircleReach(r, 100f, 100f)
        assertEquals(15.28f, zone, 0.001f)
        // Held to half the short side.
        assertEquals(20f, squircleReach(30f, 40f, 100f), 0f)
        val n = squircleExponent(r, zone)
        val along = zone * Math.pow(Math.cos(Math.PI / 4), 2.0 / n).toFloat()
        // The diagonal point sits where a circle of radius r puts it: r (1 - 1/sqrt 2) in from each edge.
        assertEquals(r * (1f - 0.70710678f), zone - along, 0.01f)
    }

    @Test fun `the squircle outline runs clockwise inside its box`() {
        val w = 300f
        val h = 120f
        val points = squirclePoints(w, h, 32f)
        val zone = squircleReach(32f, w, h)
        val steps = squircleSegments(zone) * 2
        assertEquals(4 * (steps + 1) * 2, points.size)
        // From the left edge at the top-left corner's reach, to the top edge.
        assertEquals(0f, points[0], 0.001f)
        assertEquals(zone, points[1], 0.001f)
        assertEquals(zone, points[2 * steps], 0.001f)
        assertEquals(0f, points[2 * steps + 1], 0.001f)
        for (i in points.indices step 2) {
            assertTrue(points[i] in -0.001f..w + 0.001f)
            assertTrue(points[i + 1] in -0.001f..h + 0.001f)
        }
        // A radius under half a pixel is the rectangle.
        assertEquals(8, squirclePoints(w, h, 0.2f).size)
        assertEquals(3, squircleSegments(0f))
        assertEquals(40, squircleSegments(1e6f))
    }

    @Test fun `Mornye's corners are smooth until switched off`() {
        assertEquals(true, MornyeGlass().smoothCorners)
        val off = MornyeGlass(smoothCorners = false)
        assertEquals(off, MornyeGlass.fromJson(off.toJson()))
    }
}
