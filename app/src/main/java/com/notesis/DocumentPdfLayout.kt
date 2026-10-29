package com.notesis

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.NotesisPdfPrint
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/** How a document is laid on PDF pages: the paper, its turn, the text size and the margins. */
internal data class PdfLayoutOptions(
    val paper: PdfPaper = PdfPaper.A4,
    val landscape: Boolean = false,
    /** The text drawn at this many times its size; 1 is the document's own. */
    val scale: Float = 1f,
    val margin: PdfMargin = PdfMargin.NORMAL,
) {
    fun toJson(): String = JSONObject()
        .put("paper", paper.id)
        .put("landscape", landscape)
        .put("scale", scale.toDouble())
        .put("margin", margin.mm.toDouble())
        .toString()

    /** Smaller or larger text, a step at a time, kept between [MIN_SCALE] and [MAX_SCALE]. */
    fun stepScale(steps: Int): PdfLayoutOptions =
        copy(scale = ((scale * 10f).let(Math::round) + steps).coerceIn((MIN_SCALE * 10).toInt(), (MAX_SCALE * 10).toInt()) / 10f)

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 1.8f
    }
}

internal enum class PdfPaper(val id: String, val label: String, val widthMm: Float, val heightMm: Float) {
    A4("A4", "A4", 210f, 297f),
    LETTER("Letter", "Letter", 215.9f, 279.4f),
    A5("A5", "A5", 148f, 210f),
}

internal enum class PdfMargin(val label: String, val mm: Float) {
    NARROW("Narrow", 8f),
    NORMAL("Normal", 15f),
    WIDE("Wide", 25f),
}

/** What the viewer has on show, as notesis-print.js reports it. */
internal enum class LayoutKind { NONE, FLOW, PAGES, SHEET }

internal fun layoutKindOf(reported: String?): LayoutKind = when (reported?.trim('"')) {
    "flow" -> LayoutKind.FLOW
    "pages" -> LayoutKind.PAGES
    "sheet" -> LayoutKind.SHEET
    else -> LayoutKind.NONE
}

/** Thousandths of an inch, what print attributes count in, for [mm]. */
internal fun mmToMils(mm: Float): Int = Math.round(mm / 25.4f * 1000f)

internal suspend fun WebView.evalJs(script: String): String? = suspendCancellableCoroutine { done ->
    runCatching { evaluateJavascript(script) { done.resume(it) } }.onFailure { done.resume(null) }
}

/** How a print of the laid-out document ended. */
internal sealed interface LayoutPrint {
    data class Written(val file: File) : LayoutPrint
    /** The device would not print to a file; the system print screen was opened instead. */
    data object HandedToSystem : LayoutPrint
    data object Failed : LayoutPrint
}

/**
 * Prints the document in [view] - every page, as notesis-print.js gathers them,
 * with the layout set on it - to a PDF in [dir]. Straight to the file where
 * the device allows it; otherwise through the system print screen, where the
 * person saves it as a PDF themselves.
 */
internal suspend fun printLayoutToPdf(view: WebView, title: String, dir: File): LayoutPrint {
    view.evalJs("window.notesisLayout && notesisLayout.prepare()")
    var state = "collecting"
    // A long document is read page by page; give it minutes, not seconds.
    for (i in 0 until 3000) {
        state = view.evalJs("window.notesisLayout ? notesisLayout.state() : 'failed'")?.trim('"') ?: "failed"
        if (state == "printable" || state == "failed") break
        delay(100)
    }
    if (state != "printable") return LayoutPrint.Failed
    // evaluateJavascript answers in JSON: the array itself.
    val paper = runCatching { JSONArray(view.evalJs("notesisLayout.paperMm()")) }.getOrNull()
    val widthMm = paper?.optDouble(0)?.toFloat()?.takeIf { it > 10f } ?: PdfPaper.A4.widthMm
    val heightMm = paper?.optDouble(1)?.toFloat()?.takeIf { it > 10f } ?: PdfPaper.A4.heightMm
    val attributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize("notesis", "Notesis", mmToMils(widthMm), mmToMils(heightMm)))
        .setResolution(PrintAttributes.Resolution("pdf", "PDF", 600, 600))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .build()
    val adapter = view.createPrintDocumentAdapter(title)
    val file = File.createTempFile("layout", ".pdf", dir)
    val written = runCatching {
        suspendCancellableCoroutine<Boolean> { done -> NotesisPdfPrint.write(adapter, attributes, file) { done.resume(it) } }
    }.getOrElse { error ->
        // A device that will not let the callbacks be made: let the system print it.
        if (error is LinkageError || error is SecurityException) {
            runCatching {
                view.context.getSystemService(PrintManager::class.java)
                    ?.print(title, view.createPrintDocumentAdapter(title), attributes)
            }.onSuccess { return LayoutPrint.HandedToSystem }
        }
        false
    }
    return if (written && file.length() > 0) LayoutPrint.Written(file) else LayoutPrint.Failed
}

