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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
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
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordCrashes()
        // Android 15+ draws behind the system bars whether or not you ask, so
        // opt in properly and let the insets be dispatched instead of guessed.
        enableEdgeToEdge()
        // After enableEdgeToEdge, which installs its own bar style over
        // anything set before it. The app is light and draws behind the status
        // bar, so the system needs telling to use dark icons - left alone it
        // picked white ones and the clock and battery vanished into the bar.
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        val store = NoteStore(this)
        val prefs = PenStore(this)
        val lookStore = SkinSettingsStore(this)
        setContent {
            // Hoisted to the top so a change repaints every bar at once rather
            // than whichever screen happened to be looking.
            var skin by remember { mutableStateOf(prefs.skin) }
            var look by remember { mutableStateOf(lookStore.load()) }
            var settingsOpen by remember { mutableStateOf(false) }
            // The whole scheme is built from the accent rather than painted over
            // Material's baseline, so the purple that used to survive in every
            // container, outline and tint goes when the accent does. Kept until
            // the accent changes: forty tones is forty bisections, which is
            // nothing once and not nothing on every recomposition.
            val scheme = remember(look.accent, look.highContrast) {
                schemeFrom(look.accent, look.highContrast)
            }
            // The skin reaches Material's own components through the theme, so
            // dialogs, menus and cards follow it without a single call site
            // knowing a skin exists.
            MaterialTheme(
                colorScheme = skinColors(scheme, skin, look),
                shapes = skinShapes(skin),
            ) {
            ProvideSkin(skin, look) {
                var openNote by remember { mutableStateOf<NoteMeta?>(null) }
                val note = openNote
                if (settingsOpen) {
                    SkinSettingsScreen(
                        skin = skin,
                        settings = look,
                        onSkin = {
                            skin = it
                            prefs.skin = it
                        },
                        onChange = {
                            look = it
                            lookStore.save(it)
                        },
                        onBack = { settingsOpen = false },
                    )
                } else if (note == null) {
                    NoteListScreen(store, onSettings = { settingsOpen = true }) { openNote = it }
                } else {
                    // Keyed, so jumping straight to another note builds a
                    // fresh screen instead of showing the old document until
                    // the new one finishes loading into state that was kept.
                    key(note.id) {
                    NoteScreen(
                        store = store,
                        note = note,
                        skin = skin,
                        onSkin = {
                            skin = it
                            prefs.skin = it
                        },
                        onOpenNote = { openNote = it },
                        onBack = { openNote = null },
                    )
                    }
                }
            }
        }
        }
    }
}

private val dateFormat = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)


/**
 * Keeps a crash where it can be read later. The system drop box holds a handful
 * of records for the whole device and rolls them off within the hour, which is
 * how a report of "it closes sometimes" arrives with nothing behind it. This
 * file survives, and the real handler still runs after it.
 */
private fun android.content.Context.recordCrashes() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            // The app is not debuggable, so run-as cannot reach its private
            // directory; the external one is where adb can actually pull it.
            val log = java.io.File(getExternalFilesDir(null) ?: filesDir, CRASH_LOG)
            // Bounded: a crash loop must not fill the disk with its own story.
            if (log.length() > CRASH_LOG_MAX) log.delete()
            java.io.PrintWriter(java.io.FileWriter(log, true)).use { out ->
                out.println("---- " + java.util.Date() + " on " + thread.name)
                error.printStackTrace(out)
            }
        }
        previous?.uncaughtException(thread, error)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteListScreen(
    store: NoteStore,
    onSettings: () -> Unit,
    onOpen: (NoteMeta) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Re-read from disk whenever something changed it, rather than keeping a
    // second copy of the truth in memory and having to hold the two in sync.
    var revision by remember { mutableIntStateOf(0) }
    val notes = remember(revision) { store.list() }
    var pendingDelete by remember { mutableStateOf<NoteMeta?>(null) }
    var naming by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // Which note is being written out, and whether as a PDF rather than a backup.
    var exporting by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
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
    // Blank is the top level. Searching reaches across every folder, because
    // the point of searching is not knowing where a thing is.
    var folder by remember { mutableStateOf("") }
    var filing by remember { mutableStateOf<NoteMeta?>(null) }
    val folders = remember(revision) { store.folders() }
    val shown = results ?: notes.filter { it.folder == folder }

    // The user picks where it goes, so a backup survives the app being removed.
    val saveArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        val target = exporting?.first
        exporting = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        busy = "내보내는 중"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    store.exportArchive(listOf(target), it)
                } ?: false
            }
            busy = null
            report = if (ok) "백업 파일을 저장했습니다" else "내보내지 못했습니다"
        }
    }

    val saveAllArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "전체 백업 중"
        scope.launch {
            val ids = withContext(Dispatchers.IO) { store.list().map { it.id } }
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    store.exportArchive(ids, it)
                } ?: false
            }
            busy = null
            report = if (ok) "노트 ${ids.size}개를 백업했습니다" else "내보내지 못했습니다"
        }
    }

    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri: Uri? ->
        val target = exporting?.first
        exporting = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        busy = "PDF로 그리는 중"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    store.exportPdf(target, it)
                } ?: false
            }
            busy = null
            report = if (ok) "PDF를 저장했습니다" else "내보내지 못했습니다"
        }
    }

    val openArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "복원하는 중"
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { store.importArchive(it) } ?: 0
            }
            busy = null
            revision++
            report = if (added > 0) {
                "노트 ${added}개를 복원했습니다"
            } else {
                "Notesis 백업 파일이 아닙니다"
            }
        }
    }

    val indexer = remember { InkIndexer(context) }
    DisposableEffect(Unit) { onDispose { indexer.close() } }

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

    // The list screen had no backdrop at all, so glass here was a low alpha over
    // an opaque background - translucent and not frosted, which is the whole of
    // why the skin looked like it had not been applied outside a note.
    val look = LocalSkinSettings.current
    val backdrop = rememberBackdrop(
        active = LocalSkin.current != Skin.MATERIAL &&
            (look.blur > 0.1f || look.vibrancy > 0.01f),
    )
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
    Scaffold(
        topBar = {
            // Flush to the window edge, and the notes pass underneath it.
            SkinSurface(flush = true) {
            TopAppBar(
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Tune, contentDescription = "화면 설정")
                    }
                },
                navigationIcon = {
                    // Only inside a folder: at the top level there is nowhere
                    // to go back to, and an arrow that does nothing is a lie.
                    if (folder.isNotBlank()) {
                        IconButton(onClick = { folder = "" }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "전체 노트",
                            )
                        }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (folder.isNotBlank()) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(folder, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(16.dp))
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("모든 노트에서 찾기 · 제목 · PDF · 필기") },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                },
                // The surface underneath is the skin's, so the bar itself
                // paints nothing: two containers stacked is what turned the
                // frost into a flat wash.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                BackupButton(
                    onBackupAll = { saveAllArchive.launch("Notesis-백업") },
                    onRestore = { openArchive.launch(arrayOf("*/*")) },
                )
                GlassFab(
                    onClick = { pickPdf.launch(arrayOf("application/pdf")) },
                    icon = Icons.Default.Description,
                    contentDescription = "PDF 가져오기",
                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                )
                GlassFab(
                    onClick = { naming = true },
                    icon = Icons.Default.Add,
                    contentDescription = "새 노트",
                )
            }
        },
    ) { padding ->
        // Nothing inside the recording may sample it; see NoBackdrop.
        CompositionLocalProvider(LocalBackdrop provides NoBackdrop) {
        Box(
            Modifier
                .fillMaxSize()
                .recordBackdrop(backdrop)
                // Opaque, so the frost replaces the page rather than adding to it.
                .background(MaterialTheme.colorScheme.surface),
        ) {
        if (shown.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        query.isNotBlank() -> "\"$query\" 검색 결과가 없습니다"
                        folder.isNotBlank() -> "이 폴더가 비었습니다"
                        else -> "아직 노트가 없습니다"
                    },
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 168.dp),
                // The bar's height is padding inside the list rather than
                // around it, so the notes scroll under the glass instead of
                // stopping politely below it with nothing to frost.
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 16.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Folders sit above the notes rather than beside them: they
                // are a place, not another note.
                if (results == null && folder.isBlank() && folders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FolderRow(folders) { folder = it }
                    }
                }
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
                        onExport = {
                            exporting = note.id to false
                            saveArchive.launch(safeFileName(note.title))
                        },
                        onExportPdf = {
                            exporting = note.id to true
                            savePdf.launch(safeFileName(note.title) + ".pdf")
                        },
                        onFile = { filing = note },
                        onIndex = {
                            busy = "필기를 읽는 중"
                            scope.launch {
                                val pages = withContext(Dispatchers.IO) {
                                    indexNote(store, indexer, note.id)
                                }
                                busy = null
                                report = if (pages < 0) {
                                    "인식 모델을 받지 못했습니다. 인터넷을 확인해주세요"
                                } else {
                                    "페이지 " + pages + "장을 색인했습니다"
                                }
                            }
                        },
                    )
                }
            }
        }
        }
        }
    }
    }

    busy?.let { label ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(label) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("잠시만요")
                }
            },
            confirmButton = {},
        )
    }

    report?.let { message ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text(message) },
            confirmButton = { TextButton(onClick = { report = null }) { Text("확인") } },
        )
    }

    filing?.let { target ->
        FolderDialog(
            current = target.folder,
            folders = folders,
            onDismiss = { filing = null },
            onPick = { picked ->
                store.setFolder(target.id, picked)
                filing = null
                revision++
            },
        )
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
    onExport: () -> Unit,
    onExportPdf: () -> Unit,
    onIndex: () -> Unit,
    onFile: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Keyed on the file's timestamp, so replacing the picture redraws the card
    // instead of showing the decoded copy of the old one.
    val preview = remember(note.thumbnail?.path, note.thumbnail?.lastModified()) {
        note.thumbnail?.let { file ->
            runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull()
        }
    }
    val skin = LocalSkin.current
    Card(
        onClick = onOpen,
        // No shadow on glass. A shadow is drawn under the whole card, not only
        // around it, and the card's body is translucent - so the strip under
        // the thumbnail, which is the only part you can see through, showed the
        // shadow beneath it: dark at the edges, clear in the middle. That pale
        // patch under every note's name was a shadow seen from the front.
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (skin == Skin.MATERIAL) 1.dp else 0.dp,
        ),
        colors = if (skin == Skin.MATERIAL) {
            CardDefaults.cardColors()
        } else {
            // The card is mostly its own picture, so it goes only slightly
            // translucent - enough to belong with the glass, not so much that
            // the thumbnail has to compete with the wallpaper behind it.
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            )
        },
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
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("폴더로 이동") },
                            leadingIcon = {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onFile()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("필기 검색 색인") },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onIndex()
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("백업 파일로 내보내기") },
                            leadingIcon = {
                                Icon(Icons.Default.Archive, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("PDF로 내보내기") },
                            leadingIcon = {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onExportPdf()
                            },
                        )
                        HorizontalDivider()
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

