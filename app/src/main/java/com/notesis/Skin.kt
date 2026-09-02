package com.notesis

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
            .background(MaterialTheme.colorScheme.surface.copy(alpha = tokens.fillAlpha))
            .glassEdge(tokens, radius),
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
private fun Modifier.glassEdge(tokens: SkinTokens, corner: Dp): Modifier = drawWithContent {
    drawContent()
    val radius = corner.toPx()
    if (tokens.glows.isNotEmpty()) {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
            }
            for ((widthDp, colour) in tokens.glows) {
                val glow = widthDp.toPx()
                paint.strokeWidth = glow
                paint.color = colour.toArgb()
                paint.maskFilter = BlurMaskFilter(glow / 2f, BlurMaskFilter.Blur.NORMAL)
                canvas.nativeCanvas.drawRoundRect(
                    0f, 0f, size.width, size.height, radius, radius, paint,
                )
            }
        }
    }
    val width = tokens.rimWidth.toPx()
    tokens.bevel?.let { bevel ->
        // One line further in than the specular, so the two read as the near and
        // the far face of the same edge rather than as one thick line.
        inset(width * 1.5f) {
            val r = (radius - width * 1.5f).coerceAtLeast(0f)
            drawRoundRect(brush = bevel, cornerRadius = CornerRadius(r, r), style = Stroke(width))
        }
    }
    tokens.rim?.let { rim ->
        inset(width / 2f) {
            val r = (radius - width / 2f).coerceAtLeast(0f)
            drawRoundRect(brush = rim, cornerRadius = CornerRadius(r, r), style = Stroke(width))
        }
    }
}

/** The border a skin would draw, for the few places that want one directly. */
@Composable
fun skinBorder(): BorderStroke? {
    val tokens = LocalSkin.current.tokens()
    val rim = tokens.rim ?: return null
    return BorderStroke(tokens.rimWidth, rim)
}

/**
 * A slider wearing the current skin. Material keeps the platform one; the glass
 * skins swap the thumb for a lens - the same edge recipe as every other bar,
 * just small and round - and let the track go translucent so the page reads
 * through it the way it does through the bar the slider sits in.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    val scheme = MaterialTheme.colorScheme
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            activeTrackColor = scheme.primary.copy(alpha = 0.55f),
            inactiveTrackColor = scheme.onSurface.copy(alpha = 0.12f),
        ),
        thumb = {
            SkinSurface(Modifier.size(SLIDER_THUMB), corner = SLIDER_THUMB / 2) {}
        },
    )
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

private val SLIDER_THUMB = 22.dp
