package com.notesis

import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
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
