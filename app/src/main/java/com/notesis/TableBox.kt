package com.notesis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- the table's sums ---------------------------------------------------------

/** A column's letters for its index: A..Z, then AA, AB... */
internal fun columnName(index: Int): String {
    var n = index
    val out = StringBuilder()
    do {
        out.insert(0, 'A' + n % 26)
        n = n / 26 - 1
    } while (n >= 0)
    return out.toString()
}

/** The column index of letters like "B" or "AA"; -1 if they are not letters. */
internal fun columnIndex(letters: String): Int {
    var n = 0
    for (ch in letters.uppercase()) {
        if (ch !in 'A'..'Z') return -1
        n = n * 26 + (ch - 'A' + 1)
    }
    return n - 1
}

private val RANGE_FUNCTION = Regex("""(?i)\b(SUM|AVERAGE|AVG|MIN|MAX|COUNT)\(\s*([A-Z]{1,2})([0-9]{1,4})\s*:\s*([A-Z]{1,2})([0-9]{1,4})\s*\)""")
private val CELL_REF = Regex("""(?i)\b([A-Z]{1,2})([0-9]{1,4})\b""")

/**
 * What cell [row], [col] shows: its text, or, for a formula (a leading =),
 * what it works out to - spreadsheet style: SUM, AVERAGE, MIN, MAX and COUNT
 * over a range such as A1:B3, cell references like B2, and arithmetic.
 * "#ERR" when it cannot be worked out, a loop of references included.
 */
internal fun tableValue(cells: List<List<String>>, row: Int, col: Int, depth: Int = 0): String {
    val raw = cells.getOrNull(row)?.getOrNull(col).orEmpty()
    if (!raw.startsWith("=")) return raw
    if (depth > MAX_FORMULA_DEPTH) return "#ERR"
    return runCatching {
        var expr = raw.substring(1)
        expr = RANGE_FUNCTION.replace(expr) { m ->
            val c1 = columnIndex(m.groupValues[2]); val r1 = m.groupValues[3].toInt() - 1
            val c2 = columnIndex(m.groupValues[4]); val r2 = m.groupValues[5].toInt() - 1
            val values = ArrayList<Double>()
            for (r in minOf(r1, r2)..maxOf(r1, r2)) for (c in minOf(c1, c2)..maxOf(c1, c2)) {
                tableValue(cells, r, c, depth + 1).toDoubleOrNull()?.let(values::add)
            }
            val v = when (m.groupValues[1].uppercase()) {
                "SUM" -> values.sum()
                "AVERAGE", "AVG" -> if (values.isEmpty()) 0.0 else values.average()
                "MIN" -> values.minOrNull() ?: 0.0
                "MAX" -> values.maxOrNull() ?: 0.0
                else -> values.size.toDouble()
            }
            "($v)"
        }
        expr = CELL_REF.replace(expr) { m ->
            val c = columnIndex(m.groupValues[1])
            val r = m.groupValues[2].toInt() - 1
            if (c < 0) m.value
            else "(" + (tableValue(cells, r, c, depth + 1).toDoubleOrNull() ?: 0.0) + ")"
        }
        val result = MathExpr.eval(MathExpr.parse(expr))
        if (result.isNaN()) "#ERR" else formatNumber(result)
    }.getOrDefault("#ERR")
}

private const val MAX_FORMULA_DEPTH = 24

// ---- drawing --------------------------------------------------------------------

/** The table as a picture, at twice its page size, as text boxes are; see renderTextBox. */
internal fun renderTable(content: TextBoxContent, cells: List<List<String>>): Bitmap {
    val scale = 2f
    val rows = cells.size.coerceAtLeast(1)
    val cols = cells.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = content.size.coerceIn(12f, 96f) * scale * 0.8f
        color = content.color
    }
    val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    val pad = 8f * scale
    val values = List(rows) { r -> List(cols) { c -> tableValue(cells, r, c) } }
    val widths = FloatArray(cols) { c ->
        maxOf(48f * scale, values.maxOf { row -> (if (content.tableHeader && row === values[0]) bold else text)
            .measureText(row.getOrElse(c) { "" }) } + pad * 2)
    }
    val rowHeight = text.fontSpacing + pad * 2
    val width = widths.sum().toInt() + 2
    val height = (rowHeight * rows).toInt() + 2
    require(width <= 8192 && height <= 8192) { "The table is too big. Split it into more tables." }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint().apply { color = if (content.background != 0) content.background else 0xFFFFFFFF.toInt() }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
    if (content.tableHeader) {
        canvas.drawRect(0f, 0f, width.toFloat(), rowHeight, Paint().apply { color = 0x1F1E66D0 })
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA3AD.toInt(); strokeWidth = 1.5f * scale / 2f }
    for (r in 0..rows) canvas.drawLine(0f, 1f + r * rowHeight, width.toFloat(), 1f + r * rowHeight, line)
    var x = 1f
    for (c in 0..cols) {
        canvas.drawLine(x, 0f, x, height.toFloat(), line)
        if (c < cols) x += widths[c]
    }
    for (r in 0 until rows) {
        var left = 1f
        for (c in 0 until cols) {
            val value = values[r][c]
            val paint = if (content.tableHeader && r == 0) bold else text
            // Numbers right-aligned, as a spreadsheet sets them.
            val number = value.toDoubleOrNull() != null
            val at = if (number) left + widths[c] - pad - paint.measureText(value) else left + pad
            canvas.drawText(value, at, 1f + r * rowHeight + pad - paint.fontMetrics.ascent, paint)
            left += widths[c]
        }
    }
    return bitmap
}

