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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.produceState
import androidx.compose.material3.Checkbox
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.SuggestionChip
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private var incomingViewerRequest: ViewerRequest? by mutableStateOf(null)
    private var incomingImportedNote: NoteMeta? by mutableStateOf(null)
    private lateinit var noteStore: NoteStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordCrashes()
        // Android 15+ draws behind the system bars whether or not you ask, so
        // opt in properly and let the insets be dispatched instead of guessed.
        enableEdgeToEdge()
        val store = NoteStore(this)
        noteStore = store
        val prefs = PenStore(this)
        ToolbarLayout.load(this)
        val lookStore = SkinSettingsStore(this)
        acceptDocumentIntent(intent)
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
            val darkMode = look.themeMode == AppThemeMode.DARK
            val scheme = remember(look.accent, look.highContrast, darkMode) {
                schemeFrom(look.accent, look.highContrast, darkMode)
            }
            // edge-to-edge 시스템 바의 아이콘도 앱 모드와 동시에 바꿉니다.
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkMode
                    isAppearanceLightNavigationBars = !darkMode
                }
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
                var pickedDocument by remember { mutableStateOf<ViewerRequest?>(null) }
                // A locked note opens only past the master password, once per run of the app.
                val noteLock = remember { NoteLock(this@MainActivity) }
                val unlocked = remember { mutableStateListOf<String>() }
                var unlocking by remember { mutableStateOf<NoteMeta?>(null) }
                fun requestOpen(meta: NoteMeta?) {
                    if (meta != null && store.isLocked(meta.id) && meta.id !in unlocked) unlocking = meta
                    else openNote = meta
                }
                unlocking?.let { target ->
                    UnlockDialog(
                        lock = noteLock,
                        title = target.title,
                        onUnlocked = {
                            unlocked += target.id
                            unlocking = null
                            openNote = target
                        },
                        onDismiss = { unlocking = null },
                    )
                }
                val viewedDocument = incomingViewerRequest ?: pickedDocument
                val note = openNote ?: incomingImportedNote
                if (viewedDocument != null) {
                    ReadOnlyDocumentScreen(
                        request = viewedDocument,
                        onBack = {
                            incomingViewerRequest = null
                            pickedDocument = null
                        },
                        onImport = { title, markdown ->
                            val created = store.createMarkdown(title.ifBlank { "가져온 문서" }, markdown)
                            incomingViewerRequest = null
                            pickedDocument = null
                            openNote = created
                        },
                    )
                } else if (settingsOpen) {
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
                    NoteListScreen(
                        store = store,
                        onSettings = { settingsOpen = true },
                        onOpenDocument = { pickedDocument = it },
                        onOpen = { incomingImportedNote = null; requestOpen(it) },
                        noteLock = noteLock,
                    )
                } else if (note.kind == NoteKind.MARKDOWN) {
                    key(note.id) {
                        MarkdownNoteScreen(
                            store = store,
                            note = note,
                            onBack = { openNote = null; incomingImportedNote = null },
                        )
                    }
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
                        onOpenNote = { requestOpen(it) },
                        onLookChange = { look = it; lookStore.save(it) },
                        onBack = { openNote = null; incomingImportedNote = null },
                    )
                    }
                }
            }
        }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptDocumentIntent(intent)
    }

    private fun acceptDocumentIntent(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val request = viewerRequest(this, uri)
        if (request.name.endsWith(".pdf", true) ||
            contentResolver.getType(uri) == "application/pdf"
        ) {
            lifecycleScope.launch {
                val imported = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        noteStore.createFromPdf(request.name, stream)
                    }
                }
                if (imported != null) {
                    incomingViewerRequest = null
                    incomingImportedNote = imported
                } else incomingViewerRequest = request
            }
        } else if (isSupportedDocumentName(request.name)) incomingViewerRequest = request
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
    onOpenDocument: (ViewerRequest) -> Unit,
    onOpen: (NoteMeta) -> Unit,
    noteLock: NoteLock? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Re-read from disk whenever something changed it, rather than keeping a
    // second copy of the truth in memory and having to hold the two in sync.
    var revision by remember { mutableIntStateOf(0) }
    val notes = remember(revision) { store.list() }
    var pendingDelete by remember { mutableStateOf<NoteMeta?>(null) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var selectionMode by remember { mutableStateOf(false) }
    var bulkDelete by remember { mutableStateOf(false) }
    var bulkFolder by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var templateRevision by remember { mutableIntStateOf(0) }
    val userTemplates = remember(templateRevision) { store.pageTemplates() }
    var importing by remember { mutableStateOf(false) }
    // Which note is being written out, and whether as a PDF rather than a backup.
    var exporting by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var exportDialogFor by remember { mutableStateOf<NoteMeta?>(null) }
    var exportPageOptions by remember { mutableStateOf<PageExportOptions?>(null) }
    var pngJob by remember { mutableStateOf<Pair<String, PageExportOptions>?>(null) }
    var markdownExportId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var importFailed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NoteMeta>?>(null) }
    var pageHits by remember { mutableStateOf<Map<String, List<PageHit>>>(emptyMap()) }
    var showTrash by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    val allTags = remember(revision) { store.allTags() }
    var taggingNote by remember { mutableStateOf<NoteMeta?>(null) }
    var labelingNote by remember { mutableStateOf<NoteMeta?>(null) }
    var lockingNote by remember { mutableStateOf<NoteMeta?>(null) }
    // Which note the image picker, once it comes back, belongs to.
    var thumbnailFor by remember { mutableStateOf<NoteMeta?>(null) }
    val homeStore = remember { PenStore(context) }
    var librarySort by remember { mutableIntStateOf(homeStore.librarySort) }
    var homeRevision by remember { mutableIntStateOf(0) }
    var showHomeBackground by remember { mutableStateOf(false) }
    val homeColor = remember(homeRevision) { homeStore.homeColor }
    val homePhoto = remember(homeRevision) { homeStore.homePhoto }
    if (showHomeBackground) HomeBackgroundDialog(homeStore,
        onChanged = { homeRevision++ }, onDismiss = { showHomeBackground = false })

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        onOpenDocument(viewerRequest(context, uri))
    }

    val importMarkdown = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val title = displayName(context, uri).removeSuffix(".md").removeSuffix(".markdown")
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("파일을 읽을 수 없습니다")
                    store.createMarkdown(title.ifBlank { "Markdown 노트" }, text)
                }.getOrNull()
            }
            if (imported == null) report = "Markdown 파일을 가져오지 못했습니다"
            else { revision++; onOpen(imported) }
        }
    }

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
        val found = withContext(Dispatchers.IO) { store.searchWithPages(query) }
        results = found.map { it.first }
        // A locked note can be found by name, but what is written in it stays behind the lock.
        pageHits = found.filter { it.second.isNotEmpty() && !it.first.locked }.associate { it.first.id to it.second }
    }
    // Blank is the top level. Searching reaches across every folder, because
    // the point of searching is not knowing where a thing is.
    var folder by remember { mutableStateOf("") }
    var filing by remember { mutableStateOf<NoteMeta?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf(false) }
    var deletingFolder by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    val folders = remember(revision) { store.folders() }
    // Favourites first, then the chosen order within each group.
    val shown = (results ?: if (tagFilter != null) notes.filter { tagFilter in it.tags }
        else notes.filter { it.folder == folder }).sortedWith(
        compareByDescending<NoteMeta> { it.favorite }.then(
            when (librarySort) {
                LIBRARY_SORT_TITLE -> compareBy { it.title.lowercase() }
                LIBRARY_SORT_PAGES -> compareByDescending { it.pageCount }
                else -> compareByDescending { it.modified }
            },
        ),
    )

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

    val saveSelectedArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ids = selectedIds.toList()
        busy = "선택한 노트 내보내는 중"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { store.exportArchive(ids, it) } ?: false
            }
            busy = null
            report = if (ok) "노트 ${ids.size}개를 내보냈습니다" else "내보내지 못했습니다"
        }
    }

    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri: Uri? ->
        val target = exporting?.first
        val options = exportPageOptions
        exporting = null
        exportPageOptions = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        busy = "PDF로 그리는 중"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    store.exportPdf(target, it, options)
                } ?: false
            }
            busy = null
            report = if (ok) "PDF를 저장했습니다" else "내보내지 못했습니다"
        }
    }

    fun writePng(uri: Uri?) {
        val job = pngJob
        pngJob = null
        if (uri == null || job == null) return
        busy = "PNG로 그리는 중"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    store.exportPng(job.first, it, job.second)
                } ?: false
            }
            busy = null
            report = if (ok) "PNG를 저장했습니다" else "PNG로 내보내지 못했습니다"
        }
    }
    val addPageTemplate = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    store.addPageTemplate(displayName(context, uri).substringBeforeLast('.'), input)
                }
            }
            if (added == null) report = "템플릿 이미지를 추가하지 못했습니다"
            else templateRevision++
        }
    }
    val savePng = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri -> writePng(uri) }
    val savePngZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> writePng(uri) }

    val saveMarkdown = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri: Uri? ->
        val target = markdownExportId
        markdownExportId = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { store.exportMarkdown(target, it) } ?: false
            }
            report = if (ok) "Markdown 파일을 저장했습니다" else "Markdown으로 내보내지 못했습니다"
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

    // Pictures for a new note: picked, put in order, then one per page.
    var orderingImages by remember { mutableStateOf<List<Uri>?>(null) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) orderingImages = uris
    }
    var askingUrl by remember { mutableStateOf(false) }
    orderingImages?.let { uris ->
        ImageOrderDialog(uris, onDismiss = { orderingImages = null }) { ordered ->
            orderingImages = null
            busy = "이미지로 노트를 만드는 중"
            scope.launch {
                val created = withContext(Dispatchers.IO) {
                    store.createFromImages("이미지 노트", ordered.map { uri -> { context.contentResolver.openInputStream(uri) } })
                }
                busy = null
                revision++
                if (created != null) onOpen(created) else report = "이미지를 읽지 못했습니다"
            }
        }
    }
    if (askingUrl) {
        var address by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askingUrl = false },
            title = { Text("웹페이지로 노트 만들기") },
            text = { OutlinedTextField(address, { address = it.trim() }, label = { Text("https://…") }, singleLine = true) },
            confirmButton = { TextButton(enabled = address.contains('.'), onClick = {
                askingUrl = false
                busy = "웹페이지를 읽는 중"
                scope.launch {
                    val page = withContext(Dispatchers.IO) { fetchWebPage(address) }
                    busy = null
                    if (page == null) { report = "웹페이지를 읽지 못했습니다"; return@launch }
                    val created = withContext(Dispatchers.IO) {
                        store.createMarkdown(page.title.take(80), "# ${page.title}\n\n출처: $address\n\n${page.text}")
                    }
                    revision++
                    onOpen(created)
                }
            }) { Text("만들기") } },
            dismissButton = { TextButton(onClick = { askingUrl = false }) { Text("취소") } },
        )
    }

    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            // Each picked stream is consumed serially off the UI thread. PDFBox
            // is memory hungry, so parsing a batch in parallel would make a
            // large selection less reliable rather than faster.
            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            store.createFromPdf(displayName(context, uri), input)
                        }
                    }.getOrNull()
                }
            }
            importing = false
            revision++
            when {
                imported.isEmpty() -> importFailed = true
                uris.size == 1 -> onOpen(imported.first())
                else -> report = "PDF ${imported.size}개를 가져왔습니다" +
                    if (imported.size < uris.size) " (${uris.size - imported.size}개 실패)" else ""
            }
        }
    }

    // The list screen had no backdrop at all, so glass here was a low alpha over
    // an opaque background - translucent and not frosted, which is the whole of
    // why the skin looked like it had not been applied outside a note.
    val look = LocalSkinSettings.current
    val currentSkin = LocalSkin.current
    val backdrop = rememberBackdrop(
        active = currentSkin == Skin.GLASSMORPHISM &&
            (look.blur > 0.1f || look.vibrancy > 0.01f),
    )
    val liquidBackdrop = if (currentSkin.isRefractive) rememberLiquidGlassBackdrop() else null
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    CompositionLocalProvider(
        LocalBackdrop provides backdrop,
        LocalLiquidGlassBackdrop provides if (currentSkin.isRefractive) liquidBackdrop else null,
    ) {
    Scaffold(
        // Ctrl+N a note, Ctrl+Shift+N a folder, Ctrl+F the search box.
        modifier = Modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action != android.view.KeyEvent.ACTION_DOWN || !(native.isCtrlPressed || native.isMetaPressed)) {
                return@onPreviewKeyEvent false
            }
            when (native.keyCode) {
                android.view.KeyEvent.KEYCODE_N -> { if (native.isShiftPressed) creatingFolder = true else naming = true; true }
                android.view.KeyEvent.KEYCODE_F -> { runCatching { searchFocus.requestFocus() }; true }
                else -> false
            }
        },
        topBar = {
            // Flush to the window edge, and the notes pass underneath it.
            SkinSurface(flush = true) {
            TopAppBar(
                actions = {
                    TextButton(onClick = {
                        selectionMode = !selectionMode
                        selectedIds.clear()
                    }) { Text(if (selectionMode) "선택 종료" else "여러 노트 선택") }
                    IconButton(onClick = { creatingFolder = true }) {
                        Icon(Reicons.Folder, contentDescription = "폴더 만들기")
                    }
                    if (folder.isNotBlank()) Box {
                        IconButton(onClick = { folderMenu = true }) {
                            Icon(Reicons.MoreVert, contentDescription = "폴더 관리")
                        }
                        DropdownMenu(folderMenu, onDismissRequest = { folderMenu = false }) {
                            DropdownMenuItem(text = { Text("폴더 이름 바꾸기") }, onClick = {
                                folderMenu = false; renamingFolder = true
                            })
                            DropdownMenuItem(text = { Text("폴더 삭제") }, onClick = {
                                folderMenu = false; deletingFolder = true
                            })
                        }
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(LibraryIcons.Sort, contentDescription = "정렬")
                        }
                        DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf(
                                LIBRARY_SORT_MODIFIED to "최근 수정 순",
                                LIBRARY_SORT_TITLE to "이름 순",
                                LIBRARY_SORT_PAGES to "쪽수 많은 순",
                            ).forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = if (librarySort == mode) {
                                        { Icon(Reicons.Check, contentDescription = null) }
                                    } else null,
                                    onClick = {
                                        librarySort = mode
                                        homeStore.librarySort = mode
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showTrash = true }) {
                        Icon(Reicons.Delete, contentDescription = "휴지통")
                    }
                    IconButton(onClick = { showHomeBackground = true }) {
                        Icon(Reicons.Image, contentDescription = "노트 목록 배경")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Reicons.Tune, contentDescription = "화면 설정")
                    }
                },
                navigationIcon = {
                    // Only inside a folder: at the top level there is nowhere
                    // to go back to, and an arrow that does nothing is a lie.
                    if (folder.isNotBlank()) {
                        IconButton(onClick = { folder = "" }) {
                            Icon(
                                Reicons.ArrowBack,
                                contentDescription = "전체 노트",
                            )
                        }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (folder.isNotBlank()) {
                            Icon(Reicons.Folder, contentDescription = null)
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
                                Icon(Reicons.Search, contentDescription = null)
                            },
                            modifier = Modifier
                                .focusRequester(searchFocus)
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
                    icon = Reicons.Description,
                    contentDescription = "PDF 가져오기",
                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                )
                GlassFab(
                    onClick = { openDocument.launch(SUPPORTED_DOCUMENT_MIME_TYPES) },
                    icon = Reicons.FolderOpen,
                    contentDescription = "기기·Google Drive·OneDrive 문서 열기",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                GlassFab(
                    onClick = { importMarkdown.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream")) },
                    icon = Reicons.Description,
                    contentDescription = "Markdown 가져오기",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                GlassFab(
                    onClick = { pickImages.launch(arrayOf("image/*")) },
                    icon = Reicons.AddPhotoAlternate,
                    contentDescription = "이미지로 새 노트",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                GlassFab(
                    onClick = { askingUrl = true },
                    icon = Reicons.Language,
                    contentDescription = "웹페이지로 새 노트",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                GlassFab(
                    onClick = { naming = true },
                    icon = Reicons.Add,
                    contentDescription = "새 노트",
                )
            }
        },
    ) { padding ->
        // Nothing inside the recording may sample it; see NoBackdrop.
        CompositionLocalProvider(
            LocalBackdrop provides NoBackdrop,
            LocalLiquidGlassBackdrop provides null,
        ) {
        Box(
            Modifier
                .fillMaxSize()
                .recordBackdrop(backdrop)
                .then(
                    if (currentSkin.isRefractive) {
                        Modifier.captureLiquidGlassBackdrop(requireNotNull(liquidBackdrop))
                    } else {
                        Modifier
                    },
                )
                // Opaque, so the frost replaces the page rather than adding to it.
                .background(homeColor?.let { Color(it) } ?: MaterialTheme.colorScheme.surface),
        ) {
        HomeBackground(homePhoto)
        if (shown.isEmpty() && (folder.isNotBlank() || results != null || folders.isEmpty())) {
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
                if (results == null && folder.isBlank() && folders.isNotEmpty() && tagFilter == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FolderRow(folders) { folder = it }
                    }
                }
                // Tags cut across folders, so picking one shows every note that carries it.
                if (results == null && allTags.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            allTags.forEach { tag ->
                                FilterChip(
                                    selected = tagFilter == tag,
                                    onClick = { tagFilter = if (tagFilter == tag) null else tag },
                                    label = { Text("#$tag") },
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                        }
                    }
                }
                // Where in each note the text was found; opening one goes
                // straight to that page.
                if (results != null && pageHits.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "페이지에서 찾은 곳",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    shown.forEach { note ->
                        pageHits[note.id]?.take(MAX_HITS_PER_NOTE)?.forEach { hit ->
                            item(key = "hit:${note.id}:${hit.pageId}", span = { GridItemSpan(maxLineSpan) }) {
                                SearchHitRow(note, hit) {
                                    homeStore.setLastPage(note.id, hit.pageIndex)
                                    onOpen(note)
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "노트",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                items(shown, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        selected = note.id in selectedIds,
                        selectionMode = selectionMode,
                        hasCustomThumbnail = note.thumbnail?.name == NoteStore.CUSTOM_THUMB,
                        onOpen = {
                            if (selectionMode) {
                                if (note.id in selectedIds) selectedIds.remove(note.id)
                                else selectedIds.add(note.id)
                            } else onOpen(note)
                        },
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
                            exportDialogFor = note
                        },
                        onExportMarkdown = if (note.kind == NoteKind.MARKDOWN) {
                            {
                                markdownExportId = note.id
                                saveMarkdown.launch(safeFileName(note.title) + ".md")
                            }
                        } else null,
                        onFile = { filing = note },
                        onToggleFavorite = {
                            store.setFavorite(note.id, !note.favorite)
                            revision++
                        },
                        onTags = { taggingNote = note },
                        onLabel = { labelingNote = note },
                        onLock = {
                            // Locking with a password already set needs nothing more; the rest asks first.
                            if (!note.locked && noteLock?.hasPassword == true) {
                                store.setLocked(note.id, true)
                                revision++
                            } else lockingNote = note
                        },
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
        if (selectionMode) SkinSurface(
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = padding.calculateBottomPadding() + 16.dp),
            corner = 16.dp,
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selectedIds.size}개 선택", modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = { bulkFolder = true }, enabled = selectedIds.isNotEmpty()) {
                    Text("이동")
                }
                TextButton(onClick = {
                    val ids = selectedIds.filter { id -> notes.any { it.id == id && it.kind == NoteKind.INK } }
                    busy = "노트를 합치는 중"
                    scope.launch {
                        val merged = withContext(Dispatchers.IO) {
                            store.mergeNotes(ids, "합친 노트")
                        }
                        busy = null
                        if (merged == null) report = "노트를 합치지 못했습니다"
                        else {
                            selectedIds.clear(); selectionMode = false; revision++
                            onOpen(merged)
                        }
                    }
                }, enabled = selectedIds.count { id -> notes.any { it.id == id && it.kind == NoteKind.INK } } >= 2) {
                    Text("합치기")
                }
                TextButton(onClick = { saveSelectedArchive.launch("Notesis-선택-백업") }, enabled = selectedIds.isNotEmpty()) {
                    Text("내보내기")
                }
                TextButton(onClick = { bulkDelete = true }, enabled = selectedIds.isNotEmpty()) {
                    Text("삭제")
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

    if (bulkFolder) FolderDialog(
        current = "", folders = folders, onDismiss = { bulkFolder = false },
        onPick = { picked ->
            selectedIds.forEach { store.setFolder(it, picked) }
            selectedIds.clear(); selectionMode = false; bulkFolder = false; revision++
        },
    )
    if (bulkDelete) AlertDialog(
        onDismissRequest = { bulkDelete = false },
        title = { Text("선택한 노트 ${selectedIds.size}개를 휴지통으로 옮길까요?") },
        text = { Text("휴지통에서 ${NoteStore.TRASH_DAYS}일 동안 복원할 수 있습니다.") },
        confirmButton = { TextButton(onClick = {
            selectedIds.forEach(store::moveToTrash)
            selectedIds.clear(); selectionMode = false; bulkDelete = false; revision++
        }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { bulkDelete = false }) { Text("취소") } },
    )

    if (creatingFolder || renamingFolder) {
        val rename = renamingFolder
        FolderNameDialog(
            title = if (rename) "폴더 이름 바꾸기" else "새 폴더",
            initial = if (rename) folder else "",
            onDismiss = { creatingFolder = false; renamingFolder = false },
            onConfirm = { name ->
                val ok = if (rename) store.renameFolder(folder, name) else store.createFolder(name)
                if (ok) {
                    if (rename) folder = name.trim()
                    revision++
                } else report = "폴더 이름을 사용할 수 없습니다"
                creatingFolder = false; renamingFolder = false
            },
        )
    }
    if (deletingFolder) AlertDialog(
        onDismissRequest = { deletingFolder = false },
        title = { Text("폴더 삭제") },
        text = { Text("폴더와 노트를 휴지통으로 옮깁니다. 노트를 복원하면 폴더도 다시 생깁니다.") },
        confirmButton = { TextButton(onClick = {
            store.deleteFolder(folder)
            folder = ""
            revision++
            deletingFolder = false
        }) { Text("휴지통으로") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    store.deleteFolder(folder, keepNotes = true)
                    folder = ""
                    revision++
                    deletingFolder = false
                }) { Text("노트는 남기기") }
                TextButton(onClick = { deletingFolder = false }) { Text("취소") }
            }
        },
    )

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

    exportDialogFor?.let { note ->
        PageExportDialog(
            pageCount = note.pageCount,
            onDismiss = { exportDialogFor = null },
            onExport = { png, options ->
                exportDialogFor = null
                if (png) {
                    pngJob = note.id to options
                    if (options.first == options.last)
                        savePng.launch(safeFileName(note.title) + "-${options.first}.png")
                    else savePngZip.launch(safeFileName(note.title) + "-png.zip")
                } else {
                    exporting = note.id to true
                    exportPageOptions = options
                    savePdf.launch(safeFileName(note.title) + ".pdf")
                }
            },
        )
    }

    if (naming) {
        NameDialog(
            userTemplates = userTemplates,
            onAddTemplate = { addPageTemplate.launch("image/*") },
            onDismiss = { naming = false },
            onConfirm = { title, kind, background, templateId ->
                naming = false
                onOpen(if (kind == NoteKind.MARKDOWN) {
                    store.createMarkdown(title.ifBlank { "제목 없음" }, "# ${title.ifBlank { "제목 없음" }}\n\n")
                } else {
                    store.create(title.ifBlank { "제목 없음" }, background, templateId)
                })
            },
        )
    }

    taggingNote?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.tags.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { taggingNote = null },
            title = { Text("태그") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it },
                        label = { Text("쉼표로 구분") }, singleLine = true)
                    if (allTags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        allTags.forEach { tag ->
                            SuggestionChip(onClick = {
                                val current = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                if (tag !in current) text = (current + tag).joinToString(", ")
                            }, label = { Text("#$tag") }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                store.setTags(target.id, text.split(','))
                taggingNote = null
                revision++
            }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { taggingNote = null }) { Text("취소") } },
        )
    }

    labelingNote?.let { target ->
        AlertDialog(
            onDismissRequest = { labelingNote = null },
            title = { Text("색 라벨") },
            text = {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    (listOf(0) + NOTE_LABELS).forEach { color ->
                        Box(
                            Modifier
                                .padding(4.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (color == 0) MaterialTheme.colorScheme.surfaceVariant else Color(color))
                                .border(if (target.label == color) 3.dp else 1.dp,
                                    MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable {
                                    store.setLabel(target.id, color)
                                    labelingNote = null
                                    revision++
                                },
                            contentAlignment = Alignment.Center,
                        ) { if (color == 0) Text("없음", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { labelingNote = null }) { Text("닫기") } },
        )
    }

    lockingNote?.let { target ->
        val lock = noteLock ?: return@let
        if (target.locked) {
            // Taking a lock off asks for the password, or anyone could.
            UnlockDialog(lock, target.title, onUnlocked = {
                store.setLocked(target.id, false)
                lockingNote = null
                revision++
            }, onDismiss = { lockingNote = null })
        } else if (!lock.hasPassword) {
            SetPasswordDialog(onSet = { password, hint ->
                lock.setPassword(password, hint)
                store.setLocked(target.id, true)
                lockingNote = null
                revision++
            }, onDismiss = { lockingNote = null })
        }
    }

    if (showTrash) TrashDialog(
        store = store,
        onChanged = { revision++ },
        onDismiss = { showTrash = false },
    )

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("노트를 휴지통으로 옮길까요?") },
            text = { Text("\"${note.title}\" 은(는) 휴지통에서 ${NoteStore.TRASH_DAYS}일 동안 복원할 수 있습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.moveToTrash(note.id)
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

private const val PLAYBACK_WINDOW_MS = 2500L
private const val PLAYBACK_TICK_MS = 120L
private const val LIBRARY_SORT_MODIFIED = 0
private const val LIBRARY_SORT_TITLE = 1
private const val LIBRARY_SORT_PAGES = 2
private const val MAX_HITS_PER_NOTE = 5

/** One page a search matched: which note, which page, and the words around the match. */
@Composable
private fun SearchHitRow(note: NoteMeta, hit: PageHit, onOpen: () -> Unit) {
    SkinSurface(corner = 12.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${note.title} · ${hit.pageIndex + 1}쪽",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                hit.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Fast" writing latency: the longest prediction lead offered. */
internal const val FAST_LEAD_MS = 9
internal const val WIDTH_STEP = 0.5f

internal val NOTE_LABELS = listOf(
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF6D4C41.toInt(), 0xFF546E7A.toInt(),
)

/** What fingers do on the page, apart from the pen. */
@Composable
internal fun InputSettingsDialog(gestures: CanvasGestures, onGestures: (CanvasGestures) -> Unit, onDismiss: () -> Unit) {
    @Composable
    fun choices(title: String, options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            options.forEach { (value, label) ->
                SettingsChoiceChip(selected = selected == value, onClick = { onPick(value) }, label = label,
                    modifier = Modifier.padding(end = 6.dp))
            }
        }
    }
    val taps = listOf(TAP_NONE to "없음", TAP_UNDO to "실행 취소", TAP_REDO to "다시 실행")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("손가락·제스처") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices("한 손가락", listOf(FINGER_SCROLL to "스크롤", FINGER_IGNORED to "무시", FINGER_DRAW to "그리기"),
                    gestures.oneFinger) { onGestures(gestures.copy(oneFinger = it)) }
                choices("두 손가락", listOf(TWO_ZOOM_PAN to "확대·이동", TWO_SCROLL to "스크롤", TWO_IGNORED to "무시"),
                    gestures.twoFingers) { onGestures(gestures.copy(twoFingers = it)) }
                choices("두 손가락 두 번 탭", taps, gestures.twoFingerTap) { onGestures(gestures.copy(twoFingerTap = it)) }
                choices("세 손가락 두 번 탭", taps, gestures.threeFingerTap) { onGestures(gestures.copy(threeFingerTap = it)) }
                @Composable
                fun toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        SkinSwitch(checked = on, onCheckedChange = set)
                        Spacer(Modifier.width(10.dp))
                        Text(label)
                    }
                }
                toggle("확대·축소 잠금", gestures.zoomLocked) { onGestures(gestures.copy(zoomLocked = it)) }
                toggle("한 손가락 두 번 탭으로 확대", gestures.doubleTapZoom) { onGestures(gestures.copy(doubleTapZoom = it)) }
                toggle("길게 눌러 메뉴 열기", gestures.longPressMenu) { onGestures(gestures.copy(longPressMenu = it)) }
                toggle("링크 객체에 파란 표시", gestures.linkOverlay) { onGestures(gestures.copy(linkOverlay = it)) }
                toggle("지우개로 지운 뒤 이전 도구로", gestures.eraserReturns) { onGestures(gestures.copy(eraserReturns = it)) }
                choices("펜 버튼 누르고 쓰기", listOf(PEN_BUTTON_ERASE to "지우개", PEN_BUTTON_LASER to "레이저",
                    PEN_BUTTON_LASSO to "올가미"), gestures.penButton) { onGestures(gestures.copy(penButton = it)) }
                Text("손바닥이 닿는다면 한 손가락을 '무시'로, 두 손가락을 '스크롤'로 두세요. 그리기 모드에서는 두 손가락으로 화면을 움직입니다.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** How a new sticky note starts: yellow, rounded, roomy, and square however little is written. */
internal val STICKY_STYLE = TextBoxContent(
    text = "", size = 28f, background = 0xFFFFF59D.toInt(), corner = 10f, padding = 18f, minSize = 260f,
)
internal const val TABLE_CELL_WIDTH = 220f
internal const val TABLE_CELL_HEIGHT = 110f

/** Rows, columns, line colour and an optional header row for a table. */
@Composable
internal fun TableDialog(rows: Int, cols: Int, onDismiss: () -> Unit, onSave: (Int, Int, Int, Int) -> Unit) {
    var r by remember { mutableIntStateOf(rows) }
    var c by remember { mutableIntStateOf(cols) }
    var line by remember { mutableIntStateOf(0xFF616161.toInt()) }
    var header by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("표") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("행 $r", Modifier.width(60.dp))
                    TextButton(onClick = { if (r > 1) r-- }) { Text("−") }
                    TextButton(onClick = { if (r < 30) r++ }) { Text("+") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("열 $c", Modifier.width(60.dp))
                    TextButton(onClick = { if (c > 1) c-- }) { Text("−") }
                    TextButton(onClick = { if (c < 12) c++ }) { Text("+") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkinSwitch(checked = header, onCheckedChange = { header = it })
                    Spacer(Modifier.width(8.dp))
                    Text("첫 행 강조")
                }
                Text("선 색", style = MaterialTheme.typography.bodySmall)
                ColorInput(line) { line = it }
                Text("표 위에 쓰거나 붙인 내용은 표를 옮길 때 함께 움직입니다.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = {
            onSave(r, c, line, if (header) (line and 0x00FFFFFF) or 0x22000000 else 0)
        }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * Where a selection's link goes: a page of this note, a page of another note,
 * or a web address. Saving blank removes the link.
 */
@Composable
internal fun LinkDialog(
    current: String?,
    pageCount: Int,
    notes: List<NoteMeta>,
    pageId: (Int) -> String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableIntStateOf(when {
        current == null || current.startsWith("page:") -> 0
        current.startsWith("note:") -> 1
        else -> 2
    }) }
    var page by remember { mutableStateOf("1") }
    var url by remember { mutableStateOf(current?.takeIf { kind == 2 }.orEmpty()) }
    var other by remember { mutableStateOf(notes.firstOrNull { current?.startsWith("note:${it.id}") == true }) }
    val pageNumber = page.toIntOrNull()
    val target = when (kind) {
        0 -> pageNumber?.takeIf { it in 1..pageCount }?.let { pageId(it - 1) }?.let { "page:$it" }
        1 -> other?.let { "note:${it.id}#${((pageNumber ?: 1) - 1).coerceAtLeast(0)}" }
        else -> url.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("링크") },
        text = {
            Column {
                if (current != null) Text("현재: $current", style = MaterialTheme.typography.bodySmall)
                Row {
                    listOf("이 노트의 쪽", "다른 노트", "웹 주소").forEachIndexed { i, label ->
                        SettingsChoiceChip(selected = kind == i, onClick = { kind = i }, label = label,
                            modifier = Modifier.padding(end = 6.dp))
                    }
                }
                when (kind) {
                    2 -> OutlinedTextField(url, { url = it }, label = { Text("https://…") }, singleLine = true)
                    else -> {
                        if (kind == 1) LazyColumn(Modifier.heightIn(max = 200.dp)) {
                            items(notes, key = { it.id }) { n ->
                                Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (other?.id == n.id) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    modifier = Modifier.fillMaxWidth().clickable { other = n }.padding(8.dp))
                            }
                        }
                        OutlinedTextField(page, { page = it.filter(Char::isDigit).take(5) },
                            label = { Text(if (kind == 0) "쪽 (1–$pageCount)" else "쪽") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(target) }, enabled = target != null) { Text("연결") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("링크 제거") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

/** Objects kept for reuse: tap to paste, hold to tag or remove; tags filter the list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryDialog(store: NoteStore, onPaste: (InkClipboard.Clip) -> Unit, onDismiss: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val items = remember(revision) { store.library() }
    var filter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<NoteStore.LibraryItem?>(null) }
    val tags = items.flatMap { it.tags }.distinct().sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("라이브러리") },
        text = {
            Column {
                if (tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    tags.forEach { tag ->
                        FilterChip(selected = filter == tag, onClick = { filter = if (filter == tag) null else tag },
                            label = { Text("#$tag") }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                if (items.isEmpty()) Text("올가미로 고른 뒤 '라이브러리에 추가'를 누르면 여기에 남습니다.",
                    style = MaterialTheme.typography.bodySmall)
                LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.heightIn(max = 420.dp)) {
                    items(items.filter { filter == null || filter in it.tags }, key = { it.id }) { item ->
                        val preview = remember(item.id) {
                            runCatching { android.graphics.BitmapFactory.decodeFile(item.preview.path) }.getOrNull()
                        }
                        Column(
                            Modifier
                                .padding(4.dp)
                                .combinedClickable(
                                    onClick = { store.libraryClip(item.id)?.let(onPaste) },
                                    onLongClick = { editing = item },
                                ),
                        ) {
                            Box(Modifier.size(88.dp).background(Color.White), contentAlignment = Alignment.Center) {
                                if (preview != null) Image(preview.asImageBitmap(), null, contentScale = ContentScale.Fit)
                            }
                            if (item.tags.isNotEmpty()) Text(item.tags.joinToString(" ") { "#$it" },
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
    editing?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.tags.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("라이브러리 항목") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("태그 (쉼표로 구분)") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                store.setLibraryTags(item.id, text.split(','))
                editing = null
                revision++
            }) { Text("저장") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { store.deleteLibraryItem(item.id); editing = null; revision++ }) { Text("삭제") }
                    TextButton(onClick = { editing = null }) { Text("취소") }
                }
            },
        )
    }
}

/** The master password, typed twice, and a hint for the day it is forgotten. */
@Composable
private fun SetPasswordDialog(onSet: (String, String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    val ok = first.length >= 4 && first == second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("노트 잠금 암호 만들기") },
        text = {
            Column {
                Text("모든 잠긴 노트가 이 암호 하나를 씁니다. 이 기기에만 저장되며 잊으면 복구할 수 없습니다.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(first, { first = it }, label = { Text("암호 (4자 이상)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(second, { second = it }, label = { Text("암호 확인") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = second.isNotEmpty() && second != first)
                OutlinedTextField(hint, { hint = it }, label = { Text("힌트 (선택)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(first, hint.trim()) }, enabled = ok) { Text("잠그기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** Asks for the master password, or the device's fingerprint or face first where there is one. */
@Composable
internal fun UnlockDialog(lock: NoteLock, title: String, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    val activity = LocalContext.current as? android.app.Activity
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (activity != null) lock.askBiometric(activity, "\"$title\" 열기") { passed -> if (passed) onUnlocked() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("잠긴 노트") },
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(password, { password = it; wrong = false }, label = { Text("암호") },
                    singleLine = true, isError = wrong,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (wrong && lock.hint.isNotBlank()) Text("힌트: ${lock.hint}", style = MaterialTheme.typography.bodySmall)
                else if (wrong) Text("암호가 맞지 않습니다", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (lock.check(password)) onUnlocked() else wrong = true },
                enabled = password.isNotEmpty()) { Text("열기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** The picked pictures in the order they will become pages; ▲▼ to change it. */
@Composable
private fun ImageOrderDialog(uris: List<Uri>, onDismiss: () -> Unit, onConfirm: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val order = remember { mutableStateListOf<Uri>().apply { addAll(uris) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이미지 ${order.size}장 순서") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(order.toList(), key = { it.toString() }) { uri ->
                    val at = order.indexOf(uri)
                    val thumb = remember(uri) {
                        runCatching {
                            context.contentResolver.loadThumbnail(uri, android.util.Size(160, 160), null)
                        }.getOrNull()
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${at + 1}", Modifier.width(28.dp))
                        if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.size(56.dp), contentScale = ContentScale.Crop)
                        Spacer(Modifier.weight(1f))
                        TextButton(enabled = at > 0, onClick = { order.add(at - 1, order.removeAt(at)) }) { Text("▲") }
                        TextButton(enabled = at < order.lastIndex, onClick = { order.add(at + 1, order.removeAt(at)) }) { Text("▼") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(order.toList()) }) { Text("만들기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** Deleted notes, restorable until they age out after [NoteStore.TRASH_DAYS] days. */
@Composable
private fun TrashDialog(store: NoteStore, onChanged: () -> Unit, onDismiss: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val trashed = remember(revision) { store.trashed() }
    var confirmEmpty by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("휴지통") },
        text = {
            if (trashed.isEmpty()) {
                Text("비어 있습니다", color = MaterialTheme.colorScheme.outline)
            } else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(trashed, key = { it.id }) { note ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${dateFormat.format(Date(note.trashedAt))}에 삭제 · ${note.pageCount}쪽",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        TextButton(onClick = {
                            store.restoreFromTrash(note.id)
                            revision++
                            onChanged()
                        }) { Text("복원") }
                        TextButton(onClick = {
                            store.delete(note.id)
                            revision++
                        }) { Text("영구 삭제") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = if (trashed.isNotEmpty()) {
            { TextButton(onClick = { confirmEmpty = true }) { Text("비우기") } }
        } else null,
    )
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text("휴지통을 비울까요?") },
        text = { Text("노트 ${trashed.size}개가 완전히 삭제되어 복구할 수 없습니다.") },
        confirmButton = { TextButton(onClick = {
            store.emptyTrash()
            confirmEmpty = false
            revision++
        }) { Text("비우기") } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("취소") } },
    )
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
    selected: Boolean,
    selectionMode: Boolean,
    hasCustomThumbnail: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onPickThumbnail: () -> Unit,
    onClearThumbnail: () -> Unit,
    onExport: () -> Unit,
    onExportPdf: () -> Unit,
    onExportMarkdown: (() -> Unit)?,
    onIndex: () -> Unit,
    onFile: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTags: () -> Unit = {},
    onLabel: () -> Unit = {},
    onLock: () -> Unit = {},
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
    val cardShape = RoundedCornerShape(20.dp)
    Card(
        modifier = if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, cardShape)
            else Modifier,
        onClick = onOpen,
        shape = cardShape,
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
        } else if (skin.isRefractive) {
            // Cards are themselves inside the backdrop recording and must not
            // sample that recording recursively. Asking for a liquid lens here
            // therefore entered its opaque fallback and drew the thick grey
            // frame seen around the title strip.
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            )
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
                if (preview != null && !note.locked) {
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
                        Reicons.Edit,
                        contentDescription = null,
                        tint = Color(0x22000000),
                        modifier = Modifier.size(40.dp),
                    )
                }
                if (note.favorite) Icon(
                    LibraryIcons.Star,
                    contentDescription = "즐겨찾기",
                    tint = Color(0xFFF5B400),
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                )
                if (note.locked) Icon(
                    Icons.Filled.Lock,
                    contentDescription = "잠긴 노트",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(36.dp),
                )
                if (note.label != 0) Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(Color(note.label)),
                )
                if (selectionMode) Icon(
                    if (selected) Reicons.Check else Reicons.Circle,
                    contentDescription = if (selected) "선택됨" else "선택 안 됨",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
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
                        if (note.kind == NoteKind.MARKDOWN) "${note.title} · MD" else note.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${dateFormat.format(Date(note.modified))} · ${note.pageCount}쪽",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (note.tags.isNotEmpty()) Text(
                        note.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Reicons.MoreVert,
                            contentDescription = "더보기",
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("썸네일 설정") },
                            leadingIcon = { Icon(Reicons.Image, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onPickThumbnail()
                            },
                        )
                        if (onExportMarkdown != null) {
                            DropdownMenuItem(
                                text = { Text(".md 파일로 내보내기") },
                                leadingIcon = { Icon(Reicons.Description, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onExportMarkdown()
                                },
                            )
                        }
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
                            text = { Text(if (note.favorite) "즐겨찾기 해제" else "즐겨찾기") },
                            leadingIcon = { Icon(LibraryIcons.Star, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onToggleFavorite()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("태그") },
                            onClick = { menuOpen = false; onTags() },
                        )
                        DropdownMenuItem(
                            text = { Text("색 라벨") },
                            leadingIcon = { Icon(Reicons.Palette, contentDescription = null) },
                            onClick = { menuOpen = false; onLabel() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (note.locked) "잠금 해제" else "암호로 잠그기") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            onClick = { menuOpen = false; onLock() },
                        )
                        DropdownMenuItem(
                            text = { Text("폴더로 이동") },
                            leadingIcon = {
                                Icon(Reicons.FolderOpen, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onFile()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("필기 검색 색인") },
                            leadingIcon = {
                                Icon(Reicons.Search, contentDescription = null)
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
                                Icon(Reicons.Archive, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("PDF로 내보내기") },
                            leadingIcon = {
                                Icon(Reicons.PictureAsPdf, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onExportPdf()
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("삭제") },
                            leadingIcon = { Icon(Reicons.Delete, contentDescription = null) },
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
            Icon(Reicons.AutoAwesomeMosaic, contentDescription = "테마")
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
                            Icon(Reicons.Check, contentDescription = null)
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
                    Icon(Reicons.Folder, contentDescription = null)
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
    if (LocalSkin.current.isRefractive) {
        LiquidGlassButton(
            onClick = onClick,
            modifier = modifier.size(side),
            contentPadding = PaddingValues(0.dp),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            drawBorder = false,
            glassShadowElevation = 3.dp,
        ) {
            Icon(icon, contentDescription = contentDescription)
        }
    } else {
        SkinSurface(modifier = modifier.size(side), corner = 16.dp) {
            // Clickable inside the surface, so the ripple is clipped to the corner.
            Box(
                Modifier.fillMaxSize().clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = contentDescription)
            }
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
            icon = Reicons.Archive,
            contentDescription = "백업",
            small = true,
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("전체 백업") },
                leadingIcon = { Icon(Reicons.Archive, contentDescription = null) },
                onClick = {
                    open = false
                    onBackupAll()
                },
            )
            DropdownMenuItem(
                text = { Text("백업에서 복원") },
                leadingIcon = { Icon(Reicons.Restore, contentDescription = null) },
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
internal fun PenDialog(
    mode: EditMode,
    pen: PenPreset,
    /** Global rather than per tool, but this is where a hand is being set up. */
    prediction: Boolean,
    onPrediction: (Boolean) -> Unit,
    predictionLeadMs: Int,
    onPredictionLeadMs: (Int) -> Unit,
    deferDetail: Boolean,
    onDeferDetail: (Boolean) -> Unit,
    stabilizer: Int,
    onStabilizer: (Int) -> Unit,
    highlighterAboveInk: Boolean,
    onHighlighterAboveInk: (Boolean) -> Unit,
    autoShapes: Boolean,
    onAutoShapes: (Boolean) -> Unit,
    axisSnap: Boolean,
    onAxisSnap: (Boolean) -> Unit,
    dottedPattern: Int,
    onDottedPattern: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (PenPreset) -> Unit,
    meshInk: Boolean = true,
    onMeshInk: (Boolean) -> Unit = {},
    partialEraser: Boolean = false,
    onPartialEraser: (Boolean) -> Unit = {},
    compatWetInk: Boolean = false,
    onCompatWetInk: (Boolean) -> Unit = {},
    gestures: CanvasGestures = CanvasGestures(),
    onGestures: (CanvasGestures) -> Unit = {},
    onClearPage: (() -> Unit)? = null,
    onEyedropper: (() -> Unit)? = null,
    onCornerRadius: (Float) -> Unit = {},
) {
    val context = LocalContext.current
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
    var nib by remember(pen) { mutableStateOf(start.nib) }
    var maxWidth by remember(pen) { mutableFloatStateOf(start.maxWidth) }
    var paletteOpen by remember { mutableStateOf(false) }
    val range = PenStore.widthRange(mode, start.copy(maxWidth = maxWidth))

    val picked = Color.hsv(hue, saturation, value, alpha)
    val argb = picked.toArgb()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(toolLabel(mode)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (mode == EditMode.ERASE) {
                    Row {
                        SettingsChoiceChip(selected = !partialEraser,
                            onClick = { onPartialEraser(false) }, label = "획 지우개",
                            modifier = Modifier.padding(end = 8.dp))
                        SettingsChoiceChip(selected = partialEraser,
                            onClick = { onPartialEraser(true) }, label = "부분 지우개")
                    }
                    Text(if (partialEraser) "지나간 부분만 지웁니다" else "닿은 획을 통째로 지웁니다",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Text("지울 대상", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        SettingsChoiceChip(selected = gestures.eraseInk,
                            onClick = { onGestures(gestures.copy(eraseInk = !gestures.eraseInk)) },
                            label = "펜", modifier = Modifier.padding(end = 8.dp))
                        SettingsChoiceChip(selected = gestures.eraseHighlighter,
                            onClick = { onGestures(gestures.copy(eraseHighlighter = !gestures.eraseHighlighter)) },
                            label = "형광펜", modifier = Modifier.padding(end = 8.dp))
                        SettingsChoiceChip(selected = gestures.eraseTape,
                            onClick = { onGestures(gestures.copy(eraseTape = !gestures.eraseTape)) },
                            label = "테이프")
                    }
                    Row(Modifier.padding(top = 6.dp)) {
                        SettingsChoiceChip(selected = gestures.eraseImages,
                            onClick = { onGestures(gestures.copy(eraseImages = !gestures.eraseImages)) },
                            label = "이미지", modifier = Modifier.padding(end = 8.dp))
                        SettingsChoiceChip(selected = gestures.eraseText,
                            onClick = { onGestures(gestures.copy(eraseText = !gestures.eraseText)) },
                            label = "텍스트 상자", modifier = Modifier.padding(end = 8.dp))
                        SettingsChoiceChip(selected = gestures.eraseLocked,
                            onClick = { onGestures(gestures.copy(eraseLocked = !gestures.eraseLocked)) },
                            label = "잠긴 객체")
                    }
                    if (onClearPage != null) {
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = onClearPage) {
                            Icon(Reicons.Delete, contentDescription = null)
                            Text(" 현재 페이지 모두 지우기")
                        }
                    }
                }
                if (mode.tints) {
                    Row {
                        TextButton(onClick = { paletteOpen = true }) { Text("색 팔레트") }
                        if (onEyedropper != null) TextButton(onClick = onEyedropper) { Text("스포이드") }
                    }
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

                }
                if (mode == EditMode.PEN) {
                    Spacer(Modifier.height(10.dp))
                    Text("펜 종류", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        PEN_NIBS.forEach { (tool, label) ->
                            SettingsChoiceChip(selected = nib == tool, onClick = {
                                nib = tool
                                // Watercolour is a wash: it starts see-through.
                                if (tool == Tool.WATERCOLOR && alpha > 0.6f) alpha = 0.45f
                            }, label = label, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                }
                if (mode == EditMode.PEN || mode == EditMode.PENCIL) {
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
                    Spacer(Modifier.height(10.dp))
                    Text("획 보정 $stabilizer%", style = MaterialTheme.typography.bodyMedium)
                    SkinSlider(
                        value = stabilizer.toFloat(),
                        onValueChange = { onStabilizer(it.roundToInt()) },
                        valueRange = 0f..100f,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkinSwitch(checked = autoShapes, onCheckedChange = onAutoShapes)
                        Spacer(Modifier.width(10.dp))
                        Text("자동 도형 인식")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkinSwitch(checked = gestures.holdToDraw,
                            onCheckedChange = { onGestures(gestures.copy(holdToDraw = it)) })
                        Spacer(Modifier.width(10.dp))
                        Text("끝에서 멈추면 도형으로")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkinSwitch(checked = gestures.scribbleErase,
                            onCheckedChange = { onGestures(gestures.copy(scribbleErase = it)) })
                        Spacer(Modifier.width(10.dp))
                        Text("낙서로 지우기")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkinSwitch(checked = gestures.circleToLasso,
                            onCheckedChange = { onGestures(gestures.copy(circleToLasso = it)) })
                        Spacer(Modifier.width(10.dp))
                        Text("원을 그리고 멈추면 선택")
                    }
                    Text("선 스타일", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("실선", "점선", "파선", "일점쇄선").forEachIndexed { index, label ->
                            SettingsChoiceChip(
                                selected = dottedPattern == index,
                                onClick = { onDottedPattern(index) },
                                label = label,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                }
                if (mode == EditMode.SHAPE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkinSwitch(checked = axisSnap, onCheckedChange = onAxisSnap)
                        Spacer(Modifier.width(10.dp))
                        Text("직선을 90° 간격으로 보정")
                    }
                    val shapeStore = remember { PenStore(context) }
                    var corner by remember { mutableFloatStateOf(shapeStore.shapeCornerRadius) }
                    Text("다각형 모서리 둥글기 ${corner.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
                    SkinSlider(corner, { corner = it; shapeStore.shapeCornerRadius = it; onCornerRadius(it) }, 0f..120f)
                }
                if (mode == EditMode.HIGHLIGHTER) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkinSwitch(checked = highlighterAboveInk,
                            onCheckedChange = onHighlighterAboveInk)
                        Spacer(Modifier.width(10.dp))
                        Text(if (highlighterAboveInk) "필기 위에 표시" else "필기 아래에 표시")
                    }
                }
                if (mode == EditMode.PEN || mode == EditMode.PENCIL) {
                  Spacer(Modifier.height(10.dp))
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    SkinSwitch(checked = meshInk, onCheckedChange = onMeshInk)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("메시 렌더링 (실험)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "불투명한 펜·필압 펜을 Ink 셰이더로 그립니다. 끄면 이전 방식(경로 채우기)으로 돌아갑니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                  }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkinSwitch(checked = compatWetInk, onCheckedChange = onCompatWetInk)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("호환 필기 표시", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "쓰는 동안 획이 보이지 않고 펜을 떼야 나타나면 켜세요. Galaxy Tab S6 Lite에서는 기본으로 켜집니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // The same two settings below, as the three modes people ask for by name.
                Text("필기 지연", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    SettingsChoiceChip(selected = !prediction, onClick = { onPrediction(false) }, label = "끄기",
                        modifier = Modifier.padding(end = 6.dp))
                    SettingsChoiceChip(selected = prediction && predictionLeadMs == 0,
                        onClick = { onPrediction(true); onPredictionLeadMs(0) }, label = "표준",
                        modifier = Modifier.padding(end = 6.dp))
                    SettingsChoiceChip(selected = prediction && predictionLeadMs == FAST_LEAD_MS,
                        onClick = { onPrediction(true); onPredictionLeadMs(FAST_LEAD_MS) }, label = "빠름 · 실험적")
                }
                if (prediction && predictionLeadMs == FAST_LEAD_MS) Text(
                    "빠름은 일부 기기에서 획 끝이 흔들리거나 불안정할 수 있습니다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
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
                if (prediction) {
                    Text("예측 거리", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(0 to "자동", 4 to "4 ms", 6 to "6 ms", 9 to "9 ms")
                            .forEach { (lead, label) ->
                                SettingsChoiceChip(
                                    selected = predictionLeadMs == lead,
                                    onClick = { onPredictionLeadMs(lead) },
                                    label = label,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                    }
                    Text(
                        "자동은 60Hz에서 9ms, 90Hz 이상에서 6ms를 상한으로 사용합니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
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
                        TextButton(modifier = Modifier.settingsPressHighlight(), onClick = { maxWidth = ceiling; width = width.coerceAtMost(ceiling) }) {
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
                WidthControls(mode, width) {
                    width = it
                    if (it > range.endInclusive) maxWidth = it
                }
                Row {
                    TextButton(onClick = { width = (width - WIDTH_STEP).coerceIn(range) }) { Text("−") }
                    Text(widthLabel(width.coerceIn(range)), Modifier.align(Alignment.CenterVertically))
                    TextButton(onClick = { width = (width + WIDTH_STEP).coerceIn(range) }) { Text("+") }
                }
                SkinSlider(
                    value = width.coerceIn(range),
                    onValueChange = { width = it },
                    valueRange = range,
                )
                if (mode == EditMode.ERASE) {
                    // The eraser's reach, at the same scale as the pen preview.
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(width.dp.coerceIn(4.dp, 60.dp))
                                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                    }
                }
                if (mode.tints) {
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
                            nib,
                        ),
                    )
                },
            ) {
                Text("저장")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
    if (paletteOpen) {
        PaletteDialog(onDismiss = { paletteOpen = false }, onPick = { rgb ->
            val next = FloatArray(3)
            android.graphics.Color.colorToHSV(rgb, next)
            hue = next[0]; saturation = next[1]; value = next[2]
            if (android.graphics.Color.alpha(rgb) < 255) alpha = android.graphics.Color.alpha(rgb) / 255f
            paletteOpen = false
        })
    }

}

private fun toolLabel(mode: EditMode): String = when (mode) {
    EditMode.PEN -> "펜"
    EditMode.PENCIL -> "연필"
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
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    emitSv(down.position, size.width, size.height, currentOnChange)
                    drag(down.id) { change ->
                        change.consume()
                        emitSv(change.position, size.width, size.height, currentOnChange)
                    }
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
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    currentOnChange((down.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                    drag(down.id) { change ->
                        change.consume()
                        currentOnChange((change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                    }
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
    ShapeKind.LINE -> Reicons.Remove
    ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW -> Reicons.TrendingUp
    ShapeKind.RECT, ShapeKind.CUBE -> Reicons.CropSquare
    ShapeKind.OVAL, ShapeKind.CYLINDER -> Reicons.Circle
    else -> Reicons.Category
}

private fun shapeLabel(kind: ShapeKind): String = when (kind) {
    ShapeKind.LINE -> "직선"
    ShapeKind.ARROW -> "화살표"
    ShapeKind.DOUBLE_ARROW -> "양방향 화살표"
    ShapeKind.RECT -> "사각형"
    ShapeKind.OVAL -> "원"
    ShapeKind.TRIANGLE -> "삼각형"
    ShapeKind.RIGHT_TRIANGLE -> "직각삼각형"
    ShapeKind.DIAMOND -> "마름모"
    ShapeKind.PARALLELOGRAM -> "평행사변형"
    ShapeKind.TRAPEZOID -> "사다리꼴"
    ShapeKind.PENTAGON -> "오각형"
    ShapeKind.HEXAGON -> "육각형"
    ShapeKind.STAR -> "별"
    ShapeKind.HEART -> "하트"
    ShapeKind.ARC -> "호"
    ShapeKind.SECTOR -> "부채꼴"
    ShapeKind.SINE -> "사인 곡선"
    ShapeKind.AXES -> "좌표축"
    ShapeKind.BRACE -> "중괄호"
    ShapeKind.BUBBLE -> "말풍선"
    ShapeKind.CUBE -> "정육면체"
    ShapeKind.CYLINDER -> "원기둥"
    ShapeKind.CURVE -> "곡선"
    ShapeKind.POLYGON -> "다각형 · 연속 직선"
}

/** Opens one of the AI sites in the side panel. */
@Composable
private fun AiButton(onWeb: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Reicons.AutoAwesome, contentDescription = "AI")
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
 * The bar's own swatches: tap one to write in it, tap the one in use for the
 * full settings, + keeps the colour in hand, hold one to move or drop it.
 */
@Composable
private fun QuickColors(current: Int, onPick: (Int) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    var colors by remember { mutableStateOf(store.quickColors) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val currentRgb = current and 0xFFFFFF
    fun save(next: List<Int>) { colors = next; store.quickColors = next }
    Row(verticalAlignment = Alignment.CenterVertically) {
        colors.forEachIndexed { index, rgb ->
            val chosen = rgb and 0xFFFFFF == currentRgb
            Box {
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(rgb or 0xFF000000.toInt()))
                        .border(if (chosen) 3.dp else 1.dp,
                            if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape)
                        .semantics { contentDescription = "빠른 색상 #%06X".format(rgb and 0xFFFFFF) }
                        .combinedClickable(
                            onClick = { if (chosen) onSettings() else onPick(rgb) },
                            onLongClick = { menuFor = index },
                        ),
                )
                DropdownMenu(menuFor == index, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("앞으로") }, enabled = index > 0, onClick = {
                        save(colors.toMutableList().apply { add(index - 1, removeAt(index)) }); menuFor = null
                    })
                    DropdownMenuItem(text = { Text("뒤로") }, enabled = index < colors.lastIndex, onClick = {
                        save(colors.toMutableList().apply { add(index + 1, removeAt(index)) }); menuFor = null
                    })
                    DropdownMenuItem(text = { Text("삭제") }, onClick = {
                        save(colors.filterIndexed { i, _ -> i != index }); menuFor = null
                    })
                }
            }
        }
        if (colors.none { it and 0xFFFFFF == currentRgb } && colors.size < PenStore.MAX_QUICK_COLORS) {
            TextButton(onClick = { save(colors + (currentRgb or 0xFF000000.toInt())) },
                contentPadding = PaddingValues(horizontal = 4.dp)) { Text("+") }
        }
    }
}

/**
 * Ready-made colour sets. Picking one recolours the pen in hand and leaves its
 * alpha alone, so a highlighter stays a highlighter and a pen stays opaque.
 */
@Composable
private fun PaletteDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    var custom by remember { mutableStateOf(store.colorTemplates()) }
    var adding by remember { mutableStateOf(false) }
    if (adding) TemplateEditor(onDismiss = { adding = false }, onSave = { name, colors ->
        custom = custom + (name to colors)
        store.saveColorTemplates(custom)
        adding = false
    })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("색상 템플릿") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((name, colors) in COLOR_TEMPLATES + custom) {
                    Text(name, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (rgb in colors) {
                            Box(
                                Modifier
                                    .padding(end = 8.dp)
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(rgb))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .semantics { contentDescription = "색상 #%06X".format(rgb and 0xFFFFFF) }
                                    .clickable { onPick(rgb) },
                            )
                        }
                    }
                    if ((name to colors) in custom) TextButton(onClick = {
                        custom = custom - (name to colors)
                        store.saveColorTemplates(custom)
                    }) { Text("템플릿 삭제") }
                    Spacer(Modifier.height(14.dp))
                }
            }
        },
        dismissButton = { TextButton(onClick = { adding = true }) { Text("+ 템플릿 추가") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/**
 * Hands selected text to other apps: ACTION_PROCESS_TEXT reaches translators
 * and dictionaries, ACTION_SEND the share sheet. Nothing installed, nothing happens.
 */
internal fun sendSelectedText(context: android.content.Context, text: String, action: String) {
    val intent = android.content.Intent(action).apply {
        type = "text/plain"
        if (action == android.content.Intent.ACTION_PROCESS_TEXT) {
            putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        } else putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(android.content.Intent.createChooser(intent, null)) }
        .onFailure { Toast.makeText(context, "열 수 있는 앱이 없습니다", Toast.LENGTH_SHORT).show() }
}

/** What came back from a capture, and the two things worth doing with it. */
@Composable
private fun CaptureDialog(
    bitmap: Bitmap,
    onPaste: () -> Unit,
    onAttach: () -> Unit,
    onSave: () -> Unit,
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
                TextButton(onClick = onSave) { Text("PNG 저장") }
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
    rendererGeneration: Int,
    onRendererGone: (android.webkit.WebView) -> Unit,
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
    val context = LocalContext.current
    val userAgent = remember(context, desktop) {
        uaFor(android.webkit.WebSettings.getDefaultUserAgent(context), desktop)
    }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    DisposableEffect(web) {
        web?.onResume()
        onDispose { if (holder.value === web) web?.onPause() }
    }

    SkinSurface(modifier = modifier, corner = 0.dp) {
        // The whole panel keeps clear of the system bars, not just its header: a
        // chat composer pinned to the bottom of the page was sitting under the
        // navigation bar with nothing holding it up.
        Column(
            Modifier
                .fillMaxHeight()
                // 시스템 바 inset도 패널의 실제 표면색으로 칠해 상단 회색 띠를 없앱니다.
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(ChromeInsets)
                .imePadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { web?.let { if (it.canGoBack()) it.goBack() } }) {
                    Icon(Reicons.ArrowBack, contentDescription = "뒤로")
                }
                IconButton(onClick = { web?.reload() }) {
                    Icon(Reicons.Refresh, contentDescription = "새로고침")
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
                        Reicons.BugReport,
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
                        if (desktop) Reicons.Computer else Reicons.PhoneAndroid,
                        contentDescription = if (desktop) "PC 화면" else "모바일 화면",
                    )
                }
                IconButton(onClick = { openExternally(web?.context, web?.url ?: url) }) {
                    Icon(Reicons.Launch, contentDescription = "브라우저로 열기")
                }
                IconButton(onClick = onTogglePopup) {
                    Icon(
                        if (popup) Reicons.CloseFullscreen else Reicons.OpenInFull,
                        contentDescription = if (popup) "붙이기" else "팝업",
                    )
                }
                IconButton(onClick = { web?.clearFocus(); keyboard?.hide(); onClose() }) {
                    Icon(Reicons.Close, contentDescription = "닫기")
                }
            }
            key(rendererGeneration) { AndroidView(
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
                        newBrowser(viewContext, onFile, log, onRendererGone).also { holder.value = it }
                    }
                },
                // The tag remembers which site was asked for, so picking another
                // one loads it while a stroke on the note next door does not.
                update = { view ->
                    val wanted = userAgent
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
            ) }
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
    onRendererGone: (android.webkit.WebView) -> Unit,
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
    android.webkit.WebView.setWebContentsDebuggingEnabled(
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)

    fun note(line: String) {
        if (log.lastOrNull() == line) return
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
                onRendererGone(view)
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
        setRendererPriorityPolicy(android.webkit.WebView.RENDERER_PRIORITY_BOUND, true)
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
private fun PageExportDialog(
    pageCount: Int,
    onDismiss: () -> Unit,
    onExport: (Boolean, PageExportOptions) -> Unit,
) {
    var first by remember { mutableStateOf("1") }
    var last by remember(pageCount) { mutableStateOf(pageCount.toString()) }
    var png by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(ExportPageSize.ORIGINAL) }
    var rotation by remember { mutableIntStateOf(0) }
    var raster by remember { mutableStateOf(false) }
    var invert by remember { mutableStateOf(false) }
    val from = first.toIntOrNull()
    val to = last.toIntOrNull()
    val valid = from != null && to != null && from >= 1 && to >= from && to <= pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("페이지 내보내기") },
        text = { Column {
            Text("형식")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !png, onClick = { png = false }, label = { Text("PDF") })
                FilterChip(selected = png, onClick = { png = true }, label = { Text("PNG") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(first, { first = it.filter(Char::isDigit) },
                    label = { Text("시작 쪽") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(last, { last = it.filter(Char::isDigit) },
                    label = { Text("끝 쪽") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("크기")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    ExportPageSize.ORIGINAL to "원본", ExportPageSize.A4 to "A4",
                    ExportPageSize.LETTER to "Letter",
                ).forEach { (value, label) ->
                    FilterChip(selected = size == value, onClick = { size = value },
                        label = { Text(label) })
                }
            }
            Text("회전")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 90, 180, 270).forEach { value ->
                    FilterChip(selected = rotation == value, onClick = { rotation = value },
                        label = { Text("${value}°") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!png) FilterChip(selected = raster, onClick = { raster = !raster }, label = { Text("이미지 PDF") })
                FilterChip(selected = invert, onClick = { invert = !invert }, label = { Text("색 반전") })
            }
            if (png && valid && from != to) Text("여러 PNG는 ZIP 파일로 저장됩니다.",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = valid, onClick = {
            onExport(png, PageExportOptions(from!!, to!!, size, rotation, raster = raster && !png, invert = invert))
        }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun NameDialog(
    userTemplates: List<UserPageTemplate>,
    onAddTemplate: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, NoteKind, PageBackground, String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(NoteKind.INK) }
    var background by remember { mutableStateOf(PageBackground.BLANK) }
    var templateId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 노트") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("이름") },
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = kind == NoteKind.INK, onClick = { kind = NoteKind.INK }, label = { Text("필기 노트") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = kind == NoteKind.MARKDOWN, onClick = { kind = NoteKind.MARKDOWN }, label = { Text("Markdown (.md)") })
                }
                Text(
                    if (kind == NoteKind.MARKDOWN) "Obsidian에서 열 수 있는 note.md 파일로 저장됩니다." else "펜과 텍스트 상자를 사용하는 종이 노트입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (kind == NoteKind.INK) {
                    Spacer(Modifier.height(12.dp))
                    Text("첫 페이지 템플릿", style = MaterialTheme.typography.labelMedium)
                    val labels = listOf(
                        PageBackground.BLANK to "무지", PageBackground.LINED to "줄",
                        PageBackground.NARROW_LINED to "좁은 줄", PageBackground.GRID to "모눈",
                        PageBackground.DOT to "도트", PageBackground.CORNELL to "코넬",
                        PageBackground.INFINITE to "무한 노트",
                    )
                    Column {
                        labels.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { (choice, label) ->
                                    FilterChip(
                                        selected = background == choice && templateId == null,
                                        onClick = { background = choice; templateId = null },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                    }
                    if (userTemplates.isNotEmpty()) {
                        Text("사용자 템플릿", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            userTemplates.forEach { template ->
                                FilterChip(
                                    selected = templateId == template.id,
                                    onClick = {
                                        templateId = template.id
                                        background = PageBackground.CUSTOM
                                    },
                                    label = { Text(template.name) },
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                    }
                    TextButton(onClick = onAddTemplate) { Text("+ 이미지 템플릿 추가") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(title, kind, background, templateId) }) { Text("만들기") } },
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
enum class EditMode { PEN, HIGHLIGHTER, MASK, LASSO, SHAPE, IMAGE, ERASE, READ, CAPTURE, TEXT, PENCIL }

/** Whether this mode puts something on the page in the tool's own colour. */
private val EditMode.tints: Boolean
    get() = this == EditMode.PEN || this == EditMode.PENCIL || this == EditMode.HIGHLIGHTER ||
        this == EditMode.MASK || this == EditMode.SHAPE

@Composable
private fun LatencyHud(report: () -> String, modifier: Modifier = Modifier) {
    val latestReport by rememberUpdatedState(report)
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            text = latestReport()
            delay(500)
        }
    }
    Text(text, fontSize = 12.sp, color = Color(0xFF555555), modifier = modifier)
}

@Composable
private fun NoteScreen(
    store: NoteStore,
    note: NoteMeta,
    skin: Skin,
    onSkin: (Skin) -> Unit,
    onOpenNote: (NoteMeta) -> Unit,
    onLookChange: (SkinSettings) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val scope = rememberCoroutineScope()
    var showVoice by remember { mutableStateOf(false) }
    var showInput by remember { mutableStateOf(false) }
    var shapeCorner by remember { mutableFloatStateOf(PenStore(context).shapeCornerRadius) }
    /** The last tool that was not the eraser, for going back to after an erase. */
    var beforeEraser by remember { mutableStateOf(EditMode.PEN) }
    var showLibrary by remember { mutableStateOf(false) }
    var editingTools by remember { mutableStateOf(false) }
    var exportingPages by remember { mutableStateOf<List<Page>?>(null) }
    /** Pages picked to go to another note, and whether they leave this one. */
    var sendingPages by remember { mutableStateOf<Pair<List<Int>, Boolean>?>(null) }
    val savePagesPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri: Uri? ->
        val pages = exportingPages
        exportingPages = null
        if (uri == null || pages == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    writeVectorPdf(context, store.pdfFile(note.id),
                        pages.map { VectorPdfPage(it, it.strokes, it.masks.map { m -> m.stroke }) },
                        { imageId -> store.imageFile(note.id, imageId) }, out)
                } ?: false
            }
            Toast.makeText(context, if (ok) "PDF를 저장했습니다" else "내보내지 못했습니다", Toast.LENGTH_SHORT).show()
        }
    }
    // The PDF's own contents, read once off the main thread when the note opens.
    val pdfOutline by produceState(emptyList<NoteStore.PdfOutlineEntry>(), note.id) {
        value = withContext(Dispatchers.IO) { store.pdfOutline(note.id) }
    }
    var linking by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    /** Where a finger was held on the page, in screen pixels, while its menu is up. */
    var pressMenu by remember { mutableStateOf<Offset?>(null) }
    var playNextRecording by remember { mutableStateOf<String?>(null) }
    var voiceRevision by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<java.io.File?>(null) }
    var voicePlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playingFile by remember { mutableStateOf<String?>(null) }
    // When each stroke landed while recording, saved beside the audio.
    var recordingStartedAt by remember { mutableStateOf(0L) }
    val recordingEvents = remember { mutableListOf<RecordingTimeline.Event>() }
    var playingTimeline by remember { mutableStateOf<List<RecordingTimeline.Event>>(emptyList()) }
    fun stopRecording() {
        val active = recorder ?: return
        val valid = runCatching { active.stop() }.isSuccess
        active.release()
        recorder = null
        if (!valid) recordingFile?.delete()
        else recordingFile?.let { RecordingTimeline.save(it, recordingEvents.toList()) }
        recordingEvents.clear()
        recordingFile = null
        voiceRevision++
    }
    fun startRecording() {
        if (recorder != null) return
        val file = store.newRecordingFile(note.id)
        val active = if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.media.MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            android.media.MediaRecorder()
        }
        val started = runCatching {
            active.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            active.setOutputFile(file.absolutePath)
            active.prepare()
            active.start()
        }.isSuccess
        if (started) {
            recordingFile = file
            recorder = active
            recordingEvents.clear()
            recordingStartedAt = android.os.SystemClock.elapsedRealtime()
        } else {
            active.release()
            file.delete()
            Toast.makeText(context, "녹음을 시작하지 못했습니다", Toast.LENGTH_SHORT).show()
        }
    }
    val requestAudioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startRecording() }
    DisposableEffect(note.id) {
        onDispose {
            runCatching { recorder?.stop() }
            recorder?.release()
            voicePlayer?.release()
        }
    }
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
    var regionOcrText by remember { mutableStateOf<String?>(null) }
    var regionOcrBusy by remember { mutableStateOf(false) }
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
    var showTextBox by remember { mutableStateOf(false) }
    var creatingSticky by remember { mutableStateOf(false) }
    /** A table being made (an empty PageImage) or changed; null when neither. */
    var editingTable by remember { mutableStateOf<PageImage?>(null) }
    /** 1 cropping the selected picture, 2 styling it, 0 neither. */
    var editingPicture by remember { mutableIntStateOf(0) }
    var pickingSticker by remember { mutableStateOf(false) }
    var replacingText by remember { mutableStateOf<PageImage?>(null) }
    var textPosition by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var captured by remember { mutableStateOf<Bitmap?>(null) }
    var captureToSave by remember { mutableStateOf<Bitmap?>(null) }
    val saveCapture = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri: Uri? ->
        val bitmap = captureToSave
        captureToSave = null
        if (uri != null && bitmap != null) scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                } == true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, if (ok) "PNG를 저장했습니다" else "PNG 저장 실패", Toast.LENGTH_SHORT).show()
            }
        }
    }
    /** The site the side panel is showing, or null while it is closed. */
    var webUrl by remember { mutableStateOf<String?>(null) }
    // One WebView for the whole note. Closing the panel used to destroy it, so
    // reopening paid the cold start and the login handshake all over again.
    val browser = remember { mutableStateOf<android.webkit.WebView?>(null) }
    var browserGeneration by remember { mutableIntStateOf(0) }
    var webWidth by remember { mutableStateOf(WEB_PANEL_WIDTH) }
    var draftWebWidth by remember { mutableStateOf<Dp?>(null) }
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
    val browserVisible by rememberUpdatedState(webUrl != null)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> browser.value?.onPause()
                Lifecycle.Event.ON_START -> if (browserVisible) browser.value?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
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
    DisposableEffect(imageCache) {
        onDispose { imageCache.evictAll() }
    }
    // Folded away, the bar becomes a handle that can be dragged; unfolding puts
    // it back wherever that handle was left, which is the point of moving it.
    var toolbarSize by remember { mutableIntStateOf(penStore.toolbarSize) }
    val collapsed = false
    fun setToolbarSize(size: Int) { toolbarSize = size; penStore.toolbarSize = size }
    // Flush against the top edge, or floating over the page. Kept in
    // preferences: where the toolbar sits is a habit, not a per-note choice.
    var docked by remember { mutableStateOf(penStore.docked) }
    var prediction by remember { mutableStateOf(penStore.prediction) }
    var predictionLeadMs by remember { mutableIntStateOf(penStore.predictionLeadMs) }
    var deferDetail by remember { mutableStateOf(penStore.deferDetail) }
    var stabilizer by remember { mutableIntStateOf(penStore.stabilizer) }
    var highlighterAboveInk by remember { mutableStateOf(penStore.highlighterAboveInk) }
    var meshInk by remember { mutableStateOf(penStore.meshInk) }
    var compatWetInk by remember { mutableStateOf(penStore.compatWetInk) }
    var partialEraser by remember { mutableStateOf(penStore.partialEraser) }
    var gestures by remember { mutableStateOf(penStore.gestures) }
    fun setGestures(value: CanvasGestures) { gestures = value; penStore.gestures = value }
    /** Bumped on copy and cut so the paste bar sees the clipboard change. */
    var clipRevision by remember { mutableIntStateOf(0) }
    var autoShapes by remember { mutableStateOf(penStore.autoShapes) }
    var axisSnap by remember { mutableStateOf(penStore.axisSnap) }
    var dottedPattern by remember { mutableIntStateOf(penStore.dottedPattern) }
    var noteRotation by remember { mutableIntStateOf(penStore.noteRotation) }
    // Null until it is dragged: the bar sits centred at the top by default, and
    // there is no sensible centre to store before anything has been measured.
    var barOffset by remember { mutableStateOf<Offset?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val otherNotes = remember(note.id) { store.list().filter { it.id != note.id } }
    val referenceNotes = remember(note, otherNotes) { listOf(note) + otherNotes }
    val initialReferenceNoteId = remember(note.id, referenceNotes) {
        penStore.referenceNote?.takeIf { saved -> referenceNotes.any { it.id == saved } } ?: note.id
    }
    val initialReferencePage = remember(initialReferenceNoteId, referenceNotes) {
        val count = referenceNotes.first { it.id == initialReferenceNoteId }.pageCount
        restoredPage(penStore.lastPage(initialReferenceNoteId), count)
    }

    // The three-finger reference panel: a second, live InkCanvasView floating
    // over this one, on whichever note and page it is pointed at - any note,
    // not only this one, and writable, since a reference worth keeping open
    // is usually a reference worth adding to. The note it shows is remembered
    // in preferences, so it is still the same one after a close, after leaving
    // the note, and after the app has been shut.
    var referenceOpen by remember { mutableStateOf(false) }
    var referenceNoteId by remember { mutableStateOf(initialReferenceNoteId) }
    var referencePage by remember { mutableIntStateOf(initialReferencePage) }
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
    /**
     * The panel opened as a zoom box: this note's current page in a strip
     * across the bottom, to write into at whatever magnification it is pinched to.
     * ponytail: no auto-advance or marker on the page; the panel is the
     * reference panel aimed at this note.
     */
    var zoomBox by remember { mutableStateOf(false) }
    fun openZoomBox(page: Int) {
        zoomBox = true
        referenceNoteId = note.id
        referencePage = page
        referenceSize = Size(containerSize.width.toFloat().coerceAtLeast(1f),
            (containerSize.height / 3f).coerceAtLeast(1f))
        referenceStretch = 1f
        referenceOffset = Offset(0f, containerSize.height - referenceSize.height)
        referenceOpen = true
    }
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
        val oldStretch = referenceStretch
        // Ignore sub-pixel resampling noise from a stationary three-finger grip.
        val stableFactor = if (abs(spreadFactor - 1f) < REFERENCE_SCALE_DEAD_ZONE) {
            1f
        } else {
            spreadFactor
        }
        referenceStretch = (oldStretch * stableFactor)
            .coerceIn(floor, maxOf(floor, ceiling))
        val w = referenceSize.width * referenceStretch
        val h = referenceSize.height * referenceStretch
        val oldW = referenceSize.width * oldStretch
        val oldH = referenceSize.height * oldStretch
        val maxX = (containerSize.width - w).coerceAtLeast(0f)
        val maxY = (containerSize.height - h).coerceAtLeast(0f)
        referenceOffset = Offset(
            // 핀치 중심을 화면에 고정합니다. 좌상단 고정 확대는 손가락과
            // 팝업이 서로 다른 방향으로 미끄러져 보였습니다.
            (referenceOffset.x + panX - (w - oldW) / 2f).coerceIn(0f, maxX),
            (referenceOffset.y + panY - (h - oldH) / 2f).coerceIn(0f, maxY),
        )
    }
    val maxBarWidth = with(density) {
        (containerSize.width.takeIf { it > 0 } ?: Int.MAX_VALUE).toDp()
    }
    val toolbarEffects = rememberLiquidGlassEffectsAllowed() && !LocalSkinSettings.current.highContrast
    val floatingBarWidth = animateDpAsState(
        minOf(maxBarWidth, spotiToolbarWidth(toolbarSize)),
        if (skin != Skin.MATERIAL && toolbarEffects) tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing) else tween(0),
        label = "상단 바 모드 너비",
    )
    var showLatency by remember { mutableStateOf(false) }
    var laser by remember { mutableStateOf(false) }
    var laserDot by remember { mutableStateOf(false) }
    var ruler by remember { mutableStateOf<RulerKind?>(null) }
    var rulerAngle by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var goToPage by remember { mutableStateOf(false) }
    var showSkinSettings by remember { mutableStateOf(false) }
    var pageLayout by remember { mutableStateOf(penStore.pageLayoutOf(note.id)) }
    var noteMenu by remember { mutableStateOf(false) }
    var edits by remember { mutableIntStateOf(0) }
    val indexedRevisions = remember(note.id) { mutableMapOf<String, Long>() }
    val resumePage = remember(note.id, note.pageCount) {
        restoredPage(penStore.lastPage(note.id), note.pageCount)
    }
    var pageCount by remember(note.id) { mutableIntStateOf(note.pageCount) }
    var currentPage by remember(note.id) { mutableIntStateOf(resumePage) }
    // 빠른 스크러버는 처음부터 화면을 차지하지 않고, 실제로 세 페이지 경계를
    // 지나 긴 노트 탐색이 필요해진 순간부터 나타납니다.
    var pagesTraversed by remember(note.id) { mutableIntStateOf(0) }
    var pageScrubberVisible by remember(note.id) { mutableStateOf(false) }
    var pageScrubberActivity by remember(note.id) { mutableIntStateOf(0) }
    // 마지막 페이지 이동마다 이전 타이머가 취소됩니다. 이동이 멈춘 뒤에만 사라집니다.
    LaunchedEffect(pageScrubberActivity) {
        if (!pageScrubberVisible) return@LaunchedEffect
        delay(PAGE_SCRUBBER_IDLE_MS)
        pageScrubberVisible = false
    }
    // A multiple of fit-to-width, which is the 100% anybody means.
    var zoom by remember { mutableFloatStateOf(1f) }
    var canvas by remember { mutableStateOf<InkCanvasView?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = java.io.File(context.cacheDir, "shared/camera.jpg")
        if (!taken || !file.isFile) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) { file.inputStream().use { store.addImage(note.id, it) } }
            if (added != null) { canvas?.insertImage(added.first, added.second); edits++ }
        }
    }
    if (rulerAngle) {
        var text by remember { mutableStateOf("") }
        var inches by remember { mutableStateOf(canvas?.rulerInches == true) }
        AlertDialog(
            onDismissRequest = { rulerAngle = false },
            title = { Text("자 설정") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(6) },
                        label = { Text("각도 (°)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsChoiceChip(selected = !inches, onClick = { inches = false }, label = "mm",
                            modifier = Modifier.padding(end = 8.dp))
                        SettingsChoiceChip(selected = inches, onClick = { inches = true }, label = "inch")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toFloatOrNull()?.let { canvas?.setRulerDegrees(it) }
                    canvas?.rulerInches = inches
                    rulerAngle = false
                }) { Text("적용") }
            },
            dismissButton = { TextButton(onClick = { rulerAngle = false }) { Text("취소") } },
        )
    }
    if (goToPage) canvas?.let { view ->
        GoToPageDialog(view.document.pages.size, onGo = { view.scrollToPage(it) }, onDismiss = { goToPage = false })
    }
    val study = remember(note.id) { MaskStudy(store.studyFile(note.id)) }
    var studyRevision by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<StudySession?>(null) }
    var renamingMask by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var selectedPdf by remember { mutableStateOf<PdfSelection?>(null) }
    var selectionPreview by remember { mutableStateOf<Bitmap?>(null) }
    var selectionColorMode by remember { mutableStateOf<EditMode?>(null) }
    var opened by remember { mutableStateOf<Pair<Document, PdfSource?>?>(null) }
    LaunchedEffect(selectedPdf) {
        selectionPreview = null
        val selected = selectedPdf ?: return@LaunchedEffect
        delay(120)
        selectionPreview = withContext(Dispatchers.IO) { opened?.second?.renderSelection(selected) }
    }
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
            rendererGeneration = browserGeneration,
            onRendererGone = { failed ->
                if (browser.value === failed) {
                    (failed.parent as? android.view.ViewGroup)?.removeView(failed)
                    failed.destroy()
                    browser.value = null
                    browserGeneration++
                }
            },
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
            toolPens = settings,
            toolbarTargetWidth = minOf(maxBarWidth, spotiToolbarWidth(toolbarSize)),
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
                if (mode == picked) editingPen = picked in PenStore.DEFAULTS else mode = picked
            },
            onShape = {
                shapeKind = it
                canvas?.clearSelection()
                canvas?.clearImageSelection()
                mode = EditMode.SHAPE
            },
            onPickImage = { pickImage.launch("image/*") },
            onText = {
                if (mode == EditMode.TEXT) { replacingText = null; textPosition = null; showTextBox = true }
                mode = EditMode.TEXT
                canvas?.clearSelection()
            },
            onAddText = { replacingText = null; textPosition = null; showTextBox = true; mode = EditMode.TEXT },
            onWeb = { webUrl = it },
            onWidth = {
                settings = settings + (mode to pen.copy(width = it,
                    maxWidth = if (pen.maxWidth > 0f) maxOf(pen.maxWidth, it) else 0f))
            },
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
            laser = laser,
            onToggleLaser = {
                when {
                    !laser -> { laser = true; laserDot = false }
                    !laserDot -> laserDot = true
                    else -> laser = false
                }
            },
            laserDot = laserDot,
            ruler = ruler,
            onRuler = { picked ->
                if (picked != null && picked == ruler) rulerAngle = true else ruler = picked
            },
            onAddPage = {
                canvas?.let { it.addPage(it.currentPageIndex()); it.scrollToPage(it.currentPageIndex() + 1) }
                edits++
            },
            onSticker = { pickingSticker = true },
            onStickyNote = {
                creatingSticky = true
                replacingText = null
                textPosition = null
                showTextBox = true
            },
            onTable = { editingTable = PageImage() },
            onEditTools = { editingTools = true },
            onZoomBox = { if (zoomBox && referenceOpen) { referenceOpen = false; zoomBox = false } else openZoomBox(canvas?.currentPageIndex() ?: 0) },
            onNoteMenu = { noteMenu = true },
            onCamera = {
                val file = java.io.File(context.cacheDir, "shared/camera.jpg").apply { parentFile?.mkdirs() }
                takePhoto.launch(androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file))
            },
            onQuickColor = { rgb ->
                val alpha = pen.colorArgb and 0xFF000000.toInt()
                settings = settings + (mode to pen.copy(colorArgb = (rgb and 0xFFFFFF) or alpha))
            },
            onInputSettings = { showInput = true },
            recording = recorder != null,
            onVoice = { showVoice = true },
            noteRotation = noteRotation,
            onRotate = {
                noteRotation = (noteRotation + 90) % 360
                penStore.noteRotation = noteRotation
            },
            onTogglePages = { showPages = !showPages },
            onCollapse = { setToolbarSize((toolbarSize + 1).coerceAtMost(3)) },
            sizeLevel = toolbarSize,
            onSizeLevel = { setToolbarSize(it) },
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
            onScreenSettings = { showSkinSettings = true },
            onDragBar = { delta ->
                val at = barPlacement(barOffset, barSize, containerSize)
                if (docked) { docked = false; penStore.docked = false }
                barOffset = Offset(at.x + delta.x, at.y + delta.y)
            },
            modifier = barModifier,
        )
    }

    // The chrome looks through whatever is drawn under it, so the page records
    // itself once a frame - but only while something actually bends or blurs it.
    val look = LocalSkinSettings.current
    val backdrop = rememberBackdrop(
        active = skin == Skin.GLASSMORPHISM &&
            (look.blur > 0.1f || look.vibrancy > 0.01f),
    )
    val liquidBackdrop = if (skin.isRefractive) rememberLiquidGlassBackdrop() else null
    var drawingPage by remember { mutableStateOf(false) }
    var pageLayerOrigin by remember { mutableStateOf(Offset.Zero) }
    val popupBackdrop = remember(liquidBackdrop) { liquidBackdrop?.let { SpotiPopupBackdrop(it) { pageLayerOrigin } } }
    var movingPage by remember { mutableStateOf(false) }
    CompositionLocalProvider(
        LocalBackdrop provides backdrop,
        LocalLiquidGlassBackdrop provides if (skin.isRefractive) popupBackdrop else null,
        LocalSpotiPopupBackdrop provides if (skin == Skin.SPOTIGLASS) popupBackdrop else null,
    ) {
    Row(Modifier.fillMaxSize().drawWithContent {
        drawContent()
        draftWebWidth?.let { draft ->
            val left = size.width - draft.toPx()
            drawRect(
                color = Color(0x773B7DDD),
                topLeft = Offset(left, 0f),
                size = Size(draft.toPx(), size.height),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }) {
    // The page's own ground, behind the bar as well as behind the page. Docked,
    // the bar takes its own room, and that room was the bare window underneath -
    // white above it where the status bar inset is and white in the seam below,
    // with the glass tinting nothing but that white. Painting the paper colour
    // the whole way up closes both gaps and gives the glass something of the
    // page's own to sit on.
    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFE9E7E2))) {
    Box(
        Modifier
            .fillMaxSize()
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
        Box(
            Modifier
                .fillMaxSize()
                .recordBackdrop(backdrop)
                .onGloballyPositioned { pageLayerOrigin = it.positionOnScreen() }
                .then(
                    if (skin.isRefractive) {
                        Modifier.captureLiquidGlassBackdrop(requireNotNull(liquidBackdrop), paused = { drawingPage || movingPage })
                    } else {
                        Modifier
                    },
                )
                .background(Color(0xFFE9E7E2)),
        ) {
        if (ready == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    // AndroidView를 Backdrop 레이어에 안정적으로 합성합니다.
                    .graphicsLayer {
                        alpha = 1f
                        rotationZ = noteRotation.toFloat()
                        if (noteRotation % 180 != 0 && size.width > 0f && size.height > 0f) {
                            val fit = minOf(size.width / size.height, size.height / size.width)
                            scaleX = fit
                            scaleY = fit
                        }
                    },
                factory = { viewContext ->
                    InkCanvasView(viewContext).apply {
                        pageLoader = { page, epsilon ->
                            store.loadPage(note.id, page, epsilon)
                        }
                        maskLoader = { page, epsilon ->
                            store.loadMasks(note.id, page, epsilon)
                        }
                        // The view restores only after its first measured width
                        // has established fit-to-width, so that fit cannot send
                        // the note back to page one a frame later.
                        ready.first.layoutMode = pageLayout
                        open(ready.first, ready.second, initialPage = resumePage)
                        onStrokesChanged = {
                            edits++
                            pageCount = document.pages.size
                        }
                        onCurrentPageChanged = {
                            val traversed = pagesTraversed + abs(it - currentPage)
                            pagesTraversed = traversed
                            currentPage = it
                            if (shouldShowPageScrubber(traversed, pageCount)) {
                                pageScrubberVisible = true
                                pageScrubberActivity++
                            }
                            // Save while reading, not only while leaving. This
                            // survives process death and makes reopening exact.
                            penStore.setLastPage(note.id, it)
                        }
                        onZoomChanged = { zoom = it }
                        onSelectionChanged = { selectedText = it?.text; selectedPdf = it }
                        onImageSelected = { imageSelected = it; edits++ }
                        onTextRequested = { page, x, y ->
                            replacingText = null; textPosition = Triple(page, x, y); showTextBox = true
                        }
                        onLassoSelected = { lassoCount = it }
                        imageLoader = { imageId ->
                            imageCache.get(imageId) ?: runCatching {
                                android.graphics.BitmapFactory
                                    .decodeFile(store.imageFile(note.id, imageId).path)
                            }.getOrNull()?.also { imageCache.put(imageId, it) }
                        }
                        imageAdder = { bitmap -> store.addImage(note.id, bitmap)?.first }
                        templateLoader = { templateId ->
                            runCatching { android.graphics.BitmapFactory.decodeFile(
                                store.templateFile(templateId).path) }.getOrNull()
                        }
                        // Rendered on a worker; the dialog is a UI thing.
                        onCaptured = { bitmap -> post { captured = bitmap } }
                        canvas = this
                    }
                },
                update = { view ->
                    // The frost stops while the pen is down; see Backdrop.paused.
                    view.onDrawingChanged = { drawing ->
                        drawingPage = drawing
                        backdrop.paused = drawing || movingPage
                    }
                    view.onViewportInteractionChanged = { moving ->
                        movingPage = moving
                        backdrop.paused = moving || drawingPage
                    }
                    view.predictionEnabled = prediction
                    view.predictionLeadMs = predictionLeadMs
                    view.latencyMonitoringEnabled = showLatency
                    view.deferDetail = deferDetail
                    view.stabilizer = stabilizer
                    view.highlighterAboveInk = highlighterAboveInk
                    view.meshInk = meshInk
                    view.compatWetInk = compatWetInk
                    view.partialEraser = partialEraser
                    view.applyGestures(gestures)
                    view.laserMode = laser
                    view.shapeCornerRadius = shapeCorner
                    if (mode != EditMode.ERASE && mode in PenStore.DEFAULTS) beforeEraser = mode
                    view.onEraseFinished = if (gestures.eraserReturns && mode == EditMode.ERASE) {
                        { mode = beforeEraser }
                    } else null
                    view.showLinkOverlay = gestures.linkOverlay
                    view.onLinkBackChanged = { canGoBack = it }
                    view.onOpenNoteLink = { noteId, pageIndex ->
                        store.list().firstOrNull { it.id == noteId }?.let { target ->
                            penStore.setLastPage(target.id, pageIndex)
                            onOpenNote(target)
                        } ?: Toast.makeText(context, "연결된 노트를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                    }
                    view.onLongPressCanvas = if (gestures.longPressMenu) { x, y -> pressMenu = Offset(x, y) } else null
                    view.laserTrail = !laserDot
                    view.rulerKind = ruler
                    view.onRulerClosed = { ruler = null }
                    view.autoShapeRecognitionEnabled = autoShapes
                    view.axisSnapEnabled = axisSnap
                    view.dottedPattern = dottedPattern
                    view.setPageLayout(pageLayout)
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
                    view.textMode = mode == EditMode.TEXT
                    view.imageMode = mode == EditMode.IMAGE || mode == EditMode.TEXT
                    view.captureMode = mode == EditMode.CAPTURE
                    view.maskMode = mode == EditMode.MASK
                    view.lassoMode = mode == EditMode.LASSO
                    // Tape that lets the answer through is not tape, so the
                    // mask tool draws its colour opaque whatever alpha it holds.
                    view.colorArgb =
                        if (mode == EditMode.MASK) pen.colorArgb or 0xFF000000.toInt() else pen.colorArgb
                    view.strokeWidth = pen.width
                    view.prepareBrush()
                    view.eraserWidth = eraserWidth
                    view.onUndo = { canvas?.undo(); edits++ }
                    view.onRedo = { canvas?.redo(); edits++ }
                    view.onShortcut = { keyCode, event ->
                        val command = event.isCtrlPressed || event.isMetaPressed
                        val digit = keyCode - android.view.KeyEvent.KEYCODE_1
                        when {
                            // The toolbar's order, so Ctrl+3 is the third tool you can see.
                            command && !event.isAltPressed && digit in 0..8 -> {
                                ToolbarLayout.tools.mapNotNull { it.mode }.getOrNull(digit)?.let { picked ->
                                    canvas?.clearSelection()
                                    canvas?.clearImageSelection()
                                    canvas?.clearLassoSelection()
                                    mode = picked
                                }
                                true
                            }
                            command && event.isAltPressed && keyCode == android.view.KeyEvent.KEYCODE_G -> {
                                goToPage = true
                                true
                            }
                            command && keyCode == android.view.KeyEvent.KEYCODE_F -> {
                                showPages = true
                                true
                            }
                            else -> false
                        }
                    }
                    view.referenceOpen = referenceOpen
                    view.onLinkUrl = { url -> webUrl = url }
                    view.onStrokesCommitted = { pageIndex, strokes ->
                        if (recorder != null) {
                            val at = android.os.SystemClock.elapsedRealtime() - recordingStartedAt
                            for (stroke in strokes) {
                                recordingEvents += RecordingTimeline.Event(view.keyOf(pageIndex, stroke), at)
                            }
                        }
                    }
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
                    view.onCloseReference = { referenceOpen = false; zoomBox = false }
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
            // Snapshot on the UI thread before the serial worker writes it.
            // Passing the live document to Dispatchers.IO raced pen commits and
            // mesh rebuilds, which is the repeated CME found in crash.log.
            store.saveLater(note.id, note.title, view.document)
        }

        // Handwriting recognition is useful search metadata, not part of
        // saving. Give it a longer quiet period and do not rerun it for toolbar
        // or image state that left the ink revision unchanged.
        LaunchedEffect(edits) {
            if (edits == 0) return@LaunchedEffect
            delay(INK_INDEX_IDLE_MS)
            val view = canvas ?: return@LaunchedEffect
            val page = view.document.pages.getOrNull(view.currentPageIndex()) ?: return@LaunchedEffect
            if (!page.loaded) return@LaunchedEffect
            val revision = page.revision
            if (indexedRevisions[page.id] == revision) return@LaunchedEffect
            val strokes = page.strokes.toList()
            withContext(Dispatchers.IO) {
                indexer.textOf(strokes)?.let { store.writeInkIndex(note.id, page.id, it) }
            }
            indexedRevisions[page.id] = revision
        }

        // Anything still unsaved when the screen goes away gets written now. If
        // the note was closed before it finished opening, the canvas never took
        // ownership of the PdfSource, so close it here instead of leaking it.
        DisposableEffect(note.id, lifecycleOwner) {
            fun flushLatest() {
                val view = canvas ?: return
                penStore.setLastPage(note.id, view.currentPageIndex())
                store.flushSave(note.id, note.title, view.document)
            }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) flushLatest()
            }
            lifecycleOwner?.lifecycle?.addObserver(observer)
            onDispose {
                lifecycleOwner?.lifecycle?.removeObserver(observer)
                val view = canvas
                if (view != null) {
                    flushLatest()
                } else {
                    opened?.second?.close()
                }
            }
        }

        if (showLatency) {
            LatencyHud(
                report = {
                    canvas?.let {
                        it.debugPerformanceReport() + "\nzoom ${"%.0f".format(zoom * 100)}%"
                    }.orEmpty()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(ChromeInsets)
                    .padding(top = 84.dp, start = 24.dp),
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = pageScrubberVisible && !showPages,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(220)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(0.70f)
                .windowInsetsPadding(ChromeInsets)
                .padding(end = 6.dp),
        ) {
            PageScrubber(
                currentPage = currentPage,
                pageCount = pageCount,
                onJump = { page ->
                    penStore.setLastPage(note.id, page)
                    canvas?.scrollToPage(page)
                },
                modifier = Modifier.fillMaxHeight(),
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
                    // Intrinsic text must not resize the whole bar when the pen
                    // type or a value such as 9.9/10 changes.
                    .spotiToolbarAnimatedWidth(floatingBarWidth)
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
                    onExpand = { setToolbarSize(0) },
                    onDrag = { delta ->
                        val at = barPlacement(barOffset, barSize, containerSize)
                        barOffset = Offset(at.x + delta.x, at.y + delta.y)
                    },
                )
            }
        }


        if (showSkinSettings) {
            Dialog(onDismissRequest = { showSkinSettings = false },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                SkinSettingsScreen(skin, LocalSkinSettings.current, onSkin, onLookChange,
                    onBack = { showSkinSettings = false })
            }
        }

        // A finished recording asked for the next: start it the way the play button would.
        LaunchedEffect(playNextRecording) {
            val path = playNextRecording ?: return@LaunchedEffect
            playNextRecording = null
            val player = android.media.MediaPlayer()
            val started = runCatching {
                player.setDataSource(path)
                player.prepare()
                player.setOnCompletionListener {
                    it.release(); voicePlayer = null; playingFile = null
                    if (penStore.autoPlayNext) {
                        val all = store.recordings(note.id)
                        all.getOrNull(all.indexOfFirst { f -> f.path == path } + 1)?.let { next -> playNextRecording = next.path }
                    }
                }
                player.start()
            }.isSuccess
            if (started) {
                voicePlayer = player; playingFile = path
                playingTimeline = RecordingTimeline.load(java.io.File(path))
            } else player.release()
        }
        if (showVoice) {
            val recordings = remember(note.id, voiceRevision) { store.recordings(note.id) }
            AlertDialog(
                onDismissRequest = { showVoice = false },
                title = { Text("음성 녹음") },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        TextButton(onClick = {
                            if (recorder != null) stopRecording()
                            else if (androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.RECORD_AUDIO,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                startRecording()
                            } else requestAudioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }) { Text(if (recorder == null) "● 새 녹음 시작" else "■ 녹음 끝내기") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var autoNext by remember { mutableStateOf(penStore.autoPlayNext) }
                            SkinSwitch(checked = autoNext, onCheckedChange = { autoNext = it; penStore.autoPlayNext = it })
                            Spacer(Modifier.width(8.dp))
                            Text("다음 녹음 이어서 재생", style = MaterialTheme.typography.bodySmall)
                        }
                        for (file in recordings) Row(verticalAlignment = Alignment.CenterVertically) {
                            val label = runCatching {
                                dateFormat.format(Date(file.nameWithoutExtension.toLong()))
                            }.getOrDefault(file.nameWithoutExtension)
                            Text(label, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                if (playingFile == file.path) {
                                    voicePlayer?.release(); voicePlayer = null; playingFile = null
                                } else {
                                    voicePlayer?.release()
                                    val player = android.media.MediaPlayer()
                                    val started = runCatching {
                                        player.setDataSource(file.path)
                                        player.prepare()
                                        player.setOnCompletionListener {
                                            it.release(); voicePlayer = null; playingFile = null
                                            // The one after this, if asked to carry on.
                                            if (penStore.autoPlayNext) {
                                                recordings.getOrNull(recordings.indexOf(file) + 1)?.let { next ->
                                                    playNextRecording = next.path
                                                }
                                            }
                                        }
                                        player.start()
                                    }.isSuccess
                                    if (started) {
                                        voicePlayer = player; playingFile = file.path
                                        playingTimeline = RecordingTimeline.load(file)
                                    } else { player.release(); playingFile = null }
                                }
                            }) { Text(if (playingFile == file.path) "정지" else "재생") }
                            TextButton(onClick = { shareFile(context, file, "audio/*") }) { Text("내보내기") }
                            TextButton(onClick = {
                                if (playingFile == file.path) {
                                    voicePlayer?.release(); voicePlayer = null; playingFile = null
                                }
                                file.delete()
                                RecordingTimeline.fileFor(file).delete()
                                voiceRevision++
                            }) { Text("삭제") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showVoice = false }) { Text("닫기") } },
            )
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
                predictionLeadMs = predictionLeadMs,
                onPredictionLeadMs = {
                    predictionLeadMs = it
                    penStore.predictionLeadMs = it
                },
                deferDetail = deferDetail,
                onDeferDetail = {
                    deferDetail = it
                    penStore.deferDetail = it
                },
                stabilizer = stabilizer,
                onStabilizer = { stabilizer = it; penStore.stabilizer = it },
                highlighterAboveInk = highlighterAboveInk,
                onHighlighterAboveInk = {
                    highlighterAboveInk = it; penStore.highlighterAboveInk = it
                },
                meshInk = meshInk,
                onMeshInk = { meshInk = it; penStore.meshInk = it },
                compatWetInk = compatWetInk,
                onCompatWetInk = { compatWetInk = it; penStore.compatWetInk = it },
                partialEraser = partialEraser,
                onPartialEraser = { partialEraser = it; penStore.partialEraser = it },
                gestures = gestures,
                onGestures = { setGestures(it) },
                onClearPage = {
                    canvas?.let { it.clearPage(it.currentPageIndex()) }
                    edits++
                    editingPen = false
                },
                onCornerRadius = { shapeCorner = it },
                onEyedropper = {
                    val picking = mode
                    editingPen = false
                    canvas?.pickColor { picked ->
                        val current = settings[picking] ?: return@pickColor
                        // The picked hue at the alpha the tool already had: a highlighter stays see-through.
                        val alpha = current.colorArgb and 0xFF000000.toInt()
                        settings = settings + (picking to current.copy(colorArgb = (picked and 0xFFFFFF) or alpha))
                        editingPen = true
                    }
                },
                autoShapes = autoShapes,
                onAutoShapes = { autoShapes = it; penStore.autoShapes = it },
                axisSnap = axisSnap,
                onAxisSnap = { axisSnap = it; penStore.axisSnap = it },
                dottedPattern = dottedPattern,
                onDottedPattern = { dottedPattern = it; penStore.dottedPattern = it },
                onDismiss = { editingPen = false },
                onConfirm = { saved ->
                    settings = settings + (mode to saved)
                    penStore.save(settings)
                    editingPen = false
                },
            )
        }

        if (showPalette) {
            PaletteDialog(
                onDismiss = { showPalette = false },
                onPick = { rgb ->
                    val recoloured = paletteColorArgb(pen.colorArgb, rgb)
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
                onSave = {
                    captureToSave = bitmap
                    captured = null
                    saveCapture.launch("Notesis-capture.png")
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
                onOcr = {
                    val strokes = canvas?.selectedLassoStrokes().orEmpty()
                    regionOcrBusy = true
                    scope.launch {
                        regionOcrText = withContext(Dispatchers.IO) { indexer.textOf(strokes, firstReading = true) }
                            ?: "인식 모델을 사용할 수 없습니다"
                        regionOcrBusy = false
                    }
                },
                onTransform = { factor, degrees ->
                    canvas?.transformLassoSelection(factor, degrees)
                    edits++
                },
                onRecolor = {
                    canvas?.recolorLassoSelection(settings.getValue(EditMode.PEN).colorArgb)
                    edits++
                },
                onDuplicate = {
                    canvas?.duplicateLassoSelection()
                    edits++
                },
                onCopy = {
                    canvas?.copyLassoSelection()
                    clipRevision++
                },
                onCut = {
                    canvas?.cutLassoSelection()
                    clipRevision++
                    edits++
                },
                locked = edits.let { canvas?.lassoSelectionLocked() == true },
                onLock = { locked ->
                    canvas?.lockLassoSelection(locked)
                    edits++
                },
                onLink = { linking = true },
                onAddToLibrary = {
                    val kept = canvas?.lassoClipWithPreview()
                    if (kept != null) scope.launch {
                        val id = withContext(Dispatchers.IO) { store.addToLibrary(kept.first, kept.second) }
                        Toast.makeText(context, if (id != null) "라이브러리에 추가했습니다" else "추가하지 못했습니다",
                            Toast.LENGTH_SHORT).show()
                    }
                },
                grouped = edits.let { canvas?.lassoSelectionGrouped() == true },
                onGroup = { grouped ->
                    canvas?.groupLassoSelection(grouped)
                    edits++
                },
                onDone = { canvas?.clearLassoSelection() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else if (mode == EditMode.LASSO) {
            PasteBar(
                clipRevision = clipRevision,
                wholeOnly = gestures.lassoWholeOnly,
                onWholeOnly = { setGestures(gestures.copy(lassoWholeOnly = it)) },
                onPaste = {
                    canvas?.paste(it)
                    edits++
                },
                gestures = gestures,
                onGestures = { setGestures(it) },
                onLibrary = { showLibrary = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (linking) canvas?.let { view ->
            LinkDialog(
                current = view.lassoLink(),
                pageCount = view.document.pages.size,
                notes = remember { store.list().filter { it.id != note.id } },
                pageId = { view.pageIdAt(it) },
                onSave = { target -> view.setLassoLink(target); linking = false; edits++ },
                onDismiss = { linking = false },
            )
        }
        if (canGoBack) SkinSurface(
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(ChromeInsets).padding(top = 72.dp),
            corner = 16.dp,
        ) {
            TextButton(onClick = { canvas?.goBack() }) {
                Icon(Reicons.ArrowBack, contentDescription = null)
                Text(" 이전 위치로")
            }
        }
        sendingPages?.let { (indices, move) ->
            val others = remember { store.list().filter { it.id != note.id && it.kind == NoteKind.INK } }
            AlertDialog(
                onDismissRequest = { sendingPages = null },
                title = { Text(if (move) "${indices.size}쪽을 옮길 노트" else "${indices.size}쪽을 복사할 노트") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(others, key = { it.id }) { target ->
                            Text(target.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val pages = canvas?.snapshotPages(indices).orEmpty()
                                    sendingPages = null
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { store.appendPages(target.id, note.id, pages) }
                                        if (ok && move) { canvas?.deletePages(indices); edits++ }
                                        Toast.makeText(context, if (ok) "\"${target.title}\"에 ${pages.size}쪽을 넣었습니다"
                                            else "옮기지 못했습니다", Toast.LENGTH_SHORT).show()
                                    }
                                }.padding(12.dp))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { sendingPages = null }) { Text("취소") } },
            )
        }
        if (showLibrary) LibraryDialog(store, onPaste = { clip ->
            showLibrary = false
            canvas?.paste(clip)
            edits++
        }, onDismiss = { showLibrary = false })

        // Follow along: what was being written at this point of the recording
        // is marked, and its page brought up.
        LaunchedEffect(playingFile, playingTimeline, canvas) {
            val view = canvas ?: return@LaunchedEffect
            if (playingFile == null || playingTimeline.isEmpty()) {
                view.setPlaybackHighlight(emptyMap())
                view.onStrokeTapped = null
                return@LaunchedEffect
            }
            val timeline = playingTimeline
            fun pageOf(key: String) = view.document.pages.indexOfFirst { key.startsWith(it.id + ":") }
            view.onStrokeTapped = { pageIndex, stroke ->
                val key = view.keyOf(pageIndex, stroke)
                val event = timeline.firstOrNull { it.key == key }
                if (event != null) runCatching { voicePlayer?.seekTo(event.atMs.toInt()) }
                event != null
            }
            var followed = -1
            try {
                while (true) {
                    val player = voicePlayer ?: break
                    val position = runCatching { player.currentPosition.toLong() }.getOrNull() ?: break
                    val recent = RecordingTimeline.around(timeline, position, PLAYBACK_WINDOW_MS)
                    view.setPlaybackHighlight(
                        recent.groupBy { pageOf(it.key) }
                            .filterKeys { it >= 0 }
                            .mapValues { (_, events) -> events.map { it.key }.toSet() },
                    )
                    val newest = recent.lastOrNull()?.let { pageOf(it.key) } ?: -1
                    if (newest >= 0 && newest != followed) {
                        followed = newest
                        if (newest != view.currentPageIndex()) view.scrollToPage(newest)
                    }
                    delay(PLAYBACK_TICK_MS)
                }
            } finally {
                view.setPlaybackHighlight(emptyMap())
                view.onStrokeTapped = null
            }
        }
        if (playingFile != null && !showVoice) SkinSurface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(ChromeInsets)
                .padding(16.dp),
            corner = 16.dp,
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (playingTimeline.isEmpty()) "녹음 재생 중"
                    else "녹음 재생 중 · 읽기 모드에서 필기를 누르면 그 시점으로",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = {
                    voicePlayer?.release(); voicePlayer = null; playingFile = null
                }) { Text("정지") }
            }
        }
        session?.let { run ->
            StudyBar(
                session = run,
                name = canvas?.document?.pages?.getOrNull(run.pageIndex)?.let { page ->
                    run.current?.let { maskIndex ->
                        page.masks.getOrNull(maskIndex)?.let { study.record(MaskStudy.keyOf(page.id, it.stroke)).name }
                    }
                }.orEmpty(),
                onShow = {
                    run.current?.let { canvas?.setMaskRevealed(run.pageIndex, it, true) }
                    session = run.copy(showing = true)
                    edits++
                },
                onAnswer = { right ->
                    val page = canvas?.document?.pages?.getOrNull(run.pageIndex)
                    val maskIndex = run.current
                    if (page != null && maskIndex != null) {
                        page.masks.getOrNull(maskIndex)?.let { study.answer(MaskStudy.keyOf(page.id, it.stroke), right) }
                        canvas?.setMaskRevealed(run.pageIndex, maskIndex, true)
                    }
                    session = run.copy(
                        position = run.position + 1,
                        showing = false,
                        right = run.right + if (right) 1 else 0,
                        wrong = run.wrong + if (right) 0 else 1,
                        missed = if (right || maskIndex == null) run.missed else run.missed + maskIndex,
                    )
                    studyRevision++
                    edits++
                },
                onRetryMissed = {
                    canvas?.setMasksRevealed(run.pageIndex, false)
                    session = StudySession(run.pageIndex, run.missed)
                    edits++
                },
                onClose = { session = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        renamingMask?.let { (index, maskIndex) ->
            val page = canvas?.document?.pages?.getOrNull(index)
            val mask = page?.masks?.getOrNull(maskIndex)
            if (page == null || mask == null) {
                renamingMask = null
            } else {
                val key = MaskStudy.keyOf(page.id, mask.stroke)
                var name by remember(key) { mutableStateOf(study.record(key).name) }
                AlertDialog(
                    onDismissRequest = { renamingMask = null },
                    title = { Text("마스크 이름") },
                    text = {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            placeholder = { Text("마스크 ${maskIndex + 1}") },
                        )
                    },
                    confirmButton = { TextButton(onClick = {
                        study.rename(key, name)
                        studyRevision++
                        renamingMask = null
                    }) { Text("저장") } },
                    dismissButton = { TextButton(onClick = { renamingMask = null }) { Text("취소") } },
                )
            }
        }

        if (regionOcrBusy) AlertDialog(
            onDismissRequest = {}, title = { Text("선택한 필기를 읽는 중") }, confirmButton = {},
        )
        regionOcrText?.let { recognized -> AlertDialog(
            onDismissRequest = { regionOcrText = null },
            title = { Text("필기 인식 결과") },
            text = { Text(recognized.ifBlank { "글자를 인식하지 못했습니다" }) },
            confirmButton = { TextButton(onClick = {
                clipboard.setText(AnnotatedString(recognized))
                regionOcrText = null
            }) { Text("복사") } },
            dismissButton = {
                Row {
                    // The handwriting gives way to typed text where it stood.
                    if (recognized.isNotBlank()) TextButton(onClick = {
                        regionOcrText = null
                        val place = canvas?.lassoPlacement()
                        val content = TextBoxContent(recognized.trim(), size = place?.third ?: 32f)
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching {
                                val bitmap = renderTextBox(content)
                                try { store.addImage(note.id, bitmap)?.let { Triple(it.first, bitmap.width, bitmap.height) } }
                                finally { bitmap.recycle() }
                            }.getOrNull() }
                            if (result != null && place != null) {
                                canvas?.deleteLassoSelection()
                                canvas?.putTextBox(result.first, result.second, result.third, content,
                                    at = Triple(place.first, place.second.left, place.second.top))
                                edits++
                            }
                        }
                    }) { Text("텍스트로 바꾸기") }
                    TextButton(onClick = { regionOcrText = null }) { Text("닫기") }
                }
            },
        ) }

        if (showTextBox) TextBoxDialog(replacingText?.textContent ?: if (creatingSticky) STICKY_STYLE else null,
            onDismiss = { showTextBox = false; creatingSticky = false }, onSave = { content ->
                val box = if (creatingSticky) PageImage.BOX_STICKY else PageImage.BOX_NONE
                creatingSticky = false
                val replacing = replacingText
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val bitmap = renderTextBox(content)
                        try {
                            val image = store.addImage(note.id, bitmap) ?: error("텍스트를 저장하지 못했습니다")
                            Triple(image.first, bitmap.width, bitmap.height)
                        } finally { bitmap.recycle() }
                    } }
                    result.onSuccess { (id, width, height) ->
                        canvas?.putTextBox(id, width, height, content, replacing, textPosition, box)
                        mode = EditMode.TEXT
                        edits++
                        showTextBox = false
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "텍스트를 저장하지 못했습니다", Toast.LENGTH_LONG).show()
                    }
                }
            })

        if (imageSelected) {
            val selectedBox = edits.let { canvas?.selectedImageBox() ?: PageImage.BOX_NONE }
            ImageActions(
                isText = canvas?.selectedTextBox() != null,
                box = selectedBox,
                onFold = { canvas?.toggleSelectedFolded(); edits++ },
                onTable = { editingTable = canvas?.selectedTable() },
                onEdit = { replacingText = canvas?.selectedTextBox(); showTextBox = true },
                onDelete = {
                    canvas?.deleteSelectedImage()
                    edits++
                },
                onDone = { canvas?.clearImageSelection() },
                onCrop = { editingPicture = 1 },
                onStyle = { editingPicture = 2 },
                onRotate = { canvas?.rotateSelectedImage(90f); edits++ },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Crop and corner/border make a new picture file; the old one stays for undo.
        val picture = if (editingPicture != 0) canvas?.selectedPicture() else null
        val pictureBitmap = remember(picture?.id) {
            picture?.let { runCatching { android.graphics.BitmapFactory.decodeFile(store.imageFile(note.id, it.id).path) }.getOrNull() }
        }
        fun storePicture(bitmap: android.graphics.Bitmap, area: android.graphics.RectF) {
            scope.launch {
                val added = withContext(Dispatchers.IO) { store.addImage(note.id, bitmap) }
                if (added != null) { canvas?.replaceSelectedImage(added.first, area); edits++ }
                else Toast.makeText(context, "이미지를 저장하지 못했습니다", Toast.LENGTH_SHORT).show()
            }
        }
        if (picture == null || pictureBitmap == null) {
            if (editingPicture != 0) editingPicture = 0
        } else if (editingPicture == 1) {
            ImageCropDialog(pictureBitmap, onCrop = { shape, area, outline ->
                editingPicture = 0
                storePicture(cropBitmap(pictureBitmap, shape, area, outline), area)
            }, onDismiss = { editingPicture = 0 })
        } else if (editingPicture == 2) {
            ImageStyleDialog(
                opacity = picture.opacity,
                onOpacity = { canvas?.setSelectedImageOpacity(it) },
                onDecorate = { corner, border, color ->
                    editingPicture = 0
                    storePicture(decorateBitmap(pictureBitmap, corner, border, color), android.graphics.RectF(0f, 0f, 1f, 1f))
                },
                onDismiss = { editingPicture = 0; edits++ },
            )
        }
        if (editingTools) ToolbarEditDialog(onDismiss = { editingTools = false })
        if (noteMenu) {
            val meta = remember(edits) { store.list().firstOrNull { it.id == note.id } ?: note }
            var confirmDelete by remember { mutableStateOf(false) }
            var filing by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { noteMenu = false },
                title = { Text(note.title) },
                text = {
                    Column {
                        Text("보기 방식", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf(PageLayoutMode.VERTICAL to "세로 스크롤", PageLayoutMode.HORIZONTAL to "가로 스크롤",
                                PageLayoutMode.SPREAD_2X1 to "두 쪽", PageLayoutMode.GRID_2X2 to "네 쪽").forEach { (layout, label) ->
                                SettingsChoiceChip(selected = pageLayout == layout, onClick = {
                                    pageLayout = layout
                                    penStore.setPageLayoutOf(note.id, layout)
                                    canvas?.setPageLayout(layout)
                                }, label = label, modifier = Modifier.padding(end = 6.dp))
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = { store.setFavorite(note.id, !meta.favorite); edits++ }) {
                            Text(if (meta.favorite) "즐겨찾기 해제" else "즐겨찾기")
                        }
                        TextButton(onClick = { filing = true }) { Text("폴더로 이동") }
                        TextButton(onClick = {
                            noteMenu = false
                            canvas?.let { view -> exportingPages = view.snapshotPages(view.document.pages.indices.toList()) }
                            savePagesPdf.launch(safeFileName(note.title) + ".pdf")
                        }) { Text("PDF로 내보내기") }
                        TextButton(enabled = meta.locked || NoteLock(context).hasPassword, onClick = {
                            store.setLocked(note.id, !meta.locked); edits++
                        }) { Text(if (meta.locked) "잠금 해제" else "암호로 잠그기") }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("휴지통으로", color = MaterialTheme.colorScheme.error)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("필기 인식 언어 (앞쪽이 우선)", style = MaterialTheme.typography.bodyMedium)
                        var languages by remember { mutableStateOf(InkIndexer.languages(context)) }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            InkIndexer.OFFERED.forEach { (tag, label) ->
                                SettingsChoiceChip(selected = tag in languages, onClick = {
                                    val next = if (tag in languages) languages - tag else languages + tag
                                    if (next.isNotEmpty()) { languages = next; InkIndexer.setLanguages(context, next) }
                                }, label = label, modifier = Modifier.padding(end = 6.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { noteMenu = false }) { Text("닫기") } },
            )
            if (filing) FolderDialog(current = meta.folder, folders = remember { store.folders() },
                onDismiss = { filing = false }, onPick = { store.setFolder(note.id, it); filing = false; edits++ })
            if (confirmDelete) AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("이 노트를 휴지통으로 옮길까요?") },
                confirmButton = { TextButton(onClick = {
                    confirmDelete = false
                    noteMenu = false
                    store.moveToTrash(note.id)
                    onBack()
                }) { Text("옮기기") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
            )
        }
        if (showInput) InputSettingsDialog(gestures, onGestures = { setGestures(it) }, onDismiss = { showInput = false })

        pressMenu?.let { at ->
            val system = context.getSystemService(android.content.ClipboardManager::class.java)
            val clip = system?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val inkClip = InkClipboard.clips.firstOrNull()
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
                DropdownMenu(true, onDismissRequest = { pressMenu = null }) {
                    if (inkClip != null) DropdownMenuItem(text = { Text("붙여넣기 (${inkClip.size}개)") }, onClick = {
                        pressMenu = null
                        canvas?.paste(inkClip)
                        edits++
                    })
                    val pastedText = clip?.coerceToText(context)?.toString()?.takeIf { clip.uri == null && it.isNotBlank() }
                    if (pastedText != null) DropdownMenuItem(text = { Text("텍스트 붙여넣기") }, onClick = {
                        pressMenu = null
                        val content = TextBoxContent(pastedText.take(4000))
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching {
                                val bitmap = renderTextBox(content)
                                try { store.addImage(note.id, bitmap)?.let { Triple(it.first, bitmap.width, bitmap.height) } }
                                finally { bitmap.recycle() }
                            }.getOrNull() }
                            if (result != null) {
                                canvas?.putTextBox(result.first, result.second, result.third, content)
                                edits++
                            }
                        }
                    })
                    val uri = clip?.uri
                    if (uri != null) DropdownMenuItem(text = { Text("이미지 붙여넣기") }, onClick = {
                        pressMenu = null
                        scope.launch {
                            val added = withContext(Dispatchers.IO) {
                                runCatching { context.contentResolver.openInputStream(uri)?.use { store.addImage(note.id, it) } }.getOrNull()
                            }
                            if (added != null) { canvas?.insertImage(added.first, added.second); edits++ }
                            else Toast.makeText(context, "이미지를 붙여넣지 못했습니다", Toast.LENGTH_SHORT).show()
                        }
                    })
                    if (inkClip == null && pastedText == null && uri == null) {
                        DropdownMenuItem(text = { Text("붙여넣을 내용이 없습니다") }, onClick = { pressMenu = null }, enabled = false)
                    }
                }
            }
        }

        editingTable?.let { table ->
            TableDialog(table.rows.takeIf { it > 0 } ?: 3, table.cols.takeIf { it > 0 } ?: 3,
                onDismiss = { editingTable = null },
                onSave = { rows, cols, line, header ->
                    val existing = table.takeIf { it.rows > 0 }
                    editingTable = null
                    scope.launch {
                        // Wide enough for handwriting in each cell; a changed table keeps its size.
                        val width = existing?.width ?: (cols * TABLE_CELL_WIDTH)
                        val height = existing?.height ?: (rows * TABLE_CELL_HEIGHT)
                        val added = withContext(Dispatchers.IO) {
                            store.addImage(note.id, renderTable(rows, cols, (width * 2).toInt(), (height * 2).toInt(), line, header))
                        }
                        if (added != null) {
                            canvas?.putTable(added.first, rows, cols, width, height, existing)
                            mode = EditMode.IMAGE
                            edits++
                        }
                    }
                })
        }

        if (pickingSticker) StickerDialog(onPick = { sticker ->
            pickingSticker = false
            scope.launch {
                val added = withContext(Dispatchers.IO) { store.addImage(note.id, Stickers.render(sticker)) }
                if (added != null) {
                    canvas?.insertImage(added.first, added.second)
                    mode = EditMode.IMAGE
                    edits++
                }
            }
        }, onDismiss = { pickingSticker = false })

        selectionColorMode?.let { colorMode ->
            PenDialog(mode = colorMode, pen = settings.getValue(colorMode),
                prediction = prediction, onPrediction = { prediction = it; penStore.prediction = it },
                predictionLeadMs = predictionLeadMs,
                onPredictionLeadMs = {
                    predictionLeadMs = it; penStore.predictionLeadMs = it
                },
                deferDetail = deferDetail, onDeferDetail = { deferDetail = it; penStore.deferDetail = it },
                stabilizer = stabilizer,
                onStabilizer = { stabilizer = it; penStore.stabilizer = it },
                highlighterAboveInk = highlighterAboveInk,
                onHighlighterAboveInk = {
                    highlighterAboveInk = it; penStore.highlighterAboveInk = it
                },
                meshInk = meshInk,
                onMeshInk = { meshInk = it; penStore.meshInk = it },
                compatWetInk = compatWetInk,
                onCompatWetInk = { compatWetInk = it; penStore.compatWetInk = it },
                autoShapes = autoShapes,
                onAutoShapes = { autoShapes = it; penStore.autoShapes = it },
                axisSnap = axisSnap,
                onAxisSnap = { axisSnap = it; penStore.axisSnap = it },
                dottedPattern = dottedPattern,
                onDottedPattern = { dottedPattern = it; penStore.dottedPattern = it },
                onDismiss = { selectionColorMode = null }, onConfirm = {
                    settings = settings + (colorMode to it)
                    penStore.save(settings)
                    selectionColorMode = null
                })
        }
        selectedText?.let { text ->
            SelectionActions(
                preview = selectionPreview,
                highlightColor = settings.getValue(EditMode.HIGHLIGHTER).colorArgb,
                maskColor = settings.getValue(EditMode.MASK).colorArgb,
                onHighlightColor = { selectionColorMode = EditMode.HIGHLIGHTER },
                onMaskColor = { selectionColorMode = EditMode.MASK },
                onCopy = {
                    clipboard.setText(AnnotatedString(LatexClipboardText.convert(text)))
                    canvas?.clearSelection()
                },
                onHighlight = {
                    canvas?.highlightSelection(settings.getValue(EditMode.HIGHLIGHTER).colorArgb)
                    edits++
                },
                onMask = {
                    canvas?.maskSelection(settings.getValue(EditMode.MASK).colorArgb)
                    edits++
                },
                onStrike = {
                    canvas?.strikeSelection(settings.getValue(EditMode.PEN).colorArgb)
                    edits++
                },
                // The device's own translators (Google Translate, DeepL, Papago...) answer this.
                onTranslate = { sendSelectedText(context, text, android.content.Intent.ACTION_PROCESS_TEXT) },
                onSearch = {
                    webUrl = SEARCH_HOME + "search?q=" + java.net.URLEncoder.encode(text, "UTF-8")
                    canvas?.clearSelection()
                },
                onShare = { sendSelectedText(context, text, android.content.Intent.ACTION_SEND) },
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
                pdfOutline = pdfOutline,
                document = canvas?.document,
                currentPage = currentPage,
                edits = edits,
                study = study,
                studyRevision = studyRevision,
                onRenameMask = { index, maskIndex -> renamingMask = index to maskIndex },
                onAnswerMask = { index, maskIndex, right ->
                    canvas?.document?.pages?.getOrNull(index)?.masks?.getOrNull(maskIndex)?.let { mask ->
                        study.answer(MaskStudy.keyOf(canvas!!.document.pages[index].id, mask.stroke), right)
                        studyRevision++
                    }
                },
                onStudy = { index, wrongOnly ->
                    val page = canvas?.document?.pages?.getOrNull(index)
                    val queue = if (page == null) emptyList() else page.masks.indices.filter { maskIndex ->
                        !wrongOnly || study.record(MaskStudy.keyOf(page.id, page.masks[maskIndex].stroke)).last ==
                            MaskRecord.RESULT_WRONG
                    }
                    if (queue.isNotEmpty()) {
                        canvas?.setMasksRevealed(index, false)
                        canvas?.scrollToPage(index)
                        session = StudySession(index, queue)
                        showPages = false
                        edits++
                    }
                },
                userTemplates = remember(edits) { store.pageTemplates() },
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
                onMove = { index, delta ->
                    canvas?.movePage(index, delta)
                    edits++
                },
                onDuplicate = {
                    canvas?.duplicatePage(it)
                    edits++
                },
                onBackground = { index, background ->
                    canvas?.setBackground(index, background)
                    edits++
                },
                onTemplate = { index, templateId ->
                    canvas?.setPageTemplate(index, templateId)
                    edits++
                },
                onSetToc = { index, title ->
                    canvas?.setPageToc(index, title)
                    edits++
                },
                onHighlightToc = { index, highlighted ->
                    canvas?.setPageTocHighlighted(index, highlighted)
                    edits++
                },
                onTocLevel = { index, level ->
                    canvas?.setPageTocLevel(index, level)
                    edits++
                },
                onBookmark = { index, marked ->
                    canvas?.setPageBookmarked(index, marked)
                    edits++
                },
                onRotate = { index, clockwise ->
                    canvas?.rotatePage(index, clockwise)
                    edits++
                },
                onBackgroundAll = { background, templateId ->
                    canvas?.setBackgroundAll(background, templateId)
                    edits++
                },
                onDeletePages = { canvas?.deletePages(it); edits++ },
                onPageSize = { index, w, h, all -> canvas?.setPageSize(index, w, h, all); edits++ },
                onDuplicatePages = { indices ->
                    // Last first, so each copy lands right after its own page.
                    indices.sortedDescending().forEach { canvas?.duplicatePage(it) }
                    edits++
                },
                onExportPages = { indices ->
                    exportingPages = canvas?.snapshotPages(indices)
                    if (exportingPages != null) savePagesPdf.launch(safeFileName(note.title) + "-선택.pdf")
                },
                onSendPages = { indices, move -> sendingPages = indices to move },
            )
        }

        // Scale from nothing rather than sliding in from an edge: it opens
        // from wherever the gesture was, not from a side of the screen.
        // Qualified like the sidebar's below: the enclosing scopes put more
        // than one overload in reach, and this is the one without a receiver.
        val activeReferenceNoteId = referenceNoteId
            .takeIf { id -> referenceNotes.any { it.id == id } }
            ?: note.id
        androidx.compose.animation.AnimatedVisibility(
            visible = referenceOpen,
            enter = fadeIn(tween(140)) +
                scaleIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialScale = 0.94f,
                    transformOrigin = TransformOrigin(0.5f, 0.18f),
                ) +
                slideInVertically(animationSpec = tween(190)) { -it / 18 },
            exit = fadeOut(tween(110)) +
                scaleOut(tween(130), targetScale = 0.97f) +
                slideOutVertically(animationSpec = tween(130)) { it / 22 },
        ) {
            key(activeReferenceNoteId) { ReferencePanel(
                store = store,
                sharedDocument = if (activeReferenceNoteId == note.id) canvas?.document else null,
                onSharedOpen = { canvas?.suspendSharedDetail() },
                onSharedChanged = { canvas?.refreshSharedInk() },
                notes = referenceNotes,
                // The remembered note may have been deleted since; fall back to
                // the one being written on rather than to a blank panel.
                noteId = activeReferenceNoteId,
                page = referencePage,
                mode = mode,
                tool = tool,
                pen = pen,
                shapeKind = shapeKind,
                straightLine = straightLine,
                eraserWidth = eraserWidth,
                deferDetail = deferDetail,
                stabilizer = stabilizer,
                highlighterAboveInk = highlighterAboveInk,
                meshInk = meshInk,
                compatWetInk = compatWetInk,
                partialEraser = partialEraser,
                // Circle to lasso stays off in a zoom box, as in the reference app.
                gestures = if (zoomBox) gestures.copy(circleToLasso = false) else gestures,
                autoShapes = autoShapes,
                axisSnap = axisSnap,
                dottedPattern = dottedPattern,
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
                        view?.onNextResize(factor, fitInstead = false)
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
                    val count = referenceNotes.firstOrNull { candidate -> candidate.id == it }
                        ?.pageCount ?: 1
                    // 다른 노트로 바꿀 때도 그 노트를 마지막으로 읽던 페이지에서 엽니다.
                    referencePage = restoredPage(penStore.lastPage(it), count)
                },
                onPageChange = {
                    referencePage = it
                    // 일반 화면과 팝업 화면이 같은 마지막 페이지 값을 공유합니다.
                    penStore.setLastPage(activeReferenceNoteId, it)
                },
                onDrag = ::moveReference,
                onClose = { referenceStretch = 1f; referenceOpen = false },
            ) }
        }
    }
        if (docked && !collapsed) {
            toolbar(Modifier.align(Alignment.TopCenter).then(
                if (toolbarSize > 0) Modifier.spotiToolbarAnimatedWidth(floatingBarWidth)
                    .windowInsetsPadding(ChromeInsets.only(WindowInsetsSides.Top))
                else Modifier.fillMaxWidth()))
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
                        .pointerInput(webWidth) {
                            detectDragGestures(
                                onDragStart = { draftWebWidth = webWidth },
                                onDragEnd = {
                                    draftWebWidth?.let { webWidth = it }
                                    draftWebWidth = null
                                },
                                onDragCancel = { draftWebWidth = null },
                            ) { change, drag ->
                                change.consume()
                                draftWebWidth = ((draftWebWidth ?: webWidth) - drag.x.toDp())
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
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            panel(Modifier.fillMaxSize(0.85f))
        }
    }
}

/**
 * A run through one page's tape: lift the strip to check, then say whether the
 * answer was right. At the end, the misses can be gone over again on their own.
 */
@Composable
private fun StudyBar(
    session: StudySession,
    name: String,
    onShow: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onRetryMissed: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SkinSurface(
        modifier = modifier
            .windowInsetsPadding(ChromeInsets)
            .padding(16.dp),
        corner = 16.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (session.done) {
                Text(
                    "${session.queue.size}개 중 ${session.right}개 맞음",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ToolbarDivider()
                if (session.missed.isNotEmpty()) {
                    TextButton(onClick = onRetryMissed) { Text("틀린 ${session.missed.size}개 다시") }
                }
            } else {
                Text(
                    "${session.position + 1} / ${session.queue.size}" +
                        if (name.isNotBlank()) " · $name" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ToolbarDivider()
                if (!session.showing) {
                    TextButton(onClick = onShow) { Text("정답 보기") }
                } else {
                    TextButton(onClick = { onAnswer(true) }) {
                        Text("맞음", color = Color(0xFF2E7D32))
                    }
                    TextButton(onClick = { onAnswer(false) }) {
                        Text("틀림", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            TextButton(onClick = onClose) { Text("끝내기") }
        }
    }
}

/** What the loop caught, and what can be done with it. */
@Composable
private fun LassoActions(
    count: Int,
    onDelete: () -> Unit,
    onOcr: (() -> Unit)? = null,
    onTransform: ((scale: Float, degrees: Float) -> Unit)? = null,
    onRecolor: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onCut: (() -> Unit)? = null,
    locked: Boolean = false,
    onLock: ((Boolean) -> Unit)? = null,
    onAddToLibrary: (() -> Unit)? = null,
    onLink: (() -> Unit)? = null,
    grouped: Boolean = false,
    onGroup: ((Boolean) -> Unit)? = null,
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
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$count 개 · 끌어서 이동", style = MaterialTheme.typography.bodyMedium)
            ToolbarDivider()
            if (onTransform != null) {
                IconButton(onClick = { onTransform(0.8f, 0f) }) {
                    Icon(Reicons.Remove, contentDescription = "축소")
                }
                IconButton(onClick = { onTransform(1.25f, 0f) }) {
                    Icon(Reicons.Add, contentDescription = "확대")
                }
                IconButton(onClick = { onTransform(1f, 15f) }) {
                    Icon(Reicons.Refresh, contentDescription = "15° 회전")
                }
            }
            if (onRecolor != null) IconButton(onClick = onRecolor) {
                Icon(Reicons.Palette, contentDescription = "현재 펜 색으로")
            }
            if (onDuplicate != null) IconButton(onClick = onDuplicate) {
                Icon(Reicons.ContentCopy, contentDescription = "복제")
            }
            if (onCopy != null) TextButton(onClick = onCopy) { Text("복사") }
            if (onCut != null) TextButton(onClick = onCut) { Text("잘라내기") }
            if (onLink != null) TextButton(onClick = onLink) { Text("링크") }
            if (onAddToLibrary != null) TextButton(onClick = onAddToLibrary) { Text("라이브러리에 추가") }
            if (onGroup != null) TextButton(onClick = { onGroup(!grouped) }) {
                Text(if (grouped) "그룹 해제" else "그룹")
            }
            if (onLock != null) TextButton(onClick = { onLock(!locked) }) {
                Text(if (locked) "잠금 해제" else "잠금")
            }
            TextButton(onClick = onDelete) {
                Icon(Reicons.Delete, contentDescription = null)
                Text(" 삭제")
            }
            if (onOcr != null) TextButton(onClick = onOcr) { Text("OCR") }
            TextButton(onClick = onDone) { Text("완료") }
        }
    }
}

/**
 * The lasso in hand with nothing caught: how the next loop selects, and what
 * was copied, the newest pasted with one tap and the rest of the last ten
 * from the list beside it.
 */
@Composable
private fun PasteBar(
    clipRevision: Int,
    wholeOnly: Boolean,
    onWholeOnly: (Boolean) -> Unit,
    onPaste: (InkClipboard.Clip) -> Unit,
    gestures: CanvasGestures = CanvasGestures(),
    onGestures: (CanvasGestures) -> Unit = {},
    onLibrary: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val clips = remember(clipRevision) { InkClipboard.clips.toList() }
    var open by remember { mutableStateOf(false) }
    SkinSurface(
        modifier = modifier
            .windowInsetsPadding(ChromeInsets)
            .padding(16.dp),
        corner = 16.dp,
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onLibrary) { Text("라이브러리") }
            SettingsChoiceChip(selected = wholeOnly, onClick = { onWholeOnly(!wholeOnly) },
                label = "완전히 포함된 것만")
            SettingsChoiceChip(selected = gestures.snapToAlign,
                onClick = { onGestures(gestures.copy(snapToAlign = !gestures.snapToAlign)) },
                label = "정렬 스냅", modifier = Modifier.padding(start = 6.dp))
            SettingsChoiceChip(selected = gestures.keepAspect,
                onClick = { onGestures(gestures.copy(keepAspect = !gestures.keepAspect)) },
                label = "비율 유지", modifier = Modifier.padding(start = 6.dp))
            ToolbarDivider()
            var types by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { types = true }) { Text("선택 대상") }
                DropdownMenu(types, onDismissRequest = { types = false }) {
                    listOf(
                        "펜" to gestures.lassoInk,
                        "형광펜" to gestures.lassoHighlighter,
                        "이미지" to gestures.lassoPictures,
                        "텍스트 상자" to gestures.lassoText,
                        "잠긴 객체" to gestures.selectLocked,
                    ).forEachIndexed { index, (label, on) ->
                        DropdownMenuItem(
                            text = { Text((if (on) "✓ " else "    ") + label) },
                            onClick = {
                                onGestures(when (index) {
                                    0 -> gestures.copy(lassoInk = !on)
                                    1 -> gestures.copy(lassoHighlighter = !on)
                                    2 -> gestures.copy(lassoPictures = !on)
                                    3 -> gestures.copy(lassoText = !on)
                                    else -> gestures.copy(selectLocked = !on)
                                })
                            },
                        )
                    }
                }
            }
            if (clips.isNotEmpty()) {
                ToolbarDivider()
                TextButton(onClick = { onPaste(clips.first()) }) { Text("붙여넣기") }
                Box {
                    TextButton(onClick = { open = true }) { Text("클립보드 ${clips.size}") }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        clips.forEachIndexed { index, clip ->
                            DropdownMenuItem(
                                text = { Text("${index + 1}. ${clip.size} 개") },
                                onClick = { open = false; onPaste(clip) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Delete or let go of the picture in hand. */
@Composable
private fun ImageActions(
    isText: Boolean,
    box: Int = PageImage.BOX_NONE,
    onFold: () -> Unit = {},
    onTable: () -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    onCrop: () -> Unit = {},
    onStyle: () -> Unit = {},
    onRotate: () -> Unit = {},
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
            Text(when (box) {
                PageImage.BOX_STICKY -> "스티키 노트"
                PageImage.BOX_TABLE -> "표"
                else -> if (isText) "텍스트" else "사진"
            }, style = MaterialTheme.typography.bodyMedium)
            if (box == PageImage.BOX_STICKY) TextButton(onClick = onFold) { Text("접기·펼치기") }
            if (box == PageImage.BOX_TABLE) TextButton(onClick = onTable) { Text("표 편집") }
            else if (isText) TextButton(onClick = onEdit) { Text("편집") }
            else {
                TextButton(onClick = onCrop) { Text("자르기") }
                TextButton(onClick = onStyle) { Text("스타일") }
            }
            IconButton(onClick = onRotate) { Icon(Reicons.Refresh, contentDescription = "90° 회전") }
            ToolbarDivider()
            TextButton(onClick = onDelete) {
                Icon(Reicons.Delete, contentDescription = null)
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

/** Copies [file] where the share sheet may read it and offers it to other apps. */
internal fun shareFile(context: android.content.Context, file: java.io.File, mime: String) {
    val shared = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val copy = java.io.File(shared, file.name)
    if (runCatching { file.copyTo(copy, overwrite = true) }.isFailure) return
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", copy)
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, null))
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
    sharedDocument: Document?,
    onSharedOpen: () -> Unit,
    onSharedChanged: () -> Unit,
    /** This note first, then every other one - what the picker offers. */
    notes: List<NoteMeta>,
    noteId: String,
    page: Int,
    mode: EditMode,
    tool: Tool,
    pen: PenPreset,
    shapeKind: ShapeKind,
    straightLine: Boolean,
    eraserWidth: Float,
    deferDetail: Boolean,
    stabilizer: Int,
    highlighterAboveInk: Boolean,
    meshInk: Boolean,
    compatWetInk: Boolean,
    partialEraser: Boolean,
    gestures: CanvasGestures,
    autoShapes: Boolean,
    axisSnap: Boolean,
    dottedPattern: Int,
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
    val lifecycleOwner = context as? LifecycleOwner
    val density = LocalDensity.current
    var opened by remember { mutableStateOf<Pair<Document, PdfSource?>?>(null) }
    var view by remember { mutableStateOf<InkCanvasView?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    val latestDrag by rememberUpdatedState(onDrag)
    val latestStretchEnd by rememberUpdatedState(onStretchEnd)
    val latestClose by rememberUpdatedState(onClose)
    fun closePanel() {
        val ready: () -> Unit = {
            view?.currentPageIndex()?.let(onPageChange)
            latestClose()
        }
        view?.requestSafeClose(ready) ?: ready()
    }
    val latestSharedChanged by rememberUpdatedState(onSharedChanged)
    DisposableEffect(sharedDocument) {
        if (sharedDocument != null) onSharedOpen()
        onDispose { if (sharedDocument != null) latestSharedChanged() }
    }
    val imageCache = remember { object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    } }
    var popupEdits by remember { mutableIntStateOf(0) }
    var popupLassoCount by remember { mutableIntStateOf(0) }
    var noteMenu by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf(false) }
    val title = notes.find { it.id == noteId }?.title ?: noteId
    // What has been typed into the picker's search box. Cleared with the menu,
    // so opening it again offers everything rather than the last hunt.
    var noteQuery by remember { mutableStateOf("") }

    // A different note is a different document and a different PDF, decoded
    // the same way opening one from the list is - off the main thread, since
    // parsing a PDF header there is a visible freeze.
    LaunchedEffect(noteId, sharedDocument, loadAttempt) {
        opened = null
        loadFailed = false
        var pendingPdf: PdfSource? = null
        try {
            opened = withContext(Dispatchers.IO) {
                val document = sharedDocument ?: store.load(noteId)
                pendingPdf = PdfSource.open(store.pdfFile(noteId), PdfSource.cacheBytesFor(context))
                document to pendingPdf
            }
            pendingPdf = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            pendingPdf?.close()
        }
    }
    // The panel owns both its PDF source and its pending edits. Flush on app
    // background as well as close; the generation guard makes both events safe.
    DisposableEffect(noteId, lifecycleOwner) {
        fun flushLatest() {
            view?.let { store.flushSave(noteId, title, it.document) }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) flushLatest()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            flushLatest()
            if (view == null) opened?.second?.close()
            imageCache.evictAll()
        }
    }
    // A note or a page change refits the page to the panel, when that is asked
    // for. Not a resize: a resize now scales the page along with its frame, and
    // refitting after one would undo exactly that.
    LaunchedEffect(view, noteId, opened, fit) {
        val v = view ?: return@LaunchedEffect
        if (fit) v.fitWidth()
        v.scrollToPage(page)
    }
    LaunchedEffect(mode, view) {
        if (mode != EditMode.LASSO) {
            view?.clearLassoSelection()
            popupLassoCount = 0
        }
    }

    // Match the main note's quiet-period save. Closing or backgrounding bypasses
    // this delay through flushSave above, so debounce does not risk data loss.
    LaunchedEffect(popupEdits) {
        if (popupEdits == 0) return@LaunchedEffect
        delay(AUTOSAVE_DELAY_MS)
        val v = view ?: return@LaunchedEffect
        store.saveLater(noteId, title, v.document)
    }

    val pageCount = opened?.first?.pages?.size ?: 0
    val panelShape = RoundedCornerShape(16.dp)
    val panelBorder = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.94f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.76f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.34f),
            Color.White.copy(alpha = 0.78f),
        ),
    )

    Box(
        Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
            .drawWithContent {
                drawContent()
                if (abs(stretch - 1f) > 0.001f) {
                    drawRect(
                        color = Color(0xFF3B7DDD),
                        size = Size(this.size.width * stretch, this.size.height * stretch),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
    ) {
    SkinSurface(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer {
                scaleX = stretch; scaleY = stretch
                transformOrigin = TransformOrigin(0f, 0f)
            }
            // 유리 표면이 밝은 페이지와 겹쳐도 팝업 외곽을 잃지 않도록 밝은 림과
            // 테마 윤곽색을 함께 사용합니다.
            .border(1.5.dp, panelBorder, panelShape),
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
                        Icon(Reicons.ArrowDropDown, contentDescription = "다른 노트")
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
                            leadingIcon = { Icon(Reicons.Search, contentDescription = null) },
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
                                        Icon(Reicons.Check, contentDescription = null)
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
                                            Icon(Reicons.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        pageMenu = false
                                        view?.scrollToPage(index)
                                        onPageChange(index)
                                    },
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { onFit(!fit) }) {
                    Icon(
                        Reicons.FitScreen,
                        contentDescription = "크기에 맞추기",
                        tint = if (fit) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
                IconButton(
                    modifier = Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false,
                                pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            down.consume()
                            closePanel()
                        }
                    },
                    onClick = ::closePanel,
                ) {
                    Icon(Reicons.Close, contentDescription = "참고 화면 닫기")
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val ready = opened
                if (ready == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (loadFailed) TextButton(onClick = { loadAttempt++ }) { Text("노트 열기 실패 · 다시 시도") }
                        else CircularProgressIndicator()
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            InkCanvasView(viewContext).apply {
                                // 팝업 안에서는 페이지 폭이 패널 폭보다 작아지는
                                // 축소를 허용하지 않습니다. 확대와 필기는 그대로입니다.
                                minimumScaleIsFitWidth = true
                                // 작은 팝업에서는 같은 손가락 이동으로 더 많은 페이지를
                                // 넘길 수 있게 하고, 관성 속도에도 같은 배율을 적용합니다.
                                viewportPanMultiplier = REFERENCE_SCROLL_MULTIPLIER
                                pageLoader = { p, epsilon -> store.loadPage(noteId, p, epsilon) }
                                maskLoader = { p, epsilon -> store.loadMasks(noteId, p, epsilon) }
                                imageLoader = { imageId ->
                                    imageCache.get("i:$imageId") ?: runCatching {
                                        android.graphics.BitmapFactory
                                            .decodeFile(store.imageFile(noteId, imageId).path)
                                    }.getOrNull()?.also { imageCache.put("i:$imageId", it) }
                                }
                                templateLoader = { templateId ->
                                    imageCache.get("t:$templateId") ?: runCatching { android.graphics.BitmapFactory.decodeFile(
                                        store.templateFile(templateId).path) }.getOrNull()?.also { imageCache.put("t:$templateId", it) }
                                }
                                open(ready.first, ready.second, initialPage = page)
                                onStrokesChanged = {
                                    popupEdits++
                                    if (sharedDocument != null) latestSharedChanged()
                                }
                                onLassoSelected = { popupLassoCount = it }
                                onCurrentPageChanged = { onPageChange(it) }
                                // Already open by definition - three fingers on
                                // this view only ever move or resize it, never
                                // open a reference panel of its own.
                                referenceOpen = true
                                onReferenceDrag = { x, y, factor -> latestDrag(x, y, factor) }
                                onReferenceDragEnd = { latestStretchEnd(this) }
                                onCloseReference = { closePanel() }
                                closeReferenceOnDownwardDrag = true
                                onUndo = { undo(); popupEdits++ }
                                onRedo = { redo(); popupEdits++ }
                                view = this
                            }
                        },
                        update = { v ->
                            v.tool = tool
                            v.readMode = mode == EditMode.READ
                            v.maskMode = mode == EditMode.MASK
                            v.lassoMode = mode == EditMode.LASSO
                            v.imageMode = mode == EditMode.IMAGE || mode == EditMode.TEXT
                            v.shapeKind = when {
                                mode == EditMode.SHAPE -> shapeKind
                                straightLine &&
                                    (mode == EditMode.HIGHLIGHTER || mode == EditMode.MASK) ->
                                    ShapeKind.LINE
                                else -> null
                            }
                            v.colorArgb = if (tool == Tool.MASK) {
                                pen.colorArgb or 0xFF000000.toInt()
                            } else {
                                pen.colorArgb
                            }
                            v.strokeWidth = pen.width
                            v.prepareBrush()
                            v.eraserWidth = eraserWidth
                            v.deferDetail = deferDetail
                            v.stabilizer = stabilizer
                            v.highlighterAboveInk = highlighterAboveInk
                            v.meshInk = meshInk
                            v.compatWetInk = compatWetInk
                            v.partialEraser = partialEraser
                            v.applyGestures(gestures)
                            v.autoShapeRecognitionEnabled = autoShapes
                            v.axisSnapEnabled = axisSnap
                            v.dottedPattern = dottedPattern
                        },
                    )
                    if (popupLassoCount > 0) {
                        LassoActions(
                            count = popupLassoCount,
                            onDelete = { view?.deleteLassoSelection() },
                            onTransform = { factor, degrees ->
                                view?.transformLassoSelection(factor, degrees)
                            },
                            onRecolor = { view?.recolorLassoSelection(pen.colorArgb) },
                            onDuplicate = { view?.duplicateLassoSelection() },
                            onDone = { view?.clearLassoSelection() },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }

    }
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("폴더 이름") },
        ) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
            Text("저장")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** What you can do with text lifted off a PDF page. */
@Composable
private fun SelectionActions(
    preview: Bitmap?,
    highlightColor: Int,
    maskColor: Int,
    onHighlightColor: () -> Unit,
    onMaskColor: () -> Unit,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onMask: () -> Unit,
    onDismiss: () -> Unit,
    onStrike: () -> Unit = {},
    onTranslate: () -> Unit = {},
    onSearch: () -> Unit = {},
    onShare: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SkinSurface(
        modifier = modifier
            .windowInsetsPadding(ChromeInsets)
            .padding(20.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (preview != null) Image(preview.asImageBitmap(), "선택 영역 원본",
                modifier = Modifier.width(220.dp).heightIn(max = 110.dp), contentScale = ContentScale.Fit)
            else Text("선택 영역", modifier = Modifier.width(100.dp))
            ToolbarDivider()
            TextButton(onClick = onHighlightColor) {
                Box(Modifier.size(22.dp).background(Color(highlightColor or 0xFF000000.toInt()), CircleShape))
                Text(" 색상")
            }
            TextButton(onClick = onHighlight) {
                Icon(Reicons.Brush, contentDescription = null)
                Text(" 형광펜")
            }
            TextButton(onClick = onMaskColor) {
                Box(Modifier.size(22.dp).background(Color(maskColor or 0xFF000000.toInt()), CircleShape))
                Text(" 색상")
            }
            TextButton(onClick = onMask) {
                Icon(Reicons.VisibilityOff, contentDescription = null)
                Text(" 마스킹")
            }
            TextButton(onClick = onStrike) { Text("취소선") }
            TextButton(onClick = onCopy) {
                Icon(Reicons.ContentCopy, contentDescription = null)
                Text(" 복사")
            }
            TextButton(onClick = onTranslate) { Text("번역") }
            TextButton(onClick = onSearch) { Text("검색") }
            TextButton(onClick = onShare) { Text("공유") }
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    }
}

/**
 * A page-scale scrollbar: the whole note is reachable with one short drag.
 *
 * The visible thumb is deliberately smaller than its 52dp touch target. It
 * borrows the current skin's surface, so Liquid Glass gets the same refracting
 * capsule as the rest of the chrome without making the rail visually heavy.
 */
@Composable
private fun PageScrubber(
    currentPage: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    val page = restoredPage(currentPage, pageCount)
    val fraction = page.toFloat() / (pageCount - 1).toFloat()
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val liquid = LocalSkin.current.isRefractive
    val thumbHeight = 52.dp
    val thumbHeightPx = with(density) { thumbHeight.toPx() }
    var trackHeight by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    val thumbWidth by animateDpAsState(
        targetValue = if (dragging && liquid) 40.dp else 34.dp,
        animationSpec = tween(120),
        label = "page scrubber width",
    )
    val travel = (trackHeight - thumbHeightPx).coerceAtLeast(0f)
    val thumbY = (fraction * travel).roundToInt()

    fun jumpAt(y: Float) {
        onJump(scrubbedPage(y, trackHeight.toFloat(), thumbHeightPx, pageCount))
    }

    Box(
        modifier
            .width(52.dp)
            .onSizeChanged { trackHeight = it.height }
            .pointerInput(pageCount, trackHeight, onJump) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        jumpAt(it.y)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    jumpAt(change.position.y)
                }
            }
            .pointerInput(pageCount, trackHeight, onJump) {
                detectTapGestures { jumpAt(it.y) }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(if (liquid) 6.dp else 4.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(scheme.onSurface.copy(alpha = if (liquid) 0.16f else 0.12f)),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction)
                        .clip(CircleShape)
                        .background(scheme.primary.copy(alpha = if (liquid) 0.84f else 1f)),
                )
            }
        }
        SkinSurface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, thumbY) }
                .size(thumbWidth, thumbHeight),
            corner = 100.dp,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "${page + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PageSidebar(
    pdfOutline: List<NoteStore.PdfOutlineEntry> = emptyList(),
    document: Document?,
    currentPage: Int,
    // Read so that laying tape down or peeling it off redraws the list; the
    // canvas owns the pages and Compose cannot see into them.
    edits: Int,
    userTemplates: List<UserPageTemplate>,
    onJump: (Int) -> Unit,
    onReveal: (Int, Boolean) -> Unit,
    onClearMasks: (Int) -> Unit,
    onRevealMask: (Int, Int, Boolean) -> Unit,
    onDeleteMask: (Int, Int) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDuplicate: (Int) -> Unit,
    onBackground: (Int, PageBackground) -> Unit,
    onTemplate: (Int, String) -> Unit,
    onSetToc: (Int, String?) -> Unit,
    onHighlightToc: (Int, Boolean) -> Unit,
    onTocLevel: (Int, Int) -> Unit = { _, _ -> },
    onBookmark: (Int, Boolean) -> Unit = { _, _ -> },
    onRotate: (Int, Boolean) -> Unit = { _, _ -> },
    onBackgroundAll: (PageBackground, String?) -> Unit = { _, _ -> },
    onDeletePages: (List<Int>) -> Unit = {},
    onDuplicatePages: (List<Int>) -> Unit = {},
    onExportPages: (List<Int>) -> Unit = {},
    onSendPages: (List<Int>, Boolean) -> Unit = { _, _ -> },
    onPageSize: (Int, Float, Float, Boolean) -> Unit = { _, _, _, _ -> },
    study: MaskStudy? = null,
    studyRevision: Int = 0,
    onRenameMask: (Int, Int) -> Unit = { _, _ -> },
    onAnswerMask: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
    onStudy: (Int, Boolean) -> Unit = { _, _ -> },
) {
    val pages = document?.pages ?: return
    var tab by remember { mutableIntStateOf(0) }
    var editingPage by remember { mutableStateOf<Page?>(null) }
    var bookmarksOnly by remember { mutableStateOf(false) }
    var tocQuery by remember { mutableStateOf("") }
    var showPdfOutline by remember { mutableStateOf(true) }
    var sizingPage by remember { mutableStateOf<Int?>(null) }
    sizingPage?.let { index ->
        pages.getOrNull(index)?.let { page ->
            PageSizeDialog(page.width, page.height, onDismiss = { sizingPage = null }) { w, h, all ->
                onPageSize(index, w, h, all)
                sizingPage = null
            }
        }
    }
    var choosing by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }
    fun chosenIndices() = pages.indices.filter { pages[it].id in chosen }
    var goingTo by remember { mutableStateOf(false) }
    if (goingTo) GoToPageDialog(pages.size, onGo = { onJump(it) }, onDismiss = { goingTo = false })
    var tocName by remember { mutableStateOf("") }
    fun editToc(page: Page) {
        editingPage = page
        tocName = page.tocTitle.orEmpty()
    }
    editingPage?.let { page ->
        val index = pages.indexOf(page)
        if (index >= 0) AlertDialog(
            onDismissRequest = { editingPage = null },
            title = { Text("${index + 1}쪽 목차") },
            text = {
                OutlinedTextField(
                    value = tocName,
                    onValueChange = { tocName = it },
                    label = { Text("목차 이름") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetToc(index, tocName)
                    editingPage = null
                }, enabled = tocName.isNotBlank()) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = { editingPage = null }) { Text("취소") }
            },
        )
    }
    SkinSurface(
        modifier = Modifier
            .windowInsetsPadding(ChromeInsets)
            .padding(12.dp)
            .width(240.dp)
            .fillMaxHeight(0.8f),
    ) {
        Column {
            LiquidSegmentedControl(
                segments = listOf("페이지", "목차", "마스킹"),
                selectedIndex = tab,
                onSelected = { tab = it },
                useLiquidGlass = LocalSkin.current.isRefractive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            )
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tab == 1 && pages.any { it.tocTitle != null }) {
                    item {
                        OutlinedTextField(
                            value = tocQuery,
                            onValueChange = { tocQuery = it },
                            placeholder = { Text("목차 검색") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (tab == 0) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = bookmarksOnly,
                                onClick = { bookmarksOnly = !bookmarksOnly },
                                label = { Text("북마크만") },
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { choosing = !choosing; chosen.clear() }) {
                                Text(if (choosing) "선택 끝" else "선택")
                            }
                            TextButton(onClick = { goingTo = true }) { Text("쪽 이동") }
                        }
                    }
                }
                // The PDF's own outline: shown or filtered out, followed with a tap, never deleted.
                if (tab == 1 && pdfOutline.isNotEmpty()) {
                    item {
                        FilterChip(selected = showPdfOutline, onClick = { showPdfOutline = !showPdfOutline },
                            label = { Text("PDF 목차 ${pdfOutline.size}") })
                    }
                    if (showPdfOutline) items(pdfOutline.filter {
                        tocQuery.isBlank() || it.title.contains(tocQuery.trim(), ignoreCase = true)
                    }) { entry ->
                        val target = pages.indexOfFirst { it.background == PageBackground.PDF && it.pdfPageIndex == entry.pdfPage }
                        Text(
                            entry.title.ifBlank { "(제목 없음)" } + "  · ${entry.pdfPage + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = target >= 0) { onJump(target) }
                                .padding(start = (8 + entry.level.coerceAtMost(4) * 12).dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                }
                if (tab == 1 && pages.any { it.tocTitle != null } && pdfOutline.isNotEmpty()) {
                    item { Text("내 목차", style = MaterialTheme.typography.labelMedium) }
                }
                if (tab == 1 && pages.none { it.tocTitle != null } && pdfOutline.isEmpty()) {
                    item {
                        Text(
                            "등록된 목차가 없습니다. 현재 쪽을 추가하거나 페이지를 길게 눌러 설정하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(
                    when {
                        tab == 1 -> pages.filter { page ->
                            val title = page.tocTitle
                            title != null && (tocQuery.isBlank() || title.contains(tocQuery.trim(), ignoreCase = true))
                        }
                        tab == 0 && bookmarksOnly -> pages.filter { it.bookmarked }
                        else -> pages
                    },
                    key = { it.id },
                ) { page ->
                    val index = pages.indexOf(page)
                    if (tab == 0 && choosing) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (page.id in chosen) chosen.remove(page.id) else chosen.add(page.id)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = page.id in chosen, onCheckedChange = null)
                            Text("${index + 1}쪽", style = MaterialTheme.typography.titleSmall)
                        }
                    } else if (tab == 0) {
                        PageChip(
                            index = index,
                            page = page,
                            selected = index == currentPage,
                            deletable = pages.size > 1,
                            onJump = { onJump(index) },
                            onDelete = { onDelete(index) },
                            onMove = { delta -> onMove(index, delta) },
                            onDuplicate = { onDuplicate(index) },
                            onBackground = { onBackground(index, it) },
                            userTemplates = userTemplates,
                            onTemplate = { onTemplate(index, it) },
                            onEditToc = { editToc(page) },
                            onBookmark = { onBookmark(index, !page.bookmarked) },
                            onRotate = { clockwise -> onRotate(index, clockwise) },
                            onBackgroundAll = { onBackgroundAll(page.background, page.templateId) },
                            onPageSize = { sizingPage = index },
                        )
                    } else if (tab == 1) {
                        TocChip(
                            index = index,
                            page = page,
                            selected = index == currentPage,
                            onJump = { onJump(index) },
                            onEdit = { editToc(page) },
                            onHighlight = { onHighlightToc(index, !page.tocHighlighted) },
                            onDelete = { onSetToc(index, null) },
                            onIndent = { onTocLevel(index, page.tocLevel + 1) },
                            onOutdent = { onTocLevel(index, page.tocLevel - 1) },
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
                            study = study,
                            studyRevision = studyRevision,
                            onRenameMask = { maskIndex -> onRenameMask(index, maskIndex) },
                            onAnswerMask = { maskIndex, right -> onAnswerMask(index, maskIndex, right) },
                            onStudy = { wrongOnly -> onStudy(index, wrongOnly) },
                        )
                    }
                }
            }
            if (tab == 0 && choosing) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
                    val any = chosen.isNotEmpty()
                    TextButton(enabled = any && chosen.size < pages.size, onClick = {
                        onDeletePages(chosenIndices()); chosen.clear()
                    }) { Text("삭제") }
                    TextButton(enabled = any, onClick = { onDuplicatePages(chosenIndices()); chosen.clear() }) { Text("복제") }
                    TextButton(enabled = any, onClick = { onExportPages(chosenIndices()) }) { Text("PDF") }
                    TextButton(enabled = any, onClick = { onSendPages(chosenIndices(), false) }) { Text("복사…") }
                    TextButton(enabled = any && chosen.size < pages.size, onClick = {
                        onSendPages(chosenIndices(), true); chosen.clear()
                    }) { Text("이동…") }
                }
            } else if (tab == 0) {
                TextButton(
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                ) {
                    Icon(Reicons.Add, contentDescription = null)
                    Text(" 페이지")
                }
            } else if (tab == 1) {
                TextButton(
                    onClick = { pages.getOrNull(currentPage)?.let(::editToc) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Icon(Reicons.Add, contentDescription = null)
                    Text(" 현재 쪽 목차 설정")
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
 * Paper sizes as other apps count them - pixels at 300 dpi - so a size typed
 * from one of them comes out the same. Pages here are kept at half that.
 */
internal val PAGE_SIZES = listOf(
    "A4" to (2480 to 3508), "A3" to (3508 to 4960), "A5" to (1748 to 2480), "Letter" to (2550 to 3300),
)
internal const val PX_300_PER_UNIT = 2f
internal val PAGE_PX_RANGE = 100..9999

@Composable
internal fun PageSizeDialog(width: Float, height: Float, onDismiss: () -> Unit, onSize: (Float, Float, Boolean) -> Unit) {
    var w by remember { mutableStateOf((width * PX_300_PER_UNIT).roundToInt().toString()) }
    var h by remember { mutableStateOf((height * PX_300_PER_UNIT).roundToInt().toString()) }
    var all by remember { mutableStateOf(false) }
    val pw = w.toIntOrNull()?.takeIf { it in PAGE_PX_RANGE }
    val ph = h.toIntOrNull()?.takeIf { it in PAGE_PX_RANGE }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("페이지 크기") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    PAGE_SIZES.forEach { (name, size) ->
                        TextButton(onClick = { w = size.first.toString(); h = size.second.toString() }) { Text(name) }
                    }
                    TextButton(onClick = { val t = w; w = h; h = t }) { Text("가로↔세로") }
                }
                Row {
                    OutlinedTextField(w, { w = it.filter(Char::isDigit).take(4) }, label = { Text("너비 px") },
                        singleLine = true, isError = pw == null, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(h, { h = it.filter(Char::isDigit).take(4) }, label = { Text("높이 px") },
                        singleLine = true, isError = ph == null, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Text("300dpi 기준, 100–9,999px", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = all, onCheckedChange = { all = it })
                    Text("PDF가 아닌 모든 페이지에 적용")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pw != null && ph != null, onClick = {
                onSize(pw!! / PX_300_PER_UNIT, ph!! / PX_300_PER_UNIT, all)
            }) { Text("적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** Page number in, page index out. Shared by the page panel and the Ctrl+Alt+G shortcut. */
@Composable
internal fun GoToPageDialog(pageCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val target = text.toIntOrNull()?.takeIf { it in 1..pageCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("쪽 이동") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(6) },
                label = { Text("1–$pageCount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { target?.let { onGo(it - 1) }; onDismiss() }, enabled = target != null) {
                Text("이동")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun TocChip(
    index: Int,
    page: Page,
    selected: Boolean,
    onJump: () -> Unit,
    onEdit: () -> Unit,
    onHighlight: () -> Unit,
    onDelete: () -> Unit,
    onIndent: () -> Unit = {},
    onOutdent: () -> Unit = {},
) {
    val highlighted = page.tocHighlighted
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .padding(start = (page.tocLevel.coerceAtMost(4) * 14).dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) colors.tertiaryContainer else chipFill(selected))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) colors.primary else colors.outlineVariant,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onJump)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(page.tocTitle.orEmpty(), style = MaterialTheme.typography.titleSmall,
                color = if (highlighted) colors.onTertiaryContainer else colors.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${index + 1}쪽", style = MaterialTheme.typography.labelSmall,
                color = if (highlighted) colors.onTertiaryContainer else colors.outline)
        }
        IconButton(onClick = onHighlight, modifier = Modifier.size(32.dp)) {
            Icon(Reicons.Highlight,
                contentDescription = if (highlighted) "강조 해제" else "목차 강조",
                tint = if (highlighted) colors.onTertiaryContainer else colors.outline,
                modifier = Modifier.size(18.dp))
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Reicons.MoreVert, contentDescription = "목차 옵션",
                    modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("이름 변경") }, onClick = {
                    menu = false; onEdit()
                })
                DropdownMenuItem(text = { Text("안으로") }, onClick = {
                    menu = false; onIndent()
                })
                DropdownMenuItem(text = { Text("밖으로") }, enabled = page.tocLevel > 0, onClick = {
                    menu = false; onOutdent()
                })
                DropdownMenuItem(text = { Text("목차 삭제") }, onClick = {
                    menu = false; onDelete()
                })
            }
        }
    }
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
    study: MaskStudy? = null,
    studyRevision: Int = 0,
    onRenameMask: (Int) -> Unit = {},
    onAnswerMask: (Int, Boolean) -> Unit = { _, _ -> },
    onStudy: (Boolean) -> Unit = {},
) {
    val masks = page.masks
    val hidden = masks.count { !it.revealed }
    val keys = remember(page, masks.size, studyRevision) {
        masks.map { MaskStudy.keyOf(page.id, it.stroke) }
    }
    val records = remember(keys, studyRevision) { keys.map { study?.record(it) ?: MaskRecord() } }
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
                if (masks.isNotEmpty() && study != null) {
                    val right = records.count { it.last == MaskRecord.RESULT_RIGHT }
                    val wrong = records.count { it.last == MaskRecord.RESULT_WRONG }
                    Text(
                        "맞음 $right · 틀림 $wrong",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (masks.isNotEmpty()) {
                IconButton(onClick = { onReveal(hidden > 0) }) {
                    Icon(
                        if (hidden > 0) Reicons.Visibility else Reicons.VisibilityOff,
                        contentDescription = if (hidden > 0) "이 페이지 보이기" else "이 페이지 가리기",
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(Reicons.Delete, contentDescription = "이 페이지 마스킹 삭제")
                }
            }
        }
        if (masks.isNotEmpty() && study != null) Row(Modifier.padding(start = 16.dp)) {
            TextButton(onClick = { onStudy(false) }) { Text("학습") }
            TextButton(
                onClick = { onStudy(true) },
                enabled = records.any { it.last == MaskRecord.RESULT_WRONG },
            ) { Text("틀린 것만") }
        }
        for ((maskIndex, mask) in masks.withIndex()) {
            val record = records.getOrNull(maskIndex) ?: MaskRecord()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (record.last) {
                        MaskRecord.RESULT_RIGHT -> "✓ "
                        MaskRecord.RESULT_WRONG -> "✗ "
                        else -> ""
                    } + record.name.ifBlank { "마스크 ${maskIndex + 1}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (record.last) {
                        MaskRecord.RESULT_RIGHT -> Color(0xFF2E7D32)
                        MaskRecord.RESULT_WRONG -> MaterialTheme.colorScheme.error
                        else -> Color.Unspecified
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = study != null) { onRenameMask(maskIndex) },
                )
                IconButton(
                    onClick = { onRevealMask(maskIndex, !mask.revealed) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (mask.revealed) Reicons.Visibility else Reicons.VisibilityOff,
                        contentDescription = if (mask.revealed) "이 마스킹 가리기" else "이 마스킹 보이기",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { onDeleteMask(maskIndex) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Reicons.Delete,
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
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onBackground: (PageBackground) -> Unit,
    userTemplates: List<UserPageTemplate>,
    onTemplate: (String) -> Unit,
    onEditToc: () -> Unit,
    onBookmark: () -> Unit = {},
    onRotate: (Boolean) -> Unit = {},
    onBackgroundAll: () -> Unit = {},
    onPageSize: () -> Unit = {},
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
                    PageBackground.DOT -> "도트"
                    PageBackground.NARROW_LINED -> "좁은 줄"
                    PageBackground.CORNELL -> "코넬"
                    PageBackground.CUSTOM -> "사용자"
                    PageBackground.INFINITE -> "무한"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            page.tocTitle?.let { title ->
                Text(title, style = MaterialTheme.typography.labelSmall,
                    color = if (page.tocHighlighted) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(
            onClick = onBookmark,
            modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
        ) {
            Icon(
                if (page.bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (page.bookmarked) "북마크 해제" else "북마크",
                tint = if (page.bookmarked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (page.tocTitle == null) "목차 설정" else "목차 이름 변경") },
                onClick = { menu = false; onEditToc() },
            )
            DropdownMenuItem(
                text = { Text("앞 페이지로") }, enabled = index > 0,
                onClick = { onMove(-1); menu = false },
            )
            DropdownMenuItem(
                text = { Text("뒤 페이지로") },
                onClick = { onMove(1); menu = false },
            )
            DropdownMenuItem(
                text = { Text("페이지 복제") },
                onClick = { onDuplicate(); menu = false },
            )
            if (page.background != PageBackground.PDF && page.pdfPageIndex < 0) {
                DropdownMenuItem(
                    text = { Text("왼쪽으로 회전") },
                    onClick = { onRotate(false); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("오른쪽으로 회전") },
                    onClick = { onRotate(true); menu = false },
                )
            }
            if (page.background != PageBackground.PDF) {
                DropdownMenuItem(
                    text = { Text("페이지 크기…") },
                    onClick = { onPageSize(); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("이 배경을 모든 페이지에") },
                    onClick = { onBackgroundAll(); menu = false },
                )
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
                DropdownMenuItem(
                    text = { Text("도트") },
                    onClick = { onBackground(PageBackground.DOT); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("좁은 줄") },
                    onClick = { onBackground(PageBackground.NARROW_LINED); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("코넬") },
                    onClick = { onBackground(PageBackground.CORNELL); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("무한 노트") },
                    onClick = { onBackground(PageBackground.INFINITE); menu = false },
                )
                userTemplates.forEach { template ->
                    DropdownMenuItem(
                        text = { Text("템플릿 · ${template.name}") },
                        onClick = { onTemplate(template.id); menu = false },
                    )
                }
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
    toolPens: Map<EditMode, PenPreset>,
    toolbarTargetWidth: Dp,
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
    onText: () -> Unit,
    onAddText: () -> Unit,
    onWeb: (String) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFitWidth: () -> Unit,
    /** The zoom as a percentage of fit-to-width, which is what the button resets to. */
    zoomLabel: String,
    onToggleFullscreen: () -> Unit,
    onToggleLatency: () -> Unit,
    recording: Boolean,
    onVoice: () -> Unit,
    noteRotation: Int,
    onRotate: () -> Unit,
    onTogglePages: () -> Unit,
    onCollapse: () -> Unit,
    sizeLevel: Int,
    onSizeLevel: (Int) -> Unit,
    onOpenNote: (NoteMeta) -> Unit,
    onBack: () -> Unit,
    onPalette: () -> Unit,
    onToggleDock: () -> Unit,
    /** Whether the bar has been dragged away from where it starts. */
    canResetBar: Boolean,
    onResetBar: () -> Unit,
    onScreenSettings: () -> Unit,
    onDragBar: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    laser: Boolean = false,
    onToggleLaser: () -> Unit = {},
    laserDot: Boolean = false,
    ruler: RulerKind? = null,
    onRuler: (RulerKind?) -> Unit = {},
    onAddPage: () -> Unit = {},
    onSticker: () -> Unit = {},
    onInputSettings: () -> Unit = {},
    onStickyNote: () -> Unit = {},
    onTable: () -> Unit = {},
    onEditTools: () -> Unit = {},
    onQuickColor: (Int) -> Unit = {},
    onZoomBox: () -> Unit = {},
    onNoteMenu: () -> Unit = {},
    onCamera: () -> Unit = {},
) {
    val topRow: @Composable () -> Unit = {
        // ---- top row: the note, and what is done to the whole of it
        Row(
            // Docked the bar is the window and everything fits. Floating it
            // is capped, and narrower still with the browser panel open -
            // and a Row that cannot scroll does not shrink, it just stops
            // drawing: the last buttons in this row, from full screen to
            // the other notes, were being cut off the end of the bar with
            // no way to reach them. Only the bottom row scrolled.
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Reicons.ArrowBack, contentDescription = "뒤로가기")
            }
            IconButton(onClick = onTogglePages) {
                Icon(Reicons.Search, contentDescription = "페이지 · 검색")
            }
            ToolButton(
                Reicons.CropFree,
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
                modifier = Modifier.weight(1f)
                    .padding(horizontal = 16.dp),
            )

            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Reicons.Undo, contentDescription = "실행취소")
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Reicons.Redo, contentDescription = "다시실행")
            }
            IconButton(onClick = onVoice) {
                Icon(
                    Reicons.Mic,
                    contentDescription = if (recording) "녹음 중 · 녹음 패널 열기" else "녹음 패널 열기",
                    tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onRotate) {
                Icon(Reicons.ScreenRotation, contentDescription = "노트 회전 ${noteRotation}도")
            }
            // 확대 아이콘과 수치는 분리해 둘을 감싸는 강조 칸은 만들지 않고,
            // 숫자는 행의 정중앙에 놓습니다.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onFitWidth,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Reicons.ZoomOutMap, contentDescription = "화면에 맞추기")
                }
                Text(
                    zoomLabel,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(min = 44.dp)
                        .padding(horizontal = 4.dp),
                )
            }
            SkinButton(skin, onSkin)
            IconButton(onClick = onToggleDock) {
                Icon(
                    if (docked) {
                        Reicons.PictureInPictureAlt
                    } else {
                        Reicons.VerticalAlignTop
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
                        Reicons.FilterCenterFocus,
                        contentDescription = "도구막대 제자리로",
                    )
                }
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    if (fullscreen) Reicons.FullscreenExit else Reicons.Fullscreen,
                    contentDescription = "전체화면",
                )
            }
            IconButton(onClick = onToggleLaser) {
                Icon(
                    Reicons.FilterCenterFocus,
                    contentDescription = if (!laser) "레이저 포인터" else if (laserDot) "레이저 점 · 끄기" else "레이저 선 · 점으로",
                    tint = if (laser) Color(0xFFFF3B30) else LocalContentColor.current,
                )
            }
            RulerButton(ruler, onRuler)
            IconButton(onClick = onSticker) { Text("😀") }
            TextButton(onClick = onStickyNote) { Text("메모") }
            TextButton(onClick = onTable) { Text("표") }
            IconButton(onClick = onInputSettings) { Icon(Reicons.TouchApp, contentDescription = "손가락·제스처") }
            IconButton(onClick = onAddPage) {
                Icon(Reicons.Add, contentDescription = "페이지 추가")
            }
            IconButton(onClick = onNoteMenu) { Icon(Reicons.MoreVert, contentDescription = "노트 · 보기 방식") }
            OtherNotesButton(otherNotes, onOpenNote)
        }
    }
    SpotiGlassToolbar(sizeLevel, onSizeLevel, mode,
            inkColor = if (mode.tints) Color(pen.colorArgb.or(0xFF000000.toInt())) else null,
            toolColors = toolPens.filterKeys { it.tints }.mapValues { Color(it.value.colorArgb.or(0xFF000000.toInt())) },
            targetWidth = toolbarTargetWidth,
            docked = docked, modifier = modifier, onDrag = onDragBar,
            onTool = { tool -> when (tool.mode) {
                EditMode.SHAPE -> onShape(shapeKind)
                EditMode.TEXT -> onText()
                null -> Unit
                else -> onMode(tool.mode)
            } },
            actions = buildList {
                add(SpotiToolbarAction("뒤로가기", Reicons.ArrowBack, onClick = onBack))
                add(SpotiToolbarAction("검색 · 페이지", Reicons.Search, slot = 8, onClick = onTogglePages))
                add(SpotiToolbarAction("실행취소", Reicons.Undo, enabled = canUndo, onClick = onUndo))
                add(SpotiToolbarAction("다시실행", Reicons.Redo, enabled = canRedo, onClick = onRedo))
                add(SpotiToolbarAction("마이크", Reicons.Mic, selected = recording, slot = 0, onClick = onVoice))
                add(SpotiToolbarAction(if (docked) "상단 고정 해제" else "상단 고정", Reicons.VerticalAlignTop,
                    selected = docked, slot = 1, onClick = onToggleDock))
                add(SpotiToolbarAction("전체화면", Reicons.Fullscreen, selected = fullscreen, slot = 2, onClick = onToggleFullscreen))
                add(SpotiToolbarAction("노트 회전", Reicons.ScreenRotation, slot = 7, onClick = onRotate))
                add(SpotiToolbarAction("캡쳐", Reicons.CropFree, slot = 5, onClick = { onMode(EditMode.CAPTURE) }))
                add(SpotiToolbarAction(if (!laser) "레이저 포인터" else if (laserDot) "레이저 점" else "레이저 선",
                    Reicons.FilterCenterFocus, selected = laser, onClick = onToggleLaser))
                for (kind in RulerKind.entries) {
                    add(SpotiToolbarAction(rulerLabel(kind), Reicons.Remove, selected = ruler == kind,
                        onClick = { onRuler(if (ruler == kind) null else kind) }))
                }
                add(SpotiToolbarAction("페이지 추가", Reicons.Add, onClick = onAddPage))
                add(SpotiToolbarAction("필기 설정", Reicons.Create, slot = 4, onClick = {
                    if (mode !in PenStore.DEFAULTS) onMode(EditMode.PEN)
                    onEditPen()
                }))
                add(SpotiToolbarAction("사진", Reicons.AddPhotoAlternate, onClick = onPickImage))
                add(SpotiToolbarAction("스티커", Reicons.AutoAwesome, onClick = onSticker))
                add(SpotiToolbarAction("스티키 노트", Reicons.Description, onClick = onStickyNote))
                add(SpotiToolbarAction("표", Reicons.AutoAwesomeMosaic, onClick = onTable))
                add(SpotiToolbarAction("손가락·제스처", Reicons.TouchApp, onClick = onInputSettings))
                add(SpotiToolbarAction("도구 편집", Reicons.Tune, onClick = onEditTools))
                add(SpotiToolbarAction("확대 필기창", Reicons.ZoomOutMap, onClick = onZoomBox))
                add(SpotiToolbarAction("노트 · 보기 방식", Reicons.MoreVert, onClick = onNoteMenu))
                add(SpotiToolbarAction("사진 찍어 넣기", Reicons.Image, onClick = onCamera))
                add(SpotiToolbarAction("UI · 화면 설정", Reicons.AutoAwesomeMosaic, slot = 6, onClick = onScreenSettings))
                add(SpotiToolbarAction("화면 맞추기 · $zoomLabel", Reicons.ZoomOutMap, onClick = onFitWidth))
                add(SpotiToolbarAction("인터넷", Reicons.Language, onClick = { onWeb(SEARCH_HOME) }))
                add(SpotiToolbarAction("지연 측정", Reicons.Speed, selected = showLatency, onClick = onToggleLatency))
                if (canResetBar) add(SpotiToolbarAction("도구막대 제자리로", Reicons.FilterCenterFocus, onClick = onResetBar))
            },
            notes = otherNotes.map { other -> SpotiToolbarAction(other.title, Reicons.Description) { onOpenNote(other) } },
            ai = AI_SITES.map { (name, url) -> SpotiToolbarAction(name, Reicons.AutoAwesome) { onWeb(url) } },
            topRow = topRow,
            penOptions = { availableWidth ->
              val inkOptions: @Composable RowScope.() -> Unit = {
                if (mode == EditMode.SHAPE) ShapeButton(true, shapeKind, onShape)
                if (mode == EditMode.TEXT) TextButton(onClick = onAddText) { Text("+ 텍스트") }
                if (mode in PenStore.DEFAULTS) {
                    PenChip(pen, true, onEditPen)
                }
                if (mode.tints) QuickColors(pen.colorArgb, onQuickColor, onEditPen)
                if (mode.tints) {
                    IconButton(onClick = onPalette, modifier = Modifier.size(32.dp)) {
                        Icon(Reicons.Palette, "색상 템플릿", Modifier.size(20.dp))
                    }
                }
                if (mode == EditMode.HIGHLIGHTER || mode == EditMode.MASK) {
                    ToolButton(Reicons.Remove, "직선", straightLine, onClick = onToggleStraightLine)
                }
              }
              val widthOptions: @Composable RowScope.() -> Unit = {
                if (mode in PenStore.DEFAULTS) {
                    val range = PenStore.widthRange(mode, pen)
                    FavoriteWidthButton(mode, pen.width, onWidth)
                    SkinSlider(pen.width.coerceIn(range), onWidth, range, Modifier.weight(1f).widthIn(min = 64.dp))
                }
              }
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                inkOptions()
                widthOptions()
                if (mode !in PenStore.DEFAULTS) Spacer(Modifier.weight(1f))
                TextButton(onClick = onTogglePages) { Text(pageLabel) }
              }
            })
}

internal fun rulerLabel(kind: RulerKind): String = when (kind) {
    RulerKind.RULER -> "자"
    RulerKind.TRIANGLE -> "삼각자"
    RulerKind.PROTRACTOR -> "각도기"
}

/** Lays a ruler, set square or protractor on the screen; tapping the one in hand again asks for its angle. */
@Composable
private fun RulerButton(ruler: RulerKind?, onRuler: (RulerKind?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Reicons.Remove, contentDescription = "자",
                tint = if (ruler != null) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (kind in RulerKind.entries) {
                DropdownMenuItem(
                    text = { Text((if (ruler == kind) "✓ " else "") + rulerLabel(kind)) },
                    onClick = { open = false; onRuler(kind) },
                )
            }
            if (ruler != null) DropdownMenuItem(text = { Text("치우기") }, onClick = { open = false; onRuler(null) })
        }
    }
}

/** Jumps straight to another note without going back through the list. */
@Composable
private fun OtherNotesButton(notes: List<NoteMeta>, onOpenNote: (NoteMeta) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Reicons.Menu, contentDescription = "다른 노트")
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
            Icon(Reicons.Menu, contentDescription = "도구 보이기")
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
    val shape = CircleShape
    val contentColor = when {
        selected && tint != null -> tint
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (selected && LocalSkin.current.isRefractive) {
        LiquidGlassButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp),
            contentPadding = PaddingValues(0.dp),
            containerColor = Color.Transparent,
            contentColor = contentColor,
        ) {
            Icon(icon, contentDescription = label)
        }
        return
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .clip(shape)
            .then(
                if (selected) {
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                } else {
                    Modifier
                },
            ),
        colors = IconButtonDefaults.iconButtonColors(
            // 선택한 도구만 기존처럼 조용한 배경과 색으로 구분합니다.
            contentColor = contentColor,
        ),
    ) { Icon(icon, contentDescription = label) }
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
private const val INK_INDEX_IDLE_MS = 8000L
private const val SEARCH_DEBOUNCE_MS = 220L

private const val PEN_SAVE_DELAY_MS = 400L
private const val REFERENCE_SCALE_DEAD_ZONE = 0.0025f

/** 페이지 이동이 끝난 뒤 스크러버가 화면에 남아 있는 시간입니다. */
private const val PAGE_SCRUBBER_IDLE_MS = 1600L

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

/** 작은 팝업 안의 페이지 이동 거리와 관성 속도를 키우는 배율입니다. */
private const val REFERENCE_SCROLL_MULTIPLIER = 1.65f

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
    "형광" to listOf(0x66FFEB3B, 0x6676FF03, 0x6600E5FF, 0x66FF4081, 0x66FF9100),
    "먹" to listOf(0xFF000000, 0xFF3A3A3A, 0xFF6B6B6B, 0xFF9E9E9E, 0xFFCFCFCF),
).map { (name, colors) -> name to colors.map { it.toInt() } }
