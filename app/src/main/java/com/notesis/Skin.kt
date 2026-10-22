package com.notesis

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * What a skin actually changes. Plain numbers rather than a pile of composables,
 * so a new skin is a row of values and not a new widget set.
 */
class SkinTokens(
    val corner: Dp,
    /** How much of the surface colour survives; the rest is what is behind. */
    val fillAlpha: Float,
    /** The body colour, when the skin names one rather than tinting surface. */
    val fill: Color? = null,
    /** The lit edge. Diagonal, because that is where light enters and leaves. */
    val rim: Brush?,
    val rimWidth: Dp,
    /** A darker line just inside the rim, which is what gives glass thickness. */
    val bevel: Brush?,
    /** Width and colour of each inner glow, widest first. */
    val glows: List<Pair<Dp, Color>>,
    val shadow: Dp,
    val tonalElevation: Dp,
)

val LocalSkin = compositionLocalOf { Skin.MATERIAL }
val LocalSkinSettings = compositionLocalOf { SkinSettings() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvideSkin(skin: Skin, settings: SkinSettings, content: @Composable () -> Unit) {
    // 앱 전역 설정 감시는 여기에서 한 번만 만들고 모든 Liquid Glass 컨트롤이 공유합니다.
    val inheritedEffectsAllowed = LocalLiquidGlassEffectsAllowed.current
    val effectsAllowed = inheritedEffectsAllowed ?: rememberSystemLiquidGlassEffectsAllowed()
    val values = mutableListOf<ProvidedValue<*>>(
        LocalSkin provides skin,
        LocalSkinSettings provides settings,
        LocalLiquidGlassEffectsAllowed provides effectsAllowed,
    )
    if (!skin.animatesChrome) {
        // Still chrome: no ripple and no hover or press wash, in Material's
        // own components (the ripple configuration) and in every plain
        // clickable (the indication). Keyboard focus still shows, or a
        // keyboard user would have no idea where they are.
        values += LocalRippleConfiguration provides null
        values += LocalIndication provides FocusOnlyIndication
    }
    CompositionLocalProvider(*values.toTypedArray(), content = content)
}

/**
 * An indication that answers focus and nothing else: no ripple on a press,
 * no wash on hover. A quiet veil while the element holds keyboard focus.
 */
private object FocusOnlyIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusOnlyIndicationNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class FocusOnlyIndicationNode(
    private val source: InteractionSource,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var focused = false

    override fun onAttach() {
        // Undispatched: a clickable may create this only as focus arrives,
        // and a collector that starts a frame later would miss that focus.
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val held = mutableListOf<FocusInteraction.Focus>()
            source.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> held += interaction
                    is FocusInteraction.Unfocus -> held -= interaction.focus
                }
                val now = held.isNotEmpty()
                if (now != focused) {
                    focused = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused) drawRect(currentValueOf(LocalContentColor).copy(alpha = FOCUS_VEIL_ALPHA))
    }
}

private const val FOCUS_VEIL_ALPHA = 0.12f

@Composable
fun Skin.tokens(): SkinTokens = when (this) {
    Skin.MATERIAL -> SkinTokens(
        corner = Ui.Corner.card,
        fillAlpha = 1f,
        rim = null,
        rimWidth = 0.dp,
        bevel = null,
        glows = emptyList(),
        shadow = 4.dp,
        tonalElevation = 3.dp,
    )

    // Frosted glass, and the numbers the settings screen owns. No lens: one
    // even rim, one soft shadow, and a body that is mostly what is behind it.
    Skin.GLASSMORPHISM -> LocalSkinSettings.current.let { look ->
        val body = darkAwareTint(Color(look.tint), look.themeMode == AppThemeMode.DARK)
        SkinTokens(
            corner = look.corner.dp,
            fillAlpha = 1f,
            // Turned up, the glass stops being see-through: a panel you can
            // read is worth more than one you can look through, and this is
            // the setting where somebody has said so.
            fill = if (look.highContrast) {
                body.copy(alpha = maxOf(body.alpha, 0.94f))
            } else {
                body
            },
            // Even, not diagonal. A gradient that lights two corners is a lens
            // catching the light; frost scatters it the same way all round.
            // Turned up it becomes the ink colour, because a white rim on a
            // white panel is an edge nobody can find.
            rim = SolidColor(
                if (look.highContrast) {
                    if (look.themeMode == AppThemeMode.DARK) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color(look.content)
                    }
                } else {
                    Color(look.border)
                },
            ),
            rimWidth = if (look.highContrast) 2.dp else 1.dp,
            bevel = null,
            glows = emptyList(),
            shadow = 12.dp,
            tonalElevation = 0.dp,
        )
    }

    Skin.SPOTIGLASS -> LocalSkinSettings.current.let { look ->
        SkinTokens(
            corner = look.spotiglassCorner.dp,
            fillAlpha = 1f,
            fill = Color(look.tint).copy(alpha = Color(look.tint).alpha * 0.6f),
            rim = Brush.linearGradient(
                listOf(Color(look.border).copy(alpha = 0.34f), Color.Transparent,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
            ),
            rimWidth = if (look.highContrast) 2.dp else 0.75.dp,
            bevel = null,
            glows = emptyList(),
            shadow = 8.dp,
            tonalElevation = 0.dp,
        )
    }
}

