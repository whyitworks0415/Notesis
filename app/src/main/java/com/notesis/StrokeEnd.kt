package com.notesis

import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import kotlin.math.hypot

/** Fine pens need a finer outline too; otherwise the round cap becomes a polygon. */
internal fun strokeEpsilon(width: Float, viewportEpsilon: Float): Float =
    minOf(viewportEpsilon, width / 64f).coerceAtLeast(0.0001f)

/** A lifted stylus can jump sideways or backwards as it leaves the digitizer. */
internal fun unstableLift(dx: Float, dy: Float, lastDx: Float, lastDy: Float): Boolean {
    val distance = hypot(dx, dy)
    val previous = hypot(lastDx, lastDy)
    if (distance < 1f) return false
    if (distance > maxOf(4f, previous * 2f)) return true
    return previous >= 0.5f && dx * lastDx + dy * lastDy < distance * previous * 0.5f
}

/** A tiny out-and-back movement at contact or lift is digitizer chatter, not a cap. */
internal fun isContactSpur(
    beforeX: Float, beforeY: Float,
    tipX: Float, tipY: Float,
    afterX: Float, afterY: Float,
    elapsedMillis: Long,
    width: Float,
): Boolean {
    if (elapsedMillis < 0L || elapsedMillis > 40L || width <= 0f) return false
    val ax = tipX - beforeX
    val ay = tipY - beforeY
    val bx = afterX - tipX
    val by = afterY - tipY
    val outgoing = hypot(ax, ay)
    val returning = hypot(bx, by)
    if (outgoing < 0.5f || returning < 0.5f ||
        outgoing > maxOf(2f, width * 1.5f)) return false
    val returnGap = hypot(afterX - beforeX, afterY - beforeY)
    return returnGap <= minOf(2f, width * 0.4f) &&
        ax * bx + ay * by < -0.65f * outgoing * returning
}

/** Rebuild only a stroke whose first or last few inputs contain a tiny reversal. */
internal fun withoutContactSpurs(stroke: Stroke): Stroke {
    val inputs = stroke.inputs
    if (inputs.size < 4) return stroke
    val before = StrokeInput()
    val tip = StrokeInput()
    val after = StrokeInput()
    val first = StrokeInput()
    val last = StrokeInput()
    inputs.populate(0, first)
    inputs.populate(inputs.size - 1, last)
    fun spur(at: Int): Boolean {
        inputs.populate(at - 1, before)
        inputs.populate(at, tip)
        inputs.populate(at + 1, after)
        return isContactSpur(
            before.x, before.y, tip.x, tip.y, after.x, after.y,
            after.elapsedTimeMillis - before.elapsedTimeMillis,
            stroke.brush.size,
        )
    }
    var dropStart = -1
    for (index in 1..minOf(5, inputs.size - 2)) {
        if (spur(index) && tip.elapsedTimeMillis - first.elapsedTimeMillis <= 48L) {
            dropStart = index
            break
        }
    }
    var dropEnd = -1
    for (index in (inputs.size - 2) downTo maxOf(1, inputs.size - 6)) {
        if ((dropStart < 0 || kotlin.math.abs(index - dropStart) > 1) && spur(index) &&
            last.elapsedTimeMillis - tip.elapsedTimeMillis <= 48L) {
            dropEnd = index
            break
        }
    }
    if (dropStart < 0 && dropEnd < 0) return stroke
    val clean = MutableStrokeInputBatch()
    val sample = StrokeInput()
    for (index in 0 until inputs.size) {
        if (index == dropStart || index == dropEnd) continue
        inputs.populate(index, sample)
        clean.add(
            type = sample.toolType,
            x = sample.x,
            y = sample.y,
            elapsedTimeMillis = sample.elapsedTimeMillis,
            pressure = sample.pressure,
            tiltRadians = sample.tiltRadians,
            orientationRadians = sample.orientationRadians,
        )
    }
    return Stroke(stroke.brush, clean)
}

internal const val STATIONARY_START_MS = 100L

