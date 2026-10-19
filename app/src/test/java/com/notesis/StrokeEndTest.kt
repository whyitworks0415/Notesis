package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class StrokeEndTest {
    @Test
    fun `short contact reversal is a spur`() {
        assertTrue(isContactSpur(0f, 0f, 3f, 0f, 0.4f, 0.1f, 16L, 5f))
    }

    @Test
    fun `intentional turn and slow backtrack are preserved`() {
        assertFalse(isContactSpur(0f, 0f, 5f, 0f, 5f, 5f, 16L, 5f))
        assertFalse(isContactSpur(0f, 0f, 3f, 0f, 0.4f, 0f, 90L, 5f))
        assertFalse(isContactSpur(0f, 0f, 12f, 0f, 0.4f, 0f, 16L, 5f))
    }

    // The first stroke of a reported note: an 11-wide pen whose contact sample
    // landed 0.35 ahead of the three after it. The old fixed 0.3 threshold took
    // the contact sample's neighbour as the first movement and kept the cluster.
    private val reportedX = floatArrayOf(658.52f, 658.17f, 658.24f, 658.35f, 658.82f, 659.02f,
        659.65f, 659.90f, 660.65f, 661.23f, 661.80f, 662.37f)
    private val reportedY = floatArrayOf(769.29f, 769.29f, 769.29f, 769.29f, 769.29f, 769.25f,
        769.16f, 769.04f, 768.90f, 768.78f, 768.60f, 768.41f)
    private val reportedT = longArrayOf(0, 10, 16, 19, 20, 23, 25, 27, 29, 31, 33, 35)

    @Test
    fun `contact sample ahead of the cluster still drops the cluster`() {
        val first = firstMovementIndex(
            reportedX.size, stationaryStartThreshold(11f),
            { reportedX[it] }, { reportedY[it] }, { reportedT[it] },
        )
        assertEquals(9, first)
    }

    @Test
    fun `a pen that never leaves the contact point keeps every sample`() {
        val first = firstMovementIndex(
            4, stationaryStartThreshold(11f),
            { reportedX[it] }, { reportedY[it] }, { reportedT[it] },
        )
        assertEquals(-1, first)
    }

    @Test
    fun `fine pens keep a floor on the threshold`() {
        assertEquals(0.3f, stationaryStartThreshold(0.5f), 0f)
        assertEquals(2.75f, stationaryStartThreshold(11f), 1e-6f)
    }

    // The apex of a hairpin turn in a reported 1.4-wide stroke: the pen slows,
    // wobbles a hundredth of a unit back and forth, and comes back down.
    private val hairpinX = floatArrayOf(999.22f, 999.34f, 999.46f, 999.56f, 999.62f, 999.7f, 999.76f, 999.8f,
        999.84f, 999.85f, 999.87f, 999.87f, 999.86f, 999.83f, 999.77f, 999.67f, 999.56f, 999.42f, 999.26f,
        999.1f, 998.92f, 998.72f, 998.53f, 998.31f, 998.1f, 997.87f)
    private val hairpinY = floatArrayOf(26.5f, 26.34f, 26.19f, 26.07f, 25.98f, 25.89f, 25.82f, 25.77f,
        25.72f, 25.7f, 25.68f, 25.67f, 25.69f, 25.75f, 25.82f, 25.94f, 26.08f, 26.24f, 26.43f, 26.64f,
        26.88f, 27.15f, 27.43f, 27.73f, 28.04f, 28.36f)

    @Test
    fun `a wobbling hairpin apex folds to its furthest sample`() {
        val keep = dwellKeepMask(hairpinX.size, stationaryStartThreshold(1.4f), { hairpinX[it] }, { hairpinY[it] })!!
        assertEquals(listOf(5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16), keep.indices.filter { !keep[it] })
        // The kept apex is the one furthest up the incoming line.
        assertTrue(keep[11])
    }

    @Test
    fun `a smooth turn without doubling back is left alone`() {
        // A quarter circle of radius 3 sampled every 0.2: no step points back.
        val count = 24
        val xs = FloatArray(count) { 3f * kotlin.math.cos(it * 0.0683f) }
        val ys = FloatArray(count) { 3f * kotlin.math.sin(it * 0.0683f) }
        assertEquals(null, dwellKeepMask(count, stationaryStartThreshold(1.4f), { xs[it] }, { ys[it] }))
    }

    // The masks below are compared against the algorithms as they stood
    // before dwellKeepMask was made linear and the pen-up tidying was folded
    // into one pass: kept here, and only here, as the reference the faster
    // versions must agree with sample for sample.

    @Test
    fun `dwell mask matches the reference on random strokes`() {
        val random = Random(20261019)
        repeat(6000) { case ->
            val stroke = randomStroke(random)
            val radius = RADII[case % RADII.size]
            assertSameMask("case $case radius $radius", referenceDwellKeepMask(stroke.xs, stroke.ys, radius),
                dwellKeepMask(stroke.xs.size, radius, stroke.xs, stroke.ys))
        }
    }

    @Test
    fun `dwell mask matches the reference on adversarial strokes`() {
        val random = Random(7)
        val cases = ArrayList<Pair<FloatArray, FloatArray>>()
        // Dead still: every step is zero, so nothing ever counts as turning,
        // with a turn only at the very end, then the same with it at the start.
        for (n in listOf(5, 6, 7, 50, 1500)) {
            val xs = FloatArray(n) { 10f }
            val ys = FloatArray(n) { 20f }
            xs[n - 2] = 10.2f
            cases += xs to ys
            val ys2 = ys.copyOf()
            val xs2 = xs.copyOf().also { it[n - 2] = 10f; it[1] = 10.2f }
            cases += xs2 to ys2
        }
        // A slow straight creep, far slower than the radius, with a hairpin at the end.
        for (step in floatArrayOf(0.001f, 0.01f, 0.07f)) {
            val n = 1200
            val xs = FloatArray(n) { if (it < n - 20) it * step else (n - 20) * step - (it - (n - 20)) * step }
            val ys = FloatArray(n) { 5f }
            cases += xs to ys
        }
        // Zigzags of every amplitude around the radius, with and without drift.
        for (amplitude in floatArrayOf(0.01f, 0.1f, 0.29f, 0.3f, 0.31f, 0.6f, 2f)) {
            for (drift in floatArrayOf(0f, 0.02f, 0.3f)) {
                val n = 300
                cases += FloatArray(n) { it * drift } to FloatArray(n) { if (it % 2 == 0) 0f else amplitude }
                cases += FloatArray(n) { it * drift + if (it % 3 == 0) amplitude else 0f } to FloatArray(n) { 0f }
            }
        }
        // Hairpins that slow into the apex and wobble there.
        repeat(200) {
            val n = 20 + random.nextInt(200)
            val apex = n / 2
            val speed = 0.02f + random.nextFloat()
            val wobble = random.nextFloat() * 0.2f
            val xs = FloatArray(n)
            val ys = FloatArray(n)
            for (k in 0 until n) {
                val fromApex = kotlin.math.abs(k - apex).toFloat()
                xs[k] = 100f - fromApex * fromApex * speed * 0.05f + (random.nextFloat() - 0.5f) * wobble
                ys[k] = (k - apex) * speed * 0.3f + (random.nextFloat() - 0.5f) * wobble
            }
            cases += xs to ys
        }
        // Long stationary clusters of jitter joined by moves, and blocks of exact repeats.
        repeat(100) {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            var x = 0f
            var y = 0f
            repeat(1 + random.nextInt(6)) {
                val jitter = floatArrayOf(0f, 1e-5f, 0.003f, 0.05f, 0.2f)[random.nextInt(5)]
                repeat(random.nextInt(400)) {
                    xs += x + (random.nextFloat() - 0.5f) * jitter
                    ys += y + (random.nextFloat() - 0.5f) * jitter
                }
                repeat(random.nextInt(10)) { x += random.nextFloat() * 2f; y += random.nextFloat() - 0.5f; xs += x; ys += y }
            }
            cases += xs.toFloatArray() to ys.toFloatArray()
        }
        // Points on a grid, so distances land exactly on the radius.
        repeat(400) {
            val n = 5 + random.nextInt(80)
            cases += FloatArray(n) { random.nextInt(3) * 0.5f } to FloatArray(n) { random.nextInt(3) * 0.5f }
        }
        for ((index, case) in cases.withIndex()) {
            val (xs, ys) = case
            for (radius in floatArrayOf(0.3f, 0.5f, 1f, 2.75f)) {
                assertSameMask("adversarial $index radius $radius", referenceDwellKeepMask(xs, ys, radius),
                    dwellKeepMask(xs.size, radius, xs, ys))
            }
        }
    }

    @Test
    fun `dwell mask handles samples that are not finite like the reference`() {
        val random = Random(99)
        val specials = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        repeat(2000) { case ->
            val stroke = randomStroke(random)
            repeat(1 + random.nextInt(3)) {
                if (stroke.xs.isNotEmpty()) {
                    val at = random.nextInt(stroke.xs.size)
                    if (random.nextBoolean()) stroke.xs[at] = specials[random.nextInt(3)]
                    else stroke.ys[at] = specials[random.nextInt(3)]
                }
            }
            assertSameMask("special $case", referenceDwellKeepMask(stroke.xs, stroke.ys, 0.5f),
                dwellKeepMask(stroke.xs.size, 0.5f, stroke.xs, stroke.ys))
        }
    }

    @Test
    fun `dwell mask through lambdas matches the array form`() {
        val random = Random(3)
        repeat(500) {
            val stroke = randomStroke(random)
            assertSameMask("lambda $it", dwellKeepMask(stroke.xs.size, 0.35f, stroke.xs, stroke.ys),
                dwellKeepMask(stroke.xs.size, 0.35f, { stroke.xs[it] }, { stroke.ys[it] }))
        }
    }

    @Test
    fun `contact spurs match the reference`() {
        val random = Random(41)
        var found = 0
        repeat(6000) { case ->
            val stroke = randomStroke(random)
            val width = WIDTHS[case % WIDTHS.size]
            val expected = referenceContactSpurs(stroke.xs, stroke.ys, stroke.ts, width)
            val actual = findContactSpurs(stroke.xs.size, width, { stroke.xs[it] }, { stroke.ys[it] },
                { stroke.ts[it] }) { start, end -> start to end }
            assertEquals("case $case", expected, actual)
            if (actual.first >= 0 || actual.second >= 0) found++
        }
        // The generator has to actually produce spurs for this to mean anything.
        assertTrue("only $found strokes had a spur", found > 300)
    }

    @Test
    fun `pen tidying matches the reference`() {
        val random = Random(5)
        repeat(6000) { case ->
            val stroke = randomStroke(random)
            val width = WIDTHS[case % WIDTHS.size]
            assertSameMask("case $case width $width",
                referencePenTidyKeepMask(stroke.xs, stroke.ys, stroke.ts, width),
                penTidyKeepMask(stroke.xs.size, width, stroke.xs, stroke.ys, stroke.ts))
            assertEquals(
                referenceFirstMovementIndex(stroke.xs, stroke.ys, stroke.ts, stationaryStartThreshold(width)),
                firstMovementIndex(stroke.xs.size, stationaryStartThreshold(width), stroke.xs, stroke.ys, stroke.ts),
            )
        }
    }

    @Test
    fun `one pass pen tidying keeps what spur removal then tidying kept`() {
        val random = Random(11)
        var changed = 0
        repeat(8000) { case ->
            val stroke = randomStroke(random)
            val width = WIDTHS[case % WIDTHS.size]
            val xs = stroke.xs.copyOf()
            val expected = referenceAfterSpurs(stroke, width) { cx, cy, ct ->
                referencePenTidyKeepMask(cx, cy, ct, width)
            }
            val actual = tidiedPenKeepMask(stroke.xs.size, width, stroke.xs, stroke.ys, stroke.ts)
            assertSameMask("case $case width $width", expected, actual)
            assertTrue("inputs were changed", xs.contentEquals(stroke.xs))
            if (actual != null) changed++
        }
        assertTrue(changed > 500)
    }

    @Test
    fun `one pass freehand tidying keeps what spur removal then dwell removal kept`() {
        val random = Random(13)
        repeat(8000) { case ->
            val stroke = randomStroke(random)
            val width = WIDTHS[case % WIDTHS.size]
            val expected = referenceAfterSpurs(stroke, width) { cx, cy, _ ->
                referenceDwellKeepMask(cx, cy, stationaryStartThreshold(width))
            }
            assertSameMask("case $case width $width", expected,
                spurAndDwellKeepMask(stroke.xs.size, width, stroke.xs, stroke.ys, stroke.ts))
        }
    }

    @Test
    fun `the reported strokes tidy the same in one pass`() {
        val first = tidiedPenKeepMask(reportedX.size, 11f, reportedX, reportedY, reportedT)!!
        assertEquals((1..8).toList(), first.indices.filter { !first[it] })
        val times = LongArray(hairpinX.size) { it * 4L }
        val hairpin = spurAndDwellKeepMask(hairpinX.size, 1.4f, hairpinX, hairpinY, times)!!
        assertEquals(listOf(5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16), hairpin.indices.filter { !hairpin[it] })
    }

    private class Samples(val xs: FloatArray, val ys: FloatArray, val ts: LongArray)

    /**
     * A pen stroke of a random kind: wandering, creeping, held still with
     * jitter or exact repeats, zigzagging, turning hairpins, sometimes with a
     * tiny out-and-back at contact or lift and with times that bunch up.
     */
    private fun randomStroke(random: Random): Samples {
        val n = when (random.nextInt(10)) {
            0 -> random.nextInt(8)
            9 -> 200 + random.nextInt(400)
            else -> 5 + random.nextInt(80)
        }
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val ts = LongArray(n)
        var x = random.nextFloat() * 1000f
        var y = random.nextFloat() * 1000f
        var heading = random.nextFloat() * 6.2832f
        var t = 0L
        val kind = random.nextInt(6)
        val step = floatArrayOf(0.001f, 0.02f, 0.1f, 0.3f, 1f, 3f)[random.nextInt(6)]
        val jitter = floatArrayOf(0f, 1e-5f, 0.01f, 0.1f, 0.4f)[random.nextInt(5)]
        for (k in 0 until n) {
            when (kind) {
                0 -> { heading += (random.nextFloat() - 0.5f) * 1.5f; x += cos(heading) * step; y += sin(heading) * step }
                1 -> { x += step * 0.1f }
                2 -> if (random.nextInt(20) == 0) { x += step * 5f; y += step }
                3 -> { x += if (k % 2 == 0) step else -step * 0.8f; y += step * 0.05f }
                4 -> { if (k == n / 2) heading += 3.1416f; x += cos(heading) * step * (0.1f + kotlin.math.abs(k - n / 2) * 0.05f); y += sin(heading) * step }
                else -> if (random.nextInt(3) == 0) { heading += (random.nextFloat() - 0.5f) * 4f; x += cos(heading) * step; y += sin(heading) * step }
            }
            xs[k] = x + (random.nextFloat() - 0.5f) * jitter
            ys[k] = y + (random.nextFloat() - 0.5f) * jitter
            t += if (random.nextInt(4) == 0) 0L else random.nextLong(1L, if (random.nextInt(8) == 0) 60L else 12L)
            ts[k] = t
        }
        // A spur: the tip darts out about a pen width and comes straight back.
        fun spur(at: Int) {
            if (at < 1 || at >= n - 1) return
            val size = 0.5f + random.nextFloat() * 4f
            val angle = random.nextFloat() * 6.2832f
            xs[at] = xs[at - 1] + cos(angle) * size
            ys[at] = ys[at - 1] + sin(angle) * size
            xs[at + 1] = xs[at - 1] + (random.nextFloat() - 0.5f) * 0.5f
            ys[at + 1] = ys[at - 1] + (random.nextFloat() - 0.5f) * 0.5f
        }
        if (random.nextInt(3) == 0) spur(1 + random.nextInt(5))
        if (random.nextInt(3) == 0) spur(n - 2 - random.nextInt(5))
        return Samples(xs, ys, ts)
    }

    private fun assertSameMask(message: String, expected: BooleanArray?, actual: BooleanArray?) {
        if (expected == null || actual == null) {
            assertEquals(message, expected?.toList(), actual?.toList())
        } else {
            assertTrue("$message: ${expected.toList()} vs ${actual.toList()}", expected.contentEquals(actual))
        }
    }

    /** The reference keep mask after spurs: drop them, pack the rest, run [after], spread it back. */
    private fun referenceAfterSpurs(
        stroke: Samples,
        width: Float,
        after: (FloatArray, FloatArray, LongArray) -> BooleanArray?,
    ): BooleanArray? {
        val (start, end) = referenceContactSpurs(stroke.xs, stroke.ys, stroke.ts, width)
        val kept = stroke.xs.indices.filter { it != start && it != end }
        val rest = after(
            FloatArray(kept.size) { stroke.xs[kept[it]] },
            FloatArray(kept.size) { stroke.ys[kept[it]] },
            LongArray(kept.size) { stroke.ts[kept[it]] },
        )
        if (start < 0 && end < 0) return rest
        val keep = BooleanArray(stroke.xs.size)
        for ((at, index) in kept.withIndex()) keep[index] = rest?.get(at) ?: true
        return keep
    }

    /** The spur indices withoutContactSpurs picked, as it picked them. */
    private fun referenceContactSpurs(xs: FloatArray, ys: FloatArray, ts: LongArray, width: Float): Pair<Int, Int> {
        val size = xs.size
        if (size < 4) return -1 to -1
        fun spur(at: Int) = isContactSpur(xs[at - 1], ys[at - 1], xs[at], ys[at], xs[at + 1], ys[at + 1],
            ts[at + 1] - ts[at - 1], width)
        var dropStart = -1
        for (index in 1..minOf(5, size - 2)) {
            if (spur(index) && ts[index] - ts[0] <= 48L) {
                dropStart = index
                break
            }
        }
        var dropEnd = -1
        for (index in (size - 2) downTo maxOf(1, size - 6)) {
            if ((dropStart < 0 || kotlin.math.abs(index - dropStart) > 1) && spur(index) &&
                ts[size - 1] - ts[index] <= 48L) {
                dropEnd = index
                break
            }
        }
        return dropStart to dropEnd
    }

    /** tidiedPenInputs's keep mask as it was worked out. */
    private fun referencePenTidyKeepMask(xs: FloatArray, ys: FloatArray, ts: LongArray, width: Float): BooleanArray? {
        val count = xs.size
        if (count < 3) return null
        val radius = stationaryStartThreshold(width)
        val keep = BooleanArray(count) { true }
        var changed = false
        val firstMovement = referenceFirstMovementIndex(xs, ys, ts, radius)
        if (firstMovement >= 2) {
            for (i in 1 until firstMovement) keep[i] = false
            changed = true
        }
        referenceDwellKeepMask(xs, ys, radius)?.let { dwell ->
            for (i in 0 until count) if (!dwell[i] && keep[i]) { keep[i] = false; changed = true }
        }
        return if (changed) keep else null
    }

    private fun referenceFirstMovementIndex(xs: FloatArray, ys: FloatArray, ts: LongArray, threshold: Float): Int {
        if (xs.size < 2) return -1
        for (index in 1 until xs.size) {
            if (ts[index] - ts[0] > STATIONARY_START_MS) return -1
            if (hypot(xs[index] - xs[0], ys[index] - ys[0]) >= threshold) return index
        }
        return -1
    }

    /** dwellKeepMask before it was made linear: every start re-measures its run. */
    private fun referenceDwellKeepMask(xs: FloatArray, ys: FloatArray, radius: Float): BooleanArray? {
        val count = xs.size
        if (count < 5) return null
        var keep: BooleanArray? = null
        var i = 1
        while (i < count - 1) {
            var j = i
            while (j + 1 < count - 1 && hypot(xs[j + 1] - xs[i], ys[j + 1] - ys[i]) <= radius) j++
            if (j - i + 1 >= 3 && referenceDoublesBack(xs, ys, i, j)) {
                var back = i - 1
                while (back > 0 && hypot(xs[i] - xs[back], ys[i] - ys[back]) < radius) back--
                val inX = xs[i] - xs[back]
                val inY = ys[i] - ys[back]
                var best = i
                var bestReach = Float.NEGATIVE_INFINITY
                for (k in i..j) {
                    val reach = (xs[k] - xs[i]) * inX + (ys[k] - ys[i]) * inY
                    if (reach > bestReach) { bestReach = reach; best = k }
                }
                val mask = keep ?: BooleanArray(count) { true }.also { keep = it }
                for (k in i..j) if (k != best) mask[k] = false
                i = j + 1
            } else {
                i++
            }
        }
        return keep
    }

    private fun referenceDoublesBack(xs: FloatArray, ys: FloatArray, from: Int, to: Int): Boolean {
        for (k in maxOf(1, from)..minOf(to, xs.size - 2)) {
            val ax = xs[k] - xs[k - 1]
            val ay = ys[k] - ys[k - 1]
            val bx = xs[k + 1] - xs[k]
            val by = ys[k + 1] - ys[k]
            val a = hypot(ax, ay)
            val b = hypot(bx, by)
            if (a < 1e-4f || b < 1e-4f) continue
            if ((ax * bx + ay * by) / (a * b) < -0.2f) return true
        }
        return false
    }

    private companion object {
        val RADII = floatArrayOf(0.3f, 0.35f, 0.5f, 1f, 2.75f)
        val WIDTHS = floatArrayOf(0.5f, 1.4f, 2f, 5f, 11f)
    }
}
