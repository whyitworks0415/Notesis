package com.notesis

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale

/** A document to show; [layOut] opens it laid out for a PDF straight away. */
internal data class ViewerRequest(val uri: Uri, val name: String, val layOut: Boolean = false)

internal val SUPPORTED_DOCUMENT_MIME_TYPES = arrayOf(
    "text/plain",
    "text/csv",
    "text/comma-separated-values",
    "text/markdown",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/x-hwp",
    "application/haansofthwp",
    "application/vnd.hancom.hwp",
    "application/vnd.hancom.hwpx",
    "application/zip",
    "application/octet-stream",
)

private const val MAX_SOURCE_BYTES = 128 * 1024 * 1024
private const val MAX_WORD_TABLE_ROWS_ON_SCREEN = 500

internal fun viewerRequest(context: Context, uri: Uri): ViewerRequest {
    val queried = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    val fallback = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    var name = queried?.takeIf(String::isNotBlank) ?: fallback.ifBlank { "Document" }
    if (!name.contains('.')) {
        val suffix = when (context.contentResolver.getType(uri)) {
            "text/plain" -> ".txt"
            "text/markdown" -> ".md"
            "application/msword" -> ".doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
            "application/vnd.ms-powerpoint" -> ".ppt"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx"
            "application/vnd.ms-excel" -> ".xls"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
            "application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp" -> ".hwp"
            "application/vnd.hancom.hwpx" -> ".hwpx"
            else -> ""
        }
        name += suffix
    }
    return ViewerRequest(uri, name)
}

/**
 * A Markdown note's text, as a file of its own in the cache, opened laid out
 * for a PDF. One file per title, written over each time.
 */
internal fun markdownPdfRequest(context: Context, title: String, text: String): ViewerRequest {
    val dir = java.io.File(context.cacheDir, "markdown-pdf").apply { mkdirs() }
    val name = title.ifBlank { "Note" }.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifBlank { "Note" }
    val file = java.io.File(dir, "$name.md")
    file.writeText(text)
    return ViewerRequest(Uri.fromFile(file), file.name, layOut = true)
}

