package com.notesis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    // Re-read from disk whenever something changed it, rather than keeping a
    // second copy of the truth in memory and having to hold the two in sync.
    var revision by remember { mutableStateOf(0) }
    val notes = remember(revision) { store.list() }
    var pendingDelete by remember { mutableStateOf<NoteMeta?>(null) }
    var naming by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Notesis") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { naming = true }) {
                Icon(Icons.Default.Add, contentDescription = "새 노트")
            }
        },
    ) { padding ->
        if (notes.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("아직 노트가 없습니다", color = MaterialTheme.colorScheme.outline)
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
                items(notes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onOpen = { onOpen(note) },
                        onDelete = { pendingDelete = note },
                    )
                }
            }
        }
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

@Composable
private fun NoteCard(note: NoteMeta, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onOpen,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            // Stands in for a real thumbnail. Rendering one means drawing the
            // page offscreen, which is not worth it until pages exist.
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
                        "${dateFormat.format(Date(note.modified))} · ${note.strokeCount}획",
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
    var tool by remember { mutableStateOf(Tool.PEN) }
    var color by remember { mutableStateOf(palette.first()) }
    var width by remember { mutableStateOf(5f) }
    var showLatency by remember { mutableStateOf(false) }
    var edits by remember { mutableStateOf(0) }
    var canvas by remember { mutableStateOf<InkCanvasView?>(null) }
    var latencyText by remember { mutableStateOf("") }

    BackHandler { onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFFDFCF8)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                InkCanvasView(context).apply {
                    setStrokes(store.load(note.id))
                    onStrokesChanged = { edits++ }
                    canvas = this
                }
            },
            update = { view ->
                view.tool = tool
                view.colorArgb = color.toArgb()
                view.strokeWidth = width
            },
        )

        // Autosave: each change restarts a short timer, so a burst of strokes
        // writes the page once instead of once per stroke.
        LaunchedEffect(edits) {
            if (edits == 0) return@LaunchedEffect
            delay(AUTOSAVE_DELAY_MS)
            canvas?.let { store.save(note.id, it.strokes()) }
        }

        // Anything still unsaved when the screen goes away gets written now.
        DisposableEffect(Unit) {
            onDispose { canvas?.let { store.save(note.id, it.strokes()) } }
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
                    .padding(top = 84.dp, start = 24.dp),
            )
        }

        Toolbar(
            note = note,
            tool = tool,
            color = color,
            width = width,
            showLatency = showLatency,
            // edits is read so that drawing or erasing recomposes the toolbar and
            // the undo button can re-evaluate whether there is anything to undo.
            canUndo = edits.let { canvas?.canUndo() == true },
            onTool = { tool = it },
            onColor = { color = it },
            onWidth = { width = it },
            onUndo = {
                canvas?.undo()
                edits++
            },
            onResetZoom = { canvas?.resetZoom() },
            onToggleLatency = { showLatency = !showLatency },
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )
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
    onTool: (Tool) -> Unit,
    onColor: (Color) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onResetZoom: () -> Unit,
    onToggleLatency: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(12.dp),
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
                modifier = Modifier.width(110.dp),
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
                modifier = Modifier.width(110.dp),
            )
            ToolbarDivider()

            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "실행취소")
            }
            IconButton(onClick = onResetZoom) {
                Icon(Icons.Default.ZoomOutMap, contentDescription = "확대 초기화")
            }
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
