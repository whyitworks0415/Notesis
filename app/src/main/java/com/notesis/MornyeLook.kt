package com.notesis

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject

/**
 * Mornye's accents, as its theme picks them: iOS system colours, each the
 * shade that still reads as text on white in the light and the plain dark
 * system colour in the dark. Red is Mornye's own (204, 46, 51); green and
 * blue are its readable light shades. [APP] follows the app's own accent.
 */
enum class MornyeAccent(val label: String, val light: Int, val dark: Int) {
    RED("Red", 0xFFCC2E33.toInt(), 0xFFFF453A.toInt()),
    ORANGE("Orange", 0xFFC93400.toInt(), 0xFFFF9F0A.toInt()),
    GREEN("Green", 0xFF24863D.toInt(), 0xFF30D158.toInt()),
    TEAL("Teal", 0xFF008299.toInt(), 0xFF40C8E0.toInt()),
    BLUE("Blue", 0xFF0071E3.toInt(), 0xFF0A84FF.toInt()),
    PURPLE("Purple", 0xFF8944AB.toInt(), 0xFFBF5AF2.toInt()),
    PINK("Pink", 0xFFD30F45.toInt(), 0xFFFF375F.toInt()),
    APP("App accent", 0, 0),
    ;

    /** The colour in the light or dark, or [appAccent] for [APP]. */
    fun argb(dark: Boolean, appAccent: Int): Int = when (this) {
        APP -> appAccent
        else -> if (dark) this.dark else light
    }

    companion object {
        fun fromName(name: String?): MornyeAccent = entries.firstOrNull { it.name == name } ?: RED
    }
}

/**
 * Mornye's glass, from its MornyeGlass and MornyeGlassPanel: a white body
 * (a grey one over black in the dark) whose strength the [clarity] sets -
 * 0.75 by default, as Mornye starts - a blur that clears as the clarity
 * rises, a gentle lens, a hairline contact edge with a lit rim inside it
 * ([outlines]), and a small soft shadow. Panels and menus take its "overlay"
 * tint, stronger, so their words read; bars take the clearer one.
 *
 * [corner] is its panels' 32. [accent] is the colour its theme is built from.
 */
data class MornyeGlass(
    val clarity: Float = 0.75f,
    val outlines: Boolean = true,
    val accent: MornyeAccent = MornyeAccent.RED,
    val corner: Float = 32f,
    /** Mornye's continuous corners (see [MornyeSquircleShape]) rather than round ones. */
    val smoothCorners: Boolean = true,
    val motion: ProjectMotion = ProjectMotion(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("clarity", clarity.toDouble()).put("outlines", outlines).put("accent", accent.name)
        .put("corner", corner.toDouble()).put("smoothCorners", smoothCorners).put("motion", motion.toJson())

    companion object {
        fun fromJson(json: JSONObject?): MornyeGlass {
            val d = MornyeGlass()
            if (json == null) return d
            return MornyeGlass(
                clarity = json.optDouble("clarity", d.clarity.toDouble()).toFloat()
                    .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: d.clarity,
                outlines = json.optBoolean("outlines", d.outlines),
                accent = MornyeAccent.fromName(json.optString("accent", d.accent.name)),
                corner = json.optDouble("corner", d.corner.toDouble()).toFloat()
                    .takeIf { it.isFinite() }?.coerceIn(SkinSettings.CORNER_RANGE) ?: d.corner,
                smoothCorners = json.optBoolean("smoothCorners", d.smoothCorners),
                motion = ProjectMotion.fromJson(json.optJSONObject("motion")),
            )
        }
    }
}

/** Whether the recipe in use draws Mornye's continuous corners: Mornye's own, and iOS with it. */
internal val SkinSettings.smoothCorners: Boolean
    get() = when (liquidStyle) {
        LiquidStyle.MORNYE -> mornye.smoothCorners
        LiquidStyle.IOS, LiquidStyle.IOS_LITE -> ios.smoothCorners
        LiquidStyle.NOTESIS -> false
    }

/** iOS grouped colours, as Mornye's theme sets them. */
private val GROUPED_LIGHT = Color(0xFFF2F2F7)
private val GROUPED_DARK = Color(0xFF1C1C1E)
private val ELEVATED_DARK = Color(0xFF2C2C2E)

/**
 * How far the page behind is blurred, as a Gaussian sigma in dp, for a
 * [clarity]: Mornye's four shared filters cut to the three its lens uses.
 */
internal fun mornyeBlurSigma(clarity: Float): Float = when {
    clarity >= 0.9f -> 2f
    clarity >= 0.6f -> 6f
    else -> 16f
}

/**
 * The radius Android's blur takes for a Gaussian [sigma]: it turns a radius r
 * into the sigma 0.57735 r + 0.5, so this is that turned round.
 */
internal fun blurRadiusForSigma(sigma: Float): Float = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0f)