/** The controls under a document being laid out for a PDF. */
@Composable
internal fun PdfLayoutBar(
    kind: LayoutKind,
    options: PdfLayoutOptions,
    onOptions: (PdfLayoutOptions) -> Unit,
    busy: Boolean,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
    /** Undoes every page break and pull made by hand; null where the document has none to undo. */
    onReset: (() -> Unit)? = null,
) {
    Surface(modifier, tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Ui.Space.m, vertical = Ui.Space.s),
            verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
            Text(
                when (kind) {
                    LayoutKind.FLOW -> if (onReset != null) "Tap a block to start a new page there. Tap a page's first block to pull it " +
                        "back onto the page before, which shrinks to fit it. Tap again to undo."
                    else "Tap a paragraph to start it on a new page; tap it again to undo."
                    LayoutKind.PAGES -> "Each page keeps its own size and layout."
                    LayoutKind.SHEET -> "The sheet on show is shrunk to fit the page's width; landscape gives it more room."
                    LayoutKind.NONE -> "Laying out the document…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PdfOptionControls(kind, options, onOptions)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (onReset != null) TextButton(onClick = onReset, enabled = !busy && kind != LayoutKind.NONE) { Text("Reset pages") }
                Box(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(end = Ui.Space.s).size(20.dp), strokeWidth = 2.dp)
                    Text("Preparing pages…", style = MaterialTheme.typography.labelLarge)
                }
                Button(onClick = onPreview, enabled = !busy && kind != LayoutKind.NONE,
                    modifier = Modifier.padding(start = Ui.Space.s)) { Text("Preview") }
            }
        }
    }
}

/** Paper, turn, text size and margins; what a fixed-page document does not use is left out. */
@Composable
internal fun PdfOptionControls(kind: LayoutKind, options: PdfLayoutOptions, onOptions: (PdfLayoutOptions) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
        if (kind != LayoutKind.PAGES) {
            PdfPaper.entries.forEach { paper ->
                SettingsChoiceChip(selected = options.paper == paper, onClick = { onOptions(options.copy(paper = paper)) },
                    label = paper.label)
            }
            SettingsChoiceChip(selected = !options.landscape, onClick = { onOptions(options.copy(landscape = false)) }, label = "Portrait")
            SettingsChoiceChip(selected = options.landscape, onClick = { onOptions(options.copy(landscape = true)) }, label = "Landscape")
        }
        if (kind == LayoutKind.FLOW || kind == LayoutKind.NONE) {
            TextButton(onClick = { onOptions(options.stepScale(-1)) },
                enabled = options.scale > PdfLayoutOptions.MIN_SCALE) { Text("A−") }
            Text("Text ${Math.round(options.scale * 100)}%", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { onOptions(options.stepScale(1)) },
                enabled = options.scale < PdfLayoutOptions.MAX_SCALE) { Text("A+") }
        }
        if (kind != LayoutKind.PAGES) {
            PdfMargin.entries.forEach { margin ->
                SettingsChoiceChip(selected = options.margin == margin, onClick = { onOptions(options.copy(margin = margin)) },
                    label = "${margin.label} margins")
            }
        }
    }
}

/**
 * The PDF as it will be kept, page by page, before it becomes a note:
 * back to change the layout, or on to make the note.
 */
@Composable
internal fun PdfPreviewDialog(file: File, onAdjust: () -> Unit, onCreate: () -> Unit, onSave: (() -> Unit)? = null) {
    val pages = remember(file) { mutableStateListOf<Bitmap>() }
    var count by remember(file) { mutableStateOf<Int?>(null) }
    val widthPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp - 48.dp).toPx() }
        .toInt().coerceIn(320, 1600)
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        withContext(Dispatchers.Main) { count = renderer.pageCount }
                        for (i in 0 until renderer.pageCount) {
                            renderer.openPage(i).use { page ->
                                val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(AndroidColor.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                withContext(Dispatchers.Main) { pages += bitmap }
                            }
                        }
                    }
                }
            }.onFailure { withContext(Dispatchers.Main) { count = 0 } }
        }
    }
    Dialog(onDismissRequest = onAdjust, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onAdjust) { androidx.compose.material3.Icon(Reicons.ArrowBack, contentDescription = "Change the layout") }
                    Text(
                        when (val n = count) {
                            null -> "Preview"
                            0 -> "Couldn't show the preview"
                            else -> "Preview · ${countNoun(n, "page")}"
                        },
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAdjust) { Text("Adjust") }
                    if (onSave != null) TextButton(onClick = onSave, enabled = count != 0) { Text("Save PDF") }
                    Button(onClick = onCreate, enabled = count != 0) { Text("Create note") }
                }
                LazyColumn(
                    Modifier.fillMaxSize().background(Color(0xFFE8ECF1)),
                    contentPadding = PaddingValues(Ui.Space.l),
                    verticalArrangement = Arrangement.spacedBy(Ui.Space.l),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(pages) { bitmap ->
                        Box(Modifier.fillMaxWidth().background(Color.White)) {
                            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height))
                        }
                    }
                }
            }
        }
    }
}
