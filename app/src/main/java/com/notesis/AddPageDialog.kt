package com.notesis

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A paper size a new page can take, in page units (150 per inch: half the
 * pixels at 300 dpi), upright. [SAME] keeps the page it is added after.
 */
internal enum class NewPageSize(val label: String, val width: Float, val height: Float) {
    SAME("Same as this page", 0f, 0f),
    A4("A4", 1240f, 1754f),
    A5("A5", 874f, 1240f),
    B5("B5", 1075f, 1518f),
    LETTER("Letter", 1275f, 1650f),
    SQUARE("Square 1:1", 1240f, 1240f),
    RATIO_3_4("3:4", 1240f, 1653f),
    RATIO_9_16("9:16 (16:9 turned)", 987f, 1754f),
    CUSTOM("Custom", 0f, 0f),
}

/** What the paper of a new page is: the page's own, a plain kind, or an image template. */
internal sealed class NewPagePaper {
    data object Same : NewPagePaper()
    data class Plain(val background: PageBackground) : NewPagePaper()
    data class Template(val id: String) : NewPagePaper()
}

/** What [AddPageDialog] hands back, sizes in page units. */
internal data class NewPageSpec(
    val width: Float?,
    val height: Float?,
    val paper: NewPagePaper,
    val atEnd: Boolean,
)

/** The size of [size] turned [landscape] or not, in page units; null for the page's own. */
internal fun newPageDimensions(size: NewPageSize, landscape: Boolean, customW: Float?, customH: Float?): Pair<Float, Float>? {
    val (w, h) = when (size) {
        NewPageSize.SAME -> return null
        NewPageSize.CUSTOM -> (customW ?: return null) to (customH ?: return null)
        else -> size.width to size.height
    }
    val upright = minOf(w, h) to maxOf(w, h)
    return if (size == NewPageSize.CUSTOM) w to h
        else if (landscape) upright.second to upright.first else upright
}


/**
 * Adds a page: its size and which way up, its paper, and where it goes. Opens
 * on what was chosen last; "Same as this page" for both copies the page it
 * goes after, as adding a page always did.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddPageDialog(
    userTemplates: List<UserPageTemplate>,
    onDismiss: () -> Unit,
    onAdd: (NewPageSpec) -> Unit,
    templateFile: (String) -> java.io.File,
    onRenameTemplate: (String, String) -> Boolean,
    onDeleteTemplate: (String) -> Boolean = { false },
    onMoveTemplate: (String, Int) -> Boolean = { _, _ -> false },
    /** The note the page goes in: its paper, once chosen, is what it offers next time. */
    noteId: String = "",
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pens", Context.MODE_PRIVATE) }
    var size by remember {
        mutableStateOf(runCatching { NewPageSize.valueOf(prefs.getString("addPageSize", "SAME")!!) }
            .getOrDefault(NewPageSize.SAME))
    }
    var landscape by remember { mutableStateOf(prefs.getBoolean("addPageLandscape", false)) }
    var customW by remember { mutableStateOf(prefs.getString("addPageCustomW", "2480")!!) }
    var customH by remember { mutableStateOf(prefs.getString("addPageCustomH", "3508")!!) }
    var paper by remember {
        val saved = prefs.getString("addPagePaper:$noteId", null) ?: prefs.getString("addPagePaper", "SAME")!!
        mutableStateOf<NewPagePaper>(when {
            saved == "SAME" -> NewPagePaper.Same
            saved.startsWith("T:") -> saved.removePrefix("T:").let { id ->
                if (userTemplates.any { it.id == id }) NewPagePaper.Template(id) else NewPagePaper.Same
            }
            else -> runCatching { NewPagePaper.Plain(PageBackground.valueOf(saved)) }.getOrDefault(NewPagePaper.Same)
        })
    }
    var atEnd by remember { mutableStateOf(prefs.getBoolean("addPageAtEnd", false)) }
    val cw = customW.toIntOrNull()?.takeIf { it in PAGE_PX_RANGE }
    val ch = customH.toIntOrNull()?.takeIf { it in PAGE_PX_RANGE }
    val ready = size != NewPageSize.CUSTOM || (cw != null && ch != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add page") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                Text("Size", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
                    verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                    NewPageSize.entries.forEach { choice ->
                        SettingsChoiceChip(selected = size == choice, onClick = { size = choice }, label = choice.label)
                    }
                }
                if (size != NewPageSize.SAME && size != NewPageSize.CUSTOM) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                        SettingsChoiceChip(selected = !landscape, onClick = { landscape = false }, label = "Portrait")
                        SettingsChoiceChip(selected = landscape, onClick = { landscape = true }, label = "Landscape")
                    }
                }
                if (size == NewPageSize.CUSTOM) {
                    Row {
                        OutlinedTextField(customW, { customW = it.filter(Char::isDigit).take(4) },
                            label = { Text("Width px") }, singleLine = true, isError = cw == null,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(customH, { customH = it.filter(Char::isDigit).take(4) },
                            label = { Text("Height px") }, singleLine = true, isError = ch == null,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Text("At 300 dpi, 100–9,999 px", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                Text("Paper", style = MaterialTheme.typography.labelLarge)
                PaperPicker(
                    selectedPlain = (paper as? NewPagePaper.Plain)?.background,
                    selectedTemplate = (paper as? NewPagePaper.Template)?.id,
                    userTemplates = userTemplates,
                    templateFile = templateFile,
                    onPlain = { paper = NewPagePaper.Plain(it) },
                    onTemplate = { paper = NewPagePaper.Template(it) },
                    onRenameTemplate = onRenameTemplate,
                    onDeleteTemplate = onDeleteTemplate,
                    onMoveTemplate = onMoveTemplate,
                    same = true,
                    sameSelected = paper == NewPagePaper.Same,
                    onSame = { paper = NewPagePaper.Same },
                )
                Text("Where", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                    SettingsChoiceChip(selected = !atEnd, onClick = { atEnd = false }, label = "After this page")
                    SettingsChoiceChip(selected = atEnd, onClick = { atEnd = true }, label = "At the end")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                prefs.edit()
                    .putString("addPageSize", size.name)
                    .putBoolean("addPageLandscape", landscape)
                    .putString("addPageCustomW", customW)
                    .putString("addPageCustomH", customH)
                    .putString("addPagePaper", paperKey(paper))
                    // Each note remembers its own, so a planner keeps its planner pages.
                    .putString("addPagePaper:$noteId", paperKey(paper))
                    .putBoolean("addPageAtEnd", atEnd)
                    .apply()
                val dims = newPageDimensions(size, landscape,
                    cw?.let { it / PX_300_PER_UNIT }, ch?.let { it / PX_300_PER_UNIT })
                onAdd(NewPageSpec(dims?.first, dims?.second, paper, atEnd))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun paperKey(paper: NewPagePaper): String = when (paper) {
    NewPagePaper.Same -> "SAME"
    is NewPagePaper.Plain -> paper.background.name
    is NewPagePaper.Template -> "T:${paper.id}"
}
