package com.notesis

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How a shape made by holding the pen follows the pen when it moves on
 * before lifting: the end of a line, the last corner of a bent line, the far
 * corner of a box, or the whole outline turned and scaled about its middle.
 */
internal enum class HeldReshape { END, LAST_VERTEX, BOX, SIMILAR }

internal fun heldReshapeOf(shape: RecognizedShape): HeldReshape {
    val outline = shape.outline
    return when {
        outline == null && shape.kind == ShapeKind.LINE -> HeldReshape.END
        outline == null -> HeldReshape.BOX
        shape.kind == ShapeKind.POLYGON && !isClosedOutline(outline) -> HeldReshape.LAST_VERTEX
        else -> HeldReshape.SIMILAR
    }
}

internal fun isClosedOutline(outline: List<FloatArray>): Boolean =
    outline.size > 3 && outline.first()[0] == outline.last()[0] && outline.first()[1] == outline.last()[1]

/**
 * The point the outline turns and scales about: the middle of a closed shape,
 * the start of an open one, which stays where it was drawn.
 */
internal fun reshapePivot(outline: List<FloatArray>): FloatArray {
    if (!isClosedOutline(outline)) return outline.first().copyOf()
    var x = 0f
    var y = 0f
    val n = outline.size - 1
    for (i in 0 until n) { x += outline[i][0]; y += outline[i][1] }
    return floatArrayOf(x / n, y / n)
}

/**
 * [base] turned and scaled about ([pivotX], [pivotY]) so that the point the pen
 * rested on, ([grabX], [grabY]), lands under the pen at ([penX], [penY]). A
 * grab almost on the pivot gives no direction to go by, so the shape is only
 * moved along with the pen.
 */
internal fun similarTo(
    base: List<FloatArray>,
    pivotX: Float, pivotY: Float,
    grabX: Float, grabY: Float,
    penX: Float, penY: Float,
): List<FloatArray> {
    val fromX = grabX - pivotX
    val fromY = grabY - pivotY
    val reach = hypot(fromX, fromY)
    if (reach < 1f) return base.map { floatArrayOf(it[0] + penX - grabX, it[1] + penY - grabY) }
    val toX = penX - pivotX
    val toY = penY - pivotY
    val scale = (hypot(toX, toY) / reach).coerceIn(MIN_RESHAPE_SCALE, MAX_RESHAPE_SCALE)
    val turn = atan2(toY, toX) - atan2(fromY, fromX)
    val c = cos(turn) * scale
    val s = sin(turn) * scale
    return base.map {
        val dx = it[0] - pivotX
        val dy = it[1] - pivotY
        floatArrayOf(pivotX + dx * c - dy * s, pivotY + dx * s + dy * c)
    }
}

/** [base] with its last point carried by the pen's travel since the hold; a closed outline keeps its seam. */
internal fun withLastVertexMoved(base: List<FloatArray>, dx: Float, dy: Float): List<FloatArray> {
    val out = base.map { it.copyOf() }
    val last = out.last()
    last[0] += dx
    last[1] += dy
    return out
}

/**
 * A box held at the corner nearest where the pen rested, that corner then
 * carried by the pen's travel: returns the new (from, to), from being the
 * opposite corner, which stays put.
 */
internal fun boxDraggedFrom(
    fromX: Float, fromY: Float, toX: Float, toY: Float,
    grabX: Float, grabY: Float, dx: Float, dy: Float,
): FloatArray {
    val left = minOf(fromX, toX)
    val right = maxOf(fromX, toX)
    val top = minOf(fromY, toY)
    val bottom = maxOf(fromY, toY)
    val nearRight = grabX - left > right - grabX
    val nearBottom = grabY - top > bottom - grabY
    val anchorX = if (nearRight) left else right
    val anchorY = if (nearBottom) top else bottom
    val cornerX = if (nearRight) right else left
    val cornerY = if (nearBottom) bottom else top
    return floatArrayOf(anchorX, anchorY, cornerX + dx, cornerY + dy)
}

private const val MIN_RESHAPE_SCALE = 0.05f
private const val MAX_RESHAPE_SCALE = 40f
