package com.notesis

internal data class PdfCacheBudget(
    val previewBytes: Int,
    val fullPageBytes: Int,
    val tileBytes: Int,
) {
    val totalBytes: Int get() = previewBytes + fullPageBytes + tileBytes
}

/** One total byte budget, split by role so previews cannot evict visible detail. */
internal fun splitPdfCacheBudget(totalBytes: Int): PdfCacheBudget {
    val safe = totalBytes.coerceAtLeast(5)
    val preview = safe / 5
    val fullPage = safe * 2 / 5
    return PdfCacheBudget(preview, fullPage, safe - preview - fullPage)
}

/** Conservative per-source budget: two open note panels must still fit together. */
internal fun pdfCacheBytesForMemoryClass(memoryClassMb: Int, lowRam: Boolean): Int {
    val divisor = if (lowRam) 10 else 8
    val bytes = memoryClassMb.coerceAtLeast(1).toLong() * 1024L * 1024L / divisor
    return bytes.coerceIn(PDF_CACHE_MIN_BYTES.toLong(), PDF_CACHE_MAX_BYTES.toLong()).toInt()
}

internal fun pdfRenderWidthForPage(
    page: Int,
    fullResolutionPages: Set<Int>,
    requestedWidth: Int,
    previewWidth: Int,
): Int = if (page in fullResolutionPages) requestedWidth else minOf(requestedWidth, previewWidth)

internal fun pdfViewportCacheChanged(
    previousPages: Set<Int>,
    nextPages: Set<Int>,
    previousFullResolutionPages: Set<Int>,
    nextFullResolutionPages: Set<Int>,
    previousWidthBucket: Int,
    nextWidthBucket: Int,
): Boolean = previousPages != nextPages ||
    previousFullResolutionPages != nextFullResolutionPages ||
    previousWidthBucket != nextWidthBucket

internal const val PDF_CACHE_MIN_BYTES = 64 * 1024 * 1024
internal const val PDF_CACHE_MAX_BYTES = 96 * 1024 * 1024
