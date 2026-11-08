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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.snap
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
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
import androidx.compose.ui.layout.boundsInRoot
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
import kotlinx.coroutines.coroutineScope
import kotlin.math.sin
import com.kyant.backdrop.drawBackdrop

internal enum class GlassToolbarTool(val mode: EditMode?, val label: String) {
    READ(EditMode.READ, "Read"), PEN(EditMode.PEN, "Pen"), PENCIL(EditMode.PENCIL, "Pencil"),
    HIGHLIGHTER(EditMode.HIGHLIGHTER, "Highlighter"), MASK(EditMode.MASK, "Mask"),
    ERASE(EditMode.ERASE, "Eraser"), SHAPE(EditMode.SHAPE, "Shape"), TEXT(EditMode.TEXT, "Text"),
    LASSO(EditMode.LASSO, "Lasso"), CAPTURE(EditMode.CAPTURE, "Capture"), AI(null, "AI"),
}

/** The tools the bar shows at [level], as arranged in [ToolbarLayout]. */
internal fun glassToolbarTools(level: Int): List<GlassToolbarTool> = ToolbarLayout.config.tools(level)

/**
 * How the bar moves between its four sizes: how long it takes and the curve
 * it takes it on. Kept on the device and read as state, so a change in the
 * menu shows on the very next switch. Every look moves.
 */
/**
 * Whether the bar runs across the top or down the side. Kept for every
 * note, as where the bar sits is a habit. The whole bar (1/4) always runs
 * across: its top row and pen row are rows.
 */
/**
 * The size the note's chrome - the bars, their icons, the Functions button
 * and the way back - is drawn at: [ToolbarScale]'s, where it is provided;
 * 1 elsewhere. The whole bar (1/4) takes it as one row, in glassFitRow.
 */
internal val LocalChromeScale = staticCompositionLocalOf { 1f }

/** How big the bars are drawn, as the reader sets it. Kept between runs. */
internal object ToolbarScale {
    private var prefs: android.content.SharedPreferences? = null

    const val MIN = 0.9f
    const val MAX = 1.4f
    const val STEP = 0.05f

    /** Until the reader picks one: a touch under full size, which a small tablet needs and a large one does not miss. */
    const val DEFAULT = 0.9f

    var scale by mutableFloatStateOf(DEFAULT)
        private set

    fun init(context: android.content.Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("pens", android.content.Context.MODE_PRIVATE)
        prefs = p
        scale = p.getFloat(KEY, DEFAULT).coerceIn(MIN, MAX)
    }

    fun choose(value: Float) {
        // In whole steps, so the slider's stops are what is kept.
        scale = (kotlin.math.round(value / STEP) * STEP).coerceIn(MIN, MAX)
        prefs?.edit()?.putFloat(KEY, scale)?.apply()
    }

    private const val KEY = "toolbarScale"
}

/** The height of a tool strip's capsule, and so of every round button beside it. */
@Composable
internal fun barButtonSize(): Dp = 48.dp * LocalChromeScale.current

/**
 * Where the strip's capsule starts below the top of the smaller bar's box:
 * the bar's own inset and the strip's margin round its capsule. The way back
 * sits this far down, so it lines up with the bar.
 */
internal val BAR_CAPSULE_TOP = 2.dp + 8.dp

/** Across the side bar, the same: its inset and the strip's margin. */
internal val BAR_CAPSULE_SIDE = 8.dp + 8.dp

internal object ToolbarOrientation {
    private var prefs: android.content.SharedPreferences? = null

    var vertical by mutableStateOf(false)
        private set

    /** Down the side: true against the right edge, false against the left. */
    var right by mutableStateOf(false)
        private set

    fun init(context: android.content.Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("pens", android.content.Context.MODE_PRIVATE)
        prefs = p
        vertical = p.getBoolean(KEY_VERTICAL, false)
        right = p.getBoolean(KEY_RIGHT, false)
    }

    fun set(vertical: Boolean) {
        this.vertical = vertical
        prefs?.edit()?.putBoolean(KEY_VERTICAL, vertical)?.apply()
    }

    fun setSide(right: Boolean) {
        this.right = right
        prefs?.edit()?.putBoolean(KEY_RIGHT, right)?.apply()
    }

    private const val KEY_VERTICAL = "toolbarVertical"
    private const val KEY_RIGHT = "toolbarRight"
}

/**
 * "Horizontal" and "Vertical", for the menu and the appearance screen; there
 * also which side, and how big the bar is drawn. In the menu the side and the
 * size are the Functions button's own: dragged across the screen it takes the
 * bar to that side, dragged along the bar it makes it longer or shorter.
 * [sizeLevel] 0 cannot turn.
 */
@Composable
internal fun ToolbarOrientationChoice(sizeLevel: Int, inMenu: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        Text("Direction", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        SettingsChoiceChip(selected = !ToolbarOrientation.vertical, onClick = { ToolbarOrientation.set(false) }, label = "Horizontal")
        SettingsChoiceChip(selected = ToolbarOrientation.vertical, onClick = { ToolbarOrientation.set(true) }, label = "Vertical")
    }
    if (ToolbarOrientation.vertical && !inMenu) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            Text("Side", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            SettingsChoiceChip(selected = !ToolbarOrientation.right, onClick = { ToolbarOrientation.setSide(false) }, label = "Left")
            SettingsChoiceChip(selected = ToolbarOrientation.right, onClick = { ToolbarOrientation.setSide(true) }, label = "Right")
        }
    }
    if (sizeLevel == 0 && ToolbarOrientation.vertical) {
        Text("1/4 is the whole bar, with its top row and pen row, and always runs across. 2/4 to 4/4 run down the side.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    if (inMenu) {
        Text(
            if (ToolbarOrientation.vertical) "Drag the round button up or down for a shorter or longer bar. Let go across the screen to put the bar on that side, or in the middle to put it back across the top."
            else "Drag the round button left for a shorter bar, right for a longer one. Carry it to the screen's edge to stand the bar down that side.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        return
    }
    // How big the bars are drawn, with their buttons and the way back.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        Text("Scale", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SkinSlider(
            value = ToolbarScale.scale,
            onValueChange = { ToolbarScale.choose(it) },
            valueRange = ToolbarScale.MIN..ToolbarScale.MAX,
            step = ToolbarScale.STEP,
            modifier = Modifier.weight(1f),
        )
        Text("${(ToolbarScale.scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.widthIn(min = 40.dp))
    }
}

internal object ToolbarMotion {
    private var prefs: android.content.SharedPreferences? = null

    /** Long enough for the default spring to settle softly; see [spec]. */
    const val DEFAULT_MS = 260
    const val MAX_MS = 1000

    var durationMs by mutableIntStateOf(DEFAULT_MS)
        private set
    var curve by mutableStateOf(ToolbarCurve.DEFAULT)
        private set

    fun init(context: android.content.Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("pens", android.content.Context.MODE_PRIVATE)
        prefs = p
        // Once, as every motion went to one length: what was chosen before
        // becomes that length too; a choice made after is kept.
        if (!p.getBoolean(KEY_UNIFIED, false)) {
            p.edit().putInt(KEY_MS, DEFAULT_MS).putBoolean(KEY_UNIFIED, true).apply()
        }
        durationMs = p.getInt(KEY_MS, DEFAULT_MS).coerceIn(0, MAX_MS)
        // Stored as the curve's own number, which the older four kept when
        // the two newer were added; see [ToolbarCurve.stored].
        curve = ToolbarCurve.fromStored(p.getInt(KEY_CURVE, ToolbarCurve.DEFAULT.stored))
    }

    fun update(durationMs: Int = this.durationMs, curve: ToolbarCurve = this.curve) {
        this.durationMs = durationMs.coerceIn(0, MAX_MS)
        this.curve = curve
        prefs?.edit()?.putInt(KEY_MS, this.durationMs)?.putInt(KEY_CURVE, curve.stored)?.apply()
    }

    fun <T> spec(): androidx.compose.animation.core.AnimationSpec<T> {
        if (durationMs <= 0) return tween(0)
        return when (curve) {
            // A spring has no length, only a stiffness; this one settles in
            // about the chosen time (4 / (damping x natural frequency)).
            ToolbarCurve.SPRING -> {
                val omega = 4f / (SPRING_DAMPING * durationMs / 1000f)
                spring<T>(dampingRatio = SPRING_DAMPING, stiffness = omega * omega).paced()
            }
            ToolbarCurve.SINE_IN_OUT -> tween(durationMs, easing = SineInOutEasing)
            ToolbarCurve.QUAD_IN_OUT -> tween(durationMs, easing = QuadInOutEasing)
            ToolbarCurve.CUBIC_IN_OUT -> tween(durationMs, easing = CUBIC_IN_OUT)
            ToolbarCurve.EASE_OUT -> tween(durationMs, easing = EASE_OUT)
            ToolbarCurve.LINEAR -> tween(durationMs, easing = androidx.compose.animation.core.LinearEasing)
        }
    }

