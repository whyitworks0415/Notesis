package com.notesis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The five ways of picking a colour. The choice is remembered across tools. */
internal enum class ColorPickerMode(val label: String) {
    SQUARE("사각형"),
    BARS("HSV"),
    RING("링"),
    RGB("RGB"),
    DISC("디스크"),
}

/**
 * The colour being set, held once as hue, saturation, value and alpha so that
 * every picker reads and writes the same thing and switching pickers loses
 * nothing - not even the hue of a grey, which RGB alone cannot keep.
 */
@Stable
internal class PenColorState(argb: Int) {
    var hue by mutableFloatStateOf(0f)
    var saturation by mutableFloatStateOf(0f)
    var value by mutableFloatStateOf(0f)
    var alpha by mutableFloatStateOf(1f)

    init { set(argb) }

    /** Takes [argb] in; with [keepAlpha] the alpha already chosen stays. */
    fun set(argb: Int, keepAlpha: Boolean = false) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        // A grey has no hue of its own; keep the one already there.
        if (hsv[1] > 0f) hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        if (!keepAlpha) alpha = android.graphics.Color.alpha(argb) / 255f
    }

    fun argb(): Int = android.graphics.Color.HSVToColor(
        (alpha * 255f).roundToInt().coerceIn(0, 255), floatArrayOf(hue, saturation, value),
    )

    fun rgb(): Int = argb() or OPAQUE

    fun setRgb(red: Int, green: Int, blue: Int) =
        set(android.graphics.Color.rgb(red, green, blue), keepAlpha = true)

    companion object {
        const val OPAQUE = -0x1000000
    }
}

/**
 * How one tool is set, on a glass panel over the page: colour on top with the
 * palette beside it, then thickness with a live preview, the kind of pen and
 * its pressure, the line style - and everything else behind 세부, which opens
 * the panel downward rather than crowding the top.
 *
 * Tapping past the panel, or back, keeps what was set. 취소 leaves the tool
 * as it was.
 */
