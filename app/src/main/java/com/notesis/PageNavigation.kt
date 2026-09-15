package com.notesis

import kotlin.math.roundToInt

/** A saved page can outlive page deletion, so every restore goes through here. */
internal fun restoredPage(savedPage: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else savedPage.coerceIn(0, pageCount - 1)

/** Maps the centre of a vertical scrubber thumb to a discrete page. */
internal fun scrubbedPage(
    pointerY: Float,
    trackHeight: Float,
    thumbHeight: Float,
    pageCount: Int,
): Int {
    if (pageCount <= 1 || trackHeight <= thumbHeight) return 0
    val travel = trackHeight - thumbHeight
    val fraction = ((pointerY - thumbHeight / 2f) / travel).coerceIn(0f, 1f)
    return (fraction * (pageCount - 1)).roundToInt()
}

/** 세 페이지 경계를 실제로 지난 뒤에만 빠른 페이지 바를 보여 줍니다. */
internal fun shouldShowPageScrubber(pagesTraversed: Int, pageCount: Int): Boolean =
    pageCount > 1 && pagesTraversed >= 3
