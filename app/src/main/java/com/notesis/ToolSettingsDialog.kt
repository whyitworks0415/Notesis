package com.notesis

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The ways of picking a colour, by the index the choice is remembered by
 * across tools. Grid, SV square, HSV bars and hue ring are offered.
 * Legacy RGB and disc indices remain readable without moving saved ordinals.
 */
internal enum class ColorPickerMode(val label: String) {
    SQUARE("SV square"),
    BARS("HSV bars"),
    RING("HSV ring"),
    RGB("RGB"),
    DISC("RGB disc"),
    GRID("Color grid"),
}

/**
 * The colour being set, held once as hue, saturation, value and alpha so that
 * every picker reads and writes the same thing and switching pickers loses
 * nothing - not even the hue of a grey, which RGB alone cannot keep.
 */
@Stable
internal class PenColorState(argb: Int) {
    var hue by mutableFloatStateOf(0f)
    var saturation by mutableFloatStateOf(0f)
    var value by mutableFloatStateOf(0f)
    var alpha by mutableFloatStateOf(1f)

    init { set(argb) }

    /** Takes [argb] in; with [keepAlpha] the alpha already chosen stays. */
    fun set(argb: Int, keepAlpha: Boolean = false) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        // A grey has no hue of its own; keep the one already there.
        if (hsv[1] > 0f) hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        if (!keepAlpha) alpha = android.graphics.Color.alpha(argb) / 255f
    }

    fun argb(): Int = android.graphics.Color.HSVToColor(
        (alpha * 255f).roundToInt().coerceIn(0, 255), floatArrayOf(hue, saturation, value),
    )

    fun rgb(): Int = argb() or OPAQUE

    fun setRgb(red: Int, green: Int, blue: Int) =
        set(android.graphics.Color.rgb(red, green, blue), keepAlpha = true)

    companion object {
        const val OPAQUE = -0x1000000
    }
}

/**
 * Where a popup opened from the toolbar grows out of and folds back into: the
 * point on screen that was just tapped - the pen tapped again, the Functions
 * button - or, for one opened some other way, the middle of the bar's lower
 * edge ([screen]), which the bar writes as it is laid out.
 */
internal object PenPopupAnchor {
    /** The middle of the bar's lower edge on screen; null before it is laid out. */
    @Volatile var screen: Offset? = null
    @Volatile private var tap: Offset? = null
    @Volatile private var tapAt = 0L

    /** The bar on screen, where [touchedScreen] counts a touch as the bar's; null while there is none. */
    @Volatile var barBounds: androidx.compose.ui.geometry.Rect? = null

    /** Every touch down in the window, in screen pixels; kept when it is on the bar. */
    fun touchedScreen(x: Float, y: Float) {
        LastTouch.at(x, y)
        val bar = barBounds ?: return
        if (bar.contains(Offset(x, y))) tapped(Offset(x, y))
    }

    /** Called for every touch that lands on the bar, before anything handles it. */
    fun tapped(at: Offset) {
        tap = at
        tapAt = android.os.SystemClock.uptimeMillis()
    }

    /** The bar's last touch, if it was recent enough to be what opened a popup. */
    fun recentTap(): Offset? =
        tap?.takeIf { android.os.SystemClock.uptimeMillis() - tapAt <= TAP_OPENS_WITHIN_MS }

    /** Where the pen panel grows from. */
    fun point(): Offset? = recentTap() ?: screen

    /** Longer than any tap and the frame it takes to open what it opened. */
    private const val TAP_OPENS_WITHIN_MS = 1500L
}

/** The pen panel's title for each tool. */
private fun penPanelTitle(mode: EditMode): String = when (mode) {
    EditMode.PEN -> "Pen"
    EditMode.PENCIL -> "Pencil"
    EditMode.HIGHLIGHTER -> "Highlighter"
    EditMode.MASK -> "Masking tape"
    EditMode.SHAPE -> "Shape"
    EditMode.ERASE -> "Eraser"
    else -> "Tool"
}

/**
 * How one tool is set, on a glass panel over the page: colour on top with the
 * palette beside it, then thickness with a live preview, the kind of pen and
 * its pressure, the line style - and the rest of this tool's settings behind
 * "More for this pen", which opens the panel downward rather than crowding the
 * top. On a wide screen colour and stroke sit side by side, so the panel fits
 * without scrolling until that part is opened. What every pen shares lives in
 * the settings sidebar instead; [onAllPens] goes there.
 *
 * The panel grows out of the toolbar and folds back into it. Tapping past it,
 * or back, keeps what was set; Cancel leaves the tool as it was.
 */
