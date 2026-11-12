package com.notesis

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Two to four choices in one capsule, the chosen one on a lighter pill that
 * slides to it: eased across and landing without a bounce (see
 * [selectionEase]), or at once where the chrome does not move.
 */
@Composable
fun SegmentedControl(
    segments: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(segments.size in 2..4) { "SegmentedControl supports 2 to 4 segments." }
    val view = androidx.compose.ui.platform.LocalView.current
    val selected = selectedIndex.coerceIn(segments.indices)
    val latestSelect by rememberUpdatedState { index: Int ->
        if (index != selectedIndex) Haptics.tick(view)
        onSelected(index)
    }
    val moves = rememberMotionAllowed()
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier
            .clip(CircleShape)
            .background(scheme.surfaceContainerHighest)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.55f), CircleShape)
            .height(46.dp),
    ) {
        val segmentWidth = maxWidth / segments.size
        val indicatorX by animateDpAsState(
            targetValue = segmentWidth * selected,
            animationSpec = if (moves) selectionEase() else snap(),
            label = "Segment indicator position",
        )
        Box(
            Modifier
                .padding(4.dp)
                .offset(x = indicatorX)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(scheme.surface),
        )
        Row(Modifier.fillMaxSize()) {
            segments.forEachIndexed { index, label ->
                val isSelected = index == selected
                val interaction = remember(index) { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { this.selected = isSelected }
                        // The moving pill is the response to the tap.
                        .clickable(
                            role = Role.Tab,
                            interactionSource = interaction,
                            indication = null,
                        ) { latestSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.68f),
                    )
                }
            }
        }
    }
}
