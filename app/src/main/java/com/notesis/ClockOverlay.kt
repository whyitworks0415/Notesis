package com.notesis

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** What the floating clock shows, most urgent first. */
private enum class PillKind { ALARM, TIMER_DONE, TIMER, STOPWATCH, STUDY, IDLE }

/**
 * The clock over a screen: a small glass pill that can be dragged anywhere
 * and opens the clock panel when tapped.
 *
 * The pill is the indicator too. A running or finished timer and a ringing
 * alarm always show on it, with 확인 to acknowledge, even when the button
 * itself has been turned off; a running stopwatch and study time being
 * counted show on it while it is on. Nothing here makes a sound.
 *
 * Laid over a screen that provides [LocalLiquidGlassBackdrop], the pill and
 * the panel bend that screen's own recording - no second capture of the page.
 */
@Composable
internal fun ClockOverlay(modifier: Modifier = Modifier) {
    val clock = StudyClock
    val kind by remember {
        derivedStateOf {
            when {
                clock.ringing.isNotEmpty() -> PillKind.ALARM
                clock.timerDone -> PillKind.TIMER_DONE
                clock.timerRunning || clock.timerPaused -> PillKind.TIMER
                !clock.buttonShown -> null
                clock.stopwatchRunning -> PillKind.STOPWATCH
                clock.measuring -> PillKind.STUDY
                else -> PillKind.IDLE
            }
        }
    }
    // Text and icons in the skin's own ink, light or dark, whatever the screen underneath set.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier.fillMaxSize()) {
            kind?.let { current ->
                // Kept clear of the status bar and any cutout, as the bars are.
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(ChromeInsets)) { ClockPill(current) }
            }
            if (clock.panelOpen) ClockPanel()
        }
    }
}

private val TabularDigits = TextStyle(fontFeatureSettings = "tnum")

// ---- the pill ------------------------------------------------------------------------

@Composable
private fun BoxWithConstraintsScope.ClockPill(kind: PillKind) {
    val clock = StudyClock
    val scheme = MaterialTheme.colorScheme
    val margin = with(LocalDensity.current) { 16.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val freeWidth = (constraints.maxWidth - size.width - 2 * margin).coerceAtLeast(1f)
    val freeHeight = (constraints.maxHeight - size.height - 2 * margin).coerceAtLeast(1f)
    val free by rememberUpdatedState(Offset(freeWidth, freeHeight))
    val alert = kind == PillKind.ALARM || kind == PillKind.TIMER_DONE
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            // Read while placing, so dragging moves the pill without recomposing it.
            .offset {
                IntOffset(
                    (margin + clock.buttonX * freeWidth).roundToInt(),
                    (margin + clock.buttonY * freeHeight).roundToInt(),
                )
            }
            .onSizeChanged { size = it }
            .then(if (alert) Modifier.alertGlow(scheme.primary) else Modifier)
            .liquidGlass(
                shape = shape,
                surfaceColor = if (alert) scheme.primaryContainer else scheme.surface,
                surfaceOpacity = if (alert) 0.92f else 0.72f,
                useSpotiStyle = false,
            )
            .clip(shape)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { clock.saveButtonPlace() }) { change, drag ->
                    change.consume()
                    clock.moveButton(clock.buttonX + drag.x / free.x, clock.buttonY + drag.y / free.y)
                }
            }
            .clickable { clock.openPanel(tabOf(kind)) }
            .height(44.dp)
            .then(if (kind == PillKind.IDLE) Modifier.width(44.dp) else Modifier.padding(start = 14.dp, end = if (alert) 6.dp else 16.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        CompositionLocalProvider(LocalContentColor provides if (alert) scheme.onPrimaryContainer else scheme.onSurface) {
            PillContent(kind)
        }
    }
}

/**
 * What the pill says. Its own scope, and each reading of the clock behind a
 * derived state, so the four ticks a second recompose it only when the text
 * itself changes - once a second for a timer, once a minute for study time.
 */