@Composable
internal fun PenDialog(
    mode: EditMode,
    pen: PenPreset,
    highlighterAboveInk: Boolean,
    onHighlighterAboveInk: (Boolean) -> Unit,
    axisSnap: Boolean,
    onAxisSnap: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (PenPreset) -> Unit,
    partialEraser: Boolean = false,
    onPartialEraser: (Boolean) -> Unit = {},
    gestures: CanvasGestures = CanvasGestures(),
    onGestures: (CanvasGestures) -> Unit = {},
    onClearPage: (() -> Unit)? = null,
    /** Asks to take the writing off every page; the host confirms first. */
    onClearAll: (() -> Unit)? = null,
    onEyedropper: (() -> Unit)? = null,
    onCornerRadius: (Float) -> Unit = {},
    /** Opens the settings every pen shares; null leaves the link out. */
    onAllPens: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    val color = remember(pen) { PenColorState(pen.colorArgb) }
    var width by remember(pen) { mutableFloatStateOf(pen.width) }
    var pressure by remember(pen) { mutableStateOf(pen.pressure) }
    var nib by remember(pen) { mutableStateOf(pen.nib) }
    var maxWidth by remember(pen) { mutableFloatStateOf(pen.maxWidth) }
    var leadMs by remember(pen) { mutableIntStateOf(pen.leadMs) }
    var stabilizer by remember(pen) { mutableIntStateOf(pen.stabilizer) }
    var linePattern by remember(pen) { mutableIntStateOf(pen.linePattern) }
    var autoShapes by remember(pen) { mutableStateOf(pen.autoShapes) }
    var straightLine by remember(pen) { mutableStateOf(pen.straightLine) }
    var nibAngle by remember(pen) { mutableStateOf(pen.nibAngle) }
    var density by remember(pen) { mutableStateOf(pen.density) }
    var details by remember { mutableStateOf(false) }
    val joined = remember { store.colorPickerJoined }
    val opacity = allowsOpacity(mode)
    val range = PenStore.widthRange(mode, pen.copy(maxWidth = maxWidth))
    val drawing = pen.copy(nib = nib, pressure = pressure).drawingTool()
    val freehand = mode == EditMode.PEN || mode == EditMode.PENCIL

    fun current() = PenPreset(
        tool = pen.tool,
        colorArgb = if (opacity) color.argb() else color.rgb(),
        width = width.coerceIn(range),
        pressure = pressure,
        maxWidth = maxWidth,
        nib = nib,
        leadMs = leadMs,
        stabilizer = stabilizer,
        linePattern = linePattern,
        autoShapes = autoShapes,
        straightLine = straightLine,
        nibAngle = nibAngle,
        density = density,
    )

    // Open: grow from the bar. Close: fold back, and only then hand the
    // result over, since the host takes the panel away the moment it has it.
    // The rounded-window transition is shared across skins.
    val panel = rememberToolPanelState()
    val animate = panel.animate
    fun keep() = panel.close { onConfirm(current()) }
    fun cancel() = panel.close(onDismiss)
    var tab by remember { mutableStateOf(firstPenTab(mode.tints)) }
    // Each kind of pen keeps its own settings: leaving one keeps what it was
    // set to, and coming back to one finds it as it was left.
    fun takeUp(saved: PenPreset) {
        color.set(saved.colorArgb)
        width = saved.width
        pressure = saved.pressure
        maxWidth = saved.maxWidth
        leadMs = saved.leadMs
        stabilizer = saved.stabilizer
        linePattern = saved.linePattern
        autoShapes = saved.autoShapes
        straightLine = saved.straightLine
        nibAngle = saved.nibAngle
        density = saved.density
    }
    fun switchNib(next: Tool) {
        if (next == nib && !(mode == EditMode.PENCIL && drawing != next)) return
        store.saveNibPreset(mode, current())
        val saved = store.nibPreset(mode, next, current())
        nib = next
        if (saved != null) takeUp(saved)
    }
    // Kept as this kind of pen's own as well, for the next time it is chosen.
    val keepNib: () -> Unit = { if (freehand) store.saveNibPreset(mode, current()) }

    ToolPanel(
        title = penPanelTitle(mode),
        state = panel,
        onOutside = { keepNib(); keep() },
        maxWidth = PEN_PANEL_WIDTH,
        // Every pen's settings open with their title at one height, however long they are.
        fixedTop = true,
        actions = {
            TextButton(onClick = ::cancel, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) { Text("Cancel") }
            TextButton(onClick = { keepNib(); keep() }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) { Text("Save") }
        },
    ) {
        val tabs = if (mode.tints) PenPanelTab.entries.toList() else listOf(PenPanelTab.SETTING)
        if (tabs.size > 1) MorphSegments(tabs, tab, { it.label }, onSelect = { tab = it })
        // The pen as it will draw, on both tabs, following every change.
        PenStrokePreview(
            width = width.coerceIn(range),
            color = if (mode == EditMode.ERASE) null else Color(if (opacity) color.argb() else color.rgb()),
            linePattern = if (freehand && !drawing.isDabbed()) linePattern else 0,
            tool = drawing, nibAngle = nibAngle, pressure = pressure && mode == EditMode.PEN,
            modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT),
        )
        val settingTab: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                if (mode == EditMode.ERASE) {
                    EraserSection(partialEraser, onPartialEraser, gestures, onGestures)
                    if (onClearPage != null || onClearAll != null) {
                        // Right here rather than behind "More": clearing is what an
                        // eraser's settings are opened for, often as not.
                        Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                            if (onClearPage != null) OutlinedButton(onClick = { panel.close(onClearPage) }) {
                                Icon(Reicons.Delete, contentDescription = null, modifier = Modifier.size(Ui.Icon.small))
                                Spacer(Modifier.width(Ui.Space.xs))
                                Text("Clear this page")
                            }
                            if (onClearAll != null) OutlinedButton(onClick = { panel.close(onClearAll) }) {
                                Icon(Reicons.Delete, contentDescription = null, modifier = Modifier.size(Ui.Icon.small))
                                Spacer(Modifier.width(Ui.Space.xs))
                                Text("Clear all writing")
                            }
                        }
                    }
                }
                WidthBlock(mode, width, range) { width = it }
                if (mode == EditMode.PEN) {
                    MorphSegments(PEN_TYPES.map { it.first }, penTypeShown(nib),
                        label = { kind -> PEN_TYPES.first { it.first == kind }.second },
                        onSelect = ::switchNib,
                        icon = { kind, tint -> PenGlyph(kind, tint) })
                } else if (mode == EditMode.PENCIL) {
                    MorphSegments(PENCIL_NIBS.map { it.first }, drawing,
                        label = { kind -> PENCIL_NIBS.first { it.first == kind }.second },
                        onSelect = ::switchNib,
                        icon = { kind, tint -> PenGlyph(kind, tint) })
                }
                // The row that belongs to the kind of pen: line types for the
                // ballpoint and the vanishing pen, the angle for a cut nib. It
                // changes with the pen, one folding away as the other opens.
                androidx.compose.animation.AnimatedContent(
                    targetState = penExtraRow(mode, nib, drawing) to drawing,
                    transitionSpec = {
                        val enter = if (animate) fadeIn(tween(200, delayMillis = 60)) + expandVertically(tween(240))
                            else EnterTransition.None
                        val exit = if (animate) fadeOut(tween(120)) + shrinkVertically(tween(220)) else ExitTransition.None
                        enter togetherWith exit
                    },
                    label = "pen extra row",
                ) { (row, kind) ->
                    when (row) {
                        PenExtraRow.LINE_TYPE -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Line type", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(LINE_TYPE_LABEL))
                            MorphSegments(listOf(0, 1, 2), linePattern.takeIf { it in 0..2 },
                                label = { listOf("Solid", "Dot", "Dash")[it] },
                                onSelect = { linePattern = it },
                                modifier = Modifier.weight(1f),
                                icon = { pattern, tint -> LineGlyph(pattern, tint) })
                        }
                        PenExtraRow.NIB_ANGLE -> Column { NibAngleRow(kind, nibAngle) { nibAngle = it } }
                        PenExtraRow.DENSITY -> Column { DensityRow(density) { density = it } }
                        PenExtraRow.NONE -> Spacer(Modifier.height(0.dp))
                    }
                }
                if (mode == EditMode.PEN) PanelSwitchRow("Pressure", pressure) { pressure = it }
                if (mode in STRAIGHT_LINE_MODES) PanelSwitchRow("Straight line", straightLine) { straightLine = it }
                if (freehand) PanelSwitchRow("Recognize shape", autoShapes) { autoShapes = it }
            }
        }
        val colorTab: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                var view by remember { mutableStateOf(colorViewOf(store.colorPickerMode)) }
                MorphSegments(COLOR_VIEWS, view, { it.label }, onSelect = {
                    view = it
                    store.colorPickerMode = it.ordinal
                })
                // One view for another at once: no morph between them.
                MorphingPicker(view, animate = false) { shownView ->
                    ColorPickerBody(shownView, color, joined, opacity)
                }
                if (opacity) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        Text("Opacity", style = MaterialTheme.typography.labelMedium)
                        GradientStrip(
                            colors = listOf(Color(color.rgb()).copy(alpha = 0f), Color(color.rgb())),
                            position = color.alpha,
                            modifier = Modifier.weight(1f).height(STRIP_HEIGHT),
                            shape = RoundedCornerShape(STRIP_HEIGHT / 2),
                        ) { color.alpha = it }
                        Text("${(color.alpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(36.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    // The colour itself, then its code, then the eyedropper: one height, one corner.
                    Box(Modifier.size(FIELD_COMPACT).clip(pickerShape).background(Color.White).background(Color(color.argb()))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, pickerShape))
                    HexField(color, opacity, Modifier.weight(1f))
                    if (onEyedropper != null) {
                        Box(
                            Modifier
                                .size(FIELD_COMPACT)
                                .clip(pickerShape)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, pickerShape)
                                // Handed over at once, not after the fold: the eyedropper
                                // takes the panel away itself.
                                .clickable { onConfirm(current()); onEyedropper() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Reicons.Colorize, contentDescription = "Eyedropper", modifier = Modifier.size(Ui.Icon.regular))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(PALETTE_BELOW_HEIGHT)) { PalettePanel(color, opacity) }
            }
        }
        EqualTabs(tabs.indexOf(tab).coerceAtLeast(0), animate,
            tabs.map { if (it == PenPanelTab.COLOR) colorTab else settingTab })

        // The rest of this tool's settings, opened downward on demand.
        Row(
            Modifier
                .clip(Ui.Shape.pill)
                .clickable { details = !details }
                .heightIn(min = Ui.Size.touchCompact)
                .padding(Ui.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Reicons.Tune, contentDescription = null, modifier = Modifier.size(Ui.Icon.small))
            }
            Spacer(Modifier.width(Ui.Space.m))
            Text(if (details) "Fewer settings" else "More for this ${penPanelTitle(mode).lowercase()}",
                style = MaterialTheme.typography.bodyMedium)
        }
        // Quick and eased out, rows and background together; Material
        // opens and closes it at once.
        AnimatedVisibility(
            visible = details,
            enter = if (animate) {
                expandVertically(tween(DETAIL_OPEN_MS, easing = EmphasizedDecelerateEasing), expandFrom = Alignment.Top) +
                    fadeIn(tween(DETAIL_OPEN_MS / 2, delayMillis = DETAIL_OPEN_MS / 6))
            } else EnterTransition.None,
            exit = if (animate) {
                shrinkVertically(tween(DETAIL_CLOSE_MS, easing = DETAIL_CLOSE_EASING), shrinkTowards = Alignment.Top) +
                    fadeOut(tween(DETAIL_CLOSE_MS / 2))
            } else ExitTransition.None,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Only this tool", style = MaterialTheme.typography.labelLarge)
                if (mode != EditMode.ERASE) {
                    SavedPensSection(
                        mode = mode,
                        store = store,
                        current = { current() },
                        onUse = { saved -> nib = saved.nib; takeUp(saved) },
                    )
                }
                if (mode != EditMode.ERASE) PredictionSetting(leadMs) { leadMs = it }
                if (freehand) {
                    Text(
                        "Smoothing $stabilizer%" + if (gestures.windowedStabilizer && stabilizer > 0)
                            " · average of the last ${slidingWindowMillis(stabilizer)} ms" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SkinSlider(
                        value = stabilizer.toFloat(),
                        onValueChange = { stabilizer = it.roundToInt() },
                        valueRange = 0f..100f,
                        step = 1f,
                    )
                    Text(
                        if (gestures.windowedStabilizer) "Window average, set for every pen in Pen settings."
                        else "Adaptive, set for every pen in Pen settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                WidthCeilings(mode, range) { ceiling ->
                    maxWidth = ceiling
                    width = width.coerceAtMost(ceiling)
                }
                WidthControls(mode, width) {
                    width = it
                    if (it > range.endInclusive) maxWidth = it
                }
                if (mode == EditMode.SHAPE) {
                    PanelSwitchRow("Snap lines to 90° steps", axisSnap, onChange = onAxisSnap)
                    var corner by remember { mutableFloatStateOf(store.shapeCornerRadius) }
                    Text("Polygon corner rounding ${corner.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
                    SkinSlider(corner, { corner = it; store.shapeCornerRadius = it; onCornerRadius(it) }, 0f..120f, step = 1f)
                }
                if (mode == EditMode.HIGHLIGHTER) {
                    PanelSwitchRow(if (highlighterAboveInk) "Shown above the writing" else "Shown under the writing",
                        highlighterAboveInk, onChange = onHighlighterAboveInk)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Pressure sensitivity, smoothing mode, 480 Hz frames, pen gestures and drawing " +
                        "options apply to every pen. They are in Settings › Pen settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (onAllPens != null) {
                    TextButton(
                        onClick = { onConfirm(current()); onAllPens() },
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text("Open settings for every pen") }
                }
            }
        }
    }
}