@Composable
internal fun PenDialog(
    mode: EditMode,
    pen: PenPreset,
    deferDetail: Boolean,
    onDeferDetail: (Boolean) -> Unit,
    highlighterAboveInk: Boolean,
    onHighlighterAboveInk: (Boolean) -> Unit,
    axisSnap: Boolean,
    onAxisSnap: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (PenPreset) -> Unit,
    meshInk: Boolean = true,
    onMeshInk: (Boolean) -> Unit = {},
    partialEraser: Boolean = false,
    onPartialEraser: (Boolean) -> Unit = {},
    compatWetInk: Boolean = false,
    onCompatWetInk: (Boolean) -> Unit = {},
    gestures: CanvasGestures = CanvasGestures(),
    onGestures: (CanvasGestures) -> Unit = {},
    onClearPage: (() -> Unit)? = null,
    onEyedropper: (() -> Unit)? = null,
    onCornerRadius: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    val color = remember(pen) { PenColorState(pen.colorArgb) }
    var width by remember(pen) { mutableFloatStateOf(pen.width) }
    var pressure by remember(pen) { mutableStateOf(pen.pressure) }
    var nib by remember(pen) { mutableStateOf(pen.nib) }
    var maxWidth by remember(pen) { mutableFloatStateOf(pen.maxWidth) }
    var leadMs by remember(pen) { mutableIntStateOf(pen.leadMs) }
    var stabilizer by remember(pen) { mutableIntStateOf(pen.stabilizer) }
    var linePattern by remember(pen) { mutableIntStateOf(pen.linePattern) }
    var autoShapes by remember(pen) { mutableStateOf(pen.autoShapes) }
    var straightLine by remember(pen) { mutableStateOf(pen.straightLine) }
    var details by remember { mutableStateOf(false) }
    var pressureResponse by remember { mutableIntStateOf(gestures.pressureResponse) }
    var joined by remember { mutableStateOf(store.colorPickerJoined) }
    val opacity = allowsOpacity(mode)
    val range = PenStore.widthRange(mode, pen.copy(maxWidth = maxWidth))
    val drawing = pen.copy(nib = nib, pressure = pressure).drawingTool()
    val freehand = mode == EditMode.PEN || mode == EditMode.PENCIL

    fun current() = PenPreset(
        tool = pen.tool,
        colorArgb = if (opacity) color.argb() else color.rgb(),
        width = width.coerceIn(range),
        pressure = pressure,
        maxWidth = maxWidth,
        nib = nib,
        leadMs = leadMs,
        stabilizer = stabilizer,
        linePattern = linePattern,
        autoShapes = autoShapes,
        straightLine = straightLine,
    )
    fun keep() {
        if (pressureResponse != gestures.pressureResponse) onGestures(gestures.copy(pressureResponse = pressureResponse))
        onConfirm(current())
    }

    Dialog(onDismissRequest = ::keep, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A light dim: the page should still show through the glass.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0.12f)
        val shape = RoundedCornerShape(28.dp)
        val tallest = LocalConfiguration.current.screenHeightDp.dp * 0.9f
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .heightIn(max = tallest)
                .liquidGlass(
                    shape = shape,
                    surfaceColor = MaterialTheme.colorScheme.surface,
                    surfaceOpacity = 0.86f,
                    useSpotiStyle = false,
                )
                .clip(shape)
                .verticalScroll(rememberScrollState())
                .animateContentSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(toolLabel(mode), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("취소") }
                TextButton(onClick = ::keep) { Text("저장") }
            }

            if (mode == EditMode.ERASE) {
                SectionHeader("지우개")
                EraserSection(partialEraser, onPartialEraser, gestures, onGestures)
            } else if (mode.tints) {
                SectionHeader("색상")
                ColorSection(
                    color = color,
                    opacity = opacity,
                    initialMode = store.colorPickerMode,
                    onMode = { store.colorPickerMode = it },
                    joined = joined,
                    onEyedropper = onEyedropper?.let { pick -> { keep(); pick() } },
                )
            }

            SectionHeader(if (mode == EditMode.PEN) "펜 굵기" else "굵기")
            WidthSection(
                mode = mode,
                width = width,
                range = range,
                onWidth = { width = it },
                preview = if (mode == EditMode.ERASE) null
                    else Color(if (opacity) color.argb() else color.rgb()),
                linePattern = if (freehand && !drawing.isDabbed()) linePattern else 0,
            )

            val nibs = if (freehand) nibsFor(pen.tool) else emptyList()
            if (nibs.isNotEmpty() || mode == EditMode.PEN) {
                SectionHeader(if (mode == EditMode.PENCIL) "연필 종류" else "펜 종류")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        nibs.forEach { (tool, label) ->
                            SettingsChoiceChip(
                                selected = if (mode == EditMode.PENCIL) drawing == tool else nib == tool,
                                onClick = { nib = tool },
                                label = label,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    if (mode == EditMode.PEN) {
                        Text("필압", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        SkinSwitch(checked = pressure, onCheckedChange = { pressure = it })
                    }
                }
            }

            if (freehand && !drawing.isDabbed()) {
                SectionHeader("선 스타일")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("실선", "점선", "파선", "일점쇄선").forEachIndexed { index, label ->
                        SettingsChoiceChip(
                            selected = linePattern == index,
                            onClick = { linePattern = index },
                            label = label,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }

            if (mode in STRAIGHT_LINE_MODES) {
                SwitchRow(
                    "직선",
                    straightLine,
                    hint = "펜을 댄 곳에서 뗀 곳까지 곧은 선으로 그립니다",
                ) { straightLine = it }
            }

            // 세부: everything else, opened downward on demand.
            Row(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { details = !details }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Reicons.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(if (details) "세부 설정 접기" else "세부 설정", style = MaterialTheme.typography.bodyMedium)
            }
            AnimatedVisibility(
                visible = details,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (mode != EditMode.ERASE) {
                        Text("이 도구", style = MaterialTheme.typography.labelLarge)
                        PredictionSetting(leadMs) { leadMs = it }
                    }
                    if (freehand) {
                        Text(
                            "획 보정 $stabilizer%" + if (gestures.windowedStabilizer && stabilizer > 0)
                                " · 최근 ${slidingWindowMillis(stabilizer)}ms 평균" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        SkinSlider(
                            value = stabilizer.toFloat(),
                            onValueChange = { stabilizer = it.roundToInt() },
                            valueRange = 0f..100f,
                            step = 1f,
                        )
                        Row {
                            SettingsChoiceChip(
                                selected = !gestures.windowedStabilizer,
                                onClick = { onGestures(gestures.copy(windowedStabilizer = false)) },
                                label = "적응형",
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            SettingsChoiceChip(
                                selected = gestures.windowedStabilizer,
                                onClick = { onGestures(gestures.copy(windowedStabilizer = true)) },
                                label = "구간 평균",
                            )
                        }
                        Text(
                            if (gestures.windowedStabilizer) "최근 몇 ms 동안의 펜 위치를 평균합니다. 느리게 그은 떨리는 선이 더 곧아지고, 대신 그만큼 뒤따라옵니다"
                            else "빠르게 쓸 때는 바로 따라오고, 느리고 작은 떨림만 줄입니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        SwitchRow("자동 도형 인식", autoShapes) { autoShapes = it }
                    }
                    WidthCeilings(mode, range) { ceiling ->
                        maxWidth = ceiling
                        width = width.coerceAtMost(ceiling)
                    }
                    WidthControls(mode, width) {
                        width = it
                        if (it > range.endInclusive) maxWidth = it
                    }
                    if (mode == EditMode.SHAPE) {
                        SwitchRow("직선을 90° 간격으로 보정", axisSnap, onChange = onAxisSnap)
                        var corner by remember { mutableFloatStateOf(store.shapeCornerRadius) }
                        Text("다각형 모서리 둥글기 ${corner.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
                        SkinSlider(corner, { corner = it; store.shapeCornerRadius = it; onCornerRadius(it) }, 0f..120f, step = 1f)
                    }
                    if (mode == EditMode.HIGHLIGHTER) {
                        SwitchRow(if (highlighterAboveInk) "필기 위에 표시" else "필기 아래에 표시",
                            highlighterAboveInk, onChange = onHighlighterAboveInk)
                    }
                    if (mode == EditMode.ERASE && onClearPage != null) {
                        TextButton(onClick = onClearPage) {
                            Icon(Reicons.Delete, contentDescription = null)
                            Text(" 현재 페이지 모두 지우기")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("모든 도구 공통", style = MaterialTheme.typography.labelLarge)
                    if (freehand || mode == EditMode.ERASE) {
                        Text("필압 반영 정도 $pressureResponse", style = MaterialTheme.typography.bodyMedium)
                        SkinSlider(
                            value = pressureResponse.toFloat(),
                            onValueChange = { pressureResponse = it.roundToInt().coerceIn(0, 100) },
                            valueRange = 0f..100f,
                            step = 1f,
                        )
                        Text(
                            "기본 10. 0이면 필압을 무시하고, 높일수록 살짝 누를 때 더 가늘어져 세게 눌러야 굵게 나옵니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (freehand) {
                        SwitchRow("끝에서 멈추면 도형으로", gestures.holdToDraw) {
                            onGestures(gestures.copy(holdToDraw = it))
                        }
                        SwitchRow("낙서로 지우기", gestures.scribbleErase) {
                            onGestures(gestures.copy(scribbleErase = it))
                        }
                        SwitchRow("원을 그리고 멈추면 선택", gestures.circleToLasso) {
                            onGestures(gestures.copy(circleToLasso = it))
                        }
                    }
                    if (mode.tints) SwitchRow(
                        "색상 선택기를 한 판으로", joined,
                        "막대·사각형·RGB를 틈 없이 붙여 하나의 판으로 보여 줍니다. 끄면 따로 떨어진 모양",
                    ) { joined = it; store.colorPickerJoined = it }
                    SwitchRow(
                        "호환 필기 표시", compatWetInk,
                        "쓰는 동안 획이 보이지 않고 펜을 떼야 나타나면 켜세요. Galaxy Tab S6 Lite에서는 기본으로 켜집니다",
                        onCompatWetInk,
                    )
                    SwitchRow(
                        "확대 후 선명하게", deferDetail,
                        "확대하는 동안은 있는 그대로 그리고, 손을 떼면 그때 다시 선명하게 만듭니다",
                        onDeferDetail,
                    )
                    SwitchRow(
                        "메시 렌더링 (실험)", meshInk,
                        "불투명한 펜·필압 펜을 Ink 셰이더로 그립니다. 끄면 이전 방식(경로 채우기)으로 돌아갑니다",
                        onMeshInk,
                    )
                }
            }
        }
    }
}

/** 자동, off at 0, or a fixed 1-12ms; one slider so every millisecond is there. */
@Composable
private fun PredictionSetting(leadMs: Int, onLead: (Int) -> Unit) {
    val auto = leadMs == LEAD_AUTO
    // What automatic comes to on this screen: half a frame on most tablets.
    val refresh = LocalView.current.display?.refreshRate ?: 60f
    val autoMs = autoPredictionLeadMs(refresh, vsyncBoundInk = frontBufferInkUnreliable())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "예측 " + when {
                auto -> "자동 · $autoMs ms (${refresh.roundToInt()}Hz)"
                leadMs == 0 -> "끔"
                else -> "$leadMs ms"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        SettingsChoiceChip(selected = auto, onClick = { onLead(if (auto) autoMs else LEAD_AUTO) }, label = "자동")
    }
    SkinSlider(
        value = (if (auto) autoMs else leadMs).toFloat(),
        onValueChange = { onLead(it.roundToInt().coerceIn(0, MAX_LEAD_MS)) },
        valueRange = 0f..MAX_LEAD_MS.toFloat(),
        step = 1f,
    )
    Text(
        if (auto) "자동은 60Hz에서 9ms(호환 필기 표시는 12ms), 90Hz 이상에서 6ms까지 앞서 그립니다"
        else "펜보다 이만큼 앞서 그립니다. 0은 예측을 끕니다. 길수록 빠르게 느껴지지만 획 끝이 흔들릴 수 있습니다",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(10.dp))
        SkinSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EraserSection(
    partialEraser: Boolean,
    onPartialEraser: (Boolean) -> Unit,
    gestures: CanvasGestures,
    onGestures: (CanvasGestures) -> Unit,
) {
    Row {
        SettingsChoiceChip(selected = !partialEraser, onClick = { onPartialEraser(false) },
            label = "획 지우개", modifier = Modifier.padding(end = 8.dp))
        SettingsChoiceChip(selected = partialEraser, onClick = { onPartialEraser(true) }, label = "부분 지우개")
    }
    Text(if (partialEraser) "지나간 부분만 지웁니다" else "닿은 획을 통째로 지웁니다",
        style = MaterialTheme.typography.bodySmall)
    Text("지울 대상", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(
            "펜" to gestures.eraseInk, "형광펜" to gestures.eraseHighlighter, "테이프" to gestures.eraseTape,
            "이미지" to gestures.eraseImages, "텍스트 상자" to gestures.eraseText, "잠긴 객체" to gestures.eraseLocked,
        ).forEach { (label, on) ->
            SettingsChoiceChip(selected = on, label = label, modifier = Modifier.padding(end = 6.dp), onClick = {
                onGestures(when (label) {
                    "펜" -> gestures.copy(eraseInk = !on)
                    "형광펜" -> gestures.copy(eraseHighlighter = !on)
                    "테이프" -> gestures.copy(eraseTape = !on)
                    "이미지" -> gestures.copy(eraseImages = !on)
                    "텍스트 상자" -> gestures.copy(eraseText = !on)
                    else -> gestures.copy(eraseLocked = !on)
                })
            })
        }
    }
    SwitchRow(
        "필압 지우개",
        gestures.pressureEraser,
        hint = "펜을 누르는 힘에 따라 지우개 크기가 바뀝니다. 세게 누를수록 설정한 크기에 가까워집니다",
    ) { onGestures(gestures.copy(pressureEraser = it)) }
}

// ---- colour ----------------------------------------------------------------

/** The one gap the panel uses everywhere, so every edge lines up with every other. */
internal val GAP = 10.dp

private val PICKER_HEIGHT = 190.dp
private val STRIP_HEIGHT = 30.dp
private val BAR_WIDTH = 34.dp
private val LABEL_HEIGHT = 18.dp
private val FIELD_HEIGHT = 56.dp
private val PALETTE_WIDTH = 96.dp
private val PICKER_SHAPE = RoundedCornerShape(16.dp)

/** A section's name with a hairline running out to the right edge. */
@Composable
private fun SectionHeader(label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(GAP))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** One of a row of equal pills that share the width between them. */
@Composable
private fun ModePill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else scheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColorSection(
    color: PenColorState,
    opacity: Boolean,
    initialMode: Int,
    onMode: (Int) -> Unit,
    joined: Boolean,
    onEyedropper: (() -> Unit)?,
) {
    var pickerMode by remember {
        mutableStateOf(ColorPickerMode.entries.getOrElse(initialMode) { ColorPickerMode.SQUARE })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
        ColorPickerMode.entries.forEach { option ->
            ModePill(option.label, pickerMode == option, Modifier.weight(1f)) {
                pickerMode = option
                onMode(option.ordinal)
            }
        }
    }
    PaletteBeside(
        main = {
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                when (pickerMode) {
                    ColorPickerMode.SQUARE -> SquarePicker(color, joined)
                    ColorPickerMode.BARS -> BarsPicker(color, joined)
                    ColorPickerMode.RING -> RingPicker(color)
                    ColorPickerMode.RGB -> RgbPicker(color, joined)
                    ColorPickerMode.DISC -> DiscPicker(color, joined)
                }
                if (opacity) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        Text("투명도", style = MaterialTheme.typography.labelMedium)
                        GradientStrip(
                            colors = listOf(Color(color.rgb()).copy(alpha = 0f), Color(color.rgb())),
                            position = color.alpha,
                            modifier = Modifier.weight(1f).height(STRIP_HEIGHT),
                            shape = RoundedCornerShape(STRIP_HEIGHT / 2),
                        ) { color.alpha = it }
                        Text(
                            "${(color.alpha * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(36.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    HexField(color, opacity, Modifier.weight(1f).height(FIELD_HEIGHT))
                    if (onEyedropper != null) {
                        val shape = MaterialTheme.shapes.extraSmall
                        Box(
                            Modifier
                                .size(FIELD_HEIGHT)
                                .clip(shape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                                .clickable(onClick = onEyedropper),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Reicons.Colorize, contentDescription = "스포이드", modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        },
        palette = { PalettePanel(color, opacity) },
    )
}

/**
 * The picker on the left and the palette on the right, the palette exactly
 * as tall as everything on the left - picker, opacity and the hex row - so
 * both columns start and end on the same lines.
 */
@Composable
private fun PaletteBeside(main: @Composable () -> Unit, palette: @Composable () -> Unit) {
    Layout(contents = listOf(main, palette)) { measurables, constraints ->
        val gap = GAP.roundToPx()
        val paletteWidth = PALETTE_WIDTH.roundToPx().coerceAtMost(constraints.maxWidth / 3)
        val mainWidth = (constraints.maxWidth - paletteWidth - gap).coerceAtLeast(0)
        val mains = measurables[0].map { it.measure(Constraints(minWidth = mainWidth, maxWidth = mainWidth)) }
        val height = mains.maxOfOrNull { it.height } ?: 0
        val palettes = measurables[1].map { it.measure(Constraints.fixed(paletteWidth, height)) }
        layout(constraints.maxWidth, height) {
            mains.forEach { it.place(0, 0) }
            palettes.forEach { it.place(mainWidth + gap, 0) }
        }
    }
}

/** 1. Saturation across, value down, hue on the strip below. */
@Composable
private fun SquarePicker(color: PenColorState, joined: Boolean) {
    Column(
        Modifier.fillMaxWidth().height(PICKER_HEIGHT).then(if (joined) Modifier.clip(PICKER_SHAPE) else Modifier),
        verticalArrangement = Arrangement.spacedBy(if (joined) 0.dp else GAP),
    ) {
        SaturationValueField(
            color.hue, color.saturation, color.value,
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = if (joined) RectangleShape else PICKER_SHAPE,
        ) { s, v ->
            color.saturation = s
            color.value = v
        }
        GradientStrip(
            colors = HUES,
            position = color.hue / 360f,
            modifier = Modifier.fillMaxWidth().height(STRIP_HEIGHT),
            shape = if (joined) RectangleShape else RoundedCornerShape(STRIP_HEIGHT / 2),
        ) { color.hue = it * 360f }
    }
}

/** 2. Three upright H, S and V bars, with the colour itself beside them. */
@Composable
private fun BarsPicker(color: PenColorState, joined: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(PICKER_HEIGHT).then(if (joined) Modifier.clip(PICKER_SHAPE) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(if (joined) 0.dp else GAP),
    ) {
        VerticalStrip(HUES, color.hue / 360f, "H", joined) { color.hue = it * 360f }
        VerticalStrip(
            listOf(Color.hsv(color.hue, 0f, color.value), Color.hsv(color.hue, 1f, color.value)),
            color.saturation, "S", joined,
        ) { color.saturation = it }
        VerticalStrip(
            listOf(Color.Black, Color.hsv(color.hue, color.saturation, 1f)),
            color.value, "V", joined,
        ) { color.value = it }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Apart, the bars carry their letters above them; the colour
            // starts on the same line as the bars rather than above them.
            if (!joined) Spacer(Modifier.height(LABEL_HEIGHT))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (joined) Modifier else Modifier.clip(PICKER_SHAPE))
                    .background(Color.White)
                    .background(Color(color.argb())),
                contentAlignment = if (joined) Alignment.TopStart else Alignment.BottomStart,
            ) {
                Text(
                    "H ${color.hue.roundToInt()}°  S ${(color.saturation * 100).roundToInt()}%  V ${(color.value * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (color.value > 0.6f && color.saturation < 0.5f) Color.Black.copy(alpha = 0.7f)
                        else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(GAP),
                )
            }
        }
    }
}

/** 3. A hue ring around the saturation-value square, with the numbers to type. */
@Composable
private fun RingPicker(color: PenColorState) {
    val thumb = with(LocalDensity.current) { PICKER_THUMB_RADIUS.toPx() }
    Row(
        Modifier.fillMaxWidth().height(PICKER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Canvas(
            Modifier
                .size(PICKER_HEIGHT)
                .pointerInput(color) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val outer = min(size.width, size.height) / 2f
                        val ring = outer * RING_FRACTION
                        val onRing = hypot(down.position.x - c.x, down.position.y - c.y) >= outer - ring - 2f
                        fun take(at: Offset) {
                            if (onRing) {
                                color.hue = angleOf(at - c)
                            } else {
                                val half = innerHalf(outer, ring)
                                color.saturation = trackFraction(at.x - (c.x - half), 2 * half, thumb)
                                color.value = 1f - trackFraction(at.y - (c.y - half), 2 * half, thumb)
                            }
                        }
                        take(down.position)
                        drag(down.id) { change -> change.consume(); take(change.position) }
                    }
                },
        ) {
            val c = center
            val outer = size.minDimension / 2f
            val ring = outer * RING_FRACTION
            drawCircle(Brush.sweepGradient(HUES, c), radius = outer - ring / 2f, center = c, style = Stroke(ring))
            val half = innerHalf(outer, ring)
            val topLeft = Offset(c.x - half, c.y - half)
            val box = Size(half * 2, half * 2)
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(color.hue, 1f, 1f)),
                startX = topLeft.x, endX = topLeft.x + box.width), topLeft, box)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black),
                startY = topLeft.y, endY = topLeft.y + box.height), topLeft, box)
            val angle = Math.toRadians(color.hue.toDouble())
            val r = outer - ring / 2f
            drawPickerThumb(
                Offset(c.x + (r * kotlin.math.cos(angle)).toFloat(), c.y + (r * kotlin.math.sin(angle)).toFloat()),
                min(thumb, ring / 2f - 1.dp.toPx()),
            )
            drawPickerThumb(Offset(
                topLeft.x + trackPosition(color.saturation, box.width, thumb),
                topLeft.y + trackPosition(1f - color.value, box.height, thumb),
            ), thumb)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GAP)) {
            NumberField("H", color.hue.roundToInt(), 0..360, "°", Modifier.fillMaxWidth()) { color.hue = it.toFloat() % 360f }
            NumberField("S", (color.saturation * 100).roundToInt(), 0..100, "%", Modifier.fillMaxWidth()) { color.saturation = it / 100f }
            NumberField("V", (color.value * 100).roundToInt(), 0..100, "%", Modifier.fillMaxWidth()) { color.value = it / 100f }
        }
    }
}

/** 4. Red, green and blue, each on a strip that shows where it would go. */
@Composable
private fun RgbPicker(color: PenColorState, joined: Boolean) {
    val rgb = color.rgb()
    val red = android.graphics.Color.red(rgb)
    val green = android.graphics.Color.green(rgb)
    val blue = android.graphics.Color.blue(rgb)
    val channels = listOf(
        Triple("R", red, listOf(Color(0, green, blue), Color(255, green, blue))),
        Triple("G", green, listOf(Color(red, 0, blue), Color(red, 255, blue))),
        Triple("B", blue, listOf(Color(red, green, 0), Color(red, green, 255))),
    )
    fun set(label: String, next: Int) = when (label) {
        "R" -> color.setRgb(next, green, blue)
        "G" -> color.setRgb(red, next, blue)
        else -> color.setRgb(red, green, next)
    }
    Row(Modifier.fillMaxWidth().height(PICKER_HEIGHT), horizontalArrangement = Arrangement.spacedBy(GAP)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().then(if (joined) Modifier.clip(PICKER_SHAPE) else Modifier),
            verticalArrangement = Arrangement.spacedBy(if (joined) 0.dp else GAP),
        ) {
            channels.forEach { (label, channel, ramp) ->
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                    GradientStrip(
                        colors = ramp,
                        position = channel / 255f,
                        modifier = Modifier.fillMaxSize(),
                        shape = if (joined) RectangleShape else PICKER_SHAPE,
                    ) { set(label, (it * 255f).roundToInt()) }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(start = GAP),
                    )
                }
            }
        }
        Column(
            Modifier.width(84.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(if (joined) 0.dp else GAP),
        ) {
            channels.forEach { (label, channel, _) ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    NumberField(null, channel, 0..255, null, Modifier.fillMaxWidth()) { set(label, it) }
                }
            }
        }
    }
}

