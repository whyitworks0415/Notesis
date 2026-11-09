package com.notesis

import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal const val SETTINGS_SIDEBAR_DEFAULT_DP = 380f
internal const val SETTINGS_SIDEBAR_MIN_DP = 300f
internal const val SETTINGS_SIDEBAR_MAX_DP = 720f
/** Narrower than this, the sidebar takes the whole width: half a phone is not a panel. */
internal const val SETTINGS_SIDEBAR_FULL_BELOW_DP = 560f

/**
 * How far past its narrowest a panel along an edge - settings, pages, the web
 * - is pulled, in dp, for letting go to put it away rather than keep it at
 * that width. Short of it the panel springs back to its narrowest.
 */
internal const val PANEL_PULL_TO_CLOSE_DP = 72f

/** Whether a panel pulled [past] its narrowest, in dp, goes away when let go. */
internal fun pulledToClose(past: Float): Boolean = past > PANEL_PULL_TO_CLOSE_DP

/**
 * How wide the sidebar is for a [requested] width on a [screen] that wide, both
 * in dp. It never covers the whole of a wide screen, and on a narrow one it is
 * the whole screen.
 */
internal fun settingsSidebarWidth(requested: Float, screen: Float): Float {
    if (screen < SETTINGS_SIDEBAR_FULL_BELOW_DP) return screen
    val ceiling = minOf(SETTINGS_SIDEBAR_MAX_DP, screen - 48f).coerceAtLeast(SETTINGS_SIDEBAR_MIN_DP)
    val wanted = if (requested.isFinite()) requested else SETTINGS_SIDEBAR_DEFAULT_DP
    return wanted.coerceIn(SETTINGS_SIDEBAR_MIN_DP, ceiling)
}

/** The sidebar's screens. [scrolls] is false for a screen that keeps part of itself still. */
internal enum class SettingsPage(val title: String, val subtitle: String, val scrolls: Boolean = true) {
    ROOT("Settings", ""),
    PEN("Pen settings", "Smoothing mode, latency, pressure and pen gestures for every pen"),
    APPEARANCE("Appearance", "Language, brightness, size, light or dark, UI style, icons, glass and colors", scrolls = false),
    TOUCH("Touch & gestures", "Fingers, taps, palm rejection and three-finger gestures"),
    TOOLBAR("Customize toolbar", "Tools and their order for each toolbar size", scrolls = false),
    NOTE_VIEW("Note & view", "Page layout, page tone and this note"),
    WEB("Web & AI", "Open the browser panel or an AI site, and add your own sites"),
    SAVE_RESET("Save & reset", "Keep your settings to come back to, or reset the toolbar"),
    DIAGNOSTICS("Diagnostics", "Latency overlay, timings, background work and device state"),
}

/** The order the root lists its sections in. */
private val SECTIONS = listOf(
    SettingsPage.PEN, SettingsPage.APPEARANCE, SettingsPage.TOUCH, SettingsPage.TOOLBAR,
    SettingsPage.NOTE_VIEW, SettingsPage.WEB, SettingsPage.SAVE_RESET, SettingsPage.DIAGNOSTICS,
)

/**
 * Where the sidebar is: a stack of screens with the root at the bottom, so
 * Back always has somewhere to go and nothing ever opens on top of it.
 */
internal data class SidebarNav(val stack: List<SettingsPage> = listOf(SettingsPage.ROOT)) {
    val current: SettingsPage get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun open(page: SettingsPage): SidebarNav = when {
        page == current -> this
        page == SettingsPage.ROOT -> SidebarNav()
        // Reaching a screen already further down goes back to it rather than
        // stacking a second copy.
        page in stack -> SidebarNav(stack.subList(0, stack.indexOf(page) + 1).toList())
        else -> SidebarNav(stack + page)
    }

    fun back(): SidebarNav = if (canGoBack) SidebarNav(stack.dropLast(1)) else this

    companion object {
        /** Opened straight at [page], with the root under it for Back. */
        fun at(page: SettingsPage): SidebarNav = SidebarNav().open(page)
    }
}

/** For a control that cannot act: tells accessibility services why. */
internal fun Modifier.disabledReason(reason: String?): Modifier =
    if (reason == null) this else semantics { stateDescription = reason }