/** How long "More for this pen" takes to open the rest of the panel, and to close it. */
private const val DETAIL_OPEN_MS = Ui.Motion.MS
private const val DETAIL_CLOSE_MS = Ui.Motion.MS
// Material's standard curve: off quickly, landing softly, without the
// emphasized curve's long tail - a collapse should be over when it looks over.
private val DETAIL_CLOSE_EASING = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * The writing settings every pen shares, for the settings sidebar. The
 * per-pen ones - colour, width, nib, smoothing strength, prediction - stay in
 * each pen's own panel.
 */
@Composable
internal fun GlobalPenSettings(
    gestures: CanvasGestures,
    onGestures: (CanvasGestures) -> Unit,
    deferDetail: Boolean,
    onDeferDetail: (Boolean) -> Unit,
    meshInk: Boolean,
    onMeshInk: (Boolean) -> Unit,
    compatWetInk: Boolean,
    onCompatWetInk: (Boolean) -> Unit,
    compatInkInWindow: Boolean = false,
    onCompatInkInWindow: (Boolean) -> Unit = {},
    /** Opens the panel of the pen in hand; null leaves the button out. */
    onPenInHand: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    var joined by remember { mutableStateOf(store.colorPickerJoined) }
    // The slider moves a local value; the gestures, and the preferences behind
    // them, are written once it has rested rather than on every step.
    var pressureResponse by remember { mutableIntStateOf(gestures.pressureResponse) }
    val latestGestures by rememberUpdatedState(gestures)
    val latestOnGestures by rememberUpdatedState(onGestures)
    LaunchedEffect(pressureResponse) {
        if (pressureResponse == latestGestures.pressureResponse) return@LaunchedEffect
        delay(PRESSURE_COMMIT_DELAY_MS)
        latestOnGestures(latestGestures.copy(pressureResponse = pressureResponse))
    }
    SettingsCard {
        SettingsSectionHeader("These apply to every pen")
        SettingsNote(
            "Color, width, nib, smoothing strength and prediction belong to each pen. " +
                "Tap the tool in hand again to set those for it alone.",
        )
        if (onPenInHand != null) {
            TextButton(onClick = onPenInHand, modifier = Modifier.heightIn(min = 44.dp)) { Text("Open the pen in hand") }
        }
    }
    SettingsCard {
        SettingsSectionHeader("Pressure")
        Text("Pressure sensitivity $pressureResponse", style = MaterialTheme.typography.bodyLarge)
        SkinSlider(
            value = pressureResponse.toFloat(),
            onValueChange = { pressureResponse = it.roundToInt().coerceIn(0, 100) },
            valueRange = 0f..100f,
            step = 1f,
        )
        SettingsNote(
            "Default $PRESSURE_RESPONSE_DEFAULT. At 0 pressure is ignored; higher values thin a light " +
                "touch, so only a firm press draws the full width. Also sets the pressure eraser.",
        )
    }
    SettingsCard {
        SettingsSectionHeader("Smoothing")
        SettingsChoices(
            "How smoothing works",
            listOf(0 to "Adaptive", 1 to "Window average"),
            if (gestures.windowedStabilizer) 1 else 0,
            hint = if (gestures.windowedStabilizer) {
                "Averages the pen's last few milliseconds. Slow, shaky lines come out straighter, " +
                    "and trail the pen by as much."
            } else {
                "Follows fast writing at once and only calms small, slow wobbles."
            },
        ) { onGestures(gestures.copy(windowedStabilizer = it == 1)) }
    }
    SettingsCard {
        SettingsSectionHeader("Latency and drawing")
        SettingsChoices(
            "480 Hz in-between frames",
            listOf(SUBFRAME_OFF to "Off", SUBFRAME_BLUR to "Motion blur", SUBFRAME_GHOST to "Trail"),
            gestures.subframe,
            hint = "The pen reports about four positions per screen refresh. This draws the path between " +
                "them faintly ahead of the tip, so the line seems to keep up. Nothing extra is saved.",
        ) { onGestures(gestures.copy(subframe = it)) }
        var inkPath by remember { mutableIntStateOf(store.inkPath) }
        val applied = inkPathApplied(inkPath)
        SettingsChoices(
            "How the stroke being written is shown",
            listOf(INK_PATH_AUTO to "Automatic", INK_PATH_FRONT to "Fastest", INK_PATH_COMPAT to "Compatible",
                INK_PATH_WINDOW to "Safest"),
            inkPath,
            hint = "If strokes do not show, show only when the pen lifts, or vanish, choose Safest. " +
                "Fastest draws straight to the display and has the least delay where the tablet supports it. " +
                "Compatible draws a second copy on a layer of its own. Safest draws it with the rest of the screen: " +
                "the most sure to show, and the slowest with a web panel or a second note open.",
        ) {
            inkPath = it
            store.inkPath = it
            // The canvas takes the two switches it always did.
            onCompatWetInk(store.compatWetInk)
            onCompatInkInWindow(store.compatInkInWindow)
        }
        val appliedName = when (applied) {
            INK_PATH_FRONT -> "Fastest"
            INK_PATH_COMPAT -> "Compatible"
            else -> "Safest"
        }
        SettingsNote("In use now: $appliedName.")
        when {
            frontBufferInkUnreliable() && inkPath != applied ->
                SettingsNote("This tablet cannot show the fastest path, so it is not used here.")
            inkPath == INK_PATH_AUTO && applied == INK_PATH_WINDOW ->
                SettingsNote("Automatic picks Safest on tablets older than Android 13.")
        }
        SettingsToggle("Sharpen after zooming", deferDetail, onDeferDetail,
            hint = "While zooming the page is drawn as it is, then redrawn sharp when your fingers lift.")
        SettingsToggle("Mesh rendering (experimental)", meshInk, onMeshInk,
            hint = "Draws opaque and pressure pens with the Ink shader. Off goes back to the older path fill.")
    }
    SettingsCard {
        SettingsSectionHeader("Pen gestures")
        SettingsToggle("Hold at the end to make a shape", gestures.holdToDraw,
            { onGestures(gestures.copy(holdToDraw = it)) })
        SettingsToggle("Scribble over ink to erase it", gestures.scribbleErase,
            { onGestures(gestures.copy(scribbleErase = it)) })
        SettingsToggle("Circle and hold to select", gestures.circleToLasso,
            { onGestures(gestures.copy(circleToLasso = it)) })
    }
    SettingsCard {
        SettingsSectionHeader("Color picker")
        SettingsToggle("One-piece color picker", joined, { joined = it; store.colorPickerJoined = it },
            hint = "The bars, square and RGB fields joined into one panel. Off shows them apart.")
    }
    SettingsNote("Palm rejection and what fingers do are in Touch & gestures.")
}

private const val PRESSURE_COMMIT_DELAY_MS = 300L