/** Picks how the chrome is dressed, with each choice wearing its own skin. */
@Composable
private fun SkinButton(skin: Skin, onSkin: (Skin) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.AutoAwesomeMosaic, contentDescription = "테마")
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (option in Skin.entries) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label)
                            Text(
                                option.blurb,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    },
                    leadingIcon = {
                        // Each row shows the skin it names, so the choice is
                        // made by looking rather than by reading.
                        ProvideSkin(option, LocalSkinSettings.current) {
                            SkinSurface(Modifier.size(30.dp), corner = 9.dp) {}
                        }
                    },
                    trailingIcon = {
                        if (option == skin) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        open = false
                        onSkin(option)
                    },
                )
            }
        }
    }
}

/**
 * Reads every page of a note. Pages are loaded one at a time and let go again,
 * because a 120 page book indexed all at once is a 120 page book in memory.
 * Returns how many pages were read, or -1 when the models are not available.
 */
private fun indexNote(store: NoteStore, indexer: InkIndexer, id: String): Int {
    if (!indexer.prepare()) return -1
    val document = store.load(id)
    var done = 0
    for (page in document.pages) {
        val strokes = store.loadPage(id, page, STROKE_EPSILON)
        if (strokes.isEmpty()) {
            store.writeInkIndex(id, page.id, "")
            done++
            continue
        }
        indexer.textOf(strokes)?.let { store.writeInkIndex(id, page.id, it) }
        done++
    }
    return done
}

