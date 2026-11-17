package com.notesis

import org.junit.Assert.*
import org.junit.Test

class FrameWorkPolicyTest {
    @Test fun `optional work stops before a predicted overrun but always makes one unit of progress`() {
        assertTrue(optionalTileWork(0, 9_000_000, 2_000_000, 4_000_000))
        assertFalse(optionalTileWork(1, 1_500_000, 2_000_000, 600_000))
        assertTrue(optionalTileWork(1, 1_000_000, 2_000_000, 600_000))
        assertFalse(optionalTileWork(8, 0, 2_000_000, 0))
        assertFalse(optionalTileWork(1, 2_000_001, 2_000_000, 0))
    }
    @Test fun `the requested opening page is published first even when it is later in document order`() {
        val before = Any(); val target = Any(); val after = Any()
        val missing = listOf(before, target, after)
        assertEquals(listOf(target), openingLoads(missing, target, true))
        assertEquals(missing, openingLoads(missing, target, false))
        assertEquals(listOf(before), openingLoads(missing, Any(), true))
        assertTrue(openingLoads(emptyList<Any>(), target, true).isEmpty())
    }
    @Test fun `backend draw completion is available even when the common timestamp is unset`() {
        val unset = Long.MIN_VALUE
        assertEquals(130L, inkDrawCompletion(100, unset, 130, unset))
        assertEquals(140L, inkDrawCompletion(100, unset, unset, 140))
        assertEquals(150L, inkDrawCompletion(100, 150, unset, unset))
        assertNull(inkDrawCompletion(100, unset, unset, unset))
        assertNull(inkDrawCompletion(unset, 150, 130, 140))
        assertNull(inkDrawCompletion(100, 90, 95, 100))
    }

    @Test fun `fewer strokes go to the render thread in a frame while writing or moving`() {
        val writing = strokeCapFor(Interaction.WRITING)
        val moving = strokeCapFor(Interaction.MOVING)
        val idle = strokeCapFor(Interaction.IDLE)
        assertTrue(writing < moving && moving < idle)
        assertEquals(idle, strokeCapFor(Interaction.SETTLING))
    }
}
