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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How the app's chrome is dressed. The note itself never changes - paper is
 * paper - so this only reaches the bars, panels and dialogs floating over it.
 */
enum class Skin(val label: String, val blurb: String) {
    MATERIAL("머티리얼", "안드로이드 기본. 불투명하고 또렷합니다."),
    GLASSMORPHISM("글래스모피즘", "서리 낀 유리. 뒤가 흐려지고 고르게 반투명합니다."),
    LIQUID_GLASS(
        "리퀴드 글래스",
        "빛을 굴절시키는 얇은 렌즈. 누르고 움직이면 형태와 광택이 살아납니다.",
    ),
}

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

@Composable
fun ProvideSkin(skin: Skin, settings: SkinSettings, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSkin provides skin,
        LocalSkinSettings provides settings,
        content = content,
    )
}

@Composable
fun Skin.tokens(): SkinTokens = when (this) {
    Skin.MATERIAL -> SkinTokens(
        corner = 20.dp,
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
        val body = Color(look.tint)
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
            rim = SolidColor(if (look.highContrast) Color(look.content) else Color(look.border)),
            rimWidth = if (look.highContrast) 2.dp else 1.dp,
            bevel = null,
            glows = emptyList(),
            shadow = 12.dp,
            tonalElevation = 0.dp,
        )
    }

    // Apple's material is not simply a blur with a white coat. It is a thin,
    // mostly colourless lens: the content beneath supplies the colour, one side
    // catches the light, the far side gets a quiet bevel, and the shadow grows
    // enough to keep the control legible when the background gets busy.
    Skin.LIQUID_GLASS -> LocalSkinSettings.current.let { look ->
        val tint = Color(look.tint)
        val border = Color(look.border)
        val ink = Color(look.content)
        val accent = Color(look.accent)
        SkinTokens(
            corner = look.corner.dp,
            fillAlpha = 1f,
            fill = if (look.highContrast) {
                tint.copy(alpha = maxOf(tint.alpha, 0.90f))
            } else {
                // Keep user hue, but let the backdrop be the body of the glass.
                tint.copy(alpha = (tint.alpha * 0.58f).coerceIn(0.08f, 0.32f))
            },
            rim = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (look.highContrast) 1f else 0.96f),
                    border.copy(alpha = maxOf(border.alpha, 0.74f)),
                    Color.White.copy(alpha = 0.24f),
                    ink.copy(alpha = if (look.highContrast) 0.70f else 0.22f),
                ),
            ),
            rimWidth = if (look.highContrast) 2.dp else 1.dp,
            bevel = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.12f),
                    accent.copy(alpha = 0.16f),
                    ink.copy(alpha = if (look.highContrast) 0.34f else 0.16f),
                ),
            ),
            glows = if (look.highContrast) {
                listOf(4.dp to Color.White.copy(alpha = 0.16f))
            } else {
                listOf(
                    12.dp to Color.White.copy(alpha = 0.12f),
                    5.dp to accent.copy(alpha = 0.08f),
                )
            },
            shadow = if (look.highContrast) 12.dp else 18.dp,
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

