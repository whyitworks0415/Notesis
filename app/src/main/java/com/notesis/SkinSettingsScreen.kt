package com.notesis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val penStore = remember(context) { PenStore(context) }
    var picking by remember { mutableStateOf<ColorSlot?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var deferDetail by remember { mutableStateOf(penStore.deferDetail) }

    // The screen that sets the glass was itself the one screen wearing none.
    val backdrop = rememberBackdrop(
        active = skin == Skin.GLASSMORPHISM &&
            (settings.blur > 0.1f || settings.vibrancy > 0.01f),
    )
    val liquidBackdrop = rememberLiquidGlassBackdrop()
    CompositionLocalProvider(
        LocalBackdrop provides backdrop,
        LocalLiquidGlassBackdrop provides if (skin == Skin.LIQUID_GLASS) liquidBackdrop else null,
    ) {
    Scaffold(
        topBar = {
            SkinSurface(flush = true) {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                title = { Text("화면 설정") },
                actions = {
                    TextButton(onClick = { onChange(SkinSettings()) }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("초기화")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
            }
        },
    ) { padding ->
        // Nothing inside the recording may sample it; see NoBackdrop.
        CompositionLocalProvider(
            LocalBackdrop provides NoBackdrop,
            LocalLiquidGlassBackdrop provides null,
        ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .recordBackdrop(backdrop)
                .then(
                    if (skin == Skin.LIQUID_GLASS) {
                        Modifier.captureLiquidGlassBackdrop(liquidBackdrop)
                    } else {
                        Modifier
                    },
                )
                // Inside the recording, so the layer holds a whole page and not
                // a page's content on nothing. Frosting a transparent recording
                // lays a blurred copy over the sharp one still on screen, and
                // the doubled light is what washed out the panes.
                .background(MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item { Preview() }

            item {
                SectionLabel("빠른 설정", "자주 바꾸는 항목만 한곳에 모았습니다")
                SettingsCard {
                    Text("밝기", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    LiquidSegmentedControl(
                        segments = AppThemeMode.entries.map { it.label },
                        selectedIndex = settings.themeMode.ordinal,
                        onSelected = { index ->
                            onChange(settings.copy(themeMode = AppThemeMode.entries[index]))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        useLiquidGlass = false,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text("스타일", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    SkinPicker(skin, settings, onSkin)
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text("강조 색상", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "버튼과 선택 표시의 기준 색입니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(10.dp))
                    AccentPresets(settings.accent) { onChange(settings.copy(accent = it)) }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    ToggleRow(
                        "고대비",
                        "글자와 버튼 경계를 더 또렷하게 표시합니다",
                        settings.highContrast,
                        compact = true,
                    ) { onChange(settings.copy(highContrast = it)) }
                    HorizontalDivider()
                    ToggleRow(
                        "지연 상세 렌더링",
                        if (deferDetail) {
                            "이동·확대 중에는 가볍게 표시하고 손을 떼면 선명하게 바꿉니다"
                        } else {
                            "이동·확대 중에도 상세 화질을 갱신합니다. 복잡한 페이지는 버벅일 수 있습니다"
                        },
                        deferDetail,
                        compact = true,
                    ) {
                        deferDetail = it
                        penStore.deferDetail = it
                    }
                }
            }

            item {
                SectionLabel("세부 설정", "필요할 때만 열어 미세하게 조절하세요")
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { showAdvanced = !showAdvanced },
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(if (showAdvanced) "세부 설정 접기" else "세부 설정 열기")
                            Text(
                                "블러 · 굴절 · 모서리 · 개별 색상",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Icon(
                            if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                    }
                }
            }

            item {
                AnimatedVisibility(showAdvanced) {
                    AdvancedSettings(
                        skin = skin,
                        settings = settings,
                        onChange = onChange,
                        onPickColor = { picking = it },
                    )
                }
            }
        }
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

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), content = content)
    }
}

@Composable
private fun SkinPicker(
    selected: Skin,
    settings: SkinSettings,
    onPick: (Skin) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in Skin.entries) {
            val active = option == selected
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .border(
                        if (active) 2.dp else 1.dp,
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(16.dp),
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPick(option) },
                shape = RoundedCornerShape(16.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ProvideSkin(option, settings) {
                        SkinSurface(Modifier.size(30.dp), corner = 10.dp) {}
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedSettings(
    skin: Skin,
    settings: SkinSettings,
    onChange: (SkinSettings) -> Unit,
    onPickColor: (ColorSlot) -> Unit,
) {
    Column(Modifier.animateContentSize()) {
        SectionLabel("유리 효과", "값을 움직이면 위 미리보기에 바로 반영됩니다")
        SettingsCard {
            Setting(
                "배경 흐림",
                "%.0fdp".format(settings.blur),
                settings.blur,
                SkinSettings.BLUR_RANGE,
                note = "패널 뒤가 흐려지는 정도",
            ) { onChange(settings.copy(blur = it)) }
            HorizontalDivider()
            Setting(
                "색 생동감",
                "+%.0f%%".format(settings.vibrancy * 100),
                settings.vibrancy,
                SkinSettings.VIBRANCY_RANGE,
            ) { onChange(settings.copy(vibrancy = it)) }
            if (skin == Skin.LIQUID_GLASS) {
                HorizontalDivider()
                Text("굴절 방향", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "패널이 화면 위로 솟거나 안으로 눌려 보이는 방향",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                LiquidSegmentedControl(
                    segments = RefractionDirection.entries.map { it.label },
                    selectedIndex = settings.refractionDirection.ordinal,
                    onSelected = { index ->
                        onChange(settings.copy(refractionDirection = RefractionDirection.entries[index]))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    useLiquidGlass = false,
                )
                HorizontalDivider(Modifier.padding(top = 14.dp))
                Setting(
                    "렌즈 깊이",
                    "%.0fdp".format(settings.depth),
                    settings.depth,
                    SkinSettings.DEPTH_RANGE,
                ) { onChange(settings.copy(depth = it)) }
                HorizontalDivider()
                Setting(
                    "굴절량",
                    "%.0fdp".format(settings.refraction),
                    settings.refraction,
                    SkinSettings.REFRACTION_RANGE,
                ) { onChange(settings.copy(refraction = it)) }
                HorizontalDivider()
                Setting(
                    "색수차",
                    "%.0f%%".format(settings.dispersion * 100f),
                    settings.dispersion,
                    SkinSettings.DISPERSION_RANGE,
                ) { onChange(settings.copy(dispersion = it)) }
            }
            HorizontalDivider()
            Setting(
                "둥근 모서리",
                "%.0fdp".format(settings.corner),
                settings.corner,
                SkinSettings.CORNER_RANGE,
            ) { onChange(settings.copy(corner = it)) }
        }

        SectionLabel("개별 색상", "원하는 부분만 눌러 색을 바꿀 수 있습니다")
        SettingsCard {
            ColorGrid(settings, onPickColor)
        }
    }
}

@Composable
private fun ColorGrid(settings: SkinSettings, onPick: (ColorSlot) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorTile("패널", settings.tint, Modifier.weight(1f)) { onPick(ColorSlot.TINT) }
            ColorTile("테두리", settings.border, Modifier.weight(1f)) { onPick(ColorSlot.BORDER) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorTile("글자·아이콘", settings.content, Modifier.weight(1f)) {
                onPick(ColorSlot.CONTENT)
            }
            ColorTile("강조", settings.accent, Modifier.weight(1f)) { onPick(ColorSlot.ACCENT) }
        }
    }
}

@Composable
private fun ColorTile(label: String, argb: Int, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFDDDDDD), Color.White)))
                .background(Color(argb)),
        )
        Spacer(Modifier.width(9.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

/**
 * Glass over something worth looking through. A flat colour would hide exactly
 * the thing being tuned, so the preview sits on a band of colour and a rule of
 * lines - frost only shows where there was detail to scatter.
 */
@Composable
private fun Preview() {
    var amount by remember { androidx.compose.runtime.mutableFloatStateOf(0.58f) }
    var enabled by remember { mutableStateOf(true) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(170.dp),
        contentAlignment = Alignment.Center,
    ) {
        val backdrop = rememberBackdrop(active = true)
        val liquidBackdrop = rememberLiquidGlassBackdrop()
        // The gradient is what the glass is supposed to be blurring, so it goes
        // inside the recording. Left on the parent it was never in the layer:
        // the pane showed it sharp, with a blurred ghost of the lines laid over
        // the lines themselves.
        Box(
            Modifier
                .fillMaxSize()
                .recordBackdrop(backdrop)
                .captureLiquidGlassBackdrop(liquidBackdrop)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF6A5AE0),
                            Color(0xFF39C3C9),
                            Color(0xFFF2A65A),
                        ),
                    ),
                ),
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                repeat(6) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .padding(end = if (it % 2 == 0) 0.dp else 90.dp)
                            .background(Color.White.copy(alpha = 0.7f)),
                    )
                    Spacer(Modifier.height(13.dp))
                }
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalBackdrop provides backdrop,
            LocalLiquidGlassBackdrop provides liquidBackdrop,
        ) {
            SkinSurface(Modifier.fillMaxWidth(0.72f).height(112.dp)) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (LocalSkin.current == Skin.LIQUID_GLASS) {
                            "빛을 품고, 손끝에서 형태가 변하는 렌즈"
                        } else {
                            "뒤가 흐려지고 색은 살아 있어야 유리입니다"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(5.dp))
                    SkinSlider(
                        value = amount,
                        onValueChange = { amount = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("인터랙티브 글래스", style = MaterialTheme.typography.bodySmall)
                        SkinSwitch(enabled) { enabled = it }
                    }
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

/** A setting that is either on or off, wearing the skin's own switch. */
@Composable
private fun ToggleRow(
    label: String,
    note: String,
    checked: Boolean,
    compact: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = if (compact) 0.dp else 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        SkinSwitch(checked, onChange)
    }
}

@Composable
private fun SectionLabel(text: String, description: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
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
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        val labelBlock: @Composable () -> Unit = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        value,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        if (maxWidth < 520.dp) {
            Column {
                labelBlock()
                Spacer(Modifier.height(4.dp))
                SkinSlider(
                    value = current.coerceIn(range),
                    onValueChange = onChange,
                    valueRange = range,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(end = 20.dp)) { labelBlock() }
                SkinSlider(
                    value = current.coerceIn(range),
                    onValueChange = onChange,
                    valueRange = range,
                    modifier = Modifier.width(220.dp),
                )
            }
        }
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

    AlertDialog(
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
            TextButton(onClick = { onPick(picked.toArgb()) }) {
                Text("적용")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}
