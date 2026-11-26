package com.notesis

import androidx.compose.runtime.rememberCoroutineScope

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

data class LiquidGlassItem(val label: String, val icon: ImageVector? = null, val color: Color? = null)

/** What a [LiquidGlassBar] is drawn on. */
enum class GlassBarSurface {
    /** Its own frosted capsule: a bar standing alone inside a panel. */
    FROST,
    /** Its own capsule of the liquid glass the toolbar is made of. */
    LIQUID,
    /**
     * Nothing of its own: the bar is content on a surface that is already
     * there, as the tool strip is on the full toolbar. A second translucent
     * layer over the first showed the page through both, darker and blurred
     * twice inside the strip, with a step in colour at its edge.
     */
    NONE,
}

/** x = centre, y = width, z = height of a lens body kept inside the bar. */
internal data class LiquidGlassLensBody(val x: Float, val y: Float, val z: Float)

/**
 * Keeps the travelling lens inside the capsule. It used to grow past the first
 * and last cells and leave the bar, where the layer it is drawn in cut it off
 * with a straight edge - and a spring overshoot at either end pushed it further
 * out. Now the side that meets the end of the bar is pressed flat against it,
 * like liquid against a wall, and gives the lost width back as a little height.
 */
internal fun liquidGlassLensBody(
    centerX: Float, width: Float, height: Float,
    barWidth: Float, edge: Float, minWidth: Float,
): LiquidGlassLensBody {
    val low = edge
    val high = (barWidth - edge).coerceAtLeast(low)
    var left = centerX - width / 2
    var right = centerX + width / 2
    val pressedLeft = left < low
    if (pressedLeft) left = low
    if (right > high) right = high
    val floor = minOf(minWidth, high - low)
    if (right - left < floor) {
        if (pressedLeft) right = left + floor else left = right - floor
    }
    val kept = right - left
    val squeeze = if (width > 0f) (1f - kept / width).coerceIn(0f, 1f) else 0f
    return LiquidGlassLensBody((left + right) / 2, kept, height * (1f + 0.3f * squeeze))
}

/**
 * Where the lens follows a finger that may run past either end of the bar.
 * Inside it is [liquidGlassPosition]; beyond the ends it keeps moving with
 * growing resistance, at most [LIQUID_GLASS_RUBBER_CELLS] of a cell, so the lens
 * presses into the end of the bar instead of stopping dead against it.
 */
internal fun liquidGlassDragPosition(x: Float, width: Float, count: Int, rtl: Boolean): Float {
    if (count <= 1 || width <= 0f) return 0f
    val raw = x / width * count - 0.5f
    val last = (count - 1).toFloat()
    fun rubber(over: Float) = LIQUID_GLASS_RUBBER_CELLS * (1f - 1f / (1f + over / LIQUID_GLASS_RUBBER_CELLS))
    val visual = when {
        raw < 0f -> -rubber(-raw)
        raw > last -> last + rubber(raw - last)
        else -> raw
    }
    return if (rtl) last - visual else visual
}

internal const val LIQUID_GLASS_RUBBER_CELLS = 0.3f

internal fun liquidGlassPosition(x: Float, width: Float, count: Int, rtl: Boolean): Float {
    if (count <= 1 || width <= 0f) return 0f
    val visual = (x / width * count - 0.5f).coerceIn(0f, (count - 1).toFloat())
    return if (rtl) count - 1 - visual else visual
}

/** MornyeTabBar port: frosted capsule, flat resting pill, raised travelling
 * lens. The capsule and icon shells share one optical capture.
 * MIT source and licenses: assets/liquidGlass/upstream.
 */
