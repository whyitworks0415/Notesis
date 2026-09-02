package com.notesis

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the app's chrome is dressed. The note itself never changes - paper is
 * paper - so this only reaches the bars, panels and dialogs floating over it.
 */
enum class Skin(val label: String, val blurb: String) {
    MATERIAL("머티리얼", "안드로이드 기본. 불투명하고 또렷합니다."),
    LIQUID_GLASS("리퀴드 글래스", "얇은 렌즈. 대각선 양 끝이 빛을 물고 있습니다."),
    GLASSMORPHISM("글래스모피즘", "서리 낀 유리. 고르게 반투명합니다."),
}

/**
 * What a skin actually changes. Plain numbers rather than a pile of composables,
 * so a new skin is a row of values and not a new widget set.
 */
class SkinTokens(
    val corner: Dp,
    /** How much of the surface colour survives; the rest is what is behind. */
    val fillAlpha: Float,
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

@Composable
fun ProvideSkin(skin: Skin, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSkin provides skin, content = content)
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

    // Measured off the community Liquid Glass file, whose artboard is drawn at
    // 2.292x iOS points, so every value in it divides out to a whole one: a 1pt
    // bevel, a 1pt specular blurred half a point, inner glows at 3 and 16pt, and
    // an outer shadow of only 8pt.
    //
    // Two things guesswork gets wrong here. The specular is not top-to-bottom -
    // it lights the top-left and the bottom-right, light entering one corner of
    // a lens and leaving the other. And the shadow is small: the lift comes from
    // the glow against the inside of the edge, not from a soft drop underneath.
    Skin.LIQUID_GLASS -> SkinTokens(
        corner = 26.dp,
        // Over white paper the surface tint is most of what gets seen, so it is
        // kept low. The reference file is dark, where the same alpha reads far
        // heavier than it does here.
        fillAlpha = 0.30f,
        rim = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.92f),
            0.16f to Color.White.copy(alpha = 0.14f),
            0.5f to Color.Transparent,
            0.84f to Color.White.copy(alpha = 0.14f),
            1f to Color.White.copy(alpha = 0.88f),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
        rimWidth = 1.dp,
        bevel = Brush.linearGradient(
            0f to Color.Black.copy(alpha = 0.18f),
            0.35f to Color.Transparent,
            0.65f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.12f),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
        glows = listOf(
            16.dp to Color.White.copy(alpha = 0.26f),
            3.dp to Color.White.copy(alpha = 0.50f),
        ),
        shadow = 8.dp,
        tonalElevation = 0.dp,
    )

    Skin.GLASSMORPHISM -> SkinTokens(
        corner = 22.dp,
        fillAlpha = 0.62f,
        rim = Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.18f),
            ),
        ),
        rimWidth = 1.dp,
        bevel = null,
        glows = emptyList(),
        shadow = 10.dp,
        tonalElevation = 0.dp,
    )
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
    content: @Composable () -> Unit,
) {
    val skin = LocalSkin.current
    val tokens = skin.tokens()
    val radius = corner ?: tokens.corner
    // Material's corners are circular arcs by definition; only the glass skins
    // get the continuous curve, which is part of what tells them apart.
    val shape = if (skin == Skin.MATERIAL) {
        RoundedCornerShape(radius)
    } else {
        SquircleShape(radius)
    }
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
            .background(MaterialTheme.colorScheme.surface.copy(alpha = tokens.fillAlpha))
            .glassEdge(tokens, shape),
    ) {
        content()
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

/** The border a skin would draw, for the few places that want one directly. */
@Composable
fun skinBorder(): BorderStroke? {
    val tokens = LocalSkin.current.tokens()
    val rim = tokens.rim ?: return null
    return BorderStroke(tokens.rimWidth, rim)
}

/**
 * The slider from the reference file: not a hairline with a knob on it, but a
 * thick capsule of glass that fills as it goes. Its proportions are that file's
 * (1484 x 221, so a hair under 7:1) and it has the state that file has and
 * Material does not - it swells while it is held, which is the whole of how an
 * iOS 26 slider tells you it has you.
 *
 * The materials are the ones measured off the Liquid Glass file, because this
 * file's own fills could not be read - see the note in the release.
 */
@Composable
fun SkinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    if (skin == Skin.MATERIAL) {
        Slider(value, onValueChange, modifier, valueRange = valueRange)
        return
    }
    var held by remember { mutableStateOf(false) }
    val height by animateDpAsState(
        if (held) SLIDER_HELD else SLIDER_HEIGHT,
        label = "slider height",
    )
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val tokens = skin.tokens()
    val scheme = MaterialTheme.colorScheme
    var width by remember { mutableFloatStateOf(1f) }

    fun report(x: Float) {
        val at = (x / width).coerceIn(0f, 1f)
        onValueChange(valueRange.start + at * span)
    }

    Box(
        modifier
            .height(SLIDER_HELD)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(valueRange) {
                detectDragGestures(
                    onDragStart = { held = true },
                    onDragEnd = { held = false },
                    onDragCancel = { held = false },
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
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // A capsule, not a squircle: the ends of a slider are semicircular,
        // and a superellipse end reads as a flattened rectangle at this size.
        val shape = RoundedCornerShape(percent = 50)
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(shape)
                .background(scheme.onSurface.copy(alpha = 0.10f)),
        ) {
            // The filled part is the same glass as the bars, so the control is
            // made of the app rather than dropped into it.
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(scheme.primary.copy(alpha = 0.70f)),
            )
        }
        // The lit edge goes over both, once, so the fill does not get an edge of
        // its own halfway along the track.
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .glassEdge(tokens, shape),
        )
    }
}

