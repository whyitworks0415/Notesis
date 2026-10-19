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

/**
 * Finds the contact spurs [withoutContactSpurs] drops among [count] samples
 * read through [x], [y] and [timeMillis], and hands [found] their indices: a
 * tiny reversal within the first few samples and 48 ms of contact, and one
 * within the last few and 48 ms of lift that is not next to the first. Either
 * index is -1 when there is no such spur. Inline, so the samples can come
 * straight from arrays or from the batch without boxing a value per read.
 */
internal inline fun <R> findContactSpurs(
    count: Int,
    width: Float,
    x: (Int) -> Float,
    y: (Int) -> Float,
    timeMillis: (Int) -> Long,
    found: (dropStart: Int, dropEnd: Int) -> R,
): R {
    if (count < 4) return found(-1, -1)
    val firstTime = timeMillis(0)
    val lastTime = timeMillis(count - 1)
    var dropStart = -1
    for (index in 1..minOf(5, count - 2)) {
        if (isContactSpur(
                x(index - 1), y(index - 1), x(index), y(index), x(index + 1), y(index + 1),
                timeMillis(index + 1) - timeMillis(index - 1), width,
            ) && timeMillis(index) - firstTime <= 48L) {
            dropStart = index
            break
        }
    }
    var dropEnd = -1
    for (index in (count - 2) downTo maxOf(1, count - 6)) {
        if ((dropStart < 0 || kotlin.math.abs(index - dropStart) > 1) &&
            isContactSpur(
                x(index - 1), y(index - 1), x(index), y(index), x(index + 1), y(index + 1),
                timeMillis(index + 1) - timeMillis(index - 1), width,
            ) && lastTime - timeMillis(index) <= 48L) {
            dropEnd = index
            break
        }
    }
    return found(dropStart, dropEnd)
}

/** Rebuild only a stroke whose first or last few inputs contain a tiny reversal. */
internal fun withoutContactSpurs(stroke: Stroke): Stroke {
    val inputs = stroke.inputs
    if (inputs.size < 4) return stroke
    // Only a dozen samples are read here, so they come off the batch one at a
    // time; the last one read is held so x, y and time cost one populate.
    val sample = StrokeInput()
    var loaded = -1
    fun load(index: Int): StrokeInput {
        if (index != loaded) { inputs.populate(index, sample); loaded = index }
        return sample
    }
    return findContactSpurs(
        inputs.size, stroke.brush.size,
        x = { load(it).x }, y = { load(it).y }, timeMillis = { load(it).elapsedTimeMillis },
    ) { dropStart, dropEnd ->
        if (dropStart < 0 && dropEnd < 0) stroke
        else Stroke(stroke.brush, keptInputs(inputs) { it != dropStart && it != dropEnd })
    }
}

