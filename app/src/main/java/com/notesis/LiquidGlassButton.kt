package com.notesis

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 평상시에도 실제 배경을 굴절시키고, 누르면 렌즈가 조금 넓어지고 깊어지는 버튼입니다.
 * [expandedContent]를 주면 길게 눌렀을 때 같은 레이아웃 안에서 메뉴로 늘어납니다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    useLiquidGlass: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    expandedContent: (@Composable () -> Unit)? = null,
    /** 평상시 버튼의 단색 면입니다. */
    containerColor: Color = Color.White,
    /** 흰색 면 위에서 읽히는 기본 아이콘/텍스트 색입니다. */
    contentColor: Color = Color(0xFF1D1D1F),
    /** 버튼 내용과 가장자리 사이의 최소 여백입니다. */
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val effectsAllowed = rememberLiquidGlassEffectsAllowed() && !LocalSkinSettings.current.highContrast
    var expanded by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && effectsAllowed) 0.96f else 1f,
        animationSpec = if (effectsAllowed) spring(dampingRatio = 0.82f, stiffness = 620f) else snap(),
        label = "버튼 눌림 크기",
    )
    val glassAmount by animateFloatAsState(
        targetValue = when {
            !useLiquidGlass -> 0f
            pressed && enabled -> 1f
            else -> 0.76f
        },
        animationSpec = if (effectsAllowed) tween(170) else snap(),
        label = "버튼 굴절 강도",
    )
    val haloScale by animateFloatAsState(
        // Apple식 버튼은 손가락 아래에서 살짝 퍼지되 터치 영역은 바꾸지 않습니다.
        targetValue = if (pressed && enabled && useLiquidGlass) 1.08f else 1f,
        animationSpec = if (effectsAllowed) tween(190) else snap(),
        label = "버튼 유리 확장",
    )

    val longClick = if (expandedContent != null || onLongClick != null) {
        {
            if (expandedContent != null) expanded = !expanded
            onLongClick?.invoke()
            Unit
        }
    } else {
        null
    }

    val buttonModifier = modifier
        .scale(scale)
        .animateContentSize(
            animationSpec = spring(
                dampingRatio = LiquidGlassTokens.segmentDampingRatio,
                stiffness = 430f,
            ),
        )
        .combinedClickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
            onLongClick = longClick,
        )

    Box(buttonModifier, contentAlignment = Alignment.Center) {
        if (useLiquidGlass && glassAmount > 0.001f) {
            // 이 형제 레이어만 확대하므로 터치 영역/레이아웃은 그대로이고 유리만 넘칩니다.
            Box(
                Modifier
                    .matchParentSize()
                    .scale(haloScale)
                    .liquidGlass(
                        intensity = glassAmount,
                        shape = CircleShape,
                        // 아주 얇은 흰 코팅만 남기고 배경이 버튼 몸체가 되게 합니다.
                        surfaceColor = Color.White.copy(alpha = 0.20f),
                        shadowElevation = 6.dp,
                    ),
            )
        }

        if (!useLiquidGlass) {
            Box(
                Modifier
                    .matchParentSize()
                    .shadow(1.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(containerColor.copy(alpha = if (enabled) 1f else 0.52f)),
            )
        }

        AnimatedContent(
            targetState = expanded,
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
            label = "버튼 메뉴 모핑",
        ) { isExpanded ->
            CompositionLocalProvider(
                LocalContentColor provides contentColor.copy(alpha = if (enabled) 1f else 0.38f),
            ) {
                Row(
                    Modifier.padding(contentPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isExpanded && expandedContent != null) expandedContent() else content()
                }
            }
        }
    }
}

@Preview(name = "Liquid button · Light", widthDp = 300, heightDp = 120)
@Preview(
    name = "Liquid button · Dark",
    widthDp = 300,
    heightDp = 120,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LiquidGlassButtonPreview() {
    LiquidGlassPreviewTheme {
        LiquidGlassPreviewFrame {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LiquidGlassButton(
                    onClick = {},
                    expandedContent = { Text("복사   공유   삭제") },
                ) {
                    Text("길게 눌러 메뉴")
                }
            }
        }
    }
}
