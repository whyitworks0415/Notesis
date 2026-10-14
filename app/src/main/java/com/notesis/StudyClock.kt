package com.notesis

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How often the app's one clock ticker runs. */
internal const val CLOCK_TICK_MS = 250L

internal enum class ClockTab(val label: String) {
    TIMER("타이머"),
    STOPWATCH("스톱워치"),
    ALARM("알람"),
    STUDY("순공"),
}

/** An alarm at [hour]:[minute], next due at [nextAt] while [enabled]. */
internal data class ClockAlarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val daily: Boolean,
    val enabled: Boolean,
    val nextAt: Long,
)

/** An alarm that has gone off and is waiting to be acknowledged. */
internal data class RingingAlarm(val hour: Int, val minute: Int, val at: Long)

/**
 * The clock: a timer, a stopwatch, alarms, and study time.
 *
 * None of it makes a sound. A timer that runs out or an alarm that comes due
 * is shown on screen until it is acknowledged, wherever in the app that is.
 * Everything is kept as wall-clock moments rather than as counting threads,
 * so leaving a note, or the app being closed, loses nothing: a timer still
 * ends when it was going to, and is shown as ended on return.
 *
 * Study time (순공) is counted automatically while a note is open, the app
 * is in front, and the pen or a finger has touched the screen within the last
 * few minutes; or by hand, with a start and a stop. It is kept per day, and
 * shown either as today's (reset each day) or as one running total.
 */
internal object StudyClock {

    private var prefs: SharedPreferences? = null

    /** Wall-clock now, moved on by [tick]. The clock's screens read time only from here. */
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    // ---- on screen ------------------------------------------------------------

    var panelOpen by mutableStateOf(false)
    var tab by mutableStateOf(ClockTab.TIMER)

    /** Whether the small clock button stays on screen when nothing is running. */
    var buttonShown by mutableStateOf(true)
        private set

    /** Where the button sits, as a fraction of the free space across and down. */
    var buttonX by mutableFloatStateOf(1f)
        private set
    var buttonY by mutableFloatStateOf(0.9f)
        private set

    // ---- timer ----------------------------------------------------------------

    var timerDurationMs by mutableLongStateOf(25 * MINUTE)
        private set

    /** When a running timer ends; zero when it is not running. */
    var timerEndAt by mutableLongStateOf(0L)
        private set

    /** What is left while the timer is not running. */
    var timerLeftMs by mutableLongStateOf(25 * MINUTE)
        private set

    var timerDone by mutableStateOf(false)
        private set

    val timerRunning: Boolean get() = timerEndAt != 0L
    val timerPaused: Boolean get() = !timerRunning && !timerDone && timerLeftMs in 1 until timerDurationMs

    fun timerRemaining(at: Long = now): Long =
        if (timerRunning) (timerEndAt - at).coerceAtLeast(0L) else timerLeftMs

    // ---- stopwatch --------------------------------------------------------------

    var stopwatchBaseMs by mutableLongStateOf(0L)
        private set

    /** When the stopwatch was last started; zero while it is stopped. */
    var stopwatchStartedAt by mutableLongStateOf(0L)
        private set

    /** Each lap as the stopwatch's total at the moment it was taken. */
    var laps by mutableStateOf<List<Long>>(emptyList())
        private set

    val stopwatchRunning: Boolean get() = stopwatchStartedAt != 0L

    fun stopwatchElapsed(at: Long = now): Long =
        stopwatchBaseMs + if (stopwatchRunning) (at - stopwatchStartedAt).coerceAtLeast(0L) else 0L

    // ---- alarms ------------------------------------------------------------------

    var alarms by mutableStateOf<List<ClockAlarm>>(emptyList())
        private set
    var ringing by mutableStateOf<List<RingingAlarm>>(emptyList())
        private set

    // ---- study time ------------------------------------------------------------

    var autoStudy by mutableStateOf(true)
        private set
    var dailyReset by mutableStateOf(true)
        private set

    /** Minutes without any touch after which automatic counting stops. */
    var idleMinutes by mutableIntStateOf(3)
        private set
    var manualRunning by mutableStateOf(false)
        private set

    /** Study time per day, keyed by ISO date. */
    var days by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    /** Study time since the running total was last reset. */
    var totalMs by mutableLongStateOf(0L)
        private set

    /** Whether study time is being counted at this moment. */
    var measuring by mutableStateOf(false)
        private set

    /** Set from the screen: a note is what is open. */
    @Volatile var noteOpen = false

    /** Set from the activity: the app is in front. */
    @Volatile var foreground = true

    @Volatile private var lastInput = SystemClock.elapsedRealtime()
    private var lastTickMono = 0L
    private var lastManualWall = 0L
    private var unsavedMs = 0L

