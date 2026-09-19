package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ShapeRecognitionTest {
    @Test fun `straight gesture becomes a line`() {
        val points = (0..20).map { floatArrayOf(it * 10f, it * 2f + (it % 2)) }
        assertEquals(ShapeKind.LINE, recognizeShape(points)?.kind)
    }

    @Test fun `closed ellipse becomes an oval`() {
        val points = (0..64).map { i ->
            val angle = i * Math.PI * 2 / 64
            floatArrayOf(200f + 120f * cos(angle).toFloat(),
                180f + 70f * sin(angle).toFloat())
        }
        assertEquals(ShapeKind.OVAL, recognizeShape(points)?.kind)
    }

    @Test fun `short uncertain gesture stays freehand`() {
        assertNull(recognizeShape(listOf(floatArrayOf(0f, 0f), floatArrayOf(2f, 3f))))
    }
}
