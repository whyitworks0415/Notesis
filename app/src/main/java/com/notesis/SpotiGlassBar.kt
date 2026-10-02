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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import kotlin.math.abs
import kotlin.math.roundToInt

data class SpotiGlassItem(val label: String, val icon: ImageVector? = null)

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
) {
    require(items.size >= 2)
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
    val barHeight = if (withIcons) 64.dp else 48.dp
    val padding = 6.dp
    val cellHeight = barHeight - padding * 2
    val fill = scheme.onSurface.copy(alpha = if (dark) 0.12f else 0.08f)
    val backdrop = LocalSpotiGlassHostBackdrop.current ?: LocalLiquidGlassBackdrop.current
    BoxWithConstraints(modifier.height(barHeight + 16.dp).selectableGroup()) {
        val cell = (maxWidth - padding * 2).coerceAtLeast(1.dp) / items.size
        val widthPx = with(density) { cell.toPx() * items.size }
        val paddingPx = with(density) { padding.toPx() }
        LaunchedEffect(wake, motion, cell.value) {
            var last = withFrameNanos { it }
            while (motion.running) {
                val now = withFrameNanos { it }
                motion.tick(now / 1e9, (now - last) / 1e9, cell.value)
                frameRevision++
                last = now
            }
        }
        @Suppress("UNUSED_VARIABLE") val currentFrame = frameRevision
        val visual = if (rtl) items.lastIndex - motion.position else motion.position
        val extra = (barHeight.value + 9 * response) / cellHeight.value - 1
        val morphW = cell * (1 + extra * motion.growX * response)
        val morphH = cellHeight * (1 + extra * motion.growY * response)
        val sx = 1 + motion.deviation * response
        val sy = 1 - motion.deviation * response
        val pillW = morphW * sx
        val pillH = morphH * sy
        val centerX = padding + cell * (visual + 0.5f)
        val centerY = 8.dp + barHeight / 2
        val pillRadius = morphH / 2 * (look.spotiglassCorner / 32f).coerceIn(0f, 1.5f)
        val showPill = selected != null || motion.dragging
        val moving = lensAllowed && motion.running && showPill
        val lensFrame = SpotiGlassLensFrame(
            center = with(density) { Offset(centerX.toPx(), centerY.toPx()) },
            size = with(density) { Size(pillW.toPx(), pillH.toPx()) },
            radius = with(density) { pillRadius.toPx() }, scale = Offset(sx, sy),
            progress = motion.progress, presence = motion.presence, fill = fill,
            distortion = 0.04f * (look.refraction / 24f), band = look.depth,
            dispersion = 0.002f * (look.dispersion / 0.35f),
        )
        val flatShape = SpotiGlassShape((cellHeight / 2) * (look.spotiglassCorner / 32f).coerceIn(0f, 1.5f))
        var scene = Modifier.fillMaxSize().spotiGlassLens(moving) { _, _ -> lensFrame }
        // The protruding lens also samples the page outside the capsule.
        if (backdrop != null && moving) scene = scene.drawBackdrop(backdrop,
            shape = { RectangleShape }, effects = {}, highlight = null, shadow = null)
        Box(scene) {
            Box(Modifier.fillMaxWidth().height(barHeight).align(Alignment.Center)
                .spotiGlassSurface(SpotiGlassShape(look.spotiglassCorner.dp), look, navigation = true))
            if (showPill && !moving) Box(Modifier.absoluteOffset(x = padding + cell * visual, y = 8.dp + padding)
                .width(cell).height(cellHeight).background(fill, flatShape))
            if (moving) {
                val rim = ((motion.presence - 0.45f) / 0.55f).coerceIn(0f, 1f)
                val shadowAlpha = 0.3f * motion.progress * rim
                Box(Modifier.absoluteOffset(centerX - pillW / 2, centerY - pillH / 2)
                    .width(pillW).height(pillH)
                    .shadow(9.dp, SpotiGlassShape(pillRadius), clip = false,
                        ambientColor = Color.Black.copy(alpha = shadowAlpha), spotColor = Color.Black.copy(alpha = shadowAlpha)))
            }
            @Composable
            fun IconShell(colored: Boolean, shellModifier: Modifier) {
                Row(shellModifier.fillMaxWidth().height(cellHeight).align(Alignment.Center).padding(horizontal = padding)) {
                    items.forEachIndexed { index, item ->
                        val color = if (colored) selectedContentColor ?: scheme.primary else scheme.onSurface
                        var hit = Modifier.weight(1f).fillMaxSize()
                        if (!colored) hit = hit.semantics { this.selected = selected == index }
                            .clickable(role = Role.Tab, interactionSource = remember { MutableInteractionSource() }, indication = null) { choose(index) }
                        Column(hit, horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                            item.icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(25.dp)) }
                            Text(item.label, color = color,
                                style = if (withIcons) MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp) else MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            IconShell(false, Modifier.pointerInput(widthPx, items.size, rtl, lensAllowed) {
                if (!lensAllowed) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastPosition = down.position
                    var elapsed = 0L
                    var grabbed = false
                    var released = false
                    try {
                        while (!released) {
                            val event = if (grabbed) awaitPointerEvent(PointerEventPass.Initial)
                                else withTimeoutOrNull((100 - elapsed).coerceAtLeast(1)) { awaitPointerEvent(PointerEventPass.Initial) }
                            if (event == null) {
                                grabbed = true
                                motion.grab(spotiGlassPosition(lastPosition.x - paddingPx, widthPx, items.size, rtl)); wake++
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            lastPosition = change.position
                            elapsed = change.uptimeMillis - down.uptimeMillis
                            val delta = change.position - down.position
                            if (!grabbed && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                            if (!grabbed && abs(delta.x) > viewConfiguration.touchSlop) {
                                grabbed = true
                                motion.grab(spotiGlassPosition(lastPosition.x - paddingPx, widthPx, items.size, rtl)); wake++
                            }
                            if (grabbed) {
                                motion.follow(spotiGlassPosition(lastPosition.x - paddingPx, widthPx, items.size, rtl))
                                change.consume()
                            }
                            released = !change.pressed
                        }
                        if (grabbed && released) {
                            val next = spotiGlassPosition(lastPosition.x - paddingPx, widthPx, items.size, rtl).roundToInt()
                            motion.release(next.toFloat()); wake++
                            latestSelect(next)
                        }
                    } finally {
                        if (motion.dragging) { motion.release((selected ?: 0).toFloat()); wake++ }
                    }
                }
            })
            if (showPill) IconShell(true, Modifier.clearAndSetSemantics {}.drawWithContent {
                val cx = with(density) { centerX.toPx() }
                val cy = size.height / 2
                val restSize = if (moving) with(density) { Size(morphW.toPx(), morphH.toPx()) }
                    else with(density) { Size(cell.toPx(), cellHeight.toPx()) }
                val radius = if (moving) with(density) { pillRadius.toPx() } else with(density) { cellHeight.toPx() / 2 * (look.spotiglassCorner / 32f) }
                val path = spotiGlassPath(restSize, radius)
                path.translate(Offset(-restSize.width / 2, -restSize.height / 2))
                withTransform({ translate(cx, cy); scale(if (moving) sx else 1f, if (moving) sy else 1f, pivot = Offset.Zero) }) {
                    clipPath(path) {
                        withTransform({ scale(if (moving) 1 / sx else 1f, if (moving) 1 / sy else 1f, pivot = Offset.Zero); translate(-cx, -cy) }) {
                            this@drawWithContent.drawContent()
                        }
                    }
                }
            })
        }
    }
}