/** 5. A colour disc - hue around, saturation outward - and brightness beside it. */
@Composable
private fun DiscPicker(color: PenColorState, joined: Boolean) {
    val thumb = with(LocalDensity.current) { PICKER_THUMB_RADIUS.toPx() }
    Row(
        Modifier.fillMaxWidth().height(PICKER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Canvas(
            Modifier
                .size(PICKER_HEIGHT)
                .pointerInput(color) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        fun take(at: Offset) {
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val reach = (min(size.width, size.height) / 2f - thumb).coerceAtLeast(1f)
                            val d = at - c
                            color.hue = angleOf(d)
                            color.saturation = (hypot(d.x, d.y) / reach).coerceIn(0f, 1f)
                        }
                        take(down.position)
                        drag(down.id) { change -> change.consume(); take(change.position) }
                    }
                },
        ) {
            val radius = size.minDimension / 2f
            drawCircle(Brush.sweepGradient(HUES, center), radius)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radius), radius)
            drawCircle(Color.Black.copy(alpha = 1f - color.value), radius)
            val angle = Math.toRadians(color.hue.toDouble())
            val r = color.saturation * (radius - thumb)
            drawPickerThumb(
                Offset(center.x + (r * kotlin.math.cos(angle)).toFloat(), center.y + (r * kotlin.math.sin(angle)).toFloat()),
                thumb,
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("명도 ${(color.value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.height(LABEL_HEIGHT))
            Box(Modifier.weight(1f)) {
                VerticalStrip(
                    listOf(Color.Black, Color.hsv(color.hue, color.saturation, 1f)),
                    color.value, null, joined = false,
                ) { color.value = it }
            }
        }
    }
}