/**
 * What the glass has to look through.
 *
 * One layer, recorded by whatever sits under the chrome, and every pane draws
 * it back translated to its own position. Recording is the whole page redrawn
 * once more per frame, which is why this is offered rather than assumed: it is
 * switched on by [SkinSettings.blur] or [SkinSettings.vibrancy] being above
 * zero, and a person who wants the frames back turns them down to nothing.
 */
class Backdrop(val layer: GraphicsLayer?) {
    /** Where the recording starts on screen, so panes can subtract their own. */
    var origin: Offset = Offset.Zero

    /**
     * Held while the pen is down.
     *
     * Recording is the page drawn a second time, into a layer, on the same
     * render pass as the ink - and every pane that frosts it then re-records
     * itself through a blur because the layer changed. That is a chain of work
     * hanging off every stroke, to keep a toolbar's frost current while
     * somebody is looking at their pen. So it stops for the length of a stroke:
     * the panes go on drawing the last recording, which is a frost of the page
     * as it was a moment ago, and nobody has ever noticed the difference.
     */
    var paused by mutableStateOf(false)
}

val LocalBackdrop = compositionLocalOf { Backdrop(null) }

/**
 * Nothing to look through, for the inside of a recording.
 *
 * A pane that refracts draws the layer, so a pane *inside* the layer draws the
 * layer into itself and the render tree becomes a cycle - the RenderThread
 * walks it until the stack runs out. Every screen that records a backdrop hands
 * this to its own content, so the chrome above can frost it and the panels
 * within it simply stay as they are.
 */
val NoBackdrop = Backdrop(null)

@Composable
fun rememberBackdrop(active: Boolean): Backdrop {
    val layer = if (active) rememberGraphicsLayer() else null
    return remember(layer) { Backdrop(layer) }
}

/**
 * Records everything drawn inside it, for the panes above to look through -
 * or, given a [source], only what that draws, while the content itself is
 * drawn as usual. A source is how a screen keeps a view that holds surfaces
 * out of the recording.
 */