/**
 * Movement, in page units, that counts as the tip actually leaving the
 * contact point: a quarter of the pen's width. Anything the tip does inside
 * that is hidden under the nib anyway, and it was set at a fixed 0.3 before,
 * which a contact sample landing a third of a unit ahead of the samples
 * after it already passed - the cluster was kept and the start came out
 * folded into a corner.
 */
internal fun stationaryStartThreshold(width: Float): Float = (width * 0.25f).coerceAtLeast(0.3f)

/**
 * The index of the first sample to leave the contact point by [threshold]
 * within [STATIONARY_START_MS], or -1 when the pen never did. Samples before
 * it, after the first, are the stationary cluster.
 */
internal fun firstMovementIndex(
    count: Int,
    threshold: Float,
    x: (Int) -> Float,
    y: (Int) -> Float,
    timeMillis: (Int) -> Long,
): Int {
    if (count < 2) return -1
    val x0 = x(0)
    val y0 = y(0)
    val t0 = timeMillis(0)
    for (index in 1 until count) {
        if (timeMillis(index) - t0 > STATIONARY_START_MS) return -1
        if (hypot(x(index) - x0, y(index) - y0) >= threshold) return index
    }
    return -1
}

/**
 * A fixed-width pen can receive a cluster of nearly stationary digitizer
 * samples before the tip actually moves, sometimes with the contact sample
 * itself a little ahead of them. Their tiny reversals make Ink's start
 * outline fold over itself, leaving a corner even with a circular brush tip.
 * Keep the contact point and the first real movement, but omit the cluster.
 */
internal fun withoutStationaryStart(stroke: Stroke): Stroke {
    val inputs = stroke.inputs
    if (inputs.size < 3) return stroke
    val sample = StrokeInput()
    val firstMovement = firstMovementIndex(
        inputs.size,
        stationaryStartThreshold(stroke.brush.size),
        x = { inputs.populate(it, sample); sample.x },
        y = { inputs.populate(it, sample); sample.y },
        timeMillis = { inputs.populate(it, sample); sample.elapsedTimeMillis },
    )
    // Nothing between the contact and the first movement: nothing to drop.
    if (firstMovement < 2) return stroke

    val clean = MutableStrokeInputBatch()
    for (index in 0 until inputs.size) {
        if (index in 1 until firstMovement) continue
        inputs.populate(index, sample)
        clean.add(
            type = sample.toolType,
            x = sample.x,
            y = sample.y,
            elapsedTimeMillis = sample.elapsedTimeMillis,
            pressure = sample.pressure,
            tiltRadians = sample.tiltRadians,
            orientationRadians = sample.orientationRadians,
        )
    }
    return Stroke(stroke.brush, clean)
}

/**
 * Which samples to keep once mid-stroke dwells are folded away: a run of
 * samples that all sit within [radius] of where it began, and that doubles
 * back on itself somewhere inside, is the pen pausing at a turn and wobbling
 * on the digitizer. Ink outlines that wobble by folding the stroke's edge
 * over itself, which is the corner seen at the tip of a sharp turn even with
 * a round nib. Each such run keeps one sample, the one reaching furthest the
 * way the pen was going, so the turn still reaches as far as it did.
 *
 * The first and last samples are always kept; null when nothing is dropped.
 */
