package com.notesis

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.OutputStream

/** Immutable page payload assembled from the note files before PDF export. */
internal data class VectorPdfPage(
    val page: Page,
    val strokes: List<Stroke>,
    val masks: List<Stroke>,
)

/**
 * Writes a PDF without flattening either an imported page or handwriting.
 *
 * Imported pages are copied directly, retaining their text, fonts and vector
 * graphics. Ink's tessellated outline is emitted as ordinary PDF paths. This is
 * deliberately separate from the on-screen Canvas renderer: a Canvas mesh can
 * be rasterised by PdfDocument, while move/line/fill operators cannot be.
 */
internal fun writeVectorPdf(
    context: Context,
    sourceFile: File,
    pages: List<VectorPdfPage>,
    imageFile: (String) -> File,
    out: OutputStream,
): Boolean = runCatching {
    PDFBoxResourceLoader.init(context.applicationContext)
    val source = if (sourceFile.isFile) {
        PDDocument.load(
            sourceFile,
            MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir),
        )
    } else {
        null
    }
    val result = PDDocument(
        MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir),
    )
    try {
        for (item in pages) {
            val model = item.page
            if (model.width <= 0f || model.height <= 0f) continue
            val imported = model.background == PageBackground.PDF &&
                source != null && model.pdfPageIndex in 0 until source.numberOfPages
            val pdfPage = if (imported) {
                result.importPage(source!!.getPage(model.pdfPageIndex))
            } else {
                PDPage(
                    PDRectangle(
                        model.width / PdfSource.POINTS_TO_WORLD,
                        model.height / PdfSource.POINTS_TO_WORLD,
                    ),
                ).also(result::addPage)
            }

            PDPageContentStream(
                result,
                pdfPage,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true,
            ).use { stream ->
                stream.saveGraphicsState()
                stream.transform(displayToPdfMatrix(pdfPage, model))
                drawPaper(stream, model, imported)
                drawImages(result, stream, model, imageFile)
                for (stroke in item.strokes) drawStroke(stream, stroke)
                for (stroke in item.masks) drawStroke(stream, stroke)
                stream.restoreGraphicsState()
            }
        }
        result.save(out)
    } finally {
        runCatching { result.close() }
        runCatching { source?.close() }
    }
    true
}.getOrDefault(false)

/**
 * App coordinates are the displayed page in points, with (0,0) at top-left.
 * PDF coordinates are the unrotated crop box, with (0,0) at bottom-left.
 * This matrix covers crop offsets and all legal page rotations.
 */
private fun displayToPdfMatrix(pdfPage: PDPage, model: Page): Matrix {
    val crop = pdfPage.cropBox
    val rotation = ((pdfPage.rotation % 360) + 360) % 360
    val modelWidth = (model.width / PdfSource.POINTS_TO_WORLD).coerceAtLeast(0.001f)
    val modelHeight = (model.height / PdfSource.POINTS_TO_WORLD).coerceAtLeast(0.001f)
    val displayWidth = if (rotation == 90 || rotation == 270) crop.height else crop.width
    val displayHeight = if (rotation == 90 || rotation == 270) crop.width else crop.height
    val sx = displayWidth / modelWidth
    val sy = displayHeight / modelHeight
    val left = crop.lowerLeftX
    val bottom = crop.lowerLeftY
    return when (rotation) {
        90 -> Matrix(0f, sx, sy, 0f, left, bottom)
        180 -> Matrix(-sx, 0f, 0f, sy, left + crop.width, bottom)
        270 -> Matrix(0f, -sx, -sy, 0f, left + crop.width, bottom + crop.height)
        else -> Matrix(sx, 0f, 0f, -sy, left, bottom + crop.height)
    }
}

private fun drawPaper(stream: PDPageContentStream, page: Page, imported: Boolean) {
    if (imported || page.background == PageBackground.BLANK) return
    val unit = PdfSource.POINTS_TO_WORLD
    val width = page.width / unit
    val height = page.height / unit
    val spacing = 60f / unit
    stream.saveGraphicsState()
    stream.setStrokingColor(0xDA, 0xDA, 0xDA)
    stream.setLineWidth(0.55f)
    var y = spacing
    while (y < height) {
        stream.moveTo(0f, y)
        stream.lineTo(width, y)
        y += spacing
    }
    if (page.background == PageBackground.GRID) {
        var x = spacing
        while (x < width) {
            stream.moveTo(x, 0f)
            stream.lineTo(x, height)
            x += spacing
        }
    }
    stream.stroke()
    stream.restoreGraphicsState()
}

private fun drawImages(
    document: PDDocument,
    stream: PDPageContentStream,
    page: Page,
    imageFile: (String) -> File,
) {
    val unit = PdfSource.POINTS_TO_WORLD
    for (placement in page.images) {
        val bitmap = BitmapFactory.decodeFile(imageFile(placement.id).path) ?: continue
        try {
            val image = LosslessFactory.createFromImage(document, bitmap)
            val x = placement.x / unit
            val y = placement.y / unit
            val width = placement.width / unit
            val height = placement.height / unit
            // The enclosing coordinate system points down. Flip the image once
            // locally so its bitmap rows remain upright after that page transform.
            stream.drawImage(image, Matrix(width, 0f, 0f, -height, x, y + height))
        } finally {
            bitmap.recycle()
        }
    }
}

private fun drawStroke(stream: PDPageContentStream, stroke: Stroke) {
    val argb = stroke.brush.colorIntArgb
    val alpha = Color.alpha(argb) / 255f
    if (alpha <= 0f) return
    stream.saveGraphicsState()
    if (alpha < 0.999f) {
        stream.setGraphicsStateParameters(
            PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = alpha },
        )
    }
    stream.setNonStrokingColor(Color.red(argb), Color.green(argb), Color.blue(argb))

    val shape = stroke.shape
    val point = MutableVec()
    val unit = PdfSource.POINTS_TO_WORLD
    var hasPath = false
    for (group in 0 until shape.getRenderGroupCount()) {
        for (outline in 0 until shape.getOutlineCount(group)) {
            val count = shape.getOutlineVertexCount(group, outline)
            if (count < 3) continue
            for (index in 0 until count) {
                shape.populateOutlinePosition(group, outline, index, point)
                val x = point.x / unit
                val y = point.y / unit
                if (index == 0) stream.moveTo(x, y) else stream.lineTo(x, y)
            }
            stream.closePath()
            hasPath = true
        }
    }
    if (hasPath) stream.fillEvenOdd()
    stream.restoreGraphicsState()
}
