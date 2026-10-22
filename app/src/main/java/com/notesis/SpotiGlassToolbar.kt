package com.notesis

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlin.math.sin
import com.kyant.backdrop.drawBackdrop

internal enum class SpotiToolbarTool(val mode: EditMode?, val label: String) {
    READ(EditMode.READ, "Read"), PEN(EditMode.PEN, "Pen"), PENCIL(EditMode.PENCIL, "Pencil"),
    HIGHLIGHTER(EditMode.HIGHLIGHTER, "Highlighter"), MASK(EditMode.MASK, "Mask"),
    ERASE(EditMode.ERASE, "Eraser"), SHAPE(EditMode.SHAPE, "Shape"), TEXT(EditMode.TEXT, "Text"),
    LASSO(EditMode.LASSO, "Lasso"), CAPTURE(EditMode.CAPTURE, "Capture"), AI(null, "AI"),
}

/** The tools the bar shows at [level], as arranged in [ToolbarLayout]. */
internal fun spotiToolbarTools(level: Int): List<SpotiToolbarTool> = ToolbarLayout.config.tools(level)

/**
 * How the bar moves between its four sizes: how long it takes and the curve
 * it takes it on. Kept on the device and read as state, so a change in the
 * menu shows on the very next switch.
 */
internal object ToolbarMotion {
    private var prefs: android.content.SharedPreferences? = null

    /** The curves offered, in the order the menu shows them. */
    val EASINGS = listOf("Ease in-out", "Ease out", "Linear", "Spring")
    const val DEFAULT_MS = 360
    const val MAX_MS = 1000

    var durationMs by mutableIntStateOf(DEFAULT_MS)
        private set
    var easing by mutableIntStateOf(0)
        private set

    fun init(context: android.content.Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("pens", android.content.Context.MODE_PRIVATE)
        prefs = p
        durationMs = p.getInt("toolbarMotionMs", DEFAULT_MS).coerceIn(0, MAX_MS)
        easing = p.getInt("toolbarMotionEasing", 0).coerceIn(0, EASINGS.lastIndex)
    }

    fun update(durationMs: Int = this.durationMs, easing: Int = this.easing) {
        this.durationMs = durationMs.coerceIn(0, MAX_MS)
        this.easing = easing.coerceIn(0, EASINGS.lastIndex)
        prefs?.edit()?.putInt("toolbarMotionMs", this.durationMs)?.putInt("toolbarMotionEasing", this.easing)?.apply()
    }

    fun <T> spec(): androidx.compose.animation.core.AnimationSpec<T> {
        if (durationMs <= 0) return tween(0)
        return when (easing) {
            // A spring has no length, only a stiffness; this one settles in
            // about the chosen time (4 / (damping x natural frequency)).
            3 -> {
                val omega = 4f / (SPRING_DAMPING * durationMs / 1000f)
                spring(dampingRatio = SPRING_DAMPING, stiffness = omega * omega)
            }
            1 -> tween(durationMs, easing = EASE_OUT)
            2 -> tween(durationMs, easing = androidx.compose.animation.core.LinearEasing)
            else -> tween(durationMs, easing = EASE_IN_OUT)
        }
    }

    private const val SPRING_DAMPING = 0.78f
    // Cubic in-out: starts gently, gathers, and settles as gently as it began.
    private val EASE_IN_OUT = androidx.compose.animation.core.CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    // Quintic out: quick off the mark, long soft landing.
    private val EASE_OUT = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
}

/**
 * Height that follows its content on [ToolbarMotion]'s curve. Not
 * animateContentSize: that clips, and took the corner shadows with it.
 */
@Composable
private fun Modifier.toolbarAnimatedHeight(enabled: Boolean): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    val height = remember { Animatable(0f) }
    val started = remember { booleanArrayOf(false) }
    return layout { measurable, constraints ->
        val child = measurable.measure(constraints.copy(minHeight = 0))
        val target = child.height.toFloat()
        if (!started[0]) {
            started[0] = true
            scope.launch { height.snapTo(target) }
        } else if (height.targetValue != target) {
            scope.launch { height.animateTo(target, ToolbarMotion.spec()) }
        }
        val shown = if (height.value <= 0f) child.height else height.value.roundToInt()
        layout(child.width, shown.coerceIn(constraints.minHeight, constraints.maxHeight)) { child.placeRelative(0, 0) }
    }
}

