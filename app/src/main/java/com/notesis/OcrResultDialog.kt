package com.notesis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What a selection of handwriting read as, before anything is done with it.
 *
 * The text can be corrected by hand. Each line that was read more than one
 * way - in another language, or as the recogniser's next guess - shows its
 * other readings beneath, the language beside each; picking one puts it in
 * that line's place. Copy takes the text as it stands; Convert puts it on
 * the page in place of the handwriting, so a misreading is fixed before the
 * ink is gone rather than after.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OcrResultDialog(
    lines: List<OcrLine>,
    onCopy: (String) -> Unit,
    onConvert: (String) -> Unit,
    onClose: () -> Unit,
    /** The text put on the page as a text box under the writing, which stays. */
    onPlace: ((String) -> Unit)? = null,
) {
    // Which reading each line uses, the first by default.
    val chosen = remember(lines) { mutableStateListOf(*Array(lines.size) { 0 }) }
    fun joined(): String = lines.indices.joinToString("\n") { lines[it].readings[chosen[it]].text }
    var text by remember(lines) { mutableStateOf(joined()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Handwriting recognition") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
            ) {
                if (lines.isEmpty()) {
                    Text("No text recognized")
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Recognized text") },
                    )
                    val alternatives = lines.withIndex().filter { it.value.readings.size > 1 }
                    if (alternatives.isNotEmpty()) {
                        Text("Other readings", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Picking one replaces that line; it starts the text again from the readings chosen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        alternatives.forEach { (index, line) ->
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
                                verticalArrangement = Arrangement.spacedBy(Ui.Space.xs),
                            ) {
                                line.readings.forEachIndexed { at, reading ->
                                    SettingsChoiceChip(
                                        selected = chosen[index] == at,
                                        onClick = {
                                            chosen[index] = at
                                            text = joined()
                                        },
                                        label = "${reading.text} · ${languageName(reading.language)}",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (lines.isNotEmpty()) TextButton(onClick = { onCopy(text) }) { Text("Copy") }
        },
        dismissButton = {
            Row {
                if (text.isNotBlank() && onPlace != null) TextButton(onClick = { onPlace(text) }) { Text("Add as text box") }
                if (text.isNotBlank()) TextButton(onClick = { onConvert(text) }) { Text("Convert to text") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

/** How a recognition language is shown: its own name, as the settings offer it. */
private fun languageName(tag: String): String =
    InkIndexer.OFFERED.firstOrNull { it.first == tag }?.second ?: tag

