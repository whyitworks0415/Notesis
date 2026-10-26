package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

class UiMathTest {
    @Test fun `a nested corner is the outer one moved in by the inset`() {
        assertEquals(20f, concentricRadius(28f, 8f), 0f)
        assertEquals(12f, concentricRadius(28f, 16f, min = 4f), 0f)
        assertEquals(24f, concentricRadius(32f, 8f), 0f)
    }

    @Test fun `a nested corner never goes below its floor or negative`() {
        assertEquals(6f, concentricRadius(20f, 16f, min = 6f), 0f)
        assertEquals(0f, concentricRadius(4f, 16f), 0f)
        assertEquals(4f, concentricRadius(0f, 8f, min = 4f), 0f)
        // A negative inset is not a way to grow past the container.
        assertEquals(20f, concentricRadius(20f, -5f), 0f)
        assertEquals(3f, concentricRadius(Float.NaN, 2f, min = 3f), 0f)
    }

    @Test fun `concentric steps compose like one deeper step`() {
        val outer = 32f
        assertEquals(concentricRadius(outer, 14f), concentricRadius(concentricRadius(outer, 8f), 6f), 1e-6f)
    }

    @Test fun `a drawn radius is capped at half the short side`() {
        assertEquals(20f, drawnRadius(32f, 600f, 40f), 0f)
        assertEquals(12f, drawnRadius(12f, 600f, 40f), 0f)
        assertEquals(0f, drawnRadius(-3f, 10f, 10f), 0f)
    }

    @Test fun `sine in-out is the standard curve`() {
        for (i in 0..20) {
            val t = i / 20f
            val expected = (-(cos(PI * t) - 1) / 2).toFloat()
            assertEquals(expected, easeInOutSine(t), 1e-6f)
        }
        assertEquals(0f, easeInOutSine(0f), 1e-6f)
        assertEquals(0.5f, easeInOutSine(0.5f), 1e-6f)
        assertEquals(1f, easeInOutSine(1f), 1e-6f)
    }