/** The folders that exist, as somewhere to go. */
@Composable
private fun FolderRow(folders: List<String>, onOpen: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (name in folders) {
            SkinSurface(corner = 14.dp) {
                Row(
                    Modifier
                        .clickable { onOpen(name) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * Picks a folder for one note. Typing a name that does not exist makes it -
 * there is nothing to create first, because a folder is only the name its notes
 * agree on.
 */
@Composable
private fun FolderDialog(
    current: String,
    folders: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("폴더로 이동") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("폴더 이름") },
                    placeholder = { Text("비우면 맨 위로") },
                )
                if (folders.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "이미 있는 폴더",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (option in folders) {
                            TextButton(onClick = { name = option }) { Text(option) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(name.trim()) }) { Text("이동") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * A floating button in the skin the rest of the chrome is wearing.
 *
 * Material's own FAB paints its container itself, so the three buttons in the
 * corner of the note list stayed opaque slabs while every other pane on the
 * screen went to glass. Under Material this is still that FAB; under glass it
 * is a [SkinSurface] with the icon in it, frosting the list underneath the way
 * the toolbar does.
 */
@Composable
private fun GlassFab(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val side = if (small) 40.dp else 56.dp
    if (LocalSkin.current == Skin.MATERIAL) {
        if (small) {
            SmallFloatingActionButton(onClick = onClick, modifier = modifier) {
                Icon(icon, contentDescription = contentDescription)
            }
        } else {
            FloatingActionButton(onClick = onClick, modifier = modifier) {
                Icon(icon, contentDescription = contentDescription)
            }
        }
        return
    }
    SkinSurface(modifier = modifier.size(side), corner = 16.dp) {
        // Clickable inside the surface, so the ripple is clipped to the corner
        // rather than squaring it off.
        Box(
            Modifier.fillMaxSize().clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}

/** Whole-library backup and restore, kept together because they are one job. */
@Composable
private fun BackupButton(onBackupAll: () -> Unit, onRestore: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlassFab(
            onClick = { open = true },
            icon = Icons.Default.Archive,
            contentDescription = "백업",
            small = true,
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("전체 백업") },
                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                onClick = {
                    open = false
                    onBackupAll()
                },
            )
            DropdownMenuItem(
                text = { Text("백업에서 복원") },
                leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                onClick = {
                    open = false
                    onRestore()
                },
            )
        }
    }
}

/** A title is not a filename: strip what a file system will not take. */
private fun safeFileName(title: String): String {
    val cleaned = title.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return cleaned.ifBlank { "note" }.take(60)
}

/**
 * A pen in the tray. Round for a pen, rounded-square for a highlighter, so the
 * two are told apart by shape and not only by how see-through the colour is.
 */
@Composable
private fun PenChip(
    pen: PenPreset,
    selected: Boolean,
    onClick: () -> Unit,
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
            .clickable(onClick = onClick),
    )
}

/**
 * One tool in the row, wearing its own colour so the row shows at a glance what
 * each will draw with. Tapping the one already in hand opens its settings.
 */
@Composable
private fun ToolChip(
    icon: ImageVector,
    label: String,
    tool: EditMode,
    mode: EditMode,
    pen: PenPreset,
    onMode: (EditMode) -> Unit,
) {
    val selected = mode == tool
    ToolButton(
        icon = icon,
        label = label,
        selected = selected,
        // Only the tool in hand knows its colour here; the rest wear the
        // ordinary icon tint rather than a colour this composable cannot see.
        tint = if (selected && tool.tints) Color(pen.colorArgb.or(0xFF000000.toInt())) else null,
    ) { onMode(tool) }
}

/**
 * Sets the colour and thickness of the tool in hand. Colour is picked in
 * HSV - which is how people actually describe a colour - with alpha on its own
 * strip, because a highlighter is exactly a pen whose alpha is not 255.
 */
@Composable
private fun PenDialog(
    mode: EditMode,
    pen: PenPreset,
    /** Global rather than per tool, but this is where a hand is being set up. */
    prediction: Boolean,
    onPrediction: (Boolean) -> Unit,
    deferDetail: Boolean,
    onDeferDetail: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (PenPreset) -> Unit,
) {
    val start = pen
    val hsv = remember(pen) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(start.colorArgb, it) }
    }
    var hue by remember(pen) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(pen) { mutableFloatStateOf(hsv[1]) }
    var value by remember(pen) { mutableFloatStateOf(hsv[2]) }
    var alpha by remember(pen) {
        mutableFloatStateOf(android.graphics.Color.alpha(start.colorArgb) / 255f)
    }
    var width by remember(pen) { mutableFloatStateOf(start.width) }
    var pressure by remember(pen) { mutableStateOf(start.pressure) }
    var maxWidth by remember(pen) { mutableFloatStateOf(start.maxWidth) }
    val range = PenStore.widthRange(mode, start.copy(maxWidth = maxWidth))

    val picked = Color.hsv(hue, saturation, value, alpha)
    val argb = picked.toArgb()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(toolLabel(mode)) },
        text = {
            Column {
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

                if (pen.tool == Tool.PEN) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkinSwitch(checked = pressure, onCheckedChange = { pressure = it })
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("필압", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "세게 누를수록 굵게",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkinSwitch(checked = prediction, onCheckedChange = onPrediction)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("예측", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "펜보다 한 프레임 앞서 그립니다. 획이 각져 보이면 꺼보세요",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkinSwitch(checked = deferDetail, onCheckedChange = onDeferDetail)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("확대 후 선명하게", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "확대하는 동안은 있는 그대로 그리고, 손을 떼면 그때 다시 " +
                                "선명하게 만듭니다. 글이 많은 페이지에서 확대가 버벅이면 켜두세요",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "굵기 " + "%.1f".format(width),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    // Where the slider's top end sits. One range has to cover a
                    // hairline and a broad highlighter, and the pen half of it
                    // was living in a fifth of the track.
                    for ((label, ceiling) in PenStore.widthCeilings(mode)) {
                        val chosen = kotlin.math.abs(range.endInclusive - ceiling) < 0.01f
                        TextButton(onClick = { maxWidth = ceiling }) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (chosen) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                SkinSlider(
                    value = width.coerceIn(range),
                    onValueChange = { width = it },
                    valueRange = range,
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
            TextButton(
                onClick = {
                    onConfirm(
                        PenPreset(
                            pen.tool,
                            argb,
                            width.coerceIn(range),
                            pressure,
                            maxWidth,
                        ),
                    )
                },
            ) {
                Text("저장")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun toolLabel(mode: EditMode): String = when (mode) {
    EditMode.PEN -> "펜"
    EditMode.HIGHLIGHTER -> "형광펜"
    EditMode.MASK -> "마스킹테이프"
    EditMode.SHAPE -> "도형"
    EditMode.ERASE -> "지우개"
    else -> "도구"
}

/** Saturation across, brightness down, at the given [hue]. */
@Composable
internal fun SaturationValueField(
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
internal fun GradientStrip(
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
    desktop: Boolean,
    onToggleDesktop: () -> Unit,
    log: MutableList<String>,
    showLog: Boolean,
    onToggleLog: () -> Unit,
    onClose: () -> Unit,
    onFile: (android.webkit.ValueCallback<Array<Uri>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val web = holder.value
    var address by remember(url) { mutableStateOf(url) }

    SkinSurface(modifier = modifier, corner = 0.dp) {
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
                IconButton(onClick = onToggleLog) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = "오류 기록",
                        tint = if (log.isEmpty()) {
                            MaterialTheme.colorScheme.outlineVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                IconButton(onClick = onToggleDesktop) {
                    Icon(
                        if (desktop) Icons.Default.Computer else Icons.Default.PhoneAndroid,
                        contentDescription = if (desktop) "PC 화면" else "모바일 화면",
                    )
                }
                IconButton(onClick = { openExternally(web?.context, web?.url ?: url) }) {
                    Icon(Icons.Default.Launch, contentDescription = "브라우저로 열기")
                }
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
                // weight, not fillMaxSize: a Column measures an unweighted child
                // with an unbounded height, AndroidView passes that on as an
                // UNSPECIFIED MeasureSpec, and Chromium then resolves vh units
                // against a viewport of zero. Every site built on a height chain
                // - Gemini, claude.ai - collapsed to its top bar because of it.
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { viewContext ->
                    // The same WebView is reused across close, reopen and the
                    // move between docked and popup. Adding a view that still
                    // has a parent is a hard crash, so let go of the old one.
                    val existing = holder.value
                    if (existing != null) {
                        (existing.parent as? android.view.ViewGroup)?.removeView(existing)
                        existing
                    } else {
                        newBrowser(viewContext, onFile, log).also { holder.value = it }
                    }
                },
                // The tag remembers which site was asked for, so picking another
                // one loads it while a stroke on the note next door does not.
                update = { view ->
                    val wanted = uaFor(
                        android.webkit.WebSettings.getDefaultUserAgent(view.context),
                        desktop,
                    )
                    val swapped = view.settings.userAgentString != wanted
                    if (swapped) view.settings.userAgentString = wanted
                    if (view.tag != url) {
                        view.tag = url
                        view.loadUrl(url)
                    } else if (swapped) {
                        // A site decides what to serve from the user agent it
                        // saw, so changing it is only worth anything on a fetch.
                        view.reload()
                    }
                },
            )
            if (showLog) {
                HorizontalDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    if (log.isEmpty()) {
                        Text("기록된 오류 없음", style = MaterialTheme.typography.bodySmall)
                    }
                    for (line in log) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    TextButton(onClick = { log.clear() }) { Text("지우기") }
                }
            }
        }
    }
}

/**
 * What the panel claims to be. Every AI site refuses to sign in to an embedded
 * WebView, and Android gives itself away twice: the "wv" token and the stale
 * "Version/4.0". Stripping both leaves a plain Chrome for Android. Desktop
 * borrows the same Chrome build number and drops the mobile platform, which is
 * what the sites that only ship a desktop layout want to see.
 */
internal fun uaFor(base: String, desktop: Boolean): String {
    if (!desktop) {
        return base
            .replace("; wv", "")
            .replace(Regex("Version/[\\d.]+ "), "")
    }
    val chrome = Regex("Chrome/[\\d.]+").find(base)?.value ?: "Chrome/140.0.0.0"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) $chrome Safari/537.36"
}

/** The escape hatch: hand the page to a real browser, which can always log in. */
private fun openExternally(context: android.content.Context?, url: String) {
    context ?: return
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)),
        )
    }
}

private fun newBrowser(
    context: android.content.Context,
    onFile: (android.webkit.ValueCallback<Array<Uri>>) -> Unit,
    log: MutableList<String>,
): android.webkit.WebView {
    val view = android.webkit.WebView(context)
    // The host adds a factory view as WRAP_CONTENT, which reaches Chromium as an
    // unbounded height, and it then resolves vh units against a viewport of
    // zero. Sites built on a height chain - Gemini, claude.ai - collapse to
    // their top bar because of it.
    view.layoutParams = android.view.ViewGroup.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
    )
    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    // With this on, a tablet plugged in over USB can be opened from
    // chrome://inspect on a desktop, which is the only way to see a stack
    // trace from a page that renders its shell and then stops.
    android.webkit.WebView.setWebContentsDebuggingEnabled(true)

    fun note(line: String) {
        // Newest last, and bounded: a page in a failure loop can log forever.
        if (log.size >= WEB_LOG_MAX) log.removeAt(0)
        log.add(line)
    }

    return view.apply {
        webViewClient = object : android.webkit.WebViewClient() {
            override fun onReceivedError(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                // Only the page itself: a failed tracking pixel is noise.
                if (request.isForMainFrame) note("net ${error.errorCode} ${error.description}")
            }

            override fun onReceivedHttpError(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest,
                response: android.webkit.WebResourceResponse,
            ) {
                if (request.isForMainFrame) note("http ${response.statusCode} ${request.url}")
            }

            override fun onRenderProcessGone(
                view: android.webkit.WebView,
                detail: android.webkit.RenderProcessGoneDetail,
            ): Boolean {
                note("renderer gone, crashed=${detail.didCrash()}")
                return true
            }
        }
        // Without a chrome client a page gets no upload button, no window.open
        // and no JS dialogs, which is most of a chat app.
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(
                view: android.webkit.WebView,
                callback: android.webkit.ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                onFile(callback)
                return true
            }

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    note("js ${message.message().take(WEB_LOG_LINE)}")
                }
                return true
            }
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        // A sign-in popup with nowhere to go is a dead button; loading it in
        // place is what a single-window browser does.
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.userAgentString =
            uaFor(android.webkit.WebSettings.getDefaultUserAgent(context), false)
    }
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

/**
 * What the pen does when it lands. One thing at a time, by construction, and
 * each one remembers its own colour and thickness rather than sharing a tray.
 */
enum class EditMode { PEN, HIGHLIGHTER, MASK, LASSO, SHAPE, IMAGE, ERASE, READ, CAPTURE }

/** Whether this mode puts something on the page in the tool's own colour. */
private val EditMode.tints: Boolean
    get() = this == EditMode.PEN || this == EditMode.HIGHLIGHTER ||
        this == EditMode.MASK || this == EditMode.SHAPE

@Composable
private fun NoteScreen(
    store: NoteStore,
    note: NoteMeta,
    skin: Skin,
    onSkin: (Skin) -> Unit,
    onOpenNote: (NoteMeta) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val penStore = remember { PenStore(context) }
    val indexer = remember { InkIndexer(context) }
    DisposableEffect(Unit) { onDispose { indexer.close() } }
    var settings by remember { mutableStateOf(penStore.load()) }
    // One mode at a time, and every mode carries its own colour and thickness,
    // so putting the eraser down gives back the pen exactly as it was left.
    var mode by remember { mutableStateOf(EditMode.PEN) }
    var shapeKind by remember { mutableStateOf(ShapeKind.LINE) }
    // A straight line in the highlighter's or the mask's own ink, without
    // leaving the tool to reach the separate shape pen. Scoped to those two
    // modes only - the shape button already covers the ordinary pen.
    var straightLine by remember { mutableStateOf(false) }
    /** True while the colour and thickness of the tool in hand is being set. */
    var editingPen by remember { mutableStateOf(false) }
    var lassoCount by remember { mutableIntStateOf(0) }
    val pen = settings[mode] ?: PenStore.DEFAULTS.getValue(EditMode.PEN)
    val eraserWidth = (settings[EditMode.ERASE] ?: PenStore.DEFAULTS.getValue(EditMode.ERASE)).width
    val tool = if (mode == EditMode.ERASE) Tool.ERASER else pen.drawingTool()

    // Dragging the width slider changes the tool on every frame; settings are
    // written once the dragging stops rather than once per frame.
    LaunchedEffect(settings) {
        delay(PEN_SAVE_DELAY_MS)
        withContext(Dispatchers.IO) { penStore.save(settings) }
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
    // Every AI site refuses to sign in to something that looks like a
    // WebView, so the panel can claim to be desktop Chrome instead.
    var webDesktop by remember { mutableStateOf(false) }
    // What the page says went wrong. The panel renders a shell and then
    // nothing, and without this there is no way to see why from here.
    val webLog = remember { mutableStateListOf<String>() }
    var showWebLog by remember { mutableStateOf(false) }
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
    // Flush against the top edge, or floating over the page. Kept in
    // preferences: where the toolbar sits is a habit, not a per-note choice.
    var docked by remember { mutableStateOf(penStore.docked) }
    var prediction by remember { mutableStateOf(penStore.prediction) }
    var deferDetail by remember { mutableStateOf(penStore.deferDetail) }
    // Null until it is dragged: the bar sits centred at the top by default, and
    // there is no sensible centre to store before anything has been measured.
    var barOffset by remember { mutableStateOf<Offset?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    // The three-finger reference panel: a second, live InkCanvasView floating
    // over this one, on whichever note and page it is pointed at - any note,
    // not only this one, and writable, since a reference worth keeping open
    // is usually a reference worth adding to. The note it shows is remembered
    // in preferences, so it is still the same one after a close, after leaving
    // the note, and after the app has been shut.
    var referenceOpen by remember { mutableStateOf(false) }
    var referenceNoteId by remember { mutableStateOf(penStore.referenceNote ?: note.id) }
    var referencePage by remember { mutableIntStateOf(0) }
    // Whether the page in the panel is fitted to the panel's width. Off, it
    // keeps whatever zoom it was put at.
    var referenceFit by remember { mutableStateOf(penStore.referenceFit) }
    var referenceOffset by remember { mutableStateOf(Offset.Zero) }
    // The panel's laid-out size, and the amount it is being stretched by right
    // now. Two of them, because a spread that re-lays-out the panel on every
    // frame is a relayout of everything in it on every frame: that was the
    // shake, and it was also why the page inside seemed to move on its own -
    // the window grew and the page it held did not. Growing is a scale on the
    // whole panel instead, one number on the render thread, so the page grows
    // with its frame exactly. When the hand comes off, the stretch is folded
    // into the size and the page is zoomed by the same amount, which puts the
    // picture back where it was and redraws it sharp. See onReferenceDragEnd.
    var referenceSize by remember {
        mutableStateOf(with(density) { Size(340.dp.toPx(), 440.dp.toPx()) })
    }
    var referenceStretch by remember { mutableFloatStateOf(1f) }
    // Three fingers on the panel itself, once it is open: the spread is how
    // much it grows, the drag is where it goes. One function because the
    // panel's own view and the gesture that opened it both end up calling it.
    fun moveReference(panX: Float, panY: Float, spreadFactor: Float) {
        val minSize = with(density) { REFERENCE_MIN_SIZE.toPx() }
        val floor = minSize / minOf(referenceSize.width, referenceSize.height)
        // Width only. Capping against the height too meant a panel whose height
        // had already reached the screen could not be widened at all, however
        // much narrower than the screen it still was - and width is the
        // dimension a page is read across. Taller than the screen is allowed;
        // it sits against the top and the rest is below the fold.
        val ceiling = if (containerSize.width > 0) {
            containerSize.width / referenceSize.width
        } else {
            floor
        }
        referenceStretch = (referenceStretch * spreadFactor)
            .coerceIn(floor, maxOf(floor, ceiling))
        val w = referenceSize.width * referenceStretch
        val h = referenceSize.height * referenceStretch
        val maxX = (containerSize.width - w).coerceAtLeast(0f)
        val maxY = (containerSize.height - h).coerceAtLeast(0f)
        referenceOffset = Offset(
            (referenceOffset.x + panX).coerceIn(0f, maxX),
            (referenceOffset.y + panY).coerceIn(0f, maxY),
        )
    }
    val maxBarWidth = with(density) {
        (containerSize.width.takeIf { it > 0 } ?: Int.MAX_VALUE).toDp()
    }
    val otherNotes = remember(note.id) { store.list().filter { it.id != note.id } }
    val referenceNotes = remember(note, otherNotes) { listOf(note) + otherNotes }
    var showLatency by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var edits by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(note.pageCount) }
    var currentPage by remember { mutableIntStateOf(0) }
    // A multiple of fit-to-width, which is the 100% anybody means.
    var zoom by remember { mutableFloatStateOf(1f) }
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
            desktop = webDesktop,
            onToggleDesktop = { webDesktop = !webDesktop },
            log = webLog,
            showLog = showWebLog,
            onToggleLog = { showWebLog = !showWebLog },
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

    // One toolbar, placed two ways: flush along the top edge, or floating
    // over the page where it can be dragged and folded away.
    val toolbar: @Composable (Modifier) -> Unit = { barModifier ->
        Toolbar(
            note = note,
            otherNotes = otherNotes,
            pen = pen,
            mode = mode,
            shapeKind = shapeKind,
            straightLine = straightLine,
            onToggleStraightLine = { straightLine = !straightLine },
            fullscreen = fullscreen,
            showLatency = showLatency,
            // edits is read here so drawing or erasing recomposes the toolbar and
            // undo/redo can re-evaluate whether there is anything on the stacks.
            canUndo = edits.let { canvas?.canUndo() == true },
            canRedo = edits.let { canvas?.canRedo() == true },
            onEditPen = { editingPen = true },
            onMode = { picked ->
                // Leaving a mode takes its leftovers with it: a selection that
                // cannot be extended any more, a picture with handles on it,
                // strokes held in a lasso that is no longer in hand.
                canvas?.clearSelection()
                canvas?.clearImageSelection()
                canvas?.clearLassoSelection()
                // Tapping the tool already in hand opens its settings rather
                // than dropping it: there is no unset mode to fall back to.
                if (mode == picked) editingPen = picked.tints else mode = picked
            },
            onShape = {
                shapeKind = it
                canvas?.clearSelection()
                canvas?.clearImageSelection()
                mode = EditMode.SHAPE
            },
            onPickImage = { pickImage.launch("image/*") },
            onWeb = { webUrl = it },
            onWidth = { settings = settings + (mode to pen.copy(width = it)) },
            onUndo = {
                canvas?.undo()
                edits++
            },
            onRedo = {
                canvas?.redo()
                edits++
            },
            onFitWidth = { canvas?.fitWidth() },
            zoomLabel = "${(zoom * 100).roundToInt()}%",
            onToggleFullscreen = { fullscreen = !fullscreen },
            onToggleLatency = { showLatency = !showLatency },
            onTogglePages = { showPages = !showPages },
            onCollapse = { collapsed = true },
            onOpenNote = onOpenNote,
            onBack = onBack,
            pageLabel = "${currentPage + 1} / $pageCount",
            onPalette = { showPalette = true },
            skin = skin,
            onSkin = onSkin,
            docked = docked,
            onToggleDock = {
                docked = !docked
                penStore.docked = docked
            },
            canResetBar = !docked && barOffset != null,
            onResetBar = { barOffset = null },
            modifier = barModifier,
        )
    }

    // The chrome looks through whatever is drawn under it, so the page records
    // itself once a frame - but only while something actually bends or blurs it.
    val look = LocalSkinSettings.current
    val backdrop = rememberBackdrop(
        active = skin != Skin.MATERIAL && (look.blur > 0.1f || look.vibrancy > 0.01f),
    )
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
    Row(Modifier.fillMaxSize()) {
    // The page's own ground, behind the bar as well as behind the page. Docked,
    // the bar takes its own room, and that room was the bare window underneath -
    // white above it where the status bar inset is and white in the seam below,
    // with the glass tinting nothing but that white. Painting the paper colour
    // the whole way up closes both gaps and gives the glass something of the
    // page's own to sit on.
    Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFE9E7E2))) {
    if (docked && !collapsed) toolbar(Modifier.fillMaxWidth())
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .onSizeChanged { containerSize = it },
    ) {
        val ready = opened
        // Only the page is recorded, never the chrome above it. A pane that
        // refracts draws the layer, so a pane inside the recording puts the
        // layer inside itself - the render tree becomes a cycle and the
        // RenderThread walks it until the stack runs out.
        // The ground goes inside the recording, not over it. Recorded on a
        // transparent one, the layer is only the page's content, and a pane
        // draws that blurred over the sharp copy already on screen - the same
        // light twice, which is the wash that made the middle of every panel
        // paler than the page beside it.
        Box(Modifier.fillMaxSize().recordBackdrop(backdrop).background(Color(0xFFE9E7E2))) {
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
                        maskLoader = { page, epsilon ->
                            store.loadMasks(note.id, page, epsilon)
                        }
                        open(ready.first, ready.second)
                        // Where the note was left. Posted, because the fit to
                        // width that decides the scale happens on the first
                        // layout and would otherwise undo the scroll.
                        penStore.lastPage(note.id).takeIf { it > 0 }?.let { resume ->
                            post { scrollToPage(resume) }
                        }
                        onStrokesChanged = {
                            edits++
                            pageCount = document.pages.size
                        }
                        onCurrentPageChanged = { currentPage = it }
                        onZoomChanged = { zoom = it }
                        onSelectionChanged = { selectedText = it?.text }
                        onImageSelected = { imageSelected = it }
                        onLassoSelected = { lassoCount = it }
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
                    // The frost stops while the pen is down; see Backdrop.paused.
                    view.onDrawingChanged = { drawing -> backdrop.paused = drawing }
                    view.predictionEnabled = prediction
                    view.deferDetail = deferDetail
                    view.tool = tool
                    view.readMode = mode == EditMode.READ
                    view.shapeKind = when {
                        mode == EditMode.SHAPE -> shapeKind
                        // The toggle only appears for these two, so the ink
                        // that lands is whichever of them is actually in hand.
                        straightLine && (mode == EditMode.HIGHLIGHTER || mode == EditMode.MASK) ->
                            ShapeKind.LINE
                        else -> null
                    }
                    view.imageMode = mode == EditMode.IMAGE
                    view.captureMode = mode == EditMode.CAPTURE
                    view.maskMode = mode == EditMode.MASK
                    view.lassoMode = mode == EditMode.LASSO
                    // Tape that lets the answer through is not tape, so the
                    // mask tool draws its colour opaque whatever alpha it holds.
                    view.colorArgb =
                        if (mode == EditMode.MASK) pen.colorArgb or 0xFF000000.toInt() else pen.colorArgb
                    view.strokeWidth = pen.width
                    view.eraserWidth = eraserWidth
                    view.onUndo = { canvas?.undo(); edits++ }
                    view.onRedo = { canvas?.redo(); edits++ }
                    view.referenceOpen = referenceOpen
                    view.onOpenReference = { cx, cy ->
                        // Whichever note it was last pointed at stays pointed
                        // at. Closing the panel is putting a book down, not
                        // throwing it away.
                        referenceOpen = true
                        val w = referenceSize.width * referenceStretch
                        val h = referenceSize.height * referenceStretch
                        val maxX = (containerSize.width - w).coerceAtLeast(0f)
                        val maxY = (containerSize.height - h).coerceAtLeast(0f)
                        referenceOffset = Offset(
                            (cx - w / 2f).coerceIn(0f, maxX),
                            (cy - h).coerceIn(0f, maxY),
                        )
                    }
                    // Three fingers down the page, with the panel already open,
                    // put it away: the gesture that opened it, run backwards.
                    view.onCloseReference = { referenceOpen = false }
                    // Moving and resizing the panel itself is handled on its own
                    // view, not here; see ReferencePanel.
                },
            )
        }
        }

        // Autosave: each change restarts a short timer, so a burst of strokes
        // writes the note once instead of once per stroke.
        LaunchedEffect(edits) {
            if (edits == 0) return@LaunchedEffect
            delay(AUTOSAVE_DELAY_MS)
            val view = canvas ?: return@LaunchedEffect
            // Only pages marked dirty are actually written; see NoteStore.
            withContext(Dispatchers.IO) { store.save(note.id, note.title, view.document) }
            // Then read back what was just written, so the page can be found by
            // what it says. Only the page being worked on: recognising the
            // whole note on every autosave would cost more than it is worth,
            // and the rest is caught by the note's own index action.
            val page = view.document.pages.getOrNull(view.currentPageIndex()) ?: return@LaunchedEffect
            if (!page.loaded) return@LaunchedEffect
            val strokes = page.strokes.toList()
            withContext(Dispatchers.IO) {
                indexer.textOf(strokes)?.let { store.writeInkIndex(note.id, page.id, it) }
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
                    penStore.setLastPage(note.id, view.currentPageIndex())
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

        if (!docked && !collapsed) {
            toolbar(
                Modifier
                    .align(Alignment.TopStart)
                    // Capped, not just clamped. The tool row scrolls, and a
                    // scrolling row takes every pixel it is offered, so on a
                    // landscape tablet the bar stretched the whole 2960px with
                    // the tools huddled in the first third of it.
                    .widthIn(max = minOf(maxBarWidth, FLOATING_BAR_MAX))
                    // Placed and clamped together: the folded handle can be
                    // dragged anywhere, and unfolding measures the wide bar and
                    // pulls it back inside rather than letting it hang off.
                    .offset { barPlacement(barOffset, barSize, containerSize) }
                    .onSizeChanged { barSize = it }
                    .windowInsetsPadding(ChromeInsets)
                    .padding(12.dp),
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
        }


        if (editingPen) {
            PenDialog(
                mode = mode,
                pen = pen,
                prediction = prediction,
                onPrediction = {
                    prediction = it
                    penStore.prediction = it
                },
                deferDetail = deferDetail,
                onDeferDetail = {
                    deferDetail = it
                    penStore.deferDetail = it
                },
                onDismiss = { editingPen = false },
                onConfirm = { saved ->
                    settings = settings + (mode to saved)
                    editingPen = false
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
                    val recoloured = ((rgb.toLong() and 0xFFFFFFL) or alpha).toInt()
                    settings = settings + (mode to pen.copy(colorArgb = recoloured))
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
                        // Naming the two taps: the sheet behind "+" offers a
                        // camera and a photo picker too, and neither of those
                        // is the file chooser this capture is waiting for.
                        Toast.makeText(
                            context,
                            "대화창의 + 를 누르고 \"파일\"을 고르세요",
                            Toast.LENGTH_LONG,
                        ).show()
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

        if (lassoCount > 0) {
            LassoActions(
                count = lassoCount,
                onDelete = {
                    canvas?.deleteLassoSelection()
                    edits++
                },
                onDone = { canvas?.clearLassoSelection() },
                modifier = Modifier.align(Alignment.BottomCenter),
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
                onMask = {
                    canvas?.maskSelection()
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
                edits = edits,
                onJump = { canvas?.scrollToPage(it) },
                onReveal = { index, revealed ->
                    canvas?.setMasksRevealed(index, revealed)
                    edits++
                },
                onClearMasks = {
                    canvas?.clearMasks(it)
                    edits++
                },
                onRevealMask = { index, maskIndex, revealed ->
                    canvas?.setMaskRevealed(index, maskIndex, revealed)
                    edits++
                },
                onDeleteMask = { index, maskIndex ->
                    canvas?.deleteMask(index, maskIndex)
                    edits++
                },
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

        // Scale from nothing rather than sliding in from an edge: it opens
        // from wherever the gesture was, not from a side of the screen.
        // Qualified like the sidebar's below: the enclosing scopes put more
        // than one overload in reach, and this is the one without a receiver.
        androidx.compose.animation.AnimatedVisibility(
            visible = referenceOpen,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.85f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f),
        ) {
            ReferencePanel(
                store = store,
                notes = referenceNotes,
                // The remembered note may have been deleted since; fall back to
                // the one being written on rather than to a blank panel.
                noteId = referenceNoteId.takeIf { id -> referenceNotes.any { it.id == id } }
                    ?: note.id,
                page = referencePage,
                tool = tool,
                pen = pen,
                eraserWidth = eraserWidth,
                deferDetail = deferDetail,
                offset = referenceOffset,
                size = referenceSize,
                stretch = referenceStretch,
                onStretchEnd = { view ->
                    // The stretch becomes the size. What happens to the page is
                    // left with the view to do when the new size actually
                    // arrives: either it fits the new width, which is how a
                    // note is read, or it is zoomed by the same amount the
                    // frame was, which leaves the picture exactly as it looked
                    // being stretched. Done here it would be done against the
                    // old size, and that was the lurch when the hand came off.
                    val factor = referenceStretch
                    if (factor != 1f) {
                        referenceSize = Size(
                            referenceSize.width * factor,
                            referenceSize.height * factor,
                        )
                        referenceStretch = 1f
                        view?.onNextResize(factor, fitInstead = referenceFit)
                    }
                },
                fit = referenceFit,
                onFit = {
                    referenceFit = it
                    penStore.referenceFit = it
                },
                onNoteChange = {
                    referenceNoteId = it
                    penStore.referenceNote = it
                    referencePage = 0
                },
                onPageChange = { referencePage = it },
                onDrag = ::moveReference,
                onClose = { referenceOpen = false },
            )
        }
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

/** What the loop caught, and the two things worth doing with it. */
@Composable
private fun LassoActions(
    count: Int,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SkinSurface(
        modifier = modifier
            .windowInsetsPadding(ChromeInsets)
            .padding(16.dp),
        corner = 16.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$count 획 · 끌어서 이동", style = MaterialTheme.typography.bodyMedium)
            ToolbarDivider()
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text(" 삭제")
            }
            TextButton(onClick = onDone) { Text("완료") }
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
    SkinSurface(
        modifier = modifier
            .windowInsetsPadding(ChromeInsets)
            .padding(16.dp),
        corner = 16.dp,
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

/**
 * The three-finger reference panel: a second, live [InkCanvasView] floating
 * over the one being written on, pointed at any note and any page - not a
 * snapshot of one. Writable, with whatever tool is in hand on the toolbar,
 * because a reference worth keeping open is usually a reference worth adding
 * to; its own three fingers move and resize the panel itself, the same
 * gesture that opened it, now caught by this view instead of the one under it.
 */
@Composable
private fun ReferencePanel(
    store: NoteStore,
    /** This note first, then every other one - what the picker offers. */
    notes: List<NoteMeta>,
    noteId: String,
    page: Int,
    tool: Tool,
    pen: PenPreset,
    eraserWidth: Float,
    deferDetail: Boolean,
    offset: Offset,
    size: Size,
    /** What the whole panel is scaled by while a spread is in progress. */
    stretch: Float,
    /** The spread has ended: fold [stretch] into the size, given this panel's view. */
    onStretchEnd: (InkCanvasView?) -> Unit,
    /**
     * Whether the page is fitted to the panel's width - on opening a note, on
     * changing page, and after a resize. Off, it keeps whatever zoom it was put
     * at, and a resize scales it by exactly what the frame grew by.
     */
    fit: Boolean,
    onFit: (Boolean) -> Unit,
    onNoteChange: (String) -> Unit,
    onPageChange: (Int) -> Unit,
    onDrag: (panX: Float, panY: Float, spreadFactor: Float) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var opened by remember { mutableStateOf<Pair<Document, PdfSource?>?>(null) }
    var view by remember { mutableStateOf<InkCanvasView?>(null) }
    var popupEdits by remember { mutableIntStateOf(0) }
    var noteMenu by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf(false) }
    // What has been typed into the picker's search box. Cleared with the menu,
    // so opening it again offers everything rather than the last hunt.
    var noteQuery by remember { mutableStateOf("") }

    // A different note is a different document and a different PDF, decoded
    // the same way opening one from the list is - off the main thread, since
    // parsing a PDF header there is a visible freeze.
    LaunchedEffect(noteId) {
        opened = null
        opened = withContext(Dispatchers.IO) {
            store.load(noteId) to PdfSource.open(store.pdfFile(noteId), PdfSource.cacheBytesFor(context))
        }
    }
    // This panel's own PdfSource, closed here - the note underneath opened a
    // different one, or none, and does not know this one exists.
    DisposableEffect(noteId) {
        onDispose { opened?.second?.close() }
    }
    // A note or a page change refits the page to the panel, when that is asked
    // for. Not a resize: a resize now scales the page along with its frame, and
    // refitting after one would undo exactly that.
    LaunchedEffect(view, noteId, page, opened, fit) {
        val v = view ?: return@LaunchedEffect
        if (fit) v.fitWidth()
        v.scrollToPage(page)
    }

    // No debounce: a stroke made here and lost to a quick close is worse than
    // the occasional extra write, and NoteStore only ever writes dirty pages
    // anyway - see the comment on the note's own autosave.
    LaunchedEffect(popupEdits) {
        if (popupEdits == 0) return@LaunchedEffect
        val v = view ?: return@LaunchedEffect
        val title = notes.find { it.id == noteId }?.title ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { store.save(noteId, title, v.document) }
    }

    val title = notes.find { it.id == noteId }?.title ?: noteId
    val pageCount = opened?.first?.pages?.size ?: 0

    SkinSurface(
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // Scaled from its own top-left, so the corner the offset placed
            // stays where it was put and the panel grows away from it. This is
            // a draw-time transform: nothing inside is measured again, which is
            // what makes a spread smooth and keeps the page in step with the
            // frame around it rather than resizing out from under it.
            .graphicsLayer {
                scaleX = stretch
                scaleY = stretch
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() }),
        corner = 16.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { noteMenu = true }) {
                        Text(
                            title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp),
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "다른 노트")
                    }
                    DropdownMenu(
                        noteMenu,
                        onDismissRequest = {
                            noteMenu = false
                            noteQuery = ""
                        },
                    ) {
                        // A picker that only scrolls is a picker you give up on
                        // once the library is more than a screenful.
                        OutlinedTextField(
                            value = noteQuery,
                            onValueChange = { noteQuery = it },
                            singleLine = true,
                            placeholder = { Text("노트 찾기") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .width(220.dp),
                        )
                        val matches = notes.filter {
                            it.title.contains(noteQuery.trim(), ignoreCase = true)
                        }
                        if (matches.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("결과가 없습니다") },
                                enabled = false,
                                onClick = {},
                            )
                        }
                        for (candidate in matches) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        candidate.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingIcon = {
                                    if (candidate.id == noteId) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    noteMenu = false
                                    noteQuery = ""
                                    if (candidate.id != noteId) onNoteChange(candidate.id)
                                },
                            )
                        }
                    }
                }
                // Page picking, where the note is picked. Two arrows along the
                // bottom edge cost a whole row of a panel that is already small,
                // and stepping one page at a time is not how anybody reaches
                // page forty.
                if (pageCount > 1) {
                    Box {
                        TextButton(onClick = { pageMenu = true }) {
                            Text(
                                "${page + 1}/$pageCount",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        DropdownMenu(pageMenu, onDismissRequest = { pageMenu = false }) {
                            for (index in 0 until pageCount) {
                                DropdownMenuItem(
                                    text = { Text("${index + 1}쪽") },
                                    trailingIcon = {
                                        if (index == page) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        pageMenu = false
                                        onPageChange(index)
                                    },
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { onFit(!fit) }) {
                    Icon(
                        Icons.Default.FitScreen,
                        contentDescription = "크기에 맞추기",
                        tint = if (fit) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "참고 화면 닫기")
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
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
                                pageLoader = { p, epsilon -> store.loadPage(noteId, p, epsilon) }
                                maskLoader = { p, epsilon -> store.loadMasks(noteId, p, epsilon) }
                                imageLoader = { imageId ->
                                    runCatching {
                                        android.graphics.BitmapFactory
                                            .decodeFile(store.imageFile(noteId, imageId).path)
                                    }.getOrNull()
                                }
                                open(ready.first, ready.second)
                                onStrokesChanged = { popupEdits++ }
                                // Already open by definition - three fingers on
                                // this view only ever move or resize it, never
                                // open a reference panel of its own.
                                referenceOpen = true
                                onReferenceDrag = onDrag
                                onReferenceDragEnd = { onStretchEnd(this) }
                                onUndo = { undo(); popupEdits++ }
                                onRedo = { redo(); popupEdits++ }
                                view = this
                            }
                        },
                        update = { v ->
                            v.tool = tool
                            v.colorArgb = if (tool == Tool.MASK) {
                                pen.colorArgb or 0xFF000000.toInt()
                            } else {
                                pen.colorArgb
                            }
                            v.strokeWidth = pen.width
                            v.eraserWidth = eraserWidth
                            v.deferDetail = deferDetail
                        },
                    )
                }
            }
        }
    }
}

/** What you can do with text lifted off a PDF page. */
@Composable
private fun SelectionActions(
    text: String,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onMask: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SkinSurface(
        modifier = modifier
            .windowInsetsPadding(ChromeInsets)
            .padding(20.dp),
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
            TextButton(onClick = onMask) {
                Icon(Icons.Default.VisibilityOff, contentDescription = null)
                Text(" 마스킹")
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
    // Read so that laying tape down or peeling it off redraws the list; the
    // canvas owns the pages and Compose cannot see into them.
    edits: Int,
    onJump: (Int) -> Unit,
    onReveal: (Int, Boolean) -> Unit,
    onClearMasks: (Int) -> Unit,
    onRevealMask: (Int, Int, Boolean) -> Unit,
    onDeleteMask: (Int, Int) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Int) -> Unit,
    onBackground: (Int, PageBackground) -> Unit,
) {
    val pages = document?.pages ?: return
    var tab by remember { mutableIntStateOf(0) }
    SkinSurface(
        modifier = Modifier
            .windowInsetsPadding(ChromeInsets)
            .padding(12.dp)
            // Wide enough for both tab labels on one line; the page column
            // was sized before there were tabs over it.
            .width(190.dp)
            .fillMaxHeight(0.8f),
    ) {
        Column {
            TabRow(
                selectedTabIndex = tab,
                // The panel is the surface. A TabRow paints its own container
                // over it, opaque, which is what made the top of this sidebar
                // the one Material bar left inside a pane of glass.
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("페이지") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("마스킹") },
                )
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pages) { page ->
                    val index = pages.indexOf(page)
                    if (tab == 0) {
                        PageChip(
                            index = index,
                            page = page,
                            selected = index == currentPage,
                            deletable = pages.size > 1,
                            onJump = { onJump(index) },
                            onDelete = { onDelete(index) },
                            onBackground = { onBackground(index, it) },
                        )
                    } else {
                        MaskChip(
                            index = index,
                            page = page,
                            selected = index == currentPage,
                            onJump = { onJump(index) },
                            onReveal = { onReveal(index, it) },
                            onClear = { onClearMasks(index) },
                            onRevealMask = { maskIndex, revealed ->
                                onRevealMask(index, maskIndex, revealed)
                            },
                            onDeleteMask = { maskIndex -> onDeleteMask(index, maskIndex) },
                        )
                    }
                }
            }
            if (tab == 0) {
                TextButton(
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(" 페이지")
                }
            } else {
                // Whole-note switches, which is how a page of covered answers
                // actually gets used: cover everything, then go looking.
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    TextButton(
                        onClick = { pages.indices.forEach { onReveal(it, false) } },
                        modifier = Modifier.weight(1f),
                    ) { Text("모두 가림") }
                    TextButton(
                        onClick = { pages.indices.forEach { onReveal(it, true) } },
                        modifier = Modifier.weight(1f),
                    ) { Text("모두 보임") }
                }
            }
        }
    }
}

/**
 * The body of a chip in a panel. Solid under Material, and under glass a wash
 * that lets the panel through: a white card inside a pane of glass reads as a
 * Material dialog that has been dropped into the wrong app.
 */
@Composable
private fun chipFill(selected: Boolean): Color = when {
    selected -> MaterialTheme.colorScheme.primaryContainer
    LocalSkin.current == Skin.MATERIAL -> MaterialTheme.colorScheme.surfaceContainerLowest
    else -> Color.White.copy(alpha = 0.34f)
}

/**
 * One page's worth of tape: the page itself, whole-page shortcuts, and then
 * every strip on it as its own row - three strips down is three rows, each
 * liftable and deletable on its own rather than only all together.
 */
@Composable
private fun MaskChip(
    index: Int,
    page: Page,
    selected: Boolean,
    onJump: () -> Unit,
    onReveal: (Boolean) -> Unit,
    onClear: () -> Unit,
    onRevealMask: (Int, Boolean) -> Unit,
    onDeleteMask: (Int) -> Unit,
) {
    val masks = page.masks
    val hidden = masks.count { !it.revealed }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(chipFill(selected))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onJump)
                .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${index + 1}", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (masks.isEmpty()) "없음" else "$hidden / ${masks.size} 가림",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (masks.isNotEmpty()) {
                IconButton(onClick = { onReveal(hidden > 0) }) {
                    Icon(
                        if (hidden > 0) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (hidden > 0) "이 페이지 보이기" else "이 페이지 가리기",
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = "이 페이지 마스킹 삭제")
                }
            }
        }
        for ((maskIndex, mask) in masks.withIndex()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "마스크 ${maskIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onRevealMask(maskIndex, !mask.revealed) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (mask.revealed) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (mask.revealed) "이 마스킹 가리기" else "이 마스킹 보이기",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { onDeleteMask(maskIndex) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "이 마스킹 삭제",
                        modifier = Modifier.size(18.dp),
                    )
                }
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
                .background(chipFill(selected))
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
    pen: PenPreset,
    mode: EditMode,
    shapeKind: ShapeKind,
    straightLine: Boolean,
    onToggleStraightLine: () -> Unit,
    skin: Skin,
    onSkin: (Skin) -> Unit,
    docked: Boolean,
    fullscreen: Boolean,
    showLatency: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    pageLabel: String,
    onEditPen: () -> Unit,
    onMode: (EditMode) -> Unit,
    onShape: (ShapeKind) -> Unit,
    onPickImage: () -> Unit,
    onWeb: (String) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFitWidth: () -> Unit,
    /** The zoom as a percentage of fit-to-width, which is what the button resets to. */
    zoomLabel: String,
    onToggleFullscreen: () -> Unit,
    onToggleLatency: () -> Unit,
    onTogglePages: () -> Unit,
    onCollapse: () -> Unit,
    onOpenNote: (NoteMeta) -> Unit,
    onBack: () -> Unit,
    onPalette: () -> Unit,
    onToggleDock: () -> Unit,
    /** Whether the bar has been dragged away from where it starts. */
    canResetBar: Boolean,
    onResetBar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SkinSurface(
        modifier = modifier,
        // Docked, it is part of the window edge, so it takes the edge's corner
        // and its single inner line rather than its own rim all the way round.
        flush = docked,
    ) {
        // Every control one step smaller than the touch-target minimum. The bar
        // is reached with a pen, and its height is page it is not showing.
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
        ) {
        Column(
            Modifier
                // Only the sides the bar actually touches. The full inset set
                // includes the navigation bar, and a bar docked at the top was
                // padding itself away from a navigation bar at the bottom of
                // the screen - which is where the gap under the docked toolbar
                // came from, and why it looked inset on every side at once.
                .then(
                    if (docked) {
                        Modifier.windowInsetsPadding(
                            ChromeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 8.dp, vertical = 1.dp),
        ) {
            // ---- top row: the note, and what is done to the whole of it
            Row(
                // Docked the bar is the window and everything fits. Floating it
                // is capped, and narrower still with the browser panel open -
                // and a Row that cannot scroll does not shrink, it just stops
                // drawing: the last buttons in this row, from full screen to
                // the other notes, were being cut off the end of the bar with
                // no way to reach them. Only the bottom row scrolled.
                if (docked) Modifier else Modifier.horizontalScroll(rememberScrollState()),
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
                    // Docked the bar is the window's width and the title takes
                    // the slack, so the two clusters stay pinned to their ends.
                    // Floating, the bar is only as wide as it needs to be, and a
                    // title that takes the slack has no end to stop at - which
                    // is how a landscape tablet ended up with a 2960px bar and
                    // the tools huddled in the first third of it.
                    modifier = Modifier
                        .then(
                            if (docked) {
                                Modifier.weight(1f)
                            } else {
                                Modifier.widthIn(max = 260.dp)
                            },
                        )
                        .padding(horizontal = 16.dp),
                )

                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "실행취소")
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "다시실행")
                }
                // The reading and the reset are one control: the number tells
                // you where the zoom is, and pressing it puts it back to 100.
                TextButton(onClick = onFitWidth) {
                    Icon(Icons.Default.ZoomOutMap, contentDescription = "화면에 맞추기")
                    Text(" $zoomLabel", style = MaterialTheme.typography.labelMedium)
                }
                SkinButton(skin, onSkin)
                IconButton(onClick = onToggleDock) {
                    Icon(
                        if (docked) {
                            Icons.Default.PictureInPictureAlt
                        } else {
                            Icons.Default.VerticalAlignTop
                        },
                        contentDescription = if (docked) "떼어내기" else "상단 고정",
                    )
                }
                // Only once there is somewhere to come back from. A bar that
                // has never been moved does not need a button for moving it
                // back, and a bar dragged to a corner and left there had no way
                // back at all short of docking it and undocking it again.
                if (canResetBar) {
                    IconButton(onClick = onResetBar) {
                        Icon(
                            Icons.Default.FilterCenterFocus,
                            contentDescription = "도구막대 제자리로",
                        )
                    }
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
                Modifier
                    // More tools than fit beside an open browser panel. Scrolling
                    // beats hiding: every tool stays reachable at any width.
                    .horizontalScroll(rememberScrollState()),
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

                // The tools, in a fixed row. Each keeps its own colour and
                // thickness, so picking one up is the whole of choosing what to
                // write with - there is no tray of pens to curate.
                ToolChip(Icons.Default.Create, "펜", EditMode.PEN, mode, pen, onMode)
                ToolChip(
                    Icons.Default.Highlight,
                    "형광펜",
                    EditMode.HIGHLIGHTER,
                    mode,
                    pen,
                    onMode,
                )
                ToolChip(
                    Icons.Default.VisibilityOff,
                    "마스킹테이프",
                    EditMode.MASK,
                    mode,
                    pen,
                    onMode,
                )
                ToolChip(Icons.Default.Gesture, "올가미", EditMode.LASSO, mode, pen, onMode)
                ShapeButton(mode == EditMode.SHAPE, shapeKind, onShape)
                ToolButton(
                    Icons.Default.AddPhotoAlternate,
                    "사진",
                    mode == EditMode.IMAGE,
                    onClick = onPickImage,
                )
                ToolButton(
                    Icons.Default.Delete,
                    "지우개",
                    mode == EditMode.ERASE,
                ) { onMode(EditMode.ERASE) }
                ToolbarDivider()

                // The colour of whatever is in hand. Tapping it opens the
                // picker; the templates sit next to it.
                if (mode.tints) {
                    PenChip(pen = pen, selected = true, onClick = onEditPen)
                    IconButton(onClick = onPalette) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = "색상 템플릿",
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                    ToolbarDivider()
                }

                // A straight line in the tool's own ink, without switching to
                // the separate shape pen. Only where a wobbly line is worth
                // straightening - a mask covers a printed line, a highlighter
                // underlines one.
                if (mode == EditMode.HIGHLIGHTER || mode == EditMode.MASK) {
                    ToolButton(
                        Icons.Default.Remove,
                        "직선",
                        straightLine,
                        onClick = onToggleStraightLine,
                    )
                    ToolbarDivider()
                }

                // One slider, whichever tool is in hand, because it sets the
                // thickness of that tool and no other. Two sliders would mean
                // one of them is always the wrong one to reach for.
                if (mode != EditMode.LASSO && mode != EditMode.IMAGE) {
                    val range = PenStore.widthRange(mode, pen)
                    SkinSlider(
                        value = pen.width.coerceIn(range),
                        onValueChange = onWidth,
                        valueRange = range,
                        modifier = Modifier.width(SLIDER_TRACK),
                    )
                    ToolbarDivider()
                }

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
    SkinSurface(
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
    /** Overrides the selected tint, so a tool can wear the colour it draws in. */
    tint: Color? = null,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledIconButton(
            onClick = onClick,
            colors = if (tint == null) {
                IconButtonDefaults.filledIconButtonColors()
            } else {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = tint,
                    // A black icon on a black pen is no icon at all.
                    contentColor = if (tint.luminance() < 0.5f) Color.White else Color.Black,
                )
            },
        ) { Icon(icon, contentDescription = label) }
    } else {
        IconButton(
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(
                // The quieter ink, not the line colour: this is an icon, and
                // it follows the colour the settings screen sets for icons.
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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

private const val CRASH_LOG = "crash.log"
private const val CRASH_LOG_MAX = 256L * 1024

private const val IMAGE_CACHE_BYTES = 48 * 1024 * 1024

/** What the tool row needs. Past it a floating bar is empty space. */
private val FLOATING_BAR_MAX = 940.dp
/** Near the reference file's 6.7:1, so the filled part is not lost in the cap. */
private val SLIDER_TRACK = 170.dp
private val WEB_PANEL_WIDTH = 460.dp
private const val WEB_LOG_MAX = 40
private const val WEB_LOG_LINE = 300
private val WEB_PANEL_MIN = 280.dp
private val WEB_PANEL_MAX = 1100.dp

/** Below this the reference panel is too small to hold a readable page. */
private val REFERENCE_MIN_SIZE = 220.dp

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