fun Modifier.recordBackdrop(
    backdrop: Backdrop,
    source: (DrawScope.() -> Unit)? = null,
    /** True when the last recording no longer matches the page and must be redone even while paused. */
    stale: (() -> Boolean)? = null,
): Modifier {
    val layer = backdrop.layer ?: return this
    return this
        // On screen rather than in the window: a popup or a dialog is a window
        // of its own, and a pane in it measured against this window's corner
        // drew the page from the wrong place.
        .onGloballyPositioned { backdrop.origin = it.positionOnScreen() }
        .drawWithContent {
            // Read here rather than in composition, so putting the pen down
            // invalidates one draw instead of recomposing the screen. The
            // governor covers every canvas, the reference panel's too, and
            // the settle after a stroke, so strokes in quick succession do not
            // re-record the page in each gap between them.
            if (stale?.invoke() != true && (backdrop.paused || InteractionGovernor.observed != Interaction.IDLE)) {
                drawContent()
                return@drawWithContent
            }
            if (source != null) {
                drawContent()
                layer.record { source() }
                return@drawWithContent
            }
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

/**
 * Every floating piece of chrome - the toolbar, the page list, the action bars -
 * goes through here, so a skin is chosen once and lands everywhere.
 */
@Composable
fun SkinSurface(
    modifier: Modifier = Modifier,
    /** Null takes the skin's own radius; pass 0.dp for chrome flush to an edge. */
    corner: Dp? = null,
    /**
     * Set for chrome that spans the window rather than floating in it. Its
     * corners go square and only the inner edge is drawn: a rim all the way
     * round something whose three other sides are the window edge reads as a
     * seam - a hairline of daylight between the bar and the screen, which is
     * what the gap along the top of the docked toolbar was.
     */
    flush: Boolean = false,
    content: @Composable () -> Unit,
) {
    val skin = LocalSkin.current
    val tokens = skin.tokens()
    val radius = if (flush) 0.dp else (corner ?: tokens.corner)
    val shape = RoundedCornerShape(radius)
    if (skin == Skin.MATERIAL) {
        Surface(
            modifier = modifier,
            shape = shape,
            tonalElevation = tokens.tonalElevation,
            shadowElevation = tokens.shadow,
            content = content,
        )
        return
    }
    val spotiHost = if (skin == Skin.SPOTIGLASS) rememberLiquidGlassBackdrop() else null
    val surfaceModifier = if (skin == Skin.SPOTIGLASS) {
        modifier.spotiGlassSurface(
            if (radius == 0.dp) shape else SpotiGlassShape(radius), LocalSkinSettings.current,
            elevation = if (flush) 0.dp else tokens.shadow, rim = !flush,
            exportedBackdrop = spotiHost,
        ).then(if (flush) Modifier.flushEdge(tokens) else Modifier)
    } else {
        modifier
            .frostedGlass(tokens, shape)
            .then(if (flush) Modifier.flushEdge(tokens) else Modifier.glassEdge(tokens, shape))
    }
    Box(surfaceModifier) {
        if (spotiHost != null) {
            CompositionLocalProvider(LocalSpotiGlassHostBackdrop provides spotiHost) { content() }
        } else content()
    }
}

/**
 * The skin's material on an outline the caller chooses, for chrome built
 * from the toolbar's shapes rather than from [SkinSurface]'s rounded panel:
 * solid under Material, frosted under Glassmorphism, and under Liquid glass
 * the refractive lens the bar itself is made of. A [panel] carries text, so
 * it is more solid than a bar that only carries icons.
 */
@Composable
internal fun Modifier.skinMaterial(shape: Shape, panel: Boolean = false): Modifier {
    val skin = LocalSkin.current
    val tokens = skin.tokens()
    return when (skin) {
        Skin.MATERIAL -> {
            val scheme = MaterialTheme.colorScheme
            this
                .shadow(if (panel) 6.dp else 3.dp, shape, clip = false)
                .clip(shape)
                .background(if (panel) scheme.surfaceContainerHigh else scheme.surfaceContainer)
        }
        Skin.GLASSMORPHISM -> this
            .frostedGlass(tokens, shape, fill = if (panel) MaterialTheme.colorScheme.surfaceContainerHigh else null)
            .glassEdge(tokens, shape)
        Skin.SPOTIGLASS -> liquidGlassPane(shape, panel = panel)
    }
}

/**
 * Frost: a shadow, the page behind blurred, and the body colour over it -
 * [fill], or the skin's own when null.
 */
@Composable
private fun Modifier.frostedGlass(tokens: SkinTokens, shape: Shape, shadow: Boolean = true, fill: Color? = null): Modifier {
    val body = fill ?: tokens.fill ?: MaterialTheme.colorScheme.surface.copy(alpha = tokens.fillAlpha)
    return this
        .then(if (shadow) Modifier.shadow(tokens.shadow, shape, clip = false) else Modifier)
        .clip(shape)
        .frost()
        // Battery saver stops the frost; a pane that stays see-through
        // without it puts its labels straight over the ink.
        .background(if (InteractionGovernor.observedPowerSave) body.copy(alpha = maxOf(body.alpha, 0.92f)) else body)
}

/** The one edge a window-wide bar has: the line where it lets the page go. */
private fun Modifier.flushEdge(tokens: SkinTokens): Modifier = drawWithContent {
    drawContent()
    val brush = tokens.rim ?: return@drawWithContent
    val width = tokens.rimWidth.toPx()
    drawRect(
        brush,
        topLeft = Offset(0f, size.height - width),
        size = androidx.compose.ui.geometry.Size(size.width, width),
    )
}

/**
 * Draws the recorded backdrop back under this pane, frosted.
 *
 * The layer holds the whole page as it was drawn a moment ago; this takes the
 * part of it that lies under this pane and scatters it, so what shows through
 * is softened rather than merely dimmed. Without a backdrop - frost turned off,
 * or a pane with nothing recorded beneath it - nothing is drawn and the panel
 * stays as it was.
 */
@Composable
private fun Modifier.frost(): Modifier {
    val backdrop = LocalBackdrop.current
    val layer = backdrop.layer ?: return this
    val settings = LocalSkinSettings.current
    // The pane's own layer, and the reason it exists: a RenderEffect belongs to
    // the layer it is set on, and the backdrop layer is also what draws the page
    // itself. Hanging the blur on it put it on the page - the PDF came out
    // softened and over-saturated, which is the effect meant for the half-inch
    // of glass sitting on top of it. This one holds the effect; the backdrop
    // stays clean.
    val pane = rememberGraphicsLayer()
    // Built when the numbers change and not once per pane per frame. This is a
    // native RenderEffect: it was being allocated inside the draw of every pane
    // on screen, every frame the page moved under them.
    val density = LocalDensity.current
    val effect = remember(settings.blur, settings.vibrancy, density) {
        frostEffect(blurPx = with(density) { settings.blur.dp.toPx() }, vibrancy = settings.vibrancy)
    }
    // Set while a popup this pane belongs to is growing; reading it redraws
    // the pane each frame of that, so the page under it keeps its place.
    val motion = LocalBackdropMotion.current
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var here by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned {
            placed[0] = it
            here = it.positionOnScreen()
        }
        .drawBehind {
            here
            motion?.invoke()
            pane.renderEffect = effect
            // The pane is already clipped to its shape, so drawing the whole
            // backdrop back by this pane's place on screen - and at the
            // inverse of any scale it is drawn at - lands the right part of
            // the page underneath it.
            pane.record { drawBackdropAligned(layer, backdrop.origin, placed[0]) }
            drawLayer(pane)
        }
}

/**
 * The edge, in the order the reference file layers it: inner glows, then the
 * bevel, then the specular over the top. Everything is stroked and the surface
 * is clipped, so the outer half of each stroke falls away and what is left hugs
 * the inside - the difference between glass with thickness and a rectangle with
 * a border drawn round it.
 */
private fun Modifier.glassEdge(tokens: SkinTokens, shape: Shape): Modifier = drawWithContent {
    drawContent()
    // The edge follows whatever shape the fill uses - the panel's continuous
    // curve, or the slider's true capsule. Stroking a different curve over the
    // fill leaves a visible double line at every corner.
    val edge = pathOf(shape, size, layoutDirection, this)
    if (tokens.glows.isNotEmpty()) {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
            }
            // The glows are measured for a panel. On something as thin as a
            // slider a 16dp glow reaches past the middle from both sides and
            // floods the whole control white, so each one is capped against the
            // short side rather than applied blind.
            val ceiling = size.minDimension / 4f
            for ((widthDp, colour) in tokens.glows) {
                val glow = widthDp.toPx().coerceAtMost(ceiling)
                if (glow <= 0.5f) continue
                paint.strokeWidth = glow
                paint.color = colour.toArgb()
                paint.maskFilter = BlurMaskFilter(glow / 2f, BlurMaskFilter.Blur.NORMAL)
                canvas.nativeCanvas.drawPath(edge.asAndroidPath(), paint)
            }
        }
    }
    val width = tokens.rimWidth.toPx()
    fun strokeEdge(brush: Brush, insetBy: Float) {
        inset(insetBy) {
            drawPath(pathOf(shape, size, layoutDirection, this), brush, style = Stroke(width))
        }
    }
    // The bevel sits one line further in than the specular, so the two read as
    // the near and the far face of the same edge rather than as one thick line.
    tokens.bevel?.let { strokeEdge(it, width * 1.5f) }
    tokens.rim?.let { strokeEdge(it, width / 2f) }
}

