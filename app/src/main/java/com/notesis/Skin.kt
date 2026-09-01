package com.notesis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the app's chrome is dressed. The note itself never changes - paper is
 * paper - so this only reaches the bars, panels and dialogs floating over it.
 */
enum class Skin(val label: String, val blurb: String) {
    MATERIAL("머티리얼", "안드로이드 기본. 불투명하고 또렷합니다."),
    LIQUID_GLASS("리퀴드 글래스", "얇은 렌즈. 가장자리가 빛을 물고 있습니다."),
    GLASSMORPHISM("글래스모피즘", "서리 낀 유리. 고르게 반투명합니다."),
}

/**
 * What a skin actually changes. Kept as plain numbers rather than a pile of
 * composables, so a new skin is a row of values and not a new widget set.
 */
class SkinTokens(
    val corner: Dp,
    /** How much of the surface colour survives; the rest is what is behind. */
    val fillAlpha: Float,
    val rim: Brush?,
    val rimWidth: Dp,
    val shadow: Dp,
    val tonalElevation: Dp,
    /** A second, brighter fill along the top, which is what reads as a lens. */
    val sheen: Brush?,
)

val LocalSkin = compositionLocalOf { Skin.MATERIAL }

@Composable
fun ProvideSkin(skin: Skin, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSkin provides skin, content = content)
}

/**
 * Liquid glass is not "translucent with a border". What makes it read as a lens
 * is that the edge is brighter than the middle and unevenly so - light entering
 * the top and leaving the bottom - while the body stays almost clear. The rim
 * gradient below is that, and it is the whole trick.
 */
@Composable
fun Skin.tokens(): SkinTokens = when (this) {
    Skin.MATERIAL -> SkinTokens(
        corner = 20.dp,
        fillAlpha = 1f,
        rim = null,
        rimWidth = 0.dp,
        shadow = 4.dp,
        tonalElevation = 3.dp,
        sheen = null,
    )

    Skin.LIQUID_GLASS -> SkinTokens(
        corner = 28.dp,
        fillAlpha = 0.42f,
        rim = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.95f),
            0.28f to Color.White.copy(alpha = 0.20f),
            0.72f to Color.White.copy(alpha = 0.10f),
            1f to Color.White.copy(alpha = 0.75f),
        ),
        rimWidth = 1.4.dp,
        shadow = 18.dp,
        tonalElevation = 0.dp,
        sheen = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.55f),
            0.45f to Color.White.copy(alpha = 0.06f),
            1f to Color.Transparent,
        ),
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
        shadow = 10.dp,
        tonalElevation = 0.dp,
        sheen = null,
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
    val fill = MaterialTheme.colorScheme.surface.copy(alpha = tokens.fillAlpha)
    Box(
        modifier
            .shadow(tokens.shadow, shape, clip = false)
            .clip(shape)
            .background(fill)
            .then(if (tokens.sheen != null) Modifier.background(tokens.sheen) else Modifier)
            .glassRim(tokens, radius),
    ) {
        content()
    }
}

/**
 * The lit edge, drawn inside the shape rather than as a border so its corners
 * stay on the same curve as the fill. A border modifier sits outside the fill
 * and gives away the trick with a double outline.
 */
private fun Modifier.glassRim(tokens: SkinTokens, corner: Dp): Modifier {
    val rim = tokens.rim ?: return this
    return drawWithContent {
        drawContent()
        val width = tokens.rimWidth.toPx()
        // Stroking centres the line on the edge, so half of it would fall
        // outside; insetting by half keeps all of it in, which reads as the
        // thickness of the glass rather than as a frame drawn around it.
        inset(width / 2f) {
            val r = (corner.toPx() - width / 2f).coerceAtLeast(0f)
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
