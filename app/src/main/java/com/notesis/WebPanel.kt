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

// The Internet panel beside a note: its WebView, its bar and the sites it offers.
/**
 * A browser beside the note. It is a plain WebView: the point is looking things
 * up without leaving the page being written on, not building a browser.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WebPanel(
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
    /** Opens another site in the panel: one of the AI chats, or the search page. */
    onSite: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    /** The panel's corner: the sidebar's beside the note, the same floating over it. */
    corner: Dp = Ui.Corner.sheet,
    /** Whether the panel keeps clear of the system bars, as it must when it runs to the screen's edges. */
    insets: Boolean = true,
    /** Floating, the panel is moved by a handle along its top. */
    onMove: ((Float, Float) -> Unit)? = null,
    /** Starts a capture on the note that lands in the chat; null where there is no note. */
    onCaptureToChat: (() -> Unit)? = null,
    /** Sends the page in view to the chat, whole. */
    onSendPage: (() -> Unit)? = null,
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

    // The bar over the page takes the colours of the page's own top, point by
    // point across it, as a pane of tinted glass laid over them; the panel
    // round the page takes the page's bottom colour, so nothing white frames
    // it. Asked of the page five times a second and eased from one answer to
    // the next, so the bar follows a scroll or a change of theme as it
    // happens. Until the page answers, the bar is the panel's surface.
    val skin = LocalSkin.current
    var pageColors by remember(web) { mutableStateOf<PageColors?>(null) }
    LaunchedEffect(web) {
        val view = web ?: return@LaunchedEffect
        while (true) {
            runCatching {
                view.evaluateJavascript(PAGE_TOP_COLOR_JS) { result -> parsePageColors(result)?.let { pageColors = it } }
            }
            delay(PAGE_TOP_COLOR_POLL_MS)
        }
    }
    val surface = MaterialTheme.colorScheme.surface
    // Material changes it at once; the other skins ease into it, about as
    // long as the page takes to be asked again, so the change never stops.
    val ease: androidx.compose.animation.core.AnimationSpec<Color> =
        if (skin == Skin.MATERIAL) androidx.compose.animation.core.snap() else tween(PAGE_TOP_COLOR_POLL_MS.toInt() + 60, easing = LinearEasing)
    val headerStops = List(PAGE_TOP_SAMPLES) { i ->
        androidx.compose.animation.animateColorAsState(
            pageColors?.top?.getOrNull(i)?.let { Color(it) } ?: surface, ease, label = "Web bar $i",
        ).value
    }
    val pageBottom by androidx.compose.animation.animateColorAsState(
        pageColors?.bottom?.let { Color(it) } ?: surface, ease, label = "Web panel",
    )
    // One colour for the bar's text and icons, read off the colours under it on average.
    val header = Color(
        headerStops.sumOf { it.red.toDouble() }.toFloat() / headerStops.size,
        headerStops.sumOf { it.green.toDouble() }.toFloat() / headerStops.size,
        headerStops.sumOf { it.blue.toDouble() }.toFloat() / headerStops.size,
    )
    val onHeader = if (pageColors == null) MaterialTheme.colorScheme.onSurface else liquidGlassContentColor(header)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = onHeader,
        unfocusedTextColor = onHeader,
        cursorColor = onHeader,
        focusedBorderColor = onHeader.copy(alpha = 0.6f),
        unfocusedBorderColor = onHeader.copy(alpha = 0.3f),
        focusedLeadingIconColor = onHeader,
        unfocusedLeadingIconColor = onHeader.copy(alpha = 0.75f),
        focusedPlaceholderColor = onHeader.copy(alpha = 0.6f),
        unfocusedPlaceholderColor = onHeader.copy(alpha = 0.6f),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
    )

    SkinSurface(modifier = modifier, corner = corner, role = ChromeRole.SIDEBAR) {
        // The whole panel keeps clear of the system bars, not just its header: a
        // chat composer pinned to the bottom of the page was sitting under the
        // navigation bar with nothing holding it up.
        Column(
            Modifier
                .fillMaxHeight()
                // Round, as the glass is: the page's colour and the page itself
                // were painted square into the corners, so the round corner
                // showed a white square of the chat's background instead of
                // what lies behind the panel.
                .clip(RoundedCornerShape(corner))
                .background(pageBottom)
                .then(if (insets) Modifier.windowInsetsPadding(ChromeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    else Modifier)
                .windowInsetsPadding(keyboardRoom),
        ) {
            CompositionLocalProvider(LocalContentColor provides onHeader) {
            // The bar and the strip of chats, on the page's colour - into the
            // status bar's room too, so the top of the screen is one colour.
            Column(
                Modifier
                    .fillMaxWidth()
                    .webBarGlass(headerStops, glass = skin != Skin.MATERIAL)
                    .then(if (insets) Modifier.windowInsetsPadding(ChromeInsets.only(WindowInsetsSides.Top)) else Modifier),
            ) {
            if (onMove != null) {
                // Floating: the panel is carried by this strip along its top.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, amount -> change.consume(); onMove(amount.x, amount.y) }
                        }
                        .semantics { contentDescription = "Drag to move" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape).background(onHeader.copy(alpha = 0.45f)))
                }
            }
            // The same buttons as the note's bar, and the same search field
            // as the library's; the less used ones folded behind one button.
            var more by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Ui.Space.xs, vertical = Ui.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIconButton(onClick = { web?.let { if (it.canGoBack()) it.goBack() } }, icon = AppIcons.back,
                    contentDescription = "Back")
                AppIconButton(onClick = { web?.reload() }, icon = Reicons.Refresh,
                    contentDescription = "Reload")
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    placeholder = { Text("Address or search") },
                    leadingIcon = { Icon(Reicons.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { web?.loadUrl(asUrl(address)) }),
                    colors = fieldColors,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Ui.Space.xs),
                )
                // Into the chat in one move: tap, then drag over the note.
                if (onCaptureToChat != null) AppIconButton(onClick = onCaptureToChat, icon = AppIcons.capture,
                    contentDescription = "Capture to the chat")
                AppIconButton(onClick = onTogglePopup,
                    icon = if (popup) Reicons.CloseFullscreen
                        else Reicons.OpenInFull,
                    contentDescription = if (popup) "Dock" else "Pop out")
                Box {
                    AppIconButton(onClick = { more = true }, icon = AppIcons.noteMenu, contentDescription = "More",
                        // A logged error shows on the button that hides the log.
                        tint = if (log.isEmpty()) null else MaterialTheme.colorScheme.error)
                    DropdownMenu(more, onDismissRequest = { more = false }) {
                        if (onSendPage != null) DropdownMenuItem(
                            text = { Text("Send this page to the chat") },
                            leadingIcon = { Icon(Reicons.Image, contentDescription = null) },
                            onClick = { more = false; onSendPage() },
                        )
                        if (onCaptureToChat != null) DropdownMenuItem(
                            text = { Text("Capture part of the note") },
                            leadingIcon = { Icon(AppIcons.capture, contentDescription = null) },
                            onClick = { more = false; onCaptureToChat() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (desktop) "Mobile site" else "Desktop site") },
                            leadingIcon = {
                                Icon(if (desktop) Reicons.PhoneAndroid else Reicons.Computer, contentDescription = null)
                            },
                            onClick = { more = false; onToggleDesktop() },
                        )
                        DropdownMenuItem(
                            text = { Text("Open in browser") },
                            leadingIcon = { Icon(Reicons.Launch, contentDescription = null) },
                            onClick = { more = false; openExternally(web?.context, web?.url ?: url) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (showLog) "Hide error log" else "Error log (${log.size})") },
                            leadingIcon = {
                                Icon(Reicons.BugReport, contentDescription = null,
                                    tint = if (log.isEmpty()) LocalContentColor.current else MaterialTheme.colorScheme.error)
                            },
                            onClick = { more = false; onToggleLog() },
                        )
                    }
                }
                AppIconButton(onClick = { web?.clearFocus(); keyboard?.hide(); onClose() }, icon = AppIcons.close,
                    contentDescription = "Close")
            }
            // Which chat, picked here as the pen's options are picked: the
            // same chips, one tap to change.
            val host = remember(url) { runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("") }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = Ui.Space.m, end = Ui.Space.m, bottom = Ui.Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
            ) {
                val known = listOf("Web" to SEARCH_HOME) + aiSites()
                for ((name, site) in known) {
                    val siteHost = Uri.parse(site).host.orEmpty().removePrefix("www.")
                    SettingsChoiceChip(
                        selected = host.isNotEmpty() && host == siteHost,
                        onClick = { address = site; onSite(site) },
                        label = name,
                        contentColor = onHeader,
                    )
                }
                // The page open now, kept among the chips and in Web & AI.
                var addingSite by remember { mutableStateOf(false) }
                val current = web?.url ?: url
                if (host.isNotEmpty() && known.none { (_, site) -> Uri.parse(site).host.orEmpty().removePrefix("www.") == host }) {
                    SettingsChoiceChip(selected = false, onClick = { addingSite = true }, label = "+ Add this site",
                        contentColor = onHeader)
                }
                if (addingSite) AddSiteDialog(onDismiss = { addingSite = false }, initialUrl = current,
                    initialName = web?.title.orEmpty().take(40))
            }
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
 * The web bar's glass: the page's top colours left to right under it, a light
 * caught along its top fading down, and a lit hairline where it meets the
 * page - so it reads as a pane laid over the page rather than a band of paint.
 * Under Material it is the colours alone.
 */
private fun Modifier.webBarGlass(stops: List<Color>, glass: Boolean): Modifier = drawBehind {
    val across = if (stops.size > 1) Brush.horizontalGradient(stops) else androidx.compose.ui.graphics.SolidColor(stops.first())
    drawRect(across)
    if (!glass) return@drawBehind
    // A wash of white, strongest at the top edge.
    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.45f to Color.White.copy(alpha = 0.06f),
        1f to Color.Transparent))
    // The lit edge along its top, and a soft shadow and a bright line along its foot.
    drawRect(Color.White.copy(alpha = 0.35f), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.08f)),
        startY = size.height - 6.dp.toPx(), endY = size.height),
        topLeft = Offset(0f, size.height - 6.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 6.dp.toPx()))
    drawRect(Color.White.copy(alpha = 0.45f), topLeft = Offset(0f, size.height - 1.dp.toPx()),
        size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
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
