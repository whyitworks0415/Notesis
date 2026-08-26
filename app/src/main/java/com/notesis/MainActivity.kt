package com.notesis

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ draws behind the system bars whether or not you ask, so
        // opt in properly and let the insets be dispatched instead of guessed.
        enableEdgeToEdge()
        val store = NoteStore(this)
        setContent {
            MaterialTheme {
                var openNote by remember { mutableStateOf<NoteMeta?>(null) }
                val note = openNote
                if (note == null) {
                    NoteListScreen(store) { openNote = it }
                } else {
                    NoteScreen(store, note) { openNote = null }
                }
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

private val palette = listOf(
    Color(0xFF1A1A1A),
    Color(0xFFD32F2F),
    Color(0xFF1976D2),
    Color(0xFF388E3C),
    Color(0xFFF9A825),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteListScreen(store: NoteStore, onOpen: (NoteMeta) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Re-read from disk whenever something changed it, rather than keeping a
    // second copy of the truth in memory and having to hold the two in sync.
    var revision by remember { mutableIntStateOf(0) }
    val notes = remember(revision) { store.list() }
    var pendingDelete by remember { mutableStateOf<NoteMeta?>(null) }
    var naming by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importFailed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NoteMeta>?>(null) }

    // Searching reads every note's text index off disk, so it runs off the main
    // thread and only after typing settles.
    LaunchedEffect(query, revision) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        results = withContext(Dispatchers.IO) { store.search(query) }
    }
    val shown = results ?: notes

    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            // Copying and parsing a large PDF is far too slow for the main
            // thread, and the picker gives no size guarantee.
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        store.createFromPdf(displayName(context, uri), input)
                    }
                }.getOrNull()
            }
            importing = false
            revision++
            if (imported == null) importFailed = true else onOpen(imported)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("노트 제목 · PDF 본문 검색") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                    )
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(
                    onClick = { pickPdf.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Icon(Icons.Default.Description, contentDescription = "PDF 가져오기")
                }
                FloatingActionButton(onClick = { naming = true }) {
                    Icon(Icons.Default.Add, contentDescription = "새 노트")
                }
            }
        },
    ) { padding ->
        if (shown.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (query.isBlank()) "아직 노트가 없습니다" else "\"$query\" 검색 결과가 없습니다",
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 168.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(shown, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onOpen = { onOpen(note) },
                        onDelete = { pendingDelete = note },
                    )
                }
            }
        }
    }

    if (importing) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000)),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    }

    if (importFailed) {
        AlertDialog(
            onDismissRequest = { importFailed = false },
            title = { Text("PDF를 열 수 없습니다") },
            text = { Text("손상되었거나 암호가 걸린 파일일 수 있습니다.") },
            confirmButton = { TextButton(onClick = { importFailed = false }) { Text("확인") } },
        )
    }

    if (naming) {
        NameDialog(
            onDismiss = { naming = false },
            onConfirm = { title ->
                naming = false
                onOpen(store.create(title.ifBlank { "제목 없음" }))
            },
        )
    }

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("노트를 삭제할까요?") },
            text = { Text("\"${note.title}\" 은(는) 복구할 수 없습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.delete(note.id)
                        pendingDelete = null
                        revision++
                    },
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
        )
    }
}

/** The picked file's own name, so an imported PDF is not called "제목 없음". */
private fun displayName(context: android.content.Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return (name ?: "가져온 PDF").removeSuffix(".pdf")
}

