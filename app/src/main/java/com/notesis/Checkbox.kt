package com.notesis

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** A new checkbox's side, in page units: about one line of ruled paper. */
internal const val CHECKBOX_SIZE = 40f

/** The smallest a checkbox can be dragged out or resized to. */
internal const val CHECKBOX_MIN_SIZE = 16f

/** The colour a checkbox is drawn in when none was chosen. */
internal const val CHECKBOX_DEFAULT_COLOR = 0xFF424242.toInt()

/**
 * A checkbox in [rect]: a rounded square outline, and when [checked] a tick
 * across it with the square lightly filled. Drawn from its size alone, so it
 * stays sharp at any zoom and in a thumbnail or an export alike.
 */
internal fun drawCheckbox(canvas: Canvas, rect: RectF, color: Int, checked: Boolean, opacity: Float = 1f) {
    val side = minOf(rect.width(), rect.height())
    if (side <= 0f) return
    val ink = (if (color == 0) CHECKBOX_DEFAULT_COLOR else color) or OPAQUE
    val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val edge = side * 0.08f
    val corner = side * 0.18f
    val box = RectF(rect).apply { inset(edge / 2f, edge / 2f) }
    if (checked) {
        paint.style = Paint.Style.FILL
        paint.color = ink
        paint.alpha = alpha * 30 / 255
        canvas.drawRoundRect(box, corner, corner, paint)
    }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = edge
    paint.color = ink
    paint.alpha = alpha
    canvas.drawRoundRect(box, corner, corner, paint)
    if (!checked) return
    paint.strokeWidth = side * 0.12f
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    canvas.drawPath(checkPath(rect), paint)
}

/** The tick through a checkbox in [rect], as fractions of its side. */
internal fun checkPath(rect: RectF): Path {
    val l = rect.left
    val t = rect.top
    val w = rect.width()
    val h = rect.height()
    return Path().apply {
        moveTo(l + w * CHECK_TICK[0], t + h * CHECK_TICK[1])
        lineTo(l + w * CHECK_TICK[2], t + h * CHECK_TICK[3])
        lineTo(l + w * CHECK_TICK[4], t + h * CHECK_TICK[5])
    }
}

/** The tick's three points across a unit square: down to the low corner, then up to the right. */
internal val CHECK_TICK = floatArrayOf(0.24f, 0.52f, 0.43f, 0.71f, 0.78f, 0.30f)

private const val OPAQUE = -0x1000000
