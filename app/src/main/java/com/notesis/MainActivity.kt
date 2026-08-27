package com.notesis

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
                    // Keyed, so jumping straight to another note builds a
                    // fresh screen instead of showing the old document until
                    // the new one finishes loading into state that was kept.
                    key(note.id) {
                    NoteScreen(
                        store = store,
                        note = note,
                        onOpenNote = { openNote = it },
                        onBack = { openNote = null },
                    )
                    }
                }
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)


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
    // Which note the image picker, once it comes back, belongs to.
    var thumbnailFor by remember { mutableStateOf<NoteMeta?>(null) }

    val pickThumbnail = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        val target = thumbnailFor ?: return@rememberLauncherForActivityResult
        thumbnailFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        store.setThumbnail(target.id, it)
                    }
                }
            }
            revision++
        }
    }

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
                        hasCustomThumbnail = note.thumbnail?.name == NoteStore.CUSTOM_THUMB,
                        onOpen = { onOpen(note) },
                        onDelete = { pendingDelete = note },
                        onPickThumbnail = {
                            thumbnailFor = note
                            pickThumbnail.launch("image/*")
                        },
                        onClearThumbnail = {
                            store.clearThumbnail(note.id)
                            revision++
                        },
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
private fun NoteCard(
    note: NoteMeta,
    hasCustomThumbnail: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onPickThumbnail: () -> Unit,
    onClearThumbnail: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Keyed on the file's timestamp, so replacing the picture redraws the card
    // instead of showing the decoded copy of the old one.
    val preview = remember(note.thumbnail?.path, note.thumbnail?.lastModified()) {
        note.thumbnail?.let { file ->
            runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull()
        }
    }
    Card(
        onClick = onOpen,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .background(Color(0xFFFDFCF8)),
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = null,
                        // Crop, so a page taller than the card fills it from the
                        // top rather than sitting in a letterbox.
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = Color(0x22000000),
                        modifier = Modifier.size(40.dp),
                    )
                }
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
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "더보기",
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("썸네일 설정") },
                            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onPickThumbnail()
                            },
                        )
                        if (hasCustomThumbnail) {
                            DropdownMenuItem(
                                text = { Text("첫 페이지로 되돌리기") },
                                onClick = {
                                    menuOpen = false
                                    onClearThumbnail()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("삭제") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Replaces one entry, leaving the rest of the list alone. */
private fun List<PenPreset>.replaceAt(index: Int, pen: PenPreset): List<PenPreset> =
    if (index !in indices) this else toMutableList().also { it[index] = pen }

/**
 * A pen in the tray. Round for a pen, rounded-square for a highlighter, so the
 * two are told apart by shape and not only by how see-through the colour is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PenChip(
    pen: PenPreset,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = if (pen.tool == Tool.HIGHLIGHTER) RoundedCornerShape(6.dp) else CircleShape
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .size(if (selected) 28.dp else 24.dp)
            .clip(shape)
            // White underneath, so a translucent pen shows how see-through it is.
            .background(Color.White)
            .background(Color(pen.colorArgb))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

/**
 * Edits one saved pen, or creates one when [pen] is null. Colour is picked in
 * HSV - which is how people actually describe a colour - with alpha on its own
 * strip, because a highlighter is exactly a pen whose alpha is not 255.
 */
@Composable
private fun PenDialog(
    pen: PenPreset?,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PenPreset) -> Unit,
    onDelete: () -> Unit,
) {
    val start = pen ?: PenPreset(Tool.PEN, 0xFF000000.toInt(), 5f)
    val hsv = remember(pen) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(start.colorArgb, it) }
    }
    var tool by remember(pen) { mutableStateOf(start.tool) }
    var hue by remember(pen) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(pen) { mutableFloatStateOf(hsv[1]) }
    var value by remember(pen) { mutableFloatStateOf(hsv[2]) }
    var alpha by remember(pen) {
        mutableFloatStateOf(android.graphics.Color.alpha(start.colorArgb) / 255f)
    }
    var width by remember(pen) { mutableFloatStateOf(start.width) }

    val picked = Color.hsv(hue, saturation, value, alpha)
    val argb = picked.toArgb()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pen == null) "펜 추가" else "펜 편집") },
        text = {
            Column {
                Row {
                    FilterChip(
                        selected = tool == Tool.PEN,
                        onClick = {
                            tool = Tool.PEN
                            // A pen at a highlighter's alpha reads as a mistake,
                            // so changing kind brings a sensible alpha with it.
                            if (alpha < 1f) alpha = 1f
                            if (width > 24f) width = 5f
                        },
                        label = { Text("펜") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = tool == Tool.HIGHLIGHTER,
                        onClick = {
                            tool = Tool.HIGHLIGHTER
                            if (alpha > 0.8f) alpha = 0.4f
                            if (width < 8f) width = 20f
                        },
                        label = { Text("형광펜") },
                    )
                }
                Spacer(Modifier.height(12.dp))

                SaturationValueField(hue, saturation, value) { s, v ->
                    saturation = s
                    value = v
                }
                Spacer(Modifier.height(10.dp))
                GradientStrip(
                    colors = (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) },
                    position = hue / 360f,
                ) { hue = it * 360f }
                Spacer(Modifier.height(10.dp))
                GradientStrip(
                    colors = listOf(
                        Color.hsv(hue, saturation, value, 0f),
                        Color.hsv(hue, saturation, value),
                    ),
                    position = alpha,
                ) { alpha = it }

                Spacer(Modifier.height(10.dp))
                Text(
                    "#%08X".format(argb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    "굵기 " + "%.1f".format(width),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = width,
                    onValueChange = { width = it },
                    valueRange = if (tool == Tool.HIGHLIGHTER) 4f..60f else 1f..24f,
                )
                // The pen as it will draw: real thickness, real transparency.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.9f)
                            .height(width.dp.coerceAtMost(36.dp))
                            .clip(CircleShape)
                            .background(picked),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(PenPreset(tool, argb, width)) }) {
                Text("저장")
            }
        },
        dismissButton = {
            Row {
                if (canDelete) {
                    TextButton(onClick = onDelete) { Text("삭제") }
                }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

/** Saturation across, brightness down, at the given [hue]. */
@Composable
private fun SaturationValueField(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTapGestures { emitSv(it, size.width, size.height, onChange) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    emitSv(change.position, size.width, size.height, onChange)
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawCircle(
            color = Color.White,
            radius = 7.dp.toPx(),
            center = Offset(saturation * size.width, (1f - value) * size.height),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private fun emitSv(at: Offset, width: Int, height: Int, onChange: (Float, Float) -> Unit) {
    if (width == 0 || height == 0) return
    onChange((at.x / width).coerceIn(0f, 1f), 1f - (at.y / height).coerceIn(0f, 1f))
}

/** A horizontal ramp with a handle: used for hue, and again for alpha. */
@Composable
private fun GradientStrip(
    colors: List<Color>,
    position: Float,
    onChange: (Float) -> Unit,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        // White underneath, so the clear end of the alpha ramp reads as clear
        // rather than as whatever happens to be behind the dialog.
        drawRect(Color.White)
        drawRect(Brush.horizontalGradient(colors))
        drawCircle(
            color = Color.White,
            radius = size.height / 2f - 3.dp.toPx(),
            center = Offset(position * size.width, size.height / 2f),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}


/** The shape tool: tapping it offers the four, and picking one arms the pen. */
@Composable
private fun ShapeButton(
    selected: Boolean,
    kind: ShapeKind,
    onShape: (ShapeKind) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(shapeIcon(kind), "도형", selected) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (option in ShapeKind.entries) {
                DropdownMenuItem(
                    text = { Text(shapeLabel(option)) },
                    leadingIcon = { Icon(shapeIcon(option), contentDescription = null) },
                    onClick = {
                        open = false
                        onShape(option)
                    },
                )
            }
        }
    }
}

private fun shapeIcon(kind: ShapeKind): ImageVector = when (kind) {
    ShapeKind.LINE -> Icons.Default.Remove
    ShapeKind.ARROW -> Icons.AutoMirrored.Filled.TrendingUp
    ShapeKind.RECT -> Icons.Default.CropSquare
    ShapeKind.OVAL -> Icons.Default.Circle
}

private fun shapeLabel(kind: ShapeKind): String = when (kind) {
    ShapeKind.LINE -> "직선"
    ShapeKind.ARROW -> "화살표"
    ShapeKind.RECT -> "사각형"
    ShapeKind.OVAL -> "원"
}

/** Opens one of the AI sites in the side panel. */
@Composable
private fun AiButton(onWeb: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.AutoAwesome, contentDescription = "AI")
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for ((name, url) in AI_SITES) {
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onWeb(url)
                    },
                )
            }
        }
    }
}