@Composable
private fun NoteCard(note: NoteMeta, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onOpen,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            // Stands in for a real thumbnail. Rendering one means drawing a page
            // offscreen, which is its own pass and not what this change is about.
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .background(Color(0xFFFDFCF8)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = Color(0x22000000),
                    modifier = Modifier.size(40.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        note.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${dateFormat.format(Date(note.modified))} · ${note.pageCount}쪽",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "삭제",
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun NameDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 노트") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("이름") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(title) }) { Text("만들기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun NoteScreen(store: NoteStore, note: NoteMeta, onBack: () -> Unit) {
    val context = LocalContext.current
    var tool by remember { mutableStateOf(Tool.PEN) }
    var color by remember { mutableStateOf(palette.first()) }
    var width by remember { mutableStateOf(5f) }
    var showLatency by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var edits by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(note.pageCount) }
    var currentPage by remember { mutableIntStateOf(0) }
    var canvas by remember { mutableStateOf<InkCanvasView?>(null) }
    var latencyText by remember { mutableStateOf("") }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<Pair<Document, PdfSource?>?>(null) }
    val clipboard = LocalClipboardManager.current

    // Back clears a selection first, the way dismissing anything else works.
    BackHandler {
        if (selectedText != null) canvas?.clearSelection() else onBack()
    }

    // Opening a note decodes every stroke it holds and parses the PDF header.
    // On the main thread that is a visible freeze, and it grows with the note.
    LaunchedEffect(note.id) {
        opened = withContext(Dispatchers.IO) {
            store.load(note.id) to PdfSource.open(
                store.pdfFile(note.id),
                PdfSource.cacheBytesFor(context),
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFE9E7E2)),
    ) {
        val ready = opened
        if (ready == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    InkCanvasView(viewContext).apply {
                        open(ready.first, ready.second)
                        onStrokesChanged = {
                            edits++
                            pageCount = document.pages.size
                        }
                        onCurrentPageChanged = { currentPage = it }
                        onSelectionChanged = { selectedText = it?.text }
                        canvas = this
                    }
                },
                update = { view ->
                    view.tool = tool
                    view.colorArgb = color.toArgb()
                    view.strokeWidth = width
                },
            )
        }

        // Autosave: each change restarts a short timer, so a burst of strokes
        // writes the note once instead of once per stroke.
        LaunchedEffect(edits) {
            if (edits == 0) return@LaunchedEffect
            delay(AUTOSAVE_DELAY_MS)
            canvas?.let { view ->
                // Only pages marked dirty are actually written; see NoteStore.
                withContext(Dispatchers.IO) { store.save(note.id, note.title, view.document) }
            }
        }

        // Anything still unsaved when the screen goes away gets written now. If
        // the note was closed before it finished opening, the canvas never took
        // ownership of the PdfSource, so close it here instead of leaking it.
        DisposableEffect(Unit) {
            onDispose {
                val view = canvas
                if (view != null) {
                    store.save(note.id, note.title, view.document)
                } else {
                    opened?.second?.close()
                }
            }
        }

        if (showLatency) {
            LaunchedEffect(Unit) {
                while (true) {
                    latencyText = canvas?.let { it.latency.render(it.strokeCount()) }.orEmpty()
                    delay(500)
                }
            }
            Text(
                latencyText,
                fontSize = 12.sp,
                color = Color(0xFF555555),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 84.dp, start = 24.dp),
            )
        }

        Toolbar(
            note = note,
            tool = tool,
            color = color,
            width = width,
            showLatency = showLatency,
            // edits is read here so drawing or erasing recomposes the toolbar and
            // undo/redo can re-evaluate whether there is anything on the stacks.
            canUndo = edits.let { canvas?.canUndo() == true },
            canRedo = edits.let { canvas?.canRedo() == true },
            onTool = { tool = it },
            onColor = { color = it },
            onWidth = { width = it },
            onUndo = {
                canvas?.undo()
                edits++
            },
            onRedo = {
                canvas?.redo()
                edits++
            },
            onFitWidth = { canvas?.fitWidth() },
            onToggleLatency = { showLatency = !showLatency },
            onTogglePages = { showPages = !showPages },
            onBack = onBack,
            pageLabel = "${currentPage + 1} / $pageCount",
            modifier = Modifier.align(Alignment.TopCenter),
        )

        selectedText?.let { text ->
            SelectionActions(
                text = text,
                onCopy = {
                    clipboard.setText(AnnotatedString(text))
                    canvas?.clearSelection()
                },
                onHighlight = {
                    canvas?.highlightSelection()
                    edits++
                },
                onDismiss = { canvas?.clearSelection() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        AnimatedVisibility(
            visible = showPages,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            PageSidebar(
                document = canvas?.document,
                currentPage = currentPage,
                onJump = { canvas?.scrollToPage(it) },
                onAdd = {
                    canvas?.addPage(currentPage)
                    edits++
                },
                onDelete = {
                    canvas?.deletePage(it)
                    edits++
                },
                onBackground = { index, background ->
                    canvas?.setBackground(index, background)
                    edits++
                },
            )
        }
    }
}

/** What you can do with text lifted off a PDF page. */
@Composable
private fun SelectionActions(
    text: String,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Selected PDF text arrives with its line breaks; the chip is
                // one line.
                text.lineSequence().joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(220.dp),
            )
            ToolbarDivider()
            TextButton(onClick = onHighlight) {
                Icon(Icons.Outlined.Brush, contentDescription = null)
                Text(" 형광펜")
            }
            TextButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text(" 복사")
            }
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    }
}

