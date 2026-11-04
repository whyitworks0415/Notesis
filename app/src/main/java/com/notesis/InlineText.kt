package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Where text is being typed straight onto the page: the page and the point
 * in it (or the box being changed), and where that is in the window, with
 * how large a page unit is there.
 */
internal data class InlineTextTarget(
    val position: Triple<Int, Float, Float>?,
    val replacing: PageImage?,
    val windowAt: Offset,
    val pageScale: Float,
    val initial: TextBoxContent?,
)

/**
 * Text typed where the page was tapped, at about the size it will have
 * there, instead of in a dialog. Markdown is drawn when it is done. A thin
 * strip over it sizes the text, opens the full styling, and finishes.
 */
@Composable
internal fun InlineTextEditor(
    target: InlineTextTarget,
    text: String,
    onText: (String) -> Unit,
    size: Float,
    onSize: (Float) -> Unit,
    onMoreStyles: () -> Unit,
    onDone: () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current
    LaunchedEffect(target) { runCatching { focus.requestFocus() } }
    // The box's text size in page units, on screen at the page's zoom.
    val fontSize = with(density) { (size * target.pageScale).coerceIn(10f, 200f).toSp() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        Column(
            Modifier.offset {
                IntOffset((target.windowAt.x - origin.x).toInt(),
                    (target.windowAt.y - origin.y - 44.dp.toPx()).toInt().coerceAtLeast(0))
            },
        ) {
            Row(
                Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = { onSize((size - 4f).coerceAtLeast(12f)) }) { Text("A−") }
                TextButton(onClick = { onSize((size + 4f).coerceAtMost(96f)) }) { Text("A+") }
                TextButton(onClick = onMoreStyles) { Text("Styles") }
                TextButton(onClick = onDone) { Text("Done") }
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                textStyle = TextStyle(fontSize = fontSize, color = Color(target.initial?.color ?: 0xFF000000.toInt())),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.focusRequester(focus).widthIn(min = 160.dp, max = 560.dp)
                    .background(Color.White.copy(alpha = 0.92f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    .padding(6.dp),
            )
        }
    }
}
