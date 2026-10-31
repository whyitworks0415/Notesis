package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiquidMotionTest {
    private val ios = SkinSettings(liquidStyle = LiquidStyle.IOS)

    @Test fun `this app's glass and switched-off motion keep the app's own`() {
        val own = SkinSettings()
        assertNull(own.slideProfile())
        assertNull(own.pressRecipe())
        assertNull(own.openProfile())
        val off = ios.copy(ios = ios.ios.copy(motion = ProjectMotion(press = false, slide = false, open = false)))
        assertNull(off.slideProfile())
        assertNull(off.pressRecipe())
        assertNull(off.openProfile())
        // Each switch on its own.
        val slideOnly = ios.copy(ios = ios.ios.copy(motion = ProjectMotion(press = false, slide = true, open = false)))
        assertNotNull(slideOnly.slideProfile())
        assertNull(slideOnly.pressRecipe())
    }

    @Test fun `the iOS press grows a button by about 17 points, as widgets measured`() {
        val press = ios.pressRecipe()!!
        // A 56 pt circle about 1.3x, a 132 pt pill about 1.13x, at 1x density.
        assertEquals(1.30f, press.scale(56f, 56f, 1f), 0.01f)
        assertEquals(1.13f, press.scale(132f, 56f, 1f), 0.01f)
        // The same in points at 3x.
        assertEquals(1.30f, press.scale(168f, 168f, 3f), 0.01f)
    }

    @Test fun `Kyant0 grows by 4 dp of the height and QWEA0 shrinks to its press scale`() {
        val kyant = SkinSettings(liquidStyle = LiquidStyle.KYANT).pressRecipe()!!
        assertEquals(1f + 4f / 48f, kyant.scale(200f, 48f, 1f), 0.0001f)
        val qwea = SkinSettings(liquidStyle = LiquidStyle.QWEA)
        assertEquals(0.95f, qwea.pressRecipe()!!.scale(100f, 40f, 2f), 0f)
        val pressed = qwea.copy(qwea = qwea.qwea.copy(pressScale = 1.1f))
        assertEquals(1.1f, pressed.pressRecipe()!!.scale(100f, 40f, 2f), 0f)
    }

    @Test fun `the slide springs are the projects'`() {
        val i = ios.slideProfile()!!
        // GlassSpring.interactive(150 ms, 0.14) and snappy(350 ms): 4 pi^2 / t^2, ratio 1 - bounce.
        assertEquals(4 * Math.PI * Math.PI / (0.15 * 0.15), i.follow!!.first, 0.01)
        assertEquals(4 * Math.PI * Math.PI / (0.35 * 0.35), i.travel.first, 0.01)
        assertEquals(2 * 0.85 * Math.sqrt(i.travel.first), i.travel.second, 0.001)
        val k = SkinSettings(liquidStyle = LiquidStyle.KYANT).slideProfile()!!
        assertEquals(1000.0, k.travel.first, 0.0)
        assertEquals(2 * Math.sqrt(1000.0), k.travel.second, 0.001)
        assertEquals(2 * 0.6 * Math.sqrt(250.0), k.liftX.second, 0.001)
        assertEquals(2 * 0.7 * Math.sqrt(250.0), k.liftY.second, 0.001)
        // QWEA0's droplet stays under the finger.
        assertNull(SkinSettings(liquidStyle = LiquidStyle.QWEA).slideProfile()!!.follow)
    }

    @Test fun `the iOS menu swells a little past its size and squeezes on the way back`() {
        val open = ios.openProfile()!!
        assertEquals(1f, open.scaleOf(1f, 0.06f), 0.0001f)
        assertEquals(1.005f, open.scaleOf(1.05f, 0.06f), 0.0001f)
        // At rest closed it is the trigger's size; below 0 it is squeezed, never inverted.
        assertEquals(0.06f, open.scaleOf(0f, 0.06f), 0.0001f)
        assertEquals(true, open.scaleOf(-0.2f, 0.06f) > 0f)
        assertEquals(-3.2f, open.closeVelocity, 0f)
        // QWEA0's dialog comes from 0.86 and goes to 0.88.
        val qwea = SkinSettings(liquidStyle = LiquidStyle.QWEA).openProfile()!!
        assertEquals(0.86f, qwea.startScale, 0f)
        assertEquals(0.88f, qwea.closeScale, 0f)
        assertEquals(0.86f, qwea.scaleOf(0f, qwea.startScale), 0f)
        // Kyant0's drops 48 dp into place without scaling.
        val kyant = SkinSettings(liquidStyle = LiquidStyle.KYANT).openProfile()!!
        assertEquals(48f, kyant.dropDp, 0f)
        assertEquals(1f, kyant.scaleOf(0.3f, kyant.startScale), 0f)
    }
}
