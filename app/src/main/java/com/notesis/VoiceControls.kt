package com.notesis

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The microphone's one big button, on the same glass as the bar it was
 * opened from: a red dot at rest, which draws itself in to a rounded square
 * while it records or listens, with rings going out from it as it does -
 * wider with a louder [level] (0 to 1), where the recogniser reports one.
 * Material and Glassmorphism change between the two at once and show one
 * still ring; only Liquid glass moves.
 */
@Composable
internal fun VoiceRecordButton(
    active: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    level: Float = 0f,
    enabled: Boolean = true,
    diameter: Dp = 76.dp,
) {
    val look = LocalSkinSettings.current
    val moves = LocalSkin.current.animatesChrome && rememberLiquidGlassEffectsAllowed() && !look.highContrast
    val red = Color(0xFFE5484D)
    // 0 the dot, 1 the square.
    val morph by animateFloatAsState(if (active) 1f else 0f,
        if (moves) spring(dampingRatio = 0.62f, stiffness = 420f) else snap(), label = "record morph")
    val loud by animateFloatAsState(level.coerceIn(0f, 1f),
        if (moves) tween(120) else snap(), label = "record level")
    val pulse = if (moves && active) {
        rememberInfiniteTransition(label = "record pulse").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ring",
        ).value
    } else 0f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(diameter)
                .semantics { contentDescription = label }
                .skinMaterial(CircleShape)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val r = this.size.minDimension / 2f
                if (active) {
                    // Rings out from the button: two a beat apart, the louder the wider.
                    fun ring(phase: Float) {
                        val t = ((pulse + phase) % 1f)
                        val reach = r * (0.55f + 0.42f * t * (0.6f + 0.4f * loud))
                        drawCircle(red.copy(alpha = (1f - t) * 0.45f), reach, c, style = Stroke(2.dp.toPx()))
                    }
                    if (moves) { ring(0f); ring(0.5f) }
                    else drawCircle(red.copy(alpha = 0.35f), r * 0.82f, c, style = Stroke(2.dp.toPx()))
                }
                // The dot draws in to a square, its corners closing as it shrinks.
                val side = r * (0.92f - 0.30f * morph) * (if (active) 1f + 0.06f * loud else 1f)
                val corner = side / 2f * (1f - 0.62f * morph)
                drawRoundRect(
                    color = if (enabled) red else red.copy(alpha = 0.35f),
                    topLeft = Offset(c.x - side / 2f, c.y - side / 2f),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(corner),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
