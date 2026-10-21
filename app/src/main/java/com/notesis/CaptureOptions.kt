package com.notesis

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.ink.strokes.Stroke
import kotlin.math.max

/**
 * A captured region of one page with everything needed to draw it again:
 * where it is, how it was seen, and a snapshot of the ink and pictures on it.
 * See [InkCanvasView.renderCapture].
 */
class CaptureRegion internal constructor(
    val pageIndex: Int,
    /** Page-local units. */
    val rect: RectF,
    /** Screen pixels per page unit when it was taken: what "as seen" means. */
    val screenScale: Float,
    internal val pageWidth: Float,
    internal val pageHeight: Float,
    /** The PDF page behind it, or -1. */
    internal val pdfIndex: Int,
    internal val strokes: List<Stroke>,
    internal val images: List<Pair<PageImage, Bitmap?>>,
) {
    val onPdf: Boolean get() = pdfIndex >= 0
}

/**
 * How big a capture comes out. [SCREEN] is the region as it was seen; the
 * multiples are of the page's own resolution (a page unit is a pixel at 1×,
 * 150 dpi); the long-edge presets fit the region's longer side to a number of
 * pixels. Every one keeps the selection's aspect ratio.
 */
internal enum class CaptureSize(val label: String) {
    SCREEN("Screen"),
    X1("1×"),
    X2("2×"),
    X3("3×"),
    X4("4×"),
    EDGE_1080("1080 px"),
    EDGE_2048("2048 px"),
    EDGE_4096("4096 px"),
}

internal enum class CaptureFormat(val label: String, val extension: String, val mime: String) {
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
}

internal data class CaptureOptions(
    val size: CaptureSize = CaptureSize.EDGE_2048,
    val format: CaptureFormat = CaptureFormat.PNG,
    /** 1-100, used for [CaptureFormat.JPEG] only. */
    val jpegQuality: Int = 90,
) {
    fun encode(): String = "${size.name},${format.name},$jpegQuality"

    companion object {
        val JPEG_QUALITIES = listOf(70, 85, 95)

        /** Back from [encode]; anything unreadable falls back to the default. */
        fun decode(text: String?): CaptureOptions {
            val parts = text?.split(',') ?: return CaptureOptions()
            val size = CaptureSize.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: CaptureSize.EDGE_2048
            val format = CaptureFormat.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: CaptureFormat.PNG
            val quality = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 100) ?: 90
            return CaptureOptions(size, format, quality)
        }
    }
}

/** Why a capture comes out smaller than asked for. */
internal enum class CaptureLimit { NONE, MEMORY, SIDE, PDF }

/** The pixel size a capture will be rendered at, and the scale (pixels per page unit) that gives it. */
internal data class CapturePlan(val width: Int, val height: Int, val scale: Float, val limit: CaptureLimit)

/** The scale [size] asks for, before any limit: pixels per page unit. */
internal fun requestedCaptureScale(rectWidth: Float, rectHeight: Float, size: CaptureSize, screenScale: Float): Float {
    val longEdge = max(rectWidth, rectHeight).coerceAtLeast(1f)
    return when (size) {
        CaptureSize.SCREEN -> screenScale
        CaptureSize.X1 -> 1f
        CaptureSize.X2 -> 2f
        CaptureSize.X3 -> 3f
        CaptureSize.X4 -> 4f
        CaptureSize.EDGE_1080 -> 1080f / longEdge
        CaptureSize.EDGE_2048 -> 2048f / longEdge
        CaptureSize.EDGE_4096 -> 4096f / longEdge
    }.takeIf { it.isFinite() && it > 0f } ?: 1f
}

/**
 * Where a [rectWidth] x [rectHeight] region (page units) at [size] lands once
 * the memory budget ([budgetBytes] for 4-byte pixels), the largest side a
 * bitmap may have ([maxSide]) and, on a PDF page, the largest side the PDF is
 * rendered at ([pdfMaxSide], null for no PDF) have had their say. One scale
 * for both axes, so the aspect ratio stays the selection's.
 */
internal fun planCapture(
    rectWidth: Float,
    rectHeight: Float,
    size: CaptureSize,
    screenScale: Float,
    budgetBytes: Long,
    maxSide: Int,
    pdfMaxSide: Int? = null,
): CapturePlan {
    val w = rectWidth.coerceAtLeast(1f)
    val h = rectHeight.coerceAtLeast(1f)
    var scale = requestedCaptureScale(w, h, size, screenScale)
    var limit = CaptureLimit.NONE
    val longEdge = max(w, h)
    if (pdfMaxSide != null && capturePixels(longEdge, scale) > pdfMaxSide) {
        scale = pdfMaxSide / longEdge
        limit = CaptureLimit.PDF
    }
    if (capturePixels(longEdge, scale) > maxSide) {
        scale = maxSide / longEdge
        limit = CaptureLimit.SIDE
    }
    val byMemory = bitmapBudgetScale(capturePixels(w, scale), capturePixels(h, scale), budgetBytes, Int.MAX_VALUE)
    if (byMemory < 1f) {
        scale *= byMemory
        limit = CaptureLimit.MEMORY
    }
    return CapturePlan(capturePixels(w, scale), capturePixels(h, scale), scale, limit)
}

/**
 * Pixels across [units] page units at [scale]. The tolerance keeps a long edge
 * fitted to 2048 from coming out 2047 by a rounding error.
 */
internal fun capturePixels(units: Float, scale: Float): Int = (units * scale + 0.001f).toInt().coerceAtLeast(1)

/** What a person is told about [limit]. Null when nothing was held back. */
internal fun captureLimitMessage(limit: CaptureLimit, maxSide: Int, pdfMaxSide: Int = maxSide): String? = when (limit) {
    CaptureLimit.NONE -> null
    CaptureLimit.MEMORY -> "Reduced to fit in memory"
    CaptureLimit.SIDE -> "Capped at $maxSide px per side"
    CaptureLimit.PDF -> "Capped: the PDF page renders at most $pdfMaxSide px per side"
}