/** The palette beside the picker: the built-in and saved colour sets, a tap each. */
@Composable
private fun PalettePanel(color: PenColorState, opacity: Boolean) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    var custom by remember { mutableStateOf(store.colorTemplates()) }
    var adding by remember { mutableStateOf(false) }
    if (adding) TemplateEditor(onDismiss = { adding = false }, onSave = { name, colors ->
        custom = custom + (name to colors)
        store.saveColorTemplates(custom)
        adding = false
    })
    Column(
        Modifier
            .fillMaxSize()
            .clip(PICKER_SHAPE)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .verticalScroll(rememberScrollState())
            .padding(GAP),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("팔레트", style = MaterialTheme.typography.labelMedium)
        for ((name, colors) in COLOR_TEMPLATES + custom) {
            Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            colors.chunked(3).forEach { row ->
                // Three to a row, spread edge to edge, so every row shares
                // the same columns whether it is full or not.
                Row(Modifier.fillMaxWidth()) {
                    for (index in 0 until 3) {
                        val argb = row.getOrNull(index)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (argb != null) Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable {
                                        // A see-through swatch brings its alpha only
                                        // where the tool can be see-through at all.
                                        val translucent = android.graphics.Color.alpha(argb) < 255
                                        color.set(argb, keepAlpha = !(opacity && translucent))
                                    },
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = { adding = true }) { Text("+ 추가") }
    }
}

/** #RRGGBB, or #AARRGGBB where the tool can be see-through; applied as soon as it parses. */
@Composable
private fun HexField(color: PenColorState, opacity: Boolean, modifier: Modifier) {
    val shown = if (opacity) "%08X".format(color.argb()) else "%06X".format(color.rgb() and 0xFFFFFF)
    var draft by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(shown) { if (!focused) draft = null }
    OutlinedTextField(
        value = draft ?: shown,
        onValueChange = { typed ->
            val clean = typed.removePrefix("#").filter { it.isLetterOrDigit() }.take(if (opacity) 8 else 6)
            draft = clean
            val parsed = clean.toLongOrNull(16)
            if (parsed != null && (clean.length == 6 || (opacity && clean.length == 8))) {
                val argb = if (clean.length == 6) (parsed.toInt() or PenColorState.OPAQUE) else parsed.toInt()
                color.set(argb, keepAlpha = clean.length == 6)
            }
        },
        prefix = { Text("#") },
        singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused; if (!it.isFocused) draft = null },
    )
}

/** A small number box that applies whatever it holds once it is a number in [range]. */
@Composable
private fun NumberField(
    label: String?,
    value: Int,
    range: IntRange,
    suffix: String?,
    modifier: Modifier = Modifier.width(96.dp),
    onValue: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) draft = null }
    OutlinedTextField(
        value = draft ?: value.toString(),
        onValueChange = { typed ->
            val digits = typed.filter { it.isDigit() }.take(3)
            draft = digits
            digits.toIntOrNull()?.takeIf { it in range }?.let(onValue)
        },
        label = if (label != null) { { Text(label) } } else null,
        suffix = if (suffix != null) { { Text(suffix) } } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.onFocusChanged { focused = it.isFocused; if (!it.isFocused) draft = null },
    )
}

