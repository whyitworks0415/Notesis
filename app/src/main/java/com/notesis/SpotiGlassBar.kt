package com.notesis

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

data class SpotiGlassItem(val label: String, val icon: ImageVector? = null)

/** Pointer coordinates map to visual slots; callbacks always use logical order. */
internal fun spotiGlassPosition(x: Float, width: Float, count: Int, rtl: Boolean): Float {
    if (count <= 1 || width <= 0f) return 0f
    val visual = (x / width * count - 0.5f).coerceIn(0f, (count - 1).toFloat())
    return if (rtl) count - 1 - visual else visual
}

/** A quiet glass capsule with a travelling lens that also refracts its icons.
 * The icon recording and lens are siblings so the lens never samples itself.
 * A slide previews a tool and commits only on release; cancellation keeps it.
 */
@Composable
fun SpotiGlassBar(
    items: List<SpotiGlassItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedContentColor: Color? = null,
) {
    require(items.size >= 2)
    val look = LocalSkinSettings.current
    val scheme = MaterialTheme.colorScheme
    val effects = rememberLiquidGlassEffectsAllowed() && !look.highContrast
    val lensAllowed = effects && look.spotiglassClarity > 0f
    val response = if (effects) look.spotiglassResponse.coerceIn(0f, 1f) else 0f
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestSelect by rememberUpdatedState(onSelected)
    var held by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    val pulse = remember { Animatable(0f) }
    val selected = selectedIndex.takeIf { it in items.indices }
    val position by animateFloatAsState(
        targetValue = if (dragging) dragPosition else (selected ?: 0).toFloat(),
        animationSpec = if (effects && !dragging) spring(dampingRatio = 0.76f, stiffness = 420f) else snap(),
        label = "Spotiglass selection",
    )
    LaunchedEffect(selected, effects) {
        pulse.snapTo(if (effects) 1f else 0f)
        if (effects) pulse.animateTo(0f, tween(260))
    }
    val activation by animateFloatAsState(
        targetValue = if (held) 1f else 0f,
        animationSpec = if (effects) spring(dampingRatio = 0.82f, stiffness = 520f) else snap(),
        label = "Spotiglass press",
    )
    val shape = RoundedCornerShape(look.corner.coerceIn(SkinSettings.CORNER_RANGE).dp)
    val iconsBackdrop = rememberLiquidGlassBackdrop()
    val height = if (items.any { it.icon != null }) 64.dp else 48.dp
    BoxWithConstraints(modifier.height(height).selectableGroup()) {
        val slot = (maxWidth - 8.dp).coerceAtLeast(1.dp) / items.size
        val widthPx = with(density) { (maxWidth - 8.dp).toPx() }
        val currentPosition = if (dragging) dragPosition else position
        val visualPosition = if (rtl) items.lastIndex - currentPosition else currentPosition
        val active = (activation + pulse.value).coerceAtMost(1f) * response
        Box(
            Modifier.fillMaxSize().liquidGlass(
                intensity = 0.48f,
                shape = shape,
                surfaceColor = Color(look.tint).copy(alpha = Color(look.tint).alpha * 0.6f),
                settings = look.copy(depth = 0f, refraction = 0f, dispersion = 0f),
                shadowElevation = 3.dp,
            ),
        )
        if (!lensAllowed && (selected != null || dragging)) {
            Box(Modifier.absoluteOffset(x = 4.dp + slot * visualPosition, y = 4.dp)
                .width(slot).height(height - 8.dp)
                .background(scheme.primary.copy(alpha = if (look.highContrast) 0.24f else 0.12f), shape))
        }
        Row(
            Modifier.fillMaxSize().padding(4.dp)
                .captureLiquidGlassBackdrop(iconsBackdrop)
                .pointerInput(widthPx, items.size, rtl) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        held = true
                        var slide = false
                        var released = false
                        var nextPosition = 0f
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                val delta = change.position - down.position
                                if (!slide && abs(delta.y) > viewConfiguration.touchSlop &&
                                    abs(delta.y) > abs(delta.x)) break
                                if (!slide && abs(delta.x) > viewConfiguration.touchSlop) {
                                    slide = true
                                    dragging = true
                                }
                                if (slide) {
                                    nextPosition = spotiGlassPosition(change.position.x, widthPx, items.size, rtl)
                                    dragPosition = nextPosition
                                    change.consume()
                                }
                                released = !change.pressed
                            } while (!released)
                            if (slide && released) latestSelect(nextPosition.roundToInt())
                        } finally {
                            held = false
                            dragging = false
                        }
                    }
                },
        ) {
            items.forEachIndexed { index, item ->
                val highlighted = if (dragging) dragPosition.roundToInt() == index else selected == index
                Column(
                    Modifier.weight(1f).fillMaxSize()
                        .semantics { this.selected = selected == index }
                        .clickable(
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { latestSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    val color = if (highlighted) selectedContentColor ?: scheme.primary else scheme.onSurface
                    item.icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(23.dp)) }
                    Text(
                        item.label,
                        color = color,
                        style = if (item.icon == null) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                        fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (lensAllowed && (selected != null || dragging)) {
            val stretch = slot * (0.12f * active)
            ProvideLiquidGlassBackdrop(iconsBackdrop) {
                Box(
                    Modifier
                        .absoluteOffset(x = 4.dp + slot * visualPosition - stretch / 2f,
                            y = 4.dp - 2.dp * active)
                        .width(slot + stretch).height(height - 8.dp)
                        .graphicsLayer { scaleY = 1f + 0.06f * active }
                        .liquidGlass(
                            intensity = 0.35f + active * 0.65f,
                            shape = RoundedCornerShape((look.corner - 4f).coerceAtLeast(0f).dp),
                            surfaceColor = scheme.onSurface.copy(alpha = 0.07f + active * 0.04f),
                            // Only the travelling pill distorts the labels, with a low resting blur.
                            settings = look.copy(blur = look.blur * 0.08f, depth = look.depth * (0.4f + active * 0.6f),
                                refraction = look.refraction * (0.3f + active * 0.7f)),
                            shadowElevation = 2.dp + 4.dp * active,
                        ),
                )
            }
        }
    }
}
