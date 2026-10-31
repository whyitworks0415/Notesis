package com.notesis

import androidx.compose.ui.graphics.graphicsLayer

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
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
            Planner.open?.let { ToolPanelHost(it) }
        }
    }
}

internal val TabularDigits = TextStyle(fontFeatureSettings = "tnum")

// ---- the pill ------------------------------------------------------------------------

@Composable
private fun BoxWithConstraintsScope.ClockPill(kind: PillKind) {
    val clock = StudyClock
    val scheme = MaterialTheme.colorScheme
    val margin = with(LocalDensity.current) { 16.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pillCenter = remember { arrayOfNulls<Offset>(1) }
    val freeWidth = (constraints.maxWidth - size.width - 2 * margin).coerceAtLeast(1f)
    val freeHeight = (constraints.maxHeight - size.height - 2 * margin).coerceAtLeast(1f)
    val free by rememberUpdatedState(Offset(freeWidth, freeHeight))
    val alert = kind == PillKind.ALARM || kind == PillKind.TIMER_DONE
    val shape = Ui.Shape.pill
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
            // The panel grows out of the pill when the pill opens it.
            .onGloballyPositioned { pillCenter[0] = it.localToScreen(Offset(it.size.width / 2f, it.size.height / 2f)) }
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
            .clickable {
                pillCenter[0]?.let { PenPopupAnchor.tapped(it) }
                clock.openPanel(tabOf(kind))
            }
            .height(44.dp)
            .then(if (kind == PillKind.IDLE) Modifier.width(44.dp) else Modifier.padding(start = 14.dp, end = if (alert) 6.dp else 16.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.s, Alignment.CenterHorizontally),
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
                PillKind.STUDY -> "Study " + formatStudyShort(clock.studyShown(clock.now))
                PillKind.STOPWATCH -> formatElapsed(clock.stopwatchElapsed(clock.now))
                PillKind.TIMER -> formatClock(clock.timerRemaining(clock.now))
                PillKind.TIMER_DONE -> "Timer done"
                PillKind.ALARM -> clock.ringing.firstOrNull()?.let { first ->
                    "Alarm %02d:%02d".format(first.hour, first.minute) +
                        if (clock.ringing.size > 1) " +${clock.ringing.size - 1}" else ""
                } ?: "Alarm"
                PillKind.IDLE -> ""
            }
        }
    }
    when (kind) {
        PillKind.IDLE -> Icon(Reicons.Clock, contentDescription = "Clock", modifier = Modifier.size(Ui.Icon.regular))
        PillKind.STUDY -> {
            Box(Modifier.size(8.dp).clip(CircleShape).background(StudyGreen))
            Text(label, style = style)
        }
        PillKind.STOPWATCH -> {
            Icon(Reicons.Timer, contentDescription = null, modifier = Modifier.size(Ui.Icon.small))
            Text(label, style = style)
        }
        PillKind.TIMER -> {
            Icon(Reicons.Timer, contentDescription = null, modifier = Modifier.size(Ui.Icon.small))
            Text(label, style = style, modifier = Modifier.alpha(if (clock.timerPaused) 0.55f else 1f))
        }
        PillKind.TIMER_DONE -> {
            Icon(Reicons.Timer, contentDescription = null, modifier = Modifier.size(Ui.Icon.small))
            Text(label, style = style)
            ConfirmChip { clock.resetTimer() }
        }
        PillKind.ALARM -> {
            Icon(Reicons.Alarm, contentDescription = null, modifier = Modifier.size(Ui.Icon.small))
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
    // In a layer of its own, the pulse redraws this ring and nothing else.
    // Drawn on the window's root list it re-recorded the page backdrop and
    // every glass pane, every frame, for as long as the alert went unseen.
    return graphicsLayer().drawBehind {
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
            .clip(Ui.Shape.pill)
            .background(scheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = Ui.Space.m),
        contentAlignment = Alignment.Center,
    ) {
        Text("OK", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
    }
}

// ---- the panel -----------------------------------------------------------------------

/** The tools panel's clock: the timer, the stopwatch, the alarms and study time. */
@Composable
internal fun ClockSection() {
    val clock = StudyClock
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
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
        "Clock button on screen",
        "Timers and alarms still show on screen when this is off",
        clock.buttonShown,
    ) { clock.updateButtonShown(it) }
}

// ---- timer ---------------------------------------------------------------------------

/** The longest timer the slider sets, in minutes. */
internal const val TIMER_SLIDER_MAX_MINUTES = 180L

/**
 * The timer's length, in whole minutes, at [position] 0 to 1 along its
 * slider. The square of the position, so the first quarter of the track
 * covers a quarter of an hour minute by minute and the rest climbs to three
 * hours; past the hour it moves in fives.
 */
internal fun timerSliderMinutes(position: Float): Long {
    val p = position.coerceIn(0f, 1f)
    val minutes = 1.0 + (TIMER_SLIDER_MAX_MINUTES - 1) * p * p
    val whole = kotlin.math.round(minutes).toLong()
    return if (whole > 60) (kotlin.math.round(minutes / 5.0) * 5).toLong().coerceAtMost(TIMER_SLIDER_MAX_MINUTES) else whole
}

/** Where [minutes] sits along the timer's slider: [timerSliderMinutes] the other way. */
internal fun timerSliderPosition(minutes: Long): Float {
    val m = minutes.coerceIn(1L, TIMER_SLIDER_MAX_MINUTES).toDouble()
    return kotlin.math.sqrt((m - 1.0) / (TIMER_SLIDER_MAX_MINUTES - 1)).toFloat()
}

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
                    clock.timerDone -> "Done"
                    clock.timerPaused -> "Paused"
                    clock.timerRunning -> "Ends at ${clockTime(clock.timerEndAt)}"
                    else -> " "
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
    if (idle) {
        // One slider for the length: fine in the first minutes, coarser past the hour.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
            SkinSlider(
                value = timerSliderPosition(duration / StudyClock.MINUTE),
                onValueChange = { clock.setTimerDuration(timerSliderMinutes(it) * StudyClock.MINUTE) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${duration / StudyClock.MINUTE} min",
                style = MaterialTheme.typography.labelLarge.merge(TabularDigits),
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 64.dp),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
        ClockButton("Reset", primary = false, enabled = !idle, modifier = Modifier.weight(1f)) { clock.resetTimer() }
        when {
            clock.timerDone -> ClockButton("OK", primary = true, modifier = Modifier.weight(1f)) { clock.resetTimer() }
            clock.timerRunning -> ClockButton("Pause", primary = true, modifier = Modifier.weight(1f)) { clock.pauseTimer() }
            else -> ClockButton(if (clock.timerPaused) "Resume" else "Start", primary = true, modifier = Modifier.weight(1f)) {
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
        // About 30 a second reads as running hundredths; every display frame
        // redrew the glass card under it at 120Hz.
        while (running) { frameNow = System.currentTimeMillis(); kotlinx.coroutines.delay(33) }
    }
    val elapsed = clock.stopwatchElapsed(if (running) frameNow else clock.now)
    Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
        Text(formatStopwatch(elapsed), style = MaterialTheme.typography.displayMedium.merge(TabularDigits))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
        if (running) {
            ClockButton("Lap", primary = false, modifier = Modifier.weight(1f)) { clock.lap() }
            ClockButton("Stop", primary = true, modifier = Modifier.weight(1f)) { clock.pauseStopwatch() }
        } else {
            ClockButton("Reset", primary = false, enabled = elapsed > 0, modifier = Modifier.weight(1f)) { clock.resetStopwatch() }
            ClockButton(if (elapsed > 0) "Resume" else "Start", primary = true, modifier = Modifier.weight(1f)) { clock.startStopwatch() }
        }
    }
    if (clock.laps.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            val laps = clock.laps
            for (index in laps.indices.reversed()) {
                val split = laps[index] - (laps.getOrNull(index - 1) ?: 0L)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Lap ${index + 1}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
        "Alarms make no sound. At the set time they show on screen and stay until you tap OK.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    if (clock.alarms.isEmpty() && !adding) {
        Text("No alarms", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
    clock.alarms.forEach { alarm -> AlarmRow(alarm, clock.now) }
    if (adding) {
        AlarmEditor(
            onAdd = { hour, minute, daily -> clock.addAlarm(hour, minute, daily); adding = false },
            onCancel = { adding = false },
        )
    } else {
        ClockButton("+ Add alarm", primary = false, modifier = Modifier.fillMaxWidth()) { adding = true }
    }
}

@Composable
private fun AlarmRow(alarm: ClockAlarm, now: Long) {
    val clock = StudyClock
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        Column(Modifier.weight(1f).alpha(if (alarm.enabled) 1f else 0.45f)) {
            Text("%02d:%02d".format(alarm.hour, alarm.minute), style = MaterialTheme.typography.headlineMedium.merge(TabularDigits))
            Text(
                (if (alarm.daily) "Daily" else "Once") +
                    if (alarm.enabled) " · " + untilText(alarm.nextAt - now) else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        ClockChip("Daily", alarm.daily) { clock.setAlarmDaily(alarm.id, !alarm.daily) }
        SkinSwitch(checked = alarm.enabled, onCheckedChange = { clock.setAlarmEnabled(alarm.id, it) })
        IconButton(onClick = { clock.removeAlarm(alarm.id) }) {
            Icon(Reicons.Delete, contentDescription = "Delete alarm", modifier = Modifier.size(Ui.Icon.small))
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
            .clip(Ui.Shape.card)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(Ui.Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Ui.Space.m),
    ) {
        TimeInput(state = state)
        Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            ClockChip("Once", !daily) { daily = false }
            ClockChip("Daily", daily) { daily = true }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
            ClockButton("Cancel", primary = false, modifier = Modifier.weight(1f), onClick = onCancel)
            ClockButton("Add", primary = true, modifier = Modifier.weight(1f)) { onAdd(state.hour, state.minute, daily) }
        }
    }
}

/** 3시간 12분 후, or 5분 후. */
private fun untilText(ms: Long): String {
    val minutes = ((ms.coerceAtLeast(0L) + 59_999L) / 60_000L)
    return if (minutes >= 60) "in ${minutes / 60} h ${minutes % 60} min" else "in ${minutes} min"
}

// ---- study time ------------------------------------------------------------------------

@Composable
private fun StudyTab() {
    val clock = StudyClock
    val now = clock.now
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (clock.dailyReset) "Study time today" else "Total study time",
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
                    clock.manualRunning -> "Measuring"
                    clock.measuring -> "Measuring · counts automatically while you write in a note"
                    clock.autoStudy && clock.noteOpen -> "Resting · no input for over ${clock.idleMinutes} min"
                    clock.autoStudy -> "Starts automatically when you open a note"
                    else -> "Stopped"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
    if (!clock.autoStudy) {
        ClockButton(
            if (clock.manualRunning) "Stop" else "Start",
            primary = true,
            modifier = Modifier.fillMaxWidth(),
        ) { if (clock.manualRunning) clock.stopManual() else clock.startManual() }
    }
    WeekBars(clock.days, now)

    SettingRow(
        "Measure automatically",
        "Counts only while a note is open and you use the pen or a finger. Turn off to start and stop by hand",
        clock.autoStudy,
    ) { clock.updateAutoStudy(it) }
    if (clock.autoStudy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pause after no input for", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            listOf(1, 3, 5, 10).forEach { minutes ->
                Spacer(Modifier.width(6.dp))
                ClockChip("${minutes} min", clock.idleMinutes == minutes) { clock.updateIdleMinutes(minutes) }
            }
        }
    }
    SettingRow(
        "Reset every day",
        "Starts again from 0 at midnight. Turn off to keep adding up until you reset it",
        clock.dailyReset,
    ) { clock.updateDailyReset(it) }
    ClockButton(
        when {
            confirmReset -> "Tap again to reset"
            clock.dailyReset -> "Reset today's time"
            else -> "Reset total time"
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
    Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
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
                        .clip(Ui.Shape.badge)
                        .background(if (day == today) primary else primary.copy(alpha = 0.32f)),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    day.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.ENGLISH),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day == today) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

internal val StudyGreen = Color(0xFF34C759)

/** HH:mm of a wall-clock moment, where the person is. */
private fun clockTime(at: Long): String {
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}

// ---- small parts ----------------------------------------------------------------------

@Composable
internal fun ClockButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(Ui.Size.touch)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(Ui.Shape.pill)
            .background(if (primary) scheme.primary else scheme.onSurface.copy(alpha = 0.07f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (primary) scheme.onPrimary else scheme.onSurface)
    }
}

@Composable
internal fun ClockChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(36.dp)
            // The chip corner every other chip in the app has.
            .clip(Ui.Shape.chip)
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
internal fun SettingRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.width(10.dp))
        SkinSwitch(checked = checked, onCheckedChange = onChange)
    }
}
