package com.notesis

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * What a bug report needs from this app, and nothing from the notes: a stable
 * random id to quote, the build and device, and the tail of the crash log.
 */
internal object Diagnostics {
    fun id(context: Context): String {
        val prefs = context.getSharedPreferences("pens", Context.MODE_PRIVATE)
        return prefs.getString("diagnosticId", null) ?: UUID.randomUUID().toString().take(8).uppercase()
            .also { prefs.edit().putString("diagnosticId", it).apply() }
    }

    fun report(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val crash = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash.log")
        val tail = if (crash.isFile) crash.readText().takeLast(MAX_LOG_CHARS) else "(no crash log)"
        return buildString {
            appendLine("Diagnostic ID: ${id(context)}")
            appendLine("App: ${info?.versionName} (${info?.longVersionCode})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("---- Recent crash log ----")
            append(tail)
        }
    }

    fun share(context: Context) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Notesis diagnostics ${id(context)}")
            putExtra(Intent.EXTRA_TEXT, report(context))
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share diagnostics")) }
    }

    private const val MAX_LOG_CHARS = 20_000
}

/** The version and updates, then diagnostics to share, the id to quote, how to take the system's own report, and a short guide. */
@Composable
internal fun HelpDialog(
    onDismiss: () -> Unit,
    onGuide: (() -> Unit)? = null,
    /** Plays the first-run welcome again; null where it cannot play (animations off). */
    onWelcome: (() -> Unit)? = null,
    /** Shows the home marks again, and lets every note tip come back. */
    onTips: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val id = remember { Diagnostics.id(context) }
    var licenses by remember { mutableStateOf(false) }
    if (licenses) OpenSourceLicensesDialog(onDismiss = { licenses = false })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About & help") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AppVersionSection(onLicenses = { licenses = true })
                if (onGuide != null) {
                    TextButton(onClick = onGuide) { Text("Open the guide note") }
                }
                if (onTips != null) {
                    TextButton(onClick = onTips) { Text("Show the tips again") }
                }
                if (onWelcome != null) {
                    TextButton(onClick = onWelcome) { Text("Play the welcome again") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Diagnostic ID $id", style = MaterialTheme.typography.titleSmall)
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(id)) }) { Text("Copy ID") }
                    TextButton(onClick = { Diagnostics.share(context) }) { Text("Share diagnostics") }
                }
                Text("Diagnostics contain only the app version, the device and the recent crash log, never note content.",
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("System bug report", style = MaterialTheme.typography.titleSmall)
                Text("For a full device log, tap Build number seven times in Settings › About phone › Software information " +
                    "to turn on Developer options, then use Developer options › Take bug report.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Common actions", style = MaterialTheme.typography.titleSmall)
                Text(HELP_TEXT, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private val HELP_TEXT = """
• Double-tap with two fingers: undo · with three fingers: redo (change in Touch & gestures)
• Three fingers up: reference panel · down: close it
• Lasso: move, resize, rotate, copy, cut, paste, group, lock, link, add to library
• Draw a circle and hold: switch to lasso (turn on in Pen settings)
• Hold the pen still at the end of a stroke: it becomes a shape; keep moving to stretch it
• Pull past the last page: new page
• Ctrl+Z / Ctrl+Shift+Z undo and redo, Ctrl+1–9 tools, Ctrl+Alt+G go to page, Ctrl+F search
• Home: Ctrl+N new note, Ctrl+Shift+N new folder
""".trim()


/**
 * The measurements as text: the conditions they were taken in, then every
 * timing and counter. Built only when it is read - by the overlay a couple
 * of times a second, or once for the clipboard - never per input event.
 */
internal object Measurements {
    /**
     * Brings the numbers that are sampled rather than pushed up to date: the
     * process's allocation and GC counters, and the refresh period frames are
     * judged against. Called at readout cadence, so it never touches the
     * 120-240 Hz input path.
     */
    fun sample(view: InkCanvasView) {
        val stats = view.latency
        InteractionGovernor.noteRefreshRate(view.display?.refreshRate)
        stats.refreshPeriodNanos = (1_000_000_000.0 / InteractionGovernor.refreshHz).toLong()
        if (!stats.enabled) return
        val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
        val collections = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
        stats.addRuntimeSample(allocated, collections, System.nanoTime())
    }

    /** One line of what the numbers depend on, for telling one run from another. */
    fun conditions(view: InkCanvasView?): String {
        val hz = InteractionGovernor.refreshHz
        return String.format(
            Locale.US, "%s · %.0f Hz (%.1f ms) · battery saver %s · thermal %s · ink %s",
            InteractionGovernor.state.name.lowercase(Locale.US), hz, 1000f / hz,
            if (InteractionGovernor.powerSave) "on" else "off",
            thermalName(InteractionGovernor.thermalStatus),
            view?.wetInkPathLabel ?: "-",
        )
    }

    /** What the overlay shows. */
    fun overlay(view: InkCanvasView, zoomPercent: Int): String {
        sample(view)
        return conditions(view) + "\n" + view.latency.render(view.strokeCount()) + "\n" +
            WorkStats.render() + "\nzoom $zoomPercent%"
    }

    /**
     * Everything above plus the device, for pasting next to another run:
     * 60 Hz with battery saver off and on, after a long stretch in battery
     * saver, and at the panel's highest refresh rate.
     */
    fun report(context: Context, view: InkCanvasView?, zoomPercent: Int): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val runtime = Runtime.getRuntime()
        val mib = 1024L * 1024L
        return buildString {
            appendLine("Notesis measurements · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}")
            appendLine("App ${info?.versionName} (${info?.longVersionCode}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (view != null) sample(view)
            appendLine(conditions(view))
            appendLine("Thermal throttle on background work: ${if (StrokeBuilder.thermalThrottle) "yes" else "no"}")
            appendLine("Times marked submitted end when the frame is handed to the display, not when it lights up.")
            if (view == null || !view.latency.enabled) {
                appendLine("Timings: not collected (turn on the latency overlay first)")
            } else {
                appendLine(view.latency.render(view.strokeCount()))
            }
            appendLine(WorkStats.render())
            appendLine("memory ${(runtime.totalMemory() - runtime.freeMemory()) / mib} of ${runtime.maxMemory() / mib} MB" +
                " · zoom $zoomPercent%")
        }.trimEnd()
    }
}

/** One look at the numbers, taken once a second while the diagnostics screen is open. */
private class DiagnosticSnapshot(
    val measuring: Boolean,
    val metrics: List<Pair<String, Percentiles?>>,
    val extras: LatencyExtras?,
    val work: List<Pair<String, Long>>,
    val thermal: String,
    val throttled: Boolean,
    val memoryUsedMiB: Long,
    val memoryMaxMiB: Long,
    val strokes: Int?,
    val interaction: Interaction,
    val powerSave: Boolean,
    val refreshHz: Float,
    val inkPath: String?,
)

private val METRIC_LABELS = listOf(
    PerformanceMetric.FIRST_INK to "First ink (pen down to frame submitted)",
    PerformanceMetric.INPUT_HANDLED to "Input handled (event to end of handling)",
    PerformanceMetric.WET_INK to "Wet ink (event to frame submitted)",
    PerformanceMetric.PEN_FINALIZE to "Pen-up finish",
    PerformanceMetric.PAGE_APPLY to "Page apply (UI thread)",
    PerformanceMetric.SAVE_SNAPSHOT to "Save snapshot (UI thread)",
    PerformanceMetric.MESH_REFINE to "Mesh refine",
    PerformanceMetric.PDF_RENDER to "PDF render",
    PerformanceMetric.CANVAS_DRAW to "Page draw",
    PerformanceMetric.DENSE_DRAW to "Dense page draw (${LatencyStats.DENSE_PAGE_STROKES}+ strokes)",
)

/** The background-work counters the Reset button sets back to zero. */
private val WORK_COUNTERS
    get() = listOf(
        WorkStats.pagesLoaded, WorkStats.pagesLoadedStale, WorkStats.loadsAbandoned,
        WorkStats.refinesApplied, WorkStats.refinesStale, WorkStats.prefetched,
        WorkStats.pdfRenders, WorkStats.pdfDeferred, WorkStats.tilesRecorded,
        WorkStats.layerSettlesFull, WorkStats.layerSettlesTail,
        WorkStats.tilesDeferred, WorkStats.savePagesReused,
    )

private fun thermalName(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "Normal"
    PowerManager.THERMAL_STATUS_LIGHT -> "Light"
    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
    PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
    PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutting down"
    else -> "Unknown"
}

private fun takeSnapshot(context: Context, view: InkCanvasView?): DiagnosticSnapshot {
    val measuring = view?.latencyMonitoringEnabled == true
    view?.let(Measurements::sample)
    val runtime = Runtime.getRuntime()
    val mib = 1024L * 1024L
    val thermal = runCatching {
        context.getSystemService(PowerManager::class.java)?.currentThermalStatus
    }.getOrNull()
    return DiagnosticSnapshot(
        measuring = measuring,
        metrics = if (view != null && measuring) {
            METRIC_LABELS.map { (metric, label) -> label to view.latency.snapshot(metric) }
        } else {
            emptyList()
        },
        extras = if (view != null && measuring) view.latency.extras() else null,
        work = listOf(
            "Pages loaded" to WorkStats.pagesLoaded.get(),
            "Loads that arrived late" to WorkStats.pagesLoadedStale.get(),
            "Loads abandoned" to WorkStats.loadsAbandoned.get(),
            "Refines applied" to WorkStats.refinesApplied.get(),
            "Refines out of date" to WorkStats.refinesStale.get(),
            "Pages prefetched" to WorkStats.prefetched.get(),
            "PDF renders" to WorkStats.pdfRenders.get(),
            "PDF renders put off" to WorkStats.pdfDeferred.get(),
            "Ink tiles recorded" to WorkStats.tilesRecorded.get(),
            "Layer settles, whole" to WorkStats.layerSettlesFull.get(),
            "Layer settles, tail only" to WorkStats.layerSettlesTail.get(),
            "Ink tiles left for a later frame" to WorkStats.tilesDeferred.get(),
            "Unchanged pages reused by autosave" to WorkStats.savePagesReused.get(),
        ),
        thermal = thermal?.let(::thermalName) ?: "Not reported",
        throttled = StrokeBuilder.thermalThrottle,
        memoryUsedMiB = (runtime.totalMemory() - runtime.freeMemory()) / mib,
        memoryMaxMiB = runtime.maxMemory() / mib,
        // A sum over the pages' lists, once a second: cheap next to a frame.
        strokes = view?.strokeCount(),
        interaction = InteractionGovernor.state,
        powerSave = InteractionGovernor.powerSave,
        refreshHz = InteractionGovernor.refreshHz,
        inkPath = view?.wetInkPathLabel,
    )
}

private fun ms(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun Percentiles.summary(unit: String = "ms"): String =
    "${ms(p50)} · ${ms(p95)} · ${ms(p99)} $unit"

/**
 * What the app is doing, laid out to be read: the overlay switch, then the
 * timings, the background work and the device, each in its own card with
 * every number named. Re-read once a second while the screen is open.
 */
@Composable
internal fun DiagnosticsSettings(
    view: InkCanvasView?,
    overlay: Boolean,
    onOverlay: (Boolean) -> Unit,
    zoomPercent: Int,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DIAGNOSTICS_REFRESH_MS)
            tick++
        }
    }
    val snapshot = remember(tick, view, overlay) { takeSnapshot(context, view) }
    val refresh = LocalView.current.display?.refreshRate ?: snapshot.refreshHz
    val id = remember { Diagnostics.id(context) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            ?.let { "${it.versionName} (${it.longVersionCode})" } ?: "Unknown"
    }

    SettingsCard {
        SettingsSectionHeader("Measuring")
        SettingsToggle(
            "Latency overlay", overlay, onOverlay,
            hint = "Shows live timings over the page and collects the numbers below. " +
                "Measuring costs a little work, so leave it off when you are not looking.",
        )
    }
    SettingsCard {
        SettingsSectionHeader("Ink timings")
        if (!snapshot.measuring) {
            SettingsNote("Turn on the latency overlay to collect timings.")
        } else {
            SettingsNote("Submitted means the frame was handed to the display. The time until it lights up " +
                "is not measured here, so these are not motion-to-photon times.")
            DiagnosticRow("", "p50 · p95 · p99", header = true)
            snapshot.metrics.forEach { (label, stats) ->
                DiagnosticRow(label, stats?.let { "${it.summary()}  (${it.count})" } ?: "No samples yet")
            }
        }
    }
    SettingsCard {
        SettingsSectionHeader("Frames and input")
        val extras = snapshot.extras
        if (extras == null) {
            SettingsNote("Turn on the latency overlay to collect frame and input numbers.")
        } else {
            DiagnosticRow("Frame interval", extras.frame?.summary() ?: "No samples yet")
            DiagnosticRow("Missed frames",
                "${extras.framesMissed} of ${extras.framesCounted} (${missedPercentText(extras.framesMissed, extras.framesCounted)})" +
                    String.format(Locale.US, " · over %.1f ms", extras.refreshPeriodMs * LatencyStats.MISSED_FRAME_FACTOR))
            DiagnosticRow("Prediction lead", extras.prediction?.summary() ?: "No samples yet")
            DiagnosticRow("Pen input rate", String.format(Locale.US, "%.0f Hz", extras.inputRateHz))
            DiagnosticRow("Allocation", extras.allocationMiBPerSecond?.summary("MB/s") ?: "No samples yet")
            DiagnosticRow("Garbage collections", extras.gcEvents.toString())
            DiagnosticRow("Ink cache",
                String.format(Locale.US, "%d tiles · %.1f MB", extras.inkCacheTiles, extras.inkCacheMiB))
            DiagnosticRow("Strokes re-recorded last frame", extras.inkCacheFrameRecorded.toString())
            DiagnosticRow("Strokes on screen", extras.visibleStrokes.toString())
        }
    }
    SettingsCard {
        SettingsSectionHeader("Background work")
        SettingsNote("Counted since the app started or the last reset. A count that keeps climbing on a page nobody " +
            "is touching is work worth reporting.")
        snapshot.work.forEach { (label, count) -> DiagnosticRow(label, count.toString()) }
        TextButton(onClick = {
            // The timings too, so a comparison run starts from nothing.
            WORK_COUNTERS.forEach { it.set(0L) }
            view?.latency?.reset()
            tick++
        }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) { Text("Reset counters") }
    }
    SettingsCard {
        SettingsSectionHeader("Compare conditions")
        SettingsNote("Reset the counters, write and scroll for a few minutes, then copy. Do it at 60 Hz with " +
            "battery saver off, again with it on, once more after 15–30 minutes with it on, and at the " +
            "highest refresh rate, then paste the four side by side.")
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(Measurements.report(context, view, zoomPercent)))
        }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) { Text("Copy measurements") }
    }
    SettingsCard {
        SettingsSectionHeader("Device")
        DiagnosticRow("Temperature", snapshot.thermal)
        DiagnosticRow("Background work cut back", if (snapshot.throttled) "Yes, the device is warm" else "No")
        DiagnosticRow("Battery saver", if (snapshot.powerSave) "On" else "Off")
        DiagnosticRow("Display refresh", String.format(Locale.US, "%.0f Hz", refresh))
        DiagnosticRow("Interaction", snapshot.interaction.name.lowercase(Locale.US)
            .replaceFirstChar { it.titlecase(Locale.US) })
        DiagnosticRow("Wet ink path", snapshot.inkPath ?: "Not open")
        DiagnosticRow("App memory", "${snapshot.memoryUsedMiB} of ${snapshot.memoryMaxMiB} MB")
        DiagnosticRow("Strokes in this note", snapshot.strokes?.toString() ?: "Not open")
        DiagnosticRow("Zoom", "$zoomPercent%")
    }
    SettingsCard {
        SettingsSectionHeader("Bug report")
        DiagnosticRow("Diagnostic ID", id)
        DiagnosticRow("App", version)
        DiagnosticRow("Device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(id)) }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) {
                Text("Copy ID")
            }
            TextButton(onClick = { Diagnostics.share(context) }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) {
                Text("Share diagnostics")
            }
        }
        SettingsNote("Shared diagnostics hold the app version, the device and the recent error log, never note contents.")
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = style, modifier = Modifier.weight(1f).padding(end = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = style.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.End,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (header) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
    }
}

private const val DIAGNOSTICS_REFRESH_MS = 1000L
