package com.notesis

import android.graphics.RectF
import androidx.ink.strokes.Stroke
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Whether a finished pen stroke is a scribble-out: the hand going back and
 * forth over the same short stretch. Counted along the stroke's longer side,
 * each pass a turn of at least [SCRIBBLE_TURN_FRACTION] of that side, and a
 * pass only counts when it is close to straight - which is what tells a
 * zigzag from a word, a loop drawn three times, or shading with curves in it.
 */
internal fun isScribble(xs: FloatArray, ys: FloatArray, count: Int): Boolean {
    if (count < 8) return false
    var minX = xs[0]
    var maxX = xs[0]
    var minY = ys[0]
    var maxY = ys[0]
    var length = 0f
    for (i in 1 until count) {
        minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
        minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
        length += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }
    val width = maxX - minX
    val height = maxY - minY
    val major = max(width, height)
    if (major < SCRIBBLE_MIN_EXTENT || length < SCRIBBLE_MIN_LENGTH_RATIO * (width + height)) return false
    val along = if (width >= height) xs else ys
    val turn = major * SCRIBBLE_TURN_FRACTION
    var direction = 0
    var legStart = along[0]
    var legStartTravel = 0f
    var extreme = along[0]
    var extremeTravel = 0f
    var travel = 0f
    var straightLegs = 0
    for (i in 1 until count) {
        travel += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val v = along[i]
        if (direction == 0) {
            if (abs(v - legStart) >= turn) {
                direction = if (v > legStart) 1 else -1
                extreme = v
                extremeTravel = travel
            }
            continue
        }
        if ((v - extreme) * direction > 0f) {
            extreme = v
            extremeTravel = travel
        } else if ((extreme - v) * direction >= turn) {
            // A leg ended at [extreme]: straight if the hand took no detour.
            if (extremeTravel - legStartTravel <= SCRIBBLE_STRAIGHTNESS * abs(extreme - legStart)) straightLegs++
            legStart = extreme
            legStartTravel = extremeTravel
            direction = -direction
            extreme = v
            extremeTravel = travel
        }
    }
    return straightLegs >= SCRIBBLE_MIN_LEGS
}

/**
 * The smallest shift that puts one of [edges] on one of [targets], with the
 * target it lands on, or null when none is within [reach].
 */
internal fun alignOffset(edges: FloatArray, targets: FloatArray, reach: Float): Pair<Float, Float>? {
    var best: Pair<Float, Float>? = null
    for (edge in edges) for (target in targets) {
        val shift = target - edge
        if (kotlin.math.abs(shift) <= reach && (best == null || kotlin.math.abs(shift) < kotlin.math.abs(best.first))) {
            best = shift to target
        }
    }
    return best
}

/** What a moved selection lines up with, each switchable from the lasso bar, as Canva's guides are. */
internal object SnapTo {
    /** The page's edges and its middle. */
    const val PAGE = 1
    /** A margin in from each edge of the page. */
    const val MARGINS = 2
    /** Pictures and text boxes: their edges and middles. */
    const val OBJECTS = 4
    /** Other handwriting, by the blocks it stands in. */
    const val INK = 8
    /** The same gap to its neighbours: centred between two, or as far as two others stand apart. */
    const val SPACING = 16
    const val ALL = PAGE or MARGINS or OBJECTS or INK or SPACING
}

/**
 * The lines a selection can catch on a [width] x [height] page: [xs] and [ys],
 * and [boxes] (left, top, right, bottom, four to a box) for even spacing.
 * [objects] and [ink] are boxes the same way, the selection's own left out.
 */
internal class SnapGuides(val xs: FloatArray, val ys: FloatArray, val boxes: FloatArray)

internal fun snapGuides(width: Float, height: Float, objects: FloatArray, ink: FloatArray, on: Int): SnapGuides {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    if (on and SnapTo.PAGE != 0) { xs += listOf(0f, width / 2f, width); ys += listOf(0f, height / 2f, height) }
    if (on and SnapTo.MARGINS != 0) {
        val margin = minOf(width, height) * PAGE_MARGIN
        xs += listOf(margin, width - margin); ys += listOf(margin, height - margin)
    }
    val boxes = (if (on and SnapTo.OBJECTS != 0) objects else FloatArray(0)) +
        (if (on and SnapTo.INK != 0) ink else FloatArray(0))
    for (i in boxes.indices step 4) {
        xs += listOf(boxes[i], (boxes[i] + boxes[i + 2]) / 2f, boxes[i + 2])
        ys += listOf(boxes[i + 1], (boxes[i + 1] + boxes[i + 3]) / 2f, boxes[i + 3])
    }
    return SnapGuides(xs.toFloatArray(), ys.toFloatArray(), if (on and SnapTo.SPACING != 0) boxes else FloatArray(0))
}

