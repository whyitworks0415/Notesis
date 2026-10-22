package com.notesis

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first

// Rest/lifted geometry from liquid_glass_easy 4.3.2 slider/switch layouts
// (MIT, Ahmed Gamil). Capture and Compose input are the Android adapter.
internal fun spotiSliderValue(fraction: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val bounded = fraction.coerceIn(0f, 1f)
    val snapped = if (steps > 0) (bounded * (steps + 1)).roundToInt().toFloat() / (steps + 1) else bounded
    return range.start + (range.endInclusive - range.start) * snapped
}

@Composable
private fun rememberControlMotion(target: Float, held: Boolean, travel: Float): ControlFrame {
    val look = LocalSkinSettings.current
    val allowed = rememberLiquidGlassEffectsAllowed() && !look.highContrast &&
        look.spotiglassClarity > 0f && look.spotiglassResponse > 0f && Build.VERSION.SDK_INT >= 33
    val motion = remember { SpotiGlassMotion(target, control = true) }
    var wake by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(target, held, allowed) {
        if (!allowed) motion.snap(target)
        else if (held) {
            if (!motion.dragging) motion.grab(target) else motion.follow(target)
        } else if (motion.dragging) motion.release(target)
        else motion.select(target)
        revision++
        wake++
    }
    // The ticker survives value updates: retarget the same springs, preserving
    // their velocities. Poll only while held or settling.
    val latestTravel by rememberUpdatedState(travel)
    LaunchedEffect(motion) {
        var observed = -1
        var previous = 0L
        while (isActive) {
            if (!motion.running) {
                snapshotFlow { wake }.first { it != observed }
                observed = wake
                previous = 0L
                if (!motion.running) continue
            }
            val now = withFrameNanos { it }
            if (previous != 0L) motion.tick(now / 1e9, (now - previous) / 1e9, latestTravel)
            revision++
            previous = now
        }
    }
    @Suppress("UNUSED_VARIABLE") val frame = revision
    return ControlFrame(motion.position, motion.growX, motion.growY, motion.progress,
        motion.presence, motion.deviation, motion.running)
}

private data class ControlFrame(val position: Float, val growX: Float, val growY: Float,
    val progress: Float, val presence: Float, val deviation: Float, val running: Boolean)

/** One scene captures the actual background AND track before the moving lens.
 * No permanent glass layer: at rest only a flat white thumb is drawn.
 *
 * [vertical] stands the slider on end for the page rail: the same track, lens
 * and lift measured along the height instead of the width, so the two read as
 * one control turned through a quarter. Top is the start; RTL does not flip it. */