    private const val KEY_MS = "toolbarMotionMs"
    private const val KEY_UNIFIED = "toolbarMotionUnified180"
    private const val KEY_CURVE = "toolbarMotionEasing"
    // Barely under critical: the bar lands without a visible bounce.
    private const val SPRING_DAMPING = 0.92f
    // Cubic in-out: starts gently, gathers, and settles as gently as it began.
    private val CUBIC_IN_OUT = androidx.compose.animation.core.CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    // Quintic out: quick off the mark, long soft landing.
    private val EASE_OUT = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
}

/** [easeInOutSine] as a Compose curve. */
internal val SineInOutEasing = androidx.compose.animation.core.Easing { easeInOutSine(it) }

/** [easeInOutQuad] as a Compose curve. */
internal val QuadInOutEasing = androidx.compose.animation.core.Easing { easeInOutQuad(it) }

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

/**
 * The bar's width at each size; every size grows and shrinks with the chrome
 * ([LocalChromeScale]). The 2/4 and 3/4 bars widen past their own width to
 * hold [tools] tools in one row, see [glassToolRows].
 */
internal fun glassToolbarWidth(level: Int, scale: Float = 1f, tools: Int = 0): Dp = when (level) {
    0 -> 940.dp * scale
    1 -> maxOf(632.dp, oneRowWidth(tools)) * scale
    2 -> maxOf(304.dp, oneRowWidth(tools)) * scale
    else -> 92.dp * scale
}

/** A row of [tools] tool cells with its padding, beside the Functions button; a hair over, so rounding never folds it. */
private fun oneRowWidth(tools: Int): Dp = (BAR_BESIDE_TOOLS + TOOL_ROW_PAD + tools * TOOL_CELL + 4f).dp

/** What the bar holds beside its tools: the Functions button and the gaps round it. */
private const val BAR_BESIDE_TOOLS = 100f
private const val TOOL_ROW_PAD = 12f
/** A tool's least width in a row across, at chrome scale 1. */
private const val TOOL_CELL = 44f

internal fun Modifier.glassToolbarAnimatedWidth(width: State<Dp>): Modifier = layout { measurable, constraints ->
    val pixels = width.value.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
    val child = measurable.measure(constraints.copy(minWidth = pixels, maxWidth = pixels))
    layout(child.width, child.height) { child.placeRelative(0, 0) }
}

/**
 * Keep a complete row visible, including hit testing, on a narrower canvas;
 * drawn at [chrome] of its size besides, as the reader's toolbar scale says.
 */