/** A shape's outline as a path, whatever kind of outline it produces. */
private fun pathOf(
    shape: Shape,
    size: androidx.compose.ui.geometry.Size,
    layoutDirection: LayoutDirection,
    density: Density,
): Path = when (val outline = shape.createOutline(size, layoutDirection, density)) {
    is androidx.compose.ui.graphics.Outline.Generic -> outline.path
    is androidx.compose.ui.graphics.Outline.Rounded ->
        Path().apply { addRoundRect(outline.roundRect) }
    is androidx.compose.ui.graphics.Outline.Rectangle ->
        Path().apply { addRect(outline.rect) }
}

/**
 * The skin's rim, for a surface this file does not own.
 *
 * A dialog paints itself, and all a theme can hand it is a colour and a shape;
 * this is the third thing glass needs. Applied to a component's own modifier it
 * lands outside that component's clip and on the same outline, so the dialog
 * gets the edge every other pane has instead of ending in a soft nothing.
 */
@Composable
fun skinEdge(shape: Shape): Modifier {
    val skin = LocalSkin.current
    if (skin == Skin.MATERIAL) return Modifier
    return Modifier.glassEdge(skin.tokens(), shape)
}

/** The border a skin would draw, for the few places that want one directly. */
@Composable
fun skinBorder(): BorderStroke? {
    val tokens = LocalSkin.current.tokens()
    val rim = tokens.rim ?: return null
    return BorderStroke(tokens.rimWidth, rim)
}