    fun studyToday(at: Long = now): Long = days[dayKey(at)] ?: 0L

    /** What the study clock shows: today's time, or the running total when it does not reset. */
    fun studyShown(at: Long = now): Long = if (dailyReset) studyToday(at) else totalMs

    // ---- setup and the tick ----------------------------------------------------

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("clock", Context.MODE_PRIVATE)
        prefs = p
        runCatching { load(p) }
    }

    /** Reads the clock's settings again, after a settings file was loaded over them. */
    fun reload(context: Context) {
        val p = prefs ?: context.getSharedPreferences("clock", Context.MODE_PRIVATE).also { prefs = it }
        runCatching { load(p) }
    }

    /** A touch or a hovering pen: the person is at work. Called for every event, so only a write. */
    fun touched() {
        lastInput = SystemClock.elapsedRealtime()
    }

    fun tick() {
        val wall = System.currentTimeMillis()
        val mono = SystemClock.elapsedRealtime()
        now = wall
        // A long gap between ticks is a sleeping device or a stopped app,
        // not time spent; automatic counting never bridges one.
        val dt = if (lastTickMono == 0L) 0L else (mono - lastTickMono).let { if (it in 0..MAX_TICK_GAP_MS) it else 0L }
        lastTickMono = mono

        if (timerRunning && wall >= timerEndAt) {
            timerEndAt = 0L
            timerLeftMs = 0L
            timerDone = true
            saveTimer()
        }
        checkAlarms(wall)

        if (manualRunning) {
            // By hand, the clock runs on real time - away from the app as well.
            val from = lastManualWall.takeIf { it in 1..wall } ?: wall
            if (wall > from) addStudy(from, wall)
            lastManualWall = wall
            measuring = true
        } else {
            val active = autoStudy && noteOpen && foreground && mono - lastInput <= idleMinutes * MINUTE
            measuring = active
            if (active && dt > 0) addStudy(wall - dt, wall)
        }
        if (unsavedMs >= SAVE_EVERY_MS) saveStudy()
    }

    fun save() {
        saveStudy()
        saveTimer()
        saveStopwatch()
        saveAlarms()
    }

    fun openPanel(at: ClockTab? = null) {
        if (at != null) tab = at
        panelOpen = true
    }

    // ---- button ------------------------------------------------------------------

    fun updateButtonShown(shown: Boolean) {
        buttonShown = shown
        prefs?.edit()?.putBoolean("buttonShown", shown)?.apply()
    }

    fun moveButton(x: Float, y: Float) {
        buttonX = x.coerceIn(0f, 1f)
        buttonY = y.coerceIn(0f, 1f)
    }

    fun saveButtonPlace() {
        prefs?.edit()?.putFloat("buttonX", buttonX)?.putFloat("buttonY", buttonY)?.apply()
    }

    // ---- timer -------------------------------------------------------------------

    fun setTimerDuration(ms: Long) {
        if (timerRunning) return
        timerDurationMs = ms.coerceIn(SECOND, 24 * HOUR - SECOND)
        timerLeftMs = timerDurationMs
        timerDone = false
        saveTimer()
    }

    fun startTimer() {
        if (timerRunning) return
        if (timerDone || timerLeftMs <= 0L) timerLeftMs = timerDurationMs
        timerDone = false
        timerEndAt = System.currentTimeMillis() + timerLeftMs
        saveTimer()
    }

    fun pauseTimer() {
        if (!timerRunning) return
        timerLeftMs = (timerEndAt - System.currentTimeMillis()).coerceAtLeast(0L)
        timerEndAt = 0L
        saveTimer()
    }

    fun resetTimer() {
        timerEndAt = 0L
        timerLeftMs = timerDurationMs
        timerDone = false
        saveTimer()
    }

    // ---- stopwatch -----------------------------------------------------------------

    fun startStopwatch() {
        if (stopwatchRunning) return
        stopwatchStartedAt = System.currentTimeMillis()
        saveStopwatch()
    }

    fun pauseStopwatch() {
        if (!stopwatchRunning) return
        stopwatchBaseMs = stopwatchElapsed(System.currentTimeMillis())
        stopwatchStartedAt = 0L
        saveStopwatch()
    }

    fun lap() {
        if (!stopwatchRunning) return
        laps = laps + stopwatchElapsed(System.currentTimeMillis())
        saveStopwatch()
    }

    fun resetStopwatch() {
        stopwatchBaseMs = 0L
        stopwatchStartedAt = 0L
        laps = emptyList()
        saveStopwatch()
    }

    // ---- alarms ----------------------------------------------------------------------

    fun addAlarm(hour: Int, minute: Int, daily: Boolean) {
        val wall = System.currentTimeMillis()
        alarms = (alarms + ClockAlarm(wall, hour, minute, daily, true, nextAlarmAt(hour, minute, wall)))
            .sortedWith(compareBy({ it.hour }, { it.minute }))
        saveAlarms()
    }

    fun setAlarmEnabled(id: Long, enabled: Boolean) = updateAlarm(id) {
        it.copy(enabled = enabled, nextAt = nextAlarmAt(it.hour, it.minute, System.currentTimeMillis()))
    }

    fun setAlarmDaily(id: Long, daily: Boolean) = updateAlarm(id) { it.copy(daily = daily) }

    fun removeAlarm(id: Long) {
        alarms = alarms.filterNot { it.id == id }
        saveAlarms()
    }

    fun dismissRinging() {
        ringing = emptyList()
        saveAlarms()
    }

    private fun updateAlarm(id: Long, change: (ClockAlarm) -> ClockAlarm) {
        alarms = alarms.map { if (it.id == id) change(it) else it }
        saveAlarms()
    }

    private fun checkAlarms(wall: Long) {
        if (alarms.none { it.enabled && wall >= it.nextAt }) return
        val rung = mutableListOf<RingingAlarm>()
        alarms = alarms.map { alarm ->
            if (!alarm.enabled || wall < alarm.nextAt) return@map alarm
            // Due while the app was away for hours is not worth announcing
            // late; it simply moves on.
            if (wall - alarm.nextAt <= STALE_ALARM_MS) rung += RingingAlarm(alarm.hour, alarm.minute, alarm.nextAt)
            if (alarm.daily) alarm.copy(nextAt = nextAlarmAt(alarm.hour, alarm.minute, wall))
            else alarm.copy(enabled = false)
        }
        if (rung.isNotEmpty()) ringing = ringing + rung
        saveAlarms()
    }

    // ---- study time ------------------------------------------------------------------

    fun updateAutoStudy(auto: Boolean) {
        if (auto && manualRunning) stopManual()
        autoStudy = auto
        prefs?.edit()?.putBoolean("autoStudy", auto)?.apply()
    }

    fun updateDailyReset(reset: Boolean) {
        dailyReset = reset
        prefs?.edit()?.putBoolean("dailyReset", reset)?.apply()
    }

    fun updateIdleMinutes(minutes: Int) {
        idleMinutes = minutes.coerceIn(1, 60)
        prefs?.edit()?.putInt("idleMinutes", idleMinutes)?.apply()
    }

    fun startManual() {
        if (manualRunning) return
        manualRunning = true
        lastManualWall = System.currentTimeMillis()
        saveStudy()
    }

    fun stopManual() {
        if (!manualRunning) return
        val wall = System.currentTimeMillis()
        if (wall > lastManualWall) addStudy(lastManualWall, wall)
        manualRunning = false
        measuring = false
        saveStudy()
    }

    /** Today's time back to zero when the clock resets daily, the running total otherwise. */
    fun resetStudy() {
        if (dailyReset) days = days - dayKey(System.currentTimeMillis()) else totalMs = 0L
        saveStudy()
    }

    private fun addStudy(from: Long, to: Long) {
        val split = splitByDay(from, to)
        if (split.isEmpty()) return
        val next = days.toMutableMap()
        var added = 0L
        for ((day, ms) in split) {
            next[day] = (next[day] ?: 0L) + ms
            added += ms
        }
        days = next
        totalMs += added
        unsavedMs += added
    }

    // ---- storage ------------------------------------------------------------------------

    private fun load(p: SharedPreferences) {
        buttonShown = p.getBoolean("buttonShown", true)
        buttonX = p.getFloat("buttonX", 1f)
        buttonY = p.getFloat("buttonY", 0.9f)

        timerDurationMs = p.getLong("timerDuration", 25 * MINUTE)
        timerEndAt = p.getLong("timerEndAt", 0L)
        timerLeftMs = p.getLong("timerLeft", timerDurationMs)
        timerDone = p.getBoolean("timerDone", false)

        stopwatchBaseMs = p.getLong("swBase", 0L)
        stopwatchStartedAt = p.getLong("swStartedAt", 0L)
        laps = runCatching {
            val array = JSONArray(p.getString("laps", "[]"))
            (0 until array.length()).map { array.getLong(it) }
        }.getOrDefault(emptyList())

        alarms = runCatching {
            val array = JSONArray(p.getString("alarms", "[]"))
            (0 until array.length()).map { index ->
                val o = array.getJSONObject(index)
                ClockAlarm(o.getLong("id"), o.getInt("h"), o.getInt("m"), o.optBoolean("daily"),
                    o.optBoolean("on"), o.optLong("next"))
            }
        }.getOrDefault(emptyList())
        ringing = runCatching {
            val array = JSONArray(p.getString("ringing", "[]"))
            (0 until array.length()).map { index ->
                val o = array.getJSONObject(index)
                RingingAlarm(o.getInt("h"), o.getInt("m"), o.getLong("at"))
            }
        }.getOrDefault(emptyList())

        autoStudy = p.getBoolean("autoStudy", true)
        dailyReset = p.getBoolean("dailyReset", true)
        idleMinutes = p.getInt("idleMinutes", 3)
        manualRunning = p.getBoolean("manualRunning", false)
        lastManualWall = p.getLong("manualWall", 0L)
        totalMs = p.getLong("studyTotal", 0L)
        days = runCatching {
            val json = JSONObject(p.getString("studyDays", "{}") ?: "{}")
            json.keys().asSequence().associateWith { json.getLong(it) }
        }.getOrDefault(emptyMap())
    }

    private fun saveTimer() {
        prefs?.edit()
            ?.putLong("timerDuration", timerDurationMs)
            ?.putLong("timerEndAt", timerEndAt)
            ?.putLong("timerLeft", timerLeftMs)
            ?.putBoolean("timerDone", timerDone)
            ?.apply()
    }

    private fun saveStopwatch() {
        prefs?.edit()
            ?.putLong("swBase", stopwatchBaseMs)
            ?.putLong("swStartedAt", stopwatchStartedAt)
            ?.putString("laps", JSONArray(laps).toString())
            ?.apply()
    }

    private fun saveAlarms() {
        val p = prefs ?: return
        val list = JSONArray(alarms.map {
            JSONObject().put("id", it.id).put("h", it.hour).put("m", it.minute)
                .put("daily", it.daily).put("on", it.enabled).put("next", it.nextAt)
        })
        val rung = JSONArray(ringing.map { JSONObject().put("h", it.hour).put("m", it.minute).put("at", it.at) })
        p.edit().putString("alarms", list.toString()).putString("ringing", rung.toString()).apply()
    }

    private fun saveStudy() {
        val p = prefs ?: return
        unsavedMs = 0L
        // Two months of days is plenty to look back on; older ones go.
        val kept = days.entries.sortedByDescending { it.key }.take(KEPT_DAYS)
        val json = JSONObject()
        for ((day, ms) in kept) json.put(day, ms)
        p.edit()
            .putString("studyDays", json.toString())
            .putLong("studyTotal", totalMs)
            .putBoolean("manualRunning", manualRunning)
            .putLong("manualWall", lastManualWall)
            .apply()
    }

    const val SECOND = 1_000L
    const val MINUTE = 60 * SECOND
    const val HOUR = 60 * MINUTE
    private const val MAX_TICK_GAP_MS = 2_000L
    private const val SAVE_EVERY_MS = 15 * SECOND
    private const val STALE_ALARM_MS = 6 * HOUR
    private const val KEPT_DAYS = 62
}

