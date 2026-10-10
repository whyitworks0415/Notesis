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
internal fun alignOffset(edges: FloatArray, targets: List<Float>, reach: Float): Pair<Float, Float>? {
    var best: Pair<Float, Float>? = null
    for (edge in edges) for (target in targets) {
        val shift = target - edge
        if (kotlin.math.abs(shift) <= reach && (best == null || kotlin.math.abs(shift) < kotlin.math.abs(best.first))) {
            best = shift to target
        }
    }
    return best
}

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
