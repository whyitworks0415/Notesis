package com.notesis

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ShapeGeometryTest {
    @Test fun `near horizontal and vertical lines snap to their axis`() {
        assertArrayEquals(floatArrayOf(100f, 20f), snappedLineEnd(10f, 20f, 100f, 30f, true), 0f)
        assertArrayEquals(floatArrayOf(10f, 120f), snappedLineEnd(10f, 20f, 20f, 120f, true), 0f)
    }

    @Test fun `diagonal lines and disabled snapping keep the raw endpoint`() {
        assertArrayEquals(floatArrayOf(100f, 70f), snappedLineEnd(10f, 20f, 100f, 70f, true), 0f)
        assertArrayEquals(floatArrayOf(100f, 30f), snappedLineEnd(10f, 20f, 100f, 30f, false), 0f)
    }
}