/**
 * Ready-made colour sets. Picking one recolours the pen in hand and leaves its
 * alpha alone, so a highlighter stays a highlighter and a pen stays opaque.
 */
@Composable
private fun PaletteDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("색상 템플릿") },
        text = {
            Column {
                for ((name, colors) in COLOR_TEMPLATES) {
                    Text(name, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        for (rgb in colors) {
                            Box(
                                Modifier
                                    .padding(end = 8.dp)
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(rgb))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable { onPick(rgb) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** What came back from a capture, and the two things worth doing with it. */
@Composable
private fun CaptureDialog(
    bitmap: Bitmap,
    onPaste: () -> Unit,
    onAttach: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("캡쳐") },
        text = {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            )
        },
        confirmButton = { TextButton(onClick = onPaste) { Text("이 페이지에 붙이기") } },
        dismissButton = {
            Row {
                TextButton(onClick = onAttach) { Text("AI에 첨부") }
                TextButton(onClick = onShare) { Text("공유") }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        },
    )
}

/**
 * A browser beside the note. It is a plain WebView: the point is looking things
 * up without leaving the page being written on, not building a browser.
 */
@Composable
private fun WebPanel(
    url: String,
    holder: MutableState<android.webkit.WebView?>,
    popup: Boolean,
    onTogglePopup: () -> Unit,
    onClose: () -> Unit,
    onFile: (android.webkit.ValueCallback<Array<Uri>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val web = holder.value
    var address by remember(url) { mutableStateOf(url) }

    Surface(modifier = modifier, tonalElevation = 2.dp) {
        // The whole panel keeps clear of the system bars, not just its header: a
        // chat composer pinned to the bottom of the page was sitting under the
        // navigation bar with nothing holding it up.
        Column(
            Modifier
                .fillMaxHeight()
                .windowInsetsPadding(ChromeInsets),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { web?.let { if (it.canGoBack()) it.goBack() } }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
                IconButton(onClick = { web?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    placeholder = { Text("주소 또는 검색어") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { web?.loadUrl(asUrl(address)) }),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                )
                IconButton(onClick = onTogglePopup) {
                    Icon(
                        if (popup) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                        contentDescription = if (popup) "붙이기" else "팝업",
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "닫기")
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    holder.value ?: newBrowser(viewContext, onFile).also { holder.value = it }
                },
                // The tag remembers which site was asked for, so picking another
                // one loads it while a stroke on the note next door does not.
                update = { view ->
                    if (view.tag != url) {
                        view.tag = url
                        view.loadUrl(url)
                    }
                },
            )
        }
    }
}

/**
 * Google refuses to sign in from anything whose user agent says WebView, which
 * is why Gemini came up against a "secure browser" wall. Dropping the "; wv"
 * token and taking third-party cookies is what the sign-in flow needs.
 */
private fun newBrowser(
    context: android.content.Context,
    onFile: (android.webkit.ValueCallback<Array<Uri>>) -> Unit,
): android.webkit.WebView =
    android.webkit.WebView(context).apply {
        // Without a client the framework hands links to the system browser,
        // which is the opposite of the point.
        webViewClient = android.webkit.WebViewClient()
        // And without a chrome client a page gets no upload button, no
        // window.open and no JS dialogs, which is most of a chat app.
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(
                view: android.webkit.WebView,
                callback: android.webkit.ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                onFile(callback)
                return true
            }
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

/** A typed address if it looks like one, a search if it does not. */
private fun asUrl(text: String): String {
    val trimmed = text.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    if (trimmed.contains(' ') || !trimmed.contains('.')) {
        return SEARCH_HOME + "search?q=" + Uri.encode(trimmed)
    }
    return "https://$trimmed"
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

/**
 * safeDrawing minus the keyboard. safeDrawing counts the IME, so every bar and
 * the canvas itself jumped whenever a text field took focus.
 */
private val ChromeInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** What the pen does when it lands. One thing at a time, by construction. */
private enum class EditMode { DRAW, ERASE, READ, SHAPE, IMAGE, CAPTURE }

@Composable
private fun NoteScreen(
    store: NoteStore,
    note: NoteMeta,
    onOpenNote: (NoteMeta) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val penStore = remember { PenStore(context) }
    var pens by remember { mutableStateOf(penStore.load()) }
    var penIndex by remember { mutableIntStateOf(0) }
    // One mode at a time: the eraser, reading and the rest are held over
    // whichever pen is selected, so putting one down gives that pen back
    // instead of dropping the user on a default.
    var mode by remember { mutableStateOf(EditMode.DRAW) }
    var shapeKind by remember { mutableStateOf(ShapeKind.LINE) }
    /** Index being edited, or -1 for a pen that does not exist yet. */
    var editingPen by remember { mutableStateOf<Int?>(null) }
    var eraserWidth by remember { mutableStateOf(24f) }
    val pen = pens.getOrElse(penIndex) { pens.first() }
    val tool = if (mode == EditMode.ERASE) Tool.ERASER else pen.tool

    // Dragging the width slider changes the pen on every frame; the tray is
    // written once the dragging stops rather than once per frame.
    LaunchedEffect(pens) {
        delay(PEN_SAVE_DELAY_MS)
        withContext(Dispatchers.IO) { penStore.save(pens) }
    }
    var fullscreen by remember { mutableStateOf(false) }
    var imageSelected by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<Bitmap?>(null) }
    /** The site the side panel is showing, or null while it is closed. */
    var webUrl by remember { mutableStateOf<String?>(null) }
    // One WebView for the whole note. Closing the panel used to destroy it, so
    // reopening paid the cold start and the login handshake all over again.
    val browser = remember { mutableStateOf<android.webkit.WebView?>(null) }
    var webWidth by remember { mutableStateOf(WEB_PANEL_WIDTH) }
    var webPopup by remember { mutableStateOf(false) }
    // A capture waiting to be handed to the next upload button a site shows.
    val pendingAttachment = remember { mutableStateOf<Uri?>(null) }
    val webChooser = remember { mutableStateOf<android.webkit.ValueCallback<Array<Uri>>?>(null) }
    DisposableEffect(Unit) {
        onDispose { browser.value?.destroy() }
    }
    // Every page redraw asks for the pictures on it, so decoding has to happen
    // once rather than once a frame.
    val imageCache = remember(note.id) {
        // Bounded by bytes, not by count: a handful of large pictures is what
        // would run the heap out, and counting entries cannot see that.
        object : LruCache<String, Bitmap>(IMAGE_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
    // Folded away, the bar becomes a handle that can be dragged; unfolding puts
    // it back wherever that handle was left, which is the point of moving it.
    var collapsed by remember { mutableStateOf(false) }
    // Null until it is dragged: the bar sits centred at the top by default, and
    // there is no sensible centre to store before anything has been measured.
    var barOffset by remember { mutableStateOf<Offset?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val otherNotes = remember(note.id) { store.list().filter { it.id != note.id } }
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
    var showPalette by remember { mutableStateOf(false) }

    // The page asked for a file and no capture was waiting, so the user picks
    // one. The callback must always be answered, or the page's upload button
    // stays dead until it is reloaded.
    val pickForWeb = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        webChooser.value?.onReceiveValue(if (uri == null) emptyArray() else arrayOf(uri))
        webChooser.value = null
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Decoding and copying a camera-sized photo is not main-thread work.
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        store.addImage(note.id, it)
                    }
                }.getOrNull()
            }
            if (added == null) {
                Toast.makeText(context, "이미지를 읽지 못했습니다", Toast.LENGTH_SHORT).show()
                return@launch
            }
            canvas?.insertImage(added.first, added.second)
            mode = EditMode.IMAGE
            edits++
        }
    }

    // Back clears a selection first, then leaves fullscreen, the way dismissing
    // anything else works - one step out per press.
    BackHandler {
        when {
            selectedText != null -> canvas?.clearSelection()
            fullscreen -> fullscreen = false
            else -> onBack()
        }
    }

    val window = (context as? ComponentActivity)?.window
    LaunchedEffect(fullscreen, window) {
        val view = window?.decorView ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // Leaving the note with the bars still hidden would hide them on the list.
    DisposableEffect(window) {
        onDispose {
            val view = window?.decorView ?: return@onDispose
            WindowCompat.getInsetsController(window, view)
                .show(WindowInsetsCompat.Type.systemBars())
        }
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

    // One panel, shown either docked beside the note or floating over it. The
    // WebView is the same instance in both, so switching keeps the page.
    val panel: @Composable (Modifier) -> Unit = { panelModifier ->
        WebPanel(
            url = webUrl.orEmpty(),
            holder = browser,
            popup = webPopup,
            onTogglePopup = { webPopup = !webPopup },
            onClose = {
                webUrl = null
                webPopup = false
            },
            onFile = { callback ->
                val ready = pendingAttachment.value
                if (ready != null) {
                    pendingAttachment.value = null
                    callback.onReceiveValue(arrayOf(ready))
                } else {
                    webChooser.value = callback
                    pickForWeb.launch("*/*")
                }
            },
            modifier = panelModifier,
        )
    }

    Row(Modifier.fillMaxSize()) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(Color(0xFFE9E7E2))
            .onSizeChanged { containerSize = it },
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
                        pageLoader = { page, epsilon ->
                            store.loadPage(note.id, page, epsilon)
                        }
                        open(ready.first, ready.second)
                        onStrokesChanged = {
                            edits++
                            pageCount = document.pages.size
                        }
                        onCurrentPageChanged = { currentPage = it }
                        onSelectionChanged = { selectedText = it?.text }
                        onImageSelected = { imageSelected = it }
                        imageLoader = { imageId ->
                            imageCache.get(imageId) ?: runCatching {
                                android.graphics.BitmapFactory
                                    .decodeFile(store.imageFile(note.id, imageId).path)
                            }.getOrNull()?.also { imageCache.put(imageId, it) }
                        }
                        // Rendered on a worker; the dialog is a UI thing.
                        onCaptured = { bitmap -> post { captured = bitmap } }
                        canvas = this
                    }
                },
                update = { view ->
                    view.tool = tool
                    view.readMode = mode == EditMode.READ
                    view.shapeKind = if (mode == EditMode.SHAPE) shapeKind else null
                    view.imageMode = mode == EditMode.IMAGE
                    view.captureMode = mode == EditMode.CAPTURE
                    view.colorArgb = pen.colorArgb
                    view.strokeWidth = pen.width
                    view.eraserWidth = eraserWidth
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
                    .windowInsetsPadding(ChromeInsets)
                    .padding(top = 84.dp, start = 24.dp),
            )
        }

        if (collapsed) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset { barPlacement(barOffset, barSize, containerSize) }
                    .onSizeChanged { barSize = it }
                    .windowInsetsPadding(ChromeInsets)
                    .padding(12.dp),
            ) {
                CollapsedToolbar(
                    onExpand = { collapsed = false },
                    onDrag = { delta ->
                        val at = barPlacement(barOffset, barSize, containerSize)
                        barOffset = Offset(at.x + delta.x, at.y + delta.y)
                    },
                )
            }
        } else {
        Toolbar(
            note = note,
            otherNotes = otherNotes,
            pens = pens,
            penIndex = penIndex,
            mode = mode,
            shapeKind = shapeKind,
            eraserWidth = eraserWidth,
            fullscreen = fullscreen,
            showLatency = showLatency,
            // edits is read here so drawing or erasing recomposes the toolbar and
            // undo/redo can re-evaluate whether there is anything on the stacks.
            canUndo = edits.let { canvas?.canUndo() == true },
            canRedo = edits.let { canvas?.canRedo() == true },
            onSelectPen = {
                penIndex = it
                if (mode == EditMode.ERASE || mode == EditMode.READ) mode = EditMode.DRAW
            },
            onEditPen = { editingPen = it },
            onAddPen = { editingPen = -1 },
            onMode = { picked ->
                // Leaving a mode takes its leftovers with it: a selection that
                // cannot be extended any more, a picture with handles on it.
                canvas?.clearSelection()
                canvas?.clearImageSelection()
                mode = if (mode == picked) EditMode.DRAW else picked
            },
            onShape = {
                shapeKind = it
                canvas?.clearSelection()
                canvas?.clearImageSelection()
                mode = EditMode.SHAPE
            },
            onPickImage = { pickImage.launch("image/*") },
            onWeb = { webUrl = it },
            onWidth = { pens = pens.replaceAt(penIndex, pen.copy(width = it)) },
            onEraserWidth = { eraserWidth = it },
            onUndo = {
                canvas?.undo()
                edits++
            },
            onRedo = {
                canvas?.redo()
                edits++
            },
            onFitWidth = { canvas?.fitWidth() },
            onToggleFullscreen = { fullscreen = !fullscreen },
            onToggleLatency = { showLatency = !showLatency },
            onTogglePages = { showPages = !showPages },
            onCollapse = { collapsed = true },
            onOpenNote = onOpenNote,
            onBack = onBack,
            pageLabel = "${currentPage + 1} / $pageCount",
            onPalette = { showPalette = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                // Placed and clamped together: the folded handle can be dragged
                // anywhere, and unfolding measures the wide bar and pulls it
                // back inside rather than letting half of it hang off screen.
                .offset { barPlacement(barOffset, barSize, containerSize) }
                .onSizeChanged { barSize = it }
                .windowInsetsPadding(ChromeInsets)
                .padding(12.dp),
        )
        }

        editingPen?.let { index ->
            val editing = pens.getOrNull(index)
            PenDialog(
                pen = editing,
                // The tray must never empty out: an empty tray leaves nothing
                // to draw with and no button to get a pen back.
                canDelete = editing != null && pens.size > 1,
                onDismiss = { editingPen = null },
                onConfirm = { saved ->
                    pens = if (editing == null) pens + saved else pens.replaceAt(index, saved)
                    penIndex = if (editing == null) pens.size - 1 else index
                    mode = EditMode.DRAW
                    editingPen = null
                },
                onDelete = {
                    pens = pens.filterIndexed { i, _ -> i != index }
                    penIndex = penIndex.coerceAtMost(pens.size - 1)
                    editingPen = null
                },
            )
        }

        if (showPalette) {
            PaletteDialog(
                onDismiss = { showPalette = false },
                onPick = { rgb ->
                    // Alpha belongs to the pen, not to the template: recolouring
                    // a highlighter must not turn it opaque.
                    val alpha = pen.colorArgb.toLong() and 0xFF000000L
                    pens = pens.replaceAt(
                        penIndex,
                        pen.copy(colorArgb = ((rgb.toLong() and 0xFFFFFFL) or alpha).toInt()),
                    )
                    showPalette = false
                },
            )
        }

        captured?.let { bitmap ->
            CaptureDialog(
                bitmap = bitmap,
                onPaste = {
                    val added = store.addImage(note.id, bitmap)
                    if (added != null) {
                        canvas?.insertImage(added.first, added.second)
                        mode = EditMode.IMAGE
                        edits++
                    }
                    captured = null
                },
                onAttach = {
                    val uri = captureUri(context, bitmap)
                    if (uri == null) {
                        Toast.makeText(context, "캡쳐를 저장하지 못했습니다", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingAttachment.value = uri
                        if (webUrl == null) webUrl = AI_SITES.first().second
                        Toast.makeText(context, "대화창의 첨부 버튼을 누르세요", Toast.LENGTH_LONG).show()
                    }
                    captured = null
                },
                onShare = {
                    shareBitmap(context, bitmap)
                    captured = null
                },
                onDismiss = { captured = null },
            )
        }

        if (imageSelected) {
            ImageActions(
                onDelete = {
                    canvas?.deleteSelectedImage()
                    edits++
                },
                onDone = { canvas?.clearImageSelection() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

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

        // Qualified: the enclosing Row puts RowScope.AnimatedVisibility in scope
        // too, and it wins the overload without a receiver to call it on.
        androidx.compose.animation.AnimatedVisibility(
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

        if (!webPopup) {
            webUrl?.let {
                // Drag the seam to give the browser more room, or less.
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(10.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .pointerInput(Unit) {
                            detectDragGestures { _, drag ->
                                webWidth = (webWidth - drag.x.toDp())
                                    .coerceIn(WEB_PANEL_MIN, WEB_PANEL_MAX)
                            }
                        },
                )
                panel(Modifier.fillMaxHeight().width(webWidth))
            }
        }
    }

    if (webPopup && webUrl != null) {
        Dialog(
            onDismissRequest = { webPopup = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            panel(Modifier.fillMaxSize(0.85f))
        }
    }
}

/** Delete or let go of the picture in hand. */
@Composable
private fun ImageActions(
    onDelete: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .windowInsetsPadding(ChromeInsets)
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("사진", style = MaterialTheme.typography.bodyMedium)
            ToolbarDivider()
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text(" 삭제")
            }
            TextButton(onClick = onDone) { Text("완료") }
        }
    }
}

/**
 * Where the toolbar actually sits: the drag offset, clamped so the thing being
 * placed stays entirely inside the canvas. A null offset means it has never
 * been moved, which is the middle of the top edge.
 */
private fun barPlacement(offset: Offset, bar: IntSize, container: IntSize): IntOffset {
    if (bar.width == 0 || container.width == 0) {
        return IntOffset(offset.x.toInt(), offset.y.toInt())
    }
    val maxX = (container.width - bar.width).toFloat().coerceAtLeast(0f)
    val maxY = (container.height - bar.height).toFloat().coerceAtLeast(0f)
    return IntOffset(offset.x.coerceIn(0f, maxX).toInt(), offset.y.coerceIn(0f, maxY).toInt())
}

private fun barPlacement(offset: Offset?, bar: IntSize, container: IntSize): IntOffset {
    val start = offset ?: Offset((container.width - bar.width) / 2f, 0f)
    return barPlacement(start, bar, container)
}

/** A capture written where another app is allowed to read it. */
private fun captureUri(context: android.content.Context, bitmap: Bitmap): Uri? {
    val shared = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(shared, "capture.png")
    val written = runCatching {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
    }.isSuccess
    if (!written) return null
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        context.packageName + ".files",
        file,
    )
}

/** Hands the captured region to whatever the user picks in the share sheet. */
private fun shareBitmap(context: android.content.Context, bitmap: Bitmap) {
    val uri = captureUri(context, bitmap) ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "캡쳐 공유"))
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
            .windowInsetsPadding(ChromeInsets)
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
            .windowInsetsPadding(ChromeInsets)
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
    otherNotes: List<NoteMeta>,
    pens: List<PenPreset>,
    penIndex: Int,
    mode: EditMode,
    shapeKind: ShapeKind,
    eraserWidth: Float,
    fullscreen: Boolean,
    showLatency: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    pageLabel: String,
    onSelectPen: (Int) -> Unit,
    onEditPen: (Int) -> Unit,
    onAddPen: () -> Unit,
    onMode: (EditMode) -> Unit,
    onShape: (ShapeKind) -> Unit,
    onPickImage: () -> Unit,
    onWeb: (String) -> Unit,
    onWidth: (Float) -> Unit,
    onEraserWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFitWidth: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleLatency: () -> Unit,
    onTogglePages: () -> Unit,
    onCollapse: () -> Unit,
    onOpenNote: (NoteMeta) -> Unit,
    onBack: () -> Unit,
    onPalette: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            // ---- top row: the note, and what is done to the whole of it
            Row(
                Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로가기")
                }
                IconButton(onClick = onTogglePages) {
                    Icon(Icons.Default.Search, contentDescription = "페이지 · 검색")
                }
                ToolButton(
                    Icons.Default.CropFree,
                    "영역 캡쳐",
                    mode == EditMode.CAPTURE,
                ) { onMode(EditMode.CAPTURE) }

                Text(
                    note.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    // Takes the slack, so the title sits in the middle and the
                    // two clusters stay pinned to their own ends.
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                )

                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "실행취소")
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "다시실행")
                }
                IconButton(onClick = onFitWidth) {
                    Icon(Icons.Default.ZoomOutMap, contentDescription = "화면에 맞추기")
                }
                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = "전체화면",
                    )
                }
                OtherNotesButton(otherNotes, onOpenNote)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ---- bottom row: what the pen is doing right now
            Row(
                Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "도구 숨기기")
                }
                ToolButton(
                    Icons.Default.TouchApp,
                    "읽기 모드",
                    mode == EditMode.READ,
                ) { onMode(EditMode.READ) }
                ToolbarDivider()

                for ((index, saved) in pens.withIndex()) {
                    PenChip(
                        pen = saved,
                        selected = mode == EditMode.DRAW && index == penIndex,
                        onClick = { onSelectPen(index) },
                        onLongClick = { onEditPen(index) },
                    )
                }
                IconButton(onClick = onAddPen) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "펜 추가",
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(onClick = onPalette) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = "색상 템플릿",
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
                ToolbarDivider()

                ToolButton(
                    Icons.Default.Delete,
                    "지우개",
                    mode == EditMode.ERASE,
                ) { onMode(EditMode.ERASE) }
                ShapeButton(mode == EditMode.SHAPE, shapeKind, onShape)
                ToolButton(
                    Icons.Default.AddPhotoAlternate,
                    "사진",
                    mode == EditMode.IMAGE,
                    onPickImage,
                )
                ToolbarDivider()

                // One slider, whichever tool is in hand: it sets the eraser's
                // size while the eraser is out and the selected pen's
                // otherwise, and the pen keeps that thickness. Two sliders
                // would mean one of them is always the wrong one to reach for.
                val pen = pens.getOrElse(penIndex) { pens.first() }
                val range = when {
                    mode == EditMode.ERASE -> 8f..96f
                    pen.tool == Tool.HIGHLIGHTER -> 4f..60f
                    else -> 1f..24f
                }
                val erasing = mode == EditMode.ERASE
                Slider(
                    value = (if (erasing) eraserWidth else pen.width).coerceIn(range),
                    onValueChange = if (erasing) onEraserWidth else onWidth,
                    valueRange = range,
                    modifier = Modifier.width(110.dp),
                )
                ToolbarDivider()

                IconButton(onClick = { onWeb(SEARCH_HOME) }) {
                    Icon(Icons.Default.Language, contentDescription = "인터넷")
                }
                AiButton(onWeb)
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
}

