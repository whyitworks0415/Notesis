package com.notesis

import org.junit.Assert.*
import org.junit.Test

class AppPopupFrameTest {
    @Test fun `every frame remains rounded and inside the popup even for an off-window anchor`() {
        for ((x,y) in listOf(100f to -20f, -50f to 100f, 500f to 100f, 100f to 900f)) {
            var lastWidth = 0f
            for (step in 0..100) {
                val f = appPopupFrame(x,y,300f,400f,44f,28f,step/100f)
                assertTrue(f.left >= -0.001f && f.top >= -0.001f)
                assertTrue(f.left+f.width <= 300.001f && f.top+f.height <= 400.001f)
                assertTrue(f.width >= lastWidth); lastWidth = f.width
                assertTrue(f.radius > 0f && f.radius <= minOf(f.width,f.height)/2f)
            }
        }
    }
    @Test fun `the complete panel returns to a small rounded square without a separate width collapse`() {
        val open = appPopupFrame(100f,-20f,300f,400f,44f,28f,1f)
        assertEquals(AppPopupFrame(0f,0f,300f,400f,28f),open)
        val closed = appPopupFrame(100f,-20f,300f,400f,44f,28f,0f)
        assertEquals(44f,closed.width,0f); assertEquals(44f,closed.height,0f)
        assertTrue(closed.radius >= 12f)
        val before = appPopupFrame(100f,-20f,300f,400f,44f,28f,0.49f)
        val after = appPopupFrame(100f,-20f,300f,400f,44f,28f,0.50f)
        assertTrue(kotlin.math.abs(before.radius-after.radius)<1f)
    }
    @Test fun `tiny or unmeasured panels cannot invert or create invalid outlines`() {
        assertEquals(0f,appPopupFrame(0f,0f,0f,0f,44f,28f,0f).width,0f)
        val tiny = appPopupFrame(Float.NaN,Float.NaN,20f,10f,44f,28f,0f)
        assertEquals(10f,tiny.width,0f)
        assertTrue(tiny.radius <= 5f)
    }
}
