package com.notesis

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop as KyantBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens

/** 현재 화면 배경을 기록하는 Kyant Backdrop 레이어입니다. */
val LocalLiquidGlassBackdrop = compositionLocalOf<KyantBackdrop?> { null }

/** 시스템의 투명도/모션 허용 여부를 화면 전체에서 한 번만 관찰해 공유합니다. */
internal val LocalLiquidGlassEffectsAllowed = compositionLocalOf<Boolean?> { null }

/** 공식 Backdrop의 좌표 의존 레이어를 한 번만 생성합니다. */
@Composable
fun rememberLiquidGlassBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/** 컨트롤이 굴절시킬 배경을 기록합니다. 컨트롤 자체에는 붙이지 않습니다. */
fun Modifier.captureLiquidGlassBackdrop(
    backdrop: LayerBackdrop,
    paused: (() -> Boolean)? = null,
    /** What to record instead of the content, which is then drawn as usual. */
    source: (DrawScope.() -> Unit)? = null,
): Modifier {
    if (paused == null && source == null) return layerBackdrop(backdrop)
    // The page's screen origin is supplied by SpotiPopupBackdrop. Avoid a
    // second page recording and full-screen texture upload during input.
    return this.drawWithContent {
        drawContent()
        if (paused?.invoke() != true) {
            backdrop.graphicsLayer.record { source?.invoke(this) ?: this@drawWithContent.drawContent() }
        }
    }
}

/** Backdrop을 하위 Liquid Glass 컨트롤에 전달합니다. */
@Composable
fun ProvideLiquidGlassBackdrop(
    backdrop: KyantBackdrop?,
    content: @Composable () -> Unit,
) {
    val inheritedEffectsAllowed = LocalLiquidGlassEffectsAllowed.current
    val effectsAllowed = inheritedEffectsAllowed ?: rememberSystemLiquidGlassEffectsAllowed()
    CompositionLocalProvider(
        LocalLiquidGlassBackdrop provides backdrop,
        LocalLiquidGlassEffectsAllowed provides effectsAllowed,
        content = content,
    )
}

/**
 * 공통 Liquid Glass 표면입니다.
 *
 * API 33 이상에서는 Backdrop 1.0.6의 AGSL 렌즈를 사용합니다. API 32 이하,
 * 애니메이션 사용 안 함, 절전 모드, 앱의 고대비 모드에서는 런타임 셰이더를
 * 만들지 않고 반투명 표면과 약한 그림자로 안전하게 폴백합니다.
 */