internal fun Modifier.glassFitRow(minWidth: Dp, chrome: Float = 1f): Modifier = layout { measurable, constraints ->
    val available = constraints.maxWidth
    val logical = maxOf((available / chrome.coerceAtLeast(0.1f)).toInt(), minWidth.roundToPx())
    val scale = available.toFloat() / logical
    val child = measurable.measure(constraints.copy(minWidth = logical, maxWidth = logical, minHeight = 0))
    layout(available, (child.height * scale).toInt()) {
        child.placeWithLayer(0, 0) {
            scaleX = scale; scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

/**
 * Responsive rows preserve every tool and its hit area without scrolling. The
 * cell and padding are at chrome scale 1 unless given: a bar drawn smaller
 * has smaller cells, and folding it at the full-size cell gave a 3/4 bar two
 * rows with room to spare in one.
 */
internal fun glassToolRows(
    tools: List<GlassToolbarTool>, widthDp: Float, cellDp: Float = TOOL_CELL, padDp: Float = TOOL_ROW_PAD,
): List<List<GlassToolbarTool>> {
    if (tools.isEmpty()) return emptyList()
    val capacity = ((widthDp - padDp) / cellDp).toInt().coerceAtLeast(2)
    val rows = (tools.size + capacity - 1) / capacity
    // Balance the rows instead of leaving almost all the tools in the first.
    var start = 0
    return List(rows) { row ->
        val count = tools.size / rows + if (row < tools.size % rows) 1 else 0
        tools.subList(start, start + count).also { start += count }
    }
}

/**
 * Whether [tools], in a bar across only [room] wide, would fold onto more rows
 * than at the bar's own [width] - the same sum the bar does, beside its
 * Functions button. The 3/4 bar is two rows by design; only a window too
 * narrow for the bar as it is meant to be turns it down the side.
 */
internal fun glassBarWraps(tools: List<GlassToolbarTool>, room: Dp, width: Dp, scale: Float, base: Dp = width): Boolean {
    fun rows(across: Dp) = glassToolRows(tools, glassToolWidth(across, scale), TOOL_CELL * scale, TOOL_ROW_PAD * scale).size
    // Widened to keep its tools on one row, it may still fold where its own width would have.
    return rows(minOf(room, width)) > rows(minOf(base, width))
}

/** The room for tools in a bar [width] across, beside its Functions button. */
internal fun glassToolWidth(width: Dp, scale: Float): Float = (width.value - BAR_BESIDE_TOOLS * scale).coerceAtLeast(100f)

internal fun GlassToolbarTool.icon(): ImageVector = when (this) {
    GlassToolbarTool.READ -> AppIcons.read
    GlassToolbarTool.PEN -> AppIcons.penTool
    GlassToolbarTool.PENCIL -> AppIcons.pencilTool
    GlassToolbarTool.HIGHLIGHTER -> AppIcons.highlighterTool
    GlassToolbarTool.MASK -> AppIconStyle.pick(Lucide.EyeOff, Reicons.VisibilityOff)
    GlassToolbarTool.ERASE -> AppIconStyle.pick(Lucide.Eraser, Reicons.Eraser)
    GlassToolbarTool.SHAPE -> AppIconStyle.pick(Lucide.Shapes, Reicons.Category)
    GlassToolbarTool.TEXT -> AppIconStyle.pick(Lucide.Type, Reicons.TextFields)
    GlassToolbarTool.LASSO -> AppIconStyle.pick(Lucide.CircleDashed, Reicons.Gesture)
    GlassToolbarTool.CAPTURE -> AppIconStyle.pick(Lucide.SquareDashed, Reicons.CropFree)
    GlassToolbarTool.AI -> AppIconStyle.pick(Lucide.Zap, Reicons.AutoAwesome)
}

internal data class GlassToolbarAction(
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
internal fun Modifier.liquidGlassMorph(key: Any, enter: Boolean = false): Modifier {
    if (LocalSkin.current != Skin.LIQUID_GLASS) return this
    val look = LocalSkinSettings.current
    val effects = rememberLiquidGlassEffectsAllowed() && !look.highContrast &&
        look.liquidGlassResponse > 0f && look.liquidGlassClarity > 0f
    val phase = remember { Animatable(if (enter) 0f else 1f) }
    var initial by remember { mutableStateOf(true) }
    LaunchedEffect(key, effects) {
        if (effects && (enter || !initial)) {
            phase.snapTo(0f)
            phase.animateTo(1f, Ui.Motion.tween())
        } else phase.snapTo(1f)
        initial = false
    }
    fun raised() = sin(PI * phase.value).toFloat().coerceIn(0f, 1f)
    return liquidGlassLens(effects, active = { raised() > 0.002f }) { size, density ->
        val raised = raised()
        LiquidGlassLensFrame(center = Offset(size.width / 2, size.height / 2), size = size,
            radius = look.glassCorner * density, progress = raised, presence = raised,
            distortion = 0.04f * (look.refraction / 24) * look.liquidGlassResponse,
            band = look.depth, dispersion = 0.002f * (look.dispersion / 0.35f),
            // The pane under it is a round rectangle; see [LiquidGlassLensFrame.continuous].
            continuous = false)
    }
}


/**
 * A round button of the bar's own material standing on its own, as the
 * Functions button of the smaller bars does - for the way back to the
 * notes when the bar is too small to have a top row.
 */
@Composable
internal fun RoundBarButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassActionButton(label, icon, modifier.size(barButtonSize()), iconOnly = true, persistentGlass = true, onClick = onClick)
}

@Composable
private fun GlassActionButton(
    label: String, icon: ImageVector? = null, modifier: Modifier = Modifier,
    selected: Boolean = false, enabled: Boolean = true, iconOnly: Boolean = false,
    /** A round button of the bar's own material, standing on its own: the Functions button of the smaller bars. */
    persistentGlass: Boolean = false,
    /** Why it is disabled; shown under the label and read out with it. */
    subtitle: String? = null,
    /** Its corner; null takes the one its container hands down (see [NestedCorners]). */
    corner: Dp? = null,
    onClick: () -> Unit,
) {
    // Each tap plays the icon once, where the icon set and the skin have motion.
    var taps by remember { mutableIntStateOf(0) }
    val tapped = { taps++; onClick() }
    val reason = subtitle.takeIf { !enabled }
    val described = Modifier.semantics {
        contentDescription = label
        if (reason != null) stateDescription = reason
    }
    val skin = LocalSkin.current
    val glass = skin == Skin.LIQUID_GLASS
    val scheme = MaterialTheme.colorScheme
    val look = LocalSkinSettings.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Only a trigger lifts on press, in the same rest -> lifted -> rest cycle as
    // the tool pill; an ordinary button stays flat, and still skins never lift.
    val lifts = persistentGlass && rememberLiquidGlassEffectsAllowed() &&
        !look.highContrast && look.liquidGlassResponse > 0f
    // A recipe with its own press switched on grows (or shrinks) the button its way.
    val recipe = if (glass) look.pressRecipe() else null
    var buttonSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = LocalDensity.current.density
    val target = when {
        !pressed || !lifts -> 1f
        recipe != null -> recipe.scale(buttonSize.width.toFloat(), buttonSize.height.toFloat(), density)
        else -> 1f + 0.08f * look.liquidGlassResponse
    }
    val grow by androidx.compose.animation.core.animateFloatAsState(
        target,
        when {
            !lifts -> snap()
            recipe != null -> if (pressed) recipe.press else recipe.release
            else -> Ui.Motion.spring(0.78f)
        }, label = "Action button lift")
    val radius = corner ?: nestedCorner(Ui.Corner.control)
    // One shape family in every skin: the round corner the bar and its strip draw.
    val shape = RoundedCornerShape(radius)
    // Liquid glass tints its buttons into the glass they sit on; the other two
    // keep their own buttons, solid under Material and glassy under frost.
    val fill = when {
        glass -> if (selected) scheme.primary.copy(alpha = 0.12f) else scheme.onSurface.copy(alpha = 0.06f)
        selected -> scheme.primaryContainer
        else -> scheme.surfaceContainerHigh
    }
    // A round trigger shows its menu is open by its icon alone; a tile by its fill.
    val content = when {
        selected && (glass || persistentGlass) -> scheme.primary
        selected -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }.copy(alpha = if (enabled) 1f else 0.38f)
    Box(modifier.sizeIn(minWidth = Ui.Size.touchCompact, minHeight = Ui.Size.touchCompact)
        .onSizeChanged { buttonSize = it }
        .then(described)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction,
            // Glass answers a press with the lift; the still skins with the
            // skin's own indication, which shows focus and nothing else.
            indication = if (glass) null else LocalIndication.current, onClick = tapped),
        contentAlignment = Alignment.Center) {
        val body = Modifier.matchParentSize().graphicsLayer { scaleX = grow; scaleY = grow }
        val pane = if (persistentGlass) body.skinMaterial(CircleShape, role = ChromeRole.TOOLBAR) else body.background(fill, shape)
        // Liquid glass lights up under the finger, as Apple's does.
        Box(if (glass) pane.glassPressGlow(interaction, if (persistentGlass) CircleShape else shape) else pane)
        Column(Modifier.padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // On its own, a round button's glyph grows with it.
            icon?.let { AppIcon(it, null, Modifier.size(if (iconOnly) Ui.Icon.small * LocalChromeScale.current else Ui.Icon.small),
                tint = content, play = taps) }
            if (!iconOnly) ActionLabels(label, reason, content)
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

/**
 * Places a toolbar popup by the panel inside it: the content keeps [inset]
 * of clear room all round for the panel's shadow, and that room is let past
 * the gap and the window's edge rather than pushing the panel inward. See
 * [popupPlacement].
 */
internal class GlassToolbarPopupPosition(
    private val atStart: Boolean, private val gap: Int, private val inset: Int = 0,
    /** Beside the bar rather than under it: for a bar down the side. */
    private val beside: Boolean = false,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        if (beside) {
            val (x, y) = popupBeside(
                anchorBounds.left, anchorBounds.top, anchorBounds.right, anchorBounds.bottom,
                windowSize.width, windowSize.height, popupContentSize.width, popupContentSize.height, gap, inset,
            )
            return IntOffset(x, y)
        }
        val (x, y) = popupPlacement(
            anchorBounds.left, anchorBounds.top, anchorBounds.right, anchorBounds.bottom,
            windowSize.width, windowSize.height, popupContentSize.width, popupContentSize.height,
            gap, inset, alignLeft = atStart == (layoutDirection == LayoutDirection.Ltr),
        )
        return IntOffset(x, y)
    }
}

/**
 * The bar in its four sizes, and the function menu behind its button. The
 * menu holds the size switch and [functions], one group to a block; settings
 * live in their own sidebar, reached from the last group.
 *
 * Every skin builds the same bar: at the full size one surface holding the
 * top row, the tool strip, the pen options and the Functions button; at the
 * smaller sizes the tool strip as a capsule of its own beside a round
 * Functions button. The skin only decides what those surfaces are made of
 * (see [skinMaterial]); every look moves.
 */
@Composable
internal fun LiquidGlassToolbar(
    sizeLevel: Int, onSizeLevel: (Int) -> Unit, mode: EditMode,
    /** Where the note's tour finds the bar's parts; null for a preview of the bar. */
    coachTargets: CoachTargets? = null,
    toolColors: Map<EditMode, Color>,
    targetWidth: Dp,
    docked: Boolean, modifier: Modifier, onDrag: (Offset) -> Unit,
    onTool: (GlassToolbarTool) -> Unit,
    functions: List<List<GlassToolbarAction>>,
    notes: List<GlassToolbarAction>, ai: List<GlassToolbarAction>,
    topRow: @Composable () -> Unit, penOptions: @Composable (Dp) -> Unit,
    /** Whether the bar may run down the side when [ToolbarOrientation] says so; a preview of the tools keeps it across. */
    allowVertical: Boolean = true,
    /** Down the side whatever [ToolbarOrientation] says: the window is too narrow for one row across. */
    autoVertical: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(ToolbarMenu.FUNCTIONS) }
    fun menu(next: ToolbarMenu) { page = next; open = true }
    fun pick(tool: GlassToolbarTool) {
        if (tool == GlassToolbarTool.AI) menu(ToolbarMenu.AI)
        else { open = false; onTool(tool) }
    }
    val config = currentToolbarConfig()
    val skin = LocalSkin.current
    val look = LocalSkinSettings.current
    val motionAllowed = rememberLiquidGlassEffectsAllowed() && !look.highContrast
    // The bar's own morphs and fades; the popup's growth is its own question.
    val effects = motionAllowed
    val popupMotion = motionAllowed
    val popupBackdrop = LocalGlassPopupBackdrop.current ?: LocalLiquidGlassBackdrop.current
    // The one radius the bar is built from. Everything inside it - the strip,
    // its pill, the Functions button - is concentric with it.
    // The same in every skin (see [chromeCorner]), and so is its shape: a
    // round corner, the one outline Backdrop's lens takes.
    val outer = skin.tokens().corner
    val outline = RoundedCornerShape(outer)
    val full = sizeLevel == 0
    val chrome = LocalChromeScale.current
    // The smaller bars may run down the side; the whole bar is always across.
    val vertical = allowVertical && (ToolbarOrientation.vertical || autoVertical) && !full
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
    // Open grows the menu out of the button; close folds it back, and only
    // then is the popup taken away.
    val menuGrowth = rememberPopupGrowth(shown = false)
    var menuShown by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            menuShown = true
            menuGrowth.open(popupMotion)
        } else if (menuShown) {
            menuGrowth.close(popupMotion)
            menuShown = false
        }
    }
    // Where the Functions button is on screen, for a menu not opened by a tap.
    val functionsAt = remember { arrayOfNulls<Offset>(1) }
    val barPlaced = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Where the bar is on screen, so the activity can note each touch that
    // lands on it (see PenPopupAnchor.touchedScreen): a popup opened by that
    // touch - the pen panel, this menu - grows from there. Not a pointerInput
    // here: one over the bar's whole box took every touch in it, the clear
    // part beside a short row of tools included, and the pen could not write
    // next to the bar.
    DisposableEffect(Unit) { onDispose { if (coachTargets != null) PenPopupAnchor.barBounds = null } }
    Box(modifier
        .onGloballyPositioned {
            barPlaced[0] = it
            if (coachTargets != null && it.isAttached) {
                val topLeft = it.localToScreen(Offset.Zero)
                PenPopupAnchor.barBounds = androidx.compose.ui.geometry.Rect(topLeft,
                    androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
            }
        }) {
        // The full bar is one surface. The strip on it is content, not a
        // second pane of glass, so the page shows through both the same way.
        if (full) Box(Modifier.matchParentSize().skinMaterial(outline, role = ChromeRole.TOOLBAR))
        // The whole bar is scaled as one row (see glassFitRow), so nothing in it scales again.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
            LocalChromeScale provides if (full) 1f else chrome) {
            Column(Modifier.toolbarAnimatedHeight(effects && ToolbarMotion.durationMs > 0)
                .graphicsLayer { alpha = fade.value }
                .then(if (docked && full) Modifier.windowInsetsPadding(
                ChromeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) else Modifier)
                // An even inset all round the full bar's lower row, so the
                // strip's corners can follow the bar's.
                .padding(horizontal = TOOLBAR_INSET, vertical = if (full) 0.dp else 2.dp)) {
                if (full) {
                    Box(Modifier.fillMaxWidth().glassFitRow(800.dp, chrome)
                        .coachTargetWhileShown(coachTargets, CoachTarget.TOP_ROW)) { topRow() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Box {
                    // 4/4 is the Functions button alone. It is the same button as
                    // in the bigger bars - one place in the layout for every
                    // size - so a drag that starts on it carries on as the bar
                    // grows out of it, instead of being dropped the moment the
                    // tools appear beside it.
                    val buttonOnly = sizeLevel == ToolbarConfig.BUTTON_ONLY_STAGE
                    if (vertical) {
                        // Down the side: the tools as one capsule (or, when the
                        // screen is too short for them, side by side columns)
                        // with the Functions button under them.
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                            if (!buttonOnly) {
                            val shown = config.tools(sizeLevel)
                            val tallest = (LocalConfiguration.current.screenHeightDp - VERTICAL_BAR_RESERVE_DP).coerceAtLeast(160)
                            val columns = glassToolRows(shown, tallest.toFloat(), cellDp = 52f * LocalChromeScale.current)
                            Row(Modifier.coachTargetWhileShown(coachTargets, CoachTarget.TOOLS),
                                horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                                columns.forEach { tools ->
                                    val selected = if (open && page == ToolbarMenu.AI) tools.indexOf(GlassToolbarTool.AI)
                                        else tools.indexOfFirst { it.mode == mode }
                                    if (skin == Skin.LIQUID_GLASS) LiquidGlassBar(
                                        tools.map { LiquidGlassItem(it.label, it.icon(), toolColors[it.mode]) },
                                        selectedIndex = selected, onSelected = { pick(tools[it]) },
                                        showLabels = false, compact = false, corner = outer,
                                        surface = GlassBarSurface.LIQUID, vertical = true, role = ChromeRole.TOOLBAR)
                                    else ToolStrip(tools, selected, compact = false, corner = outer,
                                        ownSurface = true, toolColors = toolColors, onPick = ::pick, vertical = true)
                                }
                            }
                            }
                            GlassActionButton(if (buttonOnly) "Tools and functions" else "Functions", AppIcons.functions,
                                selected = open && !buttonOnly,
                                modifier = Modifier.size(barButtonSize())
                                    .onGloballyPositioned { functionsAt[0] = it.centerOnScreen() }
                                    .coachTargetWhileShown(coachTargets, CoachTarget.FUNCTIONS)
                                    // Held still first, the button alone picks the bar up and moves it.
                                    .functionsDrag(sizeLevel, vertical = true, onSizeLevel, onMove = if (buttonOnly) onDrag else null),
                                iconOnly = true, persistentGlass = true,
                                onClick = { menu(ToolbarMenu.FUNCTIONS) })
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().then(if (full) Modifier.glassFitRow(800.dp, chrome) else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(if (full) Ui.Space.xs else Ui.Space.s)) {
                            if (!buttonOnly) Box(Modifier.weight(1f).coachTargetWhileShown(coachTargets, CoachTarget.TOOLS)) {
                              // Freeze wrapping at the destination width while the
                              // spring grows the bar; avoid 6 -> 3 -> 2 -> 1 rows.
                              val toolWidth = glassToolWidth(targetWidth, LocalChromeScale.current)
                              // On the full bar the strip is inset in it; alone it is the bar.
                              val stripCorner = if (full) Ui.concentric(outer, TOOLBAR_INSET) else outer
                              Column {
                                val shown = config.tools(sizeLevel)
                                val rows = if (full) listOf(shown).filter { it.isNotEmpty() }
                                    else LocalChromeScale.current.let { s -> glassToolRows(shown, toolWidth, TOOL_CELL * s, TOOL_ROW_PAD * s) }
                                rows.forEach { tools ->
                                  val selected = if (open && page == ToolbarMenu.AI) tools.indexOf(GlassToolbarTool.AI)
                                      else tools.indexOfFirst { it.mode == mode }
                                  // The full bar's strip is drawn on the bar's own glass, but
                                  // its selection still rises into a lens while it moves, as
                                  // the smaller bars' does - kept inside the strip's height.
                                  if (skin == Skin.LIQUID_GLASS) LiquidGlassBar(tools.map { LiquidGlassItem(it.label, it.icon(), toolColors[it.mode]) },
                                    selectedIndex = selected,
                                    onSelected = { pick(tools[it]) }, modifier = Modifier.fillMaxWidth(),
                                    showLabels = false, compact = full, corner = stripCorner,
                                    surface = if (full) GlassBarSurface.NONE else GlassBarSurface.LIQUID,
                                    // On the whole bar (1/4) the strip has no glass of its own
                                    // for a lens to bend: the lens refracted only the clear
                                    // layer and the icons, and showed as a rim and smear
                                    // round the selection. The pill slides flat there.
                                    lift = !full, role = ChromeRole.TOOLBAR)
                                  else ToolStrip(tools, selected, compact = full, corner = stripCorner,
                                    ownSurface = !full, toolColors = toolColors, onPick = ::pick)
                                }
                              }
                            }
                            if (full) Box(Modifier.width(360.dp)
                                .coachTargetWhileShown(coachTargets, CoachTarget.PEN_ROW)) { penOptions(360.dp) }
                            GlassActionButton(if (buttonOnly) "Tools and functions" else "Functions", AppIcons.functions,
                                selected = open && !buttonOnly,
                                modifier = Modifier.size(if (full) Ui.Size.touchCompact else barButtonSize())
                                    .onGloballyPositioned { functionsAt[0] = it.centerOnScreen() }
                                    .coachTargetWhileShown(coachTargets, CoachTarget.FUNCTIONS)
                                    .then(if (allowVertical) Modifier.functionsDrag(sizeLevel, vertical = false, onSizeLevel,
                                        onMove = if (buttonOnly) onDrag else null) else Modifier),
                                iconOnly = true,
                                persistentGlass = !full,
                                corner = if (full) Ui.concentric(outer, TOOLBAR_INSET) else null,
                                onClick = { menu(ToolbarMenu.FUNCTIONS) })
                        }
                    }
                    if (menuShown) {
                        val maxHeight = (LocalConfiguration.current.screenHeightDp - 140).coerceAtLeast(180).dp
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val gap = with(density) { Ui.Space.s.roundToPx() }
                        val room = with(density) { POPUP_SHADOW_ROOM.roundToPx() }
                        val position = remember(gap, room, vertical) { GlassToolbarPopupPosition(false, gap, room, beside = vertical) }
                        // The touch that opened it - the button, or the AI tool -
                        // or else the button. Kept for the fold back as well.
                        val anchor = remember { PenPopupAnchor.recentTap() ?: functionsAt[0] }
                        // Not clipped to the screen: the clear room round the
                        // panel may hang past the edge, where only shadow falls.
                        Popup(popupPositionProvider = position,
                            onDismissRequest = { open = false },
                            properties = PopupProperties(focusable = true, clippingEnabled = false)) {
                            // This surface has its own page capture; do not sample
                            // the parent toolbar's exported frost across windows.
                            CompositionLocalProvider(LocalLiquidGlassHostBackdrop provides null,
                                LocalLiquidGlassBackdrop provides popupBackdrop) {
                              // Cut out of the button rather than scaled, its glass is drawn
                              // once and need not look at the page again on each frame.
                              val reveal = menuGrowth.profile?.reveal == true && popupMotion
                              ProvideMovingBackdrops(if (reveal) StillMotion else menuGrowth.motion) {
                                val paneModifier = Modifier.width(minOf(340.dp,
                                    (LocalConfiguration.current.screenWidthDp - 24).coerceAtLeast(140).dp)).heightIn(max = maxHeight)
                                val menuBody: @Composable () -> Unit = {
                                    // The menu's buttons sit one padding inside it, so
                                    // their corners are its corner moved in by that.
                                    NestedCorners(outer, Ui.Space.m) {
                                        // Inside the surface: animateContentSize clips,
                                        // and on the surface it took the shadow with it.
                                        // Without motion, no size animation at all. A zero-length
                                        // one left the menu at no height on Android 11 and 12,
                                        // where motion is always off: there, and wherever the
                                        // system's animations are off, the menu could be
                                        // tapped but never seen.
                                        Box(if (effects) Modifier.animateContentSize(Ui.Motion.spring(0.86f)) else Modifier) {
                                            ToolbarMenuContent(page, sizeLevel, mode, config, functions, notes, ai,
                                                onPage = { page = it }, onClose = { open = false },
                                                onSizeLevel = onSizeLevel, onPick = ::pick)
                                        }
                                    }
                                }
                                // The popup window ends where its content does, so the
                                // content keeps clear room round the panel for the
                                // shadow; the growth's layer holds that room too, so
                                // its fade does not cut the shadow at the panel's edge.
                                Box(Modifier.growOutOf({ anchor }, menuGrowth)
                                    .dismissInRoom(POPUP_SHADOW_ROOM) { open = false }
                                    .padding(POPUP_SHADOW_ROOM)) {
                                    if (reveal) {
                                        // Poured out of the button: the glass grows from a drop
                                        // and the menu on it stays its size, seen where the glass
                                        // has reached (see PopupReveal.kt). Its shadow is cast
                                        // outside the morph, whose lens layer would cut it off.
                                        Box(paneModifier.revealOutOf(menuGrowth, outer) { c -> anchor?.let { c.screenToLocal(it) } }
                                            .skinShadow(outline, panel = true)
                                            .then(if (skin == Skin.LIQUID_GLASS) Modifier.liquidGlassMorph(page) else Modifier)
                                            .skinMaterial(outline, panel = true, shadow = false, role = ChromeRole.POPUP)
                                            .clip(outline)) {
                                            if (skin != Skin.LIQUID_GLASS) BlurBehind()
                                            menuBody()
                                        }
                                    } else if (skin == Skin.LIQUID_GLASS) {
                                        // The menu is the same liquid glass as the bar it came
                                        // from, looking at the page through the lens. Its shadow
                                        // is cast outside the morph, whose lens layer would cut
                                        // it off at the panel's edge while it plays.
                                        Box(paneModifier.skinShadow(outline, panel = true)
                                            // Only between the menu's pages: on top of coming out of
                                            // the point, a lens ripple was one motion too many.
                                            .liquidGlassMorph(page)
                                            .skinMaterial(outline, panel = true, shadow = false, role = ChromeRole.POPUP)) { menuBody() }
                                    } else {
                                        SkinSurface(paneModifier, role = ChromeRole.POPUP) {
                                            if (skin != Skin.MATERIAL) BlurBehind()
                                            menuBody()
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

/**
 * Clear room a toolbar popup keeps round its panel, so the panel's shadow
 * falls inside the popup's window instead of being cut off at its edge.
 */
internal val POPUP_SHADOW_ROOM = 16.dp

/**
 * A touch in the clear room [room] wide round a popup's panel closes it, as a
 * touch past the popup's window does: to the person the room is page.
 */
private fun Modifier.dismissInRoom(room: Dp, onDismiss: () -> Unit): Modifier = pointerInput(room) {
    val inset = room.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val at = down.position
        if (at.x < inset || at.y < inset || at.x > size.width - inset || at.y > size.height - inset) onDismiss()
    }
}

/**
 * The Functions button, dragged. Across the screen it takes the bar through
 * its sizes - right for longer, toward 1/4, left for shorter. With a
 * [LocalBarLiveResize] the bar's width follows the finger, the button staying
 * under it, the tools change over halfway between two sizes, and let go the
 * bar settles on the nearest; without one (a preview) it steps a size for
 * every [FUNCTIONS_DRAG_STEP] the finger has gone from where it came down.
 * Carried on to the screen's left or right edge it stands the bar down that
 * side. Down the side, dragged up it makes the bar shorter and down longer;
 * let go across the screen it takes the bar to that side, or, let go in the
 * middle, back across the top. With [onMove], a press held still first picks
 * the bar up and moves it instead. A tap is still a tap: nothing is taken
 * until the finger has moved, or been held.
 *
 * The finger is followed in the window, not on the button: the bar changes
 * size under it, and the button moves with the bar.
 */
@Composable
private fun Modifier.functionsDrag(
    sizeLevel: Int, vertical: Boolean, onSizeLevel: (Int) -> Unit, onMove: ((Offset) -> Unit)? = null,
): Modifier {
    // Read through state: the gesture outlives the sizes it passes through.
    val level by rememberUpdatedState(sizeLevel)
    val resize by rememberUpdatedState(onSizeLevel)
    val move by rememberUpdatedState(onMove)
    val live by rememberUpdatedState(if (vertical) null else LocalBarLiveResize.current)
    val haptics = LocalHapticFeedback.current
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { placed[0] = it }.pointerInput(vertical) {
        val step = FUNCTIONS_DRAG_STEP.toPx()
        val across = FUNCTIONS_SIDE_DRAG.toPx()
        val edge = FUNCTIONS_EDGE.toPx()
        // Down the side the whole bar (1/4) is not a size it can take.
        val longest = if (vertical) 1 else 0
        awaitEachGesture {
            // Ahead of the button's own click, so a drag can keep the lift from opening the menu.
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            // In the window; the button's own coordinates move as the bar resizes.
            fun inWindow(local: Offset): Offset? = placed[0]?.takeIf { it.isAttached }?.localToRoot(local)
            fun windowWidth(): Float? = placed[0]?.takeIf { it.isAttached }?.findRootCoordinates()?.size?.width?.toFloat()
            val origin = inWindow(first.position) ?: return@awaitEachGesture
            // True once past the slop, false when let go first, null when held.
            suspend fun AwaitPointerEventScope.leaves(): Boolean {
                var travel = Offset.Zero
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == first.id } ?: return false
                    if (!change.pressed) return false
                    travel += change.positionChange()
                    if (travel.getDistance() > slop) return true
                }
            }
            val moved = if (move != null) withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { leaves() }
                else leaves()
            if (moved == false) return@awaitEachGesture
            val moving = moved == null
            if (moving) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val startLevel = level
            var last = origin
            var zone: FunctionsDropZone? = null
            // Across the top the bar's width follows the finger, and lets go onto the nearest size.
            val following = if (moving) null else live
            val startWidth = following?.widthOf(startLevel)?.value ?: 0f
            try { while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == first.id } ?: break
                // Taken, so lifting the finger after a drag does not also open the menu.
                change.consume()
                if (moving) {
                    if (!change.pressed) break
                    move?.invoke(change.positionChange())
                    continue
                }
                val at = inWindow(change.position) ?: last
                last = at
                val width = windowWidth() ?: break
                val travel = at - origin
                if (vertical) {
                    val sideways = kotlin.math.abs(travel.x) > across && kotlin.math.abs(travel.x) > kotlin.math.abs(travel.y)
                    val now = if (sideways) functionsDropZone(at.x, width) else null
                    if (!change.pressed) {
                        when (now) {
                            FunctionsDropZone.LEFT -> if (ToolbarOrientation.right) ToolbarOrientation.setSide(false)
                            FunctionsDropZone.RIGHT -> if (!ToolbarOrientation.right) ToolbarOrientation.setSide(true)
                            FunctionsDropZone.MIDDLE -> ToolbarOrientation.set(false)
                            null -> Unit
                        }
                        break
                    }
                    if (now != zone) {
                        zone = now
                        if (now != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    if (sideways) continue
                } else {
                    if (!change.pressed) break
                    // At the screen's edge, having been carried toward it: the bar goes down that side.
                    val toRight = travel.x > 0
                    val atEdge = kotlin.math.abs(travel.x) > step &&
                        (if (toRight) at.x >= width - edge else at.x <= edge)
                    if (atEdge) {
                        // A size the side takes, and one with tools to show there.
                        level.coerceIn(1, ToolbarConfig.BUTTON_ONLY_STAGE - 1).let { if (it != level) resize(it) }
                        ToolbarOrientation.setSide(toRight)
                        ToolbarOrientation.set(true)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // The bar is laid out again down the side; this button goes with it.
                        break
                    }
                    if (following != null) {
                        val sizes = longest..ToolbarConfig.BUTTON_ONLY_STAGE
                        val width = liveBarWidth(startWidth, travel.x.toDp().value, following.follow,
                            following.widthOf(ToolbarConfig.BUTTON_ONLY_STAGE).value, following.widthOf(longest).value)
                        following.onWidth(width.dp)
                        // The tools change over halfway between two sizes; the width never jumps.
                        val next = nearestBarSize(width, sizes) { following.widthOf(it).value }
                        if (next != level) {
                            resize(next)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        continue
                    }
                }
                val along = if (vertical) travel.y else travel.x
                val next = functionsDragLevel(startLevel, along, step, longest)
                if (next != level) {
                    resize(next)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            } } finally {
                // Let go, cancelled, or gone down the side: the bar settles on its size.
                following?.onWidth(null)
            }
        }
    }
}

/**
 * The bar turning between across the top and down the side, or crossing to
 * the other side: laid out at once in its new place, it is drawn starting
 * from where it was - moved back there and a little smaller - and travels to
 * its new place on [ToolbarMotion]'s curve as it fades up, instead of
 * vanishing from one edge and appearing at another. [key] is what it turns
 * on; off ([enabled] false) the bar simply goes there.
 */
@Composable
internal fun Modifier.toolbarTurnMorph(key: Any, enabled: Boolean): Modifier {
    val laidOut = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    val from = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    val lastKey = remember { arrayOf<Any?>(key) }
    // Read only while drawing, so setting it here costs no second composition.
    val progress = remember { mutableFloatStateOf(1f) }
    if (lastKey[0] != key) {
        lastKey[0] = key
        from[0] = if (enabled && ToolbarMotion.durationMs > 0) laidOut[0] else null
        if (from[0] != null) progress.floatValue = 0f
    }
    LaunchedEffect(key) {
        if (from[0] == null) return@LaunchedEffect
        try {
            androidx.compose.animation.core.animate(0f, 1f, animationSpec = ToolbarMotion.spec()) { value, _ ->
                progress.floatValue = value
            }
        } finally {
            progress.floatValue = 1f
            from[0] = null
        }
    }
    return onGloballyPositioned { laidOut[0] = it.boundsInRoot() }.graphicsLayer {
        val start = from[0] ?: return@graphicsLayer
        val now = laidOut[0] ?: return@graphicsLayer
        val p = progress.floatValue.coerceIn(0f, 1f)
        if (p >= 1f || now.width <= 0f || now.height <= 0f) return@graphicsLayer
        val back = 1f - p
        val smallest = minOf(start.width / now.width, start.height / now.height).coerceIn(0.55f, 1f)
        val scale = smallest + (1f - smallest) * p
        translationX = (start.center.x - now.center.x) * back
        translationY = (start.center.y - now.center.y) * back
        scaleX = scale
        scaleY = scale
        alpha = 0.3f + 0.7f * p
    }
}

/**
 * Chrome over the note put away upwards: [away] of 0 leaves it where it is,
 * 1 has it all the way off the top of the screen, whatever its height or how
 * far down it sits. Read while drawing only, so the slide recomposes nothing.
 */
@Composable
internal fun Modifier.awayUpwards(away: () -> Float): Modifier {
    val bottom = remember { floatArrayOf(0f) }
    return onGloballyPositioned { bottom[0] = it.boundsInRoot().bottom }.graphicsLayer {
        val amount = away().coerceIn(0f, 1f)
        if (amount <= 0f) return@graphicsLayer
        translationY = -(bottom[0] + 24.dp.toPx()) * amount
    }
}

/** Where a bar down the side lands when its Functions button is let go [x] across a window [width] wide. */
internal enum class FunctionsDropZone { LEFT, MIDDLE, RIGHT }

internal fun functionsDropZone(x: Float, width: Float): FunctionsDropZone = when {
    x < width * 0.35f -> FunctionsDropZone.LEFT
    x > width * 0.65f -> FunctionsDropZone.RIGHT
    else -> FunctionsDropZone.MIDDLE
}

/**
 * The size the bar takes with the Functions button dragged [along] its length
 * from where it was at [startLevel]: a size shorter for every [step] toward
 * the bar's start, longer for every step back, between [longest] and the
 * button alone. Counted from where the finger came down, so a bar that moves
 * as it resizes does not count twice.
 */
internal fun functionsDragLevel(startLevel: Int, along: Float, step: Float, longest: Int): Int =
    (startLevel - (along / step).toInt()).coerceIn(longest, ToolbarConfig.BUTTON_ONLY_STAGE)

/**
 * How the bar across the top follows its Functions button while it is
 * dragged: the bar's width at each size as it is laid out, how far the button
 * moves for each dp the bar grows (a half when the bar is centred, all of it
 * when it grows from a fixed start), and where to send the width the bar
 * should have now - null when the finger lifts and the bar is to settle on
 * its size.
 */
internal class BarLiveResize(
    val widthOf: (Int) -> Dp,
    val follow: Float,
    val onWidth: (Dp?) -> Unit,
)

/** The note's bar provides one; a preview of the bar steps through its sizes instead. */
internal val LocalBarLiveResize = compositionLocalOf<BarLiveResize?> { null }

/**
 * The width a bar dragged by its Functions button [travel] from where it
 * came down at [startWidth] follows, so the button stays under the finger,
 * between the bar's narrowest and widest.
 */
internal fun liveBarWidth(startWidth: Float, travel: Float, follow: Float, narrowest: Float, widest: Float): Float =
    (startWidth + travel / follow.coerceAtLeast(0.1f)).coerceIn(narrowest, widest)

/** The size whose width is nearest [width], among [sizes], each [widthOf]. */
internal fun nearestBarSize(width: Float, sizes: IntRange, widthOf: (Int) -> Float): Int =
    sizes.minBy { kotlin.math.abs(widthOf(it) - width) }

/** How far the Functions button is dragged across the screen for each size it steps through. */
private val FUNCTIONS_DRAG_STEP = 56.dp

/** How far across the screen the button of a bar down the side is dragged before it can be let go on another side. */
private val FUNCTIONS_SIDE_DRAG = 96.dp

/** How near the screen's edge the Functions button is carried to stand the bar down that side. */
private val FUNCTIONS_EDGE = 40.dp

/** Room a bar down the side leaves on the screen's height: its Functions button, and a margin above and below. */
private const val VERTICAL_BAR_RESERVE_DP = 150

/** How far in from the full bar's edge its lower row sits, all round. */
private val TOOLBAR_INSET = Ui.Space.s

private fun LayoutCoordinates.centerOnScreen(): Offset =
    localToScreen(Offset(size.width / 2f, size.height / 2f))

/** What the function menu shows, page by page. */
@Composable
private fun ToolbarMenuContent(
    page: ToolbarMenu, sizeLevel: Int, mode: EditMode, config: ToolbarConfig,
    functions: List<List<GlassToolbarAction>>,
    notes: List<GlassToolbarAction>, ai: List<GlassToolbarAction>,
    onPage: (ToolbarMenu) -> Unit, onClose: () -> Unit,
    onSizeLevel: (Int) -> Unit, onPick: (GlassToolbarTool) -> Unit,
) {
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Column(Modifier
        // A panel opened from the menu - Tools - grows out of the button tapped in it.
        .onGloballyPositioned { placed[0] = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                placed[0]?.takeIf { it.isAttached }?.let { PenPopupAnchor.tapped(it.localToScreen(down.position)) }
            }
        }
        .verticalScroll(rememberScrollState()).padding(Ui.Space.m),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when (page) {
                ToolbarMenu.FUNCTIONS -> "Functions"
                ToolbarMenu.NOTES -> "Notes"
                ToolbarMenu.AI -> "AI"
            }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, Modifier.size(Ui.Size.touchCompact)) {
                AppIcon(AppIcons.close, "Close menu")
            }
        }
        when (page) {
            ToolbarMenu.FUNCTIONS -> {
                Text("Toolbar size", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToolbarStageSelector(sizeLevel, onSelect = { onClose(); onSizeLevel(it) })
                ToolbarOrientationChoice(sizeLevel, inMenu = true)
                // The button-only bar has no tools of its own to hand,
                // so its menu carries the compact size's.
                val quick = if (sizeLevel == ToolbarConfig.BUTTON_ONLY_STAGE) config.tools(2) else emptyList()
                if (quick.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GlassMenuGrid(quick.map { tool ->
                        GlassToolbarAction(tool.label, tool.icon(), selected = tool.mode == mode) { onPick(tool) }
                    }, columns = 2)
                }
                functions.forEach { group ->
                    if (group.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        GlassMenuGrid(group, columns = when (group.size) {
                            1 -> 1
                            2, 4 -> 2
                            else -> 3
                        }, onAction = { action ->
                            if (action.opensNotes) onPage(ToolbarMenu.NOTES)
                            else { onClose(); action.onClick() }
                        })
                    }
                }
            }
            ToolbarMenu.NOTES, ToolbarMenu.AI -> {
                val entries = if (page == ToolbarMenu.NOTES) notes else ai
                if (entries.isEmpty()) Text("No other notes yet",
                    style = MaterialTheme.typography.bodyMedium)
                GlassMenuGrid(entries, onAction = { onClose(); it.onClick() })
                TextButton(onClick = { onPage(ToolbarMenu.FUNCTIONS) },
                    modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) { Text("Back to functions") }
            }
        }
    }
}

/**
 * How a popup opened from the toolbar comes out of the point that was tapped
 * and goes back into it: [width] and [height] run 0 to 1 together, on one
 * curve, with no overshoot. Two axes on two springs, one of them bouncing,
 * made the popup wobble as it arrived, and that was tiring to watch; one
 * shape drawn out of a point and drawn back in reads as a single motion.
 * Opening it shoots out and settles softly; closing it gathers speed into the
 * point. Material snaps.
 */
@Stable
/**
 * Lets the frame that first composes and draws something new go by, and the
 * one after it, before anything is timed from them.
 */
internal suspend fun awaitFirstFrames() {
    androidx.compose.runtime.withFrameNanos { }
    androidx.compose.runtime.withFrameNanos { }
}

internal class PopupGrowth(shown: Boolean) {
    val width = Animatable(if (shown) 1f else 0f)
    val height = Animatable(if (shown) 1f else 0f)

    /**
     * Read in a draw: the glass inside the popup redraws, and looks at the
     * page again from where it now is, on every frame either axis moves.
     */
    val motion: () -> Float = { width.value + height.value }

    /** A recipe's own way in and out (see [openProfile]); null is this app's. Set as the popup is composed. */
    var profile: OpenProfile? = null

    /** Whether the last move was a close, so the scale it is drawn at is the closing one. */
    var closing = false
        private set

    /**
     * Run just before a move starts, while the popup still stands as it is:
     * a revealing popup takes its picture here (see [revealOutOf]).
     */
    var prepare: (suspend () -> Unit)? = null

    /** Whether the popup is anywhere but fully open: on its way, or shut. */
    fun moving(w: Float, h: Float): Boolean = w < 1f || h < 1f || width.isRunning || height.isRunning

    suspend fun open(animate: Boolean) {
        closing = false
        // Opening from nothing: the popup's first frame - composing, measuring
        // and drawing all of it, its glass included - is the slow one, and a
        // curve started on it lost its first steps to that frame and jumped.
        // It goes out unseen; the curve starts on the frame after.
        if (animate && width.value == 0f && height.value == 0f) awaitFirstFrames()
        if (animate) runCatching { prepare?.invoke() }
        val spec = profile?.open ?: POPUP_OPEN
        // Opened again while closing: on from where it is, at the speed it has.
        val resumed = midFlight()
        move(1f, animate, spec, spec, if (resumed) Float.NaN else 0f,
            widthDelayMs = if (resumed) 0 else profile?.widthDelayMs ?: 0)
    }

    /** Neither shut nor fully open: a move was caught on its way. */
    private fun midFlight(): Boolean =
        width.isRunning || height.isRunning || width.value in 0.001f..0.999f || height.value in 0.001f..0.999f

    suspend fun close(animate: Boolean) {
        closing = true
        // Taken again from the open popup, which may have changed while it was open.
        if (animate && !midFlight()) runCatching { prepare?.invoke() }
        val spec = profile?.close ?: POPUP_CLOSE
        // Caught while still opening, it turns round at the speed it has
        // rather than being thrown back at the profile's own.
        val throwBack = if (midFlight()) Float.NaN else profile?.closeVelocity ?: 0f
        move(0f, animate, profile?.closeWidth ?: spec, spec, throwBack)
    }

    private suspend fun move(
        target: Float, animate: Boolean,
        widthSpec: androidx.compose.animation.core.AnimationSpec<Float>,
        heightSpec: androidx.compose.animation.core.AnimationSpec<Float>,
        velocity: Float,
        widthDelayMs: Int = 0,
    ) {
        if (!animate) {
            width.snapTo(target)
            height.snapTo(target)
            return
        }
        // The same curve on both unless the opening pours, so the shape keeps
        // its proportion on the way; a poured one runs down, then spreads.
        // NaN velocity: each axis keeps the speed it is already moving at.
        val widthStart = if (velocity.isNaN()) width.velocity else velocity
        val heightStart = if (velocity.isNaN()) height.velocity else velocity
        ChromeMotion.during {
            coroutineScope {
                launch {
                    if (widthDelayMs > 0) kotlinx.coroutines.delay((widthDelayMs * MotionPrefs.responseScale).toLong())
                    width.animateTo(target, widthSpec.paced(), initialVelocity = widthStart)
                }
                launch { height.animateTo(target, heightSpec.paced(), initialVelocity = heightStart) }
            }
        }
    }
}

/**
 * A popup's growth: Material's out of the button on its spring, the glass
 * looks' poured as Mornye pours its menus, or a recipe's own opening where
 * that is switched on.
 */
@Composable
internal fun rememberPopupGrowth(shown: Boolean): PopupGrowth {
    val growth = remember { PopupGrowth(shown) }
    val look = LocalSkinSettings.current
    growth.profile = popupOpening(LocalSkin.current, look)
    return growth
}

/** How [skin]'s popups open under [look]; see [rememberPopupGrowth]. */
internal fun popupOpening(skin: Skin, look: SkinSettings): OpenProfile = when (skin) {
    Skin.MATERIAL -> MaterialOpen
    Skin.GLASSMORPHISM -> PourOpen
    Skin.LIQUID_GLASS -> look.openProfile() ?: PourOpen
}

// Material's emphasized accelerate: leaves slowly and is gone quickly.
internal val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
// Material's emphasized decelerate: arrives quickly and lands softly.
internal val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
// Out of the point: fast at first, then a soft landing at full size.
private val POPUP_OPEN = tween<Float>(Ui.Motion.MS, easing = EmphasizedDecelerateEasing)
// Back into it: slow to leave, then drawn in quickly.
private val POPUP_CLOSE = tween<Float>(Ui.Motion.MS, easing = EmphasizedAccelerateEasing)

/**
 * Grows a popup out of [anchor], a point on screen - the button or the tool
 * that opened it - as [growth] runs from 0 to 1, and folds it back into that
 * point as it returns, each axis on its own curve. The scale is a layer over
 * the popup, so the glass inside it has to be told to keep looking at the
 * page from where it now is: see [ProvideMovingBackdrops], whose alignment
 * undoes the two scales separately. Put it outside any room kept for the
 * shadow: while the popup fades this layer is drawn offscreen, and cuts off
 * whatever lies past its own bounds.
 */
@Composable
internal fun Modifier.growOutOf(anchor: () -> Offset?, growth: PopupGrowth): Modifier {
    var origin by remember { mutableStateOf(TransformOrigin(0.5f, 0f)) }
    return this
        // Before the layer, so the bounds are the popup's own, unscaled.
        .onGloballyPositioned { coordinates ->
            val at = anchor() ?: return@onGloballyPositioned
            val corner = coordinates.positionOnScreen()
            val (x, y) = popupGrowOrigin(at.x, at.y, corner.x, corner.y,
                coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
            val next = TransformOrigin(x, y)
            if (next != origin) origin = next
        }
        .graphicsLayer {
            val w = growth.width.value
            val h = growth.height.value
            val profile = growth.profile
            if (profile?.reveal == true) {
                // Nothing scaled: the pane's glass grows itself and what is on
                // it stays its size (see PopupReveal.kt).
                return@graphicsLayer
            }
            if (profile == null) {
                alpha = popupGrowAlpha(w, h)
                scaleX = popupGrowScale(w, POPUP_START_SCALE_X)
                scaleY = popupGrowScale(h, POPUP_START_SCALE_Y)
            } else {
                val start = if (growth.closing) profile.closeScale else profile.startScale
                alpha = profile.alpha(maxOf(w, h))
                scaleX = profile.scaleOf(w, start)
                scaleY = profile.scaleOf(h, start)
                translationY = -profile.dropDp * density * (1f - minOf(w, h).coerceIn(0f, 1f))
            }
            transformOrigin = origin
            // On a 60 Hz screen the fast start of a popup jumps far between
            // frames; a trace of blur along it reads as motion rather than
            // as steps. Gone as it slows, and never at 120 Hz.
            renderEffect = popupMotionBlur(growth, size.width, size.height, 4.dp.toPx())
        }
}

/** The blur along a growing popup's motion, or none; see [motionBlurPx]. */
private fun popupMotionBlur(growth: PopupGrowth, width: Float, height: Float, maxPx: Float): androidx.compose.ui.graphics.RenderEffect? {
    if (android.os.Build.VERSION.SDK_INT < 31 || !MotionPrefs.motionBlur || InteractionGovernor.lightMode) return null
    val hz = MotionPrefs.refreshHz
    // An edge of a pane scaled about a point inside it moves at about the
    // scale's speed times half the pane's size.
    val bx = motionBlurPx(growth.width.velocity * width / 2f, hz, maxPx)
    val by = motionBlurPx(growth.height.velocity * height / 2f, hz, maxPx)
    if (bx <= 0f && by <= 0f) return null
    return androidx.compose.ui.graphics.BlurEffect(bx.coerceAtLeast(0.01f), by.coerceAtLeast(0.01f),
        androidx.compose.ui.graphics.TileMode.Decal)
}

/**
 * How small a popup is at the point it comes out of: nearly nothing, so it is
 * seen to come from where the finger was, the same on both axes.
 */
private const val POPUP_START_SCALE_X = 0.06f
private const val POPUP_START_SCALE_Y = 0.06f

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
            ToolbarCurve.entries.forEach { curve ->
                SettingsChoiceChip(
                    selected = ToolbarMotion.curve == curve,
                    onClick = { ToolbarMotion.update(curve = curve) },
                    label = curve.label,
                )
            }
        }
    }
}

/**
 * The tool strip of the still skins, cut to the glass bar's measure: the same
 * capsule, cells and resting pill as [LiquidGlassBar], so all three skins share
 * one toolbar. Every other icon wears the same ink; the tool in hand wears its
 * own colour - the pen's ink, as under Liquid glass - or the accent for a tool
 * that has none, and its highlight slides over from the tool before it on the
 * lens's own spring without changing size - the one motion Material and
 * Glassmorphism keep. [ownSurface] gives it a capsule of the skin's material;
 * without one it is content on the bar it sits in.
 */
@Composable
private fun ToolStrip(
    tools: List<GlassToolbarTool>, selectedIndex: Int, compact: Boolean, corner: Dp,
    ownSurface: Boolean, toolColors: Map<EditMode, Color>, onPick: (GlassToolbarTool) -> Unit,
    /** Down the side: the same strip and sliding highlight on the other axis. */
    vertical: Boolean = false,
) {
    if (tools.isEmpty()) return
    val skin = LocalSkin.current
    val scheme = MaterialTheme.colorScheme
    val chromeScale = LocalChromeScale.current
    val barHeight = (if (compact) 40.dp else 48.dp) * chromeScale
    val padding = 6.dp
    val cellHeight = barHeight - padding * 2
    // Liquid glass's resting pill and lens radius, from the same function.
    val pill = RoundedCornerShape(toolPillRadius(corner, barHeight, padding))
    // Solid under Material, as every button there is; a wash of the accent on frost.
    val highlight = if (skin == Skin.MATERIAL) scheme.primaryContainer else scheme.primary.copy(alpha = 0.16f)
    val selected = selectedIndex.takeIf { it in tools.indices }
    val slide = remember { Animatable((selected ?: 0).toFloat()) }
    val showing = remember { booleanArrayOf(selected != null) }
    LaunchedEffect(selected) {
        if (selected == null) {
            showing[0] = false
            return@LaunchedEffect
        }
        // Arriving from no selection there is nothing to slide from.
        if (!showing[0]) {
            showing[0] = true
            slide.snapTo(selected.toFloat())
        } else {
            // The lens's travel spring, so a tap moves the selection as fast
            // here as it does under Liquid glass; a new tap mid-slide keeps
            // the velocity it has, as the lens does.
            slide.animateTo(selected.toFloat(), TOOL_HIGHLIGHT_SPRING)
        }
    }
    val frame = if (vertical) Modifier.width(barHeight + 16.dp).height(verticalBarLength(tools.size, compact, padding, chromeScale))
        else Modifier.fillMaxWidth().height(barHeight + 16.dp)
    BoxWithConstraints(frame.selectableGroup()) {
        val cell = ((if (vertical) maxHeight else maxWidth) - padding * 2).coerceAtLeast(1.dp) / tools.size
        val capsule = if (vertical) Modifier.fillMaxHeight().width(barHeight) else Modifier.fillMaxWidth().height(barHeight)
        Box(capsule.align(Alignment.Center)
            .then(if (ownSurface) Modifier.skinMaterial(RoundedCornerShape(corner), role = ChromeRole.TOOLBAR) else Modifier)) {
            if (selected != null) {
                // Kept inside the strip if the spring runs a hair past the end.
                // Moved in the draw, to the fraction of a pixel: placed on
                // whole pixels its slow end crept on in visible steps, which
                // a 60 Hz screen shows plainly.
                Box(Modifier
                    .graphicsLayer {
                        val along = (padding + cell * slide.value.coerceIn(0f, tools.lastIndex.toFloat())).toPx()
                        val across = padding.toPx()
                        translationX = if (vertical) across else along
                        translationY = if (vertical) along else across
                    }
                    .then(if (vertical) Modifier.size(cellHeight, cell) else Modifier.size(cell, cellHeight))
                    .background(highlight, pill))
            }
        }
        @Composable
        fun Cell(index: Int, tool: GlassToolbarTool, base: Modifier) {
            val chosen = index == selected
            Box(base
                .semantics {
                    contentDescription = tool.label
                    this.selected = chosen
                }
                .clickable(role = Role.Tab, onClick = { onPick(tool) }),
                contentAlignment = Alignment.Center) {
                AppIcon(tool.icon(), null, Modifier.size((if (compact) 20.dp else 25.dp) * chromeScale),
                    tint = if (chosen) tool.mode?.let { toolColors[it] } ?: scheme.primary else scheme.onSurface)
            }
        }
        // The whole thickness of the strip is the target, not just the pill.
        if (vertical) {
            Column(Modifier.matchParentSize().padding(vertical = padding)) {
                tools.forEachIndexed { index, tool -> Cell(index, tool, Modifier.weight(1f).fillMaxWidth()) }
            }
        } else {
            Row(Modifier.matchParentSize().padding(horizontal = padding)) {
                tools.forEachIndexed { index, tool -> Cell(index, tool, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/** The still skins' highlight slides on the lens's travel spring (see [LiquidGlassMotion]). */
private val TOOL_HIGHLIGHT_SPRING = spring(
    dampingRatio = LIQUID_GLASS_TRAVEL_DAMPING_RATIO,
    stiffness = LIQUID_GLASS_TRAVEL_STIFFNESS.toFloat(),
    visibilityThreshold = LIQUID_GLASS_TRAVEL_SETTLE.toFloat(),
)

/** Buttons [columns] to a row, the last row padded so every button keeps one width. */
@Composable
internal fun GlassMenuGrid(
    actions: List<GlassToolbarAction>,
    columns: Int = 2,
    onAction: (GlassToolbarAction) -> Unit = { it.onClick() },
) {
    val across = columns.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        actions.chunked(across).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                row.forEach { action ->
                    GlassActionButton(action.label, action.icon, Modifier.weight(1f), action.selected, action.enabled,
                        subtitle = action.disabledReason) { onAction(action) }
                }
                repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