/**
 * A panel along the end edge: a header that never scrolls, with large Back
 * and Close, and a body below. Its inner edge is a handle that drags the width;
 * the width is handed to [onWidthChange] once, when the drag ends.
 */
@Composable
internal fun SettingsSidebarFrame(
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    widthDp: Float,
    onWidthChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** How far in the panel is, 0 off past its edge to 1 in place; see [AnimatedSettingsSidebar]. */
    appear: () -> Float = { 1f },
    body: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val screen = maxWidth.value
        val full = screen < SETTINGS_SIDEBAR_FULL_BELOW_DP
        var draft by remember { mutableStateOf<Float?>(null) }
        // Where the finger has taken the edge, unclamped, and how far that is past the narrowest.
        var reach by remember { mutableStateOf<Float?>(null) }
        var past by remember { mutableFloatStateOf(0f) }
        val latestClose by rememberUpdatedState(onClose)
        // Let go stretched past its widest, it springs back to it with a
        // little give, rather than jumping back the moment the finger lifts.
        val springBack = remember { androidx.compose.animation.core.Animatable(0f) }
        val springScope = androidx.compose.runtime.rememberCoroutineScope()
        // The draft as the drag left it, stretched past its widest included.
        val shown = draft ?: (settingsSidebarWidth(widthDp, screen) + springBack.value)
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val latestShown by rememberUpdatedState(shown)
        val latestScreen by rememberUpdatedState(screen)
        val latestCommit by rememberUpdatedState(onWidthChange)
        val scheme = MaterialTheme.colorScheme
        val slideShift = LocalSlideShift.current
        Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(shown.dp)
            // Slides in from the end edge it stands on and back out past it.
            // Read in the layer, so a frame of the slide is a recomposite only.
            .graphicsLayer {
                val outside = 1f - appear().coerceIn(0f, 1f)
                // Pulled past its narrowest it follows the finger out, to be let go of.
                translationX = (outside * size.width + past.dp.toPx()) * (if (rtl) -1f else 1f)
                // The glass looks at the page where the panel rests; see [ProvideSlidingBackdrops].
                slideShift?.x = translationX
            }) {
            val hapticView = androidx.compose.ui.platform.LocalView.current
            if (!full) {
                Box(
                    Modifier
                        .width(24.dp)
                        .fillMaxHeight()
                        .pointerInput(rtl) {
                            fun letGo() {
                                // Pulled far enough past its narrowest, it goes; it slides
                                // the rest of the way out from where the finger left it.
                                // Kept at the width it was pulled to: put back to its own
                                // width first, it grew for a moment on the way out.
                                if (pulledToClose(past)) { latestClose(); return }
                                val stretched = draft
                                val kept = stretched?.let { settingsSidebarWidth(it, latestScreen) }
                                if (kept != null) latestCommit(kept)
                                past = 0f
                                draft = null
                                reach = null
                                val over = if (stretched != null && kept != null) stretched - kept else 0f
                                if (over > 0.5f) springScope.launch {
                                    springBack.snapTo(over)
                                    ChromeMotion.during { springBack.animateTo(0f, SIDEBAR_SPRING_BACK.paced()) }
                                }
                            }
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    draft = latestShown; reach = latestShown
                                    springScope.launch { springBack.snapTo(0f) }
                                },
                                onDragEnd = { letGo() },
                                onDragCancel = { letGo() },
                            ) { change, dragAmount ->
                                change.consume()
                                // Pulling the inner edge toward the middle widens the panel.
                                val grow = (if (rtl) dragAmount else -dragAmount) / density
                                val wanted = (reach ?: latestShown) + grow
                                reach = wanted
                                val held = settingsSidebarWidth(wanted, latestScreen)
                                val over = wanted - held
                                // Past its widest it gives a little, stiffer the further it is
                                // pulled, and comes back to its widest when let go.
                                val wasOver = (draft ?: held) > held + 0.5f
                                draft = if (over > 0f) held + rubberBand(over, SIDEBAR_STRETCH_DP) else held
                                if (over > 0f && !wasOver) Haptics.tick(hapticView)
                                val wasClosing = pulledToClose(past)
                                past = (SETTINGS_SIDEBAR_MIN_DP - wanted).coerceAtLeast(0f)
                                if (pulledToClose(past) != wasClosing) Haptics.snap(hapticView)
                            }
                        }
                        .semantics {
                            contentDescription = "Resize settings panel"
                            customActions = listOf(
                                CustomAccessibilityAction("Make wider") {
                                    latestCommit(settingsSidebarWidth(latestShown + 60f, latestScreen)); true
                                },
                                CustomAccessibilityAction("Make narrower") {
                                    latestCommit(settingsSidebarWidth(latestShown - 60f, latestScreen)); true
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 5.dp, height = 56.dp)
                            .clip(CircleShape)
                            .background(scheme.outline.copy(alpha = if (draft != null) 0.95f else 0.6f)),
                    )
                }
            }
            SkinSurface(Modifier.weight(1f).fillMaxHeight(), corner = if (full) 0.dp else Ui.Corner.sheet, role = ChromeRole.SIDEBAR) {
                // A veil over the glass: a column of settings has to be read,
                // and the page behind it should only be a hint.
                Column(
                    Modifier
                        .fillMaxSize()
                        // What it holds arrives a beat after the panel and a
                        // little further, so it settles into the panel rather
                        // than being carried in pasted to it.
                        .graphicsLayer {
                            val p = appear().coerceIn(0f, 1f)
                            // Faded draw by draw rather than through a layer the
                            // size of the panel, which every frame of the slide
                            // would otherwise fill and blend again.
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                            alpha = sidebarContentAlpha(p)
                            translationX = (1f - p) * SIDEBAR_CONTENT_LEAD.toPx() * (if (rtl) -1f else 1f)
                        }
                        .background(scheme.surface.copy(alpha = if (LocalSkin.current == Skin.MATERIAL) 0f else 0.72f))
                        .windowInsetsPadding(ChromeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.End)),
                ) {
                    SidebarHeader(title, canGoBack, onBack, onClose)
                    HorizontalDivider(color = scheme.outlineVariant)
                    // The pages pad their content by [SIDEBAR_INSET], so the
                    // cards and rows on them are concentric with the panel.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        NestedCorners(if (full) 0.dp else Ui.Corner.sheet, SIDEBAR_INSET) { body() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarHeader(title: String, canGoBack: Boolean, onBack: () -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(Ui.Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            Row(
                Modifier
                    .heightIn(min = Ui.Size.touch)
                    .clip(Ui.Shape.pill)
                    .background(scheme.surfaceContainerHigh)
                    .clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack)
                    .semantics(mergeDescendants = true) { contentDescription = "Back" }
                    .padding(start = Ui.Space.m, end = Ui.Space.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(AppIcons.back, null, Modifier.size(Ui.Icon.regular))
                Spacer(Modifier.width(Ui.Space.s))
                Text("Back", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            Spacer(Modifier.width(Ui.Space.s))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = Ui.Space.s).semantics { heading() },
        )
        Box(
            Modifier
                .size(Ui.Size.touch)
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "Close settings" },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(AppIcons.close, null, Modifier.size(Ui.Icon.regular))
        }
    }
}