    @Test fun `quad in-out is the standard curve`() {
        for (i in 0..20) {
            val t = i / 20f
            val expected = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).pow(2) / 2
            assertEquals(expected, easeInOutQuad(t), 1e-6f)
        }
        assertEquals(0.125f, easeInOutQuad(0.25f), 1e-6f)
        assertEquals(0.875f, easeInOutQuad(0.75f), 1e-6f)
    }

    @Test fun `both curves are monotonic, symmetric and clamped`() {
        for (curve in listOf(::easeInOutSine, ::easeInOutQuad)) {
            var last = -1f
            for (i in 0..100) {
                val t = i / 100f
                val y = curve(t)
                assertTrue(y >= last)
                assertEquals(1f - curve(1f - t), y, 1e-5f)
                last = y
            }
            assertEquals(0f, curve(-1f), 0f)
            assertEquals(1f, curve(2f), 0f)
        }
    }

    @Test fun `saved curve indices keep their meaning and new ones are added after them`() {
        assertEquals(ToolbarCurve.CUBIC_IN_OUT, ToolbarCurve.fromStored(0))
        assertEquals(ToolbarCurve.EASE_OUT, ToolbarCurve.fromStored(1))
        assertEquals(ToolbarCurve.LINEAR, ToolbarCurve.fromStored(2))
        assertEquals(ToolbarCurve.SPRING, ToolbarCurve.fromStored(3))
        assertEquals(ToolbarCurve.SINE_IN_OUT, ToolbarCurve.fromStored(4))
        assertEquals(ToolbarCurve.QUAD_IN_OUT, ToolbarCurve.fromStored(5))
        assertEquals(ToolbarCurve.DEFAULT, ToolbarCurve.fromStored(-1))
        assertEquals(ToolbarCurve.DEFAULT, ToolbarCurve.fromStored(99))
        assertEquals(ToolbarCurve.SINE_IN_OUT, ToolbarCurve.DEFAULT)
        assertEquals(ToolbarCurve.entries.size, ToolbarCurve.entries.map { it.stored }.distinct().size)
        for (curve in ToolbarCurve.entries) assertEquals(curve, ToolbarCurve.fromStored(curve.stored))
    }

    @Test fun `the retired lens skin loads as liquid glass`() {
        assertEquals(Skin.SPOTIGLASS, Skin.fromName("LIQUID_GLASS"))
        assertEquals(Skin.SPOTIGLASS, Skin.fromName("SPOTIGLASS"))
        assertEquals(Skin.GLASSMORPHISM, Skin.fromName("GLASSMORPHISM"))
        assertEquals(Skin.MATERIAL, Skin.fromName("MATERIAL"))
        assertEquals(Skin.MATERIAL, Skin.fromName(""))
        assertEquals(Skin.MATERIAL, Skin.fromName(null))
        assertEquals(Skin.MATERIAL, Skin.fromName("liquid_glass"))
        assertEquals(listOf("Material", "Glassmorphism", "Liquid glass"), Skin.entries.map { it.label })
        assertEquals(listOf(Skin.SPOTIGLASS), Skin.entries.filter { it.isRefractive })
        assertEquals(listOf(Skin.SPOTIGLASS), Skin.entries.filter { it.animatesChrome })
        assertEquals(listOf(Skin.GLASSMORPHISM, Skin.SPOTIGLASS), Skin.entries.filter { it.animatesPopups })
    }

    @Test fun `a popup grows from the point it was opened from`() {
        assertEquals(0.75f to 0f, popupGrowOrigin(175f, 100f, 100f, 100f, 100f, 200f))
        assertEquals(1f to 0f, popupGrowOrigin(900f, 40f, 100f, 100f, 100f, 200f))
        assertEquals(0f to 1f, popupGrowOrigin(0f, 900f, 100f, 100f, 100f, 200f))
        assertEquals(0.5f to 0f, popupGrowOrigin(10f, 10f, 0f, 0f, 0f, 0f))
    }

    @Test fun `the page under a scaled pane lands where it is on screen`() {
        // A pane laid out at (300, 200), 400 x 300, drawn at 0.8 about its anchor.
        val laidLeft = 300f; val laidTop = 200f; val w = 400f; val h = 300f
        val pivotX = laidLeft + w * 0.75f; val pivotY = laidTop
        val s = 0.8f
        val screenX = pivotX + (laidLeft - pivotX) * s
        val screenY = pivotY + (laidTop - pivotY) * s
        val farX = screenX + w * s
        val farY = screenY + h * s
        val sourceX = 0f; val sourceY = 24f
        val a = backdropAlignment(sourceX, sourceY, screenX, screenY, farX, farY, w, h)
        assertEquals(1f / s, a.scaleX, 1e-5f)
        assertEquals(1f / s, a.scaleY, 1e-5f)
        // A point of the recording, drawn in the pane's local space and then
        // carried to the screen by the pane's own transform.
        for ((qx, qy) in listOf(0f to 0f, 350f to 210f, 512f to 333f)) {
            val localX = (qx + a.shiftX) * a.scaleX
            val localY = (qy + a.shiftY) * a.scaleY
            assertEquals(sourceX + qx, screenX + localX * s, 1e-3f)
            assertEquals(sourceY + qy, screenY + localY * s, 1e-3f)
        }
    }

    @Test fun `an unscaled or collapsed pane is only shifted`() {
        val plain = backdropAlignment(0f, 0f, 10f, 20f, 110f, 220f, 100f, 200f)
        assertEquals(-10f, plain.shiftX, 0f)
        assertEquals(-20f, plain.shiftY, 0f)
        assertEquals(1f, plain.scaleX, 1e-6f)
        assertEquals(1f, plain.scaleY, 1e-6f)
        val folded = backdropAlignment(0f, 0f, 10f, 20f, 10f, 20f, 100f, 200f)
        assertEquals(1f, folded.scaleX, 0f)
        assertEquals(1f, folded.scaleY, 0f)
        val empty = backdropAlignment(0f, 0f, 10f, 20f, 10f, 20f, 0f, 0f)
        assertEquals(1f, empty.scaleX, 0f)
    }
}
