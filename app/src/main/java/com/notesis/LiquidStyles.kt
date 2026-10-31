package com.notesis

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import org.json.JSONObject

/**
 * Which recipe Liquid glass is drawn with. All three bend the page through
 * Backdrop's lens; they differ in how much, how frosted, how coloured and how
 * lit, each taken from where it comes from:
 *
 * - [NOTESIS] is this app's own glass, tuned in the settings above it.
 * - [KYANT] follows the catalog app of Kyant0/AndroidLiquidGlass (Apache 2.0),
 *   the library the app's glass is built on: its bottom tabs for bars and
 *   buttons, its dialog for panels.
 * - [QWEA] follows QWEA0/Liquid-Glass-Android's LiquidGlassView (MIT): its
 *   README's lens, its view's blur, saturation, tint and rim, and its dialog
 *   builder for panels. That library draws Views with its own shader; here
 *   the same numbers drive Backdrop's lens instead.
 *
 * Saved by [name].
 */
enum class LiquidStyle(val label: String, val blurb: String) {
    NOTESIS("Liquid glass", "A lens that bends the page. The selection glides after your fingertip."),
    KYANT("Liquid glass · Kyant0", "As Kyant0/AndroidLiquidGlass draws it: vivid colour, a deep lens and a lit rim."),
    QWEA("Liquid glass · QWEA0", "As QWEA0/Liquid-Glass-Android draws it: a narrow bevel, light blur and a tint that follows the page."),
    ;

    companion object {
        fun fromName(name: String?): LiquidStyle = entries.firstOrNull { it.name == name } ?: NOTESIS
    }
}

/**
 * Kyant0/AndroidLiquidGlass's glass, as its catalog sets it. The defaults are
 * the catalog's LiquidBottomTabs: vibrancy() (saturation 1.5), blur 8 dp,
 * lens(24 dp, 24 dp), a #FAFAFA surface at 40% (#121212 at 40% in the dark)
 * and Highlight.Default. Panels take its DialogContent instead, which is the
 * same glass with twice the blur and lens and a brighter surface: blur 16 dp
 * (8 in the dark), lens(24 dp, 48 dp, depthEffect), brightness +0.2 and the
 * surface at 60% in the light, Highlight.Plain. Small round buttons take its
 * LiquidButton: blur 2 dp, lens(12 dp, 24 dp).
 */
