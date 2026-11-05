package com.notesis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/** The plain papers a page can be, with their names. */
internal val PAPER_CHOICES = listOf(
    PageBackground.BLANK to "Blank", PageBackground.LINED to "Lined",
    PageBackground.NARROW_LINED to "Narrow lined", PageBackground.GRID to "Grid",
    PageBackground.DOT to "Dot", PageBackground.CORNELL to "Cornell",
    PageBackground.INFINITE to "Infinite down", PageBackground.INFINITE_ALL to "Infinite all ways",
)

private val PAPER_CARD_WIDTH = 68.dp
private val PAPER_CARD_HEIGHT = 90.dp

/**
 * The papers to choose from as small pages, each drawn as it will look, the
 * image templates as their own pictures. [same] adds a first card for "the
 * page it goes after". A template is renamed by holding it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaperPicker(
    selectedPlain: PageBackground?,
    selectedTemplate: String?,
    userTemplates: List<UserPageTemplate>,
    templateFile: (String) -> File,
    onPlain: (PageBackground) -> Unit,
    onTemplate: (String) -> Unit,
    onRenameTemplate: (String, String) -> Boolean,
    same: Boolean = false,
    sameSelected: Boolean = false,
    onSame: () -> Unit = {},
    onDeleteTemplate: (String) -> Boolean = { false },
    onMoveTemplate: (String, Int) -> Boolean = { _, _ -> false },
) {
    // Changes made here show at once; the store has them for next time.
    val renamed = remember { mutableStateMapOf<String, String>() }
    var shown by remember(userTemplates) { mutableStateOf(userTemplates) }
    var acting by remember { mutableStateOf<UserPageTemplate?>(null) }
    var renaming by remember { mutableStateOf<UserPageTemplate?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        if (same) PaperCard("Same as this page", sameSelected, onSame) {
            Icon(Reicons.ContentCopy, contentDescription = null, tint = Color(0x55000000),
                modifier = Modifier.size(24.dp).align(Alignment.Center))
        }
        PAPER_CHOICES.forEach { (background, label) ->
            PaperCard(label, selectedPlain == background && selectedTemplate == null, { onPlain(background) }) {
                Canvas(Modifier.fillMaxSize()) { drawPaper(background) }
            }
        }
        shown.forEach { template ->
            val name = renamed[template.id] ?: template.name
            PaperCard(name, selectedTemplate == template.id, { onTemplate(template.id) },
                onLongClick = { acting = template.copy(name = name) }) {
                val picture = rememberFileBitmap(templateFile(template.id), 160)
                if (picture != null) Image(picture.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            }
        }
    }
    if (shown.isNotEmpty()) Text(
        "Hold a template to rename, move or delete it",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    acting?.let { target ->
        val at = shown.indexOfFirst { it.id == target.id }
        AlertDialog(
            onDismissRequest = { acting = null },
            title = { Text(target.name) },
            text = {
                Column {
                    TextButton(onClick = { acting = null; renaming = target }) { Text("Rename") }
                    TextButton(enabled = at > 0, onClick = {
                        if (onMoveTemplate(target.id, -1)) shown = shown.toMutableList().apply { add(at - 1, removeAt(at)) }
                        acting = null
                    }) { Text("Move earlier") }
                    TextButton(enabled = at in 0 until shown.lastIndex, onClick = {
                        if (onMoveTemplate(target.id, 1)) shown = shown.toMutableList().apply { add(at + 1, removeAt(at)) }
                        acting = null
                    }) { Text("Move later") }
                    TextButton(onClick = {
                        if (onDeleteTemplate(target.id)) shown = shown.filterNot { it.id == target.id }
                        acting = null
                    }) { Text("Delete") }
                }
            },
            confirmButton = { TextButton(onClick = { acting = null }) { Text("Close") } },
        )
    }
    renaming?.let { target ->
        TemplateNameDialog(
            title = "Rename template",
            initial = target.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                if (onRenameTemplate(target.id, name)) renamed[target.id] = name.trim()
                renaming = null
            },
        )
    }
}

/** Asks for a template's name, when it is added and when it is renamed. */
@Composable
internal fun TemplateNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Template name") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaperCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    page: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(PAPER_CARD_WIDTH).clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.padding(top = 2.dp).width(PAPER_CARD_WIDTH - 6.dp).height(PAPER_CARD_HEIGHT)
                .clip(shape).background(Color(0xFFFDFCF8))
                .border(if (selected) 2.5.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant, shape),
            content = page,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
    }
}

/** A small drawing of [background]: its lines, grid, dots or margins, in the page's own grey. */
private fun DrawScope.drawPaper(background: PageBackground) {
    val ink = Color(0x33000000)
    val step = size.width / 9f
    val hair = 1f
    fun lines(gap: Float, top: Float = gap * 1.5f, left: Float = 0f, bottom: Float = size.height) {
        var y = top
        while (y < bottom) { drawLine(ink, Offset(left, y), Offset(size.width, y), hair); y += gap }
    }
    when (background) {
        PageBackground.LINED -> lines(step)
        PageBackground.NARROW_LINED -> lines(step * 0.7f)
        PageBackground.GRID -> {
            var x = step
            while (x < size.width) { drawLine(ink, Offset(x, 0f), Offset(x, size.height), hair); x += step }
            var y = step
            while (y < size.height) { drawLine(ink, Offset(0f, y), Offset(size.width, y), hair); y += step }
        }
        PageBackground.DOT -> {
            var y = step
            while (y < size.height) {
                var x = step
                while (x < size.width) { drawCircle(Color(0x55000000), 1.3f, Offset(x, y)); x += step }
                y += step
            }
        }
        PageBackground.CORNELL -> {
            val cue = size.width * 0.28f
            val summary = size.height * 0.78f
            lines(step, top = step * 2.5f, left = cue, bottom = summary)
            drawLine(Color(0x55000000), Offset(0f, step * 1.8f), Offset(size.width, step * 1.8f), hair)
            drawLine(Color(0x55000000), Offset(cue, step * 1.8f), Offset(cue, summary), hair)
            drawLine(Color(0x55000000), Offset(0f, summary), Offset(size.width, summary), hair)
        }
        PageBackground.INFINITE, PageBackground.INFINITE_ALL -> {
            // A page that goes on: its edge fades into arrows the way it grows.
            val mid = Offset(size.width / 2f, size.height / 2f)
            val arm = size.width * 0.16f
            val arrow = Color(0x66000000)
            fun arrowTo(dx: Float, dy: Float) {
                val tip = Offset(mid.x + dx * arm * 1.6f, mid.y + dy * arm * 1.6f)
                drawLine(arrow, mid, tip, 2f)
                drawLine(arrow, tip, Offset(tip.x - dx * arm * 0.5f - dy * arm * 0.4f, tip.y - dy * arm * 0.5f - dx * arm * 0.4f), 2f)
                drawLine(arrow, tip, Offset(tip.x - dx * arm * 0.5f + dy * arm * 0.4f, tip.y - dy * arm * 0.5f + dx * arm * 0.4f), 2f)
            }
            arrowTo(0f, 1f)
            if (background == PageBackground.INFINITE_ALL) { arrowTo(1f, 0f); arrowTo(-1f, 0f); arrowTo(0f, -1f) }
        }
        else -> Unit
    }
}