/**
 * The slider from the reference: a thin track that fills as it goes, and a lens
 * of glass standing on it.
 *
 * Material's slider is a hairline with a disc on it; this one is the shape in
 * the file - a rounded upright pane, taller than the track and wide enough to
 * have a face, translucent so the filled part of the track carries on through
 * it instead of being cut in half by the handle. Under glassmorphism it swells
 * while it is held, which is the whole of how the control tells you it has you.
 *
 * Both skins get the shape. The skin decides what the lens is made of: the
 * tunable glass and its lit rim under glassmorphism; under Material a solid
 * white handle that holds still, since Material's chrome does not move.
 */
@Composable
fun SkinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    /**
     * The interval values come in. The handle only ever stands on one of
     * them and the value reported is always one, rather than gliding across
     * the track in between. 0 picks a hundredth of the range.
     */
    step: Float = 0f,
) {
    val skin = LocalSkin.current
    val steps = sliderSteps(valueRange, step)
    if (skin.isRefractive) {
        LiquidGlassSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = modifier,
            steps = steps,
            label = "Value",
        )
        return
    }
    val tokens = skin.tokens()
    val still = skin == Skin.MATERIAL
    val scheme = MaterialTheme.colorScheme
    val trackAlpha = if (LocalSkinSettings.current.highContrast) 0.34f else 0.14f
    val density = LocalDensity.current
    var held by remember { mutableStateOf(false) }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var width by remember { mutableFloatStateOf(0f) }
    val swell = held && !still
    val thumbWidth by animateDpAsState(
        targetValue = if (swell) SLIDER_THUMB_W_HELD else SLIDER_THUMB_W,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "slider thumb width",
    )
    val thumbHeight by animateDpAsState(
        targetValue = if (swell) SLIDER_THUMB_H_HELD else SLIDER_THUMB_H,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "slider thumb height",
    )

    // The travel is the track less one thumb, so the thumb comes to rest with
    // its edge on the end of the track rather than half of it hanging off. It
    // is measured at the resting width: letting the swell move the scale would
    // make the value drift under a finger that is holding still.
    val half = with(density) { SLIDER_THUMB_W.toPx() } / 2f
    // Held, the handle follows the finger itself, not the stepped value under
    // it; let go, it eases onto the value it chose. See [sliderShownFraction].
    var dragFraction by remember { mutableFloatStateOf(NOT_DRAGGING) }
    val shown = sliderShownFraction(fraction, dragFraction, animate = !still)
    val centre = half + shown * ((width - 2f * half).coerceAtLeast(1f))

    // A gesture block outlives the composition that started it, so both the
    // width it measures against and the callback it reports to are read at the
    // moment of the drag rather than captured when the finger went down. Caught
    // the hard way: a captured callback writes back the settings as they were,
    // taking whatever was changed in between with it.
    val latest by rememberUpdatedState(onValueChange)
    fun report(x: Float) {
        val travel = (width - 2f * half).coerceAtLeast(1f)
        val raw = ((x - half) / travel).coerceIn(0f, 1f)
        dragFraction = raw
        latest(spotiSliderValue(raw, valueRange, steps))
    }

    Box(
        modifier
            .height(SLIDER_ROW_H)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(valueRange) {
                detectDragGestures(
                    onDragStart = { held = true },
                    onDragEnd = {
                        held = false
                        dragFraction = NOT_DRAGGING
                    },
                    onDragCancel = {
                        held = false
                        dragFraction = NOT_DRAGGING
                    },
                ) { change, _ ->
                    change.consume()
                    report(change.position.x)
                }
            }
            .pointerInput(valueRange) {
                detectTapGestures(
                    onPress = {
                        held = true
                        report(it.x)
                        tryAwaitRelease()
                        held = false
                        dragFraction = NOT_DRAGGING
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SLIDER_TRACK_H)
                .clip(CircleShape)
                .background(scheme.onSurface.copy(alpha = trackAlpha)),
        ) {
            // Filled to the middle of the thumb, not to its near edge: the
            // reading is where the lens is centred, so that is where the colour
            // has to stop.
            Box(
                Modifier
                    .fillMaxWidth(if (width > 2f * half) (centre / width).coerceIn(0f, 1f) else 0f)
                    .fillMaxHeight()
                    // The outer clip only rounds the track's own two ends; the
                    // fill's far edge is a corner of its own and stayed square
                    // wherever the thumb was short of the end. Its own capsule
                    // rounds that edge too.
                    .clip(CircleShape)
                    .background(scheme.primary),
            )
        }
        Thumb(
            Modifier
                .offset { IntOffset((centre - thumbWidth.toPx() / 2f).roundToInt(), 0) }
                .size(thumbWidth, thumbHeight),
            // A capsule, as in the file: the ends are half the thumb's height,
            // so the lens has no corner of its own to argue with the track's.
            shape = RoundedCornerShape(percent = 50),
            tokens = tokens,
            // The shadow is ordinary until the thumb is taken hold of, and then
            // it picks up the accent - the same swell said in light.
            glow = if (swell) scheme.primary else Color.Black,
            solid = still,
        )
    }
}