@Composable
private fun PageSidebar(
    document: Document?,
    currentPage: Int,
    onJump: (Int) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Int) -> Unit,
    onBackground: (Int, PageBackground) -> Unit,
) {
    val pages = document?.pages ?: return
    Surface(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            .width(132.dp)
            .fillMaxHeight(0.8f),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pages) { page ->
                    val index = pages.indexOf(page)
                    PageChip(
                        index = index,
                        page = page,
                        selected = index == currentPage,
                        deletable = pages.size > 1,
                        onJump = { onJump(index) },
                        onDelete = { onDelete(index) },
                        onBackground = { onBackground(index, it) },
                    )
                }
            }
            TextButton(
                onClick = onAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" 페이지")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageChip(
    index: Int,
    page: Page,
    selected: Boolean,
    deletable: Boolean,
    onJump: () -> Unit,
    onDelete: () -> Unit,
    onBackground: (PageBackground) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.White,
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(8.dp),
                )
                .combinedClickable(
                    onClick = onJump,
                    // Long press for the page menu. It used to be an invisible
                    // hotspot in the corner, which nobody would ever find.
                    onLongClick = { menu = true },
                )
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("${index + 1}", style = MaterialTheme.typography.titleMedium)
            Text(
                when (page.background) {
                    PageBackground.BLANK -> "무지"
                    PageBackground.LINED -> "줄"
                    PageBackground.GRID -> "모눈"
                    PageBackground.PDF -> "PDF"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (page.background != PageBackground.PDF) {
                DropdownMenuItem(
                    text = { Text("무지") },
                    onClick = { onBackground(PageBackground.BLANK); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("줄") },
                    onClick = { onBackground(PageBackground.LINED); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("모눈") },
                    onClick = { onBackground(PageBackground.GRID); menu = false },
                )
            }
            DropdownMenuItem(
                text = { Text("페이지 삭제") },
                enabled = deletable,
                onClick = { onDelete(); menu = false },
            )
        }
    }
}

@Composable
private fun Toolbar(
    note: NoteMeta,
    tool: Tool,
    color: Color,
    width: Float,
    showLatency: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    pageLabel: String,
    onTool: (Tool) -> Unit,
    onColor: (Color) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFitWidth: () -> Unit,
    onToggleLatency: () -> Unit,
    onTogglePages: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "노트 목록")
            }
            Text(
                note.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(96.dp),
            )
            ToolbarDivider()

            ToolButton(Icons.Outlined.Edit, "펜", tool == Tool.PEN) { onTool(Tool.PEN) }
            ToolButton(Icons.Outlined.Brush, "형광펜", tool == Tool.HIGHLIGHTER) {
                onTool(Tool.HIGHLIGHTER)
            }
            ToolButton(Icons.Default.Delete, "지우개", tool == Tool.ERASER) { onTool(Tool.ERASER) }
            ToolbarDivider()

            for (swatch in palette) {
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (swatch == color) 26.dp else 22.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(
                            width = if (swatch == color) 2.dp else 0.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            shape = CircleShape,
                        )
                        .clickable { onColor(swatch) },
                )
            }
            ToolbarDivider()

            Slider(
                value = width,
                onValueChange = onWidth,
                valueRange = 1f..24f,
                modifier = Modifier.width(96.dp),
            )
            ToolbarDivider()

            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "실행취소")
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "다시실행")
            }
            IconButton(onClick = onFitWidth) {
                Icon(Icons.Default.ZoomOutMap, contentDescription = "화면에 맞추기")
            }
            ToolbarDivider()

            TextButton(onClick = onTogglePages) { Text(pageLabel) }
            IconButton(onClick = onToggleLatency) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = "지연 측정",
                    tint = if (showLatency) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledIconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    } else {
        IconButton(
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.outline,
            ),
        ) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun ToolbarDivider() {
    Spacer(
        Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

private const val AUTOSAVE_DELAY_MS = 1200L
private const val SEARCH_DEBOUNCE_MS = 220L
