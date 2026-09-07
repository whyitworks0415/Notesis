package com.notesis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
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
fun Modifier.captureLiquidGlassBackdrop(backdrop: LayerBackdrop): Modifier =
    layerBackdrop(backdrop)

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
): Modifier {
    val amount = intensity.coerceIn(0f, 1f)
    val backdrop = LocalLiquidGlassBackdrop.current
    val effectsAllowed = rememberLiquidGlassEffectsAllowed() && !settings.highContrast
    val density = LocalDensity.current
    val blurPx = with(density) { settings.blur.dp.toPx() } * amount
    val depthPx = with(density) { settings.depth.dp.toPx() } * amount
    val refractionPx = with(density) { settings.refraction.dp.toPx() } * amount
    val tint = surfaceColor.copy(
        alpha = if (settings.highContrast) {
            maxOf(surfaceColor.alpha, 0.94f)
        } else if (surfaceColor.alpha <= 0f) {
            // 완전 투명 렌즈를 요청한 손잡이/버튼에 최소 흰색 알파를 강제로
            // 넣으면 전환 중 중앙에 흰 점이 생깁니다. 0은 그대로 보존합니다.
            0f
        } else {
            (surfaceColor.alpha * (0.72f + amount * 0.28f)).coerceIn(0f, 0.72f)
        },
    )
    val borderColor = Color(settings.border).copy(
        alpha = (0.30f + settings.dispersion.coerceIn(0f, 1f) * 0.38f) * amount,
    )

    if (enabled && amount > 0.001f && effectsAllowed && backdrop != null) {
        var result = this
            .shadow(shadowElevation, shape, clip = false)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    // Backdrop의 vibrancy()는 고정 1.5배라서, 설정값을 그대로
                    // 반영할 수 있는 colorControls의 saturation을 사용합니다.
                    colorControls(saturation = 1f + settings.vibrancy * amount)
                    blur(blurPx)
                    lens(
                        refractionHeight = depthPx,
                        refractionAmount = refractionPx,
                        depthEffect = settings.depth > 0.5f,
                        // 1.0.6 API는 색수차를 Boolean으로 받습니다. 세기는
                        // 아래 테두리 알파에 연속적으로 반영하고 셰이더는 임계값
                        // 이상일 때만 켜서 거의 0인 값의 GPU 비용을 없앱니다.
                        chromaticAberration = settings.dispersion * amount > 0.08f,
                    )
                },
                highlight = null,
                shadow = null,
                onDrawSurface = { drawRect(tint) },
            )
        if (drawBorder) result = result.border(1.dp, borderColor, shape)
        return result
    }

    // 시스템 효과가 줄어든 경우에는 불투명하게, 구형 API에서는 반투명하게
    // 처리합니다. 어느 경우에도 RuntimeShader를 생성하지 않아 크래시가 없습니다.
    val fallbackAlpha = if (!effectsAllowed && Build.VERSION.SDK_INT >= 33) 0.96f else 0.84f
    val fallback = surfaceColor.copy(alpha = maxOf(surfaceColor.alpha, fallbackAlpha))
    var result = this
        .shadow(shadowElevation.coerceAtMost(LiquidGlassTokens.fallbackShadow), shape, clip = false)
        .clip(shape)
        .background(fallback)
    if (drawBorder) {
        result = result.border(
            1.dp,
            Color(settings.border).copy(alpha = if (settings.highContrast) 0.72f else 0.32f),
            shape,
        )
    }
    return result
}

/** 평균 배경색을 알고 있는 화면에서 사용할 자동 대비 콘텐츠 색입니다. */
fun liquidGlassContentColor(backgroundAverage: Color): Color =
    if (backgroundAverage.luminance() > 0.52f) Color(0xFF17171A) else Color.White

/** API/접근성/절전 설정을 합쳐 고비용 시각 효과를 사용할 수 있는지 계산합니다. */
@Composable
internal fun rememberLiquidGlassEffectsAllowed(): Boolean {
    return LocalLiquidGlassEffectsAllowed.current ?: rememberSystemLiquidGlassEffectsAllowed()
}

/** ContentObserver와 BroadcastReceiver는 상위 제공자당 하나만 등록합니다. */
@Composable
internal fun rememberSystemLiquidGlassEffectsAllowed(): Boolean {
    val context = LocalContext.current
    val allowed = remember(context) {
        mutableStateOf(readLiquidGlassEffectsAllowed(context))
    }

    DisposableEffect(context) {
        fun refresh() {
            allowed.value = readLiquidGlassEffectsAllowed(context)
        }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        val resolver = context.contentResolver
        val uris = listOf(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
            Settings.Global.getUriFor(Settings.Global.WINDOW_ANIMATION_SCALE),
        )
        uris.forEach { resolver.registerContentObserver(it, false, observer) }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        val registered = runCatching {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            true
        }.getOrDefault(false)

        onDispose {
            uris.forEach { resolver.unregisterContentObserver(observer) }
            if (registered) runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return allowed.value
}

private fun readLiquidGlassEffectsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    val resolver = context.contentResolver
    fun scale(name: String): Float = runCatching {
        Settings.Global.getFloat(resolver, name, 1f)
    }.getOrDefault(1f)
    val animationsEnabled =
        scale(Settings.Global.ANIMATOR_DURATION_SCALE) > 0f &&
            scale(Settings.Global.TRANSITION_ANIMATION_SCALE) > 0f &&
            scale(Settings.Global.WINDOW_ANIMATION_SCALE) > 0f
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return animationsEnabled && powerManager?.isPowerSaveMode != true
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