@Composable
fun Modifier.liquidGlass(
    intensity: Float = 1f,
    shape: Shape = RoundedCornerShape(LiquidGlassTokens.cornerRadius),
    enabled: Boolean = true,
    settings: SkinSettings = LocalSkinSettings.current,
    surfaceColor: Color = Color(settings.tint),
    shadowElevation: Dp = LiquidGlassTokens.fallbackShadow,
    drawBorder: Boolean = true,
    useSpotiStyle: Boolean = true,
    surfaceOpacity: Float? = null,
    /** A pane with text on it rather than a bar or a button; the other recipes in [LiquidStyle] tell them apart. */
    panel: Boolean = false,
): Modifier {
    val skin = LocalSkin.current
    val spoti = useSpotiStyle && skin == Skin.SPOTIGLASS
    if (spoti) {
        val circle = shape == androidx.compose.foundation.shape.CircleShape
        val square = shape == RoundedCornerShape(0.dp)
        // A round corner, as the toolbar and every popup draw theirs: one
        // shape family across the skins, and an outline Backdrop can take.
        val spotiShape = if (circle || square) shape else RoundedCornerShape(settings.glassCorner.dp)
        // No lens over a resting surface. The lens is a shader over the
        // element's whole drawing, so it ran every icon, line of text and
        // control on a panel through refraction and resampling, and they came
        // out soft and stair-stepped at their edges. The blur and the rim are
        // what make the glass; the bar and the controls still bend the light
        // while they move, where nothing small has to be read.
        return this.spotiGlassSurface(spotiShape, settings, elevation = shadowElevation, rim = drawBorder)
    }
    val amount = intensity.coerceIn(0f, 1f)
    // Every layer of the pane - shadow, glass, edge - takes the same outline,
    // one the lens can bend along.
    val glassShape = lensSafeShape(shape)
    val backdrop = LocalLiquidGlassBackdrop.current
    val effectsAllowed = rememberLiquidGlassEffectsAllowed() && !settings.highContrast
    val density = LocalDensity.current
    val blurPx = with(density) { settings.blur.dp.toPx() } * amount
    val depthPx = with(density) { settings.depth.dp.toPx() } * amount
    val refractionPx = with(density) { settings.refraction.dp.toPx() } * amount
    val body = surfaceColor
    val tint = body.copy(
        alpha = if (settings.highContrast) {
            maxOf(body.alpha, 0.94f)
        } else if (surfaceOpacity != null) {
            surfaceOpacity.coerceIn(0f, 1f)
        } else if (body.alpha <= 0f) {
            // 완전 투명 렌즈를 요청한 손잡이/버튼에 최소 흰색 알파를 강제로
            // 넣으면 전환 중 중앙에 흰 점이 생깁니다. 0은 그대로 보존합니다.
            0f
        } else {
            (body.alpha * (0.72f + amount * 0.28f)).coerceIn(0f, 0.72f)
        },
    )
    // The edge and the sheen catch as much light as [SkinSettings.reflection] lets them.
    val shine = settings.reflection.coerceIn(0f, 1f) / SkinSettings.LEGACY_REFLECTION
    val rimStrength = (0.45f * shine).coerceIn(0f, 1f) * amount
    val rimLight = Color(settings.border)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val shadows = settings.shadows
    if (enabled && amount > 0.001f && effectsAllowed && backdrop != null &&
        skin == Skin.SPOTIGLASS && settings.liquidStyle != LiquidStyle.NOTESIS) {
        return styledLiquidGlass(settings.liquidStyle, settings, glassShape, backdrop, panel, dark,
            shadows = shadows && shadowElevation > 0.dp, rim = drawBorder, amount = amount, light = glassLight(settings))
    }
    if (enabled && amount > 0.001f && effectsAllowed && backdrop != null) {
        var result = this
            // Cast round the glass, never under it: a shadow beneath clear glass
            // shows through it and greys the whole pane, which is the first thing
            // that kept this from looking like Apple's.
            .then(if (shadows) Modifier.glassShadow(glassShape, shadowElevation, dark) else Modifier)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { glassShape },
                effects = {
                    // Backdrop의 vibrancy()는 고정 1.5배라서, 설정값을 그대로
                    // 반영할 수 있는 colorControls의 saturation을 사용합니다.
                    colorControls(saturation = 1f + settings.vibrancy * amount)
                    blur(blurPx)
                    // Backdrop's lens refracts along a rounded-rectangle SDF
                    // and throws for any other outline; see [lensSafeShape].
                    if (glassShape is CornerBasedShape) directionalLens(
                        refractionHeight = depthPx,
                        refractionAmount = refractionPx,
                        direction = settings.refractionDirection,
                        depthEffect = settings.depth > 0.5f,
                        // 1.0.6 기본 렌즈와 같은 방식으로 색수차는 임계값
                        // 이상일 때만 켜서 거의 0인 값의 GPU 비용을 없앱니다.
                        chromaticAberration = settings.dispersion * amount > 0.08f,
                    )
                },
                // Apple's specular edge: light from the top left caught along the
                // curve of the rim, brightest where the edge faces it, added to
                // what is under it rather than painted over it.
                highlight = if (drawBorder && rimStrength > 0.001f) {
                    { Highlight(width = 1.dp, blurRadius = 0.6.dp, alpha = rimStrength,
                        style = HighlightStyle.Default(color = rimLight.copy(alpha = if (dark) 0.42f else 0.6f))) }
                } else null,
                shadow = null,
                onDrawSurface = {
                    drawRect(tint)
                    drawRect(
                        Brush.verticalGradient(
                            if (settings.refractionDirection == RefractionDirection.RAISED) {
                                listOf(
                                    Color.White.copy(alpha = (0.13f * amount * shine).coerceAtMost(0.5f)),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.035f * amount),
                                )
                            } else {
                                listOf(
                                    Color.Black.copy(alpha = 0.055f * amount),
                                    Color.Transparent,
                                    Color.White.copy(alpha = (0.10f * amount * shine).coerceAtMost(0.5f)),
                                )
                            },
                        ),
                    )
                },
            )
        // A hair of shade under the light, so the edge still reads over white paper.
        if (drawBorder) result = result.glassHairline(glassShape, dark)
        return result
    }

    // 효과가 꺼졌거나 쓸 수 없는 경우(Android 12 이하 포함)에는 거의 불투명하게
    // 처리합니다. 흐림 없이 비치면 뒤 페이지의 글씨가 선명하게 겹쳐 패널을 읽을
    // 수 없었습니다. 어느 경우에도 RuntimeShader를 생성하지 않아 크래시가 없습니다.
    val fallbackAlpha = if (effectsAllowed) 0.84f else 0.95f
    val fallback = body.copy(alpha = maxOf(body.alpha, fallbackAlpha))
    var result = this
        .then(if (shadows) Modifier.shadow(shadowElevation.coerceAtMost(LiquidGlassTokens.fallbackShadow), glassShape, clip = false)
            else Modifier)
        .clip(glassShape)
        .background(fallback)
    if (drawBorder) {
        result = if (settings.highContrast) {
            result.border(1.dp, Color(settings.border).copy(alpha = 0.72f), glassShape)
        } else {
            result.glassRim(glassShape, rimStrength * 0.7f, rimLight, dark = dark)
        }
    }
    return result
}

