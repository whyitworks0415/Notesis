package com.notesis

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.graphics.Shape
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
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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

    // Always full screen: the bars come back on a swipe and leave again on their own.
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // A dialog or the keyboard taking focus can bring the bars back.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordCrashes()
        // Android 15+ draws behind the system bars whether or not you ask, so
        // opt in properly and let the insets be dispatched instead of guessed.
        enableEdgeToEdge()
        hideSystemBars()
        StudyClock.init(applicationContext)
        // Before any screen: the glass reads battery saver from it from the first frame.
        InteractionGovernor.init(applicationContext)
        Planner.init(applicationContext)
        ToolbarMotion.init(applicationContext)
        val store = NoteStore(this)
        noteStore = store
        val prefs = PenStore(this)
        ToolbarLayout.load(this)
        AppIconStyle.load(this)
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
                // Lives here rather than in the list, which leaves composition
                // while a note is open and used to come back at the top level.
                var libraryFolder by remember { mutableStateOf("") }
                fun closeNote(closed: NoteMeta) {
                    // Back to where the note lives, which is also where it was
                    // opened from unless it was found by searching.
                    libraryFolder = closed.folder
                    openNote = null
                    incomingImportedNote = null
                }
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
                // One ticker for the whole app: timer, alarms and study time
                // move on whichever screen is showing. Stopped while the app
                // is out of sight; the first tick back catches up on whatever
                // ended meanwhile, since all of it is kept as wall-clock moments.
                LaunchedEffect(Unit) {
                    this@MainActivity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) {
                            StudyClock.tick()
                            delay(CLOCK_TICK_MS)
                        }
                    }
                }
                // Study time is counted while a note is what is open.
                val studying = viewedDocument == null && !settingsOpen && note != null
                SideEffect { StudyClock.noteOpen = studying }
                Box(Modifier.fillMaxSize()) {
                if (viewedDocument != null) {
                    ReadOnlyDocumentScreen(
                        request = viewedDocument,
                        onBack = {
                            incomingViewerRequest = null
                            pickedDocument = null
                        },
                        onImport = { title, markdown ->
                            val created = store.createMarkdown(title.ifBlank { "Imported document" }, markdown)
                            incomingViewerRequest = null
                            pickedDocument = null
                            openNote = created
                        },
                    )
                } else if (settingsOpen) {
                    BackHandler { settingsOpen = false }
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
                        initialFolder = libraryFolder,
                        onFolder = { libraryFolder = it },
                    )
                } else if (note.kind == NoteKind.MARKDOWN) {
                    key(note.id) {
                        MarkdownNoteScreen(
                            store = store,
                            note = note,
                            onBack = { closeNote(note) },
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
                        onBack = { closeNote(note) },
                    )
                    }
                }
                // The library and the ink note float the clock on their own
                // glass; anywhere else, a finished timer or a ringing alarm
                // still has to be seen.
                val clockHosted = viewedDocument == null && !settingsOpen &&
                    (note == null || note.kind != NoteKind.MARKDOWN)
                if (!clockHosted) ClockOverlay()
                }
            }
        }
        }
    }

    // Every touch and every hovering pen counts as being at work, for the
    // study clock. Seen here, before any view takes it, and cheap: one write.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        StudyClock.touched()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        StudyClock.touched()
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        StudyClock.foreground = true
        StudyClock.touched()
    }

    override fun onPause() {
        StudyClock.foreground = false
        StudyClock.save()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryTrim.trim(level)
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

private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.ENGLISH)


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
    initialFolder: String = "",
    onFolder: (String) -> Unit = {},
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
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) HelpDialog(onDismiss = { showHelp = false }, onGuide = {
        showHelp = false
        scope.launch {
            val guide = withContext(Dispatchers.IO) { GuideNote.create(store) }
            revision++
            guide?.let(onOpen)
        }
    })
    LaunchUpdateCheck()
    // A fresh install starts with the guide among its notes - once, so a
    // deleted guide stays deleted; About & help makes a new one on request.
    LaunchedEffect(Unit) {
        if (withContext(Dispatchers.IO) { GuideNote.seedOnce(context, store) }) revision++
    }
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
                    } ?: error("Can't read the file")
                    store.createMarkdown(title.ifBlank { "Markdown note" }, text)
                }.getOrNull()
            }
            if (imported == null) report = "Couldn't import the Markdown file"
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
    var folder by remember { mutableStateOf(initialFolder) }
    // Kept by the caller too, so closing a note comes back to this folder.
    LaunchedEffect(folder) { onFolder(folder) }
    // Back leaves the folder before it leaves the app.
    BackHandler(enabled = folder.isNotBlank()) { folder = "" }
    var filing by remember { mutableStateOf<NoteMeta?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf(false) }
    var deletingFolder by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    val folders = remember(revision) { store.folders() }
    // Favourites first, then the chosen order within each group.
    val shown = (results ?: if (tagFilter != null) notes.filter { tagFilter in it.tags }
        else notes.filter { it.folder == folder }).sortedWith(
        compareByDescending<NoteMeta> { it.favorite }.thenBy { if (it.favorite) it.favoriteOrder else 0L }.then(
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
        busy = "Exporting"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    store.exportArchive(listOf(target), it)
                } ?: false
            }
            busy = null
            report = if (ok) "Backup saved" else "Export failed"
        }
    }

    val saveAllArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Backing up all notes"
        scope.launch {
            val ids = withContext(Dispatchers.IO) { store.list().map { it.id } }
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    store.exportArchive(ids, it)
                } ?: false
            }
            busy = null
            report = if (ok) "Backed up ${countNoun(ids.size, "note")}" else "Export failed"
        }
    }

    val saveSelectedArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ids = selectedIds.toList()
        busy = "Exporting selected notes"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { store.exportArchive(ids, it) } ?: false
            }
            busy = null
            report = if (ok) "Exported ${countNoun(ids.size, "note")}" else "Export failed"
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
        busy = "Rendering PDF"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    store.exportPdf(target, it, options)
                } ?: false
            }
            busy = null
            report = if (ok) "PDF saved" else "Export failed"
        }
    }

    fun writePng(note: NoteMeta, options: PageExportOptions) {
        busy = "Rendering PNG"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                store.exportPng(note.id, options) { page, bitmap ->
                    saveToGallery(context, safeFileName(note.title) + "-$page.png", bitmap)
                }
            }
            busy = null
            report = if (ok) "PNG saved to Gallery" else "PNG export failed"
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
            if (added == null) report = "Couldn't add the template image"
            else templateRevision++
        }
    }

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
            report = if (ok) "Markdown file saved" else "Markdown export failed"
        }
    }

    val openArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Restoring"
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { store.importArchive(it) } ?: 0
            }
            busy = null
            revision++
            report = if (added > 0) {
                "Restored ${countNoun(added, "note")}"
            } else {
                "Not a Notesis backup file"
            }
        }
    }

    // Settings travel on their own, small enough to keep anywhere and to
    // carry to another tablet without the notes.
    val saveSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        StudyClock.save()
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                    context.contentResolver.openOutputStream(uri)?.use { SettingsBackup.export(context, it, version) }
                }.getOrNull()
            }
            report = if (count != null) "Saved ${countNoun(count, "setting")}" else "Couldn't save settings"
        }
    }
    val openSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // What the clock holds in memory goes down first, so loading the
        // file over it is not then undone by the clock's next save.
        StudyClock.save()
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { SettingsBackup.import(context, it) }
                }.getOrNull()
            }
            if (count == null) {
                report = "Not a Notesis settings file"
                return@launch
            }
            StudyClock.reload(context)
            android.widget.Toast.makeText(context, "Loaded ${countNoun(count, "setting")}", android.widget.Toast.LENGTH_SHORT).show()
            // Pens, skin, gestures and the bars all read their settings as
            // the screen is built, so it is built again.
            (context as? android.app.Activity)?.recreate()
        }
    }

    val indexer = remember { InkIndexer(context) }
    DisposableEffect(Unit) { onDispose { indexer.close() } }

    // Pictures for a new note: picked, put in order, then one per page.
    var orderingImages by remember { mutableStateOf<List<Uri>?>(null) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) orderingImages = uris
    }
    var askingUrl by remember { mutableStateOf(false) }
    orderingImages?.let { uris ->
        ImageOrderDialog(uris, onDismiss = { orderingImages = null }) { ordered ->
            orderingImages = null
            busy = "Making a note from images"
            scope.launch {
                val created = withContext(Dispatchers.IO) {
                    store.createFromImages("Image note", ordered.map { uri -> { context.contentResolver.openInputStream(uri) } })
                }
                busy = null
                revision++
                if (created != null) onOpen(created) else report = "Couldn't read the images"
            }
        }
    }
    if (askingUrl) {
        var address by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askingUrl = false },
            title = { Text("New note from a web page") },
            text = { OutlinedTextField(address, { address = it.trim() }, label = { Text("https://…") }, singleLine = true) },
            confirmButton = { TextButton(enabled = address.contains('.'), onClick = {
                askingUrl = false
                busy = "Reading the web page"
                scope.launch {
                    val page = withContext(Dispatchers.IO) { fetchWebPage(address) }
                    busy = null
                    if (page == null) { report = "Couldn't read the web page"; return@launch }
                    val created = withContext(Dispatchers.IO) {
                        store.createMarkdown(page.title.take(80), "# ${page.title}\n\nSource: $address\n\n${page.text}")
                    }
                    revision++
                    onOpen(created)
                }
            }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { askingUrl = false }) { Text("Cancel") } },
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
                else -> report = "Imported ${countNoun(imported.size, "PDF")}" +
                    if (imported.size < uris.size) " (${uris.size - imported.size} failed)" else ""
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
    // The clock floats over the library too, on the same glass as its bars.
    Box(Modifier.fillMaxSize()) {
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
                    }) { Text(if (selectionMode) "Done" else "Select notes") }
                    ToolsMenuButton()
                    IconButton(onClick = { creatingFolder = true }) {
                        Icon(Reicons.Folder, contentDescription = "New folder")
                    }
                    if (folder.isNotBlank()) Box {
                        IconButton(onClick = { folderMenu = true }) {
                            Icon(Reicons.MoreVert, contentDescription = "Folder options")
                        }
                        DropdownMenu(folderMenu, onDismissRequest = { folderMenu = false }) {
                            DropdownMenuItem(text = { Text("Rename folder") }, onClick = {
                                folderMenu = false; renamingFolder = true
                            })
                            DropdownMenuItem(text = { Text("Delete folder") }, onClick = {
                                folderMenu = false; deletingFolder = true
                            })
                        }
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(LibraryIcons.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf(
                                LIBRARY_SORT_MODIFIED to "Last modified",
                                LIBRARY_SORT_TITLE to "Name",
                                LIBRARY_SORT_PAGES to "Most pages",
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
                        Icon(Reicons.Delete, contentDescription = "Trash")
                    }
                    IconButton(onClick = { showHomeBackground = true }) {
                        Icon(Reicons.Image, contentDescription = "Library background")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Reicons.Tune, contentDescription = "Display settings")
                    }
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Reicons.BugReport, contentDescription = "About · Help")
                    }
                },
                navigationIcon = {
                    // Only inside a folder: at the top level there is nowhere
                    // to go back to, and an arrow that does nothing is a lie.
                    if (folder.isNotBlank()) {
                        IconButton(onClick = { folder = "" }) {
                            Icon(
                                Reicons.ArrowBack,
                                contentDescription = "All notes",
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
                            placeholder = { Text("Search all notes · titles · PDFs · handwriting") },
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
                    onBackupAll = { saveAllArchive.launch("Notesis-backup") },
                    onRestore = { openArchive.launch(arrayOf("*/*")) },
                    onSaveSettings = { saveSettings.launch("Notesis-settings.json") },
                    onLoadSettings = { openSettings.launch(arrayOf("*/*")) },
                )
                Spacer(Modifier.height(12.dp))
                // Everything that makes a note, folded into the one button.
                NewNoteDial(
                    listOf(
                        DialOption("New note from web page", AppIconStyle.pick(Lucide.Globe, Reicons.Language)) { askingUrl = true },
                        DialOption("New note from images", AppIconStyle.pick(Lucide.Image, Reicons.AddPhotoAlternate)) {
                            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        DialOption("Import Markdown", AppIconStyle.pick(Lucide.FileText, Reicons.Description)) {
                            importMarkdown.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream"))
                        },
                        DialOption("Open document · Drive · OneDrive", AppIconStyle.pick(Lucide.FolderOpen, Reicons.FolderOpen)) {
                            openDocument.launch(SUPPORTED_DOCUMENT_MIME_TYPES)
                        },
                        DialOption("Import PDF", AppIconStyle.pick(Lucide.File, Reicons.PictureAsPdf)) {
                            pickPdf.launch(arrayOf("application/pdf"))
                        },
                        DialOption("New note", AppIconStyle.pick(Lucide.NotebookPen, Reicons.Edit)) { naming = true },
                    ),
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
                .graphicsLayer()
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
                        query.isNotBlank() -> "No results for \"$query\""
                        folder.isNotBlank() -> "This folder is empty"
                        else -> "No notes yet"
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
                horizontalArrangement = Arrangement.spacedBy(Ui.Space.m),
                verticalArrangement = Arrangement.spacedBy(Ui.Space.m),
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
                                    modifier = Modifier.padding(end = Ui.Space.s),
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
                            "Matches in pages",
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
                            "Notes",
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
                        onMoveFavorite = if (note.favorite) { delta ->
                            val pinned = shown.filter { it.favorite }
                            val at = pinned.indexOfFirst { it.id == note.id }
                            val to = (at + delta).coerceIn(pinned.indices)
                            if (at >= 0 && to != at) {
                                store.setFavoriteOrder(pinned.toMutableList().apply { add(to, removeAt(at)) }.map { it.id })
                                revision++
                            }
                        } else null,
                        onLabel = { labelingNote = note },
                        onLock = {
                            // Locking with a password already set needs nothing more; the rest asks first.
                            if (!note.locked && noteLock?.hasPassword == true) {
                                store.setLocked(note.id, true)
                                revision++
                            } else lockingNote = note
                        },
                        onIndex = {
                            busy = "Reading handwriting"
                            scope.launch {
                                val pages = withContext(Dispatchers.IO) {
                                    indexNote(store, indexer, note.id)
                                }
                                busy = null
                                report = if (pages < 0) {
                                    "Couldn't download the recognition model. Check your internet connection"
                                } else {
                                    "Indexed " + countNoun(pages, "page")
                                }
                            }
                        },
                    )
                }
            }
        }
        // Floating bars take the skin's own corner, the toolbar's, so every
        // bar over the page or the list is the same shape.
        if (selectionMode) SkinSurface(
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = padding.calculateBottomPadding() + Ui.Space.l),
        ) {
            Row(Modifier.padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Text("${selectedIds.size} selected", modifier = Modifier.padding(horizontal = Ui.Space.s))
                TextButton(onClick = { bulkFolder = true }, enabled = selectedIds.isNotEmpty()) {
                    Text("Move")
                }
                TextButton(onClick = {
                    val ids = selectedIds.filter { id -> notes.any { it.id == id && it.kind == NoteKind.INK } }
                    busy = "Merging notes"
                    scope.launch {
                        val merged = withContext(Dispatchers.IO) {
                            store.mergeNotes(ids, "Merged note")
                        }
                        busy = null
                        if (merged == null) report = "Couldn't merge the notes"
                        else {
                            selectedIds.clear(); selectionMode = false; revision++
                            onOpen(merged)
                        }
                    }
                }, enabled = selectedIds.count { id -> notes.any { it.id == id && it.kind == NoteKind.INK } } >= 2) {
                    Text("Merge")
                }
                TextButton(onClick = { saveSelectedArchive.launch("Notesis-selected-backup") }, enabled = selectedIds.isNotEmpty()) {
                    Text("Export")
                }
                TextButton(onClick = { bulkDelete = true }, enabled = selectedIds.isNotEmpty()) {
                    Text("Delete")
                }
            }
        }
        }
        }
    }
    ClockOverlay()
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
                    Text("Please wait")
                }
            },
            confirmButton = {},
        )
    }

    report?.let { message ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text(message) },
            confirmButton = { TextButton(onClick = { report = null }) { Text("OK") } },
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
        title = { Text("Move ${countNoun(selectedIds.size, "selected note")} to Trash?") },
        text = { Text("You can restore them from Trash for ${NoteStore.TRASH_DAYS} days.") },
        confirmButton = { TextButton(onClick = {
            selectedIds.forEach(store::moveToTrash)
            selectedIds.clear(); selectionMode = false; bulkDelete = false; revision++
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { bulkDelete = false }) { Text("Cancel") } },
    )

    if (creatingFolder || renamingFolder) {
        val rename = renamingFolder
        FolderNameDialog(
            title = if (rename) "Rename folder" else "New folder",
            initial = if (rename) folder else "",
            onDismiss = { creatingFolder = false; renamingFolder = false },
            onConfirm = { name ->
                val ok = if (rename) store.renameFolder(folder, name) else store.createFolder(name)
                if (ok) {
                    if (rename) folder = name.trim()
                    revision++
                } else report = "That folder name can't be used"
                creatingFolder = false; renamingFolder = false
            },
        )
    }
    if (deletingFolder) AlertDialog(
        onDismissRequest = { deletingFolder = false },
        title = { Text("Delete folder") },
        text = { Text("Moves the folder and its notes to Trash. Restoring a note brings the folder back.") },
        confirmButton = { TextButton(onClick = {
            store.deleteFolder(folder)
            folder = ""
            revision++
            deletingFolder = false
        }) { Text("Move to Trash") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    store.deleteFolder(folder, keepNotes = true)
                    folder = ""
                    revision++
                    deletingFolder = false
                }) { Text("Keep notes") }
                TextButton(onClick = { deletingFolder = false }) { Text("Cancel") }
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
            title = { Text("Can't open the PDF") },
            text = { Text("The file may be damaged or password-protected.") },
            confirmButton = { TextButton(onClick = { importFailed = false }) { Text("OK") } },
        )
    }

    exportDialogFor?.let { note ->
        PageExportDialog(
            pageCount = note.pageCount,
            onDismiss = { exportDialogFor = null },
            onExport = { png, options ->
                exportDialogFor = null
                if (png) {
                    writePng(note, options)
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
                    store.createMarkdown(title.ifBlank { "Untitled" }, "# ${title.ifBlank { "Untitled" }}\n\n")
                } else {
                    store.create(title.ifBlank { "Untitled" }, background, templateId)
                })
            },
        )
    }

    taggingNote?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.tags.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { taggingNote = null },
            title = { Text("Tags") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it },
                        label = { Text("Separate with commas") }, singleLine = true)
                    if (allTags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        allTags.forEach { tag ->
                            SuggestionChip(onClick = {
                                val current = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                if (tag !in current) text = (current + tag).joinToString(", ")
                            }, label = { Text("#$tag") }, modifier = Modifier.padding(end = Ui.Space.s))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                store.setTags(target.id, text.split(','))
                taggingNote = null
                revision++
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { taggingNote = null }) { Text("Cancel") } },
        )
    }

    labelingNote?.let { target ->
        AlertDialog(
            onDismissRequest = { labelingNote = null },
            title = { Text("Color label") },
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
                        ) { if (color == 0) Text("None", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { labelingNote = null }) { Text("Close") } },
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
            title = { Text("Move note to Trash?") },
            text = { Text("You can restore \"${note.title}\" from Trash for ${NoteStore.TRASH_DAYS} days.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.moveToTrash(note.id)
                        pendingDelete = null
                        revision++
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
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
    SkinSurface(corner = Ui.Corner.control, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(horizontal = Ui.Space.l, vertical = Ui.Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${note.title} · p. ${hit.pageIndex + 1}",
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
/** The width sliders' stops: a tenth, fine enough to land between the − and + steps. */
internal const val WIDTH_SLIDER_STEP = 0.1f

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
                    modifier = Modifier.padding(end = Ui.Space.s))
            }
        }
    }
    val taps = listOf(TAP_NONE to "None", TAP_UNDO to "Undo", TAP_REDO to "Redo")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fingers & gestures") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices("One finger", listOf(FINGER_SCROLL to "Scroll", FINGER_IGNORED to "Ignore", FINGER_DRAW to "Draw"),
                    gestures.oneFinger) { onGestures(gestures.copy(oneFinger = it)) }
                choices("Two fingers", listOf(TWO_ZOOM_PAN to "Zoom & pan", TWO_SCROLL to "Scroll", TWO_IGNORED to "Ignore"),
                    gestures.twoFingers) { onGestures(gestures.copy(twoFingers = it)) }
                choices("Two-finger double tap", taps, gestures.twoFingerTap) { onGestures(gestures.copy(twoFingerTap = it)) }
                choices("Three-finger double tap", taps, gestures.threeFingerTap) { onGestures(gestures.copy(threeFingerTap = it)) }
                @Composable
                fun toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        SkinSwitch(checked = on, onCheckedChange = set)
                        Spacer(Modifier.width(10.dp))
                        Text(label)
                    }
                }
                toggle("Lock zoom", gestures.zoomLocked) { onGestures(gestures.copy(zoomLocked = it)) }
                toggle("Double-tap with one finger to zoom", gestures.doubleTapZoom) { onGestures(gestures.copy(doubleTapZoom = it)) }
                toggle("Long-press to open the menu", gestures.longPressMenu) { onGestures(gestures.copy(longPressMenu = it)) }
                toggle("Mark linked objects in blue", gestures.linkOverlay) { onGestures(gestures.copy(linkOverlay = it)) }
                toggle("Back to the previous tool after erasing", gestures.eraserReturns) { onGestures(gestures.copy(eraserReturns = it)) }
                choices("Writing with the pen button held", listOf(PEN_BUTTON_ERASE to "Eraser",
                    PEN_BUTTON_LASSO to "Lasso"), gestures.penButton.takeIf { it != PEN_BUTTON_LASER } ?: PEN_BUTTON_ERASE) { onGestures(gestures.copy(penButton = it)) }
                Text("If your palm touches the screen, set one finger to 'Ignore' and two fingers to 'Scroll'. In Draw mode, two fingers move the page.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
        title = { Text("Link") },
        text = {
            Column {
                if (current != null) Text("Current: $current", style = MaterialTheme.typography.bodySmall)
                Row {
                    listOf("Page in this note", "Another note", "Web address").forEachIndexed { i, label ->
                        SettingsChoiceChip(selected = kind == i, onClick = { kind = i }, label = label,
                            modifier = Modifier.padding(end = Ui.Space.s))
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
                            label = { Text(if (kind == 0) "Page (1–$pageCount)" else "Page") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(target) }, enabled = target != null) { Text("Link") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Remove link") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
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
        title = { Text("Library") },
        text = {
            Column {
                if (tags.isNotEmpty()) Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
                ) {
                    tags.forEach { tag ->
                        FilterChip(selected = filter == tag, onClick = { filter = if (filter == tag) null else tag },
                            label = { Text("#$tag") })
                    }
                }
                if (items.isEmpty()) Text("Select with the lasso and tap 'Add to library' to keep it here.",
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
                            Box(Modifier.size(88.dp).clip(Ui.Shape.preview).background(Color.White), contentAlignment = Alignment.Center) {
                                if (preview != null) Image(preview.asImageBitmap(), null, contentScale = ContentScale.Fit)
                            }
                            if (item.tags.isNotEmpty()) Text(item.tags.joinToString(" ") { "#$it" },
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    editing?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.tags.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Library item") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Tags (comma-separated)") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                store.setLibraryTags(item.id, text.split(','))
                editing = null
                revision++
            }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { store.deleteLibraryItem(item.id); editing = null; revision++ }) { Text("Delete") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
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
        title = { Text("Create a note password") },
        text = {
            Column {
                Text("All locked notes share this one password. It is stored only on this device and can't be recovered if you forget it.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(first, { first = it }, label = { Text("Password (4+ characters)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(second, { second = it }, label = { Text("Confirm password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = second.isNotEmpty() && second != first)
                OutlinedTextField(hint, { hint = it }, label = { Text("Hint (optional)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(first, hint.trim()) }, enabled = ok) { Text("Lock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks for the master password, or the device's fingerprint or face first where there is one. */
@Composable
internal fun UnlockDialog(lock: NoteLock, title: String, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    val activity = LocalActivity.current
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (activity != null) lock.askBiometric(activity, "Open \"$title\"") { passed -> if (passed) onUnlocked() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Locked note") },
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(password, { password = it; wrong = false }, label = { Text("Password") },
                    singleLine = true, isError = wrong,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (wrong && lock.hint.isNotBlank()) Text("Hint: ${lock.hint}", style = MaterialTheme.typography.bodySmall)
                else if (wrong) Text("Wrong password", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (lock.check(password)) onUnlocked() else wrong = true },
                enabled = password.isNotEmpty()) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The picked pictures in the order they will become pages; ▲▼ to change it. */
@Composable
private fun ImageOrderDialog(uris: List<Uri>, onDismiss: () -> Unit, onConfirm: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val order = remember { mutableStateListOf<Uri>().apply { addAll(uris) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Order of ${countNoun(order.size, "image")}") },
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
        confirmButton = { TextButton(onClick = { onConfirm(order.toList()) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        title = { Text("Trash") },
        text = {
            if (trashed.isEmpty()) {
                Text("Trash is empty", color = MaterialTheme.colorScheme.outline)
            } else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(trashed, key = { it.id }) { note ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Deleted ${dateFormat.format(Date(note.trashedAt))} · ${countNoun(note.pageCount, "page")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        TextButton(onClick = {
                            store.restoreFromTrash(note.id)
                            revision++
                            onChanged()
                        }) { Text("Restore") }
                        TextButton(onClick = {
                            store.delete(note.id)
                            revision++
                        }) { Text("Delete forever") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (trashed.isNotEmpty()) {
            { TextButton(onClick = { confirmEmpty = true }) { Text("Empty") } }
        } else null,
    )
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text("Empty Trash?") },
        text = { Text("${countNoun(trashed.size, "note")} will be deleted for good and can't be recovered.") },
        confirmButton = { TextButton(onClick = {
            store.emptyTrash()
            confirmEmpty = false
            revision++
        }) { Text("Empty") } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
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
    return (name ?: "Imported PDF").removeSuffix(".pdf")
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
    onMoveFavorite: ((Int) -> Unit)? = null,
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
    val cardShape = Ui.Shape.card
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
                    .background(if (isDarkTheme()) MaterialTheme.colorScheme.surfaceContainerHigh else Color(0xFFFDFCF8)),
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
                    contentDescription = "Favorite",
                    tint = Color(0xFFF5B400),
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                )
                if (note.locked) Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Locked note",
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
                    contentDescription = if (selected) "Selected" else "Not selected",
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
                        "${dateFormat.format(Date(note.modified))} · ${countNoun(note.pageCount, "page")}",
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
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Set thumbnail") },
                            leadingIcon = { Icon(Reicons.Image, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onPickThumbnail()
                            },
                        )
                        if (onExportMarkdown != null) {
                            DropdownMenuItem(
                                text = { Text("Export as .md") },
                                leadingIcon = { Icon(Reicons.Description, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onExportMarkdown()
                                },
                            )
                        }
                        if (hasCustomThumbnail) {
                            DropdownMenuItem(
                                text = { Text("Reset to first page") },
                                onClick = {
                                    menuOpen = false
                                    onClearThumbnail()
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (note.favorite) "Remove from favorites" else "Add to favorites") },
                            leadingIcon = { Icon(LibraryIcons.Star, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onToggleFavorite()
                            },
                        )
                        if (onMoveFavorite != null) {
                            DropdownMenuItem(text = { Text("Move up in favorites") }, onClick = { menuOpen = false; onMoveFavorite(-1) })
                            DropdownMenuItem(text = { Text("Move down in favorites") }, onClick = { menuOpen = false; onMoveFavorite(1) })
                        }
                        DropdownMenuItem(
                            text = { Text("Tags") },
                            onClick = { menuOpen = false; onTags() },
                        )
                        DropdownMenuItem(
                            text = { Text("Color label") },
                            leadingIcon = { Icon(Reicons.Palette, contentDescription = null) },
                            onClick = { menuOpen = false; onLabel() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (note.locked) "Unlock" else "Lock with password") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            onClick = { menuOpen = false; onLock() },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to folder") },
                            leadingIcon = {
                                Icon(Reicons.FolderOpen, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onFile()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Index handwriting for search") },
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
                            text = { Text("Export as backup file") },
                            leadingIcon = {
                                Icon(Reicons.Archive, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export as PDF") },
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
                            text = { Text("Delete") },
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
        AppIconButton(onClick = { open = true }, icon = AppIcons.skin, contentDescription = "Theme")
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
                            SkinSurface(Modifier.size(30.dp), corner = Ui.Corner.preview) {}
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
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
    ) {
        for (name in folders) {
            // The same tile as a search hit: a control-sized corner, and
            // padding that makes the row a full touch target.
            SkinSurface(corner = Ui.Corner.control) {
                Row(
                    Modifier
                        .clickable { onOpen(name) }
                        .padding(horizontal = Ui.Space.l, vertical = Ui.Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Reicons.Folder, contentDescription = null)
                    Spacer(Modifier.width(Ui.Space.s))
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
        title = { Text("Move to folder") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Folder name") },
                    placeholder = { Text("Leave blank for the top level") },
                )
                if (folders.isNotEmpty()) {
                    Spacer(Modifier.height(Ui.Space.m))
                    Text(
                        "Existing folders",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(Ui.Space.xs))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (option in folders) {
                            TextButton(onClick = { name = option }) { Text(option) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(name.trim()) }) { Text("Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
    iconRotation: Float = 0f,
) {
    val side = if (small) 40.dp else 56.dp
    val iconModifier = Modifier.graphicsLayer { rotationZ = iconRotation }
    if (LocalSkin.current == Skin.MATERIAL) {
        if (small) {
            SmallFloatingActionButton(onClick = onClick, modifier = modifier) {
                AppIcon(icon, contentDescription = contentDescription, modifier = iconModifier, play = iconRotation > 0f)
            }
        } else {
            FloatingActionButton(onClick = onClick, modifier = modifier) {
                AppIcon(icon, contentDescription = contentDescription, modifier = iconModifier, play = iconRotation > 0f)
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
            AppIcon(icon, contentDescription = contentDescription, modifier = iconModifier, play = iconRotation > 0f)
        }
    } else {
        // The theme's FAB corners: card for the big one, control for the
        // small, so glass and Material buttons have the same outline.
        SkinSurface(modifier = modifier.size(side), corner = if (small) Ui.Corner.control else Ui.Corner.card) {
            // Clickable inside the surface, so the ripple is clipped to the corner.
            Box(
                Modifier.fillMaxSize().clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(icon, contentDescription = contentDescription, modifier = iconModifier, play = iconRotation > 0f)
            }
        }
    }
}

internal class DialOption(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * One button for every way to start a note. Tapped, the choices rise out of
 * it one after another, each with its name beside it; the plus turns into a
 * cross that folds them away again.
 */
@Composable
private fun NewNoteDial(options: List<DialOption>) {
    var open by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = open) { open = false }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 45f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f), label = "dialTurn")
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
        options.forEachIndexed { index, option ->
            // The nearest choice comes first and leaves last.
            val fromButton = options.lastIndex - index
            androidx.compose.animation.AnimatedVisibility(
                visible = open,
                enter = androidx.compose.animation.fadeIn(tween(160, delayMillis = fromButton * 35)) +
                    androidx.compose.animation.slideInVertically(tween(240, delayMillis = fromButton * 35)) { it / 2 } +
                    androidx.compose.animation.scaleIn(tween(240, delayMillis = fromButton * 35), initialScale = 0.7f),
                exit = androidx.compose.animation.fadeOut(tween(120, delayMillis = index * 20)) +
                    androidx.compose.animation.slideOutVertically(tween(160, delayMillis = index * 20)) { it / 2 } +
                    androidx.compose.animation.scaleOut(tween(160, delayMillis = index * 20), targetScale = 0.7f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
                    SkinSurface(corner = Ui.Corner.chip) {
                        Text(option.label, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clickable { open = false; option.onClick() }
                                .padding(horizontal = Ui.Space.m, vertical = Ui.Space.s))
                    }
                    GlassFab(onClick = { open = false; option.onClick() }, icon = option.icon,
                        contentDescription = option.label, small = true, modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
        GlassFab(
            onClick = { open = !open },
            icon = AppIconStyle.pick(Lucide.Plus, Reicons.Add),
            contentDescription = if (open) "Close" else "New note · Import",
            iconRotation = turn,
        )
    }
}

/** Whole-library backup and restore, and the settings beside them: one place for keeping things safe. */
@Composable
private fun BackupButton(
    onBackupAll: () -> Unit,
    onRestore: () -> Unit,
    onSaveSettings: () -> Unit,
    onLoadSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlassFab(
            onClick = { open = true },
            icon = Reicons.Archive,
            contentDescription = "Backup",
            small = true,
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Back up all notes") },
                leadingIcon = { Icon(Reicons.Archive, contentDescription = null) },
                onClick = {
                    open = false
                    onBackupAll()
                },
            )
            DropdownMenuItem(
                text = { Text("Restore from backup") },
                leadingIcon = { Icon(Reicons.Restore, contentDescription = null) },
                onClick = {
                    open = false
                    onRestore()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Save settings") },
                leadingIcon = { Icon(Reicons.Tune, contentDescription = null) },
                onClick = {
                    open = false
                    onSaveSettings()
                },
            )
            DropdownMenuItem(
                text = { Text("Load settings") },
                leadingIcon = { Icon(Reicons.Restore, contentDescription = null) },
                onClick = {
                    open = false
                    onLoadSettings()
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
    val shape = if (pen.tool == Tool.HIGHLIGHTER) Ui.Shape.badge else CircleShape
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

internal fun toolLabel(mode: EditMode): String = when (mode) {
    EditMode.PEN -> "Pen"
    EditMode.PENCIL -> "Pencil"
    EditMode.HIGHLIGHTER -> "Highlighter"
    EditMode.MASK -> "Masking tape"
    EditMode.SHAPE -> "Shape"
    EditMode.ERASE -> "Eraser"
    else -> "Tool"
}

/** Saturation across, brightness down, at the given [hue]. */
@Composable
internal fun SaturationValueField(
    hue: Float,
    saturation: Float,
    value: Float,
    modifier: Modifier = Modifier.fillMaxWidth().height(150.dp),
    shape: Shape = Ui.Shape.chip,
    onChange: (Float, Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val inset = with(LocalDensity.current) { PICKER_THUMB_RADIUS.toPx() }
    Canvas(
        modifier
            .clip(shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    fun take(at: Offset) = currentOnChange(
                        trackFraction(at.x, size.width.toFloat(), inset),
                        1f - trackFraction(at.y, size.height.toFloat(), inset),
                    )
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    take(down.position)
                    drag(down.id) { change ->
                        change.consume()
                        take(change.position)
                    }
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawPickerThumb(Offset(
            trackPosition(saturation, size.width, inset),
            trackPosition(1f - value, size.height, inset),
        ), inset)
    }
}

/** A horizontal ramp with a handle: used for hue, and again for alpha. */
@Composable
internal fun GradientStrip(
    colors: List<Color>,
    position: Float,
    modifier: Modifier = Modifier.fillMaxWidth().height(26.dp),
    shape: Shape = Ui.Shape.pill,
    onChange: (Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val inset = with(LocalDensity.current) { PICKER_THUMB_RADIUS.toPx() }
    Canvas(
        modifier
            .clip(shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    currentOnChange(trackFraction(down.position.x, size.width.toFloat(), inset))
                    drag(down.id) { change ->
                        change.consume()
                        currentOnChange(trackFraction(change.position.x, size.width.toFloat(), inset))
                    }
                }
            },
    ) {
        // White underneath, so the clear end of the alpha ramp reads as clear
        // rather than as whatever happens to be behind the dialog.
        drawRect(Color.White)
        drawRect(Brush.horizontalGradient(colors))
        drawPickerThumb(Offset(trackPosition(position, size.width, inset), size.height / 2f), inset)
    }
}

/**
 * Every colour picker's handle: small, solid white, with a faint rim so it
 * still shows on white. Drawn whole even at either end of its track - the
 * track maps its range between one handle's radius in from each edge, see
 * [trackFraction] - so it is never cut in half against the edge.
 */
internal val PICKER_THUMB_RADIUS = 7.dp

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPickerThumb(at: Offset, radius: Float) {
    drawCircle(Color.Black.copy(alpha = 0.22f), radius + 1.5.dp.toPx(), at)
    drawCircle(Color.White, radius, at)
}

/** A touch at [position] along [length], as 0..1 between one [inset] in from each end. */
internal fun trackFraction(position: Float, length: Float, inset: Float): Float =
    ((position - inset) / (length - 2f * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)

/** Where a handle at [fraction] sits along [length], kept [inset] in from each end. */
internal fun trackPosition(fraction: Float, length: Float, inset: Float): Float =
    inset + fraction.coerceIn(0f, 1f) * (length - 2f * inset).coerceAtLeast(0f)


/** The shape in hand; tapping it opens the shape picker. */
@Composable
private fun ShapeButton(
    selected: Boolean,
    kind: ShapeKind,
    onOptions: () -> Unit,
) {
    ToolButton(shapeIcon(kind), "Shape: ${shapeLabel(kind).substringBefore(" (")}", selected, onClick = onOptions)
}

private fun shapeIcon(kind: ShapeKind): ImageVector = when (kind) {
    ShapeKind.LINE -> Reicons.Remove
    ShapeKind.CHECKBOX -> Reicons.CheckBox
    ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW -> Reicons.TrendingUp
    ShapeKind.RECT, ShapeKind.CUBE -> Reicons.CropSquare
    ShapeKind.OVAL, ShapeKind.CYLINDER -> Reicons.Circle
    else -> Reicons.Category
}

internal fun shapeLabel(kind: ShapeKind): String = when (kind) {
    ShapeKind.LINE -> "Line"
    ShapeKind.ARROW -> "Arrow"
    ShapeKind.DOUBLE_ARROW -> "Double arrow"
    ShapeKind.RECT -> "Rectangle"
    ShapeKind.OVAL -> "Circle"
    ShapeKind.TRIANGLE -> "Triangle"
    ShapeKind.RIGHT_TRIANGLE -> "Right triangle"
    ShapeKind.DIAMOND -> "Diamond"
    ShapeKind.PARALLELOGRAM -> "Parallelogram"
    ShapeKind.TRAPEZOID -> "Trapezoid"
    ShapeKind.PENTAGON -> "Pentagon"
    ShapeKind.HEXAGON -> "Hexagon"
    ShapeKind.STAR -> "Star"
    ShapeKind.HEART -> "Heart"
    ShapeKind.ARC -> "Arc"
    ShapeKind.SECTOR -> "Sector"
    ShapeKind.SINE -> "Sine curve"
    ShapeKind.AXES -> "Axes"
    ShapeKind.BRACE -> "Brace"
    ShapeKind.BUBBLE -> "Speech bubble"
    ShapeKind.CUBE -> "Cube"
    ShapeKind.CYLINDER -> "Cylinder"
    ShapeKind.CURVE -> "Curve"
    ShapeKind.POLYGON -> "Polygon · polyline"
    ShapeKind.CHECKBOX -> "Checkbox (tap to add · tap again to tick)"
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
                        .semantics { contentDescription = "Quick color #%06X".format(rgb and 0xFFFFFF) }
                        .combinedClickable(
                            onClick = { if (chosen) onSettings() else onPick(rgb) },
                            onLongClick = { menuFor = index },
                        ),
                )
                DropdownMenu(menuFor == index, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("Move left") }, enabled = index > 0, onClick = {
                        save(colors.toMutableList().apply { add(index - 1, removeAt(index)) }); menuFor = null
                    })
                    DropdownMenuItem(text = { Text("Move right") }, enabled = index < colors.lastIndex, onClick = {
                        save(colors.toMutableList().apply { add(index + 1, removeAt(index)) }); menuFor = null
                    })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
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
        title = { Text("Color templates") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((name, colors) in COLOR_TEMPLATES + custom) {
                    Text(name, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(Ui.Space.s))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
                    ) {
                        for (rgb in colors) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(rgb))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .semantics { contentDescription = "Color #%06X".format(rgb and 0xFFFFFF) }
                                    .clickable { onPick(rgb) },
                            )
                        }
                    }
                    if ((name to colors) in custom) TextButton(onClick = {
                        custom = custom - (name to colors)
                        store.saveColorTemplates(custom)
                    }) { Text("Delete template") }
                    Spacer(Modifier.height(Ui.Space.l))
                }
            }
        },
        dismissButton = { TextButton(onClick = { adding = true }) { Text("+ Add template") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
        .onFailure { Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show() }
}

/**
 * The size [region] will come out at for [size]: the selection's aspect ratio,
 * held within the bitmap budget and, on a PDF page, within what the PDF renders.
 */
internal fun captureOutputPlan(region: CaptureRegion, size: CaptureSize): CapturePlan = planCapture(
    region.rect.width(), region.rect.height(), size, region.screenScale,
    BitmapBudget.bytes(), BitmapBudget.MAX_SIDE,
    if (region.onPdf) PdfSource.cropSideLimit() else null,
)

/**
 * What came back from a capture: how big and in what format it is kept, and
 * what to do with it. [bitmap] is only the preview; every action gets the
 * capture rendered again at the size picked here.
 */
@Composable
private fun CaptureDialog(
    bitmap: Bitmap,
    region: CaptureRegion?,
    options: CaptureOptions,
    onOptions: (CaptureOptions) -> Unit,
    /** True while the chosen size is being rendered. */
    busy: Boolean,
    onPaste: () -> Unit,
    onCarryLinked: (() -> Unit)? = null,
    onAttach: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val plan = remember(region, options.size) { region?.let { captureOutputPlan(it, options.size) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capture") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(Ui.Shape.preview),
                )
                // Each option group is a label and a row of chips, a section
                // gap apart, the same rhythm as the tool settings.
                if (region != null) {
                    Spacer(Modifier.height(Ui.Space.m))
                    Text("Size", style = MaterialTheme.typography.labelMedium)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
                    ) {
                        for (size in CaptureSize.entries) {
                            SettingsChoiceChip(
                                selected = options.size == size,
                                onClick = { onOptions(options.copy(size = size)) },
                                label = size.label,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Ui.Space.s))
                Text("Format", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
                ) {
                    for (format in CaptureFormat.entries) {
                        SettingsChoiceChip(
                            selected = options.format == format,
                            onClick = { onOptions(options.copy(format = format)) },
                            label = format.label,
                        )
                    }
                    if (options.format == CaptureFormat.JPEG) {
                        for (quality in CaptureOptions.JPEG_QUALITIES) {
                            SettingsChoiceChip(
                                selected = options.jpegQuality == quality,
                                onClick = { onOptions(options.copy(jpegQuality = quality)) },
                                label = "Quality $quality",
                            )
                        }
                    }
                }
                plan?.let {
                    Text(
                        "${it.width} × ${it.height} px",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = Ui.Space.s),
                    )
                    captureLimitMessage(it.limit, BitmapBudget.MAX_SIDE, PdfSource.cropSideLimit())?.let { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (busy) {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = Ui.Space.s),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onPaste, enabled = !busy) { Text("Paste on this page") } },
        dismissButton = {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (onCarryLinked != null) {
                    TextButton(onClick = onCarryLinked, enabled = !busy) { Text("Carry as link") }
                }
                TextButton(onClick = onAttach, enabled = !busy) { Text("Attach to AI") }
                TextButton(onClick = onSave, enabled = !busy) { Text("Save") }
                TextButton(onClick = onShare, enabled = !busy) { Text("Share") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/**
 * A browser beside the note. It is a plain WebView: the point is looking things
 * up without leaving the page being written on, not building a browser.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // Room for the keyboard at the height it is going to, from the first frame
    // of its slide, and kept until it has gone. Following the slide itself
    // resized the WebView on every frame of it: Chromium re-laid the chat out
    // each time, which was the stutter, and the composer rode up with the
    // keyboard instead of simply being there when it arrived.
    val keyboardRoom = WindowInsets.imeAnimationSource.union(WindowInsets.imeAnimationTarget)
    // Out of sight the page stops: onPause alone left its JavaScript timers
    // running, so a chat page kept waking the CPU beside the writing. The
    // timers are process-wide, which is why the note screen starts them again
    // when it goes away (see the browser's DisposableEffect there).
    DisposableEffect(web) {
        web?.resumeTimers()
        web?.onResume()
        onDispose {
            if (holder.value === web) {
                web?.onPause()
                web?.pauseTimers()
            }
        }
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
                .windowInsetsPadding(keyboardRoom),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { web?.let { if (it.canGoBack()) it.goBack() } }) {
                    Icon(Reicons.ArrowBack, contentDescription = "Back")
                }
                IconButton(onClick = { web?.reload() }) {
                    Icon(Reicons.Refresh, contentDescription = "Reload")
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    placeholder = { Text("Address or search") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { web?.loadUrl(asUrl(address)) }),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                )
                IconButton(onClick = onToggleLog) {
                    Icon(
                        Reicons.BugReport,
                        contentDescription = "Error log",
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
                        contentDescription = if (desktop) "Desktop site" else "Mobile site",
                    )
                }
                IconButton(onClick = { openExternally(web?.context, web?.url ?: url) }) {
                    Icon(Reicons.Launch, contentDescription = "Open in browser")
                }
                IconButton(onClick = onTogglePopup) {
                    Icon(
                        if (popup) Reicons.CloseFullscreen else Reicons.OpenInFull,
                        contentDescription = if (popup) "Dock" else "Pop out",
                    )
                }
                IconButton(onClick = { web?.clearFocus(); keyboard?.hide(); onClose() }) {
                    Icon(Reicons.Close, contentDescription = "Close")
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
                        Text("No errors logged", style = MaterialTheme.typography.bodySmall)
                    }
                    for (line in log) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    TextButton(onClick = { log.clear() }) { Text("Clear") }
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
        title = { Text("Export pages") },
        text = { Column {
            Text("Format")
            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                FilterChip(selected = !png, onClick = { png = false }, label = { Text("PDF") })
                FilterChip(selected = png, onClick = { png = true }, label = { Text("PNG") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                OutlinedTextField(first, { first = it.filter(Char::isDigit) },
                    label = { Text("From page") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(last, { last = it.filter(Char::isDigit) },
                    label = { Text("To page") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Ui.Space.s))
            Text("Size")
            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                listOf(
                    ExportPageSize.ORIGINAL to "Original", ExportPageSize.A4 to "A4",
                    ExportPageSize.LETTER to "Letter",
                ).forEach { (value, label) ->
                    FilterChip(selected = size == value, onClick = { size = value },
                        label = { Text(label) })
                }
            }
            Text("Rotation")
            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                listOf(0, 90, 180, 270).forEach { value ->
                    FilterChip(selected = rotation == value, onClick = { rotation = value },
                        label = { Text("${value}°") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                if (!png) FilterChip(selected = raster, onClick = { raster = !raster }, label = { Text("Image PDF") })
                FilterChip(selected = invert, onClick = { invert = !invert }, label = { Text("Invert colors") })
            }
            if (png) Text("PNGs go to the Gallery (Pictures/Notesis), one per page.",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = valid, onClick = {
            onExport(png, PageExportOptions(from!!, to!!, size, rotation, raster = raster && !png, invert = invert))
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        title = { Text("New note") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = kind == NoteKind.INK, onClick = { kind = NoteKind.INK }, label = { Text("Handwritten note") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = kind == NoteKind.MARKDOWN, onClick = { kind = NoteKind.MARKDOWN }, label = { Text("Markdown (.md)") })
                }
                Text(
                    if (kind == NoteKind.MARKDOWN) "Saved as a note.md file that Obsidian can open." else "A paper notebook for pens and text boxes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (kind == NoteKind.INK) {
                    Spacer(Modifier.height(12.dp))
                    Text("First page template", style = MaterialTheme.typography.labelMedium)
                    val labels = listOf(
                        PageBackground.BLANK to "Blank", PageBackground.LINED to "Lined",
                        PageBackground.NARROW_LINED to "Narrow lined", PageBackground.GRID to "Grid",
                        PageBackground.DOT to "Dot", PageBackground.CORNELL to "Cornell",
                        PageBackground.INFINITE to "Infinite",
                    )
                    Column {
                        labels.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
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
                        Text("Custom templates", style = MaterialTheme.typography.labelMedium)
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
                    TextButton(onClick = onAddTemplate) { Text("+ Add image template") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(title, kind, background, templateId) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * safeDrawing minus the keyboard. safeDrawing counts the IME, so every bar and
 * the canvas itself jumped whenever a text field took focus.
 */
internal val ChromeInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * What the pen does when it lands. One thing at a time, by construction, and
 * each one remembers its own colour and thickness rather than sharing a tray.
 */
enum class EditMode { PEN, HIGHLIGHTER, MASK, LASSO, SHAPE, IMAGE, ERASE, READ, CAPTURE, TEXT, PENCIL }

/** Whether this mode puts something on the page in the tool's own colour. */
internal val EditMode.tints: Boolean
    get() = this == EditMode.PEN || this == EditMode.PENCIL || this == EditMode.HIGHLIGHTER ||
        this == EditMode.MASK || this == EditMode.SHAPE

/**
 * A recomposition scope of its own. Box, Column and the like are inline, so
 * state read inside them recomposes the whole function they sit in - for the
 * note screen, the page host, the toolbar and every panel at once. State read
 * in [content] recomposes [content] only.
 */
@Composable
private fun Isolated(content: @Composable () -> Unit) = content()

/**
 * The overlay's text. The numbers are collected all along by the canvas; this
 * only reads them, at most a couple of times a second and half that while a
 * pen writes, so the readout itself is not part of what it measures. Composed
 * only while shown, so a hidden overlay builds nothing.
 */
@Composable
private fun LatencyHud(report: () -> String, modifier: Modifier = Modifier) {
    val latestReport by rememberUpdatedState(report)
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        var last = Long.MIN_VALUE / 2
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            if (UiScheduling.hudRefreshDue(now - last, InteractionGovernor.writing)) {
                last = now
                text = latestReport()
            }
            delay(UiScheduling.HUD_REFRESH_MS)
        }
    }
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
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
    var pullingForPage by remember { mutableStateOf(false) }
    var dictating by remember { mutableStateOf(false) }
    var shapeCorner by remember { mutableFloatStateOf(PenStore(context).shapeCornerRadius) }
    /** The last tool that was not the eraser, for going back to after an erase. */
    var beforeEraser by remember { mutableStateOf(EditMode.PEN) }
    var showLibrary by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var exportingPages by remember { mutableStateOf<List<Page>?>(null) }
    /** The layers' opacity when the pages were taken, for compositing them in the PDF. */
    var exportingLook by remember { mutableStateOf<LayerLook?>(null) }
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
                        pages.map { VectorPdfPage(it, it.strokes, it.masks.map { m -> m.stroke }, exportingLook) },
                        { imageId -> store.imageFile(note.id, imageId) }, out)
                } ?: false
            }
            Toast.makeText(context, if (ok) "PDF saved" else "Couldn't export", Toast.LENGTH_SHORT).show()
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
        RecordingService.stop(context)
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
            RecordingService.start(context)
            recordingFile = file
            recorder = active
            recordingEvents.clear()
            recordingStartedAt = android.os.SystemClock.elapsedRealtime()
        } else {
            active.release()
            file.delete()
            Toast.makeText(context, "Couldn't start recording", Toast.LENGTH_SHORT).show()
        }
    }
    val requestAudioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startRecording() }
    DisposableEffect(note.id) {
        onDispose {
            runCatching { recorder?.stop() }
            recorder?.release()
            if (recorder != null) RecordingService.stop(context)
            voicePlayer?.release()
        }
    }
    val penStore = remember { PenStore(context) }
    var playbackSpeed by remember { mutableFloatStateOf(penStore.playbackSpeed) }
    var playMode by remember { mutableIntStateOf(penStore.playMode) }
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
    var imageSelected by remember { mutableStateOf(false) }
    var showTextBox by remember { mutableStateOf(false) }
    /** 1 cropping the selected picture, 2 styling it, 0 neither. */
    var editingPicture by remember { mutableIntStateOf(0) }
    var replacingText by remember { mutableStateOf<PageImage?>(null) }
    var textPosition by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var captured by remember { mutableStateOf<Bitmap?>(null) }
    // How captures come out, kept between them: picking 4x once is picking it.
    val captureOptionPrefs = remember { context.getSharedPreferences("capture", android.content.Context.MODE_PRIVATE) }
    var captureOptions by remember { mutableStateOf(CaptureOptions.decode(captureOptionPrefs.getString("options", null))) }
    var captureBusy by remember { mutableStateOf(false) }
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
        onDispose {
            browser.value?.let {
                // Timers are paused for every WebView in the process, and the
                // document viewer has one of its own; leave them running.
                it.resumeTimers()
                it.destroy()
            }
            browser.value = null
        }
    }
    val browserVisible by rememberUpdatedState(webUrl != null)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // onPause leaves a page's scripts and timers running; out of
                // sight, an AI chat page kept the CPU busy all the same.
                Lifecycle.Event.ON_STOP -> browser.value?.let { it.onPause(); it.pauseTimers() }
                // A hidden panel stays paused; showing it starts it (WebPanel).
                Lifecycle.Event.ON_START -> browser.value?.takeIf { browserVisible }?.let {
                    it.resumeTimers()
                    it.onResume()
                }
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
    // Prediction, smoothing, line style and shape straightening belong to
    // the tool in hand; picking up another tool brings its own.
    val prediction = pen.leadMs != 0
    val predictionLeadMs = if (pen.leadMs == LEAD_AUTO) 0 else pen.leadMs
    var deferDetail by remember { mutableStateOf(penStore.deferDetail) }
    val stabilizer = pen.stabilizer
    var highlighterAboveInk by remember { mutableStateOf(penStore.highlighterAboveInk) }
    var meshInk by remember { mutableStateOf(penStore.meshInk) }
    var compatWetInk by remember { mutableStateOf(penStore.compatWetInk) }
    var partialEraser by remember { mutableStateOf(penStore.partialEraser) }
    var gestures by remember { mutableStateOf(penStore.gestures) }
    var choosingShape by remember { mutableStateOf(false) }
    fun setGestures(value: CanvasGestures) { gestures = value; penStore.gestures = value }
    /** Bumped on copy and cut so the paste bar sees the clipboard change. */
    var clipRevision by remember { mutableIntStateOf(0) }
    val autoShapes = pen.autoShapes
    val straightLine = pen.straightLine
    var axisSnap by remember { mutableStateOf(penStore.axisSnap) }
    val dottedPattern = pen.linePattern
    // Degrees, any angle: three fingers turn the note freely. See InkCanvasView.onRotateNote.
    var noteRotation by remember { mutableFloatStateOf(penStore.noteRotation.toFloat()) }
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
    // How far the hand has carried the panel past the bottom edge in this
    // drag. Let go far enough past it and the panel is put down (closed);
    // short of that it stays exactly where it was let go. Dragging down
    // used to be read as "close" from the first few pixels, so a panel could
    // not be moved down at all - it froze, then vanished.
    var referenceOvershoot by remember { mutableFloatStateOf(0f) }
    // A free resize from one of the panel's handles: the outline of the size
    // it will be, and the drag so far. Folded into the size when the hand
    // lifts, so the page inside is laid out once, at its new size.
    var referenceDraft by remember { mutableStateOf<Size?>(null) }
    var referenceResizeDrag by remember { mutableStateOf(Offset.Zero) }
    fun resizeReference(dx: Float, dy: Float) {
        val minSize = with(density) { REFERENCE_MIN_SIZE.toPx() }
        val drag = referenceResizeDrag + Offset(dx, dy)
        referenceResizeDrag = drag
        val (w, h) = freeResize(
            referenceSize.width, referenceSize.height, drag.x, drag.y, minSize,
            containerSize.width - referenceOffset.x, containerSize.height - referenceOffset.y,
        )
        referenceDraft = Size(w, h)
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
        // 핀치 중심을 화면에 고정합니다. 좌상단 고정 확대는 손가락과
        // 팝업이 서로 다른 방향으로 미끄러져 보였습니다.
        val (top, overshoot) = panelTopWithOvershoot(
            referenceOffset.y, referenceOvershoot, panY - (h - oldH) / 2f, maxY)
        referenceOffset = Offset((referenceOffset.x + panX - (w - oldW) / 2f).coerceIn(0f, maxX), top)
        referenceOvershoot = overshoot
    }
    val maxBarWidth = with(density) {
        (containerSize.width.takeIf { it > 0 } ?: Int.MAX_VALUE).toDp()
    }
    val toolbarEffects = rememberLiquidGlassEffectsAllowed() && !LocalSkinSettings.current.highContrast
    val floatingBarWidth = animateDpAsState(
        minOf(maxBarWidth, spotiToolbarWidth(toolbarSize)),
        // Material and Glassmorphism switch sizes at once; only Liquid glass morphs.
        if (skin.animatesChrome && toolbarEffects) ToolbarMotion.spec() else tween(0),
        label = "Top bar mode width",
    )
    var showLatency by remember { mutableStateOf(false) }
    var laser by remember { mutableStateOf(false) }
    var laserDot by remember { mutableStateOf(false) }
    var ruler by remember { mutableStateOf<RulerKind?>(null) }
    var rulerAngle by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    // Whether the pen put the panel away. It then goes in one frame, without
    // the slide: a panel of glass and thumbnails animating out is GPU and UI
    // thread work landing on the very first frames of the stroke.
    var pagesClosedByPen by remember { mutableStateOf(false) }
    // The page panel's body search. Held here, not in the panel, so the query
    // and the place in its results outlive the panel closing under the pen.
    val noteSearch = remember(note.id) {
        NoteSearchState(store.inkIndexFile(note.id, "search").parentFile ?: context.filesDir)
    }
    var goToPage by remember { mutableStateOf(false) }
    /** Where the settings sidebar is, or null while it is closed. */
    var settingsNav by remember { mutableStateOf<SidebarNav?>(null) }
    var settingsWidth by remember { mutableFloatStateOf(penStore.settingsSidebarWidth) }
    fun openSettings(page: SettingsPage) { settingsNav = SidebarNav.at(page) }
    var pageLayout by remember { mutableStateOf(penStore.pageLayoutOf(note.id)) }
    var pageTone by remember { mutableIntStateOf(penStore.pageTone) }
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
    // 마지막 페이지 이동마다 이전 타이머가 취소됩니다. 이동이 멈춘 뒤에만 사라집니다.
    // The timer lives with the scrubber below, in a scope of its own.
    var pageScrubberActivity by remember(note.id) { mutableIntStateOf(0) }
    // A multiple of fit-to-width, which is the 100% anybody means.
    var zoom by remember { mutableFloatStateOf(1f) }
    // The zoom as the canvas last reported it. During a pinch or a fling only
    // this moves; [zoom], which the toolbar label reads, catches up when the
    // hand lifts, so the bar is not recomposed thirty times a second meanwhile.
    val liveZoom = remember { floatArrayOf(1f) }
    var zoomMenu by remember { mutableStateOf(false) }
    var canvas by remember { mutableStateOf<InkCanvasView?>(null) }
    /** Puts [text] on the page as a text box in the middle of the screen. */
    fun putTypedText(text: String) {
        val content = TextBoxContent(text.take(4000))
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
    }
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
            title = { Text("Ruler settings") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(6) },
                        label = { Text("Angle (°)") },
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
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { rulerAngle = false }) { Text("Cancel") } },
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

    fun addImageFrom(uri: Uri) {
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
                Toast.makeText(context, "Couldn't read that image", Toast.LENGTH_SHORT).show()
                return@launch
            }
            canvas?.insertImage(added.first, added.second)
            mode = EditMode.IMAGE
            edits++
        }
    }
    // The gallery, not the file browser: the system photo picker opens on the
    // pictures themselves and needs no storage permission.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) addImageFrom(uri)
    }

    // Back clears a selection first, the way dismissing anything else works -
    // one step out per press.
    BackHandler {
        if (selectedText != null) canvas?.clearSelection() else onBack()
    }

    // Split screen and pop-up view resize the window, and every resize is a
    // configuration change; reading it here re-evaluates this then.
    val sharedScreen = LocalConfiguration.current.let {
        (context as? ComponentActivity)?.isInMultiWindowMode == true
    }

    // Opening a note decodes every stroke it holds and parses the PDF header.
    // On the main thread that is a visible freeze, and it grows with the note.
    LaunchedEffect(note.id) {
        opened = withContext(Dispatchers.IO) {
            // The PDF and the page list do not depend on each other; open them
            // side by side rather than one after the other.
            val pdf = async {
                PdfHold.take(store.pdfFile(note.id), PdfSource.cacheBytesFor(context))
            }
            store.load(note.id) to pdf.await()
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
    // With a level, the same bar drawn as a picture for the settings previews:
    // that size, floating, reporting nothing back.
    val toolbar: @Composable (Modifier, Int?) -> Unit = { barModifier, previewLevel ->
        Toolbar(
            note = note,
            otherNotes = otherNotes,
            pen = pen,
            toolPens = settings,
            toolbarTargetWidth = if (previewLevel != null) spotiToolbarWidth(previewLevel)
                else minOf(maxBarWidth, spotiToolbarWidth(toolbarSize)),
            mode = mode,
            shapeKind = shapeKind,
            straightLine = straightLine,
            onToggleStraightLine = {
                val updated = pen.copy(straightLine = !pen.straightLine)
                settings = settings + (mode to updated)
            },
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
            onShapeOptions = { choosingShape = true },
            onPickImage = {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
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
            onZoomLabel = { zoomMenu = true },
            zoomLabel = "${(zoom * 100).roundToInt()}%",
            onAddPage = {
                canvas?.let { it.addPage(it.currentPageIndex()); it.scrollToPage(it.currentPageIndex() + 1) }
                edits++
            },
            onClock = { StudyClock.openPanel() },
            onLayers = { showLayers = true },
            onCamera = {
                val file = java.io.File(context.cacheDir, "shared/camera.jpg").apply { parentFile?.mkdirs() }
                takePhoto.launch(androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file))
            },
            onQuickColor = { rgb ->
                val alpha = pen.colorArgb and 0xFF000000.toInt()
                settings = settings + (mode to pen.copy(colorArgb = (rgb and 0xFFFFFF) or alpha))
            },
            recording = recorder != null,
            onVoice = { showVoice = true },
            noteRotation = noteRotation.roundToInt(),
            onRotate = {
                // The button turns to the next quarter, from wherever three fingers left it.
                noteRotation = (((noteRotation / 90f).roundToInt() + 1) * 90 % 360).toFloat()
                penStore.noteRotation = noteRotation.roundToInt()
            },
            onTogglePages = { showPages = !showPages },
            sizeLevel = previewLevel ?: toolbarSize,
            onSizeLevel = { setToolbarSize(it) },
            onOpenNote = onOpenNote,
            onBack = onBack,
            pageLabel = "${currentPage + 1} / $pageCount",
            onPalette = { showPalette = true },
            skin = skin,
            onSkin = onSkin,
            docked = docked && previewLevel == null,
            canResetBar = !docked && barOffset != null,
            onResetBar = { barOffset = null },
            onSettings = { openSettings(it) },
            onDragBar = { delta ->
                val at = barPlacement(barOffset, barSize, containerSize)
                if (docked) { docked = false; penStore.docked = false }
                barOffset = Offset(at.x + delta.x, at.y + delta.y)
            },
            layerChip = { LayerTargetChip(canvas) { showLayers = true } },
            preview = previewLevel != null,
            modifier = barModifier,
        )
    }

    // The chrome looks through whatever is drawn under it, so the page records
    // itself once a frame - but only while something actually bends or blurs it.
    val look = LocalSkinSettings.current
    // Not in battery saver: the frost is decoration, and it is the page drawn a
    // second time. The panes go solid instead (see SkinSurface); the setting
    // itself is left as chosen and the frost is back when saving ends.
    val backdrop = rememberBackdrop(
        active = skin == Skin.GLASSMORPHISM &&
            (look.blur > 0.1f || look.vibrancy > 0.01f) &&
            !InteractionGovernor.observedPowerSave,
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
    val desk = deskColor()
    Box(Modifier.weight(1f).fillMaxHeight().background(desk)) {
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
        // What the glass sees is the ground and the page the canvas has drawn,
        // never the canvas view itself: that view holds the wet-ink surfaces,
        // and a surface drawn into a recording moves to wherever the glass
        // draws that recording - the ink then showed beside the pen until it
        // lifted. Turned with the note, as the view is below.
        // The turn each recording was made at. Pausing the glass while the page
        // is busy is right for ink and scrolling, whose frost a moment late
        // nobody sees; a turned note is the whole picture at a new angle, and
        // the glass showed the old one until the screen went idle, or kept it
        // when nothing else came along to ask for a redraw.
        val recordedTurn = remember { floatArrayOf(Float.NaN) }
        val turnStale: () -> Boolean = { recordedTurn[0] != noteRotation }
        val pageBackdrop: DrawScope.() -> Unit = {
            recordedTurn[0] = noteRotation
            drawRect(desk)
            val view = canvas
            if (view != null) {
                // The canvas turns its own page about its middle; the glass sees it the same way.
                withTransform({
                    rotate(noteRotation)
                }) {
                    drawIntoCanvas { view.drawBackdrop(it.nativeCanvas) }
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                // A layer of its own: on the window's root list, any small
                // redraw anywhere (a clock tick, a spinner) re-ran both page
                // recordings and every pane of glass sampling them.
                .graphicsLayer()
                .recordBackdrop(backdrop, pageBackdrop, stale = turnStale)
                .onGloballyPositioned { pageLayerOrigin = it.positionOnScreen() }
                .then(
                    if (skin.isRefractive) {
                        Modifier.captureLiquidGlassBackdrop(
                            requireNotNull(liquidBackdrop),
                            // Any canvas busy - this one, or the reference panel's -
                            // and through the short settle after: the panes keep the
                            // last recording until the screen is idle again.
                            paused = { !turnStale() &&
                                (drawingPage || movingPage || InteractionGovernor.observed != Interaction.IDLE) },
                            source = pageBackdrop,
                        )
                    } else {
                        Modifier
                    },
                )
                .background(desk),
        ) {
        if (ready == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .dropImages(::addImageFrom)
                    // AndroidView를 Backdrop 레이어에 안정적으로 합성합니다.
                    .graphicsLayer {
                        alpha = 1f
                    },
                factory = { viewContext ->
                    InkCanvasView(viewContext).apply {
                        // Let go into PdfHold, so leaving and coming straight back is quick.
                        releasePdf = { PdfHold.release(it) }
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
                        onZoomChanged = {
                            liveZoom[0] = it
                            if (!movingPage) zoom = it
                        }
                        onSelectionChanged = { selectedText = it?.text; selectedPdf = it }
                        onImageSelected = { imageSelected = it; edits++ }
                        onTextRequested = { page, x, y ->
                            replacingText = null; textPosition = Triple(page, x, y); showTextBox = true
                        }
                        onLassoSelected = { lassoCount = it }
                        // A file that cannot be read is remembered as such: it was
                        // read and failed again on every frame that drew its page.
                        val unreadable = HashSet<String>()
                        imageLoader = { imageId ->
                            imageCache.get(imageId) ?: if (imageId in unreadable) null else runCatching {
                                android.graphics.BitmapFactory
                                    .decodeFile(store.imageFile(note.id, imageId).path)
                            }.getOrNull().also { if (it != null) imageCache.put(imageId, it) else unreadable += imageId }
                        }
                        imageAdder = { bitmap -> store.addImage(note.id, bitmap)?.first }
                        // Cached like the pictures: a custom paper was decoded
                        // from disk on every frame, and uploaded as a new texture.
                        templateLoader = { templateId ->
                            val key = "t:$templateId"
                            imageCache.get(key) ?: if (key in unreadable) null else runCatching {
                                android.graphics.BitmapFactory.decodeFile(store.templateFile(templateId).path)
                            }.getOrNull().also { if (it != null) imageCache.put(key, it) else unreadable += key }
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
                        if (!moving) zoom = liveZoom[0]
                    }
                    view.predictionEnabled = prediction
                    view.predictionLeadMs = predictionLeadMs
                    view.latencyMonitoringEnabled = showLatency
                    view.deferDetail = deferDetail
                    view.stabilizer = stabilizer
                    view.highlighterAboveInk = highlighterAboveInk
                    view.meshInk = meshInk
                    view.compatWetInk = compatWetInk
                    // Turned inside the canvas, not as a view: a turned view clipped the
                    // page's corners and left the wet ink's surface behind the pen.
                    view.viewRotation = noteRotation
                    view.onRotateNote = { delta ->
                        noteRotation = ((noteRotation + delta) % 360f + 360f) % 360f
                    }
                    view.onRotateNoteEnd = {
                        // Close to square is square: a quarter turn by hand lands a degree or two off.
                        val quarter = (noteRotation / 90f).roundToInt() * 90f
                        if (abs(noteRotation - quarter) <= ROTATION_SNAP_DEGREES) noteRotation = quarter % 360f
                        penStore.noteRotation = noteRotation.roundToInt()
                    }
                    view.softPredictionHead = !sharedScreen && webUrl == null && !referenceOpen
                    view.pageTone = pageTone
                    view.partialEraser = partialEraser
                    view.applyGestures(gestures)
                    view.laserMode = laser
                    view.onPullForPage = { pullingForPage = it }
                    view.onEditTextBox = { replacingText = canvas?.selectedTextBox(); showTextBox = true }
                    // Writing puts the page panel away; using it, scrolling and
                    // zooming do not. Read when it fires, not here, so this
                    // update block does not rerun every time the panel toggles.
                    // Only the panel's own scope reads these (see Isolated
                    // below), so the flip recomposes the panel, not the screen.
                    view.onWritingStarted = {
                        if (showPages) {
                            pagesClosedByPen = true
                            showPages = false
                        }
                    }
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
                        } ?: Toast.makeText(context, "Couldn't find the linked note", Toast.LENGTH_SHORT).show()
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
                        // Each tool keeps its own; the one in hand decides.
                        straightLine && mode in STRAIGHT_LINE_MODES -> ShapeKind.LINE
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
                                ToolbarLayout.config.shortcutModes(toolbarSize).getOrNull(digit)?.let { picked ->
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
                                // Find means the body search: open the panel on it.
                                noteSearch.panelTab = PAGE_PANEL_SEARCH_TAB
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
                    view.onCloseReference = { referenceOpen = false }
                    // Moving and resizing the panel itself is handled on its own
                    // view, not here; see ReferencePanel.
                },
            )
        }
        }

        // Autosave: a burst of strokes writes the note once, in a pause, rather
        // than once per stroke; see autosaveLoop for when.
        //
        // Both loops watch edits from a coroutine instead of using it as a
        // LaunchedEffect key. A key is read during composition, so every pen-up
        // recomposed this whole screen - page host, toolbar and panels - on the
        // UI thread right when the next stroke was starting.
        val latestNote by rememberUpdatedState(note)
        // When the last autosave was taken, so recognition keeps clear of it.
        val lastSaveAt = remember { longArrayOf(0L) }
        LaunchedEffect(Unit) {
            autosaveLoop(edits = { edits }) {
                val view = canvas ?: return@autosaveLoop
                lastSaveAt[0] = android.os.SystemClock.uptimeMillis()
                // Snapshot on the UI thread before the serial worker writes it.
                // Passing the live document to Dispatchers.IO raced pen commits and
                // mesh rebuilds, which is the repeated CME found in crash.log.
                val started = System.nanoTime()
                store.saveLater(latestNote.id, latestNote.title, view.document)
                view.latency.record(PerformanceMetric.SAVE_SNAPSHOT, System.nanoTime() - started)
            }
        }

        // Handwriting recognition is useful search metadata, not part of
        // saving. It is optional work: it waits for an idle screen whose hands
        // have been off it a while, keeps clear of the save, and does not rerun
        // for toolbar or image state that left the ink revision unchanged.
        LaunchedEffect(Unit) {
            snapshotFlow { edits }.collectLatest { count ->
                if (count == 0) return@collectLatest
                delay(UiScheduling.INK_INDEX_IDLE_MS)
                while (true) {
                    val sinceSave = lastSaveAt[0].let {
                        if (it == 0L) Long.MAX_VALUE / 2 else android.os.SystemClock.uptimeMillis() - it
                    }
                    val wait = UiScheduling.inkIndexWaitMs(
                        InteractionGovernor.state, InteractionGovernor.quietForMs(), sinceSave)
                    if (wait <= 0L && InteractionGovernor.mayRunOptional(UiScheduling.INK_INDEX_IDLE_MS)) break
                    delay(wait.coerceAtLeast(INK_INDEX_RECHECK_MS))
                }
                val view = canvas ?: return@collectLatest
                val page = view.document.pages.getOrNull(view.currentPageIndex()) ?: return@collectLatest
                if (!page.loaded) return@collectLatest
                val revision = page.revision
                if (indexedRevisions[page.id] == revision) return@collectLatest
                // A copy of the list of references, nothing deeper: strokes are immutable.
                val strokes = page.strokes.toList()
                val text = withContext(Dispatchers.IO) {
                    // A pen or finger back on the page stops it between lines;
                    // the page is read again at the next quiet spell.
                    indexer.textOf(strokes, keepGoing = { InteractionGovernor.state == Interaction.IDLE })
                } ?: return@collectLatest
                withContext(Dispatchers.IO) { store.writeInkIndex(latestNote.id, page.id, text) }
                indexedRevisions[page.id] = revision
            }
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
                    canvas?.let { Measurements.overlay(it, (liveZoom[0] * 100).roundToInt()) }.orEmpty()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(ChromeInsets)
                    .padding(top = 84.dp, start = 24.dp),
            )
        }

        // Its own scope: the scrubber's timer key moves with every page passed
        // in a fling, and read out here it recomposed the whole screen each time.
        Isolated {
        LaunchedEffect(pageScrubberActivity) {
            if (!pageScrubberVisible) return@LaunchedEffect
            delay(PAGE_SCRUBBER_IDLE_MS)
            pageScrubberVisible = false
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
                thumbnail = { index -> canvas?.pageThumbnail(index, SCRUBBER_THUMBNAIL_PX) },
            )
        }
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
                null,
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

        // Above the page and the bar, on the same glass as the bar.
        ClockOverlay()

        // Settings live in a sidebar along the edge, over the page and the
        // bar, with its own screens inside it rather than dialogs on dialogs.
        // Isolated: opening it, moving between its screens and what its quick
        // actions read (zoom, rotation, the page panel) recompose the sidebar,
        // not the note behind it.
        Isolated {
        // Kept on screen after it is closed, for as long as it takes to slide
        // away; Material shows and removes it at once.
        AnimatedSettingsSidebar(settingsNav) { nav, appear, closing ->
            NoteSettingsSidebar(
                appear = appear,
                closing = closing,
                nav = nav,
                onNav = { settingsNav = it },
                onClose = { settingsNav = null },
                widthDp = settingsWidth,
                onWidthChange = { settingsWidth = it; penStore.settingsSidebarWidth = it },
                quickActions = listOf(
                    SpotiToolbarAction(if (docked) "Undock bar" else "Dock bar",
                        if (docked) AppIcons.undock else AppIcons.dock, selected = docked) {
                        docked = !docked
                        penStore.docked = docked
                    },
                    SpotiToolbarAction("Reset bar position", AppIcons.resetBar,
                        enabled = !docked && barOffset != null,
                        disabledReason = when {
                            docked -> "A docked bar stays put"
                            barOffset == null -> "Already in place"
                            else -> null
                        }) { barOffset = null },
                    SpotiToolbarAction("Rotate · ${noteRotation.roundToInt()}°", AppIcons.rotate) {
                        noteRotation = (((noteRotation / 90f).roundToInt() + 1) * 90 % 360).toFloat()
                        penStore.noteRotation = noteRotation.roundToInt()
                    },
                    SpotiToolbarAction("Zoom · ${(zoom * 100).roundToInt()}%", AppIcons.zoom) { zoomMenu = true },
                    SpotiToolbarAction("Pages & search", AppIcons.search, selected = showPages) { showPages = !showPages },
                    SpotiToolbarAction("Pen in hand", AppIcons.penSettings) {
                        if (mode !in PenStore.DEFAULTS) mode = EditMode.PEN
                        editingPen = true
                    },
                    SpotiToolbarAction("Back to notes", AppIcons.back) {
                        settingsNav = null
                        onBack()
                    },
                ),
                webActions = listOf(SpotiToolbarAction("Search the web", AppIcons.web) { webUrl = SEARCH_HOME }) +
                    AI_SITES.map { (name, url) -> SpotiToolbarAction(name, AppIcons.ai) { webUrl = url } },
                penSettings = {
                    GlobalPenSettings(
                        gestures = gestures,
                        onGestures = { setGestures(it) },
                        deferDetail = deferDetail,
                        onDeferDetail = { deferDetail = it; penStore.deferDetail = it },
                        meshInk = meshInk,
                        onMeshInk = { meshInk = it; penStore.meshInk = it },
                        compatWetInk = compatWetInk,
                        onCompatWetInk = { compatWetInk = it; penStore.compatWetInk = it },
                        onPenInHand = {
                            if (mode !in PenStore.DEFAULTS) mode = EditMode.PEN
                            editingPen = true
                        },
                    )
                },
                appearance = {
                    AppearanceSettings(skin, LocalSkinSettings.current, onSkin, onLookChange,
                        preview = { toolbar(Modifier, 1) })
                },
                touch = { TouchGestureSettings(gestures) { setGestures(it) } },
                toolbar = { done ->
                    ToolbarCustomizeScreen(
                        initialStage = toolbarSize,
                        preview = { level -> toolbar(Modifier, level) },
                        onFinished = done,
                    )
                },
                noteView = {
                    val meta = remember(edits) { store.list().firstOrNull { it.id == note.id } ?: note }
                    var confirmDelete by remember { mutableStateOf(false) }
                    var filing by remember { mutableStateOf(false) }
                    SettingsCard {
                        SettingsSectionHeader("Page layout")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                            listOf(PageLayoutMode.VERTICAL to "Vertical scroll", PageLayoutMode.HORIZONTAL to "Horizontal scroll",
                                PageLayoutMode.SPREAD_2X1 to "Two pages", PageLayoutMode.SPREAD_COVER to "Two pages, cover alone",
                                PageLayoutMode.GRID_2X2 to "Four pages").forEach { (layout, label) ->
                                SettingsChoiceChip(selected = pageLayout == layout, onClick = {
                                    pageLayout = layout
                                    penStore.setPageLayoutOf(note.id, layout)
                                    canvas?.setPageLayout(layout)
                                }, label = label)
                            }
                        }
                    }
                    SettingsCard {
                        SettingsSectionHeader("Page tone")
                        SettingsChoices(
                            "Screen color",
                            listOf(PAGE_TONE_NORMAL to "Normal", PAGE_TONE_SEPIA to "Sepia", PAGE_TONE_NIGHT to "Night"),
                            pageTone,
                            hint = "Changes only what the screen shows. The note and its exports keep their colors, " +
                                "and at night the stroke being written shows in its own color for a moment.",
                        ) { tone ->
                            pageTone = tone
                            penStore.pageTone = tone
                        }
                    }
                    SettingsCard {
                        SettingsSectionHeader("This note")
                        Text(note.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        SettingsToggle("Favorite", meta.favorite, { store.setFavorite(note.id, it); edits++ })
                        TextButton(onClick = { filing = true }) { Text("Move to folder") }
                        TextButton(onClick = {
                            canvas?.let { view -> exportingPages = view.snapshotPages(view.document.pages.indices.toList()) }
                            savePagesPdf.launch(safeFileName(note.title) + ".pdf")
                        }) { Text("Export as PDF") }
                        val canLock = meta.locked || NoteLock(context).hasPassword
                        TextButton(
                            enabled = canLock,
                            onClick = { store.setLocked(note.id, !meta.locked); edits++ },
                            modifier = Modifier.disabledReason(if (canLock) null else "No password set yet"),
                        ) { Text(if (meta.locked) "Unlock" else "Lock with password") }
                        if (!canLock) SettingsNote("Set a password from the note list to lock notes.")
                        if (!confirmDelete) {
                            TextButton(onClick = { confirmDelete = true }) {
                                Text("Move to trash", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            // Asked in place: one more tap, not one more dialog.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Move this note to the trash?", style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                                TextButton(onClick = {
                                    confirmDelete = false
                                    settingsNav = null
                                    store.moveToTrash(note.id)
                                    onBack()
                                }) { Text("Move", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    SettingsCard {
                        SettingsSectionHeader("Handwriting recognition")
                        SettingsNote("The languages handwriting is read in for search; the first is tried first.")
                        var languages by remember { mutableStateOf(InkIndexer.languages(context)) }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                            InkIndexer.OFFERED.forEach { (tag, label) ->
                                SettingsChoiceChip(selected = tag in languages, onClick = {
                                    val next = if (tag in languages) languages - tag else languages + tag
                                    if (next.isNotEmpty()) { languages = next; InkIndexer.setLanguages(context, next) }
                                }, label = label)
                            }
                        }
                    }
                    if (filing) FolderDialog(current = meta.folder, folders = remember { store.folders() },
                        onDismiss = { filing = false }, onPick = { store.setFolder(note.id, it); filing = false; edits++ })
                },
                diagnostics = {
                    DiagnosticsSettings(
                        view = canvas,
                        overlay = showLatency,
                        onOverlay = { showLatency = it },
                        zoomPercent = (zoom * 100).roundToInt(),
                    )
                },
            )
        }
        }

        /**
         * Plays the recording at [path] at the chosen speed, and at its end
         * stops, starts the next or begins it again, as [PenStore.playMode] says.
         */
        fun playRecording(path: String) {
            voicePlayer?.release()
            voicePlayer = null
            val player = android.media.MediaPlayer()
            val started = runCatching {
                player.setDataSource(path)
                player.prepare()
                player.isLooping = penStore.playMode == PLAY_LOOP_ONE
                player.setOnCompletionListener {
                    it.release(); voicePlayer = null; playingFile = null
                    if (penStore.playMode == PLAY_NEXT) {
                        val all = store.recordings(note.id)
                        all.getOrNull(all.indexOfFirst { f -> f.path == path } + 1)?.let { next -> playNextRecording = next.path }
                    }
                }
                player.start()
                setPlaybackSpeed(player, playbackSpeed)
            }.isSuccess
            if (started) {
                voicePlayer = player; playingFile = path
                playingTimeline = RecordingTimeline.load(java.io.File(path))
            } else { player.release(); playingFile = null }
        }

        // A finished recording asked for the next: start it the way the play button would.
        LaunchedEffect(playNextRecording) {
            val path = playNextRecording ?: return@LaunchedEffect
            playNextRecording = null
            playRecording(path)
        }
        if (showVoice) {
            val recordings = remember(note.id, voiceRevision) { store.recordings(note.id) }
            AlertDialog(
                onDismissRequest = { showVoice = false },
                title = { Text("Voice recordings") },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        TextButton(onClick = {
                            if (recorder != null) stopRecording()
                            else if (androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.RECORD_AUDIO,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                startRecording()
                            } else requestAudioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }) { Text(if (recorder == null) "● Start new recording" else "■ Stop recording") }
                        TextButton(enabled = recorder == null, onClick = {
                            showVoice = false
                            if (androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.RECORD_AUDIO,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) dictating = true
                            else requestAudioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }) { Text("Dictation") }
                        Text("When finished", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf(PLAY_STOP to "Stop", PLAY_NEXT to "Next recording", PLAY_LOOP_ONE to "Repeat one").forEach { (mode, label) ->
                                SettingsChoiceChip(selected = playMode == mode, onClick = {
                                    playMode = mode
                                    penStore.playMode = mode
                                    voicePlayer?.isLooping = mode == PLAY_LOOP_ONE
                                }, label = label, modifier = Modifier.padding(end = Ui.Space.s))
                            }
                        }
                        for (file in recordings) Row(verticalAlignment = Alignment.CenterVertically) {
                            val label = runCatching {
                                dateFormat.format(Date(file.nameWithoutExtension.toLong()))
                            }.getOrDefault(file.nameWithoutExtension)
                            Text(label, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                if (playingFile == file.path) {
                                    voicePlayer?.release(); voicePlayer = null; playingFile = null
                                } else playRecording(file.path)
                            }) { Text(if (playingFile == file.path) "Stop" else "Play") }
                            TextButton(onClick = { shareFile(context, file, "audio/*") }) { Text("Export") }
                            TextButton(onClick = {
                                if (playingFile == file.path) {
                                    voicePlayer?.release(); voicePlayer = null; playingFile = null
                                }
                                file.delete()
                                RecordingTimeline.fileFor(file).delete()
                                voiceRevision++
                            }) { Text("Delete") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showVoice = false }) { Text("Close") } },
            )
        }

        if (editingPen) {
            PenDialog(
                mode = mode,
                pen = pen,
                highlighterAboveInk = highlighterAboveInk,
                onHighlighterAboveInk = {
                    highlighterAboveInk = it; penStore.highlighterAboveInk = it
                },
                onAllPens = {
                    editingPen = false
                    openSettings(SettingsPage.PEN)
                },
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
                axisSnap = axisSnap,
                onAxisSnap = { axisSnap = it; penStore.axisSnap = it },
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

        captured?.let { preview ->
            /**
             * Renders the capture again at the size picked in the dialog and
             * hands it on. Off the main thread: a 4096 px render of a dense page
             * is a visible pause. Dropped if the dialog was closed meanwhile.
             */
            fun withOutput(use: (Bitmap) -> Unit) {
                val view = canvas
                val region = view?.lastCapture
                if (view == null || region == null) { use(preview); return }
                val options = captureOptions
                captureBusy = true
                scope.launch {
                    val plan = captureOutputPlan(region, options.size)
                    val output = withContext(Dispatchers.Default) { view.renderCapture(region, plan.scale) }
                    captureBusy = false
                    if (captured == null) return@launch
                    if (output == null) {
                        Toast.makeText(context, "Couldn't render the capture at this size", Toast.LENGTH_SHORT).show()
                    } else use(output)
                }
            }
            CaptureDialog(
                bitmap = preview,
                region = canvas?.lastCapture,
                options = captureOptions,
                onOptions = {
                    captureOptions = it
                    captureOptionPrefs.edit().putString("options", it.encode()).apply()
                },
                busy = captureBusy,
                onPaste = {
                    withOutput { bitmap ->
                        val added = store.addImage(note.id, bitmap)
                        if (added != null) {
                            canvas?.insertImage(added.first, added.second)
                            mode = EditMode.IMAGE
                            edits++
                        }
                        captured = null
                    }
                },
                onCarryLinked = {
                    withOutput { bitmap ->
                        val view = canvas
                        val source = view?.lastCapturePage ?: -1
                        val pageId = view?.pageIdAt(source)
                        if (pageId != null) {
                            SnipLink.pending = SnipLink.Snip(bitmap, note.id, note.title, pageId, source)
                            Toast.makeText(context, "Go to the note and page to paste it into, then tap \"Paste here\"",
                                Toast.LENGTH_LONG).show()
                        }
                        captured = null
                    }
                },
                onAttach = {
                    withOutput { bitmap ->
                        val options = captureOptions
                        val uri = captureUri(context, bitmap, options.format, options.jpegQuality)
                        if (uri == null) {
                            Toast.makeText(context, "Couldn't save the capture", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingAttachment.value = uri
                            if (webUrl == null) webUrl = AI_SITES.first().second
                            // Naming the two taps: the sheet behind "+" offers a
                            // camera and a photo picker too, and neither of those
                            // is the file chooser this capture is waiting for.
                            Toast.makeText(
                                context,
                                "Tap + in the chat and choose \"Files\"",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        captured = null
                    }
                },
                onShare = {
                    withOutput { bitmap ->
                        shareBitmap(context, bitmap, captureOptions.format, captureOptions.jpegQuality)
                        captured = null
                    }
                },
                onSave = {
                    withOutput { bitmap ->
                        captured = null
                        val options = captureOptions
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                saveToGallery(context,
                                    "Notesis-capture-${System.currentTimeMillis()}.${options.format.extension}",
                                    bitmap, options.format, options.jpegQuality)
                            }
                            Toast.makeText(context, if (ok) "Saved to the gallery" else "Couldn't save the capture",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDismiss = { captured = null },
            )
        }

        SnipLink.pending?.let { snip ->
            SkinSurface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(ChromeInsets)
                    .padding(top = 150.dp),
            ) {
                Row(Modifier.padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        snip.bitmap.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(Ui.Shape.preview),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        if (snip.noteId == note.id) " Capture from page ${snip.pageIndex + 1}"
                        else " Capture from ${snip.noteTitle}, page ${snip.pageIndex + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                    TextButton(onClick = {
                        val added = store.addImage(note.id, snip.bitmap)
                        if (added != null) {
                            canvas?.insertLinkedImage(added.first, added.second, SnipLink.linkFor(snip, note.id))
                            mode = EditMode.IMAGE
                            edits++
                        }
                        SnipLink.pending = null
                    }) { Text("Paste here") }
                    TextButton(onClick = { SnipLink.pending = null }) { Text("Cancel") }
                }
            }
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
                            ?: "Recognition model unavailable"
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
                recolorChoices = listOf(settings.getValue(EditMode.PEN).colorArgb) + penStore.quickColors,
                onRecolorTo = { argb ->
                    canvas?.recolorLassoSelection(argb)
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
                        Toast.makeText(context, if (id != null) "Added to library" else "Couldn't add to library",
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
        ) {
            TextButton(onClick = { canvas?.goBack() }, modifier = Modifier.padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs)) {
                Icon(Reicons.ArrowBack, contentDescription = null)
                Text(" Back to previous spot")
            }
        }
        sendingPages?.let { (indices, move) ->
            val others = remember { store.list().filter { it.id != note.id && it.kind == NoteKind.INK } }
            AlertDialog(
                onDismissRequest = { sendingPages = null },
                title = { Text(if (move) "Move ${countNoun(indices.size, "page")} to…" else "Copy ${countNoun(indices.size, "page")} to…") },
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
                                        Toast.makeText(context, if (ok) "Added ${countNoun(pages.size, "page")} to \"${target.title}\""
                                            else if (move) "Couldn't move the pages" else "Couldn't copy the pages", Toast.LENGTH_SHORT).show()
                                    }
                                }.padding(12.dp))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { sendingPages = null }) { Text("Cancel") } },
            )
        }
        if (choosingShape) ShapePickerDialog(
            current = shapeKind,
            holdToShape = gestures.holdToDraw,
            onHoldToShape = { setGestures(gestures.copy(holdToDraw = it)) },
            cornerRadius = shapeCorner,
            onCornerRadius = { shapeCorner = it; penStore.shapeCornerRadius = it },
            onPick = { picked ->
                choosingShape = false
                shapeKind = picked
                canvas?.clearSelection()
                canvas?.clearImageSelection()
                mode = EditMode.SHAPE
            },
            onDismiss = { choosingShape = false },
        )
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
                .padding(Ui.Space.l),
        ) {
            Row(Modifier.padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (playingTimeline.isEmpty()) "Playing recording"
                    else "Playing recording · In read mode, tap ink to jump to that moment",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { voicePlayer?.let { seekBy(it, -SKIP_MS) } }) { Text("−15 s") }
                TextButton(onClick = { voicePlayer?.let { seekBy(it, SKIP_MS) } }) { Text("+15 s") }
                TextButton(onClick = {
                    playbackSpeed = nextPlaybackSpeed(playbackSpeed)
                    penStore.playbackSpeed = playbackSpeed
                    voicePlayer?.let { setPlaybackSpeed(it, playbackSpeed) }
                }) { Text("${formatSpeed(playbackSpeed)}×") }
                TextButton(onClick = {
                    playMode = (playMode + 1) % 3
                    penStore.playMode = playMode
                    voicePlayer?.isLooping = playMode == PLAY_LOOP_ONE
                }) { Text(when (playMode) { PLAY_NEXT -> "Next recording"; PLAY_LOOP_ONE -> "Repeat one"; else -> "Stop when finished" }) }
                TextButton(onClick = {
                    voicePlayer?.release(); voicePlayer = null; playingFile = null
                }) { Text("Stop") }
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
                    title = { Text("Mask name") },
                    text = {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            placeholder = { Text("Mask ${maskIndex + 1}") },
                        )
                    },
                    confirmButton = { TextButton(onClick = {
                        study.rename(key, name)
                        studyRevision++
                        renamingMask = null
                    }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { renamingMask = null }) { Text("Cancel") } },
                )
            }
        }

        if (regionOcrBusy) AlertDialog(
            onDismissRequest = {}, title = { Text("Reading selected handwriting…") }, confirmButton = {},
        )
        regionOcrText?.let { recognized -> AlertDialog(
            onDismissRequest = { regionOcrText = null },
            title = { Text("Handwriting recognition") },
            text = { Text(recognized.ifBlank { "No text recognized" }) },
            confirmButton = { TextButton(onClick = {
                clipboard.setText(AnnotatedString(recognized))
                regionOcrText = null
            }) { Text("Copy") } },
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
                    }) { Text("Convert to text") }
                    TextButton(onClick = { regionOcrText = null }) { Text("Close") }
                }
            },
        ) }

        if (showTextBox) TextBoxDialog(replacingText?.textContent,
            onDismiss = { showTextBox = false }, onSave = { content ->
                val replacing = replacingText
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val bitmap = renderTextBox(content)
                        try {
                            val image = store.addImage(note.id, bitmap) ?: error("Couldn't save the text")
                            Triple(image.first, bitmap.width, bitmap.height)
                        } finally { bitmap.recycle() }
                    } }
                    result.onSuccess { (id, width, height) ->
                        canvas?.putTextBox(id, width, height, content, replacing, textPosition)
                        mode = EditMode.TEXT
                        edits++
                        showTextBox = false
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Couldn't save the text", Toast.LENGTH_LONG).show()
                    }
                }
            })

        if (imageSelected) {
            val selectedBox = edits.let { canvas?.selectedImageBox() ?: PageImage.BOX_NONE }
            ImageActions(
                isText = canvas?.selectedTextBox() != null,
                box = selectedBox,
                onFold = { canvas?.toggleSelectedFolded(); edits++ },
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
                else Toast.makeText(context, "Couldn't save the image", Toast.LENGTH_SHORT).show()
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
        // The layer panel slides in from the end, like the page sidebar; the
        // canvas saves every change itself.
        androidx.compose.animation.AnimatedVisibility(
            visible = showLayers && canvas != null,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            canvas?.let { view -> LayerPanel(view, onClose = { showLayers = false }) }
        }
        if (pullingForPage) Box(
            Modifier
                .align(if (pageLayout == PageLayoutMode.HORIZONTAL) Alignment.CenterEnd else Alignment.BottomCenter)
                .padding(32.dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0xFF1E88E5)),
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Color.White, style = MaterialTheme.typography.headlineMedium) }
        if (dictating) DictationDialog(
            onInsert = { text -> dictating = false; putTypedText(text) },
            onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
            onDismiss = { dictating = false },
        )

        pressMenu?.let { at ->
            val system = context.getSystemService(android.content.ClipboardManager::class.java)
            val clip = system?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val inkClip = InkClipboard.clips.firstOrNull()
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
                DropdownMenu(true, onDismissRequest = { pressMenu = null }) {
                    if (inkClip != null) DropdownMenuItem(text = { Text("Paste (${countNoun(inkClip.size, "item")})") }, onClick = {
                        pressMenu = null
                        canvas?.paste(inkClip)
                        edits++
                    })
                    val pastedText = clip?.coerceToText(context)?.toString()?.takeIf { clip.uri == null && it.isNotBlank() }
                    if (pastedText != null) DropdownMenuItem(text = { Text("Paste text") }, onClick = {
                        pressMenu = null
                        putTypedText(pastedText)
                    })
                    val uri = clip?.uri
                    if (uri != null) DropdownMenuItem(text = { Text("Paste image") }, onClick = {
                        pressMenu = null
                        scope.launch {
                            val added = withContext(Dispatchers.IO) {
                                runCatching { context.contentResolver.openInputStream(uri)?.use { store.addImage(note.id, it) } }.getOrNull()
                            }
                            if (added != null) { canvas?.insertImage(added.first, added.second); edits++ }
                            else Toast.makeText(context, "Couldn't paste the image", Toast.LENGTH_SHORT).show()
                        }
                    })
                    if (inkClip == null && pastedText == null && uri == null) {
                        DropdownMenuItem(text = { Text("Nothing to paste") }, onClick = { pressMenu = null }, enabled = false)
                    }
                }
            }
        }

        if (zoomMenu) {
            ZoomPresetDialog(
                current = zoom,
                onPick = { percent ->
                    canvas?.zoomTo(percent / 100f)
                    zoomMenu = false
                },
                onDismiss = { zoomMenu = false },
            )
        }

        selectionColorMode?.let { colorMode ->
            PenDialog(mode = colorMode, pen = settings.getValue(colorMode),
                highlighterAboveInk = highlighterAboveInk,
                onHighlighterAboveInk = {
                    highlighterAboveInk = it; penStore.highlighterAboveInk = it
                },
                axisSnap = axisSnap,
                onAxisSnap = { axisSnap = it; penStore.axisSnap = it },
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
        // In a scope of its own, so opening and closing it - writing closes
        // it - recomposes the panel and nothing else: not the page host, not
        // the toolbar, not the AndroidView's update.
        Isolated {
        // Opened by hand, it closes the usual way again.
        LaunchedEffect(showPages) { if (showPages) pagesClosedByPen = false }
        androidx.compose.animation.AnimatedVisibility(
            visible = showPages,
            enter = slideInHorizontally { it },
            exit = if (pagesClosedByPen) ExitTransition.None else slideOutHorizontally { it },
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
                    exportingLook = canvas?.layers?.translucentLook()
                    if (exportingPages != null) savePagesPdf.launch(safeFileName(note.title) + "-pages.pdf")
                },
                onSendPages = { indices, move -> sendingPages = indices to move },
                search = noteSearch,
                searchView = canvas,
                onSearchHit = { hit ->
                    val view = canvas
                    val pages = view?.document?.pages
                    if (view != null && pages != null) {
                        // By id: the page may have moved since the search ran.
                        val index = pages.indexOfFirst { it.id == hit.pageId }.takeIf { it >= 0 } ?: hit.pageIndex
                        val page = pages.getOrNull(index)
                        val box = hit.box
                        if (box != null) {
                            view.showSearchHit(index, listOf(android.graphics.RectF(box.left, box.top, box.right, box.bottom)))
                        } else if (hit.source == SearchSource.PDF && page != null && page.pdfPageIndex >= 0) {
                            // The PDF knows where its own text is; asking it opens
                            // one page off the main thread. Until it answers, the
                            // page is already in view.
                            view.scrollToPage(index)
                            val pdfIndex = page.pdfPageIndex
                            val query = noteSearch.query.trim()
                            scope.launch {
                                val rects = withContext(Dispatchers.IO) {
                                    view.pdfMatchBounds(pdfIndex, query, hit.occurrence)
                                }
                                if (noteSearch.hits.getOrNull(noteSearch.selected) == hit) view.showSearchHit(index, rects)
                            }
                        } else {
                            view.showSearchHit(index, emptyList())
                        }
                    }
                },
            )
        }
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
                gestures = gestures,
                autoShapes = autoShapes,
                axisSnap = axisSnap,
                dottedPattern = dottedPattern,
                offset = { referenceOffset },
                size = referenceSize,
                stretch = { referenceStretch },
                dismissProgress = {
                    (referenceOvershoot / with(density) { REFERENCE_DISMISS_DRAG.toPx() }).coerceIn(0f, 1f)
                },
                draft = { referenceDraft },
                onResize = { dx, dy -> resizeReference(dx, dy) },
                onResizeEnd = { view ->
                    val draft = referenceDraft
                    referenceDraft = null
                    referenceResizeDrag = Offset.Zero
                    if (draft != null && draft != referenceSize) {
                        val widthFactor = draft.width / referenceSize.width
                        referenceSize = draft
                        // Spent by the view when the new size arrives, as with a spread.
                        if (abs(widthFactor - 1f) > 0.001f) view?.onNextResize(widthFactor, fitInstead = referenceFit)
                    }
                },
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
                    // Put down: let go well past the bottom edge.
                    val dismiss = referenceOvershoot >= with(density) { REFERENCE_DISMISS_DRAG.toPx() }
                    referenceOvershoot = 0f
                    dismiss
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
                onDrag = { x, y, spread -> moveReference(x, y, spread) },
                onClose = {
                    referenceStretch = 1f
                    referenceOvershoot = 0f
                    referenceDraft = null
                    referenceResizeDrag = Offset.Zero
                    referenceOpen = false
                },
            ) }
        }
    }
        if (docked && !collapsed) {
            toolbar(Modifier.align(Alignment.TopCenter).then(
                if (toolbarSize > 0) Modifier.spotiToolbarAnimatedWidth(floatingBarWidth)
                    .windowInsetsPadding(ChromeInsets.only(WindowInsetsSides.Top))
                else Modifier.fillMaxWidth()), null)
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
            .padding(Ui.Space.l),
    ) {
        Row(
            Modifier.padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (session.done) {
                Text(
                    "${session.right} of ${session.queue.size} correct",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ToolbarDivider()
                if (session.missed.isNotEmpty()) {
                    TextButton(onClick = onRetryMissed) { Text("Retry ${session.missed.size} missed") }
                }
            } else {
                Text(
                    "${session.position + 1} / ${session.queue.size}" +
                        if (name.isNotBlank()) " · $name" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ToolbarDivider()
                if (!session.showing) {
                    TextButton(onClick = onShow) { Text("Show answer") }
                } else {
                    TextButton(onClick = { onAnswer(true) }) {
                        Text("Correct", color = Color(0xFF2E7D32))
                    }
                    TextButton(onClick = { onAnswer(false) }) {
                        Text("Wrong", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            TextButton(onClick = onClose) { Text("Finish") }
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
    /** Colours offered for the selection besides the pen's; picking one recolours it. */
    recolorChoices: List<Int> = emptyList(),
    onRecolorTo: ((Int) -> Unit)? = null,
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
            .padding(Ui.Space.l),
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${countNoun(count, "item")} · Drag to move", style = MaterialTheme.typography.bodyMedium)
            ToolbarDivider()
            if (onTransform != null) {
                IconButton(onClick = { onTransform(0.8f, 0f) }) {
                    Icon(Reicons.Remove, contentDescription = "Shrink")
                }
                IconButton(onClick = { onTransform(1.25f, 0f) }) {
                    Icon(Reicons.Add, contentDescription = "Enlarge")
                }
                IconButton(onClick = { onTransform(1f, 15f) }) {
                    Icon(Reicons.Refresh, contentDescription = "Rotate 15°")
                }
            }
            if (onRecolorTo != null && recolorChoices.isNotEmpty()) {
                // Change colour: a row of swatches, the pen's colour first.
                var picking by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { picking = true }) {
                        Icon(Reicons.Palette, contentDescription = "Change color")
                    }
                    DropdownMenu(picking, onDismissRequest = { picking = false }) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                            recolorChoices.distinct().forEach { argb ->
                                Box(
                                    Modifier
                                        .padding(4.dp)
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(Color(argb or 0xFF000000.toInt()))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                        .clickable { picking = false; onRecolorTo(argb) },
                                )
                            }
                        }
                    }
                }
            } else if (onRecolor != null) IconButton(onClick = onRecolor) {
                Icon(Reicons.Palette, contentDescription = "Use current pen color")
            }
            if (onDuplicate != null) IconButton(onClick = onDuplicate) {
                Icon(Reicons.ContentCopy, contentDescription = "Duplicate")
            }
            if (onCopy != null) TextButton(onClick = onCopy) { Text("Copy") }
            if (onCut != null) TextButton(onClick = onCut) { Text("Cut") }
            if (onLink != null) TextButton(onClick = onLink) { Text("Link") }
            if (onAddToLibrary != null) TextButton(onClick = onAddToLibrary) { Text("Add to library") }
            if (onGroup != null) TextButton(onClick = { onGroup(!grouped) }) {
                Text(if (grouped) "Ungroup" else "Group")
            }
            if (onLock != null) TextButton(onClick = { onLock(!locked) }) {
                Text(if (locked) "Unlock" else "Lock")
            }
            TextButton(onClick = onDelete) {
                Icon(Reicons.Delete, contentDescription = null)
                Text(" Delete")
            }
            if (onOcr != null) TextButton(onClick = onOcr) { Text("OCR") }
            TextButton(onClick = onDone) { Text("Done") }
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
            .padding(Ui.Space.l),
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onLibrary) { Text("Library") }
            SettingsChoiceChip(selected = !gestures.lassoRect,
                onClick = { onGestures(gestures.copy(lassoRect = false)) },
                label = "Freeform", modifier = Modifier.padding(end = Ui.Space.s))
            SettingsChoiceChip(selected = gestures.lassoRect,
                onClick = { onGestures(gestures.copy(lassoRect = true)) },
                label = "Rectangle", modifier = Modifier.padding(end = Ui.Space.s))
            SettingsChoiceChip(selected = wholeOnly, onClick = { onWholeOnly(!wholeOnly) },
                label = "Fully enclosed only")
            SettingsChoiceChip(selected = gestures.snapToAlign,
                onClick = { onGestures(gestures.copy(snapToAlign = !gestures.snapToAlign)) },
                label = "Snap to align", modifier = Modifier.padding(start = 6.dp))
            SettingsChoiceChip(selected = gestures.keepAspect,
                onClick = { onGestures(gestures.copy(keepAspect = !gestures.keepAspect)) },
                label = "Keep aspect ratio", modifier = Modifier.padding(start = 6.dp))
            ToolbarDivider()
            var types by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { types = true }) { Text("Select types") }
                DropdownMenu(types, onDismissRequest = { types = false }) {
                    listOf(
                        "Pen" to gestures.lassoInk,
                        "Highlighter" to gestures.lassoHighlighter,
                        "Images" to gestures.lassoPictures,
                        "Text boxes" to gestures.lassoText,
                        "Locked objects" to gestures.selectLocked,
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
                TextButton(onClick = { onPaste(clips.first()) }) { Text("Paste") }
                Box {
                    TextButton(onClick = { open = true }) { Text("Clipboard ${clips.size}") }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        clips.forEachIndexed { index, clip ->
                            DropdownMenuItem(
                                text = { Text("${index + 1}. ${countNoun(clip.size, "item")}") },
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
            .padding(Ui.Space.l),
    ) {
        Row(
            Modifier.padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(when (box) {
                PageImage.BOX_STICKY -> "Sticky note"
                PageImage.BOX_TABLE -> "Table"
                PageImage.BOX_CHECK -> "Checkbox"
                else -> if (isText) "Text" else "Photo"
            }, style = MaterialTheme.typography.bodyMedium)
            if (box == PageImage.BOX_STICKY) TextButton(onClick = onFold) { Text("Fold/Unfold") }
            // Tables can no longer be made or rebuilt; one already on a page is
            // a picture of a grid, kept as it is.
            if (isText) TextButton(onClick = onEdit) { Text("Edit") }
            else if (box != PageImage.BOX_TABLE && box != PageImage.BOX_CHECK) {
                TextButton(onClick = onCrop) { Text("Crop") }
                TextButton(onClick = onStyle) { Text("Style") }
            }
            IconButton(onClick = onRotate) { Icon(Reicons.Refresh, contentDescription = "Rotate 90°") }
            ToolbarDivider()
            TextButton(onClick = onDelete) {
                Icon(Reicons.Delete, contentDescription = null)
                Text(" Delete")
            }
            TextButton(onClick = onDone) { Text("Done") }
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

/**
 * Writes [bitmap] into the shared Pictures/Notesis album, where the gallery
 * finds it. Scoped storage (minSdk 29) needs no permission for this.
 */
internal fun saveToGallery(
    context: android.content.Context,
    name: String,
    bitmap: Bitmap,
    format: CaptureFormat = CaptureFormat.PNG,
    jpegQuality: Int = 90,
): Boolean {
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, format.mime)
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Notesis")
        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { compressCapture(bitmap, format, jpegQuality, it) } == true
    }.getOrDefault(false)
    // A half-written picture would sit in the gallery as a broken tile.
    if (!ok) { resolver.delete(uri, null, null); return false }
    values.clear()
    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return true
}

/** [bitmap] into [out] as [format]; PNG ignores [jpegQuality]. */
private fun compressCapture(bitmap: Bitmap, format: CaptureFormat, jpegQuality: Int, out: java.io.OutputStream): Boolean =
    when (format) {
        CaptureFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        CaptureFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(1, 100), out)
    }

/** A capture written where another app is allowed to read it. */
private fun captureUri(
    context: android.content.Context,
    bitmap: Bitmap,
    format: CaptureFormat = CaptureFormat.PNG,
    jpegQuality: Int = 90,
): Uri? {
    val shared = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(shared, "capture.${format.extension}")
    val written = runCatching {
        file.outputStream().use { compressCapture(bitmap, format, jpegQuality, it) }
    }.getOrDefault(false)
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
private fun shareBitmap(
    context: android.content.Context,
    bitmap: Bitmap,
    format: CaptureFormat = CaptureFormat.PNG,
    jpegQuality: Int = 90,
) {
    val uri = captureUri(context, bitmap, format, jpegQuality) ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = format.mime
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share capture"))
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
    // Read where they are used - placement, the layer, the draw - and not in
    // composition: a drag moves them every frame, and passed as values each
    // frame recomposed the whole panel around a page that had not changed.
    offset: () -> Offset,
    size: Size,
    /** What the whole panel is scaled by while a spread is in progress. */
    stretch: () -> Float,
    /**
     * The three-finger drag has ended: fold [stretch] into the size, given this
     * panel's view. True means the panel was let go past the bottom edge, and
     * is put down.
     */
    onStretchEnd: (InkCanvasView?) -> Boolean,
    /** 0 to 1: how close the panel, dragged past the bottom edge, is to being put down on release. */
    dismissProgress: () -> Float = { 0f },
    /** The size a resize handle is being dragged to, drawn as an outline; null when none is held. */
    draft: () -> Size? = { null },
    /** A resize handle moved by this much (pixels, each axis free). */
    onResize: (Float, Float) -> Unit = { _, _ -> },
    /** The resize handle was let go: take the [draft] size, given this panel's view. */
    onResizeEnd: (InkCanvasView?) -> Unit = {},
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
    val latestResize by rememberUpdatedState(onResize)
    val latestResizeEnd by rememberUpdatedState(onResizeEnd)
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
                // A second view of a PDF beside the note: half the budget, so the
                // two together do not hold twice what one may.
                pendingPdf = PdfHold.take(store.pdfFile(noteId), PdfSource.cacheBytesFor(context, share = 2))
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

    // The main note's save timing. Closing or backgrounding bypasses it through
    // flushSave above, so waiting for a pause does not risk data loss. Watched
    // from a coroutine rather than keyed on popupEdits, so a stroke in the
    // panel does not recompose the panel.
    val latestTitle by rememberUpdatedState(title)
    LaunchedEffect(noteId) {
        autosaveLoop(edits = { popupEdits }) {
            val v = view ?: return@autosaveLoop
            val started = System.nanoTime()
            store.saveLater(noteId, latestTitle, v.document)
            v.latency.record(PerformanceMetric.SAVE_SNAPSHOT, System.nanoTime() - started)
        }
    }

    val pageCount = opened?.first?.pages?.size ?: 0
    val panelShape = Ui.Shape.card
    val rimLight = if (isDarkTheme()) 0.35f else 1f
    val panelBorder = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.94f * rimLight),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.76f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.34f),
            Color.White.copy(alpha = 0.78f * rimLight),
        ),
    )

    Box(
        Modifier
            .offset { offset().let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
            .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
            // Carried past the bottom edge, the panel gives a little and fades:
            // let go now and it is put down; bring it back and it is not.
            .graphicsLayer {
                val dismiss = dismissProgress()
                translationY = dismiss * REFERENCE_DISMISS_SAG.toPx()
                alpha = 1f - 0.45f * dismiss
            }
            .drawWithContent {
                drawContent()
                val scale = stretch()
                if (abs(scale - 1f) > 0.001f) {
                    drawRect(
                        color = Color(0xFF3B7DDD),
                        size = Size(this.size.width * scale, this.size.height * scale),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                // A free resize in progress: the size it will be, outlined.
                val outline = draft()
                if (outline != null) {
                    drawRect(
                        color = Color(0xFF3B7DDD),
                        size = outline,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
    ) {
    SkinSurface(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer {
                val scale = stretch()
                scaleX = scale; scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            // 유리 표면이 밝은 페이지와 겹쳐도 팝업 외곽을 잃지 않도록 밝은 림과
            // 테마 윤곽색을 함께 사용합니다.
            .border(1.5.dp, panelBorder, panelShape),
        corner = Ui.Corner.card,
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
                        Icon(Reicons.ArrowDropDown, contentDescription = "Other notes")
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
                            placeholder = { Text("Find a note") },
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
                                text = { Text("No results") },
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
                                    text = { Text("Page ${index + 1}") },
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
                        contentDescription = "Fit to width",
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
                    Icon(Reicons.Close, contentDescription = "Close reference")
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val ready = opened
                if (ready == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (loadFailed) TextButton(onClick = { loadAttempt++ }) { Text("Couldn't open the note · Retry") }
                        else CircularProgressIndicator()
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            InkCanvasView(viewContext).apply {
                                // Let go into PdfHold, so leaving and coming straight back is quick.
                                releasePdf = { PdfHold.release(it) }
                                // 팝업 안에서는 페이지 폭이 패널 폭보다 작아지는
                                // 축소를 허용하지 않습니다. 확대와 필기는 그대로입니다.
                                minimumScaleIsFitWidth = true
                                // A popup always shares the screen with the note
                                // under it; see InkCanvasView.softPredictionHead.
                                softPredictionHead = false
                                // The page moves exactly with the finger; a faster page
                                // felt like it was slipping away under it.
                                viewportPanMultiplier = 1f
                                // And it cannot be slid off sideways past its edges.
                                keepPageAcross = true
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
                                onReferenceDragEnd = { if (latestStretchEnd(this)) closePanel() }
                                onCloseReference = { closePanel() }
                                // Down is a direction like any other: the panel
                                // follows the hand there too, and is put down only
                                // when let go past the bottom edge (see onStretchEnd).
                                closeReferenceOnDownwardDrag = false
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
                                straightLine && mode in STRAIGHT_LINE_MODES -> ShapeKind.LINE
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
    // Free resize: the right edge, the bottom edge and the corner between
    // them, each axis on its own, so the panel takes whatever shape the hand
    // drags it to. Hidden while a three-finger spread is scaling the panel;
    // derived, so a spread recomposes this once as it starts and once as it ends.
    val latestStretch by rememberUpdatedState(stretch)
    val unstretched by remember { derivedStateOf { abs(latestStretch() - 1f) <= 0.001f } }
    if (unstretched) {
        val resize: (Float, Float) -> Unit = { dx, dy -> latestResize(dx, dy) }
        val resizeEnd: () -> Unit = { latestResizeEnd(view) }
        ReferenceResizeGrip(horizontal = true, vertical = false, onDrag = resize, onEnd = resizeEnd,
            modifier = Modifier.align(Alignment.CenterEnd).size(18.dp, 64.dp))
        ReferenceResizeGrip(horizontal = false, vertical = true, onDrag = resize, onEnd = resizeEnd,
            modifier = Modifier.align(Alignment.BottomCenter).size(64.dp, 18.dp))
        ReferenceResizeGrip(horizontal = true, vertical = true, onDrag = resize, onEnd = resizeEnd,
            modifier = Modifier.align(Alignment.BottomEnd).size(30.dp))
    }
    }
}

/**
 * One of the reference panel's resize handles: dragging it moves the panel's
 * right edge ([horizontal]), its bottom edge ([vertical]) or both. A small
 * mark rather than a bar, so it does not take a strip of the page.
 */
@Composable
private fun ReferenceResizeGrip(
    horizontal: Boolean,
    vertical: Boolean,
    onDrag: (Float, Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    Canvas(
        modifier
            .semantics { contentDescription = "Resize" }
            .pointerInput(horizontal, vertical) {
                detectDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { change, amount ->
                    change.consume()
                    onDrag(if (horizontal) amount.x else 0f, if (vertical) amount.y else 0f)
                }
            },
    ) {
        val thick = 3.dp.toPx()
        val round = androidx.compose.ui.graphics.StrokeCap.Round
        when {
            horizontal && vertical -> {
                // Two short diagonals tucked into the corner, as on a window.
                val inset = 7.dp.toPx()
                val right = size.width - inset
                val bottom = size.height - inset
                for (step in 1..2) {
                    val reach = step * 6.dp.toPx()
                    drawLine(color, Offset(right - reach, bottom), Offset(right, bottom - reach), thick, round)
                }
            }
            horizontal -> drawLine(color, Offset(size.width / 2f, size.height * 0.25f),
                Offset(size.width / 2f, size.height * 0.75f), thick, round)
            else -> drawLine(color, Offset(size.width * 0.25f, size.height / 2f),
                Offset(size.width * 0.75f, size.height / 2f), thick, round)
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
            label = { Text("Folder name") },
        ) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
            Text("Save")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            .padding(Ui.Space.l),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (preview != null) Image(preview.asImageBitmap(), "Original selection",
                modifier = Modifier.width(220.dp).heightIn(max = 110.dp), contentScale = ContentScale.Fit)
            else Text("Selection", modifier = Modifier.width(100.dp))
            ToolbarDivider()
            TextButton(onClick = onHighlightColor) {
                Box(Modifier.size(22.dp).background(Color(highlightColor or 0xFF000000.toInt()), CircleShape))
                Text(" Color")
            }
            TextButton(onClick = onHighlight) {
                Icon(Reicons.Brush, contentDescription = null)
                Text(" Highlight")
            }
            TextButton(onClick = onMaskColor) {
                Box(Modifier.size(22.dp).background(Color(maskColor or 0xFF000000.toInt()), CircleShape))
                Text(" Color")
            }
            TextButton(onClick = onMask) {
                Icon(Reicons.VisibilityOff, contentDescription = null)
                Text(" Mask")
            }
            TextButton(onClick = onStrike) { Text("Strikethrough") }
            TextButton(onClick = onCopy) {
                Icon(Reicons.ContentCopy, contentDescription = null)
                Text(" Copy")
            }
            TextButton(onClick = onTranslate) { Text("Translate") }
            TextButton(onClick = onSearch) { Text("Search") }
            TextButton(onClick = onShare) { Text("Share") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
    /** A small picture of a page, shown beside the handle while it is dragged; null while it renders. */
    thumbnail: ((Int) -> android.graphics.Bitmap?)? = null,
) {
    if (pageCount <= 1) return
    val page = restoredPage(currentPage, pageCount)
    val fraction = page.toFloat() / (pageCount - 1).toFloat()
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val liquid = LocalSkin.current.isRefractive
    // Liquid glass draws the horizontal slider stood on end, whose lens
    // centre stops [SPOTI_SLIDER_START] short of each end. The page mapping
    // uses the same reach so the finger and the lens stay on one line.
    val spoti = LocalSkin.current == Skin.SPOTIGLASS
    val thumbHeight = if (spoti) SPOTI_SLIDER_START * 2 else 52.dp
    val thumbHeightPx = with(density) { thumbHeight.toPx() }
    var trackHeight by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    val thumbWidth by animateDpAsState(
        targetValue = if (dragging && liquid) 40.dp else 34.dp,
        animationSpec = tween(120),
        label = "page scrubber width",
    )
    val travel = (trackHeight - thumbHeightPx).coerceAtLeast(0f)
    // Held, the handle follows the finger, not the page it lands on; let go,
    // it eases onto that page's place on the track.
    var dragFraction by remember { mutableFloatStateOf(NOT_DRAGGING) }
    var pressed by remember { mutableStateOf(false) }
    val shown = sliderShownFraction(fraction, dragFraction, animate = LocalSkin.current.animatesChrome)
    val thumbY = (shown * travel).roundToInt()

    fun jumpAt(y: Float) {
        if (dragging && travel > 0f) dragFraction = ((y - thumbHeightPx / 2f) / travel).coerceIn(0f, 1f)
        onJump(scrubbedPage(y, trackHeight.toFloat(), thumbHeightPx, pageCount))
    }

    Box(
        modifier
            .width(52.dp)
            .onSizeChanged { trackHeight = it.height }
            // A screen reader gets the rail as what it is: a page position
            // that can be read and set, rather than an unlabelled strip.
            .semantics {
                contentDescription = "Page ${page + 1} of $pageCount"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    page.toFloat(), 0f..(pageCount - 1).toFloat(), (pageCount - 2).coerceAtLeast(0),
                )
                setProgress { target ->
                    onJump(target.roundToInt().coerceIn(0, pageCount - 1))
                    true
                }
            }
            .pointerInput(pageCount, trackHeight, onJump) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        jumpAt(it.y)
                    },
                    onDragEnd = { dragging = false; dragFraction = NOT_DRAGGING },
                    onDragCancel = { dragging = false; dragFraction = NOT_DRAGGING },
                ) { change, _ ->
                    change.consume()
                    jumpAt(change.position.y)
                }
            }
            .pointerInput(pageCount, trackHeight, onJump) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { jumpAt(it.y) },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (spoti) {
            SpotiGlassPageRail(
                target = if (dragging && dragFraction >= 0f) dragFraction else fraction,
                held = dragging || pressed,
                label = "${page + 1}",
                modifier = Modifier.matchParentSize(),
            )
        } else Box(
            Modifier
                .align(Alignment.Center)
                .width(if (liquid) 6.dp else 4.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(scheme.onSurface.copy(alpha = if (liquid) 0.16f else 0.12f)),
        ) {
            if (shown > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(shown)
                        .clip(CircleShape)
                        .background(scheme.primary.copy(alpha = if (liquid) 0.84f else 1f)),
                )
            }
        }
        val pageLabel: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "${page + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurface,
                )
            }
        }
        val thumbPlace = Modifier
            .align(Alignment.TopCenter)
            .offset { IntOffset(0, thumbY) }
            .size(thumbWidth, thumbHeight)
        // Dragged, the page under the finger is pictured beside the handle,
        // so a long jump can be aimed without waiting for the page to land.
        if (dragging && thumbnail != null) {
            var picture by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(page) {
                // The PDF preview may still be rendering: ask a few times.
                repeat(THUMBNAIL_ATTEMPTS) {
                    val ready = thumbnail(page)
                    if (ready != null) {
                        picture = ready
                        return@LaunchedEffect
                    }
                    delay(THUMBNAIL_RETRY_MS)
                }
            }
            picture?.let { bitmap ->
                val previewWidth = SCRUBBER_PREVIEW_WIDTH
                val previewHeight = previewWidth * bitmap.height / bitmap.width.coerceAtLeast(1)
                val previewHeightPx = with(density) { previewHeight.toPx() }
                val previewY = (thumbY + thumbHeightPx / 2f - previewHeightPx / 2f)
                    .coerceIn(0f, (trackHeight - previewHeightPx).coerceAtLeast(0f)).roundToInt()
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-(52.dp + 8.dp).roundToPx(), previewY) }
                        .requiredSize(previewWidth, previewHeight)
                        .shadow(6.dp, Ui.Shape.preview)
                        .clip(Ui.Shape.preview)
                        .background(Color.White)
                        .border(0.5.dp, scheme.outlineVariant, Ui.Shape.preview),
                ) {
                    Image(bitmap.asImageBitmap(), contentDescription = "Page ${page + 1} preview",
                        modifier = Modifier.fillMaxSize())
                    Text(
                        "${page + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(Ui.Space.xs)
                            .background(Color.Black.copy(alpha = 0.55f), Ui.Shape.badge)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
        // Liquid glass drew its own lens with the rail above: solid at rest,
        // glass only while held, exactly as the horizontal slider does.
        if (!spoti) {
            SkinSurface(modifier = thumbPlace, corner = 100.dp) { pageLabel() }
        }
    }
}

private val SCRUBBER_PREVIEW_WIDTH = 120.dp
/** The page panel's tabs: pages, contents, masks, then the body search. */
private const val PAGE_PANEL_SEARCH_TAB = 3
private const val SCRUBBER_THUMBNAIL_PX = 240
private const val THUMBNAIL_ATTEMPTS = 40
private const val THUMBNAIL_RETRY_MS = 80L

/** Within this many degrees of a quarter turn, a turned note settles onto it. */
private const val ROTATION_SNAP_DEGREES = 4f

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
    /** The in-note body search, kept by the note screen; null leaves the Search tab out. */
    search: NoteSearchState? = null,
    searchView: InkCanvasView? = null,
    onSearchHit: (NoteSearchHit) -> Unit = {},
) {
    val pages = document?.pages ?: return
    // Kept with the search, so a panel that writing put away opens again on
    // the tab it was left on - the search, most of all.
    var tab by remember { mutableIntStateOf(search?.panelTab ?: 0) }
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
            title = { Text("Contents entry for page ${index + 1}") },
            text = {
                OutlinedTextField(
                    value = tocName,
                    onValueChange = { tocName = it },
                    label = { Text("Entry name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetToc(index, tocName)
                    editingPage = null
                }, enabled = tocName.isNotBlank()) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingPage = null }) { Text("Cancel") }
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
                segments = if (search != null) listOf("Pages", "TOC", "Masks", "Search")
                    else listOf("Pages", "TOC", "Masks"),
                selectedIndex = tab,
                onSelected = { tab = it; search?.panelTab = it },
                useLiquidGlass = LocalSkin.current.isRefractive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            )
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            if (tab == PAGE_PANEL_SEARCH_TAB && search != null) NoteSearchPanel(
                state = search,
                view = searchView,
                onOpen = onSearchHit,
                modifier = Modifier.weight(1f),
            ) else LazyColumn(
                Modifier.weight(1f).glassEdges(listState),
                state = listState,
                contentPadding = PaddingValues(Ui.Space.m),
                verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
            ) {
                if (tab == 1 && pages.any { it.tocTitle != null }) {
                    item {
                        OutlinedTextField(
                            value = tocQuery,
                            onValueChange = { tocQuery = it },
                            placeholder = { Text("Search contents") },
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
                                label = { Text("Bookmarks") },
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { choosing = !choosing; chosen.clear() }) {
                                Text(if (choosing) "Done" else "Select")
                            }
                            TextButton(onClick = { goingTo = true }) { Text("Go to") }
                        }
                    }
                }
                // The PDF's own outline: shown or filtered out, followed with a tap, never deleted.
                if (tab == 1 && pdfOutline.isNotEmpty()) {
                    item {
                        FilterChip(selected = showPdfOutline, onClick = { showPdfOutline = !showPdfOutline },
                            label = { Text("PDF contents ${pdfOutline.size}") })
                    }
                    if (showPdfOutline) items(pdfOutline.filter {
                        tocQuery.isBlank() || it.title.contains(tocQuery.trim(), ignoreCase = true)
                    }) { entry ->
                        val target = pages.indexOfFirst { it.background == PageBackground.PDF && it.pdfPageIndex == entry.pdfPage }
                        Text(
                            entry.title.ifBlank { "(Untitled)" } + "  · ${entry.pdfPage + 1}",
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
                    item { Text("My contents", style = MaterialTheme.typography.labelMedium) }
                }
                if (tab == 1 && pages.none { it.tocTitle != null } && pdfOutline.isEmpty()) {
                    item {
                        Text(
                            "No contents yet. Add the current page, or long-press a page to set one.",
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
                            Text("Page ${index + 1}", style = MaterialTheme.typography.titleSmall)
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
                    }) { Text("Delete") }
                    TextButton(enabled = any, onClick = { onDuplicatePages(chosenIndices()); chosen.clear() }) { Text("Duplicate") }
                    TextButton(enabled = any, onClick = { onExportPages(chosenIndices()) }) { Text("PDF") }
                    TextButton(enabled = any, onClick = { onSendPages(chosenIndices(), false) }) { Text("Copy to…") }
                    TextButton(enabled = any && chosen.size < pages.size, onClick = {
                        onSendPages(chosenIndices(), true); chosen.clear()
                    }) { Text("Move to…") }
                }
            } else if (tab == 0) {
                TextButton(
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                ) {
                    Icon(Reicons.Add, contentDescription = null)
                    Text(" Page")
                }
            } else if (tab == 1) {
                TextButton(
                    onClick = { pages.getOrNull(currentPage)?.let(::editToc) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Icon(Reicons.Add, contentDescription = null)
                    Text(" Add current page")
                }
            } else if (tab == 2) {
                // Whole-note switches, which is how a page of covered answers
                // actually gets used: cover everything, then go looking.
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    TextButton(
                        onClick = { pages.indices.forEach { onReveal(it, false) } },
                        modifier = Modifier.weight(1f),
                    ) { Text("Cover all") }
                    TextButton(
                        onClick = { pages.indices.forEach { onReveal(it, true) } },
                        modifier = Modifier.weight(1f),
                    ) { Text("Reveal all") }
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
    // A white wash on a dark panel greys it and pales the light text in it.
    isDarkTheme() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    else -> Color.White.copy(alpha = 0.34f)
}

/** Whether the app is in its dark theme. */
@Composable
internal fun isDarkTheme(): Boolean = LocalSkinSettings.current.themeMode == AppThemeMode.DARK

/**
 * The desk the pages lie on. Paper stays paper in the dark theme, but the
 * ground round it goes dark, so the screen is not a light frame around a note.
 */
@Composable
internal fun deskColor(): Color = if (isDarkTheme()) Color(0xFF1C1D20) else Color(0xFFE9E7E2)

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
        title = { Text("Page size") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    PAGE_SIZES.forEach { (name, size) ->
                        TextButton(onClick = { w = size.first.toString(); h = size.second.toString() }) { Text(name) }
                    }
                    TextButton(onClick = { val t = w; w = h; h = t }) { Text("Swap ↔") }
                }
                Row {
                    OutlinedTextField(w, { w = it.filter(Char::isDigit).take(4) }, label = { Text("Width px") },
                        singleLine = true, isError = pw == null, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(h, { h = it.filter(Char::isDigit).take(4) }, label = { Text("Height px") },
                        singleLine = true, isError = ph == null, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Text("At 300 dpi, 100–9,999 px", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = all, onCheckedChange = { all = it })
                    Text("Apply to all non-PDF pages")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pw != null && ph != null, onClick = {
                onSize(pw!! / PX_300_PER_UNIT, ph!! / PX_300_PER_UNIT, all)
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Page number in, page index out. Shared by the page panel and the Ctrl+Alt+G shortcut. */
@Composable
internal fun GoToPageDialog(pageCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val target = text.toIntOrNull()?.takeIf { it in 1..pageCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
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
                Text("Go")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            .clip(Ui.Shape.chip)
            .background(if (highlighted) colors.tertiaryContainer else chipFill(selected))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) colors.primary else colors.outlineVariant,
                Ui.Shape.chip,
            )
            .clickable(onClick = onJump)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(page.tocTitle.orEmpty(), style = MaterialTheme.typography.titleSmall,
                color = if (highlighted) colors.onTertiaryContainer else colors.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("p. ${index + 1}", style = MaterialTheme.typography.labelSmall,
                color = if (highlighted) colors.onTertiaryContainer else colors.outline)
        }
        IconButton(onClick = onHighlight, modifier = Modifier.size(32.dp)) {
            Icon(Reicons.Highlight,
                contentDescription = if (highlighted) "Remove highlight" else "Highlight entry",
                tint = if (highlighted) colors.onTertiaryContainer else colors.outline,
                modifier = Modifier.size(18.dp))
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Reicons.MoreVert, contentDescription = "Entry options",
                    modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = {
                    menu = false; onEdit()
                })
                DropdownMenuItem(text = { Text("Indent") }, onClick = {
                    menu = false; onIndent()
                })
                DropdownMenuItem(text = { Text("Outdent") }, enabled = page.tocLevel > 0, onClick = {
                    menu = false; onOutdent()
                })
                DropdownMenuItem(text = { Text("Remove entry") }, onClick = {
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
            .clip(Ui.Shape.chip)
            .background(chipFill(selected))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = Ui.Shape.chip,
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
                    if (masks.isEmpty()) "None" else "$hidden / ${masks.size} covered",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (masks.isNotEmpty() && study != null) {
                    val right = records.count { it.last == MaskRecord.RESULT_RIGHT }
                    val wrong = records.count { it.last == MaskRecord.RESULT_WRONG }
                    Text(
                        "Right $right · Wrong $wrong",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (masks.isNotEmpty()) {
                IconButton(onClick = { onReveal(hidden > 0) }) {
                    Icon(
                        if (hidden > 0) Reicons.Visibility else Reicons.VisibilityOff,
                        contentDescription = if (hidden > 0) "Reveal this page" else "Cover this page",
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(Reicons.Delete, contentDescription = "Delete this page's masks")
                }
            }
        }
        if (masks.isNotEmpty() && study != null) Row(Modifier.padding(start = 16.dp)) {
            TextButton(onClick = { onStudy(false) }) { Text("Study") }
            TextButton(
                onClick = { onStudy(true) },
                enabled = records.any { it.last == MaskRecord.RESULT_WRONG },
            ) { Text("Wrong only") }
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
                    } + record.name.ifBlank { "Mask ${maskIndex + 1}" },
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
                        contentDescription = if (mask.revealed) "Cover this mask" else "Reveal this mask",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { onDeleteMask(maskIndex) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Reicons.Delete,
                        contentDescription = "Delete this mask",
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
                .clip(Ui.Shape.chip)
                .background(chipFill(selected))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = Ui.Shape.chip,
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
                    PageBackground.BLANK -> "Blank"
                    PageBackground.LINED -> "Lined"
                    PageBackground.GRID -> "Grid"
                    PageBackground.PDF -> "PDF"
                    PageBackground.DOT -> "Dot"
                    PageBackground.NARROW_LINED -> "Narrow lined"
                    PageBackground.CORNELL -> "Cornell"
                    PageBackground.CUSTOM -> "Custom"
                    PageBackground.INFINITE -> "Infinite"
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
                contentDescription = if (page.bookmarked) "Remove bookmark" else "Bookmark",
                tint = if (page.bookmarked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (page.tocTitle == null) "Set contents entry" else "Rename contents entry") },
                onClick = { menu = false; onEditToc() },
            )
            DropdownMenuItem(
                text = { Text("Move earlier") }, enabled = index > 0,
                onClick = { onMove(-1); menu = false },
            )
            DropdownMenuItem(
                text = { Text("Move later") },
                onClick = { onMove(1); menu = false },
            )
            DropdownMenuItem(
                text = { Text("Duplicate page") },
                onClick = { onDuplicate(); menu = false },
            )
            if (page.background != PageBackground.PDF && page.pdfPageIndex < 0) {
                DropdownMenuItem(
                    text = { Text("Rotate left") },
                    onClick = { onRotate(false); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Rotate right") },
                    onClick = { onRotate(true); menu = false },
                )
            }
            if (page.background != PageBackground.PDF) {
                DropdownMenuItem(
                    text = { Text("Page size…") },
                    onClick = { onPageSize(); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Use this background on all pages") },
                    onClick = { onBackgroundAll(); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Blank") },
                    onClick = { onBackground(PageBackground.BLANK); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Lined") },
                    onClick = { onBackground(PageBackground.LINED); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Grid") },
                    onClick = { onBackground(PageBackground.GRID); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Dot") },
                    onClick = { onBackground(PageBackground.DOT); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Narrow lined") },
                    onClick = { onBackground(PageBackground.NARROW_LINED); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Cornell") },
                    onClick = { onBackground(PageBackground.CORNELL); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Infinite note") },
                    onClick = { onBackground(PageBackground.INFINITE); menu = false },
                )
                userTemplates.forEach { template ->
                    DropdownMenuItem(
                        text = { Text("Template · ${template.name}") },
                        onClick = { onTemplate(template.id); menu = false },
                    )
                }
            }
            DropdownMenuItem(
                text = { Text("Delete page") },
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
    canUndo: Boolean,
    canRedo: Boolean,
    pageLabel: String,
    onEditPen: () -> Unit,
    onMode: (EditMode) -> Unit,
    onShape: (ShapeKind) -> Unit,
    onShapeOptions: () -> Unit = {},
    onPickImage: () -> Unit,
    onText: () -> Unit,
    onAddText: () -> Unit,
    onWeb: (String) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    /** Opens the zoom presets. */
    onZoomLabel: () -> Unit,
    /** The zoom as a percentage of fit-to-width. */
    zoomLabel: String,
    recording: Boolean,
    onVoice: () -> Unit,
    noteRotation: Int,
    onRotate: () -> Unit,
    onTogglePages: () -> Unit,
    sizeLevel: Int,
    onSizeLevel: (Int) -> Unit,
    onOpenNote: (NoteMeta) -> Unit,
    onBack: () -> Unit,
    onPalette: () -> Unit,
    /** Whether the bar has been dragged away from where it starts. */
    canResetBar: Boolean,
    onResetBar: () -> Unit,
    /** Opens the settings sidebar at the given screen. */
    onSettings: (SettingsPage) -> Unit,
    onDragBar: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    onAddPage: () -> Unit = {},
    onClock: () -> Unit = {},
    onQuickColor: (Int) -> Unit = {},
    onCamera: () -> Unit = {},
    onLayers: () -> Unit = {},
    /** The layer new ink goes to, shown beside the pen options (and in the top row if placed there). */
    layerChip: @Composable () -> Unit = {},
    /** Drawn as a picture of the bar in a settings preview: it reports no position for the pen panel. */
    preview: Boolean = false,
) {
    val config = currentToolbarConfig()
    val undoReason = if (canUndo) null else "Nothing to undo"
    val redoReason = if (canRedo) null else "Nothing to redo"
    val topRow: @Composable () -> Unit = {
        // ---- top row: the note, and what is done to the whole of it, in
        // the order the toolbar editor left it.
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
            for (item in config.topRow) {
                when (item) {
                    TopRowItem.BACK -> AppIconButton(onClick = onBack, icon = AppIcons.back, contentDescription = "Back to notes")
                    TopRowItem.PAGES -> AppIconButton(onClick = onTogglePages, icon = AppIcons.search, contentDescription = "Pages & search")
                    TopRowItem.TITLE -> Text(
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
                    TopRowItem.UNDO -> AppIconButton(onClick = onUndo, icon = AppIcons.undo, contentDescription = "Undo",
                        enabled = canUndo, modifier = Modifier.disabledReason(undoReason))
                    TopRowItem.REDO -> AppIconButton(onClick = onRedo, icon = AppIcons.redo, contentDescription = "Redo",
                        enabled = canRedo, modifier = Modifier.disabledReason(redoReason))
                    TopRowItem.MIC -> AppIconButton(
                        onClick = onVoice,
                        icon = AppIcons.mic,
                        contentDescription = if (recording) "Recording · open recordings" else "Open recordings",
                        tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    TopRowItem.ROTATE -> AppIconButton(onClick = onRotate, icon = AppIcons.rotate,
                        contentDescription = "Rotate note, now $noteRotation°")
                    // 확대 아이콘과 수치는 분리해 둘을 감싸는 강조 칸은 만들지 않고,
                    // 숫자는 행의 정중앙에 놓습니다.
                    // The reading is the button: tapping it offers the zoom steps.
                    TopRowItem.ZOOM -> Text(
                        zoomLabel,
                        // Tabular figures: 97% and 100% take the same width, so the
                        // buttons beside the reading do not shuffle as the zoom changes.
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(Ui.Shape.chip)
                            .clickable(onClickLabel = "Zoom presets", onClick = onZoomLabel)
                            .widthIn(min = 52.dp)
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                    )
                    TopRowItem.SKIN -> SkinButton(skin, onSkin)
                    // Only once there is somewhere to come back from. A bar that
                    // has never been moved does not need a button for moving it
                    // back, and a bar dragged to a corner and left there had no way
                    // back at all short of docking it and undocking it again.
                    TopRowItem.RESET_BAR -> if (canResetBar) {
                        AppIconButton(onClick = onResetBar, icon = AppIcons.resetBar, contentDescription = "Reset bar position")
                    }
                    // The clock, the calculator, the to-do list and the calendar.
                    TopRowItem.PLANNER -> ToolsMenuButton()
                    TopRowItem.GESTURES -> AppIconButton(onClick = { onSettings(SettingsPage.TOUCH) }, icon = AppIcons.touch,
                        contentDescription = "Touch & gestures")
                    TopRowItem.ADD_PAGE -> AppIconButton(onClick = onAddPage, icon = AppIcons.addPage, contentDescription = "Add page")
                    TopRowItem.NOTE_MENU -> AppIconButton(onClick = { onSettings(SettingsPage.NOTE_VIEW) }, icon = AppIcons.noteMenu,
                        contentDescription = "Note & view")
                    TopRowItem.OTHER_NOTES -> OtherNotesButton(otherNotes, onOpenNote)
                    TopRowItem.LAYER -> layerChip()
                    TopRowItem.SETTINGS -> AppIconButton(onClick = { onSettings(SettingsPage.ROOT) }, icon = AppIcons.settings,
                        contentDescription = "Settings")
                }
            }
        }
    }
    SpotiGlassToolbar(sizeLevel, onSizeLevel, mode,
            toolColors = toolPens.filterKeys { it.tints }.mapValues { Color(it.value.colorArgb.or(0xFF000000.toInt())) },
            targetWidth = toolbarTargetWidth,
            docked = docked,
            // Where the pen panel grows from: the middle of the bar's lower edge.
            modifier = if (preview) modifier else modifier.onGloballyPositioned {
                val at = it.positionOnScreen()
                PenPopupAnchor.screen = Offset(at.x + it.size.width / 2f, at.y + it.size.height)
            },
            onDrag = onDragBar,
            onTool = { tool -> when (tool.mode) {
                // The shape already in hand, tapped again, opens the picker,
                // the way a pen tapped again opens its settings.
                EditMode.SHAPE -> if (mode == EditMode.SHAPE) onShapeOptions() else onShape(shapeKind)
                EditMode.TEXT -> onText()
                null -> Unit
                else -> onMode(tool.mode)
            } },
            // Exactly these, in these groups; everything else is in Settings.
            functions = listOf(
                listOf(
                    SpotiToolbarAction("Undo", AppIcons.undo, enabled = canUndo, disabledReason = undoReason, onClick = onUndo),
                    SpotiToolbarAction("Redo", AppIcons.redo, enabled = canRedo, disabledReason = redoReason, onClick = onRedo),
                ),
                listOf(
                    SpotiToolbarAction("Picture", AppIcons.picture, onClick = onPickImage),
                    SpotiToolbarAction("Camera", AppIcons.camera, onClick = onCamera),
                    SpotiToolbarAction("Layers", AppIcons.layers, onClick = onLayers),
                    SpotiToolbarAction("Microphone", AppIcons.mic, selected = recording, onClick = onVoice),
                    SpotiToolbarAction("Capture", AppIcons.capture, selected = mode == EditMode.CAPTURE,
                        onClick = { onMode(EditMode.CAPTURE) }),
                    SpotiToolbarAction("Text", AppIcons.text, selected = mode == EditMode.TEXT, onClick = onText),
                ),
                listOf(
                    SpotiToolbarAction("Clock", AppIcons.clock, onClick = onClock),
                    SpotiToolbarAction("Calendar", AppIcons.calendar, onClick = { Planner.show(ToolPanel.CALENDAR) }),
                    SpotiToolbarAction("To-do", AppIcons.todo, onClick = { Planner.show(ToolPanel.TODO) }),
                    SpotiToolbarAction("Calculator", AppIcons.tools, onClick = { Planner.show(ToolPanel.CALCULATOR) }),
                ),
                listOf(
                    SpotiToolbarAction("Notes", AppIcons.otherNotes, opensNotes = true, onClick = {}),
                    SpotiToolbarAction("Add page", AppIcons.addPage, onClick = onAddPage),
                ),
                listOf(
                    SpotiToolbarAction("Settings", AppIcons.settings, onClick = { onSettings(SettingsPage.ROOT) }),
                ),
            ),
            notes = otherNotes.map { other -> SpotiToolbarAction(other.title, AppIcons.note) { onOpenNote(other) } },
            ai = AI_SITES.map { (name, url) -> SpotiToolbarAction(name, AppIcons.ai) { onWeb(url) } },
            topRow = topRow,
            penOptions = { _ ->
              // What the editor put in this row, in its order. The slider takes
              // the slack; without it a spacer does, so the page number keeps
              // to the end either way.
              val stretches = PenRowItem.WIDTH in config.penRow && mode in PenStore.DEFAULTS
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (item in config.penRow) {
                    when (item) {
                        PenRowItem.TOOL -> {
                            if (mode == EditMode.SHAPE) ShapeButton(true, shapeKind, onShapeOptions)
                            if (mode == EditMode.TEXT) TextButton(onClick = onAddText) { Text("+ Text") }
                            if (mode in PenStore.DEFAULTS) PenChip(pen, true, onEditPen)
                        }
                        PenRowItem.QUICK_COLORS -> if (mode.tints) QuickColors(pen.colorArgb, onQuickColor, onEditPen)
                        PenRowItem.PALETTE -> if (mode.tints) {
                            var paletteTaps by remember { mutableIntStateOf(0) }
                            IconButton(onClick = { paletteTaps++; onPalette() }, modifier = Modifier.size(32.dp)) {
                                AppIcon(AppIcons.palette, "Color templates", Modifier.size(20.dp), play = paletteTaps)
                            }
                        }
                        PenRowItem.STRAIGHT_LINE -> if (mode in STRAIGHT_LINE_MODES) {
                            ToolButton(Reicons.Remove, "Straight line", straightLine, onClick = onToggleStraightLine)
                        }
                        PenRowItem.LAYER -> layerChip()
                        PenRowItem.WIDTH -> if (mode in PenStore.DEFAULTS) {
                            val range = PenStore.widthRange(mode, pen)
                            FavoriteWidthButton(mode, pen.width, onWidth)
                            SkinSlider(pen.width.coerceIn(range), onWidth, range, Modifier.weight(1f).widthIn(min = 64.dp), step = WIDTH_SLIDER_STEP)
                        }
                        PenRowItem.PAGE_LABEL -> {
                            if (!stretches) Spacer(Modifier.weight(1f))
                            TextButton(onClick = onTogglePages) { Text(pageLabel) }
                        }
                    }
                }
                if (!stretches && PenRowItem.PAGE_LABEL !in config.penRow) Spacer(Modifier.weight(1f))
              }
            })
}

internal fun rulerLabel(kind: RulerKind): String = when (kind) {
    RulerKind.RULER -> "Ruler"
    RulerKind.TRIANGLE -> "Set square"
    RulerKind.PROTRACTOR -> "Protractor"
}


/** Jumps straight to another note without going back through the list. */
@Composable
private fun OtherNotesButton(notes: List<NoteMeta>, onOpenNote: (NoteMeta) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppIconButton(onClick = { open = true }, icon = AppIcons.otherNotes, contentDescription = "Other notes")
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (notes.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No other notes") },
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
            Icon(Reicons.Menu, contentDescription = "Show tools")
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
            modifier = Modifier.size(Ui.Size.button),
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
            .padding(horizontal = Ui.Space.s)
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * Saves a note's pending edits for as long as the screen is up: in a pause
 * after them, at the latest [UiScheduling.AUTOSAVE_MAX_DELAY_MS] after the
 * first unsaved one, and not in the middle of a stroke while that can be
 * helped - see [UiScheduling.autosaveWaitMs]. [edits] is a change counter read
 * as snapshot state; [save] takes the snapshot on the UI thread. Closing and
 * backgrounding still flush on their own, without waiting for any of this.
 */
private suspend fun autosaveLoop(edits: () -> Int, save: () -> Unit) {
    var seen = edits()
    var pending = false
    var firstAt = 0L
    var lastAt = 0L
    // The interaction state is watched as well, so a save held back by a
    // stroke is taken in the gap the moment that stroke lifts.
    snapshotFlow { edits() to InteractionGovernor.observed }.collectLatest { (count, _) ->
        val now = android.os.SystemClock.uptimeMillis()
        if (count != seen) {
            seen = count
            lastAt = now
            if (!pending) {
                pending = true
                firstAt = now
            }
        }
        if (!pending) return@collectLatest
        while (true) {
            val at = android.os.SystemClock.uptimeMillis()
            val wait = UiScheduling.autosaveWaitMs(
                at - firstAt, at - lastAt, InteractionGovernor.state, InteractionGovernor.quietForMs())
            if (wait <= 0L) break
            delay(wait)
        }
        // Nothing suspends from here on, so a newer emission cannot cut in
        // between clearing the flag and taking the snapshot.
        pending = false
        save()
    }
}

/** How often a held-back handwriting index looks again at the screen. */
private const val INK_INDEX_RECHECK_MS = 250L
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
/** How far past the bottom edge a reference panel has to be carried for letting go to close it. */
private val REFERENCE_DISMISS_DRAG = 96.dp
/** How far the panel sags towards the edge as that distance is reached. */
private val REFERENCE_DISMISS_SAG = 28.dp


private const val SEARCH_HOME = "https://www.google.com/"

private val AI_SITES = listOf(
    "Gemini" to "https://gemini.google.com/",
    "Claude" to "https://claude.ai/",
    "ChatGPT" to "https://chatgpt.com/",
    "Grok" to "https://grok.com/",
    "Perplexity" to "https://www.perplexity.ai/",
    "Cerebras" to "https://inference.cerebras.ai/",
)

internal val COLOR_TEMPLATES = listOf(
    "Basic" to listOf(0xFF000000, 0xFFD32F2F, 0xFF1976D2, 0xFF388E3C, 0xFFF9A825),
    "Pastel" to listOf(0xFF6D6875, 0xFFE5989B, 0xFF9AC1D9, 0xFFA8D5BA, 0xFFF6D186),
    "Neon" to listOf(0x66FFEB3B, 0x6676FF03, 0x6600E5FF, 0x66FF4081, 0x66FF9100),
    "Ink" to listOf(0xFF000000, 0xFF3A3A3A, 0xFF6B6B6B, 0xFF9E9E9E, 0xFFCFCFCF),
).map { (name, colors) -> name to colors.map { it.toInt() } }