/** Automatic, off at 0, or a fixed 1-12ms; one slider so every millisecond is there. */
@Composable
private fun PredictionSetting(leadMs: Int, onLead: (Int) -> Unit) {
    val auto = leadMs == LEAD_AUTO
    // What automatic comes to on this screen: half a frame on most tablets.
    val refresh = LocalView.current.display?.refreshRate ?: 60f
    val autoMs = autoPredictionLeadMs(refresh, vsyncBoundInk = frontBufferInkUnreliable())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Prediction " + when {
                auto -> "automatic · $autoMs ms (${refresh.roundToInt()} Hz)"
                leadMs == 0 -> "off"
                else -> "$leadMs ms"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        SettingsChoiceChip(selected = auto, onClick = { onLead(if (auto) autoMs else LEAD_AUTO) }, label = "Auto")
    }
    SkinSlider(
        value = (if (auto) autoMs else leadMs).toFloat(),
        onValueChange = { onLead(it.roundToInt().coerceIn(0, MAX_LEAD_MS)) },
        valueRange = 0f..MAX_LEAD_MS.toFloat(),
        step = 1f,
    )
    Text(
        if (auto) "Automatic draws half a frame ahead: 8 ms at 60 Hz, 6 ms at 90 Hz and 4 ms at 120 Hz (12, 8 and 6 ms with compatible wet ink), a little more when the ink is late to appear"
        else "Draws this far ahead of the pen. 0 turns prediction off. Longer feels faster, but stroke ends can wobble",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun EraserSection(
    partialEraser: Boolean,
    onPartialEraser: (Boolean) -> Unit,
    gestures: CanvasGestures,
    onGestures: (CanvasGestures) -> Unit,
) {
    Row {
        SettingsChoiceChip(selected = !partialEraser, onClick = { onPartialEraser(false) },
            label = "Whole strokes", modifier = Modifier.padding(end = Ui.Space.s))
        SettingsChoiceChip(selected = partialEraser, onClick = { onPartialEraser(true) }, label = "Partial")
    }
    Text(if (partialEraser) "Erases only where it passes" else "Erases every stroke it touches, whole",
        style = MaterialTheme.typography.bodySmall)
    Text("Erases", style = MaterialTheme.typography.labelLarge)
    WrappingChips {
        listOf(
            EraseTarget.INK to gestures.eraseInk, EraseTarget.HIGHLIGHTER to gestures.eraseHighlighter,
            EraseTarget.TAPE to gestures.eraseTape, EraseTarget.IMAGES to gestures.eraseImages,
            EraseTarget.TEXT to gestures.eraseText, EraseTarget.LOCKED to gestures.eraseLocked,
        ).forEach { (target, on) ->
            SettingsChoiceChip(selected = on, label = target.label, onClick = {
                onGestures(when (target) {
                    EraseTarget.INK -> gestures.copy(eraseInk = !on)
                    EraseTarget.HIGHLIGHTER -> gestures.copy(eraseHighlighter = !on)
                    EraseTarget.TAPE -> gestures.copy(eraseTape = !on)
                    EraseTarget.IMAGES -> gestures.copy(eraseImages = !on)
                    EraseTarget.TEXT -> gestures.copy(eraseText = !on)
                    EraseTarget.LOCKED -> gestures.copy(eraseLocked = !on)
                })
            })
        }
    }
    PanelSwitchRow(
        "Pressure eraser",
        gestures.pressureEraser,
        hint = "The set size is the lightest touch; pressing harder grows it up to 2.5 times",
    ) { onGestures(gestures.copy(pressureEraser = it)) }
    // With the eraser's other choices rather than only in Settings: what the
    // eraser does after a stroke is part of how it is set up.
    PanelSwitchRow(
        "Back to the previous tool after erasing",
        gestures.eraserReturns,
        hint = "One stroke of the eraser, then the pen you had is back",
    ) { onGestures(gestures.copy(eraserReturns = it)) }
}

/** Choice chips that wrap onto as many lines as they need instead of scrolling out of sight. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WrappingChips(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.xs),
    ) { content() }
}

private enum class EraseTarget(val label: String) {
    INK("Pen"), HIGHLIGHTER("Highlighter"), TAPE("Tape"), IMAGES("Pictures"), TEXT("Text boxes"), LOCKED("Locked objects"),
}

// ---- colour ----------------------------------------------------------------

/** The one gap the panel uses everywhere, so every edge lines up with every other. */
internal val GAP = Ui.Space.m

private val PICKER_HEIGHT = 190.dp
private val STRIP_HEIGHT = 30.dp
private val BAR_WIDTH = 34.dp
private val LABEL_HEIGHT = 18.dp
private val FIELD_HEIGHT = 56.dp
private val PALETTE_WIDTH = 96.dp
/** The colour fields' corner: concentric with the panel they sit in. */
private val pickerShape: androidx.compose.ui.graphics.Shape
    @Composable get() = RoundedCornerShape(nestedCorner(Ui.Corner.control))




/** The four colour layouts share one HSVA state and never convert it on a view switch. */
@Composable
internal fun ColorPickerBody(view: ColorPickerMode, color: PenColorState, joined: Boolean, opacity: Boolean) {
    Box(Modifier.fillMaxWidth().testTag("color-picker-${view.name}")) {
        when (view) {
            ColorPickerMode.GRID -> ColorGrid(color)
            ColorPickerMode.BARS, ColorPickerMode.RGB -> BarsPicker(color, joined, opacity)
            ColorPickerMode.RING, ColorPickerMode.DISC -> RingPicker(color)
            else -> SquarePicker(color, joined)
        }
    }
}

/** 1. Saturation across, value down, hue on the strip below. */
@Composable
private fun SquarePicker(color: PenColorState, joined: Boolean) {
    Column(
        Modifier.fillMaxWidth().height(PICKER_HEIGHT).then(if (joined) Modifier.clip(pickerShape) else Modifier),
        verticalArrangement = Arrangement.spacedBy(if (joined) 0.dp else GAP),
    ) {
        SaturationValueField(
            color.hue, color.saturation, color.value,
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = if (joined) RectangleShape else pickerShape,
        ) { s, v ->
            color.saturation = s
            color.value = v
        }
        GradientStrip(
            colors = HUES,
            position = color.hue / 360f,
            modifier = Modifier.fillMaxWidth().height(STRIP_HEIGHT),
            shape = if (joined) RectangleShape else RoundedCornerShape(STRIP_HEIGHT / 2),
        ) { color.hue = it * 360f }
    }
}


@Composable
private fun BarsPicker(color: PenColorState, joined: Boolean, opacity: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(PICKER_HEIGHT).then(if (joined) Modifier.clip(pickerShape) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(if (joined) 0.dp else GAP),
    ) {
        VerticalStrip(HUES, color.hue / 360f, "H", joined) { color.hue = it * 360f }
        VerticalStrip(
            listOf(Color.hsv(color.hue, 0f, color.value), Color.hsv(color.hue, 1f, color.value)),
            color.saturation, "S", joined,
        ) { color.saturation = it }
        VerticalStrip(
            listOf(Color.Black, Color.hsv(color.hue, color.saturation, 1f)),
            color.value, "V", joined,
        ) { color.value = it }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Apart, the bars carry their letters above them; the colour
            // starts on the same line as the bars rather than above them.
            if (!joined) Spacer(Modifier.height(LABEL_HEIGHT))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (joined) Modifier else Modifier.clip(pickerShape))
                    .background(Color.White)
                    .background(Color(color.argb())),
            )
        }
    }
}


/** 3. A hue ring around the saturation-value square, with the numbers to type. */
@Composable
private fun RingPicker(color: PenColorState) {
    val thumb = with(LocalDensity.current) { PICKER_THUMB_RADIUS.toPx() }
    Row(
        Modifier.fillMaxWidth().height(PICKER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Canvas(
            Modifier
                .size(PICKER_HEIGHT)
                .pointerInput(color) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val outer = min(size.width, size.height) / 2f
                        val ring = outer * RING_FRACTION
                        val onRing = hypot(down.position.x - c.x, down.position.y - c.y) >= outer - ring - 2f
                        fun take(at: Offset) {
                            if (onRing) {
                                color.hue = angleOf(at - c)
                            } else {
                                val half = innerHalf(outer, ring)
                                color.saturation = trackFraction(at.x - (c.x - half), 2 * half, thumb)
                                color.value = 1f - trackFraction(at.y - (c.y - half), 2 * half, thumb)
                            }
                        }
                        take(down.position)
                        drag(down.id) { change -> change.consume(); take(change.position) }
                    }
                },
        ) {
            val c = center
            val outer = size.minDimension / 2f
            val ring = outer * RING_FRACTION
            drawCircle(Brush.sweepGradient(HUES, c), radius = outer - ring / 2f, center = c, style = Stroke(ring))
            val half = innerHalf(outer, ring)
            val topLeft = Offset(c.x - half, c.y - half)
            val box = Size(half * 2, half * 2)
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(color.hue, 1f, 1f)),
                startX = topLeft.x, endX = topLeft.x + box.width), topLeft, box)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black),
                startY = topLeft.y, endY = topLeft.y + box.height), topLeft, box)
            val angle = Math.toRadians(color.hue.toDouble())
            val r = outer - ring / 2f
            drawPickerThumb(
                Offset(c.x + (r * kotlin.math.cos(angle)).toFloat(), c.y + (r * kotlin.math.sin(angle)).toFloat()),
                min(thumb, ring / 2f - 1.dp.toPx()),
            )
            drawPickerThumb(Offset(
                topLeft.x + trackPosition(color.saturation, box.width, thumb),
                topLeft.y + trackPosition(1f - color.value, box.height, thumb),
            ), thumb)
        }
        // Three boxes of one size, as tall as the eyedropper, centred on the ring.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GAP, Alignment.CenterVertically)) {
            CompactNumber("H", color.hue.roundToInt(), 0..360, "°", Modifier.fillMaxWidth()) { color.hue = it.toFloat() % 360f }
            CompactNumber("S", (color.saturation * 100).roundToInt(), 0..100, "%", Modifier.fillMaxWidth()) { color.saturation = it / 100f }
            CompactNumber("V", (color.value * 100).roundToInt(), 0..100, "%", Modifier.fillMaxWidth()) { color.value = it / 100f }
        }
    }
}