/**
 * Shows the sidebar for [nav] and slides it in from its edge, and when [nav]
 * goes back to null slides it out again before taking it away - [content] is
 * handed the last screen it was on, how far in it is, and whether it is on
 * its way out. Liquid glass and Glassmorphism move; Material shows it and
 * removes it at once, as it does every popup.
 */
@Composable
internal fun AnimatedSettingsSidebar(
    nav: SidebarNav?,
    content: @Composable (nav: SidebarNav, appear: () -> Float, closing: Boolean) -> Unit,
) {
    val animate = rememberLiquidGlassEffectsAllowed() &&
        !LocalSkinSettings.current.highContrast
    val skin = LocalSkin.current
    // The screen it was on, kept for the slide out; not state, since [nav]
    // itself is what recomposes this.
    val last = remember { arrayOfNulls<SidebarNav>(1) }
    if (nav != null) last[0] = nav
    val progress = remember { Animatable(if (nav != null) 1f else 0f) }
    val velocity = remember { floatArrayOf(0f) }
    var present by remember { mutableStateOf(nav != null) }
    val open = nav != null
    LaunchedEffect(open, animate) {
        if (open) {
            present = true
            // The first frame with the sidebar in it is the slow one; the slide
            // starts after it (see PopupGrowth.open), not on it.
            if (animate && progress.value == 0f) awaitFirstFrames()
            if (animate) ChromeMotion.during { progress.animateTo(1f, sidebarOpenFor(skin), initialVelocity = velocity[0]) { velocity[0] = this.velocity }; velocity[0] = 0f } else { velocity[0] = 0f; progress.snapTo(1f) }
        } else {
            if (animate) ChromeMotion.during { progress.animateTo(0f, sidebarCloseFor(skin), initialVelocity = velocity[0]) { velocity[0] = this.velocity }; velocity[0] = 0f } else { velocity[0] = 0f; progress.snapTo(0f) }
            present = false
        }
    }
    // One reader, whichever skin is on, so switching skin with the sidebar
    // open keeps its screens and their scroll positions intact.
    val moving by rememberUpdatedState(animate)
    val appear = remember { { if (moving) progress.value else 1f } }
    // Keep the last screen until its closing motion has completed.
    if (!open && !(animate && present)) return
    val shown = nav ?: last[0] ?: return
    // The glass in it looks at the page where it comes to rest, so it is drawn
    // once rather than again on each frame of the slide; see [ProvideSlidingBackdrops].
    val shift = remember { SlideShift() }
    ProvideSlidingBackdrops(shift) {
        content(shown, appear, !open)
    }
}

