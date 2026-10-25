package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class ChromeMotionTest {
    @Test fun `the selection pill is the strip's drawn corner moved in by the inset`() {
        // 32 on a 48 tall strip draws 24; six in that is 18.
        assertEquals(18f, toolPillRadius(32f, 48f, 6f, 6f), 0f)
        // The 24 a full bar hands its strip, on the 40 tall compact strip: 20 drawn, 14 inside.
        assertEquals(14f, toolPillRadius(24f, 40f, 6f, 6f), 0f)
        // Never below the floor, however square the strip.
        assertEquals(6f, toolPillRadius(4f, 48f, 6f, 6f), 0f)
    }

    @Test fun `without room a popup is placed as it always was`() {
        val (x, y) = popupPlacement(100, 40, 700, 120, 800, 600, 340, 180, gap = 8, inset = 0, alignLeft = true)
        assertEquals(100, x)
        assertEquals(128, y)
        val (rx, _) = popupPlacement(100, 40, 700, 120, 800, 600, 340, 180, gap = 8, inset = 0, alignLeft = false)
        assertEquals(360, rx)
    }

    @Test fun `the panel inside the shadow room lands where a popup without one would`() {
        val room = 16
        for (anchor in listOf(intArrayOf(0, 0, 44, 44), intArrayOf(756, 556, 800, 600), intArrayOf(100, 40, 700, 120))) {
            for (left in listOf(true, false)) {
                val bare = popupPlacement(anchor[0], anchor[1], anchor[2], anchor[3], 800, 600, 340, 400,
                    gap = 8, inset = 0, alignLeft = left)
                val roomy = popupPlacement(anchor[0], anchor[1], anchor[2], anchor[3], 800, 600,
                    340 + 2 * room, 400 + 2 * room, gap = 8, inset = room, alignLeft = left)
                assertEquals(bare.first, roomy.first + room)
                assertEquals(bare.second, roomy.second + room)
                // The panel itself stays the gap clear of the window's edges.
                assertTrue(roomy.first + room >= 8 && roomy.first + room + 340 <= 800 - 8)
                assertTrue(roomy.second + room >= 8 && roomy.second + room + 400 <= 600 - 8)
            }
        }
    }

    @Test fun `beside a bar down the side, a popup opens to its right, else its left`() {
        // A bar at the left edge: the menu to its right, level with its top.
        assertEquals(Pair(88, 200), popupBeside(0, 200, 80, 600, 1000, 800, 340, 400, gap = 8, inset = 0))
        // A bar at the right edge: no room to the right, so to its left.
        assertEquals(Pair(572, 200), popupBeside(920, 200, 1000, 600, 1000, 800, 340, 400, gap = 8, inset = 0))
        // Low on the screen: pulled up to stay inside it.
        assertEquals(Pair(88, 392), popupBeside(0, 700, 80, 790, 1000, 800, 340, 400, gap = 8, inset = 0))
        // With shadow room, the panel lands where it would without.
        assertEquals(Pair(88 - 16, 200 - 16), popupBeside(0, 200, 80, 600, 1000, 800, 372, 432, gap = 8, inset = 16))
    }

    @Test fun `a popup's two axes grow from their own start to their own size`() {
        assertEquals(0.55f, popupGrowScale(0f, 0.55f), 0f)
        assertEquals(1f, popupGrowScale(1f, 0.55f), 0f)
        // A spring's overshoot carries it just past its size.
        assertTrue(popupGrowScale(1.05f, 0.84f) > 1f)
    }

    @Test fun `a popup is solid long before it lands and clear when folded`() {
        assertEquals(0f, popupGrowAlpha(0f, 0f), 0f)
        assertEquals(0f, popupGrowAlpha(1f, 0f), 0f)
        assertEquals(1f, popupGrowAlpha(1f, POPUP_OPAQUE_AT), 0f)
        assertEquals(1f, popupGrowAlpha(1.05f, 1.02f), 0f)
        var previous = 0f
        for (i in 0..20) {
            val t = i / 20f
            val a = popupGrowAlpha(t, t)
            assertTrue(a >= previous)
            previous = a
        }
    }

    @Test fun `sidebar content waits for the panel then arrives with it`() {
        assertEquals(0f, sidebarContentAlpha(0f), 0f)
        assertEquals(0f, sidebarContentAlpha(0.25f), 0f)
        assertEquals(1f, sidebarContentAlpha(1f), 0f)
        assertTrue(sidebarContentAlpha(0.6f) in 0.4f..0.6f)
    }

    @Test fun `the lens travel spring is just under critical damping`() {
        assertEquals(0.938f, SPOTIGLASS_TRAVEL_DAMPING_RATIO, 0.001f)
    }

    /**
     * The still skins slide their highlight on a Compose spring of the lens's
     * stiffness and damping ratio. That spring's closed form has to follow the
     * lens's own integration, or the two skins would move at different speeds.
     */
    @Test fun `the highlight's spring moves with the lens`() {
        val motion = SpotiGlassMotion(0f)
        motion.select(3f)
        val k = SPOTIGLASS_TRAVEL_STIFFNESS
        val zeta = SPOTIGLASS_TRAVEL_DAMPING_RATIO.toDouble()
        val omega = sqrt(k)
        val damped = omega * sqrt(1 - zeta * zeta)
        fun spring(t: Double): Double {
            // From 0 to 3, at rest at the start.
            val decay = exp(-zeta * omega * t)
            return 3.0 * (1 - decay * (cos(damped * t) + zeta * omega / damped * sin(damped * t)))
        }
        val dt = 1.0 / 120
        var t = 0.0
        var halfwayAt = -1.0
        repeat(120) {
            t += dt
            motion.tick(t, dt, 100f)
            // The lens steps its spring at 240 Hz, which runs a few
            // milliseconds ahead of the exact curve: under a tenth of a cell.
            assertEquals(spring(t), motion.position.toDouble(), 0.08)
            if (halfwayAt < 0 && motion.position >= 1.5f) halfwayAt = t
        }
        // Halfway in about a tenth of a second: off the mark quicker than
        // the 260 ms sine ease it replaces, which took 130 ms to get there.
        assertTrue(halfwayAt in 0.08..0.115)
        assertEquals(3f, motion.position, 0.001f)
    }
}