/**
 * The body colour of Mornye's glass. [panel] is its overlay tint, 0.78
 * rather than 0.60; a bar's body is drawn towards the clear fill as the
 * clarity rises, a panel's stays the tint so its words read.
 */
internal fun mornyeFill(clarity: Float, panel: Boolean, dark: Boolean): Color {
    val c = clarity.coerceIn(0f, 1f)
    val opacity = if (panel) 0.78f else 0.60f
    val high = if (dark) ELEVATED_DARK else GROUPED_LIGHT
    var base = if (dark) ELEVATED_DARK.copy(alpha = opacity * 0.82f)
        else Color.White.copy(alpha = (opacity * 0.88f).coerceIn(0.5f, 1f))
    // Over black in the dark, so bright pages under it stay bounded.
    if (dark) base = base.compositeOver(Color.Black.copy(alpha = 0.35f))
    // The upper end of the clarity reveals more of the page.
    val strength = (1f - c) * (1f - c)
    val tint = high.copy(alpha = strength).compositeOver(base)
    if (panel) return tint
    val clear = if (dark) Color(0xFF999999).copy(alpha = 0.17f) else Color.White.copy(alpha = 0.46f)
    return lerp(tint, clear, ((c - 0.2f) / 0.8f).coerceIn(0f, 1f))
}

/**
 * Mornye's glass over [backdrop]: the blur and lens, the body, the rim and
 * the shadow, all faded in by [amount]. Below a clarity of 0.2 the body is
 * all there is, as Mornye stops sampling the page there. Only called where
 * the lens may run.
 */
internal fun Modifier.mornyeGlass(
    m: MornyeGlass,
    shape: Shape,
    backdrop: Backdrop,
    panel: Boolean,
    dark: Boolean,
    shadows: Boolean,
    rim: Boolean,
    exportedBackdrop: LayerBackdrop?,
    amount: Float,
): Modifier {
    // The same outline for the glass, its lens, its shadow and its rim, so
    // nothing is cut off at a corner.
    val shape = if (m.smoothCorners) mornyeSquircle(shape) else shape
    val fill = mornyeFill(m.clarity, panel, dark)
    val body = fill.copy(alpha = fill.alpha * amount)
    val samples = m.clarity > 0.2f
    val sigma = mornyeBlurSigma(m.clarity)
    // Black at 8% (14% in the dark), 8 soft, 2 down (see [paneShadow]).
    val base = (if (shadows) paneShadow(shape, 8.dp, 2.dp, Color.Black.copy(alpha = if (dark) 0.14f else 0.08f), amount) else this)
        .drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (samples) {
                blur(blurRadiusForSigma(sigma).dp.toPx() * amount)
                // Its lens: refraction width 20 at depth 0.30, which its
                // formula turns into an edge that travels about 24 points.
                lens(
                    refractionHeight = (20.dp.toPx() * amount).coerceAtMost(size.minDimension / 2f),
                    refractionAmount = 24.dp.toPx() * amount,
                    depthEffect = false,
                    chromaticAberration = false,
                )
            }
        },
        highlight = null,
        shadow = null,
        exportedBackdrop = exportedBackdrop,
        onDrawSurface = { drawRect(body) },
    )
    return if (rim && m.outlines) base.mornyeRim(shape, emphasized = panel, dark = dark, strength = amount) else base
}

/**
 * Mornye's rim: a half-point contact edge, dark enough to find the glass by,
 * and inside it a fine line lit at the top and bottom and nearly gone down
 * the sides. Panels take its emphasised rim, which is brighter and a point wide.
 */
internal fun Modifier.mornyeRim(shape: Shape, emphasized: Boolean, dark: Boolean, strength: Float = 1f): Modifier = drawWithCache {
    val contactWidth = 0.5.dp.toPx()
    val contactInset = 0.25.dp.toPx()
    val litWidth = (if (emphasized) 1f else 0.6f).dp.toPx()
    val litInset = 0.8.dp.toPx()
    fun outlineAt(inset: Float) = shape.createOutline(
        Size((size.width - inset * 2f).coerceAtLeast(0f), (size.height - inset * 2f).coerceAtLeast(0f)),
        layoutDirection, this,
    )
    val contact = outlineAt(contactInset)
    val lit = outlineAt(litInset)
    val contactColor = Color.Black.copy(alpha = (if (dark) 0.5f else 0.16f) * strength)
    fun white(alpha: Int) = Color.White.copy(alpha = alpha / 255f * strength)
    val litBrush = Brush.verticalGradient(
        0f to white(if (emphasized) 0x88 else 0x40),
        0.14f to white(if (emphasized) 0x26 else 0x0d),
        0.5f to Color.Black.copy(alpha = 0x0a / 255f * strength),
        0.86f to white(if (emphasized) 0x20 else 0x0d),
        1f to white(if (emphasized) 0x55 else 0x26),
        startY = 0f, endY = size.height,
    )
    onDrawWithContent {
        drawContent()
        if (strength <= 0f) return@onDrawWithContent
        translate(contactInset, contactInset) { drawOutline(contact, contactColor, style = Stroke(contactWidth)) }
        translate(litInset, litInset) { drawOutline(lit, litBrush, style = Stroke(litWidth)) }
    }
}