/** How far past its widest the sidebar can be stretched at most, in dp; it gives far less. */
private const val SIDEBAR_STRETCH_DP = 120f

/** Back from a stretch: underdamped, so it settles past its widest and back, as a rubber band lets go. */
private val SIDEBAR_SPRING_BACK = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.55f, stiffness = 420f)

/** How much further than the panel its content travels as it comes in. */
private val SIDEBAR_CONTENT_LEAD = 24.dp

/**
 * The note's settings sidebar. Each screen's content comes from the note,
 * which holds the state it sets; this decides where they go and how to get
 * between them.
 */
@Composable
internal fun NoteSettingsSidebar(
    nav: SidebarNav,
    onNav: (SidebarNav) -> Unit,
    onClose: () -> Unit,
    widthDp: Float,
    onWidthChange: (Float) -> Unit,
    quickActions: List<GlassToolbarAction>,
    webActions: List<GlassToolbarAction>,
    /** Opens an address in the browser panel: one of the sites added in Web & AI. */
    onOpenSite: (String) -> Unit = {},
    penSettings: @Composable () -> Unit,
    appearance: @Composable () -> Unit,
    touch: @Composable () -> Unit,
    /** The toolbar editor, and how it says it is finished. */
    toolbar: @Composable (done: () -> Unit) -> Unit,
    noteView: @Composable () -> Unit,
    diagnostics: @Composable () -> Unit,
    /** Puts back the toolbar's place and pin, which the note screen holds, after [resetToolbar]. */
    onResetToolbar: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** See [AnimatedSettingsSidebar]. */
    appear: () -> Float = { 1f },
    /** Set while it slides away after closing: Back is the page's again. */
    closing: Boolean = false,
) {
    val latestNav by rememberUpdatedState(nav)
    val moves = rememberLiquidGlassEffectsAllowed() && !LocalSkinSettings.current.highContrast
    val material = LocalSkin.current == Skin.MATERIAL
    BackHandler(enabled = !closing) { if (latestNav.canGoBack) onNav(latestNav.back()) else onClose() }
    SettingsSidebarFrame(
        title = nav.current.title,
        canGoBack = nav.canGoBack,
        onBack = { onNav(latestNav.back()) },
        onClose = onClose,
        widthDp = widthDp,
        onWidthChange = onWidthChange,
        modifier = modifier,
        appear = appear,
    ) {
        AnimatedContent(targetState = nav, contentKey = { it.current },
            transitionSpec = {
                if (!moves) EnterTransition.None togetherWith ExitTransition.None
                else if (material) {
                    val direction = materialPageDirection(initialState.stack.size, targetState.stack.size)
                    (slideInHorizontally(sidebarOpenFor<androidx.compose.ui.unit.IntOffset>(Skin.MATERIAL)) {
                        (it * 0.06f * direction).toInt()
                    } + fadeIn(MaterialMotion.effects())) togetherWith
                        (slideOutHorizontally(sidebarCloseFor<androidx.compose.ui.unit.IntOffset>(Skin.MATERIAL)) {
                            (-it * 0.04f * direction).toInt()
                        } + fadeOut(MaterialMotion.effects()))
                } else fadeIn(Ui.Motion.tween()) togetherWith fadeOut(Ui.Motion.tween())
            }, label = "settings page") { target ->
            val page = target.current
            val content: @Composable () -> Unit = {
                when (page) {
                    SettingsPage.ROOT -> SettingsRoot(quickActions) { onNav(latestNav.open(it)) }
                    SettingsPage.PEN -> penSettings()
                    SettingsPage.APPEARANCE -> appearance()
                    SettingsPage.TOUCH -> touch()
                    SettingsPage.TOOLBAR -> toolbar { onNav(latestNav.back()) }
                    SettingsPage.NOTE_VIEW -> noteView()
                    SettingsPage.WEB -> {
                        SettingsNote("The page opens in a panel beside the note, so you can keep writing.")
                        GlassMenuGrid(webActions, columns = 2)
                        AddedSitesEditor(onOpen = onOpenSite)
                    }
                    SettingsPage.SAVE_RESET -> SettingsSaveAndReset(onResetToolbar)
                    SettingsPage.DIAGNOSTICS -> diagnostics()
                }
            }
            if (page.scrolls) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SIDEBAR_INSET),
                    verticalArrangement = Arrangement.spacedBy(Ui.Space.m),
                ) { content() }
            } else {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

/**
 * The first screen: the quick actions and the sections, all of it in view
 * without scrolling on a tablet. Every entry is one size and one spacing,
 * an icon and a name with no tile, border or circle behind the icon. The
 * language and the brightness moved into Appearance; Diagnostics is shown
 * to beta testers only, who are the ones asked to read it.
 */
@Composable
private fun SettingsRoot(quickActions: List<GlassToolbarAction>, onOpen: (SettingsPage) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val beta = remember { AppUpdate.isBeta(context) }
    if (quickActions.isNotEmpty()) {
        SettingsSectionHeader("Quick actions")
        QuickActionGrid(quickActions)
    }
    SettingsSectionHeader("Sections")
    Column {
        settingsSections(beta).forEach { page -> SettingsNavRow(page) { onOpen(page) } }
    }
}

/** The sections the root lists: Diagnostics only on a beta tester's copy. */
internal fun settingsSections(beta: Boolean): List<SettingsPage> =
    SECTIONS.filter { beta || it != SettingsPage.DIAGNOSTICS }

/** Quick actions three across: an icon over its name, nothing behind either. */
@Composable
private fun QuickActionGrid(actions: List<GlassToolbarAction>) {
    val scheme = MaterialTheme.colorScheme
    Column {
        actions.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { action ->
                    val tint = when {
                        !action.enabled -> scheme.onSurface.copy(alpha = 0.38f)
                        action.selected -> scheme.primary
                        else -> scheme.onSurface
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .height(QUICK_ACTION_HEIGHT)
                            .clickable(enabled = action.enabled, role = Role.Button) { action.onClick() }
                            .semantics(mergeDescendants = true) { if (action.selected) selected = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Ui.Space.xs, Alignment.CenterVertically),
                    ) {
                        AppIcon(action.icon, null, Modifier.size(Ui.Icon.regular), tint = tint)
                        Text(action.label, style = MaterialTheme.typography.labelLarge, color = tint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = Ui.Space.xs))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val QUICK_ACTION_HEIGHT = 72.dp

private fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.ROOT -> AppIcons.settings
    SettingsPage.PEN -> AppIcons.penSettings
    SettingsPage.APPEARANCE -> AppIcons.appearance
    SettingsPage.TOUCH -> AppIcons.touch
    SettingsPage.TOOLBAR -> AppIcons.editTools
    SettingsPage.NOTE_VIEW -> AppIcons.note
    SettingsPage.WEB -> AppIcons.web
    SettingsPage.SAVE_RESET -> AppIcons.resetBar
    SettingsPage.DIAGNOSTICS -> AppIcons.diagnostics
}

@Composable
internal fun SettingsNavRow(page: SettingsPage, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // One height and one gap for every row, no tile or border behind it.
    Row(
        Modifier
            .fillMaxWidth()
            .height(SETTINGS_ROW_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = page.title }
            .padding(horizontal = Ui.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(page.icon(), null, Modifier.size(Ui.Icon.regular), tint = scheme.onSurface)
        Spacer(Modifier.width(Ui.Space.l))
        Text(page.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Icon(Reicons.ChevronRight, contentDescription = null, modifier = Modifier.size(Ui.Icon.small), tint = scheme.outline)
    }
}

private val SETTINGS_ROW_HEIGHT = 52.dp

// ---- shared pieces for the sidebar's screens ---------------------------------

@Composable
internal fun SettingsSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = Ui.Space.xs).semantics { heading() },
    )
}

@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A rounded block that groups related settings. */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // Concentric with whatever holds it - the sidebar, a dialog - and the
    // card in turn hands its own corner, one padding in, to what it holds.
    val corner = nestedCorner(Ui.Corner.card)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(corner))
            .padding(Ui.Space.l),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
    ) {
        NestedCorners(corner, Ui.Space.l) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Ui.Space.s), content = content)
        }
    }
}

