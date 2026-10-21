
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
)

private val METRIC_LABELS = listOf(
    PerformanceMetric.WET_INK to "Wet ink (pen to screen)",
    PerformanceMetric.PEN_FINALIZE to "Pen-up finish",
    PerformanceMetric.MESH_REFINE to "Mesh refine",
    PerformanceMetric.PDF_RENDER to "PDF render",
    PerformanceMetric.CANVAS_DRAW to "Page draw",
    PerformanceMetric.DENSE_DRAW to "Dense page draw (${LatencyStats.DENSE_PAGE_STROKES}+ strokes)",
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
        ),
        thermal = thermal?.let(::thermalName) ?: "Not reported",
        throttled = StrokeBuilder.thermalThrottle,
        memoryUsedMiB = (runtime.totalMemory() - runtime.freeMemory()) / mib,
        memoryMaxMiB = runtime.maxMemory() / mib,
        // A sum over the pages' lists, once a second: cheap next to a frame.
        strokes = view?.strokeCount(),
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
    val refresh = LocalView.current.display?.refreshRate
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
            DiagnosticRow("Frame time", extras.frame?.summary() ?: "No samples yet")
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
        SettingsNote("Counted since the app started. A count that keeps climbing on a page nobody " +
            "is touching is work worth reporting.")
        snapshot.work.forEach { (label, count) -> DiagnosticRow(label, count.toString()) }
        TextButton(onClick = {
            listOf(
                WorkStats.pagesLoaded, WorkStats.pagesLoadedStale, WorkStats.loadsAbandoned,
                WorkStats.refinesApplied, WorkStats.refinesStale, WorkStats.prefetched,
                WorkStats.pdfRenders, WorkStats.pdfDeferred, WorkStats.tilesRecorded,
                WorkStats.layerSettlesFull, WorkStats.layerSettlesTail,
            ).forEach { it.set(0L) }
            tick++
        }, modifier = Modifier.heightIn(min = 44.dp)) { Text("Reset counters") }
    }
    SettingsCard {
        SettingsSectionHeader("Device")
        DiagnosticRow("Temperature", snapshot.thermal)
        DiagnosticRow("Background work cut back", if (snapshot.throttled) "Yes, the device is warm" else "No")
        DiagnosticRow("Display refresh", refresh?.let { String.format(Locale.US, "%.0f Hz", it) } ?: "Unknown")
        DiagnosticRow("App memory", "${snapshot.memoryUsedMiB} of ${snapshot.memoryMaxMiB} MB")
        DiagnosticRow("Strokes in this note", snapshot.strokes?.toString() ?: "Not open")
        DiagnosticRow("Zoom", "$zoomPercent%")
    }
    SettingsCard {
        SettingsSectionHeader("Bug report")
        DiagnosticRow("Diagnostic ID", id)
        DiagnosticRow("App", version)
        DiagnosticRow("Device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(id)) }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Copy ID")
            }
            TextButton(onClick = { Diagnostics.share(context) }, modifier = Modifier.heightIn(min = 44.dp)) {
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