@Composable
fun LiquidGlassBar(
    items: List<LiquidGlassItem>, selectedIndex: Int, onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true, compact: Boolean = false,
    /** The capsule's own radius; the resting pill inside it is concentric with it. */
    corner: Dp = LocalSkinSettings.current.glassCorner.dp,
    surface: GlassBarSurface = GlassBarSurface.FROST,
    /**
     * Down the side instead of across: the same capsule, pill and lens on
     * the other axis, one cell per tool from the top. Its length is its own.
     */
    vertical: Boolean = false,
    /**
     * Whether the selection rises into a lens as it moves. A bar drawn on a
     * surface that is already there - the full toolbar's strip - keeps it
     * flat unless asked. Asked, its lens is kept inside the strip: it only
     * swells along the bar, never past the strip onto the rows above and
     * below, and carries no tint of its own over the pill's, which had left
     * it darker than the pill for a moment after the finger lifted.
     */
    lift: Boolean = surface != GlassBarSurface.NONE,
    /** Which kind of chrome the capsule is, for its own transparency; null takes none. */
    role: ChromeRole? = null,
    /** Whether choosing the item at an index opens a popup, so the lens waits for its first frame. */
    holdFor: (Int) -> Boolean = { false },
) {
    require(items.isNotEmpty())
    val look = LocalSkinSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val effects = rememberLiquidGlassEffectsAllowed() && !look.highContrast
    val response = if (effects) look.liquidGlassResponse else 0f
    val lensAllowed = effects && look.liquidGlassClarity > 0 && response > 0
    val density = LocalDensity.current
    // Down the side there is no reading direction: the first tool is at the top.
    val rtl = !vertical && LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestSelect by rememberUpdatedState(onSelected)
    val selected = selectedIndex.takeIf { it in items.indices }
    val latestSelected by rememberUpdatedState(selected)
    // On a surface it does not own, a lens keeps to the strip's own height.
    val contained = lift && surface == GlassBarSurface.NONE
    // Under a recipe whose slide is switched on, its springs; otherwise this app's.
    val slide = if (LocalSkin.current == Skin.LIQUID_GLASS) look.slideProfile() else null
    val slideKey = look.liquidStyle to (slide != null)
    val motion = remember(items.size, rtl, lift, slideKey) {
        LiquidGlassMotion((selected ?: 0).toFloat(), lifts = lift, profile = slide)
    }
    var wake by remember(motion) { mutableIntStateOf(0) }
    var frameRevision by remember(motion) { mutableIntStateOf(0) }
    val latestHold by rememberUpdatedState(holdFor)
    val holdScope = rememberCoroutineScope()
    var pending by remember(motion) { mutableStateOf<Job?>(null) }
    val dispatched = remember(motion) { intArrayOf(-1) }
    DisposableEffect(motion) { onDispose { pending?.cancel() } }

    fun moveTo(index: Int, release: Boolean) {
        when {
            !lensAllowed -> motion.snap(index.toFloat())
            release -> motion.release(index.toFloat())
            else -> motion.select(index.toFloat())
        }
        wake++
    }

    fun choose(index: Int, release: Boolean = false) {
        pending?.cancel()
        pending = null
        dispatched[0] = index
        if (lensAllowed && latestHold(index)) {
            // Keep only one pending target. The window can prepare while the
            // lens is still, even when the tap came through the drag handler.
            pending = holdScope.launch {
                awaitPopupFrames()
                moveTo(index, release)
                pending = null
            }
        } else moveTo(index, release)
        latestSelect(index)
    }
    LaunchedEffect(motion, selected, lensAllowed) {
        // The callback above already moved or scheduled this selection.
        if (lensAllowed && selected != null && dispatched[0] == selected) {
            dispatched[0] = -1
            return@LaunchedEffect
        }
        dispatched[0] = -1
        pending?.cancel()
        pending = null
        if (selected != null) moveTo(selected, release = false) else wake++
    }
    val withIcons = items.any { it.icon != null }
    val chromeScale = LocalChromeScale.current
    val barHeight = (if (compact) 40.dp else if (withIcons && showLabels) 64.dp else 48.dp) * chromeScale
    val padding = 6.dp
    val cellHeight = barHeight - padding * 2
    // A neutral wash, not the accent: the selected tool's pill stays the same
    // grey whatever accent is picked (see [selectionPillFill]).
    val fill = selectionPillFill(scheme.onSurface, dark)
    // The pill sits [padding] inside the capsule, so its curve is the
    // capsule's moved in by that much - measured from the radius the capsule
    // can actually draw at this height, not from the setting.
    val restRadius = toolPillRadius(corner, barHeight, padding)
    // A capsule of its own casts a shadow, and while the lens moves the whole
    // bar is drawn through one layer that cuts off anything past its edges -
    // the capsule's shadow at its two ends with it. The layer reaches this far
    // past the bar on either side so the shadow is inside it.
    val room = if (surface == GlassBarSurface.NONE) 0.dp else LIQUID_GLASS_SHADOW_ROOM
    val thickness = Modifier.then(
        if (vertical) Modifier.width(barHeight + 16.dp).height(verticalBarLength(items.size, compact, padding, chromeScale))
        else Modifier.height(barHeight + 16.dp),
    )
    BoxWithConstraints(modifier.then(thickness).selectableGroup()) {
        // The bar's long side: across it is its width, down the side its height.
        val length = if (vertical) maxHeight else maxWidth
        val cell = (length - padding * 2).coerceAtLeast(1.dp) / items.size
        val widthPx = with(density) { cell.toPx() * items.size }
        val barWidthPx = with(density) { length.toPx() }
        val paddingPx = with(density) { padding.toPx() }
        // Whole pixels, as [spillOver] places it.
        val roomPx = with(density) { room.roundToPx().toFloat() }
        // Retargeting a drag never cancels/restarts the frame clock. Frame state
        // is read in drawing only, so icons and touch targets keep their layout.
        val latestCell by rememberUpdatedState(cell.value)
        LaunchedEffect(motion) {
            var observed = -1
            var last = 0L
            while (isActive) {
                if (!motion.running || pending != null) {
                    snapshotFlow { wake.takeIf { pending == null } }.first { it != null && it != observed }
                    observed = wake
                    last = 0L
                    if (!motion.running) continue
                }
                val now = withFrameNanos { it }
                if (pending != null) { last = 0L; continue }
                if (last != 0L) motion.tick(now / 1e9, (now - last) / 1e9, latestCell)
                frameRevision++
                last = now
            }
        }
        fun showPill() = selected != null || motion.dragging
        fun moving(): Boolean { frameRevision; return lensAllowed && motion.running && showPill() }
        fun lensFrame(): LiquidGlassLensFrame {
            frameRevision
            val visual = if (rtl) items.lastIndex - motion.position else motion.position
            val extra = (barHeight.value + 9 * response) / cellHeight.value - 1
            val w = cell * (1 + extra * motion.growX * response)
            val h = if (contained) cellHeight else cellHeight * (1 + extra * motion.growY * response)
            val sx = 1 + motion.deviation * response
            val sy = if (contained) 1f else 1 - motion.deviation * response
            val body = with(density) {
                liquidGlassLensBody(
                    centerX = (padding + cell * (visual + 0.5f)).toPx(),
                    width = (w * sx).toPx(), height = (h * sy).toPx(),
                    barWidth = barWidthPx, edge = 1.dp.toPx(), minWidth = cell.toPx(),
                )
            }
            // Worked out along the bar and across it, then put on the screen's axes.
            val across = with(density) { (8.dp + barHeight / 2).toPx() }
            return LiquidGlassLensFrame(
                center = if (vertical) Offset(across, body.x) else Offset(body.x, across),
                size = if (vertical) Size(body.z, body.y) else Size(body.y, body.z),
                // The lens grows out of the resting pill, so its curve grows
                // with it from the same radius.
                radius = with(density) { restRadius.toPx() * (h / cellHeight) },
                scale = if (vertical) Offset(sy, sx) else Offset(sx, sy),
                progress = motion.progress, presence = motion.presence, fill = if (contained) Color.Transparent else fill,
                distortion = 0.04f * (look.refraction / 24f), band = look.depth,
                dispersion = 0.002f * (look.dispersion / 0.35f), borderWidth = 1.2f,
                // The same round corner as the resting pill and the still
                // skins' highlight, so the lens lands as the shape it left.
                continuous = false)
        }
            @Composable
            fun IconShell(colored: Boolean, shellModifier: Modifier) {
                @Composable
                fun Cell(index: Int, item: LiquidGlassItem, base: Modifier) {
                    // Under the glass a tool shows its own colour only. The
                    // pen's ink colour no longer spills onto tools that have
                    // none, so the ball itself never looks recoloured.
                    val color = if (colored) item.color ?: scheme.primary else scheme.onSurface
                    var hit = base
                    if (!colored) hit = hit.semantics { this.selected = selected == index; contentDescription = item.label }
                        .clickable(role = Role.Tab, interactionSource = remember { MutableInteractionSource() }, indication = null) { choose(index) }
                    Column(hit, horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Ui.Space.hair, Alignment.CenterVertically)) {
                        item.icon?.let { AppIcon(it, null, tint = color, modifier = Modifier.size((if (compact) 20.dp else 25.dp) * chromeScale)) }
                        if (showLabels || item.icon == null) Text(item.label, color = color,
                            style = if (withIcons) MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp) else MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (vertical) {
                    EvenCells(vertical = true,
                        modifier = shellModifier.graphicsLayer().fillMaxHeight().width(cellHeight).align(Alignment.Center).padding(vertical = padding)) {
                        items.forEachIndexed { index, item -> Cell(index, item, Modifier) }
                    }
                } else {
                    EvenCells(vertical = false,
                        modifier = shellModifier.graphicsLayer().fillMaxWidth().height(cellHeight).align(Alignment.Center).padding(horizontal = padding)) {
                        items.forEachIndexed { index, item -> Cell(index, item, Modifier) }
                    }
                }
            }
        @Composable
        fun Frost() {
            // The selection changes every frame, but the glass recipe and
            // icons beneath it do not need to be recorded for each position.
            val capsule = if (vertical) Modifier.fillMaxHeight().width(barHeight).align(Alignment.Center).graphicsLayer()
                else Modifier.fillMaxWidth().height(barHeight).align(Alignment.Center).graphicsLayer()
            when (surface) {
                // A round corner, as the toolbar and the other skins' strip draw
                // theirs - and the only outline Backdrop's lens can take.
                GlassBarSurface.FROST -> Box(capsule.liquidGlassSurface(RoundedCornerShape(corner), look, navigation = true)
                    .chromeVeil(role, RoundedCornerShape(corner)))
                GlassBarSurface.LIQUID -> Box(capsule.liquidGlassPane(RoundedCornerShape(corner))
                    .chromeVeil(role, RoundedCornerShape(corner)))
                GlassBarSurface.NONE -> Unit
            }
        }
        // The same frosted capsule remains in the source at rest and on press.
        // Refract the capsule and both icon shells in the same optical scene.
        // or swap between two backdrop surfaces during animation.
        // A pill that only slides has no lens to draw: it is the flat pill, moved.
        val scene = Modifier.spillOver(room, vertical).liquidGlassLens(lensAllowed && lift, active = { moving() }) { _, _ ->
            // The layer starts [room] before the bar; the frame is the bar's.
            lensFrame().let {
                if (roomPx == 0f) it else it.copy(center = it.center + if (vertical) Offset(0f, roomPx) else Offset(roomPx, 0f))
            }
        }
        Box(scene) { Box(Modifier.fillMaxSize().then(if (vertical) Modifier.padding(vertical = room) else Modifier.padding(horizontal = room))) {
            Frost()
            Box(Modifier.matchParentSize().drawWithContent {
                frameRevision
                if (showPill()) {
                    val frame = lensFrame()
                    val body = if (moving()) frame.size
                        else if (vertical) Size(cellHeight.toPx(), cell.toPx()) else Size(cell.toPx(), cellHeight.toPx())
                    val radius = if (moving()) frame.radius else restRadius.toPx()
                    val path = roundRectPath(body, radius).apply {
                        translate(frame.center - Offset(body.width / 2, body.height / 2))
                    }
                    // The ball keeps the bar's neutral fill while it travels;
                    // only the icon underneath carries a colour.
                    drawPath(path, fill)
                }
                drawContent()
            })
            IconShell(false, Modifier.pointerInput(widthPx, items.size, rtl, lensAllowed, motion) {
                if (!lensAllowed) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var lastPosition = down.position
                    fun along(at: Offset) = if (vertical) at.y else at.x
                    fun indexAt(at: Offset) = liquidGlassPosition(along(at) - paddingPx, widthPx, items.size, rtl).roundToInt()
                    down.consume()
                    if (pending != null) { pending?.cancel(); pending = null; wake++ }
                    // A tap on a popup tool must not start travelling on
                    // down and then pause on up. A drag still follows the pen.
                    var grabbed = !latestHold(indexAt(down.position))
                    if (grabbed) {
                        motion.grab(liquidGlassPosition(along(lastPosition) - paddingPx, widthPx, items.size, rtl))
                        wake++
                    }
                    var released = false
                    try {
                        while (!released) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            lastPosition = change.position
                            val target = liquidGlassDragPosition(along(lastPosition) - paddingPx, widthPx, items.size, rtl)
                            if (!grabbed && change.pressed && abs(along(lastPosition) - along(down.position)) > viewConfiguration.touchSlop) {
                                motion.grab(target)
                                grabbed = true
                                wake++
                            }
                            if (grabbed) motion.follow(target)
                            change.consume()
                            released = !change.pressed
                        }
                        if (released) {
                            choose(indexAt(lastPosition), release = motion.dragging)
                        }
                    } finally {
                        if (!released && motion.dragging) { motion.release((latestSelected ?: 0).toFloat()); wake++ }
                    }
                }
            })
            IconShell(true, Modifier.clearAndSetSemantics {}.drawWithContent {
                frameRevision
                if (!showPill()) return@drawWithContent
                val frame = lensFrame()
                val cx = if (vertical) size.width / 2 else frame.center.x
                val cy = if (vertical) frame.center.y else size.height / 2
                val raised = moving()
                val sx = if (raised) frame.scale.x else 1f
                val sy = if (raised) frame.scale.y else 1f
                val restSize = if (raised) Size(frame.size.width / sx, frame.size.height / sy)
                    else with(density) { if (vertical) Size(cellHeight.toPx(), cell.toPx()) else Size(cell.toPx(), cellHeight.toPx()) }
                val radius = if (raised) frame.radius else with(density) { restRadius.toPx() }
                val path = roundRectPath(restSize, radius)
                path.translate(Offset(-restSize.width / 2, -restSize.height / 2))
                withTransform({ translate(cx, cy); scale(sx, sy, pivot = Offset.Zero) }) {
                    clipPath(path) {
                        withTransform({ scale(1 / sx, 1 / sy, pivot = Offset.Zero); translate(-cx, -cy) }) {
                            this@drawWithContent.drawContent()
                        }
                    }
                }
            })
        } }
    }
}

/** How far past a bar with a capsule of its own its lens layer reaches, for the capsule's shadow. */
private val LIQUID_GLASS_SHADOW_ROOM = 8.dp

/**
 * The parent's whole size, and [room] more on either side, placed so the
 * parent's left edge is [room] in. What is drawn there is still the parent's
 * content; only a layer set on this is wider.
 */
private fun Modifier.spillOver(room: Dp, vertical: Boolean = false): Modifier {
    if (room <= 0.dp) return fillMaxSize()
    return layout { measurable, constraints ->
        val extra = room.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        // Past the two ends of the bar, whichever way it runs.
        val placeable = if (vertical) measurable.measure(Constraints.fixed(width, height + 2 * extra))
            else measurable.measure(Constraints.fixed(width + 2 * extra, height))
        layout(width, height) { if (vertical) placeable.place(0, -extra) else placeable.place(-extra, 0) }
    }
}