/** How far the sidebar's pages are padded in from its edge. */
private val SIDEBAR_INSET = Ui.Space.l

/**
 * On or off, with the whole row as the target. A row that cannot be changed
 * says why under its label, rather than only greying out.
 */
@Composable
internal fun SettingsToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    hint: String? = null,
    disabledReason: String? = null,
) {
    val enabled = disabledReason == null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Ui.Size.touch)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .disabledReason(disabledReason),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = Ui.Space.xs)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
            if (hint != null) SettingsNote(hint)
            if (disabledReason != null) Text(disabledReason, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.width(Ui.Space.m))
        Box(if (enabled) Modifier else Modifier.alpha(0.38f)) {
            SkinSwitch(checked = checked, onCheckedChange = { if (enabled) onChange(it) })
        }
    }
}

/** One choice out of a few, as chips in a row that scrolls when it has to. */
@Composable
internal fun SettingsChoices(
    title: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    hint: String? = null,
    onPick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            options.forEach { (value, label) ->
                SettingsChoiceChip(selected = selected == value, onClick = { onPick(value) }, label = label)
            }
        }
        if (hint != null) SettingsNote(hint)
    }
}

// ---- touch & gestures --------------------------------------------------------

/**
 * What fingers do on the page, apart from the pen. Three-finger gestures can
 * be switched off as a whole; the list under the switch is everything that
 * switch reaches, and nothing else changes with it.
 */
