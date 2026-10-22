package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

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

    // ---- closed shapes ----------------------------------------------------------

    @Test fun `noisy triangle keeps its three drawn corners`() {
        val drawn = listOf(pt(100f, 320f), pt(240f, 70f), pt(410f, 300f))
        for (seed in 0 until 25) for (held in listOf(true, false)) {
            val shape = recognizeShape(handDrawn(drawn, closed = true, seed = seed), held)
            assertNotNull("seed $seed held $held", shape)
            assertEquals("seed $seed held $held", ShapeKind.TRIANGLE, shape!!.kind)
            assertCornersNear(drawn, closedCorners(shape), 14f, "seed $seed")
        }
    }

    @Test fun `level rectangle becomes an exact level rectangle`() {
        val drawn = listOf(pt(80f, 90f), pt(420f, 90f), pt(420f, 300f), pt(80f, 300f))
        for (seed in 0 until 25) for (held in listOf(true, false)) {
            val shape = recognizeShape(handDrawn(drawn, closed = true, seed = seed), held)
            assertNotNull("seed $seed held $held", shape)
            assertEquals(ShapeKind.RECT, shape!!.kind)
            // Level: the plain box the rectangle tool draws.
            assertNull(shape.outline)
            assertEquals(80f, min(shape.fromX, shape.toX), 8f)
            assertEquals(420f, max(shape.fromX, shape.toX), 8f)
            assertEquals(90f, min(shape.fromY, shape.toY), 8f)
            assertEquals(300f, max(shape.fromY, shape.toY), 8f)
        }
    }

    @Test fun `tilted rectangle stays tilted with four square corners`() {
        val drawn = rotated(listOf(pt(-160f, -90f), pt(160f, -90f), pt(160f, 90f), pt(-160f, 90f)), 20f, 300f, 300f)
        for (seed in 0 until 25) {
            val shape = recognizeShape(handDrawn(drawn, closed = true, seed = seed), held = true)
            assertNotNull("seed $seed", shape)
            assertEquals(ShapeKind.RECT, shape!!.kind)
            val corners = closedCorners(shape)
            assertEquals(4, corners.size)
            assertCornersNear(drawn, corners, 12f, "seed $seed")
            for (i in 0 until 4) {
                val angle = interior(corners[(i + 3) % 4], corners[i], corners[(i + 1) % 4])
                assertEquals("seed $seed corner $i", 90f, angle, 0.05f)
            }
        }
    }

    @Test fun `uneven quadrilateral always has exactly its four drawn corners`() {
        val quads = listOf(
            listOf(pt(100f, 100f), pt(400f, 130f), pt(360f, 340f), pt(40f, 260f)),
            listOf(pt(200f, 60f), pt(420f, 230f), pt(230f, 330f), pt(70f, 200f)),
            // A trapezoid with one short side.
            listOf(pt(160f, 100f), pt(280f, 100f), pt(420f, 320f), pt(60f, 320f)),
        )
        for ((q, drawn) in quads.withIndex()) for (seed in 0 until 40) {
            val noise = if (seed % 2 == 0) 1.5f else 3f
            val stroke = handDrawn(drawn, closed = true, seed = seed, noise = noise, startAt = seed % 4)
            val shape = recognizeShape(stroke, held = true)
            assertNotNull("quad $q seed $seed", shape)
            assertEquals("quad $q seed $seed", ShapeKind.POLYGON, shape!!.kind)
            val corners = closedCorners(shape)
            assertEquals("quad $q seed $seed", 4, corners.size)
            assertCornersNear(drawn, corners, 16f, "quad $q seed $seed")
            // Unheld, an uneven quadrilateral is not one of the shapes looked for.
            val auto = recognizeShape(stroke, held = false)
            assertTrue("quad $q seed $seed auto", auto == null || closedCorners(auto).size == 4)
        }
    }

    @Test fun `round circle snaps to a perfect circle`() {
        for (seed in 0 until 20) {
            val stroke = handDrawnEllipse(300f, 280f, 120f, 110f, seed)
            val shape = recognizeShape(stroke, held = seed % 2 == 0)
            assertNotNull("seed $seed", shape)
            assertEquals(ShapeKind.OVAL, shape!!.kind)
            assertEquals(abs(shape.toX - shape.fromX), abs(shape.toY - shape.fromY), 0.01f)
            assertEquals(300f, (shape.fromX + shape.toX) / 2f, 8f)
            assertEquals(280f, (shape.fromY + shape.toY) / 2f, 8f)
        }
    }

    @Test fun `ellipse keeps its proportions`() {
        for (seed in 0 until 20) {
            val shape = recognizeShape(handDrawnEllipse(300f, 280f, 170f, 80f, seed), held = true)
            assertNotNull("seed $seed", shape)
            assertEquals(ShapeKind.OVAL, shape!!.kind)
            assertEquals(340f, abs(shape.toX - shape.fromX), 14f)
            assertEquals(160f, abs(shape.toY - shape.fromY), 14f)
        }
    }

    @Test fun `circle outline is smooth and evenly sampled`() {
        val shape = recognizeShape(handDrawnEllipse(300f, 280f, 120f, 116f, 3), held = true)!!
        val outline = shapeOutline(shape.kind, shape.fromX, shape.fromY, shape.toX, shape.toY, axisSnap = false)
        val steps = outline.zipWithNext { a, b -> hypot(b[0] - a[0], b[1] - a[1]) }
        assertTrue(steps.max() - steps.min() < 0.5f)
        val inputs = shapeInputs(outline)
        // No corner rests on the way round: only the end.
        assertEquals(outline.size + 2, inputs.size)
    }

    @Test fun `open v is not closed into a triangle`() {
        val drawn = listOf(pt(200f, 80f), pt(225f, 330f), pt(250f, 80f))
        val shape = recognizeShape(handDrawn(drawn, closed = false, seed = 4), held = true)
        assertNotNull(shape)
        assertEquals(ShapeKind.POLYGON, shape!!.kind)
        assertEquals(3, shape.outline!!.size)
    }

    // ---- open shapes ------------------------------------------------------------

    @Test fun `held L becomes a bent line with its corner where it was drawn`() {
        val drawn = listOf(pt(100f, 80f), pt(110f, 350f), pt(340f, 340f))
        for (seed in 0 until 25) {
            val stroke = handDrawn(drawn, closed = false, seed = seed)
            val shape = recognizeShape(stroke, held = true)
            assertNotNull("seed $seed", shape)
            assertEquals("seed $seed", ShapeKind.POLYGON, shape!!.kind)
            val outline = shape.outline!!
            assertEquals("seed $seed", 3, outline.size)
            assertCornersNear(drawn, outline, 10f, "seed $seed")
            // Never found unheld: writing is full of corners.
            assertNull("seed $seed", recognizeShape(stroke, held = false))
        }
    }

    @Test fun `held zig-zag keeps every bend`() {
        val drawn = listOf(pt(50f, 100f), pt(130f, 300f), pt(210f, 110f), pt(290f, 300f), pt(380f, 100f))
        for (seed in 0 until 25) {
            val shape = recognizeShape(handDrawn(drawn, closed = false, seed = seed), held = true)
            assertNotNull("seed $seed", shape)
            assertEquals("seed $seed", ShapeKind.POLYGON, shape!!.kind)
            assertEquals("seed $seed", drawn.size, shape.outline!!.size)
            assertCornersNear(drawn, shape.outline!!, 12f, "seed $seed")
            assertTrue(shapeInputs(shape.outline!!, shape.corners).size <= SHAPE_EDITABLE_INPUTS)
        }
    }

    @Test fun `held s-curve with a kink is smooth curves meeting at one sharp corner`() {
        val first = cubic(pt(60f, 320f), pt(40f, 80f), pt(260f, 60f), pt(250f, 250f))
        val second = cubic(pt(250f, 250f), pt(420f, 250f), pt(460f, 460f), pt(300f, 480f))
        val ideal = first + second.drop(1)
        for (seed in 0 until 20) {
            val shape = recognizeShape(jittered(ideal, seed), held = true)
            assertNotNull("seed $seed", shape)
            assertEquals("seed $seed", ShapeKind.CURVE, shape!!.kind)
            val outline = shape.outline!!
            val interior = shape.corners.filter { it in 1 until outline.size - 1 }
            assertEquals("seed $seed", 1, interior.size)
            val kink = outline[interior[0]]
            assertTrue("seed $seed kink", hypot(kink[0] - 250f, kink[1] - 250f) < 12f)
            // Smooth everywhere else, and following what was drawn.
            for (i in 1 until outline.size - 1) {
                if (i == interior[0]) continue
                val turn = turnDegrees(outline[i - 1], outline[i], outline[i + 1])
                assertTrue("seed $seed turn $turn at $i", turn < 20f)
            }
            assertTrue(turnDegrees(outline[interior[0] - 1], kink, outline[interior[0] + 1]) > 60f)
            for (p in outline) assertTrue("seed $seed off the drawing", ideal.minOf { hypot(it[0] - p[0], it[1] - p[1]) } < 10f)
            assertTrue(outline.size > 12)
        }
    }

    @Test fun `held smooth arc becomes one clean curve`() {
        val ideal = cubic(pt(60f, 300f), pt(120f, 60f), pt(360f, 60f), pt(420f, 300f))
        val stroke = jittered(ideal, 7)
        val shape = recognizeShape(stroke, held = true)
        assertNotNull(shape)
        assertEquals(ShapeKind.CURVE, shape!!.kind)
        val outline = shape.outline!!
        for (i in 1 until outline.size - 1) assertTrue(turnDegrees(outline[i - 1], outline[i], outline[i + 1]) < 20f)
        // It starts and ends exactly where the pen did.
        assertEquals(stroke.first()[0], outline.first()[0], 0f)
        assertEquals(stroke.last()[1], outline.last()[1], 0f)
    }

    // ---- writing stays writing ----------------------------------------------------

    @Test fun `scribbles stay as written unheld`() {
        for (seed in 0 until 60) {
            val random = Random(seed)
            var x = 300f
            var y = 300f
            var heading = random.nextFloat() * 6.28f
            val points = (0 until 120).map {
                heading += (random.nextFloat() - 0.5f) * 1.6f
                x += cos(heading) * 5f
                y += sin(heading) * 5f
                pt(x, y)
            }
            val shape = recognizeShape(points, held = false)
            assertTrue("seed $seed ${shape?.kind}", shape == null || shape.kind == ShapeKind.LINE)
        }
    }

    @Test fun `letters stay as written unheld`() {
        for ((name, letter) in letters()) for (scale in listOf(0.5f, 1f, 1.6f)) for (seed in 0 until 5) {
            val points = jittered(letter.map { pt(200f + it[0] * scale, 200f + it[1] * scale) }, seed, noise = 0.8f)
            assertNull("$name x$scale seed $seed", recognizeShape(points, held = false))
        }
    }

    // ---- helpers --------------------------------------------------------------------

    private fun pt(x: Float, y: Float) = floatArrayOf(x, y)

    /** The distinct corners of a closed recognised shape, its box's when it has no outline. */
    private fun closedCorners(shape: RecognizedShape): List<FloatArray> = shape.outline?.dropLast(1) ?: listOf(
        pt(shape.fromX, shape.fromY), pt(shape.toX, shape.fromY), pt(shape.toX, shape.toY), pt(shape.fromX, shape.toY),
    )

    private fun assertCornersNear(expected: List<FloatArray>, actual: List<FloatArray>, tolerance: Float, label: String) {
        assertEquals(label, expected.size, actual.size)
        for (e in expected) {
            val nearest = actual.minOf { hypot(it[0] - e[0], it[1] - e[1]) }
            assertTrue("$label corner (${e[0]}, ${e[1]}) missed by $nearest", nearest <= tolerance)
        }
    }

    private fun rotated(points: List<FloatArray>, degrees: Float, cx: Float, cy: Float): List<FloatArray> {
        val r = Math.toRadians(degrees.toDouble())
        return points.map { pt(cx + (it[0] * cos(r) - it[1] * sin(r)).toFloat(), cy + (it[0] * sin(r) + it[1] * cos(r)).toFloat()) }
    }

    /**
     * A hand's version of the polyline through [corners]: sides that bow a
     * little, uneven sample spacing, jitter, rounded corners, a closed one
     * ending a little short of or past where it began, and the pen resting at
     * the end the way a hold leaves it.
     */
    private fun handDrawn(
        corners: List<FloatArray>,
        closed: Boolean,
        seed: Int,
        noise: Float = 1.5f,
        startAt: Int = 0,
    ): List<FloatArray> {
        val random = Random(seed)
        val ordered = corners.indices.map { corners[(it + startAt) % corners.size] }
        val path = if (closed) ordered + ordered[0] else ordered
        val ideal = ArrayList<FloatArray>()
        for (s in 0 until path.size - 1) {
            val a = path[s]
            val b = path[s + 1]
            val length = hypot(b[0] - a[0], b[1] - a[1])
            val bow = (random.nextFloat() - 0.5f) * 0.03f * length
            var t = 0f
            while (t < 1f) {
                val along = sin(PI * t).toFloat() * bow
                ideal += pt(a[0] + (b[0] - a[0]) * t - (b[1] - a[1]) / length * along,
                    a[1] + (b[1] - a[1]) * t + (b[0] - a[0]) / length * along)
                t += (2f + random.nextFloat() * 4f) / length
            }
        }
        ideal += path.last()
        val rounded = chaikin(ideal, corners = 2)
        val tail = ArrayList(rounded)
        if (closed) {
            // Short of the start, or on past it along the first side.
            val past = (random.nextFloat() - 0.5f) * 40f
            if (past < 0f) repeat((-past / 4f).toInt()) { tail.removeAt(tail.size - 1) }
            else tail.addAll(rounded.subList(1, 1 + (past / 4f).toInt().coerceAtMost(rounded.size - 2)))
        }
        val last = tail.last()
        repeat(8) { tail += pt(last[0], last[1]) }
        return tail.map { pt(it[0] + random.gaussian() * noise, it[1] + random.gaussian() * noise) }
    }

    private fun handDrawnEllipse(cx: Float, cy: Float, rx: Float, ry: Float, seed: Int): List<FloatArray> {
        val random = Random(seed)
        val start = random.nextFloat() * 6.28f
        val sweep = 2 * PI.toFloat() + (random.nextFloat() - 0.5f) * 0.5f
        val wobble = random.nextFloat() * 6.28f
        val points = (0..90).map {
            val t = start + sweep * it / 90
            val r = 1f + 0.025f * sin(3 * t + wobble)
            pt(cx + rx * r * cos(t), cy + ry * r * sin(t))
        }
        return points.map { pt(it[0] + random.gaussian() * 1.2f, it[1] + random.gaussian() * 1.2f) }
    }

    private fun jittered(points: List<FloatArray>, seed: Int, noise: Float = 1.2f): List<FloatArray> {
        val random = Random(seed)
        return points.map { pt(it[0] + random.gaussian() * noise, it[1] + random.gaussian() * noise) }
    }

    /** Rounds a polyline's corners a little, as a hand does, by corner cutting. */
    private fun chaikin(points: List<FloatArray>, corners: Int): List<FloatArray> {
        var current = points
        repeat(corners) {
            val next = ArrayList<FloatArray>()
            next += current.first()
            for (i in 0 until current.size - 1) {
                val a = current[i]
                val b = current[i + 1]
                next += pt(0.75f * a[0] + 0.25f * b[0], 0.75f * a[1] + 0.25f * b[1])
                next += pt(0.25f * a[0] + 0.75f * b[0], 0.25f * a[1] + 0.75f * b[1])
            }
            next += current.last()
            current = next
        }
        return current
    }

    private fun cubic(p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray, steps: Int = 60): List<FloatArray> =
        (0..steps).map {
            val t = it.toFloat() / steps
            val u = 1 - t
            pt(u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
                u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1])
        }

    private fun turnDegrees(a: FloatArray, b: FloatArray, c: FloatArray): Float {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]
        val vx = c[0] - b[0]; val vy = c[1] - b[1]
        val lengths = hypot(ux, uy) * hypot(vx, vy)
        if (lengths == 0f) return 0f
        return Math.toDegrees(acos(((ux * vx + uy * vy) / lengths).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    private fun interior(a: FloatArray, b: FloatArray, c: FloatArray) = 180f - turnDegrees(a, b, c)

    private fun Random.gaussian(): Float {
        // Box-Muller, from the seeded generator so every run is the same.
        val u = nextDouble().coerceAtLeast(1e-9)
        val v = nextDouble()
        return (kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * cos(2 * PI * v)).toFloat()
    }

    /** Cursive-ish letters about 60 units tall, each as one stroke. */
    private fun letters(): Map<String, List<FloatArray>> {
        fun arc(cx: Float, cy: Float, r: Float, from: Float, to: Float, steps: Int = 30) = (0..steps).map {
            val t = Math.toRadians((from + (to - from) * it / steps).toDouble())
            pt(cx + r * cos(t).toFloat(), cy + r * sin(t).toFloat())
        }
        fun line(a: FloatArray, b: FloatArray, steps: Int = 12) = (0..steps).map {
            val t = it.toFloat() / steps
            pt(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        }
        return mapOf(
            "e" to line(pt(0f, 0f), pt(56f, 0f)) + arc(28f, 0f, 28f, 0f, -300f),
            "a" to arc(25f, 0f, 25f, -30f, -390f) + line(pt(47f, -12f), pt(50f, 30f)),
            "m" to line(pt(0f, 30f), pt(0f, -20f)) + arc(15f, -15f, 15f, 180f, 360f) + line(pt(30f, -15f), pt(30f, 30f)) +
                arc(45f, -15f, 15f, 180f, 360f) + line(pt(60f, -15f), pt(60f, 30f)),
            "s" to arc(25f, -15f, 16f, -20f, -270f) + arc(25f, 17f, 16f, -90f, 160f),
            "z" to line(pt(0f, -30f), pt(50f, -30f)) + line(pt(50f, -30f), pt(0f, 30f)) + line(pt(0f, 30f), pt(52f, 30f)),
            "w" to line(pt(0f, -30f), pt(12f, 30f)) + line(pt(12f, 30f), pt(25f, -10f)) + line(pt(25f, -10f), pt(38f, 30f)) +
                line(pt(38f, 30f), pt(50f, -30f)),
            "8" to arc(0f, -15f, 15f, 90f, 450f) + arc(0f, 17f, 17f, -90f, -450f),
            "D" to line(pt(0f, -30f), pt(0f, 30f)) + arc(0f, 0f, 30f, 90f, -90f),
            "g" to arc(20f, 0f, 20f, 0f, -360f) + arc(20f, 25f, 20f, 0f, 160f),
            "2" to arc(25f, -12f, 20f, -160f, 40f) + line(pt(40f, 1f), pt(0f, 35f)) + line(pt(0f, 35f), pt(50f, 35f)),
        )
    }
}
