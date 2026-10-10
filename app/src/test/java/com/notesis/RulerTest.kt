package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class RulerTest {
    @Test
    fun penNearTheTopEdgeLandsOnItOutsideTheBody() {
        val edges = rulerEdges(RulerKind.RULER, 400f)
        val depth = 400f * RULER_DEPTH
        val edge = edgeNear(edges, 50f, -depth / 2f - 5f, 20f)
        assertNotNull(edge)
        val out = FloatArray(2)
        projectOnEdge(edge!!, 50f, -depth / 2f - 5f, 3f, out)
        assertEquals(50f, out[0], 0.01f)
        assertEquals(-depth / 2f - 3f, out[1], 0.01f)
    }

    @Test
    fun setSquareHypotenusePushesInkAwayFromTheBody() {
        val hypotenuse = rulerEdges(RulerKind.TRIANGLE, 200f)[2]
        val out = FloatArray(2)
        projectOnEdge(hypotenuse, 10f, 10f, 5f, out)
        // Inside is y > x; outside is below the diagonal.
        assertTrue(out[1] < out[0])
    }

    @Test
    fun nearATickLandsOnIt() {
        val edge = rulerEdges(RulerKind.RULER, 400f)[0]
        val out = FloatArray(2)
        // Ten pixels a tick from the ruler's left end at -200: 52.5 from it is 2.5 off the 50 tick.
        projectOnEdge(edge, -200f + 52.5f, edge.ay, 0f, out, tick = 10f)
        assertEquals(-150f, out[0], 0.01f)
        projectOnEdge(edge, -200f + 55f, edge.ay, 0f, out, tick = 10f)
        assertEquals(-145f, out[0], 0.01f)
    }

    @Test
    fun farFromEveryEdgeWritesFreely() {
        assertNull(edgeNear(rulerEdges(RulerKind.RULER, 400f), 0f, 300f, 20f))
    }

    @Test
    fun protractorArcKeepsItsRadius() {
        val edges = rulerEdges(RulerKind.PROTRACTOR, 100f)
        val edge = edgeNear(edges, 60f, -82f, 10f)
        assertTrue(edge!!.isArc)
        val out = FloatArray(2)
        projectOnEdge(edge, 60f, -82f, 2f, out)
        assertEquals(102f, hypot(out[0], out[1]), 0.01f)
    }

    @Test
    fun middleTapSnapsToNearestFortyFive() {
        assertEquals(45f, snapTo45(52f), 0f)
        assertEquals(0f, snapTo45(350f), 0f)
    }
}
