package com.notesis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.hypot

class HeldShapeTest {
    private fun pt(x: Float, y: Float) = floatArrayOf(x, y)

    @Test fun `each recognised shape follows the pen its own way`() {
        assertEquals(HeldReshape.END, heldReshapeOf(RecognizedShape(ShapeKind.LINE, 0f, 0f, 10f, 0f)))
        assertEquals(HeldReshape.BOX, heldReshapeOf(RecognizedShape(ShapeKind.RECT, 0f, 0f, 10f, 10f)))
        assertEquals(HeldReshape.BOX, heldReshapeOf(RecognizedShape(ShapeKind.OVAL, 0f, 0f, 10f, 10f)))
        val bent = listOf(pt(0f, 0f), pt(10f, 0f), pt(10f, 10f))
        assertEquals(HeldReshape.LAST_VERTEX,
            heldReshapeOf(RecognizedShape(ShapeKind.POLYGON, 0f, 0f, 10f, 10f, outline = bent)))
        val triangle = listOf(pt(0f, 0f), pt(10f, 0f), pt(5f, 8f), pt(0f, 0f))
        assertEquals(HeldReshape.SIMILAR,
            heldReshapeOf(RecognizedShape(ShapeKind.TRIANGLE, 0f, 0f, 10f, 8f, outline = triangle)))
    }

    @Test fun `a closed shape turns and scales about its middle so the grabbed point stays under the pen`() {
        val square = listOf(pt(0f, 0f), pt(10f, 0f), pt(10f, 10f), pt(0f, 10f), pt(0f, 0f))
        val pivot = reshapePivot(square)
        assertArrayEquals(floatArrayOf(5f, 5f), pivot, 1e-4f)
        // Grabbed at the corner (0, 0), pulled out to twice as far along the same diagonal.
        val bigger = similarTo(square, pivot[0], pivot[1], 0f, 0f, -5f, -5f)
        assertArrayEquals(floatArrayOf(-5f, -5f), bigger[0], 1e-3f)
        assertArrayEquals(floatArrayOf(15f, 15f), bigger[2], 1e-3f)
        // A quarter turn about the middle.
        val turned = similarTo(square, 5f, 5f, 0f, 0f, 10f, 0f)
        assertArrayEquals(floatArrayOf(10f, 0f), turned[0], 1e-3f)
        assertEquals(hypot(10f, 10f), hypot(turned[2][0] - turned[0][0], turned[2][1] - turned[0][1]), 1e-3f)
    }

    @Test fun `an open curve keeps its start and brings its end to the pen`() {
        val curve = listOf(pt(0f, 0f), pt(5f, 3f), pt(10f, 0f))
        val pivot = reshapePivot(curve)
        assertArrayEquals(floatArrayOf(0f, 0f), pivot, 0f)
        val longer = similarTo(curve, 0f, 0f, 10f, 0f, 20f, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), longer[0], 1e-4f)
        assertArrayEquals(floatArrayOf(20f, 0f), longer[2], 1e-3f)
        assertArrayEquals(floatArrayOf(10f, 6f), longer[1], 1e-3f)
    }

    @Test fun `a bent line moves only its last corner`() {
        val bent = listOf(pt(0f, 0f), pt(10f, 0f), pt(10f, 10f))
        val moved = withLastVertexMoved(bent, 3f, 4f)
        assertArrayEquals(floatArrayOf(0f, 0f), moved[0], 0f)
        assertArrayEquals(floatArrayOf(10f, 0f), moved[1], 0f)
        assertArrayEquals(floatArrayOf(13f, 14f), moved[2], 0f)
        // The original is untouched, so every move starts from where the shape was made.
        assertArrayEquals(floatArrayOf(10f, 10f), bent[2], 0f)
    }

    @Test fun `a box grows from the corner opposite the one the pen rested by`() {
        // Drawn from the bottom right, so the pen rested there; that corner moves.
        val box = boxDraggedFrom(10f, 10f, 110f, 60f, grabX = 108f, grabY = 58f, dx = 20f, dy = 10f)
        assertArrayEquals(floatArrayOf(10f, 10f, 130f, 70f), box, 1e-4f)
        // Rested by the top left instead: the bottom right stays.
        val other = boxDraggedFrom(10f, 10f, 110f, 60f, grabX = 12f, grabY = 11f, dx = -5f, dy = -5f)
        assertArrayEquals(floatArrayOf(110f, 60f, 5f, 5f), other, 1e-4f)
    }
}