/** Records everything drawn inside it, for the panes above to look through. */
fun Modifier.recordBackdrop(backdrop: Backdrop): Modifier {
    val layer = backdrop.layer ?: return this
    return this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            // Read here rather than in composition, so putting the pen down
            // invalidates one draw instead of recomposing the screen.
            if (backdrop.paused) {
                drawContent()
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
    Box(
        modifier
            .shadow(tokens.shadow, shape, clip = false)
            .clip(shape)
            .frost()
            .background(tokens.fill ?: MaterialTheme.colorScheme.surface.copy(alpha = tokens.fillAlpha))
            .then(
                if (skin == Skin.LIQUID_GLASS) {
                    Modifier.liquidLight(MaterialTheme.colorScheme.primary)
                } else {
                    Modifier
                },
            )
            .then(if (flush) Modifier.flushEdge(tokens) else Modifier.glassEdge(tokens, shape)),
    ) {
        content()
    }
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
    val liquid = LocalSkin.current == Skin.LIQUID_GLASS
    // The pane's own layer, and the reason it exists: a RenderEffect belongs to
    // the layer it is set on, and the backdrop layer is also what draws the page
    // itself. Hanging the blur and the bend on it put them on the page - the PDF
    // came out softened and over-saturated, which is the effect meant for the
    // half-inch of glass sitting on top of it. This one holds the effect; the
    // backdrop stays clean.
    val pane = rememberGraphicsLayer()
    // Built when the numbers change and not once per pane per frame. This is a
    // native RenderEffect: it was being allocated inside the draw of every pane
    // on screen, every frame the page moved under them.
    val density = LocalDensity.current
    var paneSize by remember { mutableStateOf(IntSize.Zero) }
    val effect = remember(settings.blur, settings.vibrancy, settings.highContrast, density, liquid, paneSize) {
        val blurPx = with(density) { settings.blur.dp.toPx() }
        if (liquid) {
            liquidGlassEffect(
                // Liquid Glass stays clearer than frosted glass; the lens and
                // edge do the separation instead of scattering everything.
                blurPx = blurPx * if (settings.highContrast) 0.82f else 0.58f,
                vibrancy = (settings.vibrancy + 0.18f).coerceAtMost(1.2f),
                widthPx = paneSize.width,
                heightPx = paneSize.height,
                bendPx = with(density) { (if (settings.highContrast) 4.dp else 8.dp).toPx() },
            )
        } else {
            frostEffect(blurPx = blurPx, vibrancy = settings.vibrancy)
        }
    }
    var here by remember { mutableStateOf(Offset.Zero) }
    return this
        .onSizeChanged { paneSize = it }
        .onGloballyPositioned { here = it.positionInRoot() }
        .drawBehind {
            val at = here - backdrop.origin
            pane.renderEffect = effect
            // The pane is already clipped to its shape, so translating the
            // whole backdrop back by this pane's offset lands the right part of
            // the page underneath it.
            pane.record { translate(-at.x, -at.y) { drawLayer(layer) } }
            drawLayer(pane)
        }
}

/** Ambient colour and a moving-looking highlight inside the clear lens body. */
private fun Modifier.liquidLight(accent: Color): Modifier = drawWithContent {
    // Applied after the translucent body and before child content. The two
    // fields therefore light the material without washing out its labels.
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
            center = Offset(size.width * 0.16f, -size.height * 0.08f),
            radius = maxOf(size.width, size.height) * 0.92f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width * 0.84f, size.height * 1.05f),
            radius = maxOf(size.width, size.height) * 0.76f,
        ),
    )
    drawContent()
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
 * it instead of being cut in half by the handle. It swells while it is held,
 * which is the whole of how the control tells you it has you.
 *
 * Both skins get the shape. The skin decides what the lens is made of: the
 * tunable glass and its lit rim under glassmorphism, plain white under
 * Material, where the tokens name no fill and no edge.
 */
