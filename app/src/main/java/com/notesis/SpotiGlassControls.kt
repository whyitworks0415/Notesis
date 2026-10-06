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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
 * No permanent glass layer: at rest only a flat white thumb is drawn. */
@Composable
private fun ControlScene(
    modifier: Modifier, motion: ControlFrame, slider: Boolean, rtl: Boolean, enabled: Boolean = true,
) {
    val look = LocalSkinSettings.current
    val scheme = MaterialTheme.colorScheme
    val response = look.spotiglassResponse
    val moving = motion.running && Build.VERSION.SDK_INT >= 33
    val white = Color.White.copy(alpha = if (enabled) 1f else 0.5f)
    val scene = modifier.spotiGlassLens(moving) { size, density ->
        val start = (if (slider) 29f else 31f) * density
        val travel = (size.width - start * 2).coerceAtLeast(0f)
        val visual = if (rtl) 1 - motion.position else motion.position
        val w = (37 + 21 * motion.growX * response) * density
        val h = (24 + 14.333f * motion.growY * response) * density
        val sx = 1 + motion.deviation * response
        val sy = 1 - motion.deviation * response
        SpotiGlassLensFrame(
            center = Offset(start + travel * visual, size.height / 2),
            size = Size(w * sx, h * sy), radius = h / 2 * (look.spotiglassCorner / 32).coerceIn(0f, 1.5f),
            scale = Offset(sx, sy), progress = motion.progress * look.spotiglassClarity,
            presence = motion.presence, fill = white,
            distortion = 0.12f * (look.refraction / 24),
            band = (if (slider) 18f else 13f) * (look.depth / 12),
            dispersion = 0.002f * (look.dispersion / 0.35f),
        )
    }
    // Capture only the track, never a rectangular patch of the dialog/page.
    Canvas(scene) {
        val inset = (if (slider) 10f else 10.5f).dp.toPx()
        val start = (if (slider) 29f else 31f).dp.toPx()
        val travel = (size.width - 2 * start).coerceAtLeast(0f)
        val fraction = motion.position.coerceIn(0f, 1f)
        val visual = if (rtl) 1 - fraction else fraction
        val center = Offset(start + travel * visual, size.height / 2)
        val trackH = (if (slider) 6f else 28f).dp.toPx()
        val trackW = (size.width - inset * 2).coerceAtLeast(0f)
        val trackOffset = Offset(inset, (size.height - trackH) / 2)
        drawRoundRect(scheme.onSurface.copy(alpha = if (look.highContrast) 0.34f else 0.18f),
            trackOffset, Size(trackW, trackH), CornerRadius(trackH / 2))
        val active = scheme.primary.copy(alpha = if (enabled) 1f else 0.4f)
        if (slider) {
            val fillWidth = (center.x - inset).coerceIn(0f, trackW)
            val x = if (rtl) center.x else inset
            drawRoundRect(active, Offset(x, trackOffset.y),
                Size(if (rtl) trackW - fillWidth else fillWidth, trackH), CornerRadius(trackH / 2))
        } else {
            drawRoundRect(active.copy(alpha = active.alpha * fraction), trackOffset,
                Size(trackW, trackH), CornerRadius(trackH / 2))
        }
        if (!moving) {
            val thumb = Size(37.dp.toPx(), 24.dp.toPx())
            drawPath(spotiGlassPath(thumb, 12.dp.toPx() * (look.spotiglassCorner / 32).coerceIn(0f, 1.5f)).apply {
                translate(center - Offset(thumb.width / 2, thumb.height / 2))
            }, white)
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
    val inset = with(density) { 29.dp.toPx() }
    val travel = (width - 2 * inset).coerceAtLeast(1f)
    val motion = rememberControlMotion(fraction, held && enabled, travel / density.density)
    fun report(f: Float) { change(spotiSliderValue(f, valueRange, steps)) }
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