// ---- editing --------------------------------------------------------------------

/**
 * A table edited as a spreadsheet: lettered columns and numbered rows, a
 * formula bar for the cell in hand, and rows and columns added or taken
 * away. A cell shows what it works out to until it is tapped, then what was
 * typed into it.
 */
@Composable
internal fun TableEditorDialog(initial: TextBoxContent?, onDismiss: () -> Unit, onSave: (TextBoxContent) -> Unit) {
    val start = initial?.table?.takeIf { it.isNotEmpty() } ?: List(3) { List(3) { "" } }
    val cols0 = start.maxOf { it.size }.coerceAtLeast(1)
    val grid = remember { mutableStateListOf(*start.map { row -> mutableStateListOf(*Array(cols0) { row.getOrElse(it) { "" } }) }.toTypedArray()) }
    var header by remember { mutableStateOf(initial?.tableHeader ?: true) }
    var size by remember { mutableStateOf(initial?.size ?: 28f) }
    var selRow by remember { mutableIntStateOf(0) }
    var selCol by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    fun snapshot(): List<List<String>> = grid.map { it.toList() }
    fun cols() = grid.firstOrNull()?.size ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New table" else "Edit table") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                // The formula bar: where the cell in hand is, and what is in it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${columnName(selCol)}${selRow + 1}", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(44.dp))
                    OutlinedTextField(
                        value = grid.getOrNull(selRow)?.getOrNull(selCol).orEmpty(),
                        onValueChange = { v -> grid.getOrNull(selRow)?.let { if (selCol < it.size) it[selCol] = v }; revision++ },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Text, a number, or =SUM(A1:A3)") },
                    )
                }
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())) {
                    Column {
                        Row {
                            Spacer(Modifier.width(32.dp))
                            for (c in 0 until cols()) Box(Modifier.width(CELL_WIDTH).height(24.dp), contentAlignment = Alignment.Center) {
                                Text(columnName(c), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                        val values = remember(revision, grid.size, cols()) { snapshot() }
                        for (r in grid.indices) Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                                Text("${r + 1}", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                            for (c in 0 until cols()) {
                                val focused = selRow == r && selCol == c
                                val shown = if (focused) grid[r][c] else tableValue(values, r, c)
                                BasicTextField(
                                    value = shown,
                                    onValueChange = { v -> if (focused) { grid[r][c] = v; revision++ } },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (header && r == 0) FontWeight.Bold else FontWeight.Normal),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.width(CELL_WIDTH).height(36.dp)
                                        .background(if (header && r == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                            else MaterialTheme.colorScheme.surface)
                                        .border(if (focused) 2.dp else 0.5.dp,
                                            if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                        .padding(horizontal = 6.dp, vertical = 8.dp)
                                        .onFocusChanged { if (it.isFocused) { selRow = r; selCol = c } },
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                    TextButton(onClick = { grid.add(selRow + 1, mutableStateListOf(*Array(cols()) { "" })); revision++ }) { Text("+ Row") }
                    TextButton(onClick = { grid.forEach { it.add(selCol + 1, "") }; revision++ }) { Text("+ Column") }
                    TextButton(enabled = grid.size > 1, onClick = {
                        grid.removeAt(selRow.coerceIn(grid.indices)); selRow = selRow.coerceAtMost(grid.lastIndex); revision++
                    }) { Text("− Row") }
                    TextButton(enabled = cols() > 1, onClick = {
                        val at = selCol.coerceIn(0, cols() - 1)
                        grid.forEach { it.removeAt(at) }; selCol = selCol.coerceAtMost(cols() - 1); revision++
                    }) { Text("− Column") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                    SettingsChoiceChip(selected = header, onClick = { header = !header }, label = "Heading row")
                    TextButton(onClick = { size = (size - 2f).coerceAtLeast(12f) }) { Text("A−") }
                    Text("${size.toInt()}", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { size = (size + 2f).coerceAtMost(72f) }) { Text("A+") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cells = snapshot()
                val plain = cells.joinToString("\n") { row -> row.joinToString("\t") }
                onSave((initial ?: TextBoxContent(plain)).copy(text = plain, size = size, html = null,
                    markdown = false, table = cells, tableHeader = header))
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val CELL_WIDTH = 96.dp