@Composable
fun SkinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    val tokens = skin.tokens()
    val liquid = skin == Skin.LIQUID_GLASS
    val scheme = MaterialTheme.colorScheme
    val trackAlpha = if (LocalSkinSettings.current.highContrast) 0.34f else 0.14f
    val density = LocalDensity.current
    var held by remember { mutableStateOf(false) }
    // Fast movement pulls the lens wider. It springs home on release, which is
    // the small, tactile deformation Apple gives its new slider thumbs.
    var motionStretch by remember { mutableFloatStateOf(0f) }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var width by remember { mutableFloatStateOf(0f) }
    val thumbWidth by animateDpAsState(
        targetValue = when {
            liquid && held -> SLIDER_THUMB_W + (LIQUID_SLIDER_PRESS_STRETCH + motionStretch).dp
            held -> SLIDER_THUMB_W_HELD
            else -> SLIDER_THUMB_W
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "slider thumb width",
    )
    val thumbHeight by animateDpAsState(
        targetValue = when {
            liquid && held -> LIQUID_SLIDER_THUMB_H_HELD
            held -> SLIDER_THUMB_H_HELD
            else -> SLIDER_THUMB_H
        },
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
    val centre = half + fraction * ((width - 2f * half).coerceAtLeast(1f))

    // A gesture block outlives the composition that started it, so both the
    // width it measures against and the callback it reports to are read at the
    // moment of the drag rather than captured when the finger went down. Caught
    // the hard way: a captured callback writes back the settings as they were,
    // taking whatever was changed in between with it.
    val latest by rememberUpdatedState(onValueChange)
    fun report(x: Float) {
        val travel = (width - 2f * half).coerceAtLeast(1f)
        latest(valueRange.start + ((x - half) / travel).coerceIn(0f, 1f) * span)
    }

    Box(
        modifier
            .height(SLIDER_ROW_H)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(valueRange) {
                detectDragGestures(
                    onDragStart = {
                        held = true
                        motionStretch = 0f
                    },
                    onDragEnd = {
                        held = false
                        motionStretch = 0f
                    },
                    onDragCancel = {
                        held = false
                        motionStretch = 0f
                    },
                ) { change, dragAmount ->
                    change.consume()
                    if (liquid) {
                        motionStretch = (abs(dragAmount.x) / density.density * 0.72f)
                            .coerceAtMost(LIQUID_SLIDER_MAX_STRETCH)
                    }
                    report(change.position.x)
                }
            }
            .pointerInput(valueRange) {
                detectTapGestures(
                    onPress = {
                        held = true
                        if (liquid) motionStretch = LIQUID_SLIDER_TAP_STRETCH
                        report(it.x)
                        tryAwaitRelease()
                        held = false
                        motionStretch = 0f
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
                .then(
                    if (liquid) {
                        Modifier
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.24f),
                                        scheme.onSurface.copy(alpha = trackAlpha * 0.72f),
                                        scheme.onSurface.copy(alpha = trackAlpha * 1.35f),
                                    ),
                                ),
                            )
                            .border(
                                0.75.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.72f),
                                        scheme.onSurface.copy(alpha = 0.16f),
                                    ),
                                ),
                                CircleShape,
                            )
                    } else {
                        Modifier.background(scheme.onSurface.copy(alpha = trackAlpha))
                    },
                ),
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
                    .background(
                        if (liquid) {
                            Brush.verticalGradient(
                                listOf(
                                    scheme.primary.copy(alpha = 0.78f),
                                    scheme.primary,
                                    scheme.primary.copy(alpha = 0.82f),
                                ),
                            )
                        } else {
                            SolidColor(scheme.primary)
                        },
                    ),
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
            glow = if (held) scheme.primary else Color.Black,
            energized = held,
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
 */
@Composable
fun SkinSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val skin = LocalSkin.current
    val tokens = skin.tokens()
    val liquid = skin == Skin.LIQUID_GLASS
    val scheme = MaterialTheme.colorScheme
    val trackAlpha = if (LocalSkinSettings.current.highContrast) 0.34f else 0.14f
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val thumbWidth by animateDpAsState(
        targetValue = if (liquid && pressed) LIQUID_SWITCH_THUMB_W_HELD else SWITCH_THUMB_W,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "switch lens width",
    )
    val thumbHeight by animateDpAsState(
        targetValue = if (liquid && pressed) LIQUID_SWITCH_THUMB_H_HELD else SWITCH_THUMB_H,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "switch lens height",
    )
    // Animate the centre, then subtract half the live width. The lens can widen
    // under a finger without shifting the value it is pointing at.
    val centre by animateDpAsState(
        targetValue = if (checked) {
            SWITCH_W - SWITCH_THUMB_INSET - SWITCH_THUMB_W / 2f
        } else {
            SWITCH_THUMB_INSET + SWITCH_THUMB_W / 2f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "switch lens centre",
    )
    val shift = centre - thumbWidth / 2f
    val track by animateColorAsState(
        if (checked) scheme.primary else scheme.onSurface.copy(alpha = trackAlpha),
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
                .then(
                    if (liquid) {
                        Modifier
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        track.copy(alpha = if (checked) 0.80f else 0.20f),
                                        track,
                                        track.copy(alpha = if (checked) 0.84f else 0.36f),
                                    ),
                                ),
                            )
                            .border(
                                0.75.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.78f),
                                        scheme.onSurface.copy(alpha = 0.18f),
                                    ),
                                ),
                                CircleShape,
                            )
                    } else {
                        Modifier.background(track)
                    },
                ),
        )
        Thumb(
            Modifier
                .offset(x = shift)
                .size(thumbWidth, thumbHeight),
            shape = RoundedCornerShape(percent = 50),
            tokens = tokens,
            // A lit handle on a lit track: the glow is the accent while the
            // switch is on, and an ordinary shadow while it is off.
            glow = if (checked) scheme.primary else Color.Black,
            energized = checked || pressed,
        )
    }
}