/**
 * The edge glass has on iOS: not a line of even white all the way round, but
 * light caught where the glass faces it - brightest at the top-left corner, a
 * softer second catch at the bottom-right, and next to nothing along the
 * sides between, over a faint dark hairline so the edge still reads on white
 * paper. [light] is the colour it catches, [strength] how much of it, from
 * nothing to full.
 */
internal fun Modifier.glassRim(
    shape: Shape,
    strength: Float,
    light: Color = Color.White,
    width: Dp = 1.dp,
    dark: Boolean = false,
): Modifier {
    if (strength <= 0.001f) return this
    return drawWithCache {
        val stroke = width.toPx()
        val inner = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f))
        val outline = shape.createOutline(inner, layoutDirection, this)
        val highlight = glassRimBrush(strength, light, dark)
        val shade = Color.Black.copy(alpha = (if (dark) 0.20f else 0.06f) * strength.coerceAtLeast(0.5f))
        onDrawWithContent {
            drawContent()
            translate(stroke / 2f, stroke / 2f) {
                drawOutline(outline, shade, style = Stroke(stroke))
                drawOutline(outline, highlight, style = Stroke(stroke))
            }
        }
    }
}

/**
 * [glassRim]'s light as a brush for any outline: a sweep round the middle of
 * whatever it is drawn on, laid out on that shape squared up, so a long bar
 * catches the light at its corners as a round button does rather than along
 * the whole of its top.
 */
internal fun glassRimBrush(strength: Float, light: Color = Color.White, dark: Boolean = false): Brush {
    val s = strength.coerceIn(0f, 1f) * if (dark) 0.8f else 1f
    fun lit(a: Float) = light.copy(alpha = (light.alpha * a * s).coerceIn(0f, 1f)).toArgb()
    // Clockwise from the right-hand side: bottom-right, bottom, bottom-left,
    // left, top-left, top, top-right, and round to the right again.
    val colors = intArrayOf(
        lit(0.10f), lit(0.55f), lit(0.08f), lit(0.02f), lit(0.20f), lit(0.95f), lit(0.30f), lit(0.04f), lit(0.10f),
    )
    val stops = floatArrayOf(0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1f)
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val cx = size.width / 2f
            val cy = size.height / 2f
            return android.graphics.SweepGradient(cx, cy, colors, stops).apply {
                val side = minOf(size.width, size.height).coerceAtLeast(1f)
                setLocalMatrix(android.graphics.Matrix().apply {
                    setScale(size.width.coerceAtLeast(1f) / side, size.height.coerceAtLeast(1f) / side, cx, cy)
                })
            }
        }
    }
}

/**
 * The shadow glass casts: soft, wide and faint, drawn only outside the
 * outline, so clear glass over it stays clear. A plain elevation shadow is a
 * dark slab under the pane, and through glass that slab is all you see.
 * [elevation] sets how far it spreads, as an elevation would.
 */
internal fun Modifier.glassShadow(shape: Shape, elevation: Dp, dark: Boolean = false): Modifier {
    if (elevation <= 0.dp) return this
    return drawWithCache {
        val spread = elevation.toPx() * 4f
        val drop = spread / 6f
        val outline = shape.createOutline(size, layoutDirection, this)
        val shade = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.Black.copy(alpha = if (dark) 0.30f else 0.12f).toArgb()
            maskFilter = android.graphics.BlurMaskFilter(spread.coerceAtLeast(0.5f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val cut = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        val path = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }.asAndroidPath()
        onDrawBehind {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val room = spread * 3f
                val layer = native.saveLayer(-room, -room, size.width + room, size.height + room + drop, null)
                native.save()
                native.translate(0f, drop)
                native.drawPath(path, shade)
                native.restore()
                // Nothing under the glass itself.
                native.drawPath(path, cut)
                native.restoreToCount(layer)
            }
        }
    }
}

