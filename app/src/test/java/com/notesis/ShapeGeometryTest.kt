package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeGeometryTest {
    @Test
    fun everyShapeStaysNearItsBox() {
        for (kind in ShapeKind.entries) {
            val points = shapeOutline(kind, 100f, 50f, 300f, 250f, axisSnap = false)
            assertTrue("$kind has a line", points.size >= 2)
            for (p in points) {
                assertTrue("$kind x ${p[0]}", p[0].isFinite() && p[0] in 60f..340f)
                assertTrue("$kind y ${p[1]}", p[1].isFinite() && p[1] in 10f..290f)
            }
        }
    }

    @Test
    fun nearlySquareDragBecomesSquare() {
        val end = squaredEnd(0f, 0f, 100f, -95f)
        assertNotNull(end)
        assertEquals(97.5f, end!![0], 0.01f)
        assertEquals(-97.5f, end[1], 0.01f)
        assertNull(squaredEnd(0f, 0f, 100f, 60f))
    }
}