/**
 * The toggle from the reference: one lens, sliding along a coloured capsule.
 *
 * Material's thumb is a disc that shrinks and grows inside its track. This one
 * is the other idea - a pane of glass a little taller than the track it rides
 * on, so it stands proud of it, and translucent enough that the colour beneath
 * carries through the glass rather than being hidden by it. On, then, is a
 * capsule of accent seen twice: once beside the lens and once through it.
 * Under Material the lens is solid white and changes sides at once.
 */
@Composable
fun SkinSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    if (LocalSkin.current == Skin.SPOTIGLASS) {
        SpotiGlassSwitch(checked, onCheckedChange)
        return
    }
    val skin = LocalSkin.current
    val tokens = skin.tokens()
    val still = skin == Skin.MATERIAL
    val scheme = MaterialTheme.colorScheme
    val trackAlpha = if (LocalSkinSettings.current.highContrast) 0.34f else 0.14f
    val interaction = remember { MutableInteractionSource() }
    // Animate the centre: the lens slides from one end to the other.
    val centre by animateDpAsState(
        targetValue = if (checked) {
            SWITCH_W - SWITCH_THUMB_INSET - SWITCH_THUMB_W / 2f
        } else {
            SWITCH_THUMB_INSET + SWITCH_THUMB_W / 2f
        },
        animationSpec = if (still) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        },
        label = "switch lens centre",
    )
    val shift = centre - SWITCH_THUMB_W / 2f
    val track by animateColorAsState(
        if (checked) scheme.primary else scheme.onSurface.copy(alpha = trackAlpha),
        animationSpec = if (still) snap() else spring(),
        label = "switch track",
    )
    Box(
        Modifier
            .size(SWITCH_W, SWITCH_ROW)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                // No ripple: it would be a rectangle round a capsule, and the
                // lens moving is already the answer to the press.
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SWITCH_TRACK)
                .clip(CircleShape)
                .background(track),
        )
        Thumb(
            Modifier
                .offset(x = shift)
                .size(SWITCH_THUMB_W, SWITCH_THUMB_H),
            shape = RoundedCornerShape(percent = 50),
            tokens = tokens,
            // A lit handle on a lit track: the glow is the accent while the
            // switch is on, and an ordinary shadow while it is off.
            glow = if (checked && !still) scheme.primary else Color.Black,
            solid = still,
        )
    }
}

