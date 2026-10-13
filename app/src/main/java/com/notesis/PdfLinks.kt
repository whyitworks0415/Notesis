package com.notesis

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** A PDF link rectangle expressed in the same top-left page coordinates as [PdfSource]. */
data class PdfPageLink(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val targetPageIndex: Int,
    val webUrl: String? = null,
) {
    fun contains(x: Float, y: Float, tolerance: Float = 0f): Boolean =
        x >= left - tolerance && x <= right + tolerance &&
            y >= top - tolerance && y <= bottom + tolerance
}

/**
 * Reads internal GoTo and web annotations once when a PDF is opened.
 *
 * PdfRenderer paints annotations but does not expose them before Android 15.
 * PDFBox gives all supported Android versions the same link behaviour, while
 * PdfRenderer remains the faster drawing and text-selection implementation.
 */
internal fun loadPdfPageLinks(file: File): Map<Int, List<PdfPageLink>> =
    runCatching {
        // Spill to a temp file rather than holding a large PDF's whole object
        // graph in the heap; this runs beside drawing, which needs the memory.
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            buildMap {
                for (pageIndex in 0 until document.numberOfPages) {
                    val page = document.getPage(pageIndex)
                    val links = page.annotations
                        .filterIsInstance<PDAnnotationLink>()
                        .mapNotNull { annotation ->
                            val webUrl = (annotation.action as? PDActionURI)?.uri
                                ?.takeIf { it.isNotBlank() }
                            val destination = when (val action = annotation.action) {
                                is PDActionGoTo -> action.destination
                                else -> annotation.destination
                            }
                            val target = if (webUrl != null) -1 else destinationPage(document, destination)
                                .takeIf { it in 0 until document.numberOfPages }
                                ?: return@mapNotNull null
                            val rectangle = annotation.rectangle ?: return@mapNotNull null
                            pdfRectangleToPage(
                                page = page,
                                lowerLeftX = rectangle.lowerLeftX,
                                lowerLeftY = rectangle.lowerLeftY,
                                upperRightX = rectangle.upperRightX,
                                upperRightY = rectangle.upperRightY,
                                targetPageIndex = target,
                            ).copy(webUrl = webUrl)
                        }
                    if (links.isNotEmpty()) put(pageIndex, links)
                }
            }
        }
    }.getOrDefault(emptyMap())

private fun destinationPage(document: PDDocument, destination: PDDestination?): Int =
    when (destination) {
        is PDPageDestination -> destination.retrievePageNumber()
        is PDNamedDestination ->
            document.documentCatalog.findNamedDestinationPage(destination)?.retrievePageNumber() ?: -1
        else -> -1
    }

internal fun pdfRectangleToPage(
    page: PDPage,
    lowerLeftX: Float,
    lowerLeftY: Float,
    upperRightX: Float,
    upperRightY: Float,
    targetPageIndex: Int,
): PdfPageLink {
    val crop = page.cropBox
    val width = crop.width
    val height = crop.height
    val rotation = ((page.rotation % 360) + 360) % 360
    val userUnit = page.userUnit.takeIf { it > 0f } ?: 1f

    fun displayed(x: Float, y: Float): Pair<Float, Float> {
        val localX = x - crop.lowerLeftX
        val localY = y - crop.lowerLeftY
        val point = when (rotation) {
            90 -> localY to localX
            180 -> (width - localX) to localY
            270 -> (height - localY) to (width - localX)
            else -> localX to (height - localY)
        }
        val scale = userUnit * PdfSource.POINTS_TO_WORLD
        return point.first * scale to point.second * scale
    }

    val corners = listOf(
        displayed(lowerLeftX, lowerLeftY),
        displayed(lowerLeftX, upperRightY),
        displayed(upperRightX, lowerLeftY),
        displayed(upperRightX, upperRightY),
    )
    return PdfPageLink(
        left = corners.minOf { it.first },
        top = corners.minOf { it.second },
        right = corners.maxOf { it.first },
        bottom = corners.maxOf { it.second },
        targetPageIndex = targetPageIndex,
    )
}

/**
 * The links [loadPdfPageLinks] found in [pdf], read back from [cache], or null
 * when there is no cache or it was written for a different file - a PDF that
 * has since been replaced, or grown, has links of its own to find.
 */
internal fun readPdfLinksCache(cache: File, pdf: File): Map<Int, List<PdfPageLink>>? = runCatching {
    if (!cache.isFile) return null
    val json = JSONObject(cache.readText())
    if (json.optInt("version") != LINKS_CACHE_VERSION ||
        json.optLong("size") != pdf.length() || json.optLong("modified") != pdf.lastModified()
    ) return null
    val pages = json.getJSONObject("pages")
    buildMap {
        for (key in pages.keys()) {
            val array = pages.getJSONArray(key)
            put(key.toInt(), (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                PdfPageLink(
                    left = item.getDouble("l").toFloat(),
                    top = item.getDouble("t").toFloat(),
                    right = item.getDouble("r").toFloat(),
                    bottom = item.getDouble("b").toFloat(),
                    targetPageIndex = item.getInt("p"),
                    webUrl = if (item.has("u")) item.getString("u") else null,
                )
            })
        }
    }
}.getOrNull()

/** Keeps [links] beside [pdf] so the next opening does not have to parse it again. */
internal fun writePdfLinksCache(cache: File, pdf: File, links: Map<Int, List<PdfPageLink>>) {
    runCatching {
        val pages = JSONObject()
        for ((page, list) in links) {
            pages.put(page.toString(), JSONArray(list.map { link ->
                JSONObject()
                    .put("l", link.left.toDouble())
                    .put("t", link.top.toDouble())
                    .put("r", link.right.toDouble())
                    .put("b", link.bottom.toDouble())
                    .put("p", link.targetPageIndex)
                    .apply { link.webUrl?.let { put("u", it) } }
            }))
        }
        val json = JSONObject()
            .put("version", LINKS_CACHE_VERSION)
            .put("size", pdf.length())
            .put("modified", pdf.lastModified())
            .put("pages", pages)
        // Beside it and renamed over it, so a note opened twice at once never
        // reads a half-written cache.
        val temp = File(cache.parentFile, "${cache.name}.${Thread.currentThread().id}.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(cache)) temp.delete()
    }
}

private const val LINKS_CACHE_VERSION = 1
