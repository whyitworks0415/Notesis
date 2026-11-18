package com.notesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Smaller copies tried when the full-size picture reads little or nothing, as shares of its size. */
private val PICTURE_RETRY_SCALES = floatArrayOf(0.5f, 0.25f)
/** A reading with at least this many letters is not tried smaller. */
private const val PICTURE_GOOD_ENOUGH = 4
/** The shorter side below which a smaller copy is not tried. */
private const val PICTURE_MIN_SIDE = 48

/**
 * Text in a picture, read on the tablet by ML Kit's text recogniser (the
 * Korean model, which reads Latin text too). The model comes through Play
 * services rather than inside the app, so the app does not grow; the first
 * use asks for it, and a reading tried before it has arrived says so.
 */
internal object PictureOcr {
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /** Lines read from [picture], top to bottom; null with a reason when it could not be read. */
    suspend fun read(context: Context, picture: Bitmap): Pair<List<OcrLine>?, String?> {
        if (!ensureModel(context)) return null to "The text model is still downloading. Try again in a moment."
        return runCatching {
            // Large handwriting in a thick pen came back as nothing at the full size: the
            // recogniser looks for text of about printed size. Smaller copies are tried
            // until one reads, and the reading with the most letters is kept.
            var best = linesIn(picture)
            for (factor in PICTURE_RETRY_SCALES) {
                if (letters(best) >= PICTURE_GOOD_ENOUGH) break
                val width = (picture.width * factor).toInt()
                val height = (picture.height * factor).toInt()
                if (minOf(width, height) < PICTURE_MIN_SIDE) break
                val smaller = Bitmap.createScaledBitmap(picture, width, height, true)
                val lines = try { linesIn(smaller) } finally { if (smaller !== picture) smaller.recycle() }
                if (letters(lines) > letters(best)) best = lines
            }
            best to null
        }.getOrElse { null to "The text model is not available on this tablet" }
    }

    private suspend fun linesIn(picture: Bitmap): List<OcrLine> =
        recognizer.process(InputImage.fromBitmap(picture, 0)).awaited().textBlocks.flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .map { OcrLine(listOf(OcrReading(it.text, OCR_SOURCE_PICTURE))) }

    private fun letters(lines: List<OcrLine>): Int = lines.sumOf { line -> line.readings.first().text.count { !it.isWhitespace() } }

    /** Plain text of [picture], lines in reading order; empty when nothing was read. */
    suspend fun textOf(context: Context, picture: Bitmap): String =
        read(context, picture).first.orEmpty().joinToString("\n") { it.readings.first().text }

    private suspend fun ensureModel(context: Context): Boolean = runCatching {
        val client = ModuleInstall.getClient(context)
        if (client.areModulesAvailable(recognizer).awaited().areModulesAvailable()) return@runCatching true
        // Asked once; Play services fetches it in the background.
        client.installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).build()).awaited()
            .areModulesAlreadyInstalled()
    }.getOrDefault(true)
}

/**
 * Scanned pages made searchable: a PDF page with no text of its own (a scan,
 * a photo) is drawn and read the picture way, and the text kept beside the
 * page where extracted PDF text lives (`<page>.txt`), so search, the page
 * panel and agents find it. Pages that already have text are left alone.
 */
internal object PageTextOcr {
    /** Longer side a page is drawn at for reading: enough for body text on A4. */
    private const val PAGE_PX = 2000

    /** How many pages were read and kept. Blocking work runs off the main thread. */
    suspend fun fillScannedPages(context: Context, store: NoteStore, noteId: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val pdf = store.pdfFile(noteId).takeIf { it.isFile } ?: return@withContext 0
            val document = runCatching { store.load(noteId) }.getOrNull() ?: return@withContext 0
            val wanted = document.pages.filter { it.background == PageBackground.PDF && it.pdfPageIndex >= 0 }
                .filter { page -> store.pageText(noteId, page.id).isBlank() }
            if (wanted.isEmpty()) return@withContext 0
            var kept = 0
            val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor).use { renderer ->
                wanted.forEachIndexed { done, page ->
                    onProgress(done, wanted.size)
                    if (page.pdfPageIndex >= renderer.pageCount) return@forEachIndexed
                    val bitmap = renderer.openPage(page.pdfPageIndex).use { pdfPage ->
                        val scale = PAGE_PX.toFloat() / max(pdfPage.width, pdfPage.height).coerceAtLeast(1)
                        Bitmap.createBitmap((pdfPage.width * scale).toInt().coerceAtLeast(1),
                            (pdfPage.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            pdfPage.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                    val text = try { PictureOcr.textOf(context, bitmap) } finally { bitmap.recycle() }
                    if (text.isNotBlank() && store.writePageText(noteId, page.id, text)) kept++
                }
            }
            kept
        }
}

/**
 * Where a PDF came from when another app handed it over to be written on (the
 * pen button of Chrome's PDF viewer, "Edit" in a file manager) with leave to
 * write it back: the note can then save its writing into that same file
 * instead of a copy. Kept per note; forgotten once the leave has run out.
 */
internal object PdfOrigins {
    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("pdf-origins", android.content.Context.MODE_PRIVATE)

    fun of(context: android.content.Context, noteId: String): android.net.Uri? =
        prefs(context).getString(noteId, null)?.let(android.net.Uri::parse)

    fun remember(context: android.content.Context, noteId: String, uri: android.net.Uri) {
        prefs(context).edit().putString(noteId, uri.toString()).apply()
    }

    fun forget(context: android.content.Context, noteId: String) {
        prefs(context).edit().remove(noteId).apply()
    }

    /**
     * Writes the note, as "Export as PDF" makes it, into the file it came from.
     * Made whole in a temporary file first, so a failure leaves the original as
     * it was. False when it could not be written; the leave is then forgotten.
     */
    fun saveBack(context: android.content.Context, store: NoteStore, noteId: String): Boolean {
        val uri = of(context, noteId) ?: return false
        val temp = java.io.File(context.cacheDir, "save-back-$noteId.pdf")
        return try {
            if (!temp.outputStream().use { store.exportPdf(noteId, it) }) return false
            context.contentResolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: return false
            true
        } catch (_: SecurityException) {
            forget(context, noteId)
            false
        } catch (_: Exception) {
            false
        } finally {
            temp.delete()
        }
    }
}