/**
 * An upright ramp with a handle: the top is 1, the bottom 0. Apart, it is a
 * rounded bar with its letter above; joined, a plain band that butts against
 * its neighbours and leaves the rounding to the block it sits in.
 */
@Composable
private fun VerticalStrip(
    colors: List<Color>,
    position: Float,
    label: String?,
    joined: Boolean,
    onChange: (Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val thumb = with(LocalDensity.current) { PICKER_THUMB_RADIUS.toPx() }
    Column(Modifier.width(BAR_WIDTH).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!joined && label != null) Box(Modifier.height(LABEL_HEIGHT), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (joined) Modifier else Modifier.clip(RoundedCornerShape(BAR_WIDTH / 2)))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        fun take(at: Offset) =
                            currentOnChange(1f - trackFraction(at.y, size.height.toFloat(), thumb))
                        take(down.position)
                        drag(down.id) { change -> change.consume(); take(change.position) }
                    }
                },
        ) {
            drawRect(Color.White)
            drawRect(Brush.verticalGradient(colors.reversed()))
            // Kept off the rounded ends by a little more than the radius, so
            // the handle never meets the curve and looks cut.
            val inset = if (joined) thumb else size.width / 2f
            drawPickerThumb(Offset(size.width / 2f, trackPosition(1f - position, size.height, inset)), thumb)
        }
    }
}

