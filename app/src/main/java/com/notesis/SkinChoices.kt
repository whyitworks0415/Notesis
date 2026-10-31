package com.notesis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The one way the look is chosen, wherever it is chosen: the first run, the
 * bar's look button, the appearance settings. Each skin is shown as a little
 * bar in its own material, named, with what it is like underneath.
 */
@Composable
internal fun SkinChoiceList(current: Skin, onSkin: (Skin) -> Unit, modifier: Modifier = Modifier) {
    val pick = rememberLookPick(onSkin)
    val now = Look(current, LocalSkinSettings.current.liquidStyle)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        for (look in Look.all) {
            val chosen = look.skin == now.skin && (look.skin != Skin.SPOTIGLASS || look.style == now.style)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(choiceShape())
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
                    .clickable(role = Role.RadioButton) { pick(look) }
                    .semantics { selected = chosen }
                    .padding(Ui.Space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ui.Space.m),
            ) {
                SkinPreview(look.skin, Modifier.size(88.dp, 56.dp), look.style)
                Column(Modifier.weight(1f)) {
                    Text(look.label, style = MaterialTheme.typography.titleMedium)
                    Text(look.blurb, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RadioButton(selected = chosen, onClick = { pick(look) })
            }
        }
    }
}

/** Picking a look: the skin, and for Liquid glass its recipe too. */
@Composable
private fun rememberLookPick(onSkin: (Skin) -> Unit): (Look) -> Unit {
    val setStyle = LocalLiquidStyleChange.current
    return { look ->
        if (look.skin == Skin.SPOTIGLASS) setStyle(look.style)
        onSkin(look.skin)
    }
}

/**
 * The same choice in less height, for a screen that has a preview above it
 * already: the five previews in a row that scrolls, each named under it.
 */
@Composable
internal fun SkinChoiceTiles(current: Skin, onSkin: (Skin) -> Unit, modifier: Modifier = Modifier) {
    val pick = rememberLookPick(onSkin)
    val now = Look(current, LocalSkinSettings.current.liquidStyle)
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        for (look in Look.all) {
            val chosen = look.skin == now.skin && (look.skin != Skin.SPOTIGLASS || look.style == now.style)
            val shape = choiceShape()
            Column(
                Modifier
                    .width(112.dp)
                    .clip(shape)
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
                    .border(
                        if (chosen) 2.dp else 1.dp,
                        if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape,
                    )
                    .clickable(role = Role.RadioButton) { pick(look) }
                    .semantics { selected = chosen }
                    .heightIn(min = Ui.Size.touch)
                    .padding(Ui.Space.s),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Ui.Space.xs),
            ) {
                SkinPreview(look.skin, Modifier.fillMaxWidth().heightIn(min = 40.dp).size(width = 88.dp, height = 44.dp), look.style)
                Text(look.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

/**
 * The bar's look button: the same panel as the pen's settings, out of the
 * button that was tapped, with the same list as everywhere else. A pick shows
 * at once, so the bar behind is the preview.
 */
@Composable
internal fun SkinPickerPanel(current: Skin, onSkin: (Skin) -> Unit, onDismiss: () -> Unit) {
    val panel = rememberToolPanelState()
    ToolPanel(
        title = "Look",
        state = panel,
        onOutside = { panel.close(onDismiss) },
        maxWidth = 460.dp,
        actions = {
            TextButton(onClick = { panel.close(onDismiss) }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) {
                Text("Done")
            }
        },
    ) {
        SkinChoiceList(current, onSkin)
        Text(
            "Colours, corners, blur and dark mode are in Settings › Appearance.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** A row or tile inside a panel: concentric with it, or the control corner outside one. */
@Composable
private fun choiceShape() = RoundedCornerShape(nestedCorner(Ui.Corner.control))

/** A little bar in [skin]'s material over a scrap of colour, so the looks can be told apart at a glance. */
@Composable
internal fun SkinPreview(skin: Skin, modifier: Modifier, style: LiquidStyle = LiquidStyle.NOTESIS) {
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val onSurface = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.semantics { contentDescription = "${skin.label} preview" }) {
        val corner = Ui.Corner.preview.toPx()
        // What the bar sits over: a page with something coloured on it.
        drawRoundRect(Color(0xFFF4F1EA), cornerRadius = CornerRadius(corner))
        drawCircle(Color(0xFFFF8A65), radius = size.height * 0.34f, center = Offset(size.width * 0.28f, size.height * 0.62f))
        drawCircle(Color(0xFF4FC3F7), radius = size.height * 0.30f, center = Offset(size.width * 0.70f, size.height * 0.40f))
        val bar = Rect(Offset(size.width * 0.1f, size.height * 0.30f), Size(size.width * 0.8f, size.height * 0.40f))
        val radius = CornerRadius(bar.height / 2f)
        when (skin) {
            Skin.MATERIAL -> drawRoundRect(surface, bar.topLeft, bar.size, radius)
            Skin.GLASSMORPHISM -> {
                drawRoundRect(Color.White.copy(alpha = 0.55f), bar.topLeft, bar.size, radius)
                drawRoundRect(Color.White.copy(alpha = 0.9f), bar.topLeft, bar.size, radius, style = Stroke(1.dp.toPx()))
            }
            Skin.SPOTIGLASS -> when (style) {
                // Kyant0's: a light surface at 40%, colour lifted, a thin lit rim.
                LiquidStyle.KYANT -> {
                    drawRoundRect(Color(0xFFFAFAFA).copy(alpha = 0.4f), bar.topLeft, bar.size, radius)
                    drawRoundRect(Color.White, bar.topLeft, bar.size, radius, style = Stroke(0.6.dp.toPx()))
                }
                // QWEA0's: tinted grey over a light page, lit from the top left.
                LiquidStyle.QWEA -> {
                    drawRoundRect(Color.Black.copy(alpha = 0.22f), bar.topLeft, bar.size, radius)
                    drawRoundRect(
                        Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0.1f)), start = bar.topLeft, end = bar.bottomRight),
                        bar.topLeft, bar.size, radius, style = Stroke(1.5.dp.toPx()),
                    )
                }
                LiquidStyle.NOTESIS -> {
                    drawRoundRect(Color.White.copy(alpha = 0.22f), bar.topLeft, bar.size, radius)
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0f)),
                            startY = bar.top, endY = bar.center.y),
                        bar.topLeft, bar.size, radius,
                    )
                    drawRoundRect(Color.White, bar.topLeft, bar.size, radius, style = Stroke(1.2.dp.toPx()))
                }
            }
        }
        // The tools, the first one picked.
        val step = bar.width / 4f
        for (i in 0 until 4) {
            val c = Offset(bar.left + step * (i + 0.5f), bar.center.y)
            if (i == 0) drawCircle(accent.copy(alpha = 0.25f), radius = bar.height * 0.42f, center = c)
            drawCircle(if (i == 0) accent else onSurface.copy(alpha = 0.7f), radius = bar.height * 0.14f, center = c)
        }
    }
}
