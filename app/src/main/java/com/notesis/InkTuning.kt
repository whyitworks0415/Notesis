package com.notesis

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The handwriting's inner numbers and switches, for trying out how writing
 * feels on a tablet: shown in Diagnostics once developer mode is on.
 *
 * Read on the pen path, so each is a plain field - no Compose state, no
 * allocation - written only from the settings. Every default is what the app
 * does without developer mode; "Back to the defaults" puts them all back.
 */
internal object InkTuning {
    private const val PREFS = "ink-tuning"

    /** Developer mode: the panel below shown in Diagnostics. Compose state, read by the settings only. */
    var developerMode by mutableStateOf(false)
        private set

    /**
     * Strokes just written are folded into their tile's picture by painting
     * the tile again on a worker on the CPU. Off (the default), they are
     * folded on the GPU the way they were first drawn, so a stroke never
     * changes on screen a moment after it was written.
     */
    @Volatile var foldInBackground = false
    /** A page coming on screen or a new zoom has its tiles painted on workers rather than on the GPU in the frame. */
    @Volatile var backgroundRaster = true
    /** At pen-up the tiny hook at the start or end of a stroke, and a pause held still, are tidied away. */
    @Volatile var tidyAtLift = true
    /** A one-pixel surface of its own posts a frame every vsync while the pen writes, so the screen keeps its rate (see [PenPulseSurface]). */
    @Volatile var heartbeat = true
    /** Motion blur's writing streak on every screen, not only at 60 Hz. */
    @Volatile var streakAtAllRates = false
    /** The CPU asked to run fast while writing (see WritingBoost). */
    @Volatile var writingBoost = true
    /** How much of the frame the tiles' ink may take follows how far frames fall behind (see [RenderLoad]). */
    @Volatile var renderLoadFeedback = true
    /**
     * How finely strokes are built, as a multiple of the usual mesh error: 1 is
     * the app's own, below 1 finer outlines (bigger meshes), above 1 coarser.
     */
    @Volatile var meshDetail = 1f
    /** The share of a frame new tiles may be recorded in, as a multiple of the app's own. */
    @Volatile var tileBudget = 1f

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        developerMode = p.getBoolean("developerMode", false)
        foldInBackground = p.getBoolean("foldInBackground", false)
        backgroundRaster = p.getBoolean("backgroundRaster", true)
        tidyAtLift = p.getBoolean("tidyAtLift", true)
        heartbeat = p.getBoolean("heartbeat", true)
        streakAtAllRates = p.getBoolean("streakAtAllRates", false)
        writingBoost = p.getBoolean("writingBoost", true)
        renderLoadFeedback = p.getBoolean("renderLoadFeedback", true)
        meshDetail = p.getFloat("meshDetail", 1f).coerceIn(MESH_DETAIL_RANGE)
        tileBudget = p.getFloat("tileBudget", 1f).coerceIn(TILE_BUDGET_RANGE)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("developerMode", developerMode)
            .putBoolean("foldInBackground", foldInBackground)
            .putBoolean("backgroundRaster", backgroundRaster)
            .putBoolean("tidyAtLift", tidyAtLift)
            .putBoolean("heartbeat", heartbeat)
            .putBoolean("streakAtAllRates", streakAtAllRates)
            .putBoolean("writingBoost", writingBoost)
            .putBoolean("renderLoadFeedback", renderLoadFeedback)
            .putFloat("meshDetail", meshDetail)
            .putFloat("tileBudget", tileBudget)
            .apply()
    }

    fun setDeveloperMode(context: Context, on: Boolean) {
        developerMode = on
        save(context)
    }

    /** Every number and switch back to the app's own; developer mode stays as it is. */
    fun reset(context: Context) {
        foldInBackground = false
        backgroundRaster = true
        tidyAtLift = true
        heartbeat = true
        streakAtAllRates = false
        writingBoost = true
        renderLoadFeedback = true
        meshDetail = 1f
        tileBudget = 1f
        save(context)
    }

    val MESH_DETAIL_RANGE = 0.25f..4f
    val TILE_BUDGET_RANGE = 0.25f..4f
}

/** The mesh error for a stroke at the usual [base] error, scaled by developer mode's mesh detail [detail]. */
internal fun tunedEpsilon(base: Float, detail: Float): Float = base * detail.coerceIn(InkTuning.MESH_DETAIL_RANGE)

/**
 * Developer mode's handwriting panel. [penControls] holds the pen's own
 * settings that are already in the pen panel - prediction, smoothing,
 * pressure, the way wet ink is shown - so they can be tried from here too.
 */