/**
 * The colour sets, built-in and saved, under the picker: each set's name over
 * its colours, which wrap onto as many lines as they need rather than being
 * cut off at the panel's edge. The colour in use is ringed.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PalettePanel(color: PenColorState, opacity: Boolean) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    var custom by remember { mutableStateOf(store.colorTemplates()) }
    var adding by remember { mutableStateOf(false) }
    if (adding) TemplateEditor(onDismiss = { adding = false }, onSave = { name, colors ->
        custom = custom + (name to colors)
        store.saveColorTemplates(custom)
        adding = false
    })
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .clip(pickerShape)
            .background(scheme.onSurface.copy(alpha = 0.05f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Ui.Space.m, vertical = Ui.Space.s),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.m),
    ) {
        for ((name, colors) in COLOR_TEMPLATES + custom) {
            Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                Text(name, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
                    verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
                ) {
                    for (argb in colors) {
                        val chosen = (argb and 0xFFFFFF) == (color.rgb() and 0xFFFFFF)
                        Box(
                            Modifier
                                .size(PALETTE_SWATCH)
                                .clip(CircleShape)
                                .background(Color.White)
                                .background(Color(argb))
                                .border(if (chosen) 3.dp else 1.dp, if (chosen) scheme.primary else scheme.outlineVariant, CircleShape)
                                .semantics { contentDescription = "Color #%06X".format(argb and 0xFFFFFF); selected = chosen }
                                .clickable {
                                    // A see-through swatch brings its alpha only
                                    // where the tool can be see-through at all.
                                    val translucent = android.graphics.Color.alpha(argb) < 255
                                    color.set(argb, keepAlpha = !(opacity && translucent))
                                },
                        )
                    }
                }
            }
        }
        TextButton(onClick = { adding = true }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) { Text("+ Add template") }
    }
}

/** A palette colour, about a fingertip. */
private val PALETTE_SWATCH = 30.dp

/** #RRGGBB, or #AARRGGBB where the tool can be see-through; applied as soon as it parses. */
@Composable
private fun HexField(color: PenColorState, opacity: Boolean, modifier: Modifier) {
    val shown = if (opacity) "%08X".format(color.argb()) else "%06X".format(color.rgb() and 0xFFFFFF)
    var draft by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(shown) { if (!focused) draft = null }
    CompactField(
        text = draft ?: shown,
        onText = { typed ->
            val clean = typed.removePrefix("#").filter { it.isLetterOrDigit() }.take(if (opacity) 8 else 6)
            draft = clean
            val parsed = clean.toLongOrNull(16)
            if (parsed != null && (clean.length == 6 || (opacity && clean.length == 8))) {
                val argb = if (clean.length == 6) (parsed.toInt() or PenColorState.OPAQUE) else parsed.toInt()
                color.set(argb, keepAlpha = clean.length == 6)
            }
        },
        label = "#", suffix = null, keyboard = KeyboardType.Ascii,
        modifier = modifier.onFocusChanged { focused = it.isFocused; if (!it.isFocused) draft = null },
    )
}


/**
 * An upright ramp with a handle: the top is 1, the bottom 0. Apart, it is a
 * rounded bar with its letter above; joined, a plain band that butts against
 * its neighbours and leaves the rounding to the block it sits in.
 */
@Composable
private fun VerticalStrip(
    colors: List<Color>,
    position: Float,
    label: String?,
    joined: Boolean,
    onChange: (Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val thumb = with(LocalDensity.current) { PICKER_THUMB_RADIUS.toPx() }
    Column(Modifier.width(BAR_WIDTH).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!joined && label != null) Box(Modifier.height(LABEL_HEIGHT), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (joined) Modifier else Modifier.clip(RoundedCornerShape(BAR_WIDTH / 2)))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        fun take(at: Offset) =
                            currentOnChange(1f - trackFraction(at.y, size.height.toFloat(), thumb))
                        take(down.position)
                        drag(down.id) { change -> change.consume(); take(change.position) }
                    }
                },
        ) {
            drawRect(Color.White)
            drawRect(Brush.verticalGradient(colors.reversed()))
            // Kept off the rounded ends by a little more than the radius, so
            // the handle never meets the curve and looks cut.
            val inset = if (joined) thumb else size.width / 2f
            drawPickerThumb(Offset(size.width / 2f, trackPosition(1f - position, size.height, inset)), thumb)
        }
    }
}

private val HUES = (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }

/** How much of the ring picker's radius the hue ring takes. */
private const val RING_FRACTION = 0.18f

/** Half the side of the square that fits inside the ring with a little air. */
private fun innerHalf(outer: Float, ring: Float): Float = (outer - ring) / sqrt(2f) * 0.92f

/** Degrees clockwise from three o'clock, as the sweep gradients run. */
private fun angleOf(d: Offset): Float {
    val degrees = Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat()
    return (degrees + 360f) % 360f
}

// ---- thickness ---------------------------------------------------------------



/** Where the thickness slider tops out: Thin, Medium or the tool's own Max. */
@Composable
private fun WidthCeilings(mode: EditMode, range: ClosedFloatingPointRange<Float>, onCeiling: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Width limit", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        for ((label, ceiling) in PenStore.widthCeilings(mode)) {
            SettingsChoiceChip(
                selected = kotlin.math.abs(range.endInclusive - ceiling) < 0.01f,
                onClick = { onCeiling(ceiling) },
                label = label,
                modifier = Modifier.padding(start = Ui.Space.s),
            )
        }
    }
}

/** The tools whose strokes can be drawn as straight lines instead. */
internal val STRAIGHT_LINE_MODES = setOf(EditMode.PEN, EditMode.PENCIL, EditMode.HIGHLIGHTER, EditMode.MASK)

/**
 * The angle a cut nib is held at: a slider from flat to upright, either way,
 * with the default a tap away, and a stroke drawn with the nib so the angle
 * is seen as the line it makes - broad where the pen moves across the nib,
 * thin where it moves along it.
 */
@Composable
private fun NibAngleRow(tool: Tool, angle: Int?, onAngle: (Int?) -> Unit) {
    val shown = angle ?: tool.defaultNibAngle()
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Nib angle", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("$shown°", style = MaterialTheme.typography.labelLarge)
        if (angle != null) TextButton(onClick = { onAngle(null) }) { Text("Reset") }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
        SkinSlider(
            value = shown.toFloat(),
            onValueChange = { onAngle(it.roundToInt().coerceIn(Tool.NIB_ANGLES)) },
            valueRange = Tool.NIB_ANGLES.first.toFloat()..Tool.NIB_ANGLES.last.toFloat(),
            step = NIB_ANGLE_STEP,
            modifier = Modifier.weight(1f),
        )
        // The nib's own proportions: the calligraphy nib is far flatter than the fountain's.
        val flat = if (tool == Tool.CALLIGRAPHY) 0.18f else 0.55f
        androidx.compose.foundation.Canvas(Modifier.size(width = 72.dp, height = 40.dp)) {
            val nib = size.height * 0.36f
            val steps = 48
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                // One wave across: it goes up, across and down, so every direction is drawn.
                val x = size.width * (0.1f + 0.8f * t)
                val y = size.height * (0.5f - 0.32f * kotlin.math.sin(t * 2f * kotlin.math.PI.toFloat()))
                rotate(shown.toFloat(), pivot = androidx.compose.ui.geometry.Offset(x, y)) {
                    drawOval(
                        scheme.onSurface,
                        topLeft = androidx.compose.ui.geometry.Offset(x - nib / 2f, y - nib * flat / 2f),
                        size = androidx.compose.ui.geometry.Size(nib, nib * flat),
                    )
                }
            }
        }
    }
}

