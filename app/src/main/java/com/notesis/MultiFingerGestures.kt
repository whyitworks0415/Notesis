package com.notesis

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Undo, redo, and a floating reference panel, all reached with fingers rather
 * than the pen - the stylus is busy drawing and every one of these has to
 * happen without putting it down.
 *
 * Two fingers stay entirely out of the way of the page's own pinch-to-pan-and-
 * zoom: this only ever *watches* a two-finger touch, and acts on it - undo -
 * when it turns out to have been a tap rather than a pinch, which is a touch
 * so brief and so still that the zoom it would have produced was nothing
 * anyway. Nothing is ever consumed at two fingers, so the page's own gesture
 * runs exactly as it always did.
 *
 * Three fingers are a zone the page never had a use for, so this claims them
 * outright the moment a third finger lands: a quick tap redoes, and a drag
 * either opens the reference panel (net upward motion, panel closed) or moves
 * and resizes it (panel open) - spreading the fingers grows it, and the drag
 * itself is where it goes. Both ride the same three fingers, because letting
 * go to switch gestures is the one thing a hand mid-drag cannot do without
 * losing its place - so opening flows straight into positioning within the
 * same continuous touch.
 */
fun Modifier.multiFingerGestures(
    popupOpen: () -> Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenPopup: (Offset) -> Unit,
    /** Screen-pixel pan this frame, and the multiple the spread grew by. */
    onDrag: (pan: Offset, spreadFactor: Float) -> Unit,
): Modifier = pointerInput(Unit) {
    var lastTapFingers = 0
    var lastTapTime = 0L
    var lastTapPos = Offset.Zero
    while (true) {
        awaitPointerEventScope {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val startTime = System.currentTimeMillis()
            var maxPointers = 1
            var opened = popupOpen()
            var centroid = Offset.Zero
            var haveCentroid = false
            var totalMovement = 0f
            // Only meaningful once three fingers are actually down; null until
            // then.
            var prevCentroid3f: Offset? = null
            var prevSpread3f: Float? = null
            // Where the third finger landed, kept for the whole gesture: the
            // open check is a net distance from there, not a per-frame one -
            // per frame the fingers barely move at all.
            var startCentroid3f: Offset? = null

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                maxPointers = maxOf(maxPointers, pressed.size)

                if (pressed.isNotEmpty()) {
                    val next = pressed.fold(Offset.Zero) { sum, c -> sum + c.position } /
                        pressed.size.toFloat()
                    if (haveCentroid) totalMovement += (next - centroid).getDistance()
                    centroid = next
                    haveCentroid = true
                }

                // A zone the page never used: claimed outright the moment a
                // third finger lands, so nothing below reacts to any of them
                // for the rest of this gesture.
                if (maxPointers >= 3) {
                    for (change in event.changes) change.consume()
                }

                if (pressed.size >= 3) {
                    val spread = averageSpread(pressed.map { it.position })
                    if (startCentroid3f == null) startCentroid3f = centroid
                    if (opened) {
                        val prevC = prevCentroid3f
                        val prevS = prevSpread3f
                        if (prevC != null && prevS != null) {
                            val factor = if (prevS > MIN_SPREAD_PX) spread / prevS else 1f
                            onDrag(centroid - prevC, factor)
                        }
                    } else if (startCentroid3f!!.y - centroid.y > OPEN_DRAG_PX) {
                        onOpenPopup(centroid)
                        opened = true
                        // The frame it opens on moves it by nothing, not by the
                        // whole drag that opened it - positioning starts fresh.
                    }
                    prevCentroid3f = centroid
                    prevSpread3f = spread
                } else if (maxPointers < 3) {
                    prevCentroid3f = null
                    prevSpread3f = null
                    startCentroid3f = null
                }

                if (pressed.isEmpty()) break
            }

            val duration = System.currentTimeMillis() - startTime
            val isTap = maxPointers in 2..3 &&
                duration < TAP_MAX_MS &&
                totalMovement < TAP_SLOP_PX

            if (isTap) {
                val now = System.currentTimeMillis()
                val sameSpot = (centroid - lastTapPos).getDistance() < DOUBLE_TAP_SLOP_PX
                if (lastTapFingers == maxPointers && now - lastTapTime < DOUBLE_TAP_MS && sameSpot) {
                    when (maxPointers) {
                        2 -> onUndo()
                        3 -> onRedo()
                    }
                    lastTapFingers = 0
                } else {
                    lastTapFingers = maxPointers
                    lastTapTime = now
                    lastTapPos = centroid
                }
            }
        }
    }
}

/** Every pair's distance, averaged - one number for how spread the hand is. */
private fun averageSpread(points: List<Offset>): Float {
    if (points.size < 2) return 0f
    var total = 0f
    var pairs = 0
    for (i in points.indices) {
        for (j in i + 1 until points.size) {
            total += (points[i] - points[j]).getDistance()
            pairs++
        }
    }
    return if (pairs > 0) total / pairs else 0f
}

/** A tap this fast, moved this little, is a tap and not the start of a pan. */
private const val TAP_MAX_MS = 250L
private const val TAP_SLOP_PX = 28f
private const val DOUBLE_TAP_MS = 400L
private const val DOUBLE_TAP_SLOP_PX = 140f

/** How far up three fingers have to travel before that reads as "open it". */
private const val OPEN_DRAG_PX = 120f

/** Below this the fingers are practically on top of each other; no factor. */
private const val MIN_SPREAD_PX = 8f
