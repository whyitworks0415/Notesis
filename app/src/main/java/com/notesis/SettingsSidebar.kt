package com.notesis

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
    APPEARANCE("Appearance", "Light or dark, UI style, icons, glass and colors", scrolls = false),
    TOUCH("Touch & gestures", "Fingers, taps, palm rejection and three-finger gestures"),
    TOOLBAR("Customize toolbar", "Tools and their order for each toolbar size", scrolls = false),
    NOTE_VIEW("Note & view", "Page layout, page tone and this note"),
    WEB("Web & AI", "Open the browser panel or an AI site"),
    DIAGNOSTICS("Diagnostics", "Latency overlay, timings, background work and device state"),
}

/** The order the root lists its sections in. */
private val SECTIONS = listOf(
    SettingsPage.PEN, SettingsPage.APPEARANCE, SettingsPage.TOUCH, SettingsPage.TOOLBAR,
    SettingsPage.NOTE_VIEW, SettingsPage.WEB, SettingsPage.DIAGNOSTICS,
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
    body: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val screen = maxWidth.value
        val full = screen < SETTINGS_SIDEBAR_FULL_BELOW_DP
        var draft by remember { mutableStateOf<Float?>(null) }
        val shown = settingsSidebarWidth(draft ?: widthDp, screen)
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val latestShown by rememberUpdatedState(shown)
        val latestScreen by rememberUpdatedState(screen)
        val latestCommit by rememberUpdatedState(onWidthChange)
        val scheme = MaterialTheme.colorScheme
        Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(shown.dp)) {
            if (!full) {
                Box(
                    Modifier
                        .width(24.dp)
                        .fillMaxHeight()
                        .pointerInput(rtl) {
                            detectHorizontalDragGestures(
                                onDragStart = { draft = latestShown },
                                onDragEnd = {
                                    draft?.let { latestCommit(it) }
                                    draft = null
                                },
                                onDragCancel = {
                                    draft?.let { latestCommit(it) }
                                    draft = null
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                // Pulling the inner edge toward the middle widens the panel.
                                val grow = (if (rtl) dragAmount else -dragAmount) / density
                                draft = settingsSidebarWidth((draft ?: latestShown) + grow, latestScreen)
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
            SkinSurface(Modifier.weight(1f).fillMaxHeight(), corner = if (full) 0.dp else Ui.Corner.sheet) {
                // A veil over the glass: a column of settings has to be read,
                // and the page behind it should only be a hint.
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(scheme.surface.copy(alpha = if (LocalSkin.current == Skin.MATERIAL) 0f else 0.72f))
                        .windowInsetsPadding(ChromeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.End)),
                ) {
                    SidebarHeader(title, canGoBack, onBack, onClose)
                    HorizontalDivider(color = scheme.outlineVariant)
                    Box(Modifier.weight(1f).fillMaxWidth()) { body() }
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
    quickActions: List<SpotiToolbarAction>,
    webActions: List<SpotiToolbarAction>,
    penSettings: @Composable () -> Unit,
    appearance: @Composable () -> Unit,
    touch: @Composable () -> Unit,
    /** The toolbar editor, and how it says it is finished. */
    toolbar: @Composable (done: () -> Unit) -> Unit,
    noteView: @Composable () -> Unit,
    diagnostics: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestNav by rememberUpdatedState(nav)
    BackHandler { if (latestNav.canGoBack) onNav(latestNav.back()) else onClose() }
    SettingsSidebarFrame(
        title = nav.current.title,
        canGoBack = nav.canGoBack,
        onBack = { onNav(latestNav.back()) },
        onClose = onClose,
        widthDp = widthDp,
        onWidthChange = onWidthChange,
        modifier = modifier,
    ) {
        Crossfade(targetState = nav.current, animationSpec = tween(160), label = "settings page") { page ->
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
                        SpotiMenuGrid(webActions, columns = 2)
                    }
                    SettingsPage.DIAGNOSTICS -> diagnostics()
                }
            }
            if (page.scrolls) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Ui.Space.l),
                    verticalArrangement = Arrangement.spacedBy(Ui.Space.m),
                ) { content() }
            } else {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

@Composable
private fun SettingsRoot(quickActions: List<SpotiToolbarAction>, onOpen: (SettingsPage) -> Unit) {
    if (quickActions.isNotEmpty()) {
        SettingsSectionHeader("Quick actions")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            SpotiMenuGrid(quickActions, columns = if (maxWidth >= 420.dp) 3 else 2)
        }
    }
    SettingsSectionHeader("Sections")
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        SECTIONS.forEach { page -> SettingsNavRow(page) { onOpen(page) } }
    }
}

private fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.ROOT -> AppIcons.settings
    SettingsPage.PEN -> AppIcons.penSettings
    SettingsPage.APPEARANCE -> AppIcons.appearance
    SettingsPage.TOUCH -> AppIcons.touch
    SettingsPage.TOOLBAR -> AppIcons.editTools
    SettingsPage.NOTE_VIEW -> AppIcons.note
    SettingsPage.WEB -> AppIcons.web
    SettingsPage.DIAGNOSTICS -> AppIcons.diagnostics
}

@Composable
private fun SettingsNavRow(page: SettingsPage, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(Ui.Shape.control)
            .background(scheme.onSurface.copy(alpha = 0.05f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Ui.Space.l, vertical = Ui.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(page.icon(), null, Modifier.size(Ui.Icon.regular))
        Spacer(Modifier.width(Ui.Space.m))
        Column(Modifier.weight(1f)) {
            Text(page.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (page.subtitle.isNotEmpty()) Text(page.subtitle, style = MaterialTheme.typography.bodySmall,
                color = scheme.outline, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Icon(Reicons.ChevronRight, contentDescription = null, modifier = Modifier.size(Ui.Icon.small), tint = scheme.outline)
    }
}

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
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = modifier)
}

/** A rounded block that groups related settings. */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Ui.Shape.card)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(Ui.Space.l),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
        content = content,
    )
}

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
        SettingsToggle("One-finger double tap zooms", gestures.doubleTapZoom,
            { onGestures(gestures.copy(doubleTapZoom = it)) },
            hint = "In, or back out to the page width")
        SettingsToggle("Lock zoom", gestures.zoomLocked, { onGestures(gestures.copy(zoomLocked = it)) },
            hint = "Pinching moves the page but never changes its zoom")
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
                "Swipe down: close the reference panel",
                "Drag or spread on the panel: move and resize it",
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
            gestures.penButton.takeIf { it != PEN_BUTTON_LASER } ?: PEN_BUTTON_ERASE) {
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