/** A faint dark line just inside the outline: the edge of clear glass on white paper. */
internal fun Modifier.glassHairline(shape: Shape, dark: Boolean): Modifier = drawWithCache {
    val stroke = 0.6.dp.toPx()
    val inner = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f))
    val outline = shape.createOutline(inner, layoutDirection, this)
    val shade = Color.Black.copy(alpha = if (dark) 0.22f else 0.07f)
    onDrawWithContent {
        drawContent()
        translate(stroke / 2f, stroke / 2f) { drawOutline(outline, shade, style = Stroke(stroke)) }
    }
}

/**
 * Apple's glass answers a touch with light: a glow that spreads from under
 * the finger across the pane while it is held, and fades when it lets go.
 * [interaction] is the control's own; [shape] keeps the glow inside it.
 */
@Composable
internal fun Modifier.glassPressGlow(interaction: androidx.compose.foundation.interaction.InteractionSource, shape: Shape): Modifier {
    val at = remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(interaction) {
        kotlinx.coroutines.coroutineScope {
            interaction.interactions.collect { event ->
                when (event) {
                    is androidx.compose.foundation.interaction.PressInteraction.Press -> {
                        at.value = event.pressPosition
                        launch { glow.animateTo(1f, androidx.compose.animation.core.tween(140)) }
                    }
                    is androidx.compose.foundation.interaction.PressInteraction.Release,
                    is androidx.compose.foundation.interaction.PressInteraction.Cancel ->
                        launch { glow.animateTo(0f, androidx.compose.animation.core.tween(520)) }
                }
            }
        }
    }
    return drawWithContent {
        drawContent()
        val center = at.value ?: return@drawWithContent
        val a = glow.value
        if (a <= 0.001f) return@drawWithContent
        val reach = maxOf(size.width, size.height) * (0.6f + 0.4f * a)
        val outline = shape.createOutline(size, layoutDirection, this)
        val clip = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }
        clipPath(clip) {
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.32f * a), Color.White.copy(alpha = 0.08f * a), Color.Transparent),
                    center = center, radius = reach,
                ),
                radius = reach, center = center,
            )
        }
    }
}

/**
 * Liquid glass's own material, the one the toolbar, its tool strip, its
 * button and the popups opened from it are all made of: the page behind,
 * blurred and bent at the edge by the lens, under a thin coat of the surface
 * colour. The coat is the toolbar opacity the person set; a [panel] carries
 * text and keeps at least [LIQUID_PANEL_MIN_OPACITY] of it, so a paragraph
 * over handwriting stays readable. The lens bends only the backdrop, never the
 * icons and text drawn on top, so those stay sharp.
 */
@Composable
internal fun Modifier.liquidGlassPane(shape: Shape, panel: Boolean = false, shadow: Boolean = true): Modifier {
    val look = LocalSkinSettings.current
    val opacity = look.spotiglassToolbarOpacity.coerceIn(0f, 1f).let {
        if (panel) maxOf(it, LIQUID_PANEL_MIN_OPACITY) else it
    }
    return liquidGlass(
        shape = shape,
        settings = look,
        surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = opacity),
        // A zero elevation adds no shadow layer at all.
        shadowElevation = if (shadow) liquidPaneElevation(panel) else 0.dp,
        useSpotiStyle = false,
        surfaceOpacity = opacity,
        panel = panel,
    )
}

private fun liquidPaneElevation(panel: Boolean): Dp = if (panel) 8.dp else 3.dp

/**
 * The shadow [liquidGlassPane] casts, for drawing it apart from the pane:
 * the full elevation over the lens, and the lower one of the fallback, as
 * [liquidGlass] chooses between them.
 */
@Composable
internal fun liquidGlassPaneShadow(panel: Boolean): Dp {
    val look = LocalSkinSettings.current
    val lens = rememberLiquidGlassEffectsAllowed() && !look.highContrast && LocalLiquidGlassBackdrop.current != null
    val elevation = liquidPaneElevation(panel)
    return if (lens) elevation else elevation.coerceAtMost(LiquidGlassTokens.fallbackShadow)
}