/**
 * Whole pens kept for this tool: a tap takes one up - type, width, colour
 * and every other setting at once - and a long press lets it go. "Save this
 * pen" keeps the one being set. A set of pens rather than separate favourite
 * widths and colours, so a pen comes back exactly as it was made.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SavedPensSection(
    mode: EditMode,
    store: PenStore,
    current: () -> PenPreset,
    onUse: (PenPreset) -> Unit,
) {
    var saved by remember(mode) { mutableStateOf(store.savedPens(mode)) }
    PanelSectionHeader("Saved pens")
    WrappingChips {
        saved.forEach { pen ->
            Row(
                Modifier
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .combinedClickable(
                        onClickLabel = "Use this pen",
                        onLongClickLabel = "Remove this pen",
                        onClick = { onUse(pen) },
                        onLongClick = {
                            saved = saved - pen
                            store.saveSavedPens(mode, saved)
                        },
                    )
                    .padding(horizontal = Ui.Space.m, vertical = Ui.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(pen.colorArgb or PenColorState.OPAQUE))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(Modifier.width(Ui.Space.xs))
                Text(
                    nibsFor(pen.tool).firstOrNull { it.first == pen.nib }?.second?.let { "$it · " }.orEmpty() +
                        widthLabel(pen.width),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        OutlinedButton(onClick = {
            val pen = current()
            saved = (saved.filterNot { it == pen } + pen).takeLast(PenStore.MAX_SAVED_PENS)
            store.saveSavedPens(mode, saved)
        }) { Text("Save this pen") }
    }
    if (saved.isNotEmpty()) {
        Text("Tap one to use it; hold it to remove it",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * The graphite pencil's density: how much of the paper's grain it fills, in
 * ten steps. Its own control rather than opacity's, which fades the whole
 * line evenly; density leaves more or less paper showing through the tooth.
 */