@Composable
private fun PillContent(kind: PillKind) {
    val clock = StudyClock
    val style = MaterialTheme.typography.titleSmall.merge(TabularDigits)
    val label by remember(kind) {
        derivedStateOf {
            when (kind) {
                PillKind.STUDY -> "순공 " + formatStudyShort(clock.studyShown(clock.now))
                PillKind.STOPWATCH -> formatElapsed(clock.stopwatchElapsed(clock.now))
                PillKind.TIMER -> formatClock(clock.timerRemaining(clock.now))
                PillKind.TIMER_DONE -> "타이머 끝"
                PillKind.ALARM -> clock.ringing.firstOrNull()?.let { first ->
                    "알람 %02d:%02d".format(first.hour, first.minute) +
                        if (clock.ringing.size > 1) " 외 ${clock.ringing.size - 1}" else ""
                } ?: "알람"
                PillKind.IDLE -> ""
            }
        }
    }
    when (kind) {
        PillKind.IDLE -> Icon(Reicons.Clock, contentDescription = "시계", modifier = Modifier.size(22.dp))
        PillKind.STUDY -> {
            Box(Modifier.size(8.dp).clip(CircleShape).background(StudyGreen))
            Text(label, style = style)
        }
        PillKind.STOPWATCH -> {
            Icon(Reicons.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = style)
        }
        PillKind.TIMER -> {
            Icon(Reicons.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = style, modifier = Modifier.alpha(if (clock.timerPaused) 0.55f else 1f))
        }
        PillKind.TIMER_DONE -> {
            Icon(Reicons.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = style)
            ConfirmChip { clock.resetTimer() }
        }
        PillKind.ALARM -> {
            Icon(Reicons.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = style)
            ConfirmChip { clock.dismissRinging() }
        }
    }
}

/**
 * A ring that breathes around a finished timer or a ringing alarm - the
 * silent stand-in for a sound. Only composed while there is something to
 * acknowledge, so a quiet pill runs no animation at all.
 */
@Composable
private fun Modifier.alertGlow(color: Color): Modifier {
    val pulse = rememberInfiniteTransition(label = "clock alert").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "clock alert glow",
    )
    return drawBehind {
        val grow = 3.dp.toPx()
        drawRoundRect(
            color = color.copy(alpha = pulse.value * 0.6f),
            topLeft = Offset(-grow, -grow),
            size = Size(size.width + grow * 2, size.height + grow * 2),
            cornerRadius = CornerRadius(size.height / 2 + grow),
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }
}

private fun tabOf(kind: PillKind): ClockTab? = when (kind) {
    PillKind.ALARM -> ClockTab.ALARM
    PillKind.TIMER, PillKind.TIMER_DONE -> ClockTab.TIMER
    PillKind.STOPWATCH -> ClockTab.STOPWATCH
    PillKind.STUDY -> ClockTab.STUDY
    PillKind.IDLE -> null
}

@Composable
private fun ConfirmChip(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("확인", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
    }
}

// ---- the panel -----------------------------------------------------------------------

@Composable
private fun ClockPanel() {
    val clock = StudyClock
    BackHandler { clock.panelOpen = false }
    // A tap past the panel closes it.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.06f))
            .pointerInput(Unit) { detectTapGestures { clock.panelOpen = false } },
    )
    // Kept above the keyboard, which the alarm's time fields bring up.
    Box(Modifier.fillMaxSize().windowInsetsPadding(ChromeInsets).imePadding()) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(16.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .liquidGlass(
                shape = shape,
                surfaceColor = MaterialTheme.colorScheme.surface,
                surfaceOpacity = 0.84f,
                useSpotiStyle = false,
            )
            .clip(shape)
            // Taps on the panel's own background stay on the panel.
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(rememberScrollState())
            .animateContentSize()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("시계", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { clock.panelOpen = false }) { Icon(Reicons.Close, contentDescription = "닫기") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockTab.entries.forEach { option ->
                ClockChip(option.label, clock.tab == option, Modifier.weight(1f)) { clock.tab = option }
            }
        }
        when (clock.tab) {
            ClockTab.TIMER -> TimerTab()
            ClockTab.STOPWATCH -> StopwatchTab()
            ClockTab.ALARM -> AlarmTab()
            ClockTab.STUDY -> StudyTab()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingRow(
            "화면에 시계 버튼",
            "꺼도 타이머와 알람은 화면에 표시됩니다",
            clock.buttonShown,
        ) { clock.setButtonShown(it) }
    }
    }
}

// ---- timer ---------------------------------------------------------------------------