/**
 * Strokes' boxes (four floats each) gathered into the blocks they stand in -
 * a word, a line, a drawing - by joining boxes less than [gap] apart, so a
 * selection lines up with a line of writing rather than with every letter.
 */
internal fun inkBlocks(strokes: FloatArray, gap: Float): FloatArray {
    val blocks = ArrayList<FloatArray>()
    for (i in strokes.indices step 4) {
        var box = floatArrayOf(strokes[i], strokes[i + 1], strokes[i + 2], strokes[i + 3])
        // Joined into one block, a box may now reach others: keep joining until it reaches none.
        var joined = true
        while (joined) {
            joined = false
            val it = blocks.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b[0] - gap <= box[2] && box[0] - gap <= b[2] && b[1] - gap <= box[3] && box[1] - gap <= b[3]) {
                    box = floatArrayOf(minOf(b[0], box[0]), minOf(b[1], box[1]), maxOf(b[2], box[2]), maxOf(b[3], box[3]))
                    it.remove()
                    joined = true
                }
            }
        }
        blocks += box
    }
    return FloatArray(blocks.size * 4) { blocks[it / 4][it % 4] }
}

/**
 * How far to move the span [lo]..[hi] along one axis to space it evenly: as far
 * from the box before it as from the one after, or as far from either as two
 * neighbouring boxes stand apart. [starts] and [ends] are the spans, on this
 * axis, of the boxes level with it on the other. Null when nothing is within
 * [reach]; otherwise the shift and the neighbours' near edges (NaN where none).
 */
internal fun evenGapShift(lo: Float, hi: Float, starts: FloatArray, ends: FloatArray, reach: Float): FloatArray? {
    var before = Float.NaN
    var after = Float.NaN
    for (i in starts.indices) {
        if (ends[i] <= lo + reach && starts[i] < lo && (before.isNaN() || ends[i] > before)) before = ends[i]
        if (starts[i] >= hi - reach && ends[i] > hi && (after.isNaN() || starts[i] < after)) after = starts[i]
    }
    val shifts = ArrayList<Float>()
    if (!before.isNaN() && !after.isNaN()) shifts += (before + after - lo - hi) / 2f
    // The gaps already on the page, between boxes side by side.
    val order = starts.indices.sortedBy { starts[it] }
    for (k in 0 until order.size - 1) {
        val gap = starts[order[k + 1]] - ends[order[k]]
        if (gap <= 0f) continue
        if (!before.isNaN()) shifts += before + gap - lo
        if (!after.isNaN()) shifts += after - gap - hi
    }
    val best = shifts.filter { kotlin.math.abs(it) <= reach }.minByOrNull { kotlin.math.abs(it) } ?: return null
    return floatArrayOf(best, before, after)
}

/** The margin guides' distance in from the page's edges, as a part of its shorter side. */
private const val PAGE_MARGIN = 0.065f

/** Whether a pen path ending [gap] from its start, inside a [width] x [height] box, closes a loop. */
internal fun isLassoLoop(gap: Float, width: Float, height: Float, minExtent: Float): Boolean =
    min(width, height) >= minExtent && gap <= max(width, height) * LOOP_GAP_FRACTION

/**
 * Copied or cut ink, newest first, shared by every note in this process so a
 * selection can be carried to another page or another note.
 * ponytail: memory only, so it does not survive the app being killed; persist
 * the strokes with the note format if a library that outlives the app is wanted.
 */
internal object InkClipboard {
    class Clip(
        val strokes: List<Stroke>,
        val bounds: RectF,
        /** Pictures with their pixels, so a paste into another note can store its own copy. */
        val images: List<Pair<PageImage, android.graphics.Bitmap?>> = emptyList(),
    ) {
        val size: Int get() = strokes.size + images.size
    }

    const val MAX_CLIPS = 10
    val clips = ArrayDeque<Clip>()

    fun push(strokes: List<Stroke>, bounds: RectF, images: List<Pair<PageImage, android.graphics.Bitmap?>> = emptyList()) {
        if (strokes.isEmpty() && images.isEmpty()) return
        clips.addFirst(Clip(strokes, RectF(bounds), images))
        while (clips.size > MAX_CLIPS) clips.removeLast()
    }
}

private const val SCRIBBLE_MIN_EXTENT = 12f
private const val SCRIBBLE_MIN_LENGTH_RATIO = 2.5f
private const val SCRIBBLE_TURN_FRACTION = 0.3f
private const val SCRIBBLE_STRAIGHTNESS = 1.4f
private const val SCRIBBLE_MIN_LEGS = 3
private const val LOOP_GAP_FRACTION = 0.35f