private val HUES = (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }

/** How much of the ring picker's radius the hue ring takes. */
private const val RING_FRACTION = 0.18f

/** Half the side of the square that fits inside the ring with a little air. */
private fun innerHalf(outer: Float, ring: Float): Float = (outer - ring) / sqrt(2f) * 0.92f

/** Degrees clockwise from three o'clock, as the sweep gradients run. */
private fun angleOf(d: Offset): Float {
    val degrees = Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat()
    return (degrees + 360f) % 360f
}

// ---- thickness ---------------------------------------------------------------

@Composable
private fun WidthSection(
    mode: EditMode,
    width: Float,
    range: ClosedFloatingPointRange<Float>,
    onWidth: (Float) -> Unit,
    preview: Color?,
    linePattern: Int,
) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    var favorites by remember(mode) { mutableStateOf(store.favoriteWidths(mode)) }
    val shown = width.coerceIn(range)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP)) {
        Column(Modifier.weight(1f)) {
            SkinSlider(value = shown, onValueChange = onWidth, valueRange = range, step = WIDTH_SLIDER_STEP)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onWidth((shown - WIDTH_STEP).coerceIn(range)) }) { Text("−") }
                Text(widthLabel(shown), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onWidth((shown + WIDTH_STEP).coerceIn(range)) }) { Text("+") }
                val starred = favorites.any { kotlin.math.abs(it - shown) < 0.005f }
                TextButton(onClick = {
                    favorites = if (starred) favorites.filterNot { kotlin.math.abs(it - shown) < 0.005f }
                        else (favorites + shown).sorted()
                    store.saveFavoriteWidths(mode, favorites)
                }) { Text(if (starred) "★" else "☆") }
            }
            if (favorites.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                favorites.forEach { favorite ->
                    SettingsChoiceChip(
                        selected = kotlin.math.abs(favorite - shown) < 0.005f,
                        onClick = { onWidth(favorite.coerceIn(range)) },
                        label = widthLabel(favorite),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
        StrokePreview(width = shown, color = preview, linePattern = linePattern,
            modifier = Modifier.width(150.dp).height(72.dp))
    }
}

/** The line as it will draw - colour, see-through, thickness and pattern - or the eraser's reach. */
@Composable
private fun StrokePreview(width: Float, color: Color?, linePattern: Int, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Canvas(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
    ) {
        val stroke = width.dp.toPx().coerceIn(1f, size.height * 0.45f)
        if (color == null) {
            drawCircle(outline, stroke / 2f, center, style = Stroke(2.dp.toPx()))
            return@Canvas
        }
        val pad = stroke / 2f + 8.dp.toPx()
        val path = Path().apply {
            val left = pad
            val right = size.width - pad
            val mid = size.height / 2f
            val swing = (size.height / 2f - pad).coerceAtLeast(0f)
            moveTo(left, mid + swing * 0.4f)
            cubicTo(
                left + (right - left) * 0.3f, mid - swing,
                left + (right - left) * 0.55f, mid - swing,
                left + (right - left) * 0.7f, mid + swing * 0.3f,
            )
            quadraticTo(left + (right - left) * 0.85f, mid + swing, right, mid)
        }
        val dash = stroke.coerceAtLeast(2f)
        val effect = when (linePattern) {
            1 -> PathEffect.dashPathEffect(floatArrayOf(0.1f, dash * 2f))
            2 -> PathEffect.dashPathEffect(floatArrayOf(dash * 3f, dash * 2f))
            3 -> PathEffect.dashPathEffect(floatArrayOf(dash * 3f, dash * 1.5f, 0.1f, dash * 1.5f))
            else -> null
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, pathEffect = effect))
    }
}

/** Where the thickness slider tops out: 얇게, 중간 or the tool's own 최대. */
@Composable
private fun WidthCeilings(mode: EditMode, range: ClosedFloatingPointRange<Float>, onCeiling: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("굵기 상한", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        for ((label, ceiling) in PenStore.widthCeilings(mode)) {
            SettingsChoiceChip(
                selected = kotlin.math.abs(range.endInclusive - ceiling) < 0.01f,
                onClick = { onCeiling(ceiling) },
                label = label,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** The tools whose strokes can be drawn as straight lines instead. */
internal val STRAIGHT_LINE_MODES = setOf(EditMode.PEN, EditMode.PENCIL, EditMode.HIGHLIGHTER, EditMode.MASK)
