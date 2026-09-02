package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Where the glass is tuned.
 *
 * Every control here changes something the eye can check immediately, so the
 * preview at the top is the point of the screen rather than decoration: the
 * numbers mean nothing on their own and everything against a piece of glass
 * sitting on a picture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinSettingsScreen(
    skin: Skin,
    settings: SkinSettings,
    onSkin: (Skin) -> Unit,
    onChange: (SkinSettings) -> Unit,
    onBack: () -> Unit,
) {
    var picking by remember { mutableStateOf<ColorSlot?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                title = { Text("화면 설정") },
                actions = {
                    IconButton(onClick = { onChange(SkinSettings()) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "기본값으로")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { Preview() }

            item { SectionLabel("테마") }
            items(Skin.entries) { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSkin(option) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProvideSkin(option, settings) {
                        SkinSurface(Modifier.size(44.dp), corner = 13.dp) {}
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            option.blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (option == skin) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
            }

            item {
                SectionLabel("배경")
                Setting(
                    "블러 반경",
                    "%.0fdp".format(settings.blur),
                    settings.blur,
                    SkinSettings.BLUR_RANGE,
                    note = "패널 뒤가 흐려지는 정도. 팝업 뒤에도 같은 값이 쓰입니다",
                ) { onChange(settings.copy(blur = it)) }
                Setting(
                    "블러 생동감",
                    "+%.0f%%".format(settings.vibrancy * 100),
                    settings.vibrancy,
                    SkinSettings.VIBRANCY_RANGE,
                ) { onChange(settings.copy(vibrancy = it)) }
                Setting(
                    "모서리",
                    "%.0fdp".format(settings.corner),
                    settings.corner,
                    SkinSettings.CORNER_RANGE,
                ) { onChange(settings.copy(corner = it)) }
            }

            item {
                SectionLabel("카드 및 버튼")
                ColorRow("색상", settings.tint) { picking = ColorSlot.TINT }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                ColorRow("테두리", settings.border) { picking = ColorSlot.BORDER }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                ColorRow("텍스트 및 아이콘", settings.content) { picking = ColorSlot.CONTENT }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                ColorRow(
                    "강조 색상",
                    settings.accent,
                    note = "테마 전체가 이 색에서 만들어집니다",
                ) { picking = ColorSlot.ACCENT }
                AccentPresets(settings.accent) { onChange(settings.copy(accent = it)) }
            }
        }
    }

    picking?.let { slot ->
        SkinColorDialog(
            title = slot.label,
            argb = slot.read(settings),
            onDismiss = { picking = null },
            onPick = {
                onChange(slot.write(settings, it))
                picking = null
            },
        )
    }
}

/** Which colour a picker is for, so one dialog serves all four. */
enum class ColorSlot(val label: String) {
    TINT("색상"),
    BORDER("테두리"),
    CONTENT("텍스트 및 아이콘"),
    ACCENT("강조 색상"),
    ;

    fun read(s: SkinSettings): Int = when (this) {
        TINT -> s.tint
        BORDER -> s.border
        CONTENT -> s.content
        ACCENT -> s.accent
    }

    fun write(s: SkinSettings, argb: Int): SkinSettings = when (this) {
        TINT -> s.copy(tint = argb)
        BORDER -> s.copy(border = argb)
        CONTENT -> s.copy(content = argb)
        ACCENT -> s.copy(accent = argb)
    }
}

/**
 * Glass over something worth looking through. A flat colour would hide exactly
 * the thing being tuned, so the preview sits on a band of colour and a rule of
 * lines - frost only shows where there was detail to scatter.
 */
@Composable
private fun Preview() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF6A5AE0),
                        Color(0xFF39C3C9),
                        Color(0xFFF2A65A),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val backdrop = rememberBackdrop(active = true)
        Box(Modifier.fillMaxSize().recordBackdrop(backdrop)) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                repeat(9) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .padding(end = if (it % 2 == 0) 0.dp else 90.dp)
                            .background(Color.White.copy(alpha = 0.7f)),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalBackdrop provides backdrop) {
            SkinSurface(Modifier.fillMaxWidth(0.7f).height(96.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "뒤가 흐려지고 색은 살아 있어야 유리입니다",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * The accent is now the whole theme, so it is worth being able to change it
 * without going through a colour wheel first. These are the hues Material's own
 * baseline is one of - the purple is that baseline, so the row starts where the
 * app has always been and every other swatch is a different app.
 */
@Composable
private fun AccentPresets(current: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (argb in ACCENTS) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .clickable { onPick(argb) },
                contentAlignment = Alignment.Center,
            ) {
                if (argb == current) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Material's own primary, and eight more hues to leave it for. */
private val ACCENTS = listOf(
    0xFF6750A4, 0xFF0A84FF, 0xFF00A03C, 0xFF00897B,
    0xFF3F51B5, 0xFFEF6C00, 0xFFD8324B, 0xFFC2185B, 0xFF5A6472,
).map { it.toInt() }

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Setting(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    note: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(6.dp))
        SkinSlider(
            value = current.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColorRow(label: String, argb: Int, note: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        // Chequered underneath, so a colour that is mostly transparent looks
        // transparent rather than looking like a slightly different grey.
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFDDDDDD), Color(0xFFF6F6F6))))
                .background(Color(argb)),
        )
    }
}

/** Alpha included, because the body colour of glass is mostly its alpha. */
@Composable
private fun SkinColorDialog(
    title: String,
    argb: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val hsv = remember(argb) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
    }
    var hue by remember(argb) { androidx.compose.runtime.mutableFloatStateOf(hsv[0]) }
    var saturation by remember(argb) { androidx.compose.runtime.mutableFloatStateOf(hsv[1]) }
    var value by remember(argb) { androidx.compose.runtime.mutableFloatStateOf(hsv[2]) }
    var alpha by remember(argb) {
        androidx.compose.runtime.mutableFloatStateOf(android.graphics.Color.alpha(argb) / 255f)
    }
    val picked = Color.hsv(hue, saturation, value, alpha)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                SaturationValueField(hue, saturation, value) { s, v ->
                    saturation = s
                    value = v
                }
                Spacer(Modifier.height(10.dp))
                GradientStrip(
                    colors = (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) },
                    position = hue / 360f,
                ) { hue = it * 360f }
                Spacer(Modifier.height(10.dp))
                GradientStrip(
                    colors = listOf(
                        Color.hsv(hue, saturation, value, 0f),
                        Color.hsv(hue, saturation, value),
                    ),
                    position = alpha,
                ) { alpha = it }
                Spacer(Modifier.height(10.dp))
                Text(
                    "#%08X".format(picked.toArgb()) + "   투명도 ${(alpha * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onPick(picked.toArgb()) }) {
                Text("적용")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}