/**
 * The theme under Mornye: its accent as the primary, and iOS's grouped
 * colours for the surfaces - white and #F2F2F7 in the light, black, #1C1C1E
 * and #2C2C2E in the dark - over [base], which carries the rest.
 */
internal fun mornyeScheme(base: ColorScheme, accentArgb: Int, dark: Boolean): ColorScheme {
    val accent = Color(accentArgb).copy(alpha = 1f)
    val foreground = if (dark) Color.White else Color.Black
    val surface = if (dark) Color.Black else Color.White
    val grouped = if (dark) GROUPED_DARK else GROUPED_LIGHT
    val secondary = if (dark) Color(0xFF98989F) else Color(0xFF6C6C70)
    return base.copy(
        primary = accent,
        onPrimary = if (accent.luminance() < 0.179f) Color.White else Color.Black,
        primaryContainer = grouped,
        onPrimaryContainer = accent,
        secondary = accent,
        onSecondary = Color.White,
        secondaryContainer = grouped,
        onSecondaryContainer = foreground,
        background = grouped,
        onBackground = foreground,
        surface = surface,
        onSurface = foreground,
        surfaceContainerLowest = surface,
        surfaceContainerLow = grouped,
        surfaceContainer = grouped,
        surfaceContainerHigh = if (dark) ELEVATED_DARK else grouped,
        surfaceContainerHighest = if (dark) Color(0xFF3A3A3C) else Color(0xFFE5E5EA),
        onSurfaceVariant = secondary,
        outline = secondary,
        outlineVariant = if (dark) Color(0xFF38383A) else Color(0xFFC6C6C8),
        surfaceTint = Color.Transparent,
    )
}


/**
 * The colours a look is drawn in, before [skinColors] inks and glazes them.
 * Mornye builds its own from its accent and iOS's greys; Material takes the
 * wallpaper's where it may (Android 12 and up, contrast not turned up) and
 * goes black under AMOLED in the dark; everything else is made from the accent.
 */
internal fun lookScheme(context: Context?, skin: Skin, look: SkinSettings): ColorScheme {
    val dark = look.themeMode == AppThemeMode.DARK
    // iOS takes Mornye's colours too - iOS's grouped greys and an accent of
    // its own, blue unless another is chosen.
    if (skin == Skin.LIQUID_GLASS && look.liquidStyle != LiquidStyle.NOTESIS) {
        val chosen = if (look.liquidStyle.looksIos) look.ios.accent else look.mornye.accent
        val accent = chosen.argb(dark, look.accent)
        val grouped = mornyeScheme(schemeFrom(accent, look.highContrast, dark), accent, dark)
        return if (look.liquidStyle.looksIos) iosChromeScheme(grouped, dark) else grouped
    }
    val wallpaper = skin == Skin.MATERIAL && look.dynamicColor && !look.highContrast &&
        context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var scheme = if (wallpaper) {
        if (dark) dynamicDarkColorScheme(context!!) else dynamicLightColorScheme(context!!)
    } else {
        schemeFrom(look.accent, look.highContrast, dark)
    }
    if (skin == Skin.MATERIAL) {
        scheme = materialStudioScheme(scheme, dark)
        if (look.highContrast) scheme = scheme.copy(
            onSurface = if (dark) Color.White else Color.Black,
            onBackground = if (dark) Color.White else Color.Black,
            onSurfaceVariant = if (dark) Color.White else Color.Black,
        )
        if (look.amoled && dark) scheme = amoledScheme(scheme)
    }
    return scheme
}

/**
 * [shape] with Mornye's continuous corners, where it is a rounded rectangle;
 * anything else - a circle drawn as a path, a custom outline - as it is.
 */
internal fun mornyeSquircle(shape: Shape): Shape =
    if (shape is CornerBasedShape && shape !is MornyeSquircleShape) MornyeSquircleShape(shape.topStart) else shape