@Composable
private fun ControlScene(
    modifier: Modifier, motion: ControlFrame, slider: Boolean, rtl: Boolean, enabled: Boolean = true,
    vertical: Boolean = false,
) {
    val look = LocalSkinSettings.current
    val scheme = MaterialTheme.colorScheme
    val response = look.spotiglassResponse
    val moving = motion.running && Build.VERSION.SDK_INT >= 33
    val white = Color.White.copy(alpha = if (enabled) 1f else 0.5f)
    val flip = rtl && !vertical
    val scene = modifier.spotiGlassLens(moving) { size, density ->
        val along = if (vertical) size.height else size.width
        val across = if (vertical) size.width else size.height
        val start = (if (slider) SPOTI_SLIDER_START.value else 31f) * density
        val travel = (along - start * 2).coerceAtLeast(0f)
        val visual = if (flip) 1 - motion.position else motion.position
        // Lengths along and across the travel. The lens is a wide capsule
        // lying on the track, so on a vertical rail it is a tall one.
        val w = (37 + 21 * motion.growX * response) * density
        val h = (24 + 14.333f * motion.growY * response) * density
        val sAlong = 1 + motion.deviation * response
        val sAcross = 1 - motion.deviation * response
        val c = start + travel * visual
        SpotiGlassLensFrame(
            center = if (vertical) Offset(across / 2, c) else Offset(c, across / 2),
            size = if (vertical) Size(h * sAcross, w * sAlong) else Size(w * sAlong, h * sAcross),
            radius = h / 2 * (look.spotiglassCorner / 32).coerceIn(0f, 1.5f),
            scale = if (vertical) Offset(sAcross, sAlong) else Offset(sAlong, sAcross),
            progress = motion.progress * look.spotiglassClarity,
            presence = motion.presence, fill = white,
            distortion = 0.12f * (look.refraction / 24),
            band = (if (slider) 18f else 13f) * (look.depth / 12),
            dispersion = 0.002f * (look.dispersion / 0.35f),
        )
    }
    // Capture only the track, never a rectangular patch of the dialog/page.
    Canvas(scene) {
        val along = if (vertical) size.height else size.width
        val across = if (vertical) size.width else size.height
        val inset = (if (slider) 10f else 10.5f).dp.toPx()
        val start = (if (slider) SPOTI_SLIDER_START.value else 31f).dp.toPx()
        val travel = (along - 2 * start).coerceAtLeast(0f)
        val fraction = motion.position.coerceIn(0f, 1f)
        val visual = if (flip) 1 - fraction else fraction
        val c = start + travel * visual
        val center = if (vertical) Offset(across / 2, c) else Offset(c, across / 2)
        val trackH = (if (slider) SPOTI_SLIDER_TRACK.value else 28f).dp.toPx()
        val trackW = (along - inset * 2).coerceAtLeast(0f)
        val trackOffset = if (vertical) Offset((across - trackH) / 2, inset) else Offset(inset, (across - trackH) / 2)
        fun bar(length: Float) = if (vertical) Size(trackH, length) else Size(length, trackH)
        drawRoundRect(scheme.onSurface.copy(alpha = if (look.highContrast) 0.34f else 0.18f),
            trackOffset, bar(trackW), CornerRadius(trackH / 2))
        val active = scheme.primary.copy(alpha = if (enabled) 1f else 0.4f)
        if (slider) {
            val fillLength = (c - inset).coerceIn(0f, trackW)
            if (vertical) {
                drawRoundRect(active, trackOffset, bar(fillLength), CornerRadius(trackH / 2))
            } else {
                val x = if (flip) center.x else inset
                drawRoundRect(active, Offset(x, trackOffset.y),
                    bar(if (flip) trackW - fillLength else fillLength), CornerRadius(trackH / 2))
            }
        } else {
            drawRoundRect(active.copy(alpha = active.alpha * fraction), trackOffset,
                bar(trackW), CornerRadius(trackH / 2))
        }
        if (!moving) {
            val thumb = if (vertical) Size(24.dp.toPx(), 37.dp.toPx()) else Size(37.dp.toPx(), 24.dp.toPx())
            val outline = spotiGlassPath(thumb, 12.dp.toPx() * (look.spotiglassCorner / 32).coerceIn(0f, 1.5f)).apply {
                translate(center - Offset(thumb.width / 2, thumb.height / 2))
            }
            drawPath(outline, white)
            // The horizontal slider's white lens sits on a glass bar; the rail
            // floats straight over paper, where white on white has no edge.
            // A hairline gives it one without changing the lens itself.
            if (vertical) {
                drawPath(outline, Color.Black.copy(alpha = if (look.highContrast) 0.40f else 0.16f),
                    style = Stroke(0.75.dp.toPx()))
            }
        }
    }
}

@Composable
internal fun SpotiGlassSlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier,
    valueRange: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean,
    label: String, valueFormatter: (Float) -> String, onValueChangeFinished: (() -> Unit)?,
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    val latestFraction by rememberUpdatedState(fraction)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var width by remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inset = with(density) { SPOTI_SLIDER_START.toPx() }
    val travel = (width - 2 * inset).coerceAtLeast(1f)
    // Held, the lens follows the finger itself; the stepped value it lands on
    // is only what is reported. Let go, it settles onto that value's stop.
    var dragFraction by remember { mutableFloatStateOf(NOT_DRAGGING) }
    val target = if (held && dragFraction >= 0f) dragFraction else fraction
    val motion = rememberControlMotion(target, held && enabled, travel / density.density)
    fun report(f: Float) {
        val raw = f.coerceIn(0f, 1f)
        if (held) dragFraction = raw
        change(spotiSliderValue(raw, valueRange, steps))
    }
    ControlScene(modifier.fillMaxWidth().height(48.dp).onSizeChanged { width = it.width }
        .semantics(mergeDescendants = true) {
            contentDescription = "$label ${valueFormatter(value)}"
            progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
            if (enabled) setProgress { target ->
                report(if (span > 0) (target - valueRange.start) / span else 0f)
                finish?.invoke(); true
            } else disabled()
        }.pointerInput(enabled, width, valueRange, steps, rtl) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                held = true
                val initial = latestFraction
                var claimed = false
                var committed = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = pointer.position - down.position
                        if (pointer.isConsumed) break
                        if (!pointer.pressed) {
                            if (!claimed) {
                                val f = ((pointer.position.x - inset) / travel).coerceIn(0f, 1f)
                                report(if (rtl) 1 - f else f)
                                committed = true
                                pointer.consume()
                            }
                            break
                        }
                        if (!claimed && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                        if (abs(delta.x) > viewConfiguration.touchSlop) claimed = true
                        if (claimed) {
                            pointer.consume()
                            // Relative dragging keeps a finger landing on the thumb
                            // from jumping it to the finger's edge.
                            report(initial + delta.x / travel * if (rtl) -1 else 1)
                            committed = true
                        }
                    }
                } finally {
                    held = false
                    dragFraction = NOT_DRAGGING
                    if (committed) finish?.invoke()
                }
            }
        }, motion, slider = true, rtl = rtl, enabled = enabled)
}

