package com.notesis

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

/** Restores erased values at their original z-order, even when indices arrive unsorted. */
internal fun <T> restoreOrdered(target: MutableList<T>, values: List<T>, indices: List<Int>) {
    values.zip(indices).sortedBy { it.second }.forEach { (value, index) ->
        target.add(index.coerceIn(0, target.size), value)
    }
}
