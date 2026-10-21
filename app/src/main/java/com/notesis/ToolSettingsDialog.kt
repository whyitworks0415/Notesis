package com.notesis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The five ways of picking a colour. The choice is remembered across tools. */
internal enum class ColorPickerMode(val label: String) {
    SQUARE("Square"),
    BARS("HSV"),
    RING("Ring"),
    RGB("RGB"),
    DISC("Disc"),
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
 * Where the toolbar sits on screen, the point the pen panel grows out of and
 * folds back into. Written by the bar as it is laid out; null before then.
 */
internal object PenPopupAnchor {
    @Volatile var screen: Offset? = null
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
    )

    // Open: grow from the bar. Close: fold back, and only then hand the
    // result over, since the host takes the panel away the moment it has it.
    val animate = rememberLiquidGlassEffectsAllowed()
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    var closing by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(closing) {
        val after = closing
        if (after == null) {
            if (animate) progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 520f))
            else progress.snapTo(1f)
        } else {
            if (animate) progress.animateTo(0f, tween(150))
            after()
        }
    }
    fun close(after: () -> Unit) {
        if (closing == null) closing = after
    }
    fun keep() = close { onConfirm(current()) }
    fun cancel() = close(onDismiss)
    var origin by remember { mutableStateOf(TransformOrigin(0.5f, 0f)) }
    val wide = LocalConfiguration.current.screenWidthDp >= 720

    Dialog(onDismissRequest = ::keep, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A light dim: the page should still show through the glass.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0.12f)
        val shape = RoundedCornerShape(28.dp)
        val tallest = LocalConfiguration.current.screenHeightDp.dp * 0.9f
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = if (wide) 880.dp else 600.dp)
                .fillMaxWidth()
                .heightIn(max = tallest)
                // Before the layer, so the bounds are the panel's own, unscaled.
                .onGloballyPositioned { coordinates ->
                    val anchor = PenPopupAnchor.screen ?: return@onGloballyPositioned
                    val at = coordinates.positionOnScreen()
                    val w = coordinates.size.width.toFloat()
                    val h = coordinates.size.height.toFloat()
                    if (w > 0f && h > 0f) {
                        origin = TransformOrigin(((anchor.x - at.x) / w).coerceIn(0f, 1f), ((anchor.y - at.y) / h).coerceIn(0f, 1f))
                    }
                }
                .graphicsLayer {
                    val t = progress.value
                    alpha = t.coerceIn(0f, 1f)
                    scaleX = 0.82f + 0.18f * t
                    scaleY = 0.82f + 0.18f * t
                    transformOrigin = origin
                }
                .liquidGlass(
                    shape = shape,
                    surfaceColor = MaterialTheme.colorScheme.surface,
                    surfaceOpacity = 0.86f,
                    useSpotiStyle = false,
                )
                .clip(shape)
                // Outside the scroll, so the panel's own height follows its
                // content as the detail opens and closes.
                .animateContentSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(penPanelTitle(mode), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = ::cancel, modifier = Modifier.heightIn(min = 44.dp)) { Text("Cancel") }
                TextButton(onClick = ::keep, modifier = Modifier.heightIn(min = 44.dp)) { Text("Save") }
            }

            val colorPart: @Composable () -> Unit = {
                if (mode == EditMode.ERASE) {
                    SectionHeader("Eraser")
                    EraserSection(partialEraser, onPartialEraser, gestures, onGestures)
                } else if (mode.tints) {
                    SectionHeader("Color")
                    ColorSection(
                        color = color,
                        opacity = opacity,
                        initialMode = store.colorPickerMode,
                        onMode = { store.colorPickerMode = it },
                        joined = joined,
                        // Handed over at once, not after the fold: the eyedropper
                        // takes the panel away itself.
                        onEyedropper = onEyedropper?.let { pick -> { onConfirm(current()); pick() } },
                    )
                }
            }
            val strokePart: @Composable () -> Unit = {
                SectionHeader(if (mode == EditMode.PEN) "Pen width" else "Width")
                WidthSection(
                    mode = mode,
                    width = width,
                    range = range,
                    onWidth = { width = it },
                    preview = if (mode == EditMode.ERASE) null
                        else Color(if (opacity) color.argb() else color.rgb()),
                    linePattern = if (freehand && !drawing.isDabbed()) linePattern else 0,
                )

                val nibs = if (freehand) nibsFor(pen.tool) else emptyList()
                if (nibs.isNotEmpty() || mode == EditMode.PEN) {
                    SectionHeader(if (mode == EditMode.PENCIL) "Pencil type" else "Pen type")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                            nibs.forEach { (tool, label) ->
                                SettingsChoiceChip(
                                    selected = if (mode == EditMode.PENCIL) drawing == tool else nib == tool,
                                    onClick = { nib = tool },
                                    label = label,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                        }
                        if (mode == EditMode.PEN) {
                            Text("Pressure", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            SkinSwitch(checked = pressure, onCheckedChange = { pressure = it })
                        }
                    }
                }

                if (freehand && !drawing.isDabbed()) {
                    SectionHeader("Line style")
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("Solid", "Dotted", "Dashed", "Dash-dot").forEachIndexed { index, label ->
                            SettingsChoiceChip(
                                selected = linePattern == index,
                                onClick = { linePattern = index },
                                label = label,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                }

                if (mode in STRAIGHT_LINE_MODES) {
                    SwitchRow(
                        "Straight line",
                        straightLine,
                        hint = "Draws a straight line from where the pen lands to where it lifts",
                    ) { straightLine = it }
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GAP)) { colorPart() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GAP)) { strokePart() }
                }
            } else {
                colorPart()
                strokePart()
            }

            // The rest of this tool's settings, opened downward on demand.
            Row(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { details = !details }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Reicons.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(if (details) "Fewer settings" else "More for this ${penPanelTitle(mode).lowercase()}",
                    style = MaterialTheme.typography.bodyMedium)
            }
            AnimatedVisibility(
                visible = details,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Only this tool", style = MaterialTheme.typography.labelLarge)
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
                        SwitchRow("Recognize shapes", autoShapes) { autoShapes = it }
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
                        SwitchRow("Snap lines to 90° steps", axisSnap, onChange = onAxisSnap)
                        var corner by remember { mutableFloatStateOf(store.shapeCornerRadius) }
                        Text("Polygon corner rounding ${corner.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
                        SkinSlider(corner, { corner = it; store.shapeCornerRadius = it; onCornerRadius(it) }, 0f..120f, step = 1f)
                    }
                    if (mode == EditMode.HIGHLIGHTER) {
                        SwitchRow(if (highlighterAboveInk) "Shown above the writing" else "Shown under the writing",
                            highlighterAboveInk, onChange = onHighlighterAboveInk)
                    }
                    if (mode == EditMode.ERASE && onClearPage != null) {
                        TextButton(onClick = onClearPage, modifier = Modifier.heightIn(min = 44.dp)) {
                            Icon(Reicons.Delete, contentDescription = null)
                            Text(" Clear this whole page")
                        }
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
}

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
        SettingsToggle("Compatible wet ink", compatWetInk, onCompatWetInk,
            hint = "Turn on if strokes only appear once the pen lifts. On by default on the Galaxy Tab S6 Lite.")
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
        if (auto) "Automatic draws 9 ms ahead at 60 Hz (12 ms with compatible wet ink) and 6 ms at 90 Hz and up"
        else "Draws this far ahead of the pen. 0 turns prediction off. Longer feels faster, but stroke ends can wobble",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(10.dp))
        SkinSwitch(checked = checked, onCheckedChange = onChange)
    }
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
            label = "Whole strokes", modifier = Modifier.padding(end = 8.dp))
        SettingsChoiceChip(selected = partialEraser, onClick = { onPartialEraser(true) }, label = "Partial")
    }
    Text(if (partialEraser) "Erases only where it passes" else "Erases every stroke it touches, whole",
        style = MaterialTheme.typography.bodySmall)
    Text("Erases", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(
            EraseTarget.INK to gestures.eraseInk, EraseTarget.HIGHLIGHTER to gestures.eraseHighlighter,
            EraseTarget.TAPE to gestures.eraseTape, EraseTarget.IMAGES to gestures.eraseImages,
            EraseTarget.TEXT to gestures.eraseText, EraseTarget.LOCKED to gestures.eraseLocked,
        ).forEach { (target, on) ->
            SettingsChoiceChip(selected = on, label = target.label, modifier = Modifier.padding(end = 6.dp), onClick = {
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
    SwitchRow(
        "Pressure eraser",
        gestures.pressureEraser,
        hint = "The set size is the lightest touch; pressing harder grows it up to 2.5 times",
    ) { onGestures(gestures.copy(pressureEraser = it)) }
}

private enum class EraseTarget(val label: String) {
    INK("Pen"), HIGHLIGHTER("Highlighter"), TAPE("Tape"), IMAGES("Pictures"), TEXT("Text boxes"), LOCKED("Locked objects"),
}

// ---- colour ----------------------------------------------------------------

/** The one gap the panel uses everywhere, so every edge lines up with every other. */
internal val GAP = 10.dp

private val PICKER_HEIGHT = 190.dp
private val STRIP_HEIGHT = 30.dp
private val BAR_WIDTH = 34.dp
private val LABEL_HEIGHT = 18.dp
private val FIELD_HEIGHT = 56.dp
private val PALETTE_WIDTH = 96.dp
private val PICKER_SHAPE = RoundedCornerShape(16.dp)

/** A section's name with a hairline running out to the right edge. */
@Composable
private fun SectionHeader(label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(GAP))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** One of a row of equal pills that share the width between them. */
@Composable
private fun ModePill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else scheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColorSection(
    color: PenColorState,
    opacity: Boolean,
    initialMode: Int,
    onMode: (Int) -> Unit,
    joined: Boolean,
    onEyedropper: (() -> Unit)?,
) {
    var pickerMode by remember {
        mutableStateOf(ColorPickerMode.entries.getOrElse(initialMode) { ColorPickerMode.SQUARE })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
        ColorPickerMode.entries.forEach { option ->
            ModePill(option.label, pickerMode == option, Modifier.weight(1f)) {
                pickerMode = option
                onMode(option.ordinal)
            }
        }
    }
    PaletteBeside(
        main = {
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                when (pickerMode) {
                    ColorPickerMode.SQUARE -> SquarePicker(color, joined)
                    ColorPickerMode.BARS -> BarsPicker(color, joined)
                    ColorPickerMode.RING -> RingPicker(color)
                    ColorPickerMode.RGB -> RgbPicker(color, joined)
                    ColorPickerMode.DISC -> DiscPicker(color, joined)
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
                        Text(
                            "${(color.alpha * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(36.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    HexField(color, opacity, Modifier.weight(1f).height(FIELD_HEIGHT))
                    if (onEyedropper != null) {
                        val shape = MaterialTheme.shapes.extraSmall
                        Box(
                            Modifier
                                .size(FIELD_HEIGHT)
                                .clip(shape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                                .clickable(onClick = onEyedropper),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Reicons.Colorize, contentDescription = "Eyedropper", modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        },
        palette = { PalettePanel(color, opacity) },
    )
}

/**
 * The picker on the left and the palette on the right, the palette exactly
 * as tall as everything on the left - picker, opacity and the hex row - so
 * both columns start and end on the same lines.
 */
@Composable
private fun PaletteBeside(main: @Composable () -> Unit, palette: @Composable () -> Unit) {
    Layout(contents = listOf(main, palette)) { measurables, constraints ->
        val gap = GAP.roundToPx()
        val paletteWidth = PALETTE_WIDTH.roundToPx().coerceAtMost(constraints.maxWidth / 3)
        val mainWidth = (constraints.maxWidth - paletteWidth - gap).coerceAtLeast(0)
        val mains = measurables[0].map { it.measure(Constraints(minWidth = mainWidth, maxWidth = mainWidth)) }
        val height = mains.maxOfOrNull { it.height } ?: 0
        val palettes = measurables[1].map { it.measure(Constraints.fixed(paletteWidth, height)) }
        layout(constraints.maxWidth, height) {
            mains.forEach { it.place(0, 0) }
            palettes.forEach { it.place(mainWidth + gap, 0) }
        }
    }
}

/** 1. Saturation across, value down, hue on the strip below. */
@Composable
private fun SquarePicker(color: PenColorState, joined: Boolean) {
    Column(
        Modifier.fillMaxWidth().height(PICKER_HEIGHT).then(if (joined) Modifier.clip(PICKER_SHAPE) else Modifier),
        verticalArrangement = Arrangement.spacedBy(if (joined) 0.dp else GAP),
    ) {
        SaturationValueField(
            color.hue, color.saturation, color.value,
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = if (joined) RectangleShape else PICKER_SHAPE,
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

/** 2. Three upright H, S and V bars, with the colour itself beside them. */
@Composable
private fun BarsPicker(color: PenColorState, joined: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(PICKER_HEIGHT).then(if (joined) Modifier.clip(PICKER_SHAPE) else Modifier),
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
                    .then(if (joined) Modifier else Modifier.clip(PICKER_SHAPE))
                    .background(Color.White)
                    .background(Color(color.argb())),
                contentAlignment = if (joined) Alignment.TopStart else Alignment.BottomStart,
            ) {
                Text(
                    "H ${color.hue.roundToInt()}°  S ${(color.saturation * 100).roundToInt()}%  V ${(color.value * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (color.value > 0.6f && color.saturation < 0.5f) Color.Black.copy(alpha = 0.7f)
                        else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(GAP),
                )
            }
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GAP)) {
            NumberField("H", color.hue.roundToInt(), 0..360, "°", Modifier.fillMaxWidth()) { color.hue = it.toFloat() % 360f }
            NumberField("S", (color.saturation * 100).roundToInt(), 0..100, "%", Modifier.fillMaxWidth()) { color.saturation = it / 100f }
            NumberField("V", (color.value * 100).roundToInt(), 0..100, "%", Modifier.fillMaxWidth()) { color.value = it / 100f }
        }
    }
}

/** 4. Red, green and blue, each on a strip that shows where it would go. */
@Composable
private fun RgbPicker(color: PenColorState, joined: Boolean) {
    val rgb = color.rgb()
    val red = android.graphics.Color.red(rgb)
    val green = android.graphics.Color.green(rgb)
    val blue = android.graphics.Color.blue(rgb)
    val channels = listOf(
        Triple("R", red, listOf(Color(0, green, blue), Color(255, green, blue))),
        Triple("G", green, listOf(Color(red, 0, blue), Color(red, 255, blue))),
        Triple("B", blue, listOf(Color(red, green, 0), Color(red, green, 255))),
    )
    fun set(label: String, next: Int) = when (label) {
        "R" -> color.setRgb(next, green, blue)
        "G" -> color.setRgb(red, next, blue)
        else -> color.setRgb(red, green, next)
    }
    Row(Modifier.fillMaxWidth().height(PICKER_HEIGHT), horizontalArrangement = Arrangement.spacedBy(GAP)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().then(if (joined) Modifier.clip(PICKER_SHAPE) else Modifier),
            verticalArrangement = Arrangement.spacedBy(if (joined) 0.dp else GAP),
        ) {
            channels.forEach { (label, channel, ramp) ->
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                    GradientStrip(
                        colors = ramp,
                        position = channel / 255f,
                        modifier = Modifier.fillMaxSize(),
                        shape = if (joined) RectangleShape else PICKER_SHAPE,
                    ) { set(label, (it * 255f).roundToInt()) }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(start = GAP),
                    )
                }
            }
        }
        Column(
            Modifier.width(84.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(if (joined) 0.dp else GAP),
        ) {
            channels.forEach { (label, channel, _) ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    NumberField(null, channel, 0..255, null, Modifier.fillMaxWidth()) { set(label, it) }
                }
            }
        }
    }
}

/** 5. A colour disc - hue around, saturation outward - and brightness beside it. */
@Composable
private fun DiscPicker(color: PenColorState, joined: Boolean) {
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
                        fun take(at: Offset) {
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val reach = (min(size.width, size.height) / 2f - thumb).coerceAtLeast(1f)
                            val d = at - c
                            color.hue = angleOf(d)
                            color.saturation = (hypot(d.x, d.y) / reach).coerceIn(0f, 1f)
                        }
                        take(down.position)
                        drag(down.id) { change -> change.consume(); take(change.position) }
                    }
                },
        ) {
            val radius = size.minDimension / 2f
            drawCircle(Brush.sweepGradient(HUES, center), radius)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radius), radius)
            drawCircle(Color.Black.copy(alpha = 1f - color.value), radius)
            val angle = Math.toRadians(color.hue.toDouble())
            val r = color.saturation * (radius - thumb)
            drawPickerThumb(
                Offset(center.x + (r * kotlin.math.cos(angle)).toFloat(), center.y + (r * kotlin.math.sin(angle)).toFloat()),
                thumb,
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Brightness ${(color.value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.height(LABEL_HEIGHT))
            Box(Modifier.weight(1f)) {
                VerticalStrip(
                    listOf(Color.Black, Color.hsv(color.hue, color.saturation, 1f)),
                    color.value, null, joined = false,
                ) { color.value = it }
            }
        }
    }
}

