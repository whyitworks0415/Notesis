package com.notesis

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** 2~4개의 항목을 갖는 재사용 가능한 Liquid Glass 세그먼트 컨트롤입니다. */
@Composable
fun LiquidSegmentedControl(
    segments: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    useLiquidGlass: Boolean = true,
) {
    require(segments.size in 2..4) { "LiquidSegmentedControl은 2~4개 세그먼트를 지원합니다." }
    val selected = selectedIndex.coerceIn(segments.indices)
    val latestSelect by rememberUpdatedState(onSelected)
    val effectsAllowed = rememberLiquidGlassEffectsAllowed()
    val morph = remember { Animatable(0f) }
    var previous by remember { mutableIntStateOf(selected) }

    LaunchedEffect(selected, effectsAllowed) {
        if (previous != selected) {
            previous = selected
            morph.snapTo(0f)
            if (!effectsAllowed) return@LaunchedEffect
            // 이동 초반에는 캡슐이 늘어나고, 도착하면서 spring으로 돌아옵니다.
            morph.animateTo(1f, tween(90))
            morph.animateTo(
                0f,
                spring(
                    dampingRatio = LiquidGlassTokens.segmentDampingRatio,
                    stiffness = 430f,
                ),
            )
        }
    }

    BoxWithConstraints(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.62f)),
    ) {
        val segmentWidth = maxWidth / segments.size
        val targetX = segmentWidth * selected
        val indicatorX by animateDpAsState(
            targetValue = targetX,
            animationSpec = if (effectsAllowed) {
                spring(
                    dampingRatio = LiquidGlassTokens.segmentDampingRatio,
                    stiffness = 420f,
                )
            } else {
                snap()
            },
            label = "세그먼트 인디케이터 위치",
        )
        val extra = segmentWidth * (0.14f * morph.value)

        val indicator = Modifier
            .padding(vertical = 3.dp)
            .offset(x = indicatorX - extra / 2f)
            .width(segmentWidth + extra)
            .fillMaxHeight()

        if (useLiquidGlass && effectsAllowed) {
            Box(
                indicator.liquidGlass(
                    intensity = 0.88f,
                    shape = CircleShape,
                    surfaceColor = Color.White.copy(alpha = 0.25f),
                    shadowElevation = 3.dp,
                ),
            )
        } else {
            Box(
                indicator
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
            )
        }

        Row(Modifier.fillMaxSize()) {
            segments.forEachIndexed { index, label ->
                val isSelected = index == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { this.selected = isSelected }
                        .clickable(role = Role.Tab) { latestSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
                        },
                    )
                }
            }
        }
    }
}

@Preview(name = "Liquid segments · Light", widthDp = 360, heightDp = 120)
@Preview(
    name = "Liquid segments · Dark",
    widthDp = 360,
    heightDp = 120,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LiquidSegmentedControlPreview() {
    LiquidGlassPreviewTheme {
        var selected by remember { mutableIntStateOf(0) }
        LiquidGlassPreviewFrame {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                LiquidSegmentedControl(
                    segments = listOf("추천", "보관함", "최근"),
                    selectedIndex = selected,
                    onSelected = { selected = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