/** A new batch holding the samples of [inputs] at the indices [keep] accepts, in order. */
private inline fun keptInputs(inputs: StrokeInputBatch, keep: (Int) -> Boolean): MutableStrokeInputBatch {
    val clean = MutableStrokeInputBatch()
    val sample = StrokeInput()
    for (index in 0 until inputs.size) {
        if (!keep(index)) continue
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
 * it, after the first, are the stationary cluster. Inline, so reading the
 * samples through the lambdas costs no boxed value per read.
 */
internal inline fun firstMovementIndex(
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

/** [firstMovementIndex] over the first [count] samples of coordinate and time arrays. */
internal fun firstMovementIndex(
    count: Int,
    threshold: Float,
    xs: FloatArray,
    ys: FloatArray,
    timesMillis: LongArray,
): Int = firstMovementIndex(count, threshold, { xs[it] }, { ys[it] }, { timesMillis[it] })

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
    return Stroke(stroke.brush, keptInputs(inputs) { it !in 1 until firstMovement })
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
 *
 * Runs are tried from each sample in turn, and a run that folds is skipped
 * past whole. Trying them is kept cheap three ways, none of which changes
 * the answer. Where the path doubles back is worked out once per sample up
 * front, so a run's "does it double back" is one lookup of the next turn at
 * or after its start, and once no turn is left nothing later can fold.
 * Knowing that turn also says how far a run has to reach to fold at all -
 * past the turn and at least [DWELL_MIN_SAMPLES] long - so a start is only
 * checked that far, rather than as far as the run goes. And a start whose
 * window to there already holds a sample further than [radius] along x or y
 * (kept as sliding extremes) is out without measuring anything: a sample
 * that far along one axis is that far away. Before this, every start
 * measured its whole run and walked it again for a turn, so a long run that
 * never folded - a pen held dead still, a slow straight creep - cost the
 * square of its length.
 */
internal fun dwellKeepMask(
    count: Int,
    radius: Float,
    xs: FloatArray,
    ys: FloatArray,
): BooleanArray? {
    if (count < 5) return null
    // Runs live in 1..count-2; count - 1 stands for "no turn left".
    val none = count - 1
    val nextTurn = IntArray(count)
    nextTurn[none] = none
    var upcoming = none
    for (k in count - 2 downTo 1) {
        if (turnsBack(xs, ys, k)) upcoming = k
        nextTurn[k] = upcoming
    }
    if (nextTurn[1] == none) return null

    val highX = SlidingExtreme(xs, count, highest = true)
    val lowX = SlidingExtreme(xs, count, highest = false)
    val highY = SlidingExtreme(ys, count, highest = true)
    val lowY = SlidingExtreme(ys, count, highest = false)
    var pushed = 0
    var keep: BooleanArray? = null
    var i = 1
    while (i < count - 1) {
        val turn = nextTurn[i]
        if (turn == none) break
        // A run from i folds only if it reaches both the turn and its minimum length.
        val reach = maxOf(turn, i + DWELL_MIN_SAMPLES - 1)
        if (reach > count - 2) break
        // The window i+1..reach: drop what slid out, add what slid in.
        if (pushed < i + 1) pushed = i + 1
        while (pushed <= reach) {
            highX.push(pushed); lowX.push(pushed); highY.push(pushed); lowY.push(pushed)
            pushed++
        }
        highX.dropBefore(i + 1); lowX.dropBefore(i + 1); highY.dropBefore(i + 1); lowY.dropBefore(i + 1)
        val cx = xs[i]
        val cy = ys[i]
        val folds = !(xs[highX.front()] - cx > radius || cx - xs[lowX.front()] > radius ||
            ys[highY.front()] - cy > radius || cy - ys[lowY.front()] > radius) &&
            reachesWithin(xs, ys, i, i + 1, reach, radius)
        if (!folds) {
            i++
            continue
        }
        var j = reach
        while (j + 1 < count - 1 && hypot(xs[j + 1] - xs[i], ys[j + 1] - ys[i]) <= radius) j++
        // The way in: from the sample before the run, or further back if that is too close.
        var back = i - 1
        while (back > 0 && hypot(xs[i] - xs[back], ys[i] - ys[back]) < radius) back--
        val inX = xs[i] - xs[back]
        val inY = ys[i] - ys[back]
        var best = i
        var bestReach = Float.NEGATIVE_INFINITY
        for (k in i..j) {
            val along = (xs[k] - xs[i]) * inX + (ys[k] - ys[i]) * inY
            if (along > bestReach) { bestReach = along; best = k }
        }
        val mask = keep ?: BooleanArray(count) { true }.also { keep = it }
        for (k in i..j) if (k != best) mask[k] = false
        i = j + 1
    }
    return keep
}

/** [dwellKeepMask] over samples read through [x] and [y]; they are copied out once. */
internal fun dwellKeepMask(
    count: Int,
    radius: Float,
    x: (Int) -> Float,
    y: (Int) -> Float,
): BooleanArray? {
    if (count < 5) return null
    return dwellKeepMask(count, radius, FloatArray(count) { x(it) }, FloatArray(count) { y(it) })
}

/** Whether every sample from [from] to [to] sits within [radius] of sample [center]. */
private fun reachesWithin(xs: FloatArray, ys: FloatArray, center: Int, from: Int, to: Int, radius: Float): Boolean {
    for (k in from..to) {
        // Written as "not within" so a NaN ends the run, as it always has.
        if (!(hypot(xs[k] - xs[center], ys[k] - ys[center]) <= radius)) return false
    }
    return true
}

/** Whether the step into sample [k] and the step out of it point against each other. */
private fun turnsBack(xs: FloatArray, ys: FloatArray, k: Int): Boolean {
    val ax = xs[k] - xs[k - 1]
    val ay = ys[k] - ys[k - 1]
    val bx = xs[k + 1] - xs[k]
    val by = ys[k + 1] - ys[k]
    val a = hypot(ax, ay)
    val b = hypot(bx, by)
    if (a < 1e-4f || b < 1e-4f) return false
    return (ax * bx + ay * by) / (a * b) < DWELL_REVERSE_COSINE
}

/**
 * The highest (or lowest) of [values] over a window of indices that only
 * ever moves forward: indices are pushed in increasing order and dropped
 * from the front, each at most once, so keeping it costs a constant per
 * index. The front is always some index still in the window, so a value
 * read there really is in it even when NaNs leave the order unsorted.
 */
private class SlidingExtreme(private val values: FloatArray, capacity: Int, private val highest: Boolean) {
    private val queue = IntArray(capacity)
    private var head = 0
    private var tail = 0

    fun push(index: Int) {
        val value = values[index]
        while (tail > head && (if (highest) values[queue[tail - 1]] <= value else values[queue[tail - 1]] >= value)) tail--
        queue[tail++] = index
    }

    fun dropBefore(index: Int) {
        while (head < tail && queue[head] < index) head++
    }

    fun front(): Int = queue[head]
}

private const val DWELL_MIN_SAMPLES = 3
/** Steps more than about 100 degrees apart count as doubling back. */
private const val DWELL_REVERSE_COSINE = -0.2f

/** [stroke] with its mid-stroke dwells folded away; see [dwellKeepMask]. */
internal fun withoutDwells(stroke: Stroke): Stroke {
    val inputs = stroke.inputs
    if (inputs.size < 5) return stroke
    val mask = withSamples(inputs) { count, xs, ys, _ ->
        dwellKeepMask(count, stationaryStartThreshold(stroke.brush.size), xs, ys)
    } ?: return stroke
    return Stroke(stroke.brush, keptInputs(inputs) { mask[it] })
}

/**
 * How far the wet ink waits for the pen to move before it starts, in page
 * units: well under a hairline. The committed stroke tidies a wider cluster
 * (see [stationaryStartThreshold]); holding the wet ink back that far made
 * the first moment of every stroke feel late.
 */
internal fun wetStartHoldThreshold(width: Float): Float = (width * 0.15f).coerceIn(0.04f, 0.3f)

/**
 * Which of [count] pen samples survive [tidiedPenInputs]: the stationary
 * cluster at contact (see [firstMovementIndex]) and the dwells at the turns
 * (see [dwellKeepMask]) both dropped. Null when nothing is.
 */
internal fun penTidyKeepMask(
    count: Int,
    width: Float,
    xs: FloatArray,
    ys: FloatArray,
    timesMillis: LongArray,
): BooleanArray? {
    if (count < 3) return null
    val radius = stationaryStartThreshold(width)
    var keep: BooleanArray? = null
    val firstMovement = firstMovementIndex(count, radius, xs, ys, timesMillis)
    if (firstMovement >= 2) {
        keep = BooleanArray(count) { it !in 1 until firstMovement }
    }
    // A dwell mask always drops something, so either way the result is a change.
    val dwell = dwellKeepMask(count, radius, xs, ys) ?: return keep
    if (keep == null) return dwell
    for (i in 0 until count) if (!dwell[i]) keep[i] = false
    return keep
}

/**
 * A pen's inputs with the stationary cluster at contact and the dwells at its
 * turns both folded away, or [inputs] itself when there is nothing to fold.
 * Works on the batch, so a stroke is built once from the result rather than
 * built, tidied and built again. Each sample is read off the batch once; a
 * stroke with nothing to fold costs those reads and no new batch.
 */
internal fun tidiedPenInputs(inputs: StrokeInputBatch, width: Float): StrokeInputBatch {
    if (inputs.size < 3) return inputs
    val keep = withSamples(inputs) { count, xs, ys, ts -> penTidyKeepMask(count, width, xs, ys, ts) }
        ?: return inputs
    return keptInputs(inputs) { keep[it] }
}

/**
 * Which of [count] pen samples survive [tidiedPenStroke]: the contact spurs
 * [withoutContactSpurs] drops, then, among what is left, what
 * [penTidyKeepMask] drops - indexed by the original samples. Null when
 * nothing is dropped. [xs], [ys] and [timesMillis] are only read.
 */
internal fun tidiedPenKeepMask(
    count: Int,
    width: Float,
    xs: FloatArray,
    ys: FloatArray,
    timesMillis: LongArray,
): BooleanArray? = findContactSpurs(count, width, { xs[it] }, { ys[it] }, { timesMillis[it] }) { dropStart, dropEnd ->
    if (dropStart < 0 && dropEnd < 0) penTidyKeepMask(count, width, xs, ys, timesMillis)
    else keepAfterSpurs(count, dropStart, dropEnd, xs, ys, timesMillis) { left, lx, ly, lt ->
        penTidyKeepMask(left, width, lx, ly, lt)
    }
}

/**
 * Which of [count] freehand samples survive [withoutSpursAndDwells]: the
 * contact spurs, then the dwells among what is left, indexed by the original
 * samples. Null when nothing is dropped.
 */
internal fun spurAndDwellKeepMask(
    count: Int,
    width: Float,
    xs: FloatArray,
    ys: FloatArray,
    timesMillis: LongArray,
): BooleanArray? = findContactSpurs(count, width, { xs[it] }, { ys[it] }, { timesMillis[it] }) { dropStart, dropEnd ->
    val radius = stationaryStartThreshold(width)
    if (dropStart < 0 && dropEnd < 0) dwellKeepMask(count, radius, xs, ys)
    else keepAfterSpurs(count, dropStart, dropEnd, xs, ys, timesMillis) { left, lx, ly, _ ->
        dwellKeepMask(left, radius, lx, ly)
    }
}

/**
 * The keep mask over all [count] samples when the spurs at [dropStart] and
 * [dropEnd] (-1 for none) go and [after] then picks among the rest: [after]
 * sees the remaining samples packed together, exactly as it would see the
 * rebuilt spurless stroke, and its mask is spread back over the originals.
 */
private inline fun keepAfterSpurs(
    count: Int,
    dropStart: Int,
    dropEnd: Int,
    xs: FloatArray,
    ys: FloatArray,
    timesMillis: LongArray,
    after: (count: Int, xs: FloatArray, ys: FloatArray, timesMillis: LongArray) -> BooleanArray?,
): BooleanArray {
    val left = count - (if (dropStart >= 0) 1 else 0) - (if (dropEnd >= 0) 1 else 0)
    val lx = FloatArray(left)
    val ly = FloatArray(left)
    val lt = LongArray(left)
    var at = 0
    for (index in 0 until count) {
        if (index == dropStart || index == dropEnd) continue
        lx[at] = xs[index]; ly[at] = ys[index]; lt[at] = timesMillis[index]
        at++
    }
    val rest = after(left, lx, ly, lt)
    val keep = BooleanArray(count)
    at = 0
    for (index in 0 until count) {
        if (index == dropStart || index == dropEnd) continue
        keep[index] = rest?.get(at) ?: true
        at++
    }
    return keep
}

/**
 * A finished pen stroke tidied for keeping: [withoutContactSpurs] and then
 * [tidiedPenInputs], worked out together on one read of the inputs so at
 * most one new stroke is built - and so tessellated - at pen-up, rather than
 * one for the spurless stroke and another for the tidied one. [stroke]
 * itself when nothing is dropped. The kept samples are exactly those the
 * two steps in turn would keep.
 */
internal fun tidiedPenStroke(stroke: Stroke): Stroke =
    rebuiltKeeping(stroke) { count, xs, ys, ts -> tidiedPenKeepMask(count, stroke.brush.size, xs, ys, ts) }

/**
 * A finished freehand stroke with its contact spurs and dwells folded away:
 * `withoutDwells(withoutContactSpurs(stroke))`, building at most one new
 * stroke. [stroke] itself when nothing is dropped.
 */
internal fun withoutSpursAndDwells(stroke: Stroke): Stroke =
    rebuiltKeeping(stroke) { count, xs, ys, ts -> spurAndDwellKeepMask(count, stroke.brush.size, xs, ys, ts) }

/** [stroke] rebuilt from the samples [keepMask] keeps, or [stroke] itself when it keeps them all. */
private inline fun rebuiltKeeping(
    stroke: Stroke,
    keepMask: (count: Int, xs: FloatArray, ys: FloatArray, timesMillis: LongArray) -> BooleanArray?,
): Stroke {
    val inputs = stroke.inputs
    if (inputs.size < 3) return stroke
    val keep = withSamples(inputs, keepMask) ?: return stroke
    return Stroke(stroke.brush, keptInputs(inputs) { keep[it] })
}

/** Reads each sample of [inputs] once into coordinate and time arrays and hands them to [block]. */
private inline fun <R> withSamples(
    inputs: StrokeInputBatch,
    block: (count: Int, xs: FloatArray, ys: FloatArray, timesMillis: LongArray) -> R,
): R {
    val count = inputs.size
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
    return block(count, xs, ys, ts)
}