/**
 * A switch wearing the current skin. Only the colours change: the track is the
 * glass, the thumb stays solid, because a control that says on or off has to
 * keep saying it against whatever is behind it.
 */
@Composable
fun SkinSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val skin = LocalSkin.current
    if (skin == Skin.MATERIAL) {
        Switch(checked, onCheckedChange)
        return
    }
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = scheme.primary.copy(alpha = 0.55f),
            uncheckedTrackColor = scheme.surface.copy(alpha = 0.45f),
            uncheckedBorderColor = scheme.onSurface.copy(alpha = 0.28f),
        ),
    )
}

// From the reference file: 1484 x 221 is 6.7:1, so the track is thick enough
// to be the control rather than a line under one.
private val SLIDER_HEIGHT = 26.dp
private val SLIDER_HELD = 34.dp

/**
 * The shapes Material's own components reach for. Overriding these is how the
 * dialogs, menus and cards take the skin without every call site being edited:
 * AlertDialog asks the theme for `extraLarge`, DropdownMenu for `extraSmall`,
 * and so on, so replacing the set replaces all of them at once.
 */
@Composable
fun skinShapes(skin: Skin): Shapes = if (skin == Skin.MATERIAL) {
    Shapes()
} else {
    Shapes(
        extraSmall = SquircleShape(10.dp),
        small = SquircleShape(14.dp),
        medium = SquircleShape(18.dp),
        large = SquircleShape(22.dp),
        extraLarge = SquircleShape(30.dp),
    )
}

/**
 * The same trick for colour. Dialogs and menus paint themselves with the
 * container roles, so making those translucent is what stops a popup from being
 * the one opaque Material slab left in a glass app.
 */
@Composable
fun skinColors(base: ColorScheme, skin: Skin): ColorScheme = if (skin == Skin.MATERIAL) {
    base
} else {
    val alpha = if (skin == Skin.LIQUID_GLASS) 0.72f else 0.80f
    base.copy(
        surfaceContainer = base.surfaceContainer.copy(alpha = alpha),
        surfaceContainerHigh = base.surfaceContainerHigh.copy(alpha = alpha),
        surfaceContainerHighest = base.surfaceContainerHighest.copy(alpha = alpha),
        surfaceContainerLow = base.surfaceContainerLow.copy(alpha = alpha),
    )
}