internal fun dwellKeepMask(
    count: Int,
    radius: Float,
    x: (Int) -> Float,
    y: (Int) -> Float,
): BooleanArray? {
    if (count < 5) return null
    val xs = FloatArray(count) { x(it) }
    val ys = FloatArray(count) { y(it) }
    var keep: BooleanArray? = null
    var i = 1
    while (i < count - 1) {
        var j = i
        while (j + 1 < count - 1 && hypot(xs[j + 1] - xs[i], ys[j + 1] - ys[i]) <= radius) j++
        if (j - i + 1 >= DWELL_MIN_SAMPLES && doublesBack(xs, ys, i, j)) {
            // The way in: from the sample before the run, or further back if that is too close.
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

/** Whether any two consecutive steps between [from] and [to] (and the step into it) point against each other. */
private fun doublesBack(xs: FloatArray, ys: FloatArray, from: Int, to: Int): Boolean {
    for (k in maxOf(1, from)..minOf(to, xs.size - 2)) {
        val ax = xs[k] - xs[k - 1]
        val ay = ys[k] - ys[k - 1]
        val bx = xs[k + 1] - xs[k]
        val by = ys[k + 1] - ys[k]
        val a = hypot(ax, ay)
        val b = hypot(bx, by)
        if (a < 1e-4f || b < 1e-4f) continue
        if ((ax * bx + ay * by) / (a * b) < DWELL_REVERSE_COSINE) return true
    }
    return false
}

private const val DWELL_MIN_SAMPLES = 3
/** Steps more than about 100 degrees apart count as doubling back. */
private const val DWELL_REVERSE_COSINE = -0.2f

/** [stroke] with its mid-stroke dwells folded away; see [dwellKeepMask]. */
internal fun withoutDwells(stroke: Stroke): Stroke {
    val inputs = stroke.inputs
    if (inputs.size < 5) return stroke
    val sample = StrokeInput()
    val mask = dwellKeepMask(
        inputs.size,
        stationaryStartThreshold(stroke.brush.size),
        x = { inputs.populate(it, sample); sample.x },
        y = { inputs.populate(it, sample); sample.y },
    ) ?: return stroke
    val clean = MutableStrokeInputBatch()
    for (index in 0 until inputs.size) {
        if (!mask[index]) continue
        inputs.populate(index, sample)
        clean.add(
            type = sample.toolType,
            x = sample.x,
            y = sample.y,
            elapsedTimeMillis = sample.elapsedTimeMillis,
            pressure = sample.pressure,
            tiltRadians = sample.tiltRadians,
            orientationRadians = sample.orientationRadians,
        )
    }
    return Stroke(stroke.brush, clean)
}

/**
 * How far the wet ink waits for the pen to move before it starts, in page
 * units: well under a hairline. The committed stroke tidies a wider cluster
 * (see [stationaryStartThreshold]); holding the wet ink back that far made
 * the first moment of every stroke feel late.
 */
internal fun wetStartHoldThreshold(width: Float): Float = (width * 0.15f).coerceIn(0.04f, 0.3f)

/**
 * A pen's inputs with the stationary cluster at contact and the dwells at its
 * turns both folded away, or [inputs] itself when there is nothing to fold.
 * Works on the batch, so a stroke is built once from the result rather than
 * built, tidied and built again.
 */
internal fun tidiedPenInputs(inputs: StrokeInputBatch, width: Float): StrokeInputBatch {
    val count = inputs.size
    if (count < 3) return inputs
    val sample = StrokeInput()
    val xs = FloatArray(count)
    val ys = FloatArray(count)
    val ts = LongArray(count)
    for (i in 0 until count) {
        inputs.populate(i, sample)
        xs[i] = sample.x
        ys[i] = sample.y
        ts[i] = sample.elapsedTimeMillis
    }
    val radius = stationaryStartThreshold(width)
    val keep = BooleanArray(count) { true }
    var changed = false
    val firstMovement = firstMovementIndex(count, radius, { xs[it] }, { ys[it] }, { ts[it] })
    if (firstMovement >= 2) {
        for (i in 1 until firstMovement) keep[i] = false
        changed = true
    }
    dwellKeepMask(count, radius, { xs[it] }, { ys[it] })?.let { dwell ->
        for (i in 0 until count) if (!dwell[i] && keep[i]) { keep[i] = false; changed = true }
    }
    if (!changed) return inputs
    val clean = MutableStrokeInputBatch()
    for (index in 0 until count) {
        if (!keep[index]) continue
        inputs.populate(index, sample)
        clean.add(
            type = sample.toolType,
            x = sample.x,
            y = sample.y,
            elapsedTimeMillis = sample.elapsedTimeMillis,
            pressure = sample.pressure,
            tiltRadians = sample.tiltRadians,
            orientationRadians = sample.orientationRadians,
        )
    }
    return clean
}