data class KyantGlass(
    /** blur(), in dp. */
    val blur: Float = 8f,
    /** lens()'s refractionHeight: how far in from the edge the bending reaches, in dp. */
    val refractionHeight: Float = 24f,
    /** lens()'s refractionAmount: how far the page is bent, in dp. */
    val refractionAmount: Float = 24f,
    /** vibrancy()'s saturation. */
    val saturation: Float = 1.5f,
    /** The surface colour's alpha over the bent page. */
    val surface: Float = 0.4f,
    /** The highlight's alpha. */
    val highlight: Float = 1f,
    /** lens()'s chromaticAberration; off in the catalog. */
    val chromaticAberration: Boolean = false,
    /** lens()'s depthEffect on bars and buttons too; the catalog keeps it for panels. */
    val depthEffect: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("blur", blur.toDouble()).put("refractionHeight", refractionHeight.toDouble())
        .put("refractionAmount", refractionAmount.toDouble()).put("saturation", saturation.toDouble())
        .put("surface", surface.toDouble()).put("highlight", highlight.toDouble())
        .put("chromaticAberration", chromaticAberration).put("depthEffect", depthEffect)

    companion object {
        val BLUR_RANGE = 0f..32f
        val HEIGHT_RANGE = 0f..64f
        val AMOUNT_RANGE = 0f..64f
        val SATURATION_RANGE = 1f..2f

        fun fromJson(json: JSONObject?): KyantGlass {
            val d = KyantGlass()
            if (json == null) return d
            fun f(key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
                json.optDouble(key, default.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(range) ?: default
            return KyantGlass(
                blur = f("blur", d.blur, BLUR_RANGE),
                refractionHeight = f("refractionHeight", d.refractionHeight, HEIGHT_RANGE),
                refractionAmount = f("refractionAmount", d.refractionAmount, AMOUNT_RANGE),
                saturation = f("saturation", d.saturation, SATURATION_RANGE),
                surface = f("surface", d.surface, 0f..1f),
                highlight = f("highlight", d.highlight, 0f..1f),
                chromaticAberration = json.optBoolean("chromaticAberration", d.chromaticAberration),
                depthEffect = json.optBoolean("depthEffect", d.depthEffect),
            )
        }
    }
}

/**
 * QWEA0/Liquid-Glass-Android's two materials, as its GlassMaterial has them:
 * how much of the blur is kept, a dimming layer, the tint when it does not
 * follow the page, and how bright the rim is.
 */
enum class QweaMaterial(val label: String, val blurScale: Float, val dim: Float, val baseTint: Float, val specBoost: Float) {
    /** Readability first: full blur, a tint that follows the page. */
    REGULAR("Regular", 1f, 0f, 0x24 / 255f, 1f),
    /** For glass over pictures: a third of the blur, a fixed 16% dimming layer. */
    CLEAR("Clear", 0.35f, 0.16f, 0x14 / 255f, 1.2f),
    ;

    companion object {
        fun fromName(name: String?): QweaMaterial = entries.firstOrNull { it.name == name } ?: REGULAR
    }
}

/**
 * QWEA0/Liquid-Glass-Android's glass. The lens is its README's quick start -
 * bevelWidth 24 dp, refractionHeight 12 dp, dispersionStrength 0.12, regular
 * material, adaptiveTint on; the rest is LiquidGlassView's own defaults -
 * blurAmount 0.0625 (a blur of 4 + 32 × amount px), saturation 140%, the edge
 * highlight at full, no shadow. Panels take its dialog builder's blurAmount,
 * 0.6, which it gives dialogs so their text has something under it.
 */
data class QweaGlass(
    val material: QweaMaterial = QweaMaterial.REGULAR,
    /** blurAmount on bars and buttons: the blur is 4 + 32 × this, in px. */
    val blurAmount: Float = 0.0625f,
    /** blurAmount on panels, menus and dialogs. */
    val panelBlurAmount: Float = 0.6f,
    /** saturation, as a factor (140% is 1.4). */
    val saturation: Float = 1.4f,
    /** bevelWidth: the band round the edge where the light is bent, in dp. */
    val bevelWidth: Float = 24f,
    /** refractionHeight: how far the page is pulled in at the very edge, in dp. */
    val refractionHeight: Float = 12f,
    /** dispersionStrength, 0 to 1; any at all splits the colours at the edge. */
    val dispersion: Float = 0.12f,
    /** edgeHighlightOpacity, 0 to 1. */
    val highlight: Float = 1f,
    /** adaptiveTint: dark over a light page, light over a dark one. */
    val adaptiveTint: Boolean = true,
    /** enableShadow; off in LiquidGlassView. */
    val shadow: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("material", material.name).put("blurAmount", blurAmount.toDouble())
        .put("panelBlurAmount", panelBlurAmount.toDouble()).put("saturation", saturation.toDouble())
        .put("bevelWidth", bevelWidth.toDouble()).put("refractionHeight", refractionHeight.toDouble())
        .put("dispersion", dispersion.toDouble()).put("highlight", highlight.toDouble())
        .put("adaptiveTint", adaptiveTint).put("shadow", shadow)

    companion object {
        val SATURATION_RANGE = 1f..2f
        val BEVEL_RANGE = 2f..100f
        val REFRACTION_RANGE = 0f..100f

        fun fromJson(json: JSONObject?): QweaGlass {
            val d = QweaGlass()
            if (json == null) return d
            fun f(key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
                json.optDouble(key, default.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(range) ?: default
            return QweaGlass(
                material = QweaMaterial.fromName(json.optString("material", d.material.name)),
                blurAmount = f("blurAmount", d.blurAmount, 0f..1f),
                panelBlurAmount = f("panelBlurAmount", d.panelBlurAmount, 0f..1f),
                saturation = f("saturation", d.saturation, SATURATION_RANGE),
                bevelWidth = f("bevelWidth", d.bevelWidth, BEVEL_RANGE),
                refractionHeight = f("refractionHeight", d.refractionHeight, REFRACTION_RANGE),
                dispersion = f("dispersion", d.dispersion, 0f..1f),
                highlight = f("highlight", d.highlight, 0f..1f),
                adaptiveTint = json.optBoolean("adaptiveTint", d.adaptiveTint),
                shadow = json.optBoolean("shadow", d.shadow),
            )
        }
    }
}

/** Sets [SkinSettings.liquidStyle] and keeps it; provided where the settings live. */
val LocalLiquidStyleChange = staticCompositionLocalOf<(LiquidStyle) -> Unit> { {} }

/**
 * One of the looks a picker offers: a skin, and for Liquid glass which recipe.
 * Five in all - Material, Glassmorphism and Liquid glass's three.
 */
data class Look(val skin: Skin, val style: LiquidStyle = LiquidStyle.NOTESIS) {
    val label: String get() = if (skin == Skin.SPOTIGLASS) style.label else skin.label
    val blurb: String get() = if (skin == Skin.SPOTIGLASS) style.blurb else skin.blurb

    companion object {
        val all: List<Look> = Skin.entries.flatMap { skin ->
            if (skin == Skin.SPOTIGLASS) LiquidStyle.entries.map { Look(skin, it) } else listOf(Look(skin))
        }
    }
}

/** Below this, a pane is a button and takes the button's recipe, where there is one. */
private val BUTTON_SIZE = 64.dp

/**
 * The glass of [style] (not [LiquidStyle.NOTESIS]) on [shape], over
 * [backdrop]. [panel] is a surface with text on it - a menu, a panel, a
 * dialog - rather than a bar; anything under [BUTTON_SIZE] is a button
 * whatever it is called. [shadows] is the app's switch; [rim] whether the
 * edge is lit at all; [amount] fades the whole effect in, as the lens does
 * while a pane morphs. Only called where the lens may run (API 33 and up,
 * effects allowed).
 */
internal fun Modifier.styledLiquidGlass(
    style: LiquidStyle,
    settings: SkinSettings,
    shape: Shape,
    backdrop: Backdrop,
    panel: Boolean,
    dark: Boolean,
    shadows: Boolean,
    rim: Boolean = true,
    exportedBackdrop: LayerBackdrop? = null,
    amount: Float = 1f,
): Modifier = when (style) {
    LiquidStyle.KYANT -> kyantGlass(settings.kyant, shape, backdrop, panel, dark, shadows, rim, exportedBackdrop, amount)
    LiquidStyle.QWEA -> qweaGlass(settings.qwea, shape, backdrop, panel, dark, shadows, rim, exportedBackdrop, amount)
    LiquidStyle.NOTESIS -> this
}

private fun Modifier.kyantGlass(
    k: KyantGlass,
    shape: Shape,
    backdrop: Backdrop,
    panel: Boolean,
    dark: Boolean,
    shadows: Boolean,
    rim: Boolean,
    exportedBackdrop: LayerBackdrop?,
    amount: Float,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        val button = size.minDimension < BUTTON_SIZE.toPx()
        val asPanel = panel && !button
        colorControls(brightness = if (asPanel && !dark) 0.2f * amount else 0f, saturation = 1f + (k.saturation - 1f) * amount)
        // Dialog 16 dp (8 in the dark), tabs 8, button 2: the setting is the tabs'.
        val blurDp = when {
            asPanel -> if (dark) k.blur else k.blur * 2f
            button -> k.blur / 4f
            else -> k.blur
        }
        blur(blurDp.dp.toPx() * amount)
        // Dialog lens(24, 48), tabs lens(24, 24), button lens(12, 24).
        val height = if (button) k.refractionHeight / 2f else k.refractionHeight
        val bend = if (asPanel) k.refractionAmount * 2f else k.refractionAmount
        lens(
            refractionHeight = (height.dp.toPx() * amount).coerceAtMost(size.minDimension / 2f),
            refractionAmount = bend.dp.toPx() * amount,
            depthEffect = asPanel || k.depthEffect,
            chromaticAberration = k.chromaticAberration,
        )
    },
    highlight = if (rim && k.highlight > 0.001f) {
        { (if (panel) Highlight.Plain else Highlight.Default).copy(alpha = k.highlight * amount) }
    } else null,
    shadow = if (shadows) { { Shadow.Default.copy(alpha = amount) } } else null,
    exportedBackdrop = exportedBackdrop,
    onDrawSurface = {
        // DialogContent: #FAFAFA at 60% in the light, #121212 at 40% in the dark;
        // the tabs and buttons at 40% in both. The setting moves them together.
        val asPanel = panel && size.minDimension >= BUTTON_SIZE.toPx()
        drawRect(if (dark) Color(0xFF121212).copy(alpha = k.surface)
            else Color(0xFFFAFAFA).copy(alpha = (k.surface + if (asPanel) 0.2f else 0f).coerceIn(0f, 1f)))
    },
)

private fun Modifier.qweaGlass(
    q: QweaGlass,
    shape: Shape,
    backdrop: Backdrop,
    panel: Boolean,
    dark: Boolean,
    shadows: Boolean,
    rim: Boolean,
    exportedBackdrop: LayerBackdrop?,
    amount: Float,
): Modifier {
    val m = q.material
    // The adaptive tint as LiquidGlassView works it out from the page's
    // luminance e: grey (1 - e) at 14% + 8% × e. The page is paper - white in
    // the light, near black in the dark - so e is taken as 1 or 0.07.
    val tint = if (q.adaptiveTint && m == QweaMaterial.REGULAR) {
        val e = if (dark) 0.07f else 1f
        val channel = 1f - e
        Color(channel, channel, channel, 0.14f + 0.08f * e)
    } else Color.White.copy(alpha = m.baseTint)
    val dimLayer = Color.Black.copy(alpha = m.dim)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1f + (q.saturation - 1f) * amount)
            // calculatedBlurRadius = 4 + blurAmount × 32, in px, times the material's share.
            val asPanel = panel && size.minDimension >= BUTTON_SIZE.toPx()
            blur((4f + (if (asPanel) q.panelBlurAmount else q.blurAmount) * 32f) * m.blurScale * amount)
            // adaptiveLensScale, on by default: the bevel at most 0.3 of the
            // short side, the bending at most 0.7 of it, less again under 110 dp.
            val short = size.minDimension
            val bevel = minOf(q.bevelWidth.dp.toPx(), short * 0.3f).coerceAtLeast(2f)
            val refract = minOf(q.refractionHeight.dp.toPx(), 0.7f * short * minOf(1f, short / 110.dp.toPx()))
            lens(
                refractionHeight = bevel * amount,
                refractionAmount = refract * amount,
                chromaticAberration = q.dispersion > 0.001f,
            )
        },
        // The rim is lit from the top left, 1.5 px wide, as bright as the material allows.
        highlight = if (rim && q.highlight > 0.001f) {
            { Highlight(width = 1.5f.toDpPx(), alpha = (q.highlight * m.specBoost * amount).coerceIn(0f, 1f),
                style = HighlightStyle.Default) }
        } else null,
        // LiquidGlassView's shadow: 40 px soft, 12 px down, black at 25%.
        shadow = if (shadows && q.shadow) {
            { Shadow(radius = 40f.toDpPx(), offset = DpOffset(0.dp, 12f.toDpPx()), color = Color.Black.copy(alpha = 0.25f), alpha = amount) }
        } else null,
        exportedBackdrop = exportedBackdrop,
        onDrawSurface = {
            drawRect(tint)
            if (m.dim > 0f) drawRect(dimLayer)
        },
    )
}

/** [this] many px as dp at the screen's density, for numbers a library gave in px. */
private fun Float.toDpPx(): Dp = (this / android.content.res.Resources.getSystem().displayMetrics.density).dp