internal fun spotiToolbarWidth(level: Int) = when (level) {
    0 -> 940.dp
    1 -> 632.dp
    2 -> 304.dp
    else -> 92.dp
}

internal fun Modifier.spotiToolbarAnimatedWidth(width: State<Dp>): Modifier = layout { measurable, constraints ->
    val pixels = width.value.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
    val child = measurable.measure(constraints.copy(minWidth = pixels, maxWidth = pixels))
    layout(child.width, child.height) { child.placeRelative(0, 0) }
}

/** Keep a complete row visible, including hit testing, on a narrower canvas. */
internal fun Modifier.spotiFitRow(minWidth: Dp): Modifier = layout { measurable, constraints ->
    val available = constraints.maxWidth
    val logical = maxOf(available, minWidth.roundToPx())
    val scale = available.toFloat() / logical
    val child = measurable.measure(constraints.copy(minWidth = logical, maxWidth = logical, minHeight = 0))
    layout(available, (child.height * scale).toInt()) {
        child.placeWithLayer(0, 0) {
            scaleX = scale; scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

/** Responsive rows preserve every tool and a 44 dp hit area without scrolling. */
internal fun spotiToolRows(tools: List<SpotiToolbarTool>, widthDp: Float): List<List<SpotiToolbarTool>> {
    if (tools.isEmpty()) return emptyList()
    val capacity = ((widthDp - 12f) / 44f).toInt().coerceAtLeast(2)
    val rows = (tools.size + capacity - 1) / capacity
    // Balance the rows instead of leaving almost all the tools in the first.
    var start = 0
    return List(rows) { row ->
        val count = tools.size / rows + if (row < tools.size % rows) 1 else 0
        tools.subList(start, start + count).also { start += count }
    }
}

/** Use the existing Liquid Glass skin optics for the enclosing chrome. */
@Composable
private fun Modifier.spotiToolbarGlass(circle: Boolean = false): Modifier {
    val look = LocalSkinSettings.current
    val shape = if (circle) CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(look.spotiglassCorner.dp)
    val color = if (circle) Color(look.tint) else MaterialTheme.colorScheme.surface.copy(
        alpha = look.spotiglassToolbarOpacity.coerceIn(0f, 1f))
    return liquidGlass(shape = shape, settings = look, surfaceColor = color,
        shadowElevation = 3.dp, useSpotiStyle = false,
        surfaceOpacity = if (circle) null else look.spotiglassToolbarOpacity)
}
internal fun SpotiToolbarTool.icon(): ImageVector = when (this) {
    SpotiToolbarTool.READ -> AppIcons.read
    SpotiToolbarTool.PEN -> AppIcons.penTool
    SpotiToolbarTool.PENCIL -> AppIcons.pencilTool
    SpotiToolbarTool.HIGHLIGHTER -> AppIcons.highlighterTool
    SpotiToolbarTool.MASK -> AppIconStyle.pick(Lucide.EyeOff, Reicons.VisibilityOff)
    SpotiToolbarTool.ERASE -> AppIconStyle.pick(Lucide.Eraser, Reicons.Eraser)
    SpotiToolbarTool.SHAPE -> AppIconStyle.pick(Lucide.Shapes, Reicons.Category)
    SpotiToolbarTool.TEXT -> AppIconStyle.pick(Lucide.Type, Reicons.TextFields)
    SpotiToolbarTool.LASSO -> AppIconStyle.pick(Lucide.CircleDashed, Reicons.Gesture)
    SpotiToolbarTool.CAPTURE -> AppIconStyle.pick(Lucide.SquareDashed, Reicons.CropFree)
    SpotiToolbarTool.AI -> AppIconStyle.pick(Lucide.Zap, Reicons.AutoAwesome)
}

internal data class SpotiToolbarAction(
    val label: String, val icon: ImageVector, val enabled: Boolean = true,
    val selected: Boolean = false,
    /** Why the action cannot be used right now; shown under its label while it is disabled. */
    val disabledReason: String? = null,
    /** Opens the list of other notes inside the menu rather than acting and closing it. */
    val opensNotes: Boolean = false,
    val onClick: () -> Unit,
)

/** Apply the original optics during a size/menu change, then hand back to frost.
 * The lens captures the surface and its contents; it never records the page. */
@Composable
internal fun Modifier.spotiGlassMorph(key: Any, enter: Boolean = false): Modifier {
    if (LocalSkin.current != Skin.SPOTIGLASS) return this
    val look = LocalSkinSettings.current
    val effects = rememberLiquidGlassEffectsAllowed() && !look.highContrast &&
        look.spotiglassResponse > 0f && look.spotiglassClarity > 0f
    val phase = remember { Animatable(if (enter) 0f else 1f) }
    var initial by remember { mutableStateOf(true) }
    LaunchedEffect(key, effects) {
        if (effects && (enter || !initial)) {
            phase.snapTo(0f)
            phase.animateTo(1f, tween(460))
        } else phase.snapTo(1f)
        initial = false
    }
    fun raised() = sin(PI * phase.value).toFloat().coerceIn(0f, 1f)
    return spotiGlassLens(effects, active = { raised() > 0.002f }) { size, density ->
        val raised = raised()
        SpotiGlassLensFrame(center = Offset(size.width / 2, size.height / 2), size = size,
            radius = look.spotiglassCorner * density, progress = raised, presence = raised,
            distortion = 0.04f * (look.refraction / 24) * look.spotiglassResponse,
            band = look.depth, dispersion = 0.002f * (look.dispersion / 0.35f))
    }
}


@Composable
private fun SpotiActionButton(
    label: String, icon: ImageVector? = null, modifier: Modifier = Modifier,
    selected: Boolean = false, enabled: Boolean = true, iconOnly: Boolean = false,
    persistentGlass: Boolean = false, plain: Boolean = false,
    /** Why it is disabled; shown under the label and read out with it. */
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    // Each tap plays the icon once, where the icon set has motion.
    var taps by remember { mutableIntStateOf(0) }
    val tapped = { taps++; onClick() }
    val reason = subtitle.takeIf { !enabled }
    val described = Modifier.semantics {
        contentDescription = label
        if (reason != null) stateDescription = reason
    }
    if (plain || LocalSkin.current != Skin.SPOTIGLASS) {
        val scheme = MaterialTheme.colorScheme
        // A button: the control corner, whichever skin draws it.
        val shape = Ui.Shape.control
        Box(modifier.sizeIn(minWidth = Ui.Size.touchCompact, minHeight = Ui.Size.touchCompact)
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh, shape)
            .then(described)
            .clickable(enabled = enabled, role = Role.Button, onClick = tapped),
            contentAlignment = Alignment.Center) {
            Column(Modifier.padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs), horizontalAlignment = Alignment.CenterHorizontally) {
                val color = (if (selected) scheme.onPrimaryContainer else scheme.onSurface)
                    .copy(alpha = if (enabled) 1f else 0.38f)
                icon?.let { AppIcon(it, null, Modifier.size(Ui.Icon.small), tint = color, play = taps) }
                if (!iconOnly) ActionLabels(label, reason, color)
            }
        }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    val shape = SpotiGlassShape(Ui.Corner.control)
    val look = LocalSkinSettings.current
    val effects = rememberLiquidGlassEffectsAllowed() && !look.highContrast && look.spotiglassResponse > 0f
    // Only a trigger lifts on press, in the same rest -> lifted -> rest cycle as
    // the tool pill; an ordinary button stays flat.
    val grow by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && effects && persistentGlass) 1f + 0.08f * look.spotiglassResponse else 1f,
        if (effects) spring(0.78f, 430f) else tween(0), label = "Action button lift")
    Box(modifier.sizeIn(minWidth = Ui.Size.touchCompact, minHeight = Ui.Size.touchCompact)
        .then(described)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = tapped),
        contentAlignment = Alignment.Center) {
        val body = Modifier.matchParentSize().graphicsLayer { scaleX = grow; scaleY = grow }
        Box(if (persistentGlass) body.spotiToolbarGlass(circle = true)
            else body.background(if (selected) scheme.primary.copy(alpha = 0.12f) else scheme.onSurface.copy(alpha = 0.06f), shape))
        Column(Modifier.padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs),
            horizontalAlignment = Alignment.CenterHorizontally) {
            val color = (if (selected) scheme.primary else scheme.onSurface).copy(alpha = if (enabled) 1f else 0.38f)
            icon?.let { AppIcon(it, null, Modifier.size(Ui.Icon.small), tint = color, play = taps) }
            if (!iconOnly) ActionLabels(label, reason, color)
        }
    }
}