/**
 * The lens both controls are handled by.
 *
 * A body, two bright edges, and whatever rim the skin draws. The middle is left
 * clear on purpose - that is the part the track shows through, and it is the
 * difference between a piece of glass and a white knob. A [solid] thumb is the
 * white knob: Material's, one colour with a hairline to find it on white.
 */
@Composable
private fun Thumb(
    modifier: Modifier,
    shape: Shape,
    tokens: SkinTokens,
    glow: Color,
    solid: Boolean = false,
) {
    val coat = modifier
        .shadow(THUMB_SHADOW, shape, clip = false, ambientColor = glow, spotColor = glow)
        .clip(shape)
    if (solid) {
        Box(
            coat
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), shape),
        )
        return
    }
    Box(
        coat
            // Two coats. The skin's own glass is thin enough to vanish against
            // a white settings page, where the rim is white too and the only
            // thing left holding the shape is the shadow, so the lens gets a
            // milky base under it: still transparent enough for the track to
            // carry through, opaque enough to be an object on a pale ground.
            .background(Color.White.copy(alpha = 0.45f))
            .background(tokens.fill ?: Color.White.copy(alpha = 0.55f))
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.72f),
                    0.3f to Color.White.copy(alpha = 0.06f),
                    0.7f to Color.White.copy(alpha = 0.06f),
                    1f to Color.White.copy(alpha = 0.46f),
                ),
            )
            .border(
                width = maxOf(tokens.rimWidth, 1.dp),
                brush = tokens.rim
                    ?: SolidColor(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)),
                shape = shape,
            ),
    )
}

// Measured off the reference prototype rather than guessed at, and the ratios
// are what was wrong before: both lenses were portrait, taller than they were
// wide, and the file's are the other way round - a wide, flat capsule lying
// along the track. Everything here is that file's proportion at this app's
// scale.
//
// Slider: the thumb is 1.45 as wide as it is tall, and about four and a half
// times the height of the track it rides on.
// One row height for everything in the toolbar: an icon button is 40dp, and a
// slider that asks for 44 makes the whole bar taller for one control.
private val SLIDER_ROW_H = 40.dp
private val SLIDER_TRACK_H = 6.dp
private val SLIDER_THUMB_W = 36.dp
private val SLIDER_THUMB_H = 25.dp
private val SLIDER_THUMB_W_HELD = 40.dp
private val SLIDER_THUMB_H_HELD = 28.dp

// Switch: the reference file's own proportions. The control is intentionally
// long and its knob intentionally lozenge-shaped; shortening it to a conventional
// Android switch was the visual mismatch the reference makes most obvious.
private val SWITCH_ROW = 44.dp
private val SWITCH_W = 74.dp
private val SWITCH_TRACK = 32.dp
private val SWITCH_THUMB_W = 47.dp
private val SWITCH_THUMB_H = 28.dp
private val SWITCH_THUMB_INSET = 2.dp

private val THUMB_SHADOW = 6.dp

/**
 * The shapes Material's own components reach for. Overriding these is how the
 * dialogs, menus and cards take the skin without every call site being edited:
 * AlertDialog asks the theme for `extraLarge`, DropdownMenu for `extraSmall`,
 * and so on, so replacing the set replaces all of them at once.
 *
 * One set for every skin. Each skin used to carry its own ladder (Material's
 * 4dp menus, glass's 17dp chips and 36dp dialogs) beside panels hand-drawn at
 * yet other radii, so a menu never matched the bar it dropped from. The skin
 * still decides what a surface is made of; [Ui.shapes] decides its outline.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun skinShapes(skin: Skin): Shapes = Ui.shapes

/** How solid a popup's own surface is. The platform blurs what is behind it. */
private const val POPUP_ALPHA = 0.86f