/** The least a liquid glass panel with text on it is coated. */
private const val LIQUID_PANEL_MIN_OPACITY = 0.45f

/**
 * [shape] as an outline Backdrop's lens can work with. Its lens() computes the
 * refraction from the four corner radii of a rounded rectangle and throws
 * UnsupportedOperationException ("Only RoundedRectangularShape or
 * CornerBasedShape is supported in lens effects") on the first draw of any
 * other shape - which crashed the note screen and the appearance settings the
 * moment the toolbar's liquid glass, drawn on a [SpotiGlassShape], appeared.
 * The glass skin's continuous corner becomes a round corner of the same
 * radius; any other unknown outline is kept, and drawn without the lens.
 */
internal fun lensSafeShape(shape: Shape): Shape = when (shape) {
    is CornerBasedShape -> shape
    is SpotiGlassShape -> RoundedCornerShape(shape.radius)
    else -> shape
}

/** 기기별 셰이더 컴파일 차이가 없는 Backdrop 기본 렌즈를 사용하는 안전 경로입니다. */
private fun com.kyant.backdrop.BackdropEffectScope.directionalLens(
    refractionHeight: Float,
    refractionAmount: Float,
    direction: RefractionDirection,
    depthEffect: Boolean,
    chromaticAberration: Boolean,
) {
    lens(
        refractionHeight = refractionHeight,
        refractionAmount = refractionAmount,
        depthEffect = depthEffect && direction == RefractionDirection.RAISED,
        chromaticAberration = chromaticAberration,
    )
}

/** 평균 배경색을 알고 있는 화면에서 사용할 자동 대비 콘텐츠 색입니다. */
fun liquidGlassContentColor(backgroundAverage: Color): Color =
    if (backgroundAverage.luminance() > 0.52f) Color(0xFF17171A) else Color.White

/** API/접근성/절전 설정을 합쳐 고비용 시각 효과를 사용할 수 있는지 계산합니다. */
@Composable
internal fun rememberLiquidGlassEffectsAllowed(): Boolean {
    return LocalLiquidGlassEffectsAllowed.current ?: rememberSystemLiquidGlassEffectsAllowed()
}

/**
 * 애니메이션 배율은 상위 제공자당 ContentObserver 하나로 관찰합니다. 절전 모드는
 * 따로 수신기를 두지 않고 [InteractionGovernor]의 상태를 읽습니다. 앱 전체에서
 * 절전 여부를 아는 곳은 그 하나뿐입니다.
 */
@Composable
internal fun rememberSystemLiquidGlassEffectsAllowed(): Boolean {
    val context = LocalContext.current
    val animations = remember(context) {
        // Idempotent; MainActivity has normally done it already.
        InteractionGovernor.init(context)
        mutableStateOf(readLiquidGlassAnimationsEnabled(context))
    }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animations.value = readLiquidGlassAnimationsEnabled(context)
            }
        }
        val resolver = context.contentResolver
        val uris = listOf(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
            Settings.Global.getUriFor(Settings.Global.WINDOW_ANIMATION_SCALE),
        )
        uris.forEach { resolver.registerContentObserver(it, false, observer) }
        onDispose {
            uris.forEach { resolver.unregisterContentObserver(observer) }
        }
    }
    return animations.value && !InteractionGovernor.observedPowerSave
}

private fun readLiquidGlassAnimationsEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    val resolver = context.contentResolver
    fun scale(name: String): Float = runCatching {
        Settings.Global.getFloat(resolver, name, 1f)
    }.getOrDefault(1f)
    return scale(Settings.Global.ANIMATOR_DURATION_SCALE) > 0f &&
        scale(Settings.Global.TRANSITION_ANIMATION_SCALE) > 0f &&
        scale(Settings.Global.WINDOW_ANIMATION_SCALE) > 0f
}

/** 미리보기에서 배경 레이어와 전경 컨트롤을 순환 참조 없이 분리합니다. */
@Composable
internal fun LiquidGlassPreviewFrame(content: @Composable BoxScope.() -> Unit) {
    val backdrop = rememberLiquidGlassBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .captureLiquidGlassBackdrop(backdrop)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF5B61D9), Color(0xFF4DB9B0), Color(0xFFF2B46D)),
                    ),
                ),
        )
        ProvideLiquidGlassBackdrop(backdrop) {
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}

/** 라이트/다크 @Preview가 실제 색 구성도 함께 바꾸도록 하는 작은 테마입니다. */
@Composable
internal fun LiquidGlassPreviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