/**
 * The lens both controls are handled by.
 *
 * A body, two bright edges, and whatever rim the skin draws. The middle is left
 * clear on purpose - that is the part the track shows through, and it is the
 * difference between a piece of glass and a white knob.
 */
@Composable
private fun Thumb(
    modifier: Modifier,
    shape: Shape,
    tokens: SkinTokens,
    glow: Color,
    energized: Boolean = false,
) {
    val liquid = LocalSkin.current == Skin.LIQUID_GLASS
    val shadow = if (liquid) {
        if (energized) LIQUID_THUMB_SHADOW_HELD else LIQUID_THUMB_SHADOW
    } else {
        THUMB_SHADOW
    }
    var coat = modifier
        .shadow(shadow, shape, clip = false, ambientColor = glow, spotColor = glow)
        .clip(shape)
    coat = if (liquid) {
        coat
            // Clear enough that the track keeps running through the lens. The
            // opposing gradients make the top edge catch and the lower face
            // retain weight against a white page.
            .background(Color.White.copy(alpha = 0.36f))
            .background(tokens.fill ?: Color.Transparent)
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.86f),
                    0.18f to Color.White.copy(alpha = 0.22f),
                    0.58f to Color.White.copy(alpha = 0.08f),
                    1f to Color.White.copy(alpha = 0.50f),
                ),
            )
            .liquidLight(if (energized) glow else Color.White)
            .glassEdge(tokens, shape)
    } else {
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
            )
    }
    Box(coat)
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
private val LIQUID_SLIDER_THUMB_H_HELD = 26.dp
private const val LIQUID_SLIDER_PRESS_STRETCH = 7f
private const val LIQUID_SLIDER_TAP_STRETCH = 3f
private const val LIQUID_SLIDER_MAX_STRETCH = 12f

// Switch: the reference file's own proportions. The control is intentionally
// long and its knob intentionally lozenge-shaped; shortening it to a conventional
// Android switch was the visual mismatch the reference makes most obvious.
private val SWITCH_ROW = 44.dp
private val SWITCH_W = 74.dp
private val SWITCH_TRACK = 32.dp
private val SWITCH_THUMB_W = 47.dp
private val SWITCH_THUMB_H = 28.dp
private val SWITCH_THUMB_INSET = 2.dp
private val LIQUID_SWITCH_THUMB_W_HELD = 52.dp
private val LIQUID_SWITCH_THUMB_H_HELD = 29.dp

private val THUMB_SHADOW = 6.dp
private val LIQUID_THUMB_SHADOW = 8.dp
private val LIQUID_THUMB_SHADOW_HELD = 12.dp

/**
 * The shapes Material's own components reach for. Overriding these is how the
 * dialogs, menus and cards take the skin without every call site being edited:
 * AlertDialog asks the theme for `extraLarge`, DropdownMenu for `extraSmall`,
 * and so on, so replacing the set replaces all of them at once.
 */
@Composable
fun skinShapes(skin: Skin): Shapes = if (skin == Skin.MATERIAL) {
    Shapes()
} else if (skin == Skin.LIQUID_GLASS) {
    // Concentric, generous curves: controls nest rather than presenting a
    // stack of unrelated corner radii.
    Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(17.dp),
        medium = RoundedCornerShape(22.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp),
    )
} else {
    Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(30.dp),
    )
}

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
    val ink = Color(look.content)
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
        skin == Skin.LIQUID_GLASS -> LIQUID_POPUP_ALPHA
        else -> POPUP_ALPHA
    }
    fun glassy(container: Color): Color =
        Color(look.tint).compositeOver(container).copy(alpha = alpha)
    return inked.copy(
        surfaceContainer = glassy(base.surfaceContainer),
        surfaceContainerHigh = glassy(base.surfaceContainerHigh),
        surfaceContainerHighest = glassy(base.surfaceContainerHighest),
        surfaceContainerLow = glassy(base.surfaceContainerLow),
    )
}

/** Clearer than frost, still solid enough for a popup window with no backdrop capture. */
private const val LIQUID_POPUP_ALPHA = 0.80f
