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

    @Test fun `popups, dialogs and sidebars share the same spatial response`() {
        val open = POPUP_OPEN as SpringSpec<Float>
        assertEquals(1f, open.dampingRatio, 0f)
        assertEquals(open.stiffness / (MotionPrefs.responseScale * MotionPrefs.responseScale),
            sidebarOpen<Float>().stiffness, 0.01f)
        // Back into the button a little quicker than out of it.
        assertTrue((POPUP_CLOSE as SpringSpec<Float>).stiffness > open.stiffness)
    }

    @Test fun `an opening is visible on its first frame and lands without overshoot at 60 and 120 Hz`() {
        for (hz in listOf(60, 120)) {
            val motion = TargetBasedAnimation(MaterialMotion.rawContainer<Float>(), Float.VectorConverter,
                0f, 1f, POPUP_OPEN_VELOCITY)
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