@Composable
private fun DensityRow(density: Int?, onDensity: (Int?) -> Unit) {
    val shown = density ?: GRAPHITE_DENSITY_DEFAULT
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Density", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${shown * 10}%", style = MaterialTheme.typography.labelLarge)
        if (density != null && density != GRAPHITE_DENSITY_DEFAULT) {
            TextButton(onClick = { onDensity(null) }) { Text("Reset") }
        }
    }
    SkinSlider(
        value = shown.toFloat(),
        onValueChange = {
            val level = it.roundToInt().coerceIn(GRAPHITE_DENSITY_MIN, GRAPHITE_DENSITY_MAX)
            onDensity(level.takeIf { l -> l != GRAPHITE_DENSITY_DEFAULT })
        },
        valueRange = GRAPHITE_DENSITY_MIN.toFloat()..GRAPHITE_DENSITY_MAX.toFloat(),
        step = 1f,
    )
    Text("Lower leaves more paper showing through the grain; higher fills it in",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

/** The nib angle's slider moves in steps of this many degrees. */
private const val NIB_ANGLE_STEP = 5f

/**
 * The last touch anywhere in the window, in screen pixels: where a dialog
 * opened by it comes out of (see [AlertDialog]).
 */
internal object LastTouch {
    @Volatile private var x = 0f
    @Volatile private var y = 0f
    @Volatile private var atMs = 0L

    fun at(x: Float, y: Float) {
        this.x = x
        this.y = y
        atMs = android.os.SystemClock.uptimeMillis()
    }

    /** The last touch, if it was recent enough to be what opened something. */
    fun recent(): Offset? =
        if (atMs != 0L && android.os.SystemClock.uptimeMillis() - atMs <= 1500L) Offset(x, y) else null
}

// ---- the pen panel's parts: tabs, live stroke, segments, width, colour views ----

/** The two faces of a pen's panel: how it draws, and its colour. */
/** The pen panel's tabs, in the order they are shown: the colour first, as it is what a pen is opened for most. */
internal enum class PenPanelTab(val label: String) { COLOR("Color"), SETTING("Setting") }

/** The tab a pen's panel opens on: the colour where the tool has one, else its settings. */
internal fun firstPenTab(tints: Boolean): PenPanelTab = if (tints) PenPanelTab.COLOR else PenPanelTab.SETTING

/** The pens the pen tool offers, as four segments; the speed fountain pen shows as the fountain pen. */
internal val PEN_TYPES = listOf(
    Tool.PEN to "Ballpoint", Tool.FOUNTAIN to "Fountain", Tool.CALLIGRAPHY to "Calligraphy", Tool.VANISHING_PEN to "Vanishing",
)

/** The segment a pen's [nib] shows as chosen. */
internal fun penTypeShown(nib: Tool): Tool = if (nib == Tool.SPEED_FOUNTAIN) Tool.FOUNTAIN else nib

/** Which extra row a pen's panel shows under its type: the line type, the nib's angle, the graphite's density, or none. */
internal enum class PenExtraRow { LINE_TYPE, NIB_ANGLE, DENSITY, NONE }

/** The extra row for [mode]'s pen drawing as [drawing] with [nib]: line types for the ballpoint and the vanishing pen, the angle for a cut nib. */
internal fun penExtraRow(mode: EditMode, nib: Tool, drawing: Tool): PenExtraRow = when {
    mode == EditMode.PEN && (nib == Tool.PEN || nib == Tool.VANISHING_PEN) -> PenExtraRow.LINE_TYPE
    drawing.hasNib() -> PenExtraRow.NIB_ANGLE
    drawing == Tool.PENCIL -> PenExtraRow.DENSITY
    else -> PenExtraRow.NONE
}

/** A width as the panel shows it: to a tenth. */
internal fun widthTenths(width: Float): String = String.format(java.util.Locale.US, "%.1f", width)

/** [width] to the nearest tenth. */
internal fun roundTenth(width: Float): Float = kotlin.math.round(width * 10f) / 10f

/** One tenth thinner ([direction] -1) or thicker (+1), on the tenth, within [range]. */
internal fun stepWidth(width: Float, direction: Int, range: ClosedFloatingPointRange<Float>): Float =
    roundTenth(width + 0.1f * direction).coerceIn(range)

/** The widths offered as presets until the reader keeps their own: the usual ones in [range], six at most. */
internal fun defaultWidthPresets(range: ClosedFloatingPointRange<Float>): List<Float> =
    listOf(0.5f, 1f, 2f, 3f, 5f, 8f, 12f, 20f, 30f, 50f).filter { it in range }.take(6)

/**
 * The colours of the grid view, [columns] across and [rows] down, row by
 * row: greys from white to black on top, then each hue from dark through
 * full to pale.
 */
internal fun gridColors(columns: Int, rows: Int): List<Int> {
    val out = ArrayList<Int>(columns * rows)
    for (c in 0 until columns) {
        val v = 1f - c / (columns - 1).coerceAtLeast(1).toFloat()
        out += android.graphics.Color.HSVToColor(floatArrayOf(0f, 0f, v))
    }
    for (r in 1 until rows) {
        val t = (r - 1) / (rows - 2).coerceAtLeast(1).toFloat()
        val value = (0.35f + 1.3f * t).coerceAtMost(1f)
        val saturation = if (t <= 0.5f) 1f else 1f - 1.5f * (t - 0.5f)
        for (c in 0 until columns) {
            out += android.graphics.Color.HSVToColor(floatArrayOf(c * 360f / columns, saturation, value))
        }
    }
    return out
}

/** The colour views the Color tab offers, in its order; the older HSV bars and RGB strips open as the ring and the disc. */
internal val COLOR_VIEWS = listOf(ColorPickerMode.GRID, ColorPickerMode.SQUARE, ColorPickerMode.BARS, ColorPickerMode.RING)

/** The view a stored picker choice opens on. */
internal fun colorViewOf(stored: Int): ColorPickerMode = when (ColorPickerMode.entries.getOrNull(stored)) {
    ColorPickerMode.DISC -> ColorPickerMode.RING
    ColorPickerMode.RGB -> ColorPickerMode.BARS
    null -> ColorPickerMode.SQUARE
    else -> ColorPickerMode.entries[stored]
}

/**
 * A row of choices on one capsule, the chosen one on a pill that slides to
 * it. Given [icon], each choice shows its name at rest and turns into a
 * picture of itself while a pointer hovers over the row or a finger is on
 * it, and back to its name a moment after.
 */
@Composable
private fun <T> MorphSegments(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (T, Color) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val index = options.indexOf(selected)
    val slide = remember { androidx.compose.animation.core.Animatable(index.coerceAtLeast(0).toFloat()) }
    // Eased across, speeding up and slowing down, and landing without a bounce.
    LaunchedEffect(index) { if (index >= 0) slide.animateTo(index.toFloat(), selectionEase()) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var touched by remember { mutableStateOf(false) }
    var showIcons by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, touched) {
        if (icon == null) return@LaunchedEffect
        if (hovered || touched) showIcons = true
        else {
            delay(ICON_LINGER_MS)
            showIcons = false
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(SEGMENT_HEIGHT)
            .clip(CircleShape)
            .background(scheme.onSurface.copy(alpha = 0.06f))
            .hoverable(interaction)
            // Watched, not taken: the segments under it still get the tap.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    touched = true
                    do {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    touched = false
                }
            },
    ) {
        val cell = maxWidth / options.size.coerceAtLeast(1)
        if (index >= 0) Box(
            Modifier
                .width(cell)
                .fillMaxHeight()
                .graphicsLayer { translationX = cell.toPx() * slide.value }
                .padding(3.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.16f)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { option ->
                val chosen = option == selected
                val tint = if (chosen) scheme.primary else scheme.onSurface
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            role = androidx.compose.ui.semantics.Role.Tab,
                        ) { onSelect(option) }
                        .segmentSemantics(label(option), chosen),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon == null) {
                        SegmentLabel(label(option), tint)
                    } else {
                        androidx.compose.animation.AnimatedContent(
                            targetState = showIcons,
                            transitionSpec = {
                                val enter = fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(200), initialScale = 0.7f)
                                val exit = fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(160), targetScale = 1.15f)
                                enter togetherWith exit
                            },
                            label = "segment face",
                        ) { asIcon -> if (asIcon) icon(option, tint) else SegmentLabel(label(option), tint) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentLabel(text: String, tint: Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
}

/** A choice's name and whether it is the chosen one, for a screen reader. */
private fun Modifier.segmentSemantics(name: String, chosen: Boolean): Modifier =
    this.then(Modifier.semantics {
        contentDescription = name
        selected = chosen
    })

/** A picture of a pen or pencil kind, drawn as a short stroke the way that kind draws it. */
@Composable
private fun PenGlyph(tool: Tool, tint: Color) {
    Canvas(Modifier.size(width = 44.dp, height = 22.dp)) {
        val steps = 28
        fun at(t: Float) = Offset(size.width * (0.08f + 0.84f * t),
            size.height * (0.5f - 0.3f * kotlin.math.sin(t * 2f * kotlin.math.PI.toFloat())))
        when (tool) {
            Tool.FOUNTAIN, Tool.SPEED_FOUNTAIN, Tool.CALLIGRAPHY -> {
                val flat = if (tool == Tool.CALLIGRAPHY) 0.18f else 0.55f
                val nib = (if (tool == Tool.CALLIGRAPHY) 8.dp else 5.dp).toPx()
                for (i in 0..steps * 2) {
                    val p = at(i / (steps * 2f))
                    rotate(45f, pivot = p) {
                        drawOval(tint, topLeft = Offset(p.x - nib / 2f, p.y - nib * flat / 2f), size = Size(nib, nib * flat))
                    }
                }
            }
            Tool.VANISHING_PEN -> for (i in 0 until steps) {
                drawLine(tint.copy(alpha = tint.alpha * (1f - i / steps.toFloat() * 0.85f)), at(i / steps.toFloat()),
                    at((i + 1) / steps.toFloat()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            Tool.PENCIL -> for (i in 0..steps * 3) {
                val p = at(i / (steps * 3f))
                val jitter = ((i * 37) % 7 - 3) * 0.3f
                drawCircle(tint.copy(alpha = 0.75f), 0.9.dp.toPx(), Offset(p.x, p.y + jitter))
            }
            Tool.SOFT_PENCIL -> for (i in 0 until steps) {
                drawLine(tint.copy(alpha = 0.5f), at(i / steps.toFloat()), at((i + 1) / steps.toFloat()),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
            Tool.STAMP -> for (i in 0..6) drawCircle(tint, 2.dp.toPx(), at(i / 6f))
            Tool.SPRAY -> for (i in 0..40) {
                val p = at(((i * 13) % 41) / 40f)
                drawCircle(tint.copy(alpha = 0.7f), 0.7.dp.toPx(),
                    Offset(p.x + ((i * 7) % 9 - 4) * 0.6f, p.y + ((i * 11) % 9 - 4) * 0.6f))
            }
            else -> for (i in 0 until steps) {
                drawLine(tint, at(i / steps.toFloat()), at((i + 1) / steps.toFloat()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

/** A line type drawn as the line itself: solid, dotted or dashed. */
@Composable
private fun LineGlyph(pattern: Int, tint: Color) {
    Canvas(Modifier.size(width = 40.dp, height = 16.dp)) {
        val w = 2.5.dp.toPx()
        val effect = when (pattern) {
            1 -> PathEffect.dashPathEffect(floatArrayOf(0.1f, w * 2.2f))
            2 -> PathEffect.dashPathEffect(floatArrayOf(w * 3f, w * 2f))
            else -> null
        }
        drawLine(tint, Offset(w, size.height / 2f), Offset(size.width - w, size.height / 2f),
            strokeWidth = w, cap = StrokeCap.Round, pathEffect = effect)
    }
}

/**
 * The pen as it will draw, across the top of its panel: its colour,
 * thickness and line type, the shape of a cut nib at its angle, the taper of
 * pressure and the fade of the vanishing pen. The eraser shows its reach.
 */
@Composable
private fun PenStrokePreview(
    width: Float, color: Color?, linePattern: Int, tool: Tool, nibAngle: Int?, pressure: Boolean, modifier: Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    Canvas(
        modifier
            .clip(pickerShape)
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, pickerShape),
    ) {
        val stroke = width.dp.toPx().coerceIn(1f, size.height * 0.4f)
        if (color == null) {
            drawCircle(outline, stroke / 2f, center, style = Stroke(2.dp.toPx()))
            return@Canvas
        }
        val pad = stroke / 2f + 12.dp.toPx()
        val swing = (size.height / 2f - pad).coerceAtLeast(0f)
        fun at(t: Float) = Offset(pad + (size.width - 2 * pad) * t,
            size.height / 2f - swing * kotlin.math.sin(t * 2f * kotlin.math.PI.toFloat() * 1.25f))
        val steps = 96
        when {
            tool.hasNib() -> {
                val angle = (nibAngle ?: tool.defaultNibAngle()).toFloat()
                val flat = if (tool == Tool.CALLIGRAPHY) 0.18f else 0.55f
                val nib = stroke * 1.8f
                for (i in 0..steps * 2) {
                    val p = at(i / (steps * 2f))
                    rotate(angle, pivot = p) {
                        drawOval(color, topLeft = Offset(p.x - nib / 2f, p.y - nib * flat / 2f), size = Size(nib, nib * flat))
                    }
                }
            }
            tool == Tool.VANISHING_PEN || pressure || tool == Tool.PRESSURE_PEN -> for (i in 0 until steps) {
                val t = i / steps.toFloat()
                val fade = if (tool == Tool.VANISHING_PEN) 1f - t * 0.85f else 1f
                val taper = if (pressure || tool == Tool.PRESSURE_PEN) 0.35f + 0.65f * kotlin.math.sin(t * kotlin.math.PI.toFloat()) else 1f
                drawLine(color.copy(alpha = color.alpha * fade), at(t), at((i + 1) / steps.toFloat()),
                    strokeWidth = stroke * taper, cap = StrokeCap.Round)
            }
            else -> {
                val path = Path().apply {
                    moveTo(at(0f).x, at(0f).y)
                    for (i in 1..steps) at(i / steps.toFloat()).let { lineTo(it.x, it.y) }
                }
                val dash = stroke.coerceAtLeast(2f)
                val effect = when (linePattern) {
                    1 -> PathEffect.dashPathEffect(floatArrayOf(0.1f, dash * 2f))
                    2 -> PathEffect.dashPathEffect(floatArrayOf(dash * 3f, dash * 2f))
                    3 -> PathEffect.dashPathEffect(floatArrayOf(dash * 3f, dash * 1.5f, 0.1f, dash * 1.5f))
                    else -> null
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, pathEffect = effect))
            }
        }
    }
}

/**
 * The panel's tabs, drawn over each other in one box as tall as the tallest
 * of them, so changing tab never changes the panel's size; the one shown
 * slides and fades in over the one it replaces. A tab out of sight takes no
 * touches and says nothing to a screen reader.
 */
@Composable
private fun EqualTabs(selected: Int, animate: Boolean, pages: List<@Composable () -> Unit>) {
    val position = remember { androidx.compose.animation.core.Animatable(selected.toFloat()) }
    LaunchedEffect(selected) {
        if (animate) position.animateTo(selected.toFloat(), TAB_SPRING.paced()) else position.snapTo(selected.toFloat())
    }
    val shift = with(LocalDensity.current) { TAB_SHIFT.toPx() }
    Layout(content = {
        pages.forEachIndexed { index, page ->
            val shown = index == selected
            Box(
                if (shown) Modifier else Modifier
                    .clearAndSetSemantics {}
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                .changes.forEach { it.consume() }
                        }
                    },
            ) { page() }
        }
    }) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeWithLayer(0, 0, zIndex = if (index == selected) 1f else 0f) {
                    val d = index - position.value
                    alpha = (1f - kotlin.math.abs(d)).coerceIn(0f, 1f)
                    translationX = d * shift
                }
            }
        }
    }
}

/** A round − or + beside the width slider: a tenth at a tap. */
@Composable
private fun StepButton(symbol: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(STEP_BUTTON)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, style = MaterialTheme.typography.titleMedium) }
}

/**
 * The width: its value to a tenth in a box to type in, a − and + a tenth
 * each either side of the slider, and the presets under them - the widths
 * kept with the star, or the usual ones until there are any.
 */
@Composable
private fun WidthBlock(mode: EditMode, width: Float, range: ClosedFloatingPointRange<Float>, onWidth: (Float) -> Unit) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    var favorites by remember(mode) { mutableStateOf(store.favoriteWidths(mode)) }
    val shown = width.coerceIn(range)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Width", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        // In millimetres on an A4 page as well; not for the eraser, whose size is on the screen.
        if (mode != EditMode.ERASE) Text(millimetreLabel(shown), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = Ui.Space.s))
        var typed by remember { mutableStateOf(widthTenths(shown)) }
        var focused by remember { mutableStateOf(false) }
        LaunchedEffect(shown) { if (!focused) typed = widthTenths(shown) }
        CompactField(
            text = typed,
            onText = { text -> typed = text; parseWidth(text, range)?.let { onWidth(roundTenth(it).coerceIn(range)) } },
            label = null, suffix = null, keyboard = KeyboardType.Decimal,
            isError = parseWidth(typed, range) == null,
            modifier = Modifier.width(WIDTH_BOX).onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) typed = widthTenths(shown)
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        StepButton("−", "Thinner") { onWidth(stepWidth(shown, -1, range)) }
        SkinSlider(value = shown, onValueChange = { onWidth(roundTenth(it).coerceIn(range)) }, valueRange = range,
            step = WIDTH_SLIDER_STEP, modifier = Modifier.weight(1f))
        StepButton("+", "Thicker") { onWidth(stepWidth(shown, 1, range)) }
    }
    val presets = favorites.ifEmpty { defaultWidthPresets(range) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
        verticalAlignment = Alignment.CenterVertically) {
        presets.forEach { preset ->
            SettingsChoiceChip(selected = kotlin.math.abs(preset - shown) < 0.05f,
                onClick = { onWidth(preset.coerceIn(range)) }, label = widthTenths(preset))
        }
        val starred = favorites.any { kotlin.math.abs(it - shown) < 0.05f }
        TextButton(onClick = {
            favorites = if (starred) favorites.filterNot { kotlin.math.abs(it - shown) < 0.05f }
                else (favorites + roundTenth(shown)).distinct().sorted()
            store.saveFavoriteWidths(mode, favorites)
        }) { Text(if (starred) "★" else "☆ Keep") }
    }
}

/**
 * A text box one size everywhere in the panel - the width, H, S, V, R, G,
 * B and the hex code alike - as tall as the eyedropper beside them, with its
 * letter inside it rather than floating over its edge.
 */
@Composable
private fun CompactField(
    text: String,
    onText: (String) -> Unit,
    label: String?,
    suffix: String?,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = pickerShape
    androidx.compose.foundation.text.BasicTextField(
        value = text,
        onValueChange = onText,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier
            .height(FIELD_COMPACT)
            .clip(shape)
            .border(1.dp, if (isError) scheme.error else scheme.outlineVariant, shape),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (label != null) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.outline)
                    Spacer(Modifier.width(6.dp))
                }
                Box(Modifier.weight(1f)) { inner() }
                if (suffix != null) Text(suffix, style = MaterialTheme.typography.labelMedium, color = scheme.outline)
            }
        },
    )
}