/**
 * The same trick for colour.
 *
 * Two things happen here. Text and icons take the colour the settings screen
 * names, and they take it in every skin - ink is a choice about reading, not
 * about texture, and it reaches the "on" roles together so a pale ink cannot
 * leave half the app dark. Then, on glass, the container roles go translucent
 * and pick up the glass's own tint: dialogs and menus paint themselves with
 * those, and it is what stops a popup from being the one Material slab left in
 * a glass app.
 *
 * The settings are passed rather than read off the composition: the theme is
 * built above [ProvideSkin], where the local still holds its defaults, and a
 * read there quietly gives back the colours nobody chose.
 */
fun skinColors(base: ColorScheme, skin: Skin, look: SkinSettings): ColorScheme {
    // 다크 모드에서 라이트 모드용 사용자 잉크색을 그대로 쓰면 검은 글씨가 됩니다.
    // 이 경우 생성한 다크 팔레트의 대비 보장 색을 사용합니다.
    val ink = if (look.themeMode == AppThemeMode.DARK) base.onSurface else Color(look.content)
    val inked = base.copy(
        onSurface = ink,
        onBackground = ink,
        // The quieter ink: labels, hints, and every icon that is not the tool
        // in hand. Turned up it stops being quieter.
        onSurfaceVariant = if (look.highContrast) ink else ink.copy(alpha = 0.72f),
    )
    if (skin == Skin.MATERIAL) return inked
    // Popups live in their own window and are blurred by the platform rather
    // than by the backdrop layer, so they can be far more solid than the in-app
    // chrome without looking like a Material slab dropped in.
    val alpha = when {
        look.highContrast -> 0.98f
        skin.isRefractive -> REFRACTIVE_POPUP_ALPHA
        else -> POPUP_ALPHA
    }
    val tint = darkAwareTint(Color(look.tint), look.themeMode == AppThemeMode.DARK)
    fun glassy(container: Color): Color =
        tint.compositeOver(container).copy(alpha = alpha)
    return inked.copy(
        surfaceContainer = glassy(base.surfaceContainer),
        surfaceContainerHigh = glassy(base.surfaceContainerHigh),
        surfaceContainerHighest = glassy(base.surfaceContainerHighest),
        surfaceContainerLow = glassy(base.surfaceContainerLow),
    )
}

/**
 * The glass tint as a theme should show it. A white frost is right over light
 * panels; over dark ones it turns them grey and pales the light text on them,
 * so a dark theme keeps only a trace of a light tint. A dark tint stays as chosen.
 */
internal fun darkAwareTint(tint: Color, dark: Boolean): Color =
    if (dark && tint.luminance() > 0.5f) tint.copy(alpha = (tint.alpha * 0.25f).coerceAtMost(0.08f)) else tint

/** Clearer than frost, still solid enough for a popup window with no backdrop capture. */
private const val REFRACTIVE_POPUP_ALPHA = 0.80f

/**
 * Stops between the ends of a slider for values every [step] across
 * [range] - the count the sliders take - or every hundredth when no step is
 * given. A step that does not divide the range lands on the nearest count.
 */
internal fun sliderSteps(range: ClosedFloatingPointRange<Float>, step: Float): Int {
    val span = range.endInclusive - range.start
    if (!(span > 0f)) return 0
    val size = if (step > 0f) step else span / 100f
    return ((span / size).roundToInt() - 1).coerceIn(0, 10_000)
}

/** A slider handle's drag position when no finger is on it. */
internal const val NOT_DRAGGING = -1f

/**
 * Where a slider's handle is drawn. While held ([dragFraction] at or above 0)
 * it stays under the finger, moving as smoothly as the finger does, whatever
 * stepped value that lands on. Released, it settles onto [valueFraction] - the
 * stop it chose - with a short spring rather than a jump, unless [animate]
 * is off.
 */
@Composable
internal fun sliderShownFraction(
    valueFraction: Float,
    dragFraction: Float,
    /** False lands it on the stop at once, for chrome that does not move. */
    animate: Boolean = true,
): Float {
    val dragging = dragFraction >= 0f
    val shown by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dragging) dragFraction else valueFraction,
        animationSpec = if (dragging || !animate) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "slider handle",
    )
    return shown.coerceIn(0f, 1f)
}
