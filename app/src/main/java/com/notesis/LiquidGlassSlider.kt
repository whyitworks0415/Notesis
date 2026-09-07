package com.notesis

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 평소에는 불투명 손잡이이고, 손을 대는 순간만 유리 렌즈로 변하는 슬라이더입니다.
 */
@Composable
fun LiquidGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    label: String = "값",
    valueFormatter: (Float) -> String = { "${(it * 100f).roundToInt()}%" },
    useLiquidGlass: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val range = valueRange.endInclusive - valueRange.start
    val safeRange = range.takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / safeRange).coerceIn(0f, 1f)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val effectsAllowed = rememberLiquidGlassEffectsAllowed() && !LocalSkinSettings.current.highContrast
    // 손잡이가 페이지가 아니라 바로 아래 트랙을 굴절시키도록 트랙 전용 레이어를 둡니다.
    val trackBackdrop = rememberLiquidGlassBackdrop()
    var trackWidth by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    val glassAmount by animateFloatAsState(
        targetValue = if (dragging && enabled && useLiquidGlass && effectsAllowed) 1f else 0f,
        animationSpec = if (effectsAllowed) {
            tween(LiquidGlassTokens.transitionMillis)
        } else {
            snap()
        },
        label = "슬라이더 유리 전환",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging && enabled && effectsAllowed) {
            LiquidGlassTokens.pressedScale
        } else {
            1f
        },
        animationSpec = if (effectsAllowed) {
            spring(dampingRatio = 0.78f, stiffness = 520f)
        } else {
            snap()
        },
        label = "슬라이더 손잡이 크기",
    )

    val thumbDiameter = 28.dp
    val thumbRadiusPx = with(density) { thumbDiameter.toPx() / 2f }
    val travel = (trackWidth - thumbRadiusPx * 2f).coerceAtLeast(1f)
    val centerX = thumbRadiusPx + travel * fraction

    fun report(pointerX: Float) {
        if (!enabled) return
        val next = ((pointerX - thumbRadiusPx) / travel).coerceIn(0f, 1f)
        latestChange(valueRange.start + safeRange * next)
    }

    Box(
        modifier
            .height(44.dp)
            .onSizeChanged { trackWidth = it.width }
            .semantics(mergeDescendants = true) {
                contentDescription = "$label ${valueFormatter(value)}"
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                if (enabled) {
                    setProgress { target ->
                        latestChange(target.coerceIn(valueRange))
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled, valueRange, trackWidth) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    report(down.position.x)
                    try {
                        drag(down.id) { change ->
                            change.consume()
                            report(change.position.x)
                        }
                    } finally {
                        dragging = false
                        latestFinished?.invoke()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .captureLiquidGlassBackdrop(trackBackdrop),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }

        val thumbModifier = Modifier
            .offset { IntOffset((centerX - thumbRadiusPx).roundToInt(), 0) }
            .size(thumbDiameter)
            .scale(thumbScale)

        ProvideLiquidGlassBackdrop(trackBackdrop) {
            if (glassAmount > 0.001f) {
                Box(
                    thumbModifier.liquidGlass(
                        intensity = glassAmount,
                        shape = CircleShape,
                        // 흰색 표면을 전혀 섞지 않아 트랙이 렌즈 중앙까지 이어집니다.
                        surfaceColor = Color.Transparent,
                        shadowElevation = 5.dp,
                    ),
                )
            }
            // 대기 손잡이는 테마의 단색 강조색입니다. 흰 원은 밝은 페이지 위에서
            // 슬라이더 중앙만 하얗게 번진 것처럼 보였으므로 쓰지 않습니다. 누르면
            // 이 면이 사라지면서 위의 투명 렌즈만 남습니다.
            Box(
                thumbModifier
                    .graphicsLayer { alpha = (1f - glassAmount).coerceIn(0f, 1f) }
                    .shadow(3.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(scheme.primary)
                    .border(1.dp, scheme.onPrimary.copy(alpha = 0.18f), CircleShape),
            )
        }
    }
}

@Preview(name = "Liquid slider · Light", widthDp = 360, heightDp = 120)
@Preview(
    name = "Liquid slider · Dark",
    widthDp = 360,
    heightDp = 120,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LiquidGlassSliderPreview() {
    LiquidGlassPreviewTheme {
        var value by remember { mutableStateOf(0.42f) }
        LiquidGlassPreviewFrame {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                LiquidGlassSlider(
                    value = value,
                    onValueChange = { value = it },
                    label = "불투명도",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
