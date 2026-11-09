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
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
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
import androidx.compose.runtime.produceState
import androidx.compose.material3.Checkbox
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
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
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

// The page panel along the right edge: pages, contents, tape and other notes.
/**
 * The panel along the right edge for finding your way: the pages as pictures,
 * the contents beside the pages they open, every strip of tape where it lies,
 * and the other notes - with one search field over all four. Its inner edge
 * drags its width; pulled well past its narrowest and let go, it closes.
 */
@Composable
internal fun PageSidebar(
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
    /** The in-note body search, kept by the note screen; its query is the panel's search field. */
    search: NoteSearchState? = null,
    searchView: InkCanvasView? = null,
    onSearchHit: (NoteSearchHit) -> Unit = {},
    /** Page n's picture [widthPx] wide, or a call back once it has been drawn. */
    pageThumbnail: (index: Int, widthPx: Int, onReady: () -> Unit) -> InkCanvasView.ThumbnailRequest? = { _, _, _ -> null },
    /** The last picture drawn of page n, at any size, to show while a new one is drawn. */
    stalePageThumbnail: (Int) -> Bitmap? = { null },
    widthDp: Float = PAGE_SIDEBAR_DEFAULT_DP,
    onWidthChange: (Float) -> Unit = {},
    onClose: () -> Unit = {},
    /** How many page pictures go on a row of the grid. */
    columns: Int = 2,
    onColumns: (Int) -> Unit = {},
    /** Every note but those in the trash, this one among them. */
    notes: List<NoteMeta> = emptyList(),
    currentNoteId: String = "",
    onOpenNote: (NoteMeta) -> Unit = {},
    onOpenBeside: (NoteMeta) -> Unit = {},
    /** A note held and dragged out of the list: where it is in the window, null when let go. */
    onDragNote: (androidx.compose.ui.geometry.Offset?) -> Unit = {},
    onDropNote: (NoteMeta, androidx.compose.ui.geometry.Offset) -> Unit = { _, _ -> },
    onNewNote: () -> Unit = {},
    onAttach: (Int) -> Unit = {},
    onAttachmentsHidden: (Int, Boolean) -> Unit = { _, _ -> },
    /** Split, the parts whose pages the panel can show, by part and note name; [source] is the one shown. */
    sources: List<Pair<Int, String>> = emptyList(),
    source: Int = 0,
    onSource: (Int) -> Unit = {},
) {
    val doc = document ?: return
    val pages = doc.pages
    // A visible card used to scan the whole note to find its number. Keep
    // identity across filtering and rebuild only when page order changes.
    val pageIds = pages.map { it.id }
    val pageIndices = remember(pageIds) { pageIds.withIndex().associate { it.value to it.index } }
    // Kept with the search, so a panel that writing put away opens again on
    // the tab it was left on.
    var tab by remember { mutableIntStateOf((search?.panelTab ?: 0).coerceIn(0, PAGE_PANEL_TABS.lastIndex)) }
    var editingPage by remember { mutableStateOf<Page?>(null) }
    var bookmarksOnly by remember { mutableStateOf(false) }
    var ownQuery by remember { mutableStateOf("") }
    // One field over every tab: on the pages it searches what is written in
    // the note, elsewhere it narrows the list in front of it.
    val query = search?.query ?: ownQuery
    fun setQuery(text: String) { if (search != null) search.query = text else ownQuery = text }
    val needle = query.trim()
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
    // The page the pen rests over in the grid, by id, and its card's place: shown large (see PagePeek).
    var peek by remember { mutableStateOf<Pair<String, androidx.compose.ui.geometry.Rect>?>(null) }
    var peekShown by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }
    fun chosenIndices() = pages.indices.filter { pages[it].id in chosen }
    val sweep = remember { PageGridSweep() }
    val scope = rememberCoroutineScope()
    var choosingRange by remember { mutableStateOf(false) }
    // The page whose menu the selection bar's More opened.
    var menuFor by remember { mutableStateOf<String?>(null) }
    fun choose(ids: List<String>) {
        chosen.clear()
        chosen.addAll(ids)
    }
    if (choosingRange) PageRangeDialog(pages.size, onDismiss = { choosingRange = false }) { from, to ->
        choosingRange = false
        choosing = true
        val range = pageRange(from, to, pages.size)
        choose((chosen.toSet() + range.map { pages[it].id }).let { set -> pages.map { it.id }.filter { it in set } })
    }
    var goingTo by remember { mutableStateOf(false) }
    if (goingTo) GoToPageDialog(pages.size, onGo = { onJump(it) }, onDismiss = { goingTo = false })
    var tocName by remember { mutableStateOf("") }
    fun editToc(page: Page) {
        editingPage = page
        tocName = page.tocTitle.orEmpty()
    }
    editingPage?.let { page ->
        val index = pageIndices[page.id] ?: -1
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
    // The pictures are drawn as wide as a card is on this screen, in steps so a
    // small change of the panel's width does not draw them all again: drawn at
    // a fixed 320 px, they were stretched on any card wider than that and blurred.
    val density = LocalDensity.current.density
    val thumbWidthPx = pageThumbnailWidthPx(pageSidebarWidth(widthDp, LocalConfiguration.current.screenWidthDp.toFloat()),
        columns.coerceIn(PAGE_GRID_COLUMNS), density)
    /** The picture of page [index] as it is now: written on, it is pictured afresh. */
    @Composable
    fun picture(index: Int): Bitmap? {
        val page = pages.getOrNull(index) ?: return null
        return rememberPageThumbnail("${page.id}:${page.strokes.size}:${page.loaded}:$thumbWidthPx",
            stale = { stalePageThumbnail(index) }) { onReady -> pageThumbnail(index, thumbWidthPx, onReady) }
    }
    BoxWithConstraints(Modifier.fillMaxHeight().windowInsetsPadding(ChromeInsets)) {
        val screen = maxWidth.value
        var draft by remember { mutableStateOf<Float?>(null) }
        var reach by remember { mutableStateOf<Float?>(null) }
        var past by remember { mutableFloatStateOf(0f) }
        val shown = pageSidebarWidth(draft ?: widthDp, screen)
        val latestShown by rememberUpdatedState(shown)
        val latestScreen by rememberUpdatedState(screen)
        val latestCommit by rememberUpdatedState(onWidthChange)
        val latestClose by rememberUpdatedState(onClose)
        Row(
            Modifier
                .fillMaxHeight()
                .padding(vertical = 12.dp)
                .padding(end = 12.dp)
                // Pulled past its narrowest it follows the finger out, to be let go of.
                .graphicsLayer { translationX = past.dp.toPx() },
        ) {
            // The inner edge: drag it for a wider or narrower panel.
            Box(
                Modifier
                    .width(20.dp)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        fun letGo() {
                            // Let go well past its narrowest, it slides out from where the
                            // finger left it, still that narrow: put back to its own width
                            // first, it grew for a moment on the way out.
                            if (pulledToClose(past)) { latestClose(); return }
                            draft?.let { latestCommit(it) }
                            past = 0f
                            draft = null
                            reach = null
                        }
                        detectHorizontalDragGestures(
                            onDragStart = { draft = latestShown; reach = latestShown },
                            onDragEnd = { letGo() },
                            onDragCancel = { letGo() },
                        ) { change, dragAmount ->
                            change.consume()
                            val wanted = (reach ?: latestShown) - dragAmount / density
                            reach = wanted
                            draft = pageSidebarWidth(wanted, latestScreen)
                            past = (PAGE_SIDEBAR_MIN_DP - wanted).coerceAtLeast(0f)
                        }
                    }
                    .semantics {
                        contentDescription = "Resize the page panel"
                        customActions = listOf(
                            CustomAccessibilityAction("Make wider") {
                                latestCommit(pageSidebarWidth(latestShown + 60f, latestScreen)); true
                            },
                            CustomAccessibilityAction("Make narrower") {
                                latestCommit(pageSidebarWidth(latestShown - 60f, latestScreen)); true
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 5.dp, height = 56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = if (draft != null) 0.95f else 0.6f)),
                )
            }
            SkinSurface(modifier = Modifier.width(shown.dp).fillMaxHeight(), role = ChromeRole.SIDEBAR) {
                Column {
                    // Split, which note's pages, contents and tape the panel is about.
                    if (sources.size > 1) {
                        Text("Show the pages of", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                            sources.forEach { (part, title) ->
                                SettingsChoiceChip(selected = part == source, onClick = { onSource(part) },
                                    label = title.ifBlank { "Untitled" }, modifier = Modifier.padding(end = 6.dp))
                            }
                        }
                    }
                    LiquidSegmentedControl(
                        segments = PAGE_PANEL_TABS,
                        selectedIndex = tab,
                        onSelected = { tab = it; search?.panelTab = it },
                        useLiquidGlass = LocalSkin.current.isRefractive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                    )
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(Unit) {
                        // Ctrl+F opens the panel to type in straight away.
                        if (search?.wantsFocus == true) {
                            search.wantsFocus = false
                            runCatching { focus.requestFocus() }
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = ::setQuery,
                        placeholder = {
                            Text(when (tab) {
                                PAGE_PANEL_PAGES -> "Search this note"
                                PAGE_PANEL_TOC -> "Search contents"
                                PAGE_PANEL_MASKS -> "Search masks"
                                else -> "Search notes"
                            })
                        },
                        singleLine = true,
                        leadingIcon = { Icon(Reicons.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            { IconButton(onClick = { setQuery("") }) { Icon(Reicons.Close, contentDescription = "Clear search") } }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Ui.Space.m)
                            .focusRequester(focus),
                    )
                    when (tab) {
                        PAGE_PANEL_PAGES -> if (needle.isNotEmpty() && search != null) {
                            // A query on the pages is a search of what is written in them.
                            NoteSearchPanel(
                                state = search,
                                view = searchView,
                                onOpen = onSearchHit,
                                showField = false,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = Ui.Space.s),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilterChip(
                                    selected = bookmarksOnly,
                                    onClick = { bookmarksOnly = !bookmarksOnly },
                                    label = { Text("Bookmarks") },
                                )
                                TextButton(onClick = { choosing = !choosing; chosen.clear(); sweep.anchor = null }) {
                                    Text(if (choosing) "Done" else "Select")
                                }
                                TextButton(onClick = { choosingRange = true }) { Text("Range") }
                                TextButton(onClick = { goingTo = true }) { Text("Go to") }
                                ColumnsStepper(columns, onColumns)
                            }
                            // A practice page put away with its host's attachments is left out, as it is on the canvas.
                            val laidOut = pages.filterIndexed { i, _ -> doc.isShown(i) }
                            val shownPages = if (bookmarksOnly) laidOut.filter { it.bookmarked } else laidOut
                            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                            // Opened on the page in hand rather than at the first.
                            LaunchedEffect(Unit) {
                                val at = shownPages.indexOfFirst { pageIndices[it.id] == currentPage }
                                if (at > 0) gridState.scrollToItem(at)
                            }
                            val haptics = LocalHapticFeedback.current
                            val order by rememberUpdatedState(shownPages.map { it.id })
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns.coerceIn(PAGE_GRID_COLUMNS)),
                                state = gridState,
                                modifier = Modifier.weight(1f).pageGridSelection(
                                    sweep, gridState, haptics,
                                    order = { order },
                                    choosing = { choosing },
                                    chosen = { chosen.toList() },
                                    onChoose = { ids -> choosing = true; choose(ids) },
                                ),
                                contentPadding = PaddingValues(Ui.Space.m),
                                horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
                                verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
                            ) {
                                items(shownPages, key = { it.id }) { page ->
                                    val index = pageIndices[page.id] ?: return@items
                                    DisposableEffect(page.id) { onDispose { sweep.bounds.remove(page.id) } }
                                    Box(Modifier.onGloballyPositioned { sweep.bounds[page.id] = it.boundsInRoot() }
                                        // The pen resting over a card shows its page large beside the panel.
                                        .hoverHold(PAGE_PEEK_HOLD_MS,
                                            onHold = { sweep.bounds[page.id]?.let { peek = page.id to it; peekShown = true } },
                                            onLeave = { if (peek?.first == page.id) peekShown = false })) {
                                    PageCard(
                                        index = index,
                                        page = page,
                                        picture = picture(index),
                                        selected = index == currentPage,
                                        choosing = choosing,
                                        chosen = page.id in chosen,
                                        deletable = pages.size > 1,
                                        onJump = {
                                            if (choosing) {
                                                if (page.id in chosen) chosen.remove(page.id) else chosen.add(page.id)
                                                sweep.anchor = page.id
                                            } else onJump(index)
                                        },
                                        menuOpen = menuFor == page.id,
                                        onMenuOpenChange = { menuFor = if (it) page.id else null },
                                        onHold = {
                                            // TalkBack's hold: start choosing here, or reach back to the page tapped last.
                                            val ids = shownPages.map { it.id }
                                            val at = ids.indexOf(page.id)
                                            val from = sweep.anchor?.takeIf { choosing }?.let(ids::indexOf)?.takeIf { it >= 0 } ?: at
                                            choosing = true
                                            choose(sweptSelection(ids, chosen.toSet(), from, at, adding = true))
                                            sweep.anchor = page.id
                                        },
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
                                        practice = page.besideOf != null,
                                        hasAttachments = doc.hasAttachments(index),
                                        attachmentsHidden = doc.hostOf(page).attachmentsHidden,
                                        onAttach = { onAttach(index) },
                                        onAttachmentsHidden = { onAttachmentsHidden(index, it) },
                                    )
                                    }
                                }
                            }
                            val peeked = peek
                            if (peeked != null) {
                                val (pageId, card) = peeked
                                val index = pageIndices[pageId] ?: -1
                                val page = pages.getOrNull(index)
                                if (page == null) {
                                    peek = null
                                } else {
                                    val config = LocalConfiguration.current
                                    val aspect = page.width / page.height
                                    val peekPx = pagePeekPixels(pagePeekWidth(config.screenWidthDp.toFloat(),
                                        config.screenHeightDp.toFloat(), aspect, card.left / density), density)
                                    val large = rememberPageThumbnail("${page.id}:${page.strokes.size}:${page.loaded}:$peekPx",
                                        stale = { null }) { onReady -> pageThumbnail(index, peekPx, onReady) }
                                    PagePeek(card, index + 1, aspect, small = picture(index), large = large, shown = peekShown,
                                        onGone = { if (!peekShown) peek = null })
                                }
                            }
                            if (choosing) {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    val any = chosen.isNotEmpty()
                                    Text("${chosen.size} selected", style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = Ui.Space.s))
                                    val allIds = shownPages.map { it.id }
                                    TextButton(onClick = {
                                        if (chosen.containsAll(allIds)) chosen.clear() else choose(allIds)
                                    }) { Text(if (chosen.containsAll(allIds) && allIds.isNotEmpty()) "None" else "All") }
                                    if (chosen.size == 1) TextButton(onClick = {
                                        val id = chosen.first()
                                        val at = shownPages.indexOfFirst { it.id == id }
                                        scope.launch {
                                            if (at >= 0) gridState.animateScrollToItem(at)
                                            menuFor = id
                                        }
                                    }) { Text("More") }
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
                            } else {
                                TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                    Icon(Reicons.Add, contentDescription = null)
                                    Text(" Page")
                                }
                            }
                        }
                        PAGE_PANEL_TOC -> {
                            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                            val tocFolded = remember { androidx.compose.runtime.mutableStateListOf<String>() }
                            var tocPictures by remember { mutableStateOf(false) }
                            LazyColumn(
                                Modifier.weight(1f).glassEdges(listState),
                                state = listState,
                                contentPadding = PaddingValues(Ui.Space.m),
                                verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
                            ) {
                                // The PDF's own outline: followed with a tap, never deleted.
                                if (pdfOutline.isNotEmpty()) {
                                    item {
                                        FilterChip(selected = showPdfOutline, onClick = { showPdfOutline = !showPdfOutline },
                                            label = { Text("PDF contents ${pdfOutline.size}") })
                                    }
                                    if (showPdfOutline) items(pdfOutline.filter {
                                        needle.isEmpty() || it.title.contains(needle, ignoreCase = true)
                                    }) { entry ->
                                        val target = pages.indexOfFirst { it.background == PageBackground.PDF && it.pdfPageIndex == entry.pdfPage }
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(start = (entry.level.coerceAtMost(4) * 12).dp)
                                                .clip(Ui.Shape.chip)
                                                .clickable(enabled = target >= 0) { onJump(target) }
                                                .padding(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
                                        ) {
                                            val page = pages.getOrNull(target)
                                            Box(Modifier.width(TOC_PICTURE)) {
                                                if (page != null) PagePicture(picture(target), page.width / page.height, target + 1)
                                            }
                                            Text(
                                                entry.title.ifBlank { "(Untitled)" } + "  · ${entry.pdfPage + 1}",
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                                val entries = pages.filter { page ->
                                    val title = page.tocTitle
                                    title != null && (needle.isEmpty() || title.contains(needle, ignoreCase = true))
                                }
                                if (entries.isNotEmpty() && pdfOutline.isNotEmpty()) {
                                    item { Text("My contents", style = MaterialTheme.typography.labelMedium) }
                                }
                                if (pages.none { it.tocTitle != null } && pdfOutline.isEmpty()) {
                                    item {
                                        Text(
                                            "No contents yet. Add the current page, or hold a page in Pages to set one.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.padding(8.dp),
                                        )
                                    }
                                }
                                // The outline as a tree, the way an editor shows a file's
                                // structure: indent guides, a chevron on every entry that has
                                // entries under it, folded or open.
                                if (entries.isNotEmpty()) item(key = "toc-tools") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("OUTLINE", style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                                        TextButton(onClick = {
                                            tocFolded.clear()
                                            entries.forEachIndexed { i, e ->
                                                if (entries.getOrNull(i + 1)?.let { it.tocLevel > e.tocLevel } == true) tocFolded += e.id
                                            }
                                        }) { Text("Fold all") }
                                        TextButton(onClick = { tocFolded.clear() }) { Text("Open all") }
                                        TextButton(onClick = { tocPictures = !tocPictures }) {
                                            Text(if (tocPictures) "Hide pictures" else "Pictures")
                                        }
                                    }
                                }
                                var foldedAt = Int.MAX_VALUE
                                val visible = ArrayList<Pair<Page, Boolean>>()
                                entries.forEachIndexed { i, page ->
                                    if (page.tocLevel > foldedAt) return@forEachIndexed
                                    foldedAt = Int.MAX_VALUE
                                    val parent = entries.getOrNull(i + 1)?.let { it.tocLevel > page.tocLevel } == true
                                    visible += page to parent
                                    if (parent && page.id in tocFolded) foldedAt = page.tocLevel
                                }
                                items(visible, key = { it.first.id }) { (page, parent) ->
                                    val index = pageIndices[page.id] ?: return@items
                                    TocTreeRow(
                                        index = index,
                                        page = page,
                                        picture = if (tocPictures) picture(index) else null,
                                        selected = index == currentPage,
                                        hasChildren = parent,
                                        folded = page.id in tocFolded,
                                        onToggle = { if (page.id in tocFolded) tocFolded -= page.id else tocFolded += page.id },
                                        onJump = { onJump(index) },
                                        onEdit = { editToc(page) },
                                        onHighlight = { onHighlightToc(index, !page.tocHighlighted) },
                                        onDelete = { onSetToc(index, null) },
                                        onIndent = { onTocLevel(index, page.tocLevel + 1) },
                                        onOutdent = { onTocLevel(index, page.tocLevel - 1) },
                                    )
                                }
                            }
                            TextButton(
                                onClick = { pages.getOrNull(currentPage)?.let(::editToc) },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            ) {
                                Icon(Reicons.Add, contentDescription = null)
                                Text(" Add current page")
                            }
                        }
                        PAGE_PANEL_MASKS -> {
                            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                            // Only pages with tape; a search narrows them to the strips whose names match.
                            val taped = pages.filter { page ->
                                page.masks.isNotEmpty() && (needle.isEmpty() || page.masks.any { mask ->
                                    study?.record(MaskStudy.keyOf(page.id, mask.stroke))?.name
                                        ?.contains(needle, ignoreCase = true) == true
                                })
                            }
                            LazyColumn(
                                Modifier.weight(1f).glassEdges(listState),
                                state = listState,
                                contentPadding = PaddingValues(Ui.Space.m),
                                verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
                            ) {
                                if (taped.isEmpty()) item {
                                    Text(
                                        if (needle.isEmpty()) "No masking tape in this note yet." else "No masks named like that.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(8.dp),
                                    )
                                }
                                items(taped, key = { it.id }) { page ->
                                    val index = pageIndices[page.id] ?: return@items
                                    MaskChip(
                                        index = index,
                                        page = page,
                                        picture = picture(index),
                                        selected = index == currentPage,
                                        onJump = { onJump(index) },
                                        onReveal = { onReveal(index, it) },
                                        onClear = { onClearMasks(index) },
                                        onRevealMask = { maskIndex, revealed -> onRevealMask(index, maskIndex, revealed) },
                                        onDeleteMask = { maskIndex -> onDeleteMask(index, maskIndex) },
                                        study = study,
                                        studyRevision = studyRevision,
                                        onRenameMask = { maskIndex -> onRenameMask(index, maskIndex) },
                                        onAnswerMask = { maskIndex, right -> onAnswerMask(index, maskIndex, right) },
                                        onStudy = { wrongOnly -> onStudy(index, wrongOnly) },
                                    )
                                }
                            }
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
                        else -> {
                            // The other notes, to change to or open beside this one.
                            val shownNotes = notes.filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) }
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(NOTE_TILE_MIN),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(Ui.Space.m),
                                horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
                                verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
                            ) {
                                if (needle.isEmpty()) item { NewNoteTile(onNewNote) }
                                items(shownNotes, key = { it.id }) { other ->
                                    NoteTile(
                                        note = other,
                                        current = other.id == currentNoteId,
                                        onOpen = { onOpenNote(other) },
                                        onOpenBeside = { onOpenBeside(other) },
                                        onDrag = onDragNote,
                                        onDrop = { at -> onDropNote(other, at) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The page panel's tabs, in order. */
private val PAGE_PANEL_TABS = listOf("Pages", "TOC", "Masks", "Notes")

/**
 * One entry of the outline tree: indent guides for its depth, a chevron when
 * entries sit under it, its title and its page. Tapped, the page; the chevron
 * folds it; its menu renames, marks, moves in or out, or removes it.
 */
@Composable
private fun TocTreeRow(
    index: Int, page: Page, picture: android.graphics.Bitmap?, selected: Boolean,
    hasChildren: Boolean, folded: Boolean, onToggle: () -> Unit,
    onJump: () -> Unit, onEdit: () -> Unit, onHighlight: () -> Unit, onDelete: () -> Unit,
    onIndent: () -> Unit, onOutdent: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val guide = scheme.outlineVariant
    val depth = page.tocLevel.coerceIn(0, 8)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(Ui.Shape.chip)
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onJump)
            .drawBehind {
                // A thin line down each level above this one, as an editor's tree draws them.
                for (level in 0 until depth) {
                    val x = (12 + level * 16).dp.toPx()
                    drawLine(guide, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
            }
            .padding(start = (depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).clickable(enabled = hasChildren, onClick = onToggle), contentAlignment = Alignment.Center) {
            if (hasChildren) Icon(Reicons.ChevronRight, contentDescription = if (folded) "Open" else "Fold",
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (folded) 0f else 90f })
        }
        if (picture != null) Box(Modifier.width(36.dp).padding(end = 6.dp)) {
            PagePicture(picture, page.width / page.height, index + 1)
        }
        Text(
            page.tocTitle.orEmpty(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (page.tocHighlighted || depth == 0) FontWeight.SemiBold else FontWeight.Normal),
            color = if (page.tocHighlighted) scheme.primary else scheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = scheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Reicons.MoreVert, contentDescription = "Contents entry options", modifier = Modifier.size(Ui.Icon.small))
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text(if (page.tocHighlighted) "Unmark" else "Mark") }, onClick = { menu = false; onHighlight() })
                DropdownMenuItem(text = { Text("Move in") }, onClick = { menu = false; onIndent() })
                if (depth > 0) DropdownMenuItem(text = { Text("Move out") }, onClick = { menu = false; onOutdent() })
                DropdownMenuItem(text = { Text("Remove from contents") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
private const val PAGE_PANEL_PAGES = 0
private const val PAGE_PANEL_TOC = 1
private const val PAGE_PANEL_MASKS = 2
/** How small a note's picture gets in the Notes tab before a row holds one fewer. */
private val NOTE_TILE_MIN = 96.dp
/** The page picture at the start of a contents entry. */
private val TOC_PICTURE = 44.dp
/** A strip of tape's part of its page, in the Masks tab. */
private val MASK_CROP_WIDTH = 88.dp
private val MASK_CROP_HEIGHT = 56.dp

@Composable
private fun TocChip(
    index: Int,
    page: Page,
    /** The page the entry opens, pictured at its start. */
    picture: Bitmap?,
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
            .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(TOC_PICTURE)) { PagePicture(picture, page.width / page.height, index + 1) }
        Spacer(Modifier.width(Ui.Space.s))
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
    /** The page's picture, from which each strip's part is cut. */
    picture: Bitmap?,
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
            val box = remember(mask.stroke) { mask.stroke.shape.computeBoundingBox() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Where the strip lies on the page, and what is under it once it is lifted.
                if (box != null) {
                    MaskCrop(
                        picture, page.width, page.height, box.xMin, box.yMin, box.xMax, box.yMax,
                        covered = !mask.revealed,
                        modifier = Modifier.size(width = MASK_CROP_WIDTH, height = MASK_CROP_HEIGHT)
                            .clickable(onClick = onJump),
                    )
                    Spacer(Modifier.width(Ui.Space.s))
                }
                Column(Modifier.weight(1f).clickable(enabled = study != null) { onRenameMask(maskIndex) }) {
                    Text(
                        when (record.last) {
                            MaskRecord.RESULT_RIGHT -> "✓ "
                            MaskRecord.RESULT_WRONG -> "✗ "
                            else -> ""
                        } + record.name.ifBlank { "Mask ${maskIndex + 1}" },
                        style = MaterialTheme.typography.labelMedium,
                        color = when (record.last) {
                            MaskRecord.RESULT_RIGHT -> Color(0xFF2E7D32)
                            MaskRecord.RESULT_WRONG -> MaterialTheme.colorScheme.error
                            else -> Color.Unspecified
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (box != null) Text(
                        maskPlace((box.xMin + box.xMax) / 2f, (box.yMin + box.yMax) / 2f, page.width, page.height) +
                            " · p. ${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
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
private fun PageCard(
    index: Int,
    page: Page,
    picture: Bitmap?,
    selected: Boolean,
    /** Picking pages for a batch action: a tap ticks the page instead of going to it. */
    choosing: Boolean,
    chosen: Boolean,
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
    /** A practice page laid beside another. */
    practice: Boolean = false,
    /** Whether there are comments or practice pages to put away. */
    hasAttachments: Boolean = page.attachments.isNotEmpty(),
    attachmentsHidden: Boolean = page.attachmentsHidden,
    /** Lays a practice page beside this one, or keeps a comment with it. */
    onAttach: () -> Unit = {},
    /** Puts the page's comments and practice pages away, or back. */
    onAttachmentsHidden: (Boolean) -> Unit = {},
    /** The page's menu, opened from the panel (the selection bar's More) rather than by a hold. */
    menuOpen: Boolean = false,
    onMenuOpenChange: (Boolean) -> Unit = {},
    /** What a hold does, for TalkBack: the panel's grid reads the hold itself (see PageGridSelection). */
    onHold: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) { if (menuOpen) menu = true }
    LaunchedEffect(menu) { if (!menu && menuOpen) onMenuOpenChange(false) }
    val scheme = MaterialTheme.colorScheme
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Ui.Shape.chip)
                .background(when {
                    chosen -> scheme.primary.copy(alpha = 0.22f)
                    selected -> scheme.primaryContainer.copy(alpha = 0.55f)
                    else -> Color.Transparent
                })
                // A tap only: holding a card is the grid's, so a hold can go on into
                // a drag across the pages (see PageGridSelection).
                .clickable(onClick = onJump)
                .semantics {
                    onLongClick(if (choosing) "Select pages up to here" else "Select pages") { onHold(); true }
                    if (choosing) stateDescription = if (chosen) "Selected" else "Not selected"
                }
                .padding(Ui.Space.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Ui.Space.hair),
        ) {
            Box {
                PagePicture(
                    picture, page.width / page.height, index + 1,
                    Modifier.border(if (selected) 2.dp else 0.dp, if (selected) scheme.primary else Color.Transparent, Ui.Shape.preview),
                )
                if (page.bookmarked) Icon(
                    Reicons.Bookmark, contentDescription = "Bookmarked", tint = scheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(16.dp),
                )
                if (choosing) Checkbox(checked = chosen, onCheckedChange = null, modifier = Modifier.align(Alignment.TopStart))
                // Its attachments, as the tab they are on the page.
                if (hasAttachments && !practice) Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 22.dp).size(width = 6.dp, height = 14.dp)
                        .background(Color(0xFFF5A623).copy(alpha = if (attachmentsHidden) 0.4f else 1f),
                            RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp)),
                )
            }
            Text(if (practice) "${index + 1} · Practice" else "${index + 1}", style = MaterialTheme.typography.labelMedium,
                color = if (selected) scheme.primary else scheme.onSurfaceVariant)
            page.tocTitle?.let { title ->
                Text(title, style = MaterialTheme.typography.labelSmall,
                    color = if (page.tocHighlighted) scheme.tertiary else scheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (page.bookmarked) "Remove bookmark" else "Bookmark") },
                onClick = { menu = false; onBookmark() },
            )
            if (hasAttachments) DropdownMenuItem(
                text = { Text(if (attachmentsHidden) "Show comments and practice pages" else "Hide comments and practice pages") },
                onClick = { menu = false; onAttachmentsHidden(!attachmentsHidden) },
            )
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
                    text = { Text("Infinite note · down") },
                    onClick = { onBackground(PageBackground.INFINITE); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Infinite note · every way") },
                    onClick = { onBackground(PageBackground.INFINITE_ALL); menu = false },
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