/** Jumps straight to another note without going back through the list. */
@Composable
private fun OtherNotesButton(notes: List<NoteMeta>, onOpenNote: (NoteMeta) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Menu, contentDescription = "다른 노트")
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (notes.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("다른 노트가 없습니다") },
                    enabled = false,
                    onClick = {},
                )
            }
            for (other in notes) {
                DropdownMenuItem(
                    text = { Text(other.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onOpenNote(other)
                    },
                )
            }
        }
    }
}

/**
 * What is left of the toolbar once it is folded away: a handle that can be
 * dragged anywhere and puts the bar back where it was dropped.
 */
@Composable
private fun CollapsedToolbar(onExpand: () -> Unit, onDrag: (Offset) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.pointerInput(Unit) {
            detectDragGestures { change, delta ->
                change.consume()
                onDrag(delta)
            }
        },
    ) {
        IconButton(onClick = onExpand) {
            Icon(Icons.Default.Menu, contentDescription = "도구 보이기")
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

private const val PEN_SAVE_DELAY_MS = 400L

private const val IMAGE_CACHE_BYTES = 48 * 1024 * 1024

private val WEB_PANEL_WIDTH = 460.dp
private val WEB_PANEL_MIN = 280.dp
private val WEB_PANEL_MAX = 1100.dp

private const val SEARCH_HOME = "https://www.google.com/"

private val AI_SITES = listOf(
    "Gemini" to "https://gemini.google.com/",
    "Claude" to "https://claude.ai/",
    "ChatGPT" to "https://chatgpt.com/",
    "Grok" to "https://grok.com/",
    "Perplexity" to "https://www.perplexity.ai/",
    "Cerebras" to "https://inference.cerebras.ai/",
)

private val COLOR_TEMPLATES = listOf(
    "기본" to listOf(0xFF000000, 0xFFD32F2F, 0xFF1976D2, 0xFF388E3C, 0xFFF9A825),
    "파스텔" to listOf(0xFF6D6875, 0xFFE5989B, 0xFF9AC1D9, 0xFFA8D5BA, 0xFFF6D186),
    "형광" to listOf(0xFFFFEB3B, 0xFF76FF03, 0xFF00E5FF, 0xFFFF4081, 0xFFFF9100),
    "먹" to listOf(0xFF000000, 0xFF3A3A3A, 0xFF6B6B6B, 0xFF9E9E9E, 0xFFCFCFCF),
).map { (name, colors) -> name to colors.map { it.toInt() } }
