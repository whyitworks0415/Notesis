package com.notesis

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The zooms always offered, in percent of fit-to-width. */
internal val ZOOM_PRESETS = listOf(100, 200, 400, 1000)

/** The range a typed zoom is held to; the top is the canvas's own limit, [InkCanvasView]'s MAX_ZOOM. */
internal val ZOOM_PERCENT_RANGE = 10..1000

/**
 * What tapping the zoom reading opens: the fixed steps, the ones saved here,
 * and a field for any other. [onPick] gets a percentage.
 */
@Composable
internal fun ZoomPresetDialog(current: Float, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    var saved by remember { mutableStateOf(store.zoomPresets) }
    var typed by remember { mutableStateOf("") }
    val currentPercent = (current * 100).roundToInt()
    val value = typed.trim().removeSuffix("%").toIntOrNull()?.takeIf { it in ZOOM_PERCENT_RANGE }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zoom · now $currentPercent%") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ZOOM_PRESETS.forEach { percent ->
                        SettingsChoiceChip(
                            selected = percent == currentPercent,
                            onClick = { onPick(percent) },
                            label = "$percent%",
                            modifier = Modifier.padding(end = Ui.Space.s),
                        )
                    }
                }
                if (saved.isNotEmpty()) {
                    Text("Saved", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        saved.forEach { percent ->
                            SettingsChoiceChip(
                                selected = percent == currentPercent,
                                onClick = { onPick(percent) },
                                label = "$percent%",
                            )
                            IconButton(
                                onClick = { saved = saved - percent; store.zoomPresets = saved },
                                modifier = Modifier.size(32.dp),
                            ) { Icon(Reicons.Close, contentDescription = "Remove $percent%", Modifier.size(16.dp)) }
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { text -> typed = text.filter { it.isDigit() }.take(4) },
                    label = { Text("Custom (${ZOOM_PERCENT_RANGE.first}–${ZOOM_PERCENT_RANGE.last}%)") },
                    singleLine = true,
                    isError = typed.isNotEmpty() && value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { value?.let(onPick) }),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = value != null, onClick = { value?.let(onPick) }) { Text("Apply") }
                    TextButton(
                        enabled = value != null && value !in ZOOM_PRESETS && value !in saved,
                        onClick = {
                            val v = value ?: return@TextButton
                            saved = (saved + v).sorted()
                            store.zoomPresets = saved
                            typed = ""
                        },
                    ) { Text("Save value") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