private sealed interface ViewerLoadState {
    data object Loading : ViewerLoadState
    data class Ready(val document: PreviewDocument) : ViewerLoadState
    data class Layout(val bytes: ByteArray) : ViewerLoadState
    data class Failed(val message: String) : ViewerLoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadOnlyDocumentScreen(
    request: ViewerRequest,
    onBack: () -> Unit,
    /** Keeps the document's text as a note: its title and Markdown. Null hides the action. */
    onImport: ((String, String) -> Unit)? = null,
    /**
     * Keeps the document as a PDF note to write on: its title and the PDF,
     * set as A4 pages in a file the callee takes over. Null hides the action.
     */
    onImportPdf: ((String, java.io.File) -> Unit)? = null,
) {
    val importScope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var state: ViewerLoadState by remember(request.uri) { mutableStateOf(ViewerLoadState.Loading) }
    var sectionIndex by remember(request.uri) { mutableIntStateOf(0) }
    var textOnly by remember(request.uri) { mutableStateOf(false) }
    val hasLayout = supportsDocumentLayout(request.name)
    // Laying the document out for a PDF note: as the document view shows it,
    // with the paper, text size and page breaks set here, then a preview.
    var laying by remember(request.uri) { mutableStateOf(request.layOut && hasLayout) }
    var pdfOptions by remember { mutableStateOf(PdfLayoutOptions()) }
    var layoutView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var layoutKind by remember(request.uri) { mutableStateOf(LayoutKind.NONE) }
    var printing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<java.io.File?>(null) }
    // A Word file has no document view: its options are asked in a dialog.
    var askingOptions by remember { mutableStateOf(false) }
    val title = request.name.substringBeforeLast('.')
    // Opened to lay out straight away: leaving the layout leaves the document too.
    fun leaveLayout() { if (request.layOut) onBack() else laying = false }
    BackHandler(onBack = { if (laying) leaveLayout() else onBack() })
    LaunchedEffect(layoutView, laying) {
        val view = layoutView ?: return@LaunchedEffect
        if (!laying) return@LaunchedEffect
        layoutKind = LayoutKind.NONE
        while (layoutKind == LayoutKind.NONE) {
            layoutKind = layoutKindOf(view.evalJs("window.notesisLayout ? notesisLayout.kind() : 'none'"))
            if (layoutKind == LayoutKind.NONE) kotlinx.coroutines.delay(300)
        }
    }
    LaunchedEffect(layoutView, layoutKind, pdfOptions) {
        val view = layoutView ?: return@LaunchedEffect
        if (laying && layoutKind != LayoutKind.NONE) view.evalJs("notesisLayout.set(${pdfOptions.toJson()})")
    }
    fun previewLayout() {
        val view = layoutView ?: return
        importScope.launch {
            printing = true
            val result = printLayoutToPdf(view, title, context.cacheDir)
            printing = false
            when (result) {
                is LayoutPrint.Written -> preview = result.file
                LayoutPrint.HandedToSystem -> android.widget.Toast.makeText(context,
                    "Save it as a PDF from the print screen, then open that PDF in Notesis", android.widget.Toast.LENGTH_LONG).show()
                LayoutPrint.Failed -> android.widget.Toast.makeText(context, "Couldn't make the PDF", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    // The previewed PDF kept as a file wherever the person picks.
    var saving by remember { mutableStateOf<java.io.File?>(null) }
    val savePdf = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf"),
    ) { target ->
        val file = saving ?: return@rememberLauncherForActivityResult
        saving = null
        if (target == null) return@rememberLauncherForActivityResult
        importScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(target, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: error("Can't write the file")
                }.isSuccess
            }
            android.widget.Toast.makeText(context, if (saved) "PDF saved" else "Couldn't save the PDF",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    /** The pages as the document gives them: every break and pull made here undone. */
    fun resetPages() {
        val view = layoutView ?: return
        importScope.launch { view.evalJs("window.notesisLayout && notesisLayout.reset && notesisLayout.reset()") }
    }
    fun previewText(options: PdfLayoutOptions) {
        importScope.launch {
            val pdf = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(request.uri)?.use(::readDocumentBytes)
                        ?: error("Can't open the file.")
                    val parsed = OfficeDocumentParser.parse(request.name, bytes)
                    java.io.File.createTempFile("converted", ".pdf", context.cacheDir).also { file ->
                        file.outputStream().use { writeDocumentPdf(parsed, it, options) }
                    }
                }.getOrNull()
            }
            if (pdf != null) preview = pdf
            else android.widget.Toast.makeText(context, "Couldn't convert to PDF", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    preview?.let { file ->
        PdfPreviewDialog(
            file = file,
            onAdjust = { preview = null; file.delete() },
            onSave = {
                saving = file
                savePdf.launch("$title.pdf")
            },
            onCreate = {
                preview = null
                laying = false
                onImportPdf?.invoke(title, file)
            },
        )
    }
    if (askingOptions) {
        var options by remember { mutableStateOf(pdfOptions) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askingOptions = false },
            title = { Text("PDF layout") },
            text = { PdfOptionControls(LayoutKind.FLOW, options) { options = it } },
            confirmButton = {
                TextButton(onClick = { askingOptions = false; pdfOptions = options; previewText(options) }) { Text("Preview") }
            },
            dismissButton = { TextButton(onClick = { askingOptions = false }) { Text("Cancel") } },
        )
    }

    LaunchedEffect(request.uri, textOnly) {
        state = ViewerLoadState.Loading
        sectionIndex = 0
        state = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(request.uri)?.use(::readDocumentBytes)
                    ?: throw UnsupportedDocumentException("Can't open the file.")
                if (hasLayout && !textOnly) ViewerLoadState.Layout(bytes)
                else ViewerLoadState.Ready(OfficeDocumentParser.parse(request.name, bytes))
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                ViewerLoadState.Failed(
                    when (error) {
                        is UnsupportedDocumentException -> error.message ?: "This document type isn't supported."
                        is SecurityException -> "No permission to read the file. Please pick it again."
                        else -> "Error reading the document: ${error.message ?: error.javaClass.simpleName}"
                    },
                )
            }
        }
    }

    val ready = state as? ViewerLoadState.Ready
    val document = ready?.document
    Scaffold(
        topBar = {
            SkinSurface(flush = true) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { if (laying) leaveLayout() else onBack() }) {
                            Icon(Reicons.ArrowBack, contentDescription = if (laying) "Back to the document" else "Close document")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                request.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (laying) "PDF layout"
                                else if (hasLayout) {
                                    if (textOnly) "Text view · Read-only" else "Document view · Read-only"
                                } else document?.let { "${it.kind.label} · Read-only" } ?: "Read-only",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (laying) {
                            TextButton(onClick = ::leaveLayout) { Text("Cancel") }
                            return@TopAppBar
                        }
                        if (onImport != null) TextButton(onClick = {
                            importScope.launch {
                                val markdown = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val bytes = context.contentResolver.openInputStream(request.uri)?.use(::readDocumentBytes)
                                            ?: error("Can't open the file.")
                                        OfficeDocumentParser.parse(request.name, bytes).toMarkdown()
                                    }.getOrNull()
                                }
                                if (markdown != null) onImport(request.name.substringBeforeLast('.'), markdown)
                                else android.widget.Toast.makeText(context, "Import failed", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Import as note") }
                        if (onImportPdf != null) TextButton(onClick = {
                            // Laid out as the document view shows it, where there is one.
                            if (hasLayout) {
                                textOnly = false
                                laying = true
                            } else askingOptions = true
                        }) { Text("Convert to PDF") }
                        if (hasLayout) {
                            TextButton(onClick = { textOnly = !textOnly }) {
                                Text(if (textOnly) "Document view" else "Text view")
                            }
                        }
                        if (document != null && document.sections.size > 1) {
                            IconButton(
                                onClick = { sectionIndex = (sectionIndex - 1).coerceAtLeast(0) },
                                enabled = sectionIndex > 0,
                            ) { Icon(Reicons.ChevronLeft, contentDescription = "Previous") }
                            Text("${sectionIndex + 1} / ${document.sections.size}")
                            IconButton(
                                onClick = { sectionIndex = (sectionIndex + 1).coerceAtMost(document.sections.lastIndex) },
                                enabled = sectionIndex < document.sections.lastIndex,
                            ) { Icon(Reicons.ChevronRight, contentDescription = "Next") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        when (val current = state) {
            ViewerLoadState.Loading -> LoadingDocument(Modifier.padding(padding))
            is ViewerLoadState.Failed -> FailedDocument(current.message, Modifier.padding(padding))
            is ViewerLoadState.Layout -> if (laying) {
                Column(Modifier.padding(padding).fillMaxSize()) {
                    DocumentLayoutView(
                        request.name, current.bytes, Modifier.weight(1f).fillMaxWidth(),
                        arrange = true, onWebView = { layoutView = it },
                    )
                    PdfLayoutBar(layoutKind, pdfOptions, { pdfOptions = it }, busy = printing, onPreview = ::previewLayout,
                        onReset = if (isMarkdownName(request.name)) ::resetPages else null,
                        modifier = Modifier.fillMaxWidth())
                }
            } else DocumentLayoutView(
                request.name, current.bytes, Modifier.padding(padding).fillMaxSize(),
            )
            is ViewerLoadState.Ready -> {
                val safeIndex = sectionIndex.coerceIn(0, current.document.sections.lastIndex)
                val section = current.document.sections[safeIndex]
                DocumentSection(
                    document = current.document,
                    section = section,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun LoadingDocument(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Opening document…")
        }
    }
}

@Composable
private fun FailedDocument(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Reicons.Description,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(16.dp))
            Text("Can't open the document", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DocumentSection(
    document: PreviewDocument,
    section: PreviewSection,
    modifier: Modifier = Modifier,
) {
    val onlyTable = section.blocks.singleOrNull() as? PreviewBlock.Table
    if (document.kind == PreviewKind.WORKBOOK && onlyTable != null) {
        SpreadsheetSection(document, section, onlyTable.rows, modifier)
        return
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.m),
    ) {
        item {
            SectionHeader(section.title, document.notice)
        }
        itemsIndexed(section.blocks) { _, block ->
            when (block) {
                is PreviewBlock.Paragraph -> ParagraphBlock(block, document.kind)
                is PreviewBlock.Table -> WordTable(block.rows.take(MAX_WORD_TABLE_ROWS_ON_SCREEN))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, notice: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (notice != null) {
            Spacer(Modifier.height(4.dp))
            Text(notice, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ParagraphBlock(block: PreviewBlock.Paragraph, kind: PreviewKind) {
    val headingSize = when (block.level) {
        1 -> 26.sp
        2 -> 22.sp
        3 -> 19.sp
        in 4..6 -> 17.sp
        else -> 16.sp
    }
    val shape = if (kind == PreviewKind.PRESENTATION) Ui.Shape.card else Ui.Shape.chip
    val modifier = if (kind == PreviewKind.PRESENTATION || block.monospace) {
        Modifier.fillMaxWidth()
    } else Modifier.fillMaxWidth()
    Surface(
        modifier = modifier,
        shape = shape,
        color = when {
            block.monospace -> MaterialTheme.colorScheme.surfaceContainerHighest
            kind == PreviewKind.PRESENTATION -> MaterialTheme.colorScheme.surface
            else -> Color.Transparent
        },
        tonalElevation = if (kind == PreviewKind.PRESENTATION) 1.dp else 0.dp,
    ) {
        Text(
            text = block.text,
            modifier = Modifier.padding(if (kind == PreviewKind.PRESENTATION || block.monospace) 16.dp else 0.dp),
            fontSize = headingSize,
            lineHeight = (headingSize.value * 1.45f).sp,
            fontWeight = if (block.level > 0) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = if (block.monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun WordTable(rows: List<List<String>>) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row {
                row.forEach { value -> TableCell(value, header = rowIndex == 0) }
            }
            if (rowIndex < rows.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun SpreadsheetSection(
    document: PreviewDocument,
    section: PreviewSection,
    rows: List<List<String>>,
    modifier: Modifier,
) {
    val horizontal = rememberScrollState()
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp, 16.dp, 20.dp, 10.dp)) {
            SectionHeader(section.title, document.notice)
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            stickyHeader {
                Row(
                    Modifier
                        .horizontalScroll(horizontal)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    RowNumberCell("")
                    repeat(rows.maxOfOrNull { it.size } ?: 0) { column ->
                        TableCell(columnLabel(column), header = true)
                    }
                }
            }
            itemsIndexed(rows) { index, row ->
                Row(Modifier.horizontalScroll(horizontal)) {
                    RowNumberCell((index + 1).toString())
                    row.forEach { value -> TableCell(value, header = false) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun RowNumberCell(value: String) {
    Box(
        Modifier
            .width(54.dp)
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(8.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TableCell(value: String, header: Boolean) {
    Box(
        Modifier
            .width(156.dp)
            .height(48.dp)
            .background(if (header) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            value,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun columnLabel(index: Int): String {
    var number = index + 1
    val result = StringBuilder()
    while (number > 0) {
        number--
        result.append(('A'.code + number % 26).toChar())
        number /= 26
    }
    return result.reverse().toString()
}

private fun readDocumentBytes(input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (output.size() + read > MAX_SOURCE_BYTES) {
            throw UnsupportedDocumentException("Documents larger than 128 MB can't be previewed yet.")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun isSupportedDocumentName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf(
        "ppt", "pptx", "doc", "docx", "xls", "xlsx", "md", "markdown", "txt", "hwp", "hwpx",
        "dot", "dotx", "pps", "ppsx", "pot", "potx", "xlt", "xltx", "csv",
    )