/** A whole number in a [CompactField], applied once it is a number in [range]. */
@Composable
private fun CompactNumber(label: String, value: Int, range: IntRange, suffix: String?, modifier: Modifier, onValue: (Int) -> Unit) {
    var draft by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) draft = null }
    CompactField(
        text = draft ?: value.toString(),
        onText = { typed ->
            val digits = typed.filter { it.isDigit() }.take(3)
            draft = digits
            digits.toIntOrNull()?.takeIf { it in range }?.let(onValue)
        },
        label = label, suffix = suffix, keyboard = KeyboardType.Number,
        modifier = modifier.onFocusChanged { focused = it.isFocused; if (!it.isFocused) draft = null },
    )
}

/** Every colour at a tap: greys along the top, then each hue from dark to pale. A drag runs over them. */
@Composable
private fun ColorGrid(color: PenColorState) {
    val columns = GRID_COLUMNS
    val rows = GRID_ROWS
    val cells = remember { gridColors(columns, rows) }
    val current = color.rgb()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(PICKER_HEIGHT)
            .clip(pickerShape)
            .pointerInput(color) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    fun take(at: Offset) {
                        val c = (at.x / size.width * columns).toInt().coerceIn(0, columns - 1)
                        val r = (at.y / size.height * rows).toInt().coerceIn(0, rows - 1)
                        color.set(cells[r * columns + c], keepAlpha = true)
                    }
                    take(down.position)
                    drag(down.id) { change -> change.consume(); take(change.position) }
                }
            },
    ) {
        val cw = size.width / columns
        val ch = size.height / rows
        for (r in 0 until rows) for (c in 0 until columns) {
            drawRect(Color(cells[r * columns + c]), Offset(c * cw, r * ch), Size(cw + 0.5f, ch + 0.5f))
        }
        val chosen = cells.indexOf(current)
        if (chosen >= 0) {
            val topLeft = Offset((chosen % columns) * cw, (chosen / columns) * ch)
            drawRect(Color.White, topLeft, Size(cw, ch), style = Stroke(3.dp.toPx()))
            drawRect(Color.Black.copy(alpha = 0.5f), topLeft, Size(cw, ch), style = Stroke(1.dp.toPx()))
        }
    }
}

/**
 * The colour view, which changes shape rather than being swapped: behind it
 * a pane that runs the width of the panel for the grid and the square and
 * draws in to the circle of the ring and the disc, on a spring, while the
 * views cross over it, the new one growing out of the old one's place.
 */
@Composable
private fun MorphingPicker(view: ColorPickerMode, animate: Boolean, content: @Composable (ColorPickerMode) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val corner = with(LocalDensity.current) { nestedCorner(Ui.Corner.control).toPx() }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(PICKER_HEIGHT)) {
        val full = constraints.maxWidth.toFloat()
        val tall = constraints.maxHeight.toFloat()
        val round = view == ColorPickerMode.RING
        val spec = if (animate) MORPH_SPRING.paced() else androidx.compose.animation.core.snap()
        val paneWidth by androidx.compose.animation.core.animateFloatAsState(if (round) tall else full, spec, label = "pane width")
        val paneCorner by androidx.compose.animation.core.animateFloatAsState(if (round) tall / 2f else corner, spec, label = "pane corner")
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(scheme.onSurface.copy(alpha = 0.06f), Offset.Zero, Size(paneWidth, size.height),
                androidx.compose.ui.geometry.CornerRadius(paneCorner))
        }
        androidx.compose.animation.AnimatedContent(
            targetState = view,
            transitionSpec = {
                val enter = if (!animate) androidx.compose.animation.EnterTransition.None
                    else fadeIn(tween(220, delayMillis = 40)) + androidx.compose.animation.scaleIn(MORPH_SPRING,
                        initialScale = 0.86f, transformOrigin = TransformOrigin(0f, 0.5f))
                val exit = if (!animate) androidx.compose.animation.ExitTransition.None
                    else fadeOut(tween(140)) + androidx.compose.animation.scaleOut(tween(180), targetScale = 1.05f,
                        transformOrigin = TransformOrigin(0f, 0.5f))
                enter togetherWith exit
            },
            contentAlignment = Alignment.CenterStart,
            label = "colour view",
        ) { shownView -> content(shownView) }
    }
}

private val SEGMENT_HEIGHT = 40.dp
private val STEP_BUTTON = 40.dp
private val WIDTH_BOX = 76.dp
/** Every box in the panel - fields, hex, eyedropper - is this tall. */
private val FIELD_COMPACT = 44.dp
private val TAB_SHIFT = 28.dp
private val PEN_PANEL_WIDTH = 480.dp
private val PREVIEW_HEIGHT = 64.dp
private val LINE_TYPE_LABEL = 88.dp
private val PALETTE_BELOW_HEIGHT = 220.dp
private const val GRID_COLUMNS = 12
private const val GRID_ROWS = 9
/** How long a row of segments keeps its pictures after the pointer or finger leaves it. */
private const val ICON_LINGER_MS = 700L
private val TAB_SPRING = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.9f, stiffness = 420f)
private val MORPH_SPRING = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.78f, stiffness = 300f)