@Composable
private fun TimerTab() {
    val clock = StudyClock
    val duration = clock.timerDurationMs
    val remaining = clock.timerRemaining(clock.now)
    val idle = !clock.timerRunning && !clock.timerPaused && !clock.timerDone
    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
        TimerRing(
            progress = when {
                clock.timerDone -> 1f
                idle -> 1f
                else -> remaining.toFloat() / duration.coerceAtLeast(1L)
            },
            done = clock.timerDone,
            modifier = Modifier.size(206.dp),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                formatClock(if (idle) duration else remaining),
                style = MaterialTheme.typography.displaySmall.merge(TabularDigits),
            )
            Text(
                when {
                    clock.timerDone -> "끝"
                    clock.timerPaused -> "일시정지"
                    clock.timerRunning -> "${clockTime(clock.timerEndAt)}에 끝"
                    else -> " "
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
    if (idle) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 3, 5, 10, 15, 25, 30, 45, 60).forEach { minutes ->
                ClockChip("${minutes}분", duration == minutes * StudyClock.MINUTE) {
                    clock.setTimerDuration(minutes * StudyClock.MINUTE)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-5 to "−5분", -1 to "−1분", 1 to "+1분", 5 to "+5분").forEach { (minutes, label) ->
                ClockChip(label, false, Modifier.weight(1f)) {
                    clock.setTimerDuration(duration + minutes * StudyClock.MINUTE)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ClockButton("초기화", primary = false, enabled = !idle, modifier = Modifier.weight(1f)) { clock.resetTimer() }
        when {
            clock.timerDone -> ClockButton("확인", primary = true, modifier = Modifier.weight(1f)) { clock.resetTimer() }
            clock.timerRunning -> ClockButton("일시정지", primary = true, modifier = Modifier.weight(1f)) { clock.pauseTimer() }
            else -> ClockButton(if (clock.timerPaused) "계속" else "시작", primary = true, modifier = Modifier.weight(1f)) {
                clock.startTimer()
            }
        }
    }
}

/** What is left as a ring that empties clockwise from twelve. */
@Composable
private fun TimerRing(progress: Float, done: Boolean, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val fill = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val width = 10.dp.toPx()
        val inset = width / 2
        val arcSize = Size(size.width - width, size.height - width)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(width))
        drawArc(
            if (done) fill.copy(alpha = 0.55f) else fill,
            -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize,
            style = Stroke(width, cap = StrokeCap.Round),
        )
    }
}

// ---- stopwatch -----------------------------------------------------------------------

@Composable
private fun StopwatchTab() {
    val clock = StudyClock
    val running = clock.stopwatchRunning
    // Hundredths only move smoothly at the display's own pace, and only
    // while this is open and running.
    var frameNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) withFrameMillis { frameNow = System.currentTimeMillis() }
    }
    val elapsed = clock.stopwatchElapsed(if (running) frameNow else clock.now)
    Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
        Text(formatStopwatch(elapsed), style = MaterialTheme.typography.displayMedium.merge(TabularDigits))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (running) {
            ClockButton("랩", primary = false, modifier = Modifier.weight(1f)) { clock.lap() }
            ClockButton("정지", primary = true, modifier = Modifier.weight(1f)) { clock.pauseStopwatch() }
        } else {
            ClockButton("초기화", primary = false, enabled = elapsed > 0, modifier = Modifier.weight(1f)) { clock.resetStopwatch() }
            ClockButton(if (elapsed > 0) "계속" else "시작", primary = true, modifier = Modifier.weight(1f)) { clock.startStopwatch() }
        }
    }
    if (clock.laps.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val laps = clock.laps
            for (index in laps.indices.reversed()) {
                val split = laps[index] - (laps.getOrNull(index - 1) ?: 0L)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("랩 ${index + 1}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(formatStopwatch(split), style = MaterialTheme.typography.bodyMedium.merge(TabularDigits),
                        color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(16.dp))
                    Text(formatStopwatch(laps[index]), style = MaterialTheme.typography.bodyMedium.merge(TabularDigits))
                }
            }
        }
    }
}

// ---- alarms --------------------------------------------------------------------------

