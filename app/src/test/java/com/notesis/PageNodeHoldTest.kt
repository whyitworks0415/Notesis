package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PageNodeHoldTest {
    @Test fun `with nothing held the page keeps its node`() {
        assertEquals(0, pageNodeToRecord(0, -1, -1, follow = false))
        assertEquals(2, pageNodeToRecord(2, -1, -1, follow = false))
    }

    @Test fun `a node the glass holds is never recorded into`() {
        for (live in 0..2) for (a in -1..2) for (b in -1..2) {
            if (a == b && a != -1) continue
            val target = pageNodeToRecord(live, a, b, follow = false)
            assertNotEquals(a, target)
            assertNotEquals(b, target)
            assertEquals(true, target in 0..2)
        }
    }

    @Test fun `the glass holds one node and the page moves to the next`() {
        // The glass took node 0: the page goes on in node 1, and node 0 stays as it was.
        assertEquals(1, pageNodeToRecord(0, 0, -1, follow = false))
        // It then took node 1 as well: the page goes into node 2.
        assertEquals(2, pageNodeToRecord(1, 1, 0, follow = false))
    }

    @Test fun `following a finger records into the node the glass took last`() {
        assertEquals(0, pageNodeToRecord(0, 0, 1, follow = true))
        // The page moved on to node 2 after the glass took node 1: it goes back to 1.
        assertEquals(1, pageNodeToRecord(2, 1, 0, follow = true))
        assertEquals(2, pageNodeToRecord(2, -1, -1, follow = true))
    }
}