/**
 * Mornye's squircle, the outline its glass and lens share: each corner an
 * L^n superellipse that reaches 1.528 times the radius along the edges and
 * passes through the same 45° point a circle of that radius would, so the
 * curve leaves the edge with no step in its curvature. As liquid_glass_easy
 * 5.1.0 (MIT) draws it with full smoothing.
 *
 * A [CornerBasedShape], so the lens, which reads the corners of one, bends
 * along the corner rather than treating the pane as square. One radius for
 * all four corners, [corner]'s; a capsule's 50% stays a capsule.
 */
internal class MornyeSquircleShape(corner: CornerSize) : CornerBasedShape(corner, corner, corner, corner) {
    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        // One draw of a pane asks for its outline several times - its clip,
        // its backdrop, its rim, its shadow - and every call worked out up to
        // 320 points with a power each. The last one is kept for its size.
        val cached = last
        if (cached != null && lastW == size.width && lastH == size.height && lastR == topStart) return cached
        return buildOutline(size, topStart).also { last = it; lastW = size.width; lastH = size.height; lastR = topStart }
    }

    private var last: Outline? = null
    private var lastW = Float.NaN
    private var lastH = Float.NaN
    private var lastR = Float.NaN

    private fun buildOutline(size: Size, topStart: Float): Outline {
        val points = squirclePoints(size.width, size.height, topStart)
        if (points.size < 16) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        val path = Path()
        path.moveTo(points[0], points[1])
        var i = 2
        while (i < points.size) {
            path.lineTo(points[i], points[i + 1])
            i += 2
        }
        path.close()
        return Outline.Generic(path)
    }

    override fun copy(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize): CornerBasedShape =
        MornyeSquircleShape(topStart)

    override fun equals(other: Any?): Boolean = other is MornyeSquircleShape && other.topStart == topStart
    override fun hashCode(): Int = topStart.hashCode() * 31 + 7
}

/** How far a corner reaches along its edges for radius [r]: 1.528 r, held to half the short side. */
internal fun squircleReach(r: Float, width: Float, height: Float): Float =
    minOf(r * (1f + 0.528f), minOf(width, height) / 2f)

/** The superellipse's exponent for radius [r] reaching [zone]: it passes through the circle's 45° point. */
internal fun squircleExponent(r: Float, zone: Float): Float {
    val base = (1.0 - 0.29289322 * (r / zone)).coerceIn(0.5, 0.999999)
    return (-1.0 / (ln(base) / ln(2.0))).toFloat()
}

/**
 * The outline of a [width] by [height] squircle of radius [r], as x, y pairs
 * round it from the top-left corner, clockwise. Each corner takes as many
 * points as keep it within a twentieth of a pixel of the true curve.
 * Below half a pixel of radius it is the rectangle's four corners.
 */
internal fun squirclePoints(width: Float, height: Float, r: Float): FloatArray {
    val maxCorner = minOf(width, height) / 2f
    val rr = minOf(r, maxCorner)
    if (!(rr >= 0.5f) || !(width > 0f) || !(height > 0f)) {
        return floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
    }
    val zone = squircleReach(rr, width, height)
    val inv = 2.0 / squircleExponent(rr, zone)
    val steps = squircleSegments(zone) * 2
    val out = FloatArray(4 * (steps + 1) * 2)
    var k = 0
    fun corner(cx: Float, cy: Float, sx: Float, sy: Float, reversed: Boolean) {
        for (j in 0..steps) {
            val i = if (reversed) steps - j else j
            val t = (PI / 2) * i / steps
            val ox = zone * cos(t).coerceIn(0.0, 1.0).pow(inv).toFloat()
            val oy = zone * sin(t).coerceIn(0.0, 1.0).pow(inv).toFloat()
            out[k++] = cx + sx * ox
            out[k++] = cy + sy * oy
        }
    }
    corner(zone, zone, -1f, -1f, reversed = false)
    corner(width - zone, zone, 1f, -1f, reversed = true)
    corner(width - zone, height - zone, 1f, 1f, reversed = false)
    corner(zone, height - zone, -1f, 1f, reversed = true)
    return out
}

/**
 * Straight segments for a corner of [reach] pixels: the fewest that keep the
 * chords within 0.05 px of the curve (their sag is about 0.103 reach / n²),
 * between 3 and 40.
 */
internal fun squircleSegments(reach: Float): Int {
    if (!reach.isFinite() || reach <= 0f) return 3
    return kotlin.math.ceil(sqrt(0.103 * reach / 0.05)).toInt().coerceIn(3, 40)
}
