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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
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
import java.io.ByteArrayOutputStream
import java.util.Locale

internal data class ViewerRequest(val uri: Uri, val name: String)

internal val SUPPORTED_DOCUMENT_MIME_TYPES = arrayOf(
    "text/plain",
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
    var name = queried?.takeIf(String::isNotBlank) ?: fallback.ifBlank { "문서" }
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
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var state: ViewerLoadState by remember(request.uri) { mutableStateOf(ViewerLoadState.Loading) }
    var sectionIndex by remember(request.uri) { mutableIntStateOf(0) }
    var textOnly by remember(request.uri) { mutableStateOf(false) }
    val hasLayout = supportsDocumentLayout(request.name)
    BackHandler(onBack = onBack)

    LaunchedEffect(request.uri, textOnly) {
        state = ViewerLoadState.Loading
        sectionIndex = 0
        state = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(request.uri)?.use(::readDocumentBytes)
                    ?: throw UnsupportedDocumentException("파일을 열 수 없습니다.")
                if (hasLayout && !textOnly) ViewerLoadState.Layout(bytes)
                else ViewerLoadState.Ready(OfficeDocumentParser.parse(request.name, bytes))
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                ViewerLoadState.Failed(
                    when (error) {
                        is UnsupportedDocumentException -> error.message ?: "지원하지 않는 문서입니다."
                        is SecurityException -> "파일 읽기 권한이 없습니다. 파일을 다시 선택해 주세요."
                        else -> "문서를 읽는 중 오류가 발생했습니다: ${error.message ?: error.javaClass.simpleName}"
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
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "문서 닫기")
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
                                if (hasLayout) {
                                    if (textOnly) "텍스트 보기 · 읽기 전용" else "문서 보기 · 읽기 전용"
                                } else document?.let { "${it.kind.label} · 읽기 전용" } ?: "읽기 전용",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (hasLayout) {
                            TextButton(onClick = { textOnly = !textOnly }) {
                                Text(if (textOnly) "문서 보기" else "텍스트 보기")
                            }
                        }
                        if (document != null && document.sections.size > 1) {
                            IconButton(
                                onClick = { sectionIndex = (sectionIndex - 1).coerceAtLeast(0) },
                                enabled = sectionIndex > 0,
                            ) { Icon(Icons.Default.ChevronLeft, contentDescription = "이전") }
                            Text("${sectionIndex + 1} / ${document.sections.size}")
                            IconButton(
                                onClick = { sectionIndex = (sectionIndex + 1).coerceAtMost(document.sections.lastIndex) },
                                enabled = sectionIndex < document.sections.lastIndex,
                            ) { Icon(Icons.Default.ChevronRight, contentDescription = "다음") }
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
            is ViewerLoadState.Layout -> DocumentLayoutView(
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
            Text("문서를 여는 중…")
        }
    }
}

@Composable
private fun FailedDocument(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(16.dp))
            Text("문서를 열 수 없습니다", style = MaterialTheme.typography.titleLarge)
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
    val shape = RoundedCornerShape(if (kind == PreviewKind.PRESENTATION) 18.dp else 10.dp)
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
            throw UnsupportedDocumentException("128MB보다 큰 문서는 현재 미리보기에서 열 수 없습니다.")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun isSupportedDocumentName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf(
        "ppt", "pptx", "doc", "docx", "xls", "xlsx", "md", "markdown", "txt", "hwp", "hwpx",
    )