/** The ISO date of [at] where the person is. */
internal fun dayKey(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()

/** [from] to [to] cut at each midnight, as time per day: an evening that runs past twelve counts on both days. */
internal fun splitByDay(from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, Long>> {
    if (to <= from) return emptyList()
    val parts = ArrayList<Pair<String, Long>>(1)
    var start = from
    while (start < to) {
        val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(to, nextMidnight)
        parts += day.toString() to (end - start)
        start = end
    }
    return parts
}

/** The next [hour]:[minute] strictly after [after]: today if it is still ahead, tomorrow if not. */
internal fun nextAlarmAt(hour: Int, minute: Int, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val moment = Instant.ofEpochMilli(after).atZone(zone)
    val today = moment.toLocalDate().atTime(hour, minute).atZone(zone)
    val due = if (today.toInstant().toEpochMilli() > after) today
        else moment.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone)
    return due.toInstant().toEpochMilli()
}

/** 25:00, or 1:05:09 past the hour. */
internal fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) + 999L) / 1000L
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Time spent, to the second and always with hours: 0:12:05. */
internal fun formatElapsed(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    return "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
}

/** 01:23.45, or 1:01:23.45 past the hour: a stopwatch counts in hundredths. */
internal fun formatStopwatch(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val h = clamped / 3_600_000
    val m = clamped % 3_600_000 / 60_000
    val s = clamped % 60_000 / 1000
    val cs = clamped % 1000 / 10
    return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, cs) else "%02d:%02d.%02d".format(m, s, cs)
}

/** Study time as hours and minutes, the way a day of study is spoken of. */
internal fun formatStudyShort(ms: Long): String {
    val minutes = ms.coerceAtLeast(0L) / 60_000
    return "%d:%02d".format(minutes / 60, minutes % 60)
}

/** The seven days ending [today], oldest first, for the study chart. */
internal fun lastSevenDays(today: LocalDate): List<LocalDate> = (6 downTo 0).map { today.minusDays(it.toLong()) }