@Composable
private fun ActionLabels(label: String, reason: String?, color: Color) {
    Text(label, color = color, style = MaterialTheme.typography.labelSmall,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (reason != null) Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

private enum class ToolbarMenu { FUNCTIONS, NOTES, AI }

internal class SpotiToolbarPopupPosition(private val atStart: Boolean, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = atStart == (layoutDirection == LayoutDirection.Ltr)
        val desiredX = if (left) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val maxX = (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - popupContentSize.height - gap
        val maxY = (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap)
        val y = when {
            below <= maxY -> below
            above >= gap -> above
            else -> below.coerceIn(gap, maxY)
        }
        return IntOffset(desiredX.coerceIn(gap, maxX), y)
    }
}

/**
 * The bar in its four sizes, and the function menu behind its button. The
 * menu holds the size switch and [functions], one group to a block; settings
 * live in their own sidebar, reached from the last group.
 */
@Composable
internal fun SpotiGlassToolbar(
    sizeLevel: Int, onSizeLevel: (Int) -> Unit, mode: EditMode,
    toolColors: Map<EditMode, Color>,
    targetWidth: Dp,
    docked: Boolean, modifier: Modifier, onDrag: (Offset) -> Unit,
    onTool: (SpotiToolbarTool) -> Unit,
    functions: List<List<SpotiToolbarAction>>,
    notes: List<SpotiToolbarAction>, ai: List<SpotiToolbarAction>,
    topRow: @Composable () -> Unit, penOptions: @Composable (Dp) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(ToolbarMenu.FUNCTIONS) }
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = open
    fun menu(next: ToolbarMenu) { page = next; open = true }
    fun pick(tool: SpotiToolbarTool) {
        if (tool == SpotiToolbarTool.AI) menu(ToolbarMenu.AI)
        else { open = false; onTool(tool) }
    }
    val config = currentToolbarConfig()
    val skin = LocalSkin.current
    val effects = skin != Skin.MATERIAL && rememberLiquidGlassEffectsAllowed() && !LocalSkinSettings.current.highContrast
    val popupBackdrop = LocalSpotiPopupBackdrop.current ?: LocalLiquidGlassBackdrop.current
    // animateContentSize clips its layer, including stable corner shadows.
    // Width already has one animation clock in the host; do not animate twice.
    // The new size's contents come up out of a fade while the frame moves,
    // instead of being swapped in whole on the first frame.
    val fade = remember { Animatable(1f) }
    val firstLevel = remember { intArrayOf(sizeLevel) }
    LaunchedEffect(sizeLevel) {
        if (firstLevel[0] == sizeLevel) return@LaunchedEffect
        firstLevel[0] = -1
        if (!effects || ToolbarMotion.durationMs <= 0) return@LaunchedEffect
        fade.snapTo(0.25f)
        fade.animateTo(1f, ToolbarMotion.spec())
    }
    ThemedToolbarFrame(skin, modifier) {
        if (sizeLevel == 0 && skin == Skin.SPOTIGLASS) Box(Modifier.matchParentSize().spotiToolbarGlass())
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified) {
            Column(Modifier.toolbarAnimatedHeight(effects && ToolbarMotion.durationMs > 0)
                .graphicsLayer { alpha = fade.value }
                .then(if (docked && sizeLevel == 0) Modifier.windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) else Modifier)
                .padding(horizontal = 8.dp, vertical = 2.dp)) {
                if (sizeLevel == 0) {
                    Box(Modifier.fillMaxWidth().spotiFitRow(800.dp)) { topRow() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Box {
                    if (sizeLevel == ToolbarConfig.BUTTON_ONLY_STAGE) {
                        SpotiActionButton("Tools and functions", AppIcons.functions,
                            Modifier.size(52.dp).pointerInput(Unit) {
                                detectDragGestures { change, delta -> change.consume(); onDrag(delta) }
                            }, iconOnly = true, persistentGlass = true, onClick = { menu(ToolbarMenu.FUNCTIONS) })
                    } else {
                        Row(Modifier.fillMaxWidth().then(if (sizeLevel == 0) Modifier.spotiFitRow(800.dp) else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(if (sizeLevel == 0) Ui.Space.xs else Ui.Space.s)) {
                            Box(Modifier.weight(1f)) {
                              // Freeze wrapping at the destination width while the
                              // spring grows the bar; avoid 6 -> 3 -> 2 -> 1 rows.
                              val toolWidth = (targetWidth.value - 100f).coerceAtLeast(100f)
                              Column {
                                val shown = config.tools(sizeLevel)
                                val rows = if (sizeLevel == 0) listOf(shown).filter { it.isNotEmpty() }
                                    else spotiToolRows(shown, toolWidth)
                                rows.forEach { tools ->
                                  if (skin == Skin.SPOTIGLASS) SpotiGlassBar(tools.map { SpotiGlassItem(it.label, it.icon(), toolColors[it.mode]) },
                                    selectedIndex = if (open && page == ToolbarMenu.AI) tools.indexOf(SpotiToolbarTool.AI)
                                        else tools.indexOfFirst { it.mode == mode },
                                    onSelected = { pick(tools[it]) }, modifier = Modifier.fillMaxWidth(),
                                    showLabels = false, compact = sizeLevel == 0)
                                  else ThemedToolbarTools(tools, mode, toolColors, sizeLevel == 0, ::pick)
                                }
                              }
                            }
                            if (sizeLevel == 0) Box(Modifier.width(360.dp)) { penOptions(360.dp) }
                            SpotiActionButton("Functions", AppIcons.functions, selected = open,
                                modifier = Modifier.size(if (sizeLevel == 0) Ui.Size.touchCompact else 52.dp), iconOnly = true,
                                persistentGlass = sizeLevel != 0, plain = sizeLevel == 0,
                                onClick = { menu(ToolbarMenu.FUNCTIONS) })
                        }
                    }
                    if (visible.currentState || visible.targetState) {
                        val maxHeight = (LocalConfiguration.current.screenHeightDp - 140).coerceAtLeast(180).dp
                        val gap = with(androidx.compose.ui.platform.LocalDensity.current) { Ui.Space.s.roundToPx() }
                        val position = remember(gap) { SpotiToolbarPopupPosition(false, gap) }
                        Popup(popupPositionProvider = position,
                            onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                          androidx.compose.animation.AnimatedVisibility(visibleState = visible,
                            enter = if (effects) fadeIn(tween(140)) + scaleIn(tween(220), initialScale = 0.86f) else EnterTransition.None,
                            exit = if (effects) fadeOut(tween(150)) + scaleOut(tween(170), targetScale = 0.9f) else ExitTransition.None) {
                            // This surface has its own page capture; do not sample
                            // the parent toolbar's exported frost across windows.
                            CompositionLocalProvider(LocalSpotiGlassHostBackdrop provides null,
                                LocalLiquidGlassBackdrop provides popupBackdrop) {
                                SkinSurface(Modifier.width(minOf(340.dp,
                                    (LocalConfiguration.current.screenWidthDp - 24).coerceAtLeast(140).dp)).heightIn(max = maxHeight)
                                    .spotiGlassMorph(page to open, enter = true)
                                    .animateContentSize(if (effects) spring(0.86f, 430f) else tween(0))) {
                                    if (skin != Skin.MATERIAL) BlurBehind()
                                    Column(Modifier.verticalScroll(rememberScrollState()).padding(Ui.Space.m),
                                        verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(when (page) {
                                                ToolbarMenu.FUNCTIONS -> "Functions"
                                                ToolbarMenu.NOTES -> "Notes"
                                                ToolbarMenu.AI -> "AI"
                                            }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                            IconButton(onClick = { open = false }, Modifier.size(Ui.Size.touchCompact)) {
                                                AppIcon(AppIcons.close, "Close menu")
                                            }
                                        }
                                        when (page) {
                                            ToolbarMenu.FUNCTIONS -> {
                                                Text("Toolbar size", style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                ToolbarStageSelector(sizeLevel, onSelect = { open = false; onSizeLevel(it) })
                                                // The button-only bar has no tools of its own to hand,
                                                // so its menu carries the compact size's.
                                                val quick = if (sizeLevel == ToolbarConfig.BUTTON_ONLY_STAGE) config.tools(2) else emptyList()
                                                if (quick.isNotEmpty()) {
                                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                                    SpotiMenuGrid(quick.map { tool ->
                                                        SpotiToolbarAction(tool.label, tool.icon(), selected = tool.mode == mode) { pick(tool) }
                                                    }, columns = 2)
                                                }
                                                functions.forEach { group ->
                                                    if (group.isNotEmpty()) {
                                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                                        SpotiMenuGrid(group, columns = when (group.size) {
                                                            1 -> 1
                                                            2, 4 -> 2
                                                            else -> 3
                                                        }, onAction = { action ->
                                                            if (action.opensNotes) page = ToolbarMenu.NOTES
                                                            else { open = false; action.onClick() }
                                                        })
                                                    }
                                                }
                                            }
                                            ToolbarMenu.NOTES, ToolbarMenu.AI -> {
                                                val entries = if (page == ToolbarMenu.NOTES) notes else ai
                                                if (entries.isEmpty()) Text("No other notes yet",
                                                    style = MaterialTheme.typography.bodyMedium)
                                                SpotiMenuGrid(entries, onAction = { open = false; it.onClick() })
                                                TextButton(onClick = { page = ToolbarMenu.FUNCTIONS },
                                                    modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) { Text("Back to functions") }
                                            }
                                        }
                                    }
                                }
                            }
                          }
                        }
                    }
                }
            }
        }
    }
}

/** The bar's switch between sizes: its length, and the curve it moves on. */
@Composable
internal fun ToolbarMotionSettings() {
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        Text(
            if (ToolbarMotion.durationMs == 0) "Instant switch" else "Switch time ${ToolbarMotion.durationMs} ms",
            style = MaterialTheme.typography.bodyMedium,
        )
        SkinSlider(
            value = ToolbarMotion.durationMs.toFloat(),
            onValueChange = { ToolbarMotion.update(durationMs = it.roundToInt()) },
            valueRange = 0f..ToolbarMotion.MAX_MS.toFloat(),
            step = 20f,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            ToolbarMotion.EASINGS.forEachIndexed { index, label ->
                SettingsChoiceChip(
                    selected = ToolbarMotion.easing == index,
                    onClick = { ToolbarMotion.update(easing = index) },
                    label = label,
                )
            }
        }
    }
}

@Composable
private fun ThemedToolbarFrame(skin: Skin, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    if (skin == Skin.SPOTIGLASS) Box(modifier, content = content)
    // The bar takes the floating-chrome corner: Material's card radius, and
    // under glass the skin's own corner setting, like every other bar.
    else if (skin == Skin.MATERIAL) Surface(modifier, shape = Ui.Shape.card,
        color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 0.dp, tonalElevation = 0.dp) {
        Box(content = content)
    } else SkinSurface(modifier) { Box(content = content) }
}

@Composable
private fun ThemedToolbarTools(tools: List<SpotiToolbarTool>, mode: EditMode,
    colors: Map<EditMode, Color>, compact: Boolean, onTool: (SpotiToolbarTool) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(if (compact) 56.dp else 64.dp)
        // Nested one step down from the bar, and the selected cell one step
        // further: an inner radius larger than its container read as a
        // second, unrelated shape inside the bar.
        .background(scheme.onSurface.copy(alpha = 0.06f), Ui.Shape.control)
        .padding(Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
        for (tool in tools) {
            val chosen = tool.mode == mode
            Box(Modifier.weight(1f).fillMaxHeight()
                .clip(Ui.Shape.chip)
                .background(if (chosen) scheme.primaryContainer else Color.Transparent,
                    Ui.Shape.chip)
                .clickable(role = Role.Tab, onClick = { onTool(tool) })
                .semantics {
                    contentDescription = tool.label
                    selected = chosen
                }, contentAlignment = Alignment.Center) {
                AppIcon(tool.icon(), null, Modifier.size(Ui.Icon.small), play = chosen,
                    tint = colors[tool.mode] ?: if (chosen) scheme.onPrimaryContainer else scheme.onSurface)
            }
        }
    }
}

/** Buttons [columns] to a row, the last row padded so every button keeps one width. */
@Composable
internal fun SpotiMenuGrid(
    actions: List<SpotiToolbarAction>,
    columns: Int = 2,
    onAction: (SpotiToolbarAction) -> Unit = { it.onClick() },
) {
    val across = columns.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        actions.chunked(across).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                row.forEach { action ->
                    SpotiActionButton(action.label, action.icon, Modifier.weight(1f), action.selected, action.enabled,
                        subtitle = action.disabledReason) { onAction(action) }
                }
                repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