@Composable
internal fun TouchGestureSettings(gestures: CanvasGestures, onGestures: (CanvasGestures) -> Unit) {
    val taps = listOf(TAP_NONE to "Nothing", TAP_UNDO to "Undo", TAP_REDO to "Redo")
    SettingsCard {
        SettingsSectionHeader("Fingers on the page")
        SettingsChoices("One finger",
            listOf(FINGER_SCROLL to "Scroll", FINGER_IGNORED to "Ignore", FINGER_DRAW to "Draw"),
            gestures.oneFinger) { onGestures(gestures.copy(oneFinger = it)) }
        SettingsChoices("Two fingers",
            listOf(TWO_ZOOM_PAN to "Zoom & move", TWO_SCROLL to "Scroll", TWO_IGNORED to "Ignore"),
            gestures.twoFingers) { onGestures(gestures.copy(twoFingers = it)) }
        SettingsNote(
            "Palm rejection: if a resting palm marks or moves the page, set one finger to Ignore " +
                "and two fingers to Scroll. In Draw mode, two fingers move the page.",
        )
    }
    SettingsCard {
        SettingsSectionHeader("Taps and zoom")
        SettingsChoices("Two-finger double tap", taps, gestures.twoFingerTap) {
            onGestures(gestures.copy(twoFingerTap = it))
        }
        SettingsChoices("One-finger double tap", listOf(
            EDGE_HIDE_CHROME to "Hide toolbar", DOUBLE_TAP_ZOOM to "Zoom", EDGE_UNDO to "Undo", EDGE_REDO to "Redo",
            EDGE_PAGES to "Pages panel", EDGE_NOTHING to "Nothing",
        ), gestures.oneFingerDoubleTap) { onGestures(gestures.copy(oneFingerDoubleTap = it)) }
        SettingsNote("Anywhere on the page. Hide toolbar puts the bar and the panels over the note away upwards; " +
            "double-tap again, or pull the tab at the top down, to bring them back. Zoom goes in, or back out to the page width.")
        SettingsToggle("Lock zoom", gestures.zoomLocked, { onGestures(gestures.copy(zoomLocked = it)) },
            hint = "Pinching moves the page but never changes its zoom")
    }
    SettingsCard {
        SettingsSectionHeader("Right edge of the page")
        SettingsNote("Quick taps with one finger near the page's right edge. Hiding puts the toolbar and the panels over the note away upwards; pull the tab at the top down, or tap the edge again, to bring them back.")
        val edge = listOf(
            EDGE_NOTHING to "Nothing", EDGE_HIDE_CHROME to "Hide toolbar", EDGE_UNDO to "Undo", EDGE_REDO to "Redo",
            EDGE_NEXT_PAGE to "Next page", EDGE_PREVIOUS_PAGE to "Previous page", EDGE_PAGES to "Pages panel",
        )
        listOf("One tap", "Two taps", "Three taps").forEachIndexed { i, title ->
            SettingsChoices(title, edge, gestures.edgeTaps.getOrElse(i) { EDGE_NOTHING }) { picked ->
                val next = List(3) { gestures.edgeTaps.getOrElse(it) { EDGE_NOTHING } }.toMutableList()
                next[i] = picked
                onGestures(gestures.copy(edgeTaps = next))
            }
        }
    }
    SettingsCard {
        SettingsSectionHeader("Three-finger gestures")
        val on = gestures.threeFingerGestures
        SettingsToggle(
            "Three-finger gestures", on, { onGestures(gestures.copy(threeFingerGestures = it)) },
            hint = if (on) "Three fingers on the page do the following."
                else "Off: three fingers on the page are ignored.",
        )
        val tapAction = taps.firstOrNull { it.first == gestures.threeFingerTap }?.second ?: "Nothing"
        Column(Modifier.alpha(if (on) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(Ui.Space.hair)) {
            listOf(
                "Double tap: $tapAction",
                "Swipe up: open the reference panel",
                "Swipe down, on the page or the panel: close the reference panel",
                "Twist: rotate the note",
            ).forEach { line -> Text("•  $line", style = MaterialTheme.typography.bodyMedium) }
        }
        if (on) {
            SettingsChoices("Three-finger double tap", taps, gestures.threeFingerTap) {
                onGestures(gestures.copy(threeFingerTap = it))
            }
        } else {
            SettingsNote("Turn three-finger gestures on to choose what a three-finger double tap does.")
        }
        SettingsNote("One- and two-finger settings, the two-finger tap and the pen are not affected by this switch.")
    }
    SettingsCard {
        SettingsSectionHeader("Pen and page")
        SettingsChoices("Writing with the pen button held",
            listOf(PEN_BUTTON_ERASE to "Erases", PEN_BUTTON_LASSO to "Selects (lasso)"),
            gestures.penButton.takeIf { it == PEN_BUTTON_LASSO } ?: PEN_BUTTON_ERASE) {
            onGestures(gestures.copy(penButton = it))
        }
        SettingsToggle("Long press opens a menu", gestures.longPressMenu,
            { onGestures(gestures.copy(longPressMenu = it)) })
        SettingsToggle("Mark linked objects in blue", gestures.linkOverlay,
            { onGestures(gestures.copy(linkOverlay = it)) })
        SettingsToggle("Back to the previous tool after erasing", gestures.eraserReturns,
            { onGestures(gestures.copy(eraserReturns = it)) })
    }
}
