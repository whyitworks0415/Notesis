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

    @Test fun `Mornye swells by a tenth of the short side, 2 to 5 points`() {
        val press = SkinSettings(liquidStyle = LiquidStyle.MORNYE).pressRecipe()!!
        // A 40 pt button: 4 points over its 40.
        assertEquals(1f + 4f / 40f, press.scale(40f, 40f, 1f), 0.0001f)
        // A wide bar moves no more than 5 points along its length.
        assertEquals(1f + 5f / 400f, press.scale(400f, 56f, 1f), 0.0001f)
        // At 2x density the same in points.
        assertEquals(1f + 8f / 80f, press.scale(80f, 80f, 2f), 0.0001f)
        assertEquals(2f, mornyeSwell(10f, 10f, 1f), 0f)
        // Its spring: stiffness 420, damping 24, underdamped.
        assertEquals(24.0 / (2.0 * Math.sqrt(420.0)), MORNYE_PRESS_RATIO.toDouble(), 0.0001)
    }

    @Test fun `the slide springs are the projects'`() {
        val i = ios.slideProfile()!!
        // GlassSpring.interactive(150 ms, 0.14) and snappy(350 ms): 4 pi^2 / t^2, ratio 1 - bounce.
        assertEquals(4 * Math.PI * Math.PI / (0.15 * 0.15), i.follow!!.first, 0.01)
        assertEquals(4 * Math.PI * Math.PI / (0.35 * 0.35), i.travel.first, 0.01)
        assertEquals(2 * 0.85 * Math.sqrt(i.travel.first), i.travel.second, 0.001)
        // Mornye's pill lands critically damped in about 240 ms and lifts on its press spring.
        val m = SkinSettings(liquidStyle = LiquidStyle.MORNYE).slideProfile()!!
        assertNull(m.follow)
        assertEquals(4 * Math.PI * Math.PI / (0.24 * 0.24), m.travel.first, 0.01)
        assertEquals(2 * Math.sqrt(m.travel.first), m.travel.second, 0.001)
        assertEquals(420.0 to 24.0, m.liftX)
    }

    @Test fun `the iOS menu swells a little past its size and squeezes on the way back`() {
        val open = ios.openProfile()!!
        assertEquals(1f, open.scaleOf(1f, 0.06f), 0.0001f)
        assertEquals(1.005f, open.scaleOf(1.05f, 0.06f), 0.0001f)
        // At rest closed it is the trigger's size; below 0 it is squeezed, never inverted.
        assertEquals(0.06f, open.scaleOf(0f, 0.06f), 0.0001f)
        assertEquals(true, open.scaleOf(-0.2f, 0.06f) > 0f)
        assertEquals(-3.2f, open.closeVelocity, 0f)
        // Mornye's own opening is the pour.
        assertEquals(PourOpen, SkinSettings(liquidStyle = LiquidStyle.MORNYE).openProfile())
    }

    @Test fun `a poured menu runs just past its size and the width follows the height`() {
        // From the drop to full, then past full by only 0.35 of the overshoot.
        assertEquals(0.12f, pourScale(0f, 0.12f), 0f)
        assertEquals(0.56f, pourScale(0.5f, 0.12f), 0.0001f)
        assertEquals(1f, pourScale(1f, 0.12f), 0f)
        assertEquals(1f + 0.08f * 0.35f, pourScale(1.08f, 0.12f), 0.0001f)
        assertEquals(50, PourOpen.widthDelayMs)
        assertNotNull(PourOpen.closeWidth)
        // The spring: damping 0.62 at 15 rad/s, which overshoots by about 8%.
        assertEquals(225f, POUR_STIFFNESS, 0f)
        val zeta = POUR_DAMPING_RATIO.toDouble()
        assertEquals(0.084, Math.exp(-zeta * Math.PI / Math.sqrt(1 - zeta * zeta)), 0.002)
    }

    @Test fun `every look's popups move, Material on its spring and the glass looks poured`() {
        assertEquals(MaterialOpen, popupOpening(Skin.MATERIAL, SkinSettings()))
        assertEquals(PourOpen, popupOpening(Skin.GLASSMORPHISM, SkinSettings()))
        assertEquals(PourOpen, popupOpening(Skin.LIQUID_GLASS, SkinSettings()))
        assertEquals(PourOpen, popupOpening(Skin.LIQUID_GLASS, SkinSettings(liquidStyle = LiquidStyle.MORNYE)))
        // iOS keeps its own while its switch is on, and pours when it is off.
        val ios = SkinSettings(liquidStyle = LiquidStyle.IOS)
        assertEquals(ios.openProfile()!!.startScale, popupOpening(Skin.LIQUID_GLASS, ios).startScale, 0f)
        assertEquals(-3.2f, popupOpening(Skin.LIQUID_GLASS, ios).closeVelocity, 0f)
        val iosOff = ios.copy(ios = ios.ios.copy(motion = ProjectMotion(open = false)))
        assertEquals(PourOpen, popupOpening(Skin.LIQUID_GLASS, iosOff))
        // Material grows from 0.8 and fades in; it never overshoots.
        assertEquals(0.8f, MaterialOpen.startScale, 0f)
        assertEquals(0f, MaterialOpen.alpha(0f), 0f)
        assertEquals(1f, MaterialOpen.alpha(0.6f), 0f)
    }
}