@Composable
internal fun SpotiGlassSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val change by rememberUpdatedState(onCheckedChange)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val travelPx = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }
    val target = if (dragging) dragFraction else if (checked) 1f else 0f
    val motion = rememberControlMotion(target, pressed || dragging, 22f)
    val latestPosition by rememberUpdatedState(motion.position)
    ControlScene(Modifier.size(84.dp, 48.dp)
        .toggleable(checked, interactionSource = interaction, indication = null, role = Role.Switch, onValueChange = onCheckedChange)
        .pointerInput(rtl) {
            detectHorizontalDragGestures(
                onDragStart = { dragFraction = latestPosition; dragging = true },
                onDragCancel = { dragging = false },
                onDragEnd = { change(dragFraction >= 0.5f); dragging = false },
                onHorizontalDrag = { pointer, delta ->
                    pointer.consume()
                    dragFraction = (dragFraction + delta / travelPx * if (rtl) -1 else 1).coerceIn(0f, 1f)
                },
            )
        }, motion, slider = false, rtl = rtl)
}

/** Where the slider's lens centre stops short of each end; the travel is what lies between. */
internal val SPOTI_SLIDER_START = 29.dp
/** The slider track's thickness, across the travel. */
private val SPOTI_SLIDER_TRACK = 6.dp
/** The resting lens is solid white, so its label keeps a fixed dark ink in every theme. */
private val SPOTI_THUMB_INK = Color(0xFF1C1B1F)

/**
 * The page scrubber's rail in the glass bar skin: [SpotiGlassSlider] stood on
 * end. The track, the white lens at rest, the glass lens and its stretch while
 * held all come from the same scene, so the vertical control is the horizontal
 * one turned through a quarter rather than a lookalike. The caller keeps the
 * gestures and page mapping; [target] is the 0..1 position from the top, and
 * the page number rides in the lens.
 */
@Composable
internal fun SpotiGlassPageRail(
    target: Float,
    held: Boolean,
    label: String,
    modifier: Modifier = Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var height by remember { mutableIntStateOf(0) }
    val start = with(density) { SPOTI_SLIDER_START.toPx() }
    val travel = (height - 2 * start).coerceAtLeast(1f)
    val motion = rememberControlMotion(target.coerceIn(0f, 1f), held, travel / density.density)
    val lensHeight = with(density) { 37.dp.toPx() }
    Box(modifier.onSizeChanged { height = it.height }) {
        ControlScene(Modifier.matchParentSize(), motion, slider = true, rtl = false, vertical = true)
        // Over the lens rather than inside the captured scene, so the number
        // stays crisp instead of being bent with the track. Solid lens, dark
        // ink; as the lens clears to glass, the theme's ink takes over.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset {
                    val centre = start + travel * motion.position.coerceIn(0f, 1f)
                    IntOffset(0, (centre - lensHeight / 2f).roundToInt())
                }
                .size(24.dp, 37.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.let {
                    // The lens is 24dp across: four digits only fit a size down.
                    if (label.length >= 4) it.copy(fontSize = 9.sp) else it
                },
                color = lerp(SPOTI_THUMB_INK, MaterialTheme.colorScheme.onSurface, motion.progress),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