@Composable
internal fun InkTuningSettings(penControls: @Composable () -> Unit) {
    val context = LocalContext.current
    // A count bumped on each change, so the switches below are drawn again from the plain fields.
    var changes by remember { mutableIntStateOf(0) }
    changes
    fun changed(apply: () -> Unit) {
        apply()
        InkTuning.save(context)
        changes++
    }
    SettingsCard {
        SettingsSectionHeader("Developer mode")
        SettingsToggle("Developer mode", InkTuning.developerMode, { InkTuning.setDeveloperMode(context, it) },
            hint = "Shows the handwriting's inner numbers and switches below, to try how writing feels. " +
                "Off, they keep what was set.")
    }
    if (!InkTuning.developerMode) return
    SettingsCard {
        SettingsSectionHeader("Handwriting parameters")
        SettingsNote("Each takes effect from the next stroke. If writing gets worse, Back to the defaults puts every one back.")
        penControls()
        SettingsSectionHeader("After the pen lifts")
        SettingsToggle("Fold new ink in the background", InkTuning.foldInBackground,
            { on -> changed { InkTuning.foldInBackground = on } },
            hint = "On, strokes just written are painted again on the CPU a moment later and swapped in, which can " +
                "change how they look slightly. Off, they stay as they were drawn.")
        SettingsToggle("Tidy the ends at pen-up", InkTuning.tidyAtLift, { on -> changed { InkTuning.tidyAtLift = on } },
            hint = "Takes away the tiny hook a pen makes as it touches down or lifts, and a pause held still. " +
                "Off, the stroke stays exactly as written, hooks and all.")
        SettingsSectionHeader("While writing")
        SettingsToggle("Keep the screen at full rate", InkTuning.heartbeat, { on -> changed { InkTuning.heartbeat = on } },
            hint = "Draws one pixel every frame while the pen is down, so the screen does not slow down and show the ink in steps.")
        SettingsToggle("Writing streak at every refresh rate", InkTuning.streakAtAllRates,
            { on -> changed { InkTuning.streakAtAllRates = on } },
            hint = "Motion blur's streak ahead of the pen, which is drawn only on 60 Hz screens. It redraws the window every pen frame.")
        SettingsToggle("Speed up the CPU while writing", InkTuning.writingBoost, { on -> changed { InkTuning.writingBoost = on } },
            hint = "Asks the system to run the pen's threads fast while a stroke is written.")
        SettingsSectionHeader("Ink on the page")
        TuningSlider("Mesh detail", InkTuning.meshDetail, InkTuning.MESH_DETAIL_RANGE,
            "1 is the app's own. Lower builds finer outlines (smoother curves, more work); higher, coarser ones.") { v ->
            changed { InkTuning.meshDetail = v }
        }
        TuningSlider("Tile budget per frame", InkTuning.tileBudget, InkTuning.TILE_BUDGET_RANGE,
            "How much of a frame new tiles may take. Higher fills a page in sooner and can stutter; lower is smoother and slower to fill.") { v ->
            changed { InkTuning.tileBudget = v }
        }
        SettingsToggle("Paint tiles in the background", InkTuning.backgroundRaster,
            { on -> changed { InkTuning.backgroundRaster = on } },
            hint = "A page coming on screen or a new zoom is painted on workers. Off, every tile is drawn on the GPU in the frame.")
        SettingsToggle("Ink load follows dropped frames", InkTuning.renderLoadFeedback,
            { on -> changed { InkTuning.renderLoadFeedback = on } },
            hint = "When frames fall behind, fewer strokes are drawn into tiles each frame. Off, always the full amount.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { changed { InkTuning.reset(context) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Back to the defaults")
            }
        }
    }
}

/** A labelled slider for one multiple, with its value and what it does. */
@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    note: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("%.2f×".format(value), style = MaterialTheme.typography.labelLarge)
        }
        SkinSlider(value = value, onValueChange = onChange, valueRange = range, step = 0.05f)
        SettingsNote(note)
    }
}

/**
 * The pen in hand's own settings, as the pen panel holds them, offered among
 * developer mode's parameters: how far ahead the stroke is guessed, how much
 * it is smoothed, and how pressure reaches it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PenTuningControls(
    pen: PenPreset,
    gestures: CanvasGestures,
    onPen: (PenPreset) -> Unit,
    onGestures: (CanvasGestures) -> Unit,
) {
    SettingsSectionHeader("The pen in hand")
    Text("Prediction", style = MaterialTheme.typography.bodyLarge)
    val leads = listOf(0 to "Off", LEAD_AUTO to "Auto", 4 to "4 ms", 8 to "8 ms", MAX_LEAD_MS to "$MAX_LEAD_MS ms")
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Ui.Space.s)) {
        leads.forEach { (ms, label) ->
            SettingsChoiceChip(selected = pen.leadMs == ms, onClick = { onPen(pen.copy(leadMs = ms)) }, label = label)
        }
    }
    SettingsNote("How far ahead of the pen the stroke is guessed while writing. More hides more delay and can overshoot at corners.")
    TuningIntSlider("Smoothing", pen.stabilizer, 0..100,
        "How much the line is evened out as it is written. 0 is the pen as it moves.") { onPen(pen.copy(stabilizer = it)) }
    SettingsToggle("Smoothing by a sliding window", gestures.windowedStabilizer,
        { onGestures(gestures.copy(windowedStabilizer = it)) },
        hint = "An even average of the last points instead of the filter that follows the pen's speed.")
    TuningIntSlider("Pressure response", gestures.pressureResponse, 0..40,
        "$PRESSURE_RESPONSE_DEFAULT is the pen as it reports pressure; 0 ignores it; higher needs a firmer press for full width.") {
        onGestures(gestures.copy(pressureResponse = it))
    }
}

/** A labelled whole-number slider with its value and what it does. */
@Composable
private fun TuningIntSlider(label: String, value: Int, range: IntRange, note: String, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$value", style = MaterialTheme.typography.labelLarge)
        }
        SkinSlider(value = value.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(), step = 1f)
        SettingsNote(note)
    }
}
