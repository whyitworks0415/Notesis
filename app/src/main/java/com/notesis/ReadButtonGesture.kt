package com.notesis

import kotlin.math.hypot

internal data class ReadTextPoint(val page: Int, val x: Float, val y: Float)

/** A button contact is a capture drag or one endpoint of a two-tap selection. */
internal class ReadButtonGesture(private val touchSlop: Float) {
    private var start: ReadTextPoint? = null
    private var screenX = 0f
    private var screenY = 0f
    private var anchor: ReadTextPoint? = null
    var dragging = false
        private set

    fun begin(point: ReadTextPoint, x: Float, y: Float) {
        start = point
        screenX = x
        screenY = y
        dragging = false
    }

    fun move(x: Float, y: Float) {
        if (start != null && hypot(x - screenX, y - screenY) > touchSlop) {
            dragging = true
            anchor = null
        }
    }

    sealed interface Result {
        data object Capture : Result
        data class Select(val start: ReadTextPoint, val end: ReadTextPoint) : Result
    }

    fun finish(): Result? {
        val point = start ?: return null
        val result = if (dragging) {
            Result.Capture
        } else {
            val previous = anchor?.takeIf { it.page == point.page }
            anchor = if (previous == null) point else null
            Result.Select(previous ?: point, point)
        }
        start = null
        dragging = false
        return result
    }

    fun reset() {
        start = null
        anchor = null
        dragging = false
    }
}
