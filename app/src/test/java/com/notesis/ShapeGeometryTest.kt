package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

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

    @Test
    fun roundedSquareStaysClosedAndInsideItsCorners() {
        val square = shapeOutline(ShapeKind.RECT, 0f, 0f, 100f, 100f, axisSnap = false)
        val round = roundCorners(square, 20f)
        assertTrue(round.size > square.size)
        assertEquals(round.first()[0], round.last()[0], 0.001f)
        assertEquals(round.first()[1], round.last()[1], 0.001f)
        // No point reaches the sharp corner any more.
        assertTrue(round.none { it[0] < 1f && it[1] < 1f })
    }

    @Test
    fun polygonInputsRestOnEveryCornerAndStayEditable() {
        val polygons = listOf(
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW, ShapeKind.RECT, ShapeKind.TRIANGLE,
            ShapeKind.RIGHT_TRIANGLE, ShapeKind.DIAMOND, ShapeKind.PARALLELOGRAM, ShapeKind.TRAPEZOID,
            ShapeKind.PENTAGON, ShapeKind.HEXAGON, ShapeKind.STAR, ShapeKind.CUBE,
        )
        for (kind in polygons) {
            val outline = shapeOutline(kind, 100f, 50f, 300f, 250f, axisSnap = false)
            val inputs = shapeInputs(outline)
            assertTrue("$kind has ${inputs.size} inputs", inputs.size <= SHAPE_EDITABLE_INPUTS)
            assertTrue("$kind time runs forward", inputs.zipWithNext().all { (a, b) -> b.timeMs > a.timeMs })
            assertEquals(outline.first()[0], inputs.first().x, 0f)
            assertEquals(outline.last()[1], inputs.last().y, 0f)
            // Every corner after the first is repeated where it is, exactly.
            for (i in 1 until outline.size) {
                val p = outline[i]
                val at = inputs.count { it.x == p[0] && it.y == p[1] }
                assertTrue("$kind corner $i has $at inputs", at >= 2)
            }
            // Nothing but the outline's own points.
            for (input in inputs) assertTrue(outline.any { it[0] == input.x && it[1] == input.y })
        }
    }

    @Test
    fun repeatsOfACornerAreConsecutiveSoTheyDragTogether() {
        val outline = shapeOutline(ShapeKind.RECT, 0f, 0f, 300f, 100f, axisSnap = false)
        val inputs = shapeInputs(outline)
        assertEquals(1 + 4 * 3, inputs.size)
        var i = 1
        for (corner in outline.drop(1)) {
            repeat(3) {
                assertEquals(corner[0], inputs[i].x, 0f)
                assertEquals(corner[1], inputs[i].y, 0f)
                i++
            }
        }
    }

    @Test
    fun sidesTakeTimeInProportionToTheirLength() {
        val inputs = shapeInputs(shapeOutline(ShapeKind.RECT, 0f, 0f, 300f, 100f, axisSnap = false))
        // Leaving a corner's last repeat, arriving at the next corner.
        val long = inputs[1].timeMs - inputs[0].timeMs
        val short = inputs[4].timeMs - inputs[3].timeMs
        assertEquals(3f, long.toFloat() / short, 0.1f)
        // Rests are long enough for the input model to settle.
        assertTrue(inputs[3].timeMs - inputs[1].timeMs >= 100)
    }

    @Test
    fun manyCornersRestOnceEachRatherThanNotAtAll() {
        val zigzag = (0..9).map { floatArrayOf(it * 30f, if (it % 2 == 0) 0f else 80f) }
        val inputs = shapeInputs(zigzag)
        assertEquals(10 + 9, inputs.size)
        assertTrue(inputs.size <= SHAPE_EDITABLE_INPUTS)
    }

    @Test
    fun curvesFlowWithoutRestsAndTurnSlowly() {
        val oval = shapeOutline(ShapeKind.OVAL, 0f, 0f, 40f, 40f, axisSnap = false)
        val inputs = shapeInputs(oval)
        assertEquals(oval.size + 2, inputs.size)
        // A small circle is not whipped round in a few milliseconds.
        assertTrue(inputs[oval.size - 1].timeMs >= 600)
        // A big one is drawn at about a steady speed.
        val big = shapeInputs(shapeOutline(ShapeKind.OVAL, 0f, 0f, 800f, 800f, axisSnap = false))
        val perimeter = PI * 800
        assertEquals(perimeter / 1.2, big[64].timeMs.toDouble(), perimeter / 1.2 * 0.1)
    }

    @Test
    fun explicitCornersRestEvenWhereTheTurnIsGentle() {
        val bend = listOf(floatArrayOf(0f, 0f), floatArrayOf(100f, 0f), floatArrayOf(200f, 20f))
        assertEquals(3 + 2, shapeInputs(bend).size)
        assertEquals(3 + 4, shapeInputs(bend, corners = listOf(1)).size)
    }

    @Test
    fun aDotIsStillAStroke() {
        val inputs = shapeInputs(listOf(floatArrayOf(5f, 5f), floatArrayOf(5f, 5f)))
        assertEquals(2, inputs.size)
        assertTrue(inputs[1].timeMs > inputs[0].timeMs)
    }
}
