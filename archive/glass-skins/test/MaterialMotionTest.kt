package com.notesis

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.*
import org.junit.Test

class MaterialMotionTest {
    @Test fun `unknown and retired icon families fall back to static Material`() {
        for (name in listOf(null, "AnimateIcons", "SPOTIGLASS", "unknown"))
            assertEquals(StaticIconSet.MATERIAL, StaticIconSet.fromName(name))
        assertEquals(StaticIconSet.REICONS, StaticIconSet.fromName("REICONS"))
    }

    @Test fun `menus dialogs and Material sidebars share the same spatial response`() {
        val open = MaterialOpen.open as SpringSpec<Float>
        val dialog = dialogArrival(true) as SpringSpec<Float>
        assertEquals(MaterialMotion.START_SCALE, dialogArrivalScale(0f, true), 0f)
        assertEquals(open.stiffness, dialog.stiffness, 0f)
        assertEquals(1f, open.dampingRatio, 0f)
        assertEquals(open.stiffness / (MotionPrefs.responseScale * MotionPrefs.responseScale),
            sidebarOpenFor<Float>(Skin.MATERIAL).stiffness, 0.01f)
        assertEquals(sidebarOpenSpring<Float>().stiffness, sidebarOpenFor<Float>(Skin.LIQUID_GLASS).stiffness, 0f)
    }

    @Test fun `an opening is visible on its first frame and lands without overshoot at 60 and 120 Hz`() {
        for (hz in listOf(60, 120)) {
            val motion = TargetBasedAnimation(MaterialMotion.rawContainer<Float>(), Float.VectorConverter,
                0f, 1f, MaterialOpen.openVelocity)
            assertTrue(motion.getValueFromNanos(1_000_000_000L / hz) > 0f)
            repeat(hz) { i -> assertTrue(motion.getValueFromNanos((i + 1) * 1_000_000_000L / hz) in 0f..1.0001f) }
            assertEquals(1f, motion.getValueFromNanos(2_000_000_000L), 0.0001f)
        }
    }

    @Test fun `navigation direction follows stack history rather than settings enum order`() {
        assertEquals(1, materialPageDirection(1, 2))
        assertEquals(-1, materialPageDirection(3, 1))
        assertEquals(1, materialPageDirection(2, 2))
        assertTrue(MaterialMotion.PRESS < MaterialMotion.RELEASE)
        assertTrue(MaterialMotion.EXIT < MaterialMotion.CONTAINER)
    }
}
