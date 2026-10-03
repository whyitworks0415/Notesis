package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

data class SpotiGlassItem(val label: String, val icon: ImageVector? = null, val color: Color? = null)

internal fun spotiGlassHoverColor(items: List<SpotiGlassItem>, position: Float, fallback: Color): Color {
    val index = position.roundToInt().coerceIn(items.indices)
    return items[index].color ?: fallback
}

internal fun spotiGlassPosition(x: Float, width: Float, count: Int, rtl: Boolean): Float {
    if (count <= 1 || width <= 0f) return 0f
    val visual = (x / width * count - 0.5f).coerceIn(0f, (count - 1).toFloat())
    return if (rtl) count - 1 - visual else visual
}

/** MornyeTabBar port: frosted capsule, flat resting pill, raised travelling
 * lens. The upstream shader refracts the capsule AND the colored icon shell.
 * MIT source and licenses: assets/spotiglass/upstream.
 */
@Composable
fun SpotiGlassBar(
    items: List<SpotiGlassItem>, selectedIndex: Int, onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier, selectedContentColor: Color? = null,
    showLabels: Boolean = true, compact: Boolean = false,
) {
    require(items.isNotEmpty())
    val look = LocalSkinSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val effects = rememberLiquidGlassEffectsAllowed() && !look.highContrast
    val response = if (effects) look.spotiglassResponse else 0f
    val lensAllowed = effects && look.spotiglassClarity > 0 && response > 0
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestSelect by rememberUpdatedState(onSelected)
    val selected = selectedIndex.takeIf { it in items.indices }
    val motion = remember(items.size, rtl) { SpotiGlassMotion((selected ?: 0).toFloat()) }
    var wake by remember { mutableIntStateOf(0) }
    var frameRevision by remember { mutableIntStateOf(0) }
    fun choose(index: Int) {
        if (lensAllowed) motion.select(index.toFloat()) else motion.snap(index.toFloat())
        wake++
        latestSelect(index)
    }
    LaunchedEffect(selected, lensAllowed) {
        if (selected != null) {
            if (lensAllowed) motion.select(selected.toFloat()) else motion.snap(selected.toFloat())
            wake++
        }
    }
    val withIcons = items.any { it.icon != null }
    val barHeight = if (compact) 40.dp else if (withIcons && showLabels) 64.dp else 48.dp
    val padding = 6.dp
    val cellHeight = barHeight - padding * 2
    val fill = scheme.onSurface.copy(alpha = if (dark) 0.12f else 0.08f)
    BoxWithConstraints(modifier.height(barHeight + 16.dp).selectableGroup()) {
        val cell = (maxWidth - padding * 2).coerceAtLeast(1.dp) / items.size
        val widthPx = with(density) { cell.toPx() * items.size }
        val paddingPx = with(density) { padding.toPx() }
        // Retargeting a drag never cancels/restarts the frame clock. Frame state
        // is read in drawing only, so icons and touch targets keep their layout.
        LaunchedEffect(motion, cell.value) {
            var observed = -1
            var last = 0L
            while (isActive) {
                if (!motion.running) {
                    snapshotFlow { wake }.first { it != observed }
                    observed = wake
                    last = 0L
                    if (!motion.running) continue
                }
                val now = withFrameNanos { it }
                if (last != 0L) motion.tick(now / 1e9, (now - last) / 1e9, cell.value)
                frameRevision++
                last = now
            }
        }
        fun showPill() = selected != null || motion.dragging
        fun moving(): Boolean { frameRevision; return lensAllowed && motion.running && showPill() }
        fun lensFrame(): SpotiGlassLensFrame {
            frameRevision
            val visual = if (rtl) items.lastIndex - motion.position else motion.position
            val extra = (barHeight.value + 9 * response) / cellHeight.value - 1
            val w = cell * (1 + extra * motion.growX * response)
            val h = cellHeight * (1 + extra * motion.growY * response)
            val sx = 1 + motion.deviation * response
            val sy = 1 - motion.deviation * response
            return SpotiGlassLensFrame(
                center = with(density) { Offset((padding + cell * (visual + 0.5f)).toPx(), (8.dp + barHeight / 2).toPx()) },
                size = with(density) { Size((w * sx).toPx(), (h * sy).toPx()) },
                radius = with(density) { (h / 2).toPx() } * (look.spotiglassCorner / 32f).coerceIn(0f, 1.5f),
                scale = Offset(sx, sy), progress = motion.progress, presence = motion.presence, fill = fill,
                distortion = 0.04f * (look.refraction / 24f), band = look.depth,
                dispersion = 0.002f * (look.dispersion / 0.35f), borderWidth = 1.2f)
        }
            @Composable
            fun IconShell(colored: Boolean, shellModifier: Modifier) {
                Row(shellModifier.fillMaxWidth().height(cellHeight).align(Alignment.Center).padding(horizontal = padding)) {
                    items.forEachIndexed { index, item ->
                        val color = if (colored) item.color ?: selectedContentColor ?: scheme.primary else scheme.onSurface
                        var hit = Modifier.weight(1f).fillMaxSize()
                        if (!colored) hit = hit.semantics { this.selected = selected == index; contentDescription = item.label }
                            .clickable(role = Role.Tab, interactionSource = remember { MutableInteractionSource() }, indication = null) { choose(index) }
                        Column(hit, horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                            item.icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(if (compact) 20.dp else 25.dp)) }
                            if (showLabels || item.icon == null) Text(item.label, color = color,
                                style = if (withIcons) MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp) else MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        @Composable
        fun Frost() {
            Box(Modifier.fillMaxWidth().height(barHeight).align(Alignment.Center)
                .spotiGlassSurface(SpotiGlassShape(look.spotiglassCorner.dp), look, navigation = true))
        }
        // The same frosted capsule remains in the source at rest and on press.
        // Refract the bar and its icons together; never punch through to the page
        // or swap between two backdrop surfaces during animation.
        val scene = Modifier.fillMaxSize().spotiGlassLens(lensAllowed, active = { moving() }) { _, _ -> lensFrame() }
        Box(scene) {
            Frost()
            Box(Modifier.matchParentSize().drawWithContent {
                frameRevision
                if (showPill()) {
                    val frame = lensFrame()
                    val body = if (moving()) frame.size else Size(cell.toPx(), cellHeight.toPx())
                    val radius = if (moving()) frame.radius else body.height / 2 * (look.spotiglassCorner / 32f)
                    val path = spotiGlassPath(body, radius).apply {
                        translate(frame.center - Offset(body.width / 2, body.height / 2))
                    }
                    val tint = if (moving()) spotiGlassHoverColor(items, motion.position, scheme.primary)
                        .copy(alpha = 0.04f * motion.presence) else fill
                    drawPath(path, tint)
                }
                drawContent()
            })
            IconShell(false, Modifier.pointerInput(widthPx, items.size, rtl, lensAllowed) {
                if (!lensAllowed) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var lastPosition = down.position
                    down.consume()
                    motion.grab(spotiGlassPosition(lastPosition.x - paddingPx, widthPx, items.size, rtl)); wake++
                    var released = false
                    try {
                        while (!released) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            lastPosition = change.position
                            motion.follow(spotiGlassPosition(lastPosition.x - paddingPx, widthPx, items.size, rtl))
                            change.consume()
                            released = !change.pressed
                        }
                        if (released) {
                            val next = spotiGlassPosition(lastPosition.x - paddingPx, widthPx, items.size, rtl).roundToInt()
                            motion.release(next.toFloat()); wake++
                            latestSelect(next)
                        }
                    } finally {
                        if (motion.dragging) { motion.release((selected ?: 0).toFloat()); wake++ }
                    }
                }
            })
            IconShell(true, Modifier.clearAndSetSemantics {}.drawWithContent {
                frameRevision
                if (!showPill()) return@drawWithContent
                val frame = lensFrame()
                val cx = frame.center.x
                val cy = size.height / 2
                val raised = moving()
                val sx = if (raised) frame.scale.x else 1f
                val sy = if (raised) frame.scale.y else 1f
                val restSize = if (raised) Size(frame.size.width / sx, frame.size.height / sy)
                    else with(density) { Size(cell.toPx(), cellHeight.toPx()) }
                val radius = if (raised) frame.radius else with(density) { cellHeight.toPx() / 2 * (look.spotiglassCorner / 32f) }
                val path = spotiGlassPath(restSize, radius)
                path.translate(Offset(-restSize.width / 2, -restSize.height / 2))
                withTransform({ translate(cx, cy); scale(sx, sy, pivot = Offset.Zero) }) {
                    clipPath(path) {
                        withTransform({ scale(1 / sx, 1 / sy, pivot = Offset.Zero); translate(-cx, -cy) }) {
                            this@drawWithContent.drawContent()
                        }
                    }
                }
            })
        }
    }
}