@Composable
private fun AlarmTab() {
    val clock = StudyClock
    var adding by remember { mutableStateOf(false) }
    Text(
        "소리는 나지 않습니다. 시각이 되면 화면에 표시되고, 확인을 누를 때까지 남아 있습니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    if (clock.alarms.isEmpty() && !adding) {
        Text("알람이 없습니다", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
    clock.alarms.forEach { alarm -> AlarmRow(alarm, clock.now) }
    if (adding) {
        AlarmEditor(
            onAdd = { hour, minute, daily -> clock.addAlarm(hour, minute, daily); adding = false },
            onCancel = { adding = false },
        )
    } else {
        ClockButton("+ 알람 추가", primary = false, modifier = Modifier.fillMaxWidth()) { adding = true }
    }
}

@Composable
private fun AlarmRow(alarm: ClockAlarm, now: Long) {
    val clock = StudyClock
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).alpha(if (alarm.enabled) 1f else 0.45f)) {
            Text("%02d:%02d".format(alarm.hour, alarm.minute), style = MaterialTheme.typography.headlineMedium.merge(TabularDigits))
            Text(
                (if (alarm.daily) "매일" else "한 번") +
                    if (alarm.enabled) " · " + untilText(alarm.nextAt - now) else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        ClockChip("매일", alarm.daily) { clock.setAlarmDaily(alarm.id, !alarm.daily) }
        SkinSwitch(checked = alarm.enabled, onCheckedChange = { clock.setAlarmEnabled(alarm.id, it) })
        IconButton(onClick = { clock.removeAlarm(alarm.id) }) {
            Icon(Reicons.Delete, contentDescription = "알람 삭제", modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(onAdd: (Int, Int, Boolean) -> Unit, onCancel: () -> Unit) {
    val start = remember { LocalTime.now().plusMinutes(5) }
    val state = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
    var daily by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TimeInput(state = state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockChip("한 번", !daily) { daily = false }
            ClockChip("매일", daily) { daily = true }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ClockButton("취소", primary = false, modifier = Modifier.weight(1f), onClick = onCancel)
            ClockButton("추가", primary = true, modifier = Modifier.weight(1f)) { onAdd(state.hour, state.minute, daily) }
        }
    }
}

/** 3시간 12분 후, or 5분 후. */
private fun untilText(ms: Long): String {
    val minutes = ((ms.coerceAtLeast(0L) + 59_999L) / 60_000L)
    return if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분 후" else "${minutes}분 후"
}

// ---- study time ------------------------------------------------------------------------

@Composable
private fun StudyTab() {
    val clock = StudyClock
    val now = clock.now
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (clock.dailyReset) "오늘 순공 시간" else "누적 순공 시간",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(formatElapsed(clock.studyShown(now)), style = MaterialTheme.typography.displayMedium.merge(TabularDigits))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (clock.measuring) StudyGreen else MaterialTheme.colorScheme.outlineVariant),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    clock.manualRunning -> "측정 중"
                    clock.measuring -> "측정 중 · 노트에 쓰는 동안 자동으로 잽니다"
                    clock.autoStudy && clock.noteOpen -> "쉬는 중 · ${clock.idleMinutes}분 넘게 입력이 없습니다"
                    clock.autoStudy -> "노트를 열면 자동으로 잽니다"
                    else -> "멈춤"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
    if (!clock.autoStudy) {
        ClockButton(
            if (clock.manualRunning) "정지" else "시작",
            primary = true,
            modifier = Modifier.fillMaxWidth(),
        ) { if (clock.manualRunning) clock.stopManual() else clock.startManual() }
    }
    WeekBars(clock.days, now)

    SettingRow(
        "자동 측정",
        "노트가 열려 있고 펜이나 손가락 입력이 있는 동안만 셉니다. 끄면 시작·정지로 직접 잽니다",
        clock.autoStudy,
    ) { clock.setAutoStudy(it) }
    if (clock.autoStudy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("입력 없이 멈추기까지", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            listOf(1, 3, 5, 10).forEach { minutes ->
                Spacer(Modifier.width(6.dp))
                ClockChip("${minutes}분", clock.idleMinutes == minutes) { clock.setIdleMinutes(minutes) }
            }
        }
    }
    SettingRow(
        "하루마다 초기화",
        "자정에 0부터 다시 셉니다. 끄면 직접 초기화할 때까지 계속 쌓입니다",
        clock.dailyReset,
    ) { clock.setDailyReset(it) }
    ClockButton(
        when {
            confirmReset -> "한 번 더 누르면 초기화"
            clock.dailyReset -> "오늘 기록 초기화"
            else -> "누적 기록 초기화"
        },
        primary = false,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (confirmReset) {
            clock.resetStudy()
            confirmReset = false
        } else confirmReset = true
    }
}

/** The last seven days of study, today on the right and in full colour. */
@Composable
private fun WeekBars(days: Map<String, Long>, now: Long) {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val week = lastSevenDays(today)
    val values = week.map { days[it.toString()] ?: 0L }
    val most = values.maxOrNull()?.coerceAtLeast(StudyClock.HOUR) ?: StudyClock.HOUR
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        week.forEachIndexed { index, day ->
            val value = values[index]
            Column(
                Modifier.weight(1f).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    if (value > 0) formatStudyShort(value) else "",
                    style = MaterialTheme.typography.labelSmall.merge(TabularDigits),
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .height((64f * value / most).coerceAtLeast(3f).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (day == today) primary else primary.copy(alpha = 0.32f)),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    day.dayOfWeek.getDisplayName(DateTextStyle.NARROW, Locale.KOREAN),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day == today) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private val StudyGreen = Color(0xFF34C759)

/** HH:mm of a wall-clock moment, where the person is. */
private fun clockTime(at: Long): String {
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}

// ---- small parts ----------------------------------------------------------------------

@Composable
private fun ClockButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(24.dp))
            .background(if (primary) scheme.primary else scheme.onSurface.copy(alpha = 0.07f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (primary) scheme.onPrimary else scheme.onSurface)
    }
}

@Composable
private fun ClockChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.secondaryContainer else scheme.onSurface.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
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
private fun SettingRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.width(10.dp))
        SkinSwitch(checked = checked, onCheckedChange = onChange)
    }
}