/** The palette beside the picker: the built-in and saved colour sets, a tap each. */
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
    Column(
        Modifier
            .fillMaxSize()
            .clip(PICKER_SHAPE)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .verticalScroll(rememberScrollState())
            .padding(GAP),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Palette", style = MaterialTheme.typography.labelMedium)
        for ((name, colors) in COLOR_TEMPLATES + custom) {
            Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            colors.chunked(3).forEach { row ->
                // Three to a row, spread edge to edge, so every row shares
                // the same columns whether it is full or not.
                Row(Modifier.fillMaxWidth()) {
                    for (index in 0 until 3) {
                        val argb = row.getOrNull(index)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (argb != null) Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
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
        }
        TextButton(onClick = { adding = true }) { Text("+ Add") }
    }
}

/** #RRGGBB, or #AARRGGBB where the tool can be see-through; applied as soon as it parses. */
@Composable
private fun HexField(color: PenColorState, opacity: Boolean, modifier: Modifier) {
    val shown = if (opacity) "%08X".format(color.argb()) else "%06X".format(color.rgb() and 0xFFFFFF)
    var draft by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(shown) { if (!focused) draft = null }
    OutlinedTextField(
        value = draft ?: shown,
        onValueChange = { typed ->
            val clean = typed.removePrefix("#").filter { it.isLetterOrDigit() }.take(if (opacity) 8 else 6)
            draft = clean
            val parsed = clean.toLongOrNull(16)
            if (parsed != null && (clean.length == 6 || (opacity && clean.length == 8))) {
                val argb = if (clean.length == 6) (parsed.toInt() or PenColorState.OPAQUE) else parsed.toInt()
                color.set(argb, keepAlpha = clean.length == 6)
            }
        },
        prefix = { Text("#") },
        singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused; if (!it.isFocused) draft = null },
    )
}

/** A small number box that applies whatever it holds once it is a number in [range]. */
@Composable
private fun NumberField(
    label: String?,
    value: Int,
    range: IntRange,
    suffix: String?,
    modifier: Modifier = Modifier.width(96.dp),
    onValue: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) draft = null }
    OutlinedTextField(
        value = draft ?: value.toString(),
        onValueChange = { typed ->
            val digits = typed.filter { it.isDigit() }.take(3)
            draft = digits
            digits.toIntOrNull()?.takeIf { it in range }?.let(onValue)
        },
        label = if (label != null) { { Text(label) } } else null,
        suffix = if (suffix != null) { { Text(suffix) } } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

@Composable
private fun WidthSection(
    mode: EditMode,
    width: Float,
    range: ClosedFloatingPointRange<Float>,
    onWidth: (Float) -> Unit,
    preview: Color?,
    linePattern: Int,
) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    var favorites by remember(mode) { mutableStateOf(store.favoriteWidths(mode)) }
    val shown = width.coerceIn(range)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP)) {
        Column(Modifier.weight(1f)) {
            SkinSlider(value = shown, onValueChange = onWidth, valueRange = range, step = WIDTH_SLIDER_STEP)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onWidth((shown - WIDTH_STEP).coerceIn(range)) }) { Text("−") }
                Text(widthLabel(shown), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onWidth((shown + WIDTH_STEP).coerceIn(range)) }) { Text("+") }
                val starred = favorites.any { kotlin.math.abs(it - shown) < 0.005f }
                TextButton(onClick = {
                    favorites = if (starred) favorites.filterNot { kotlin.math.abs(it - shown) < 0.005f }
                        else (favorites + shown).sorted()
                    store.saveFavoriteWidths(mode, favorites)
                }) { Text(if (starred) "★" else "☆") }
            }
            if (favorites.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                favorites.forEach { favorite ->
                    SettingsChoiceChip(
                        selected = kotlin.math.abs(favorite - shown) < 0.005f,
                        onClick = { onWidth(favorite.coerceIn(range)) },
                        label = widthLabel(favorite),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
        StrokePreview(width = shown, color = preview, linePattern = linePattern,
            modifier = Modifier.width(150.dp).height(72.dp))
    }
}

/** The line as it will draw - colour, see-through, thickness and pattern - or the eraser's reach. */
@Composable
private fun StrokePreview(width: Float, color: Color?, linePattern: Int, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Canvas(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
    ) {
        val stroke = width.dp.toPx().coerceIn(1f, size.height * 0.45f)
        if (color == null) {
            drawCircle(outline, stroke / 2f, center, style = Stroke(2.dp.toPx()))
            return@Canvas
        }
        val pad = stroke / 2f + 8.dp.toPx()
        val path = Path().apply {
            val left = pad
            val right = size.width - pad
            val mid = size.height / 2f
            val swing = (size.height / 2f - pad).coerceAtLeast(0f)
            moveTo(left, mid + swing * 0.4f)
            cubicTo(
                left + (right - left) * 0.3f, mid - swing,
                left + (right - left) * 0.55f, mid - swing,
                left + (right - left) * 0.7f, mid + swing * 0.3f,
            )
            quadraticTo(left + (right - left) * 0.85f, mid + swing, right, mid)
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
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** The tools whose strokes can be drawn as straight lines instead. */
internal val STRAIGHT_LINE_MODES = setOf(EditMode.PEN, EditMode.PENCIL, EditMode.HIGHLIGHTER, EditMode.MASK)
