package com.notesis

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import kotlin.math.ceil
import kotlin.math.max

/**
 * A text box's Markdown drawn as text, the marks taken out:
 * - headings (# to ######), quotes (>, nested >>), rules (---),
 * - bullets (-, *, +) nested by indent, numbered lists (1. or 1)), boxes (- [ ] / - [x]),
 * - **bold**, *italic* or _italic_, ***both***, ~~struck~~, ==marked==, <u>underlined</u>, `code`,
 *   [links](url) and [[note links]] as underlined text, \* for a mark typed as itself,
 * - ``` code blocks, | tables |,
 * - LaTeX: $inline$ in a line, $$display$$ on its own line(s), centred.
 *
 * [em] is the text's size in pixels, for indents that grow with it. Plain
 * text comes back as it was.
 */
internal fun markdownSpanned(source: String, em: Float = 64f): CharSequence {
    val out = SpannableStringBuilder()
    val lines = source.split('\n')
    var index = 0
    while (index < lines.size) {
        if (index > 0) out.append('\n')
        val raw = lines[index]
        val trimmed = raw.trim()
        val start = out.length
        // ``` fences: the lines between kept exactly, in a fixed-width face on a grey band.
        if (trimmed.startsWith("```")) {
            val close = (index + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("```") } ?: lines.size
            val body = lines.subList(index + 1, close).joinToString("\n")
            out.append(body.ifEmpty { " " })
            out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(CodeBlockSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(LeadingMarginSpan.Standard((em * 0.5f).toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            index = close + 1
            continue
        }
        // $$ ... $$: a formula on a line of its own, perhaps over several lines.
        if (trimmed.startsWith("$$")) {
            val display = displayMath(lines, index)
            if (display != null) {
                val (tex, end) = display
                out.append('￼')
                out.setSpan(TexSpan(tex, display = true), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                index = end + 1
                continue
            }
        }
        // | a | b | over |---|---|: a table.
        if (trimmed.startsWith("|") && index + 1 < lines.size && isTableRule(lines[index + 1])) {
            var end = index + 2
            while (end < lines.size && lines[end].trim().startsWith("|")) end++
            val rows = (listOf(index) + (index + 2 until end)).map { tableCells(lines[it]) }
            val aligns = tableCells(lines[index + 1]).map { cell ->
                val c = cell.trim()
                when { c.startsWith(":") && c.endsWith(":") -> 'c'; c.endsWith(":") -> 'r'; else -> 'l' }
            }
            out.append('￼')
            out.setSpan(TableSpan(rows, aligns, em), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            index = end
            continue
        }
        if (RULE.matches(trimmed)) {
            out.append(' ')
            out.setSpan(RuleSpan(em), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            index++
            continue
        }
        appendLine(out, raw, em)
        index++
    }
    return out
}

/** The formula of the $$ block starting at line [from], and the line it ends on; null when it never closes. */
private fun displayMath(lines: List<String>, from: Int): Pair<String, Int>? {
    val first = lines[from].trim().removePrefix("$$")
    if (first.trimEnd().endsWith("$$")) return first.trimEnd().removeSuffix("$$").trim() to from
    val parts = mutableListOf(first)
    for (n in from + 1 until lines.size) {
        val line = lines[n].trimEnd()
        if (line.endsWith("$$")) { parts += line.removeSuffix("$$"); return parts.joinToString("\n").trim() to n }
        parts += line
    }
    return null
}

private val RULE = Regex("""^(-\s*){3,}$|^(\*\s*){3,}$|^(_\s*){3,}$""")

private fun isTableRule(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.contains('-') && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

private fun tableCells(line: String): List<String> {
    val t = line.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var n = 0
    while (n < t.length) {
        val c = t[n]
        if (c == '\\' && n + 1 < t.length && t[n + 1] == '|') { cell.append('|'); n += 2; continue }
        if (c == '|') { cells += cell.toString().trim(); cell.clear() } else cell.append(c)
        n++
    }
    cells += cell.toString().trim()
    return cells
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val QUOTE = Regex("""^((?:>\s?)+)(.*)$""")
private val TASK = Regex("""^(\s*)[-*+]\s+\[([ xX])]\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d{1,4})([.)])\s+(.*)$""")
private val HEADING_SIZES = floatArrayOf(1.6f, 1.4f, 1.2f, 1.1f, 1f, 0.95f)

/** One line that is not part of a block: a heading, a quote, a list item or a paragraph. */
private fun appendLine(out: SpannableStringBuilder, raw: String, em: Float) {
    val start = out.length
    HEADING.matchEntire(raw)?.let { m ->
        val level = m.groupValues[1].length
        appendInline(out, m.groupValues[2], em)
        if (out.length > start) {
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(HEADING_SIZES[level - 1]), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (level == 6) out.setSpan(ForegroundColorSpan(0xFF6B6B6B.toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return
    }
    QUOTE.matchEntire(raw)?.let { m ->
        val depth = m.groupValues[1].count { it == '>' }
        appendInline(out, m.groupValues[2], em)
        if (out.length == start) out.append(' ')
        out.setSpan(ForegroundColorSpan(0xFF6B6B6B.toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        repeat(depth) {
            out.setSpan(QuoteSpan(0xFFB8B8B8.toInt(), max(2, (em * 0.12f).toInt()), (em * 0.5f).toInt()), start, out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return
    }
    val task = TASK.matchEntire(raw)
    val bullet = if (task == null) BULLET.matchEntire(raw) else null
    val numbered = if (task == null && bullet == null) NUMBERED.matchEntire(raw) else null
    val item = task ?: bullet ?: numbered
    if (item != null) {
        val level = indentLevel(item.groupValues[1])
        val marker = when {
            task != null -> if (task.groupValues[2].isBlank()) "☐ " else "☑ "
            bullet != null -> BULLETS[level % BULLETS.size] + " "
            else -> numbered!!.groupValues[2] + numbered.groupValues[3] + " "
        }
        val body = item.groupValues.last()
        out.append(marker)
        val markerEnd = out.length
        appendInline(out, body, em)
        if (task != null && task.groupValues[2].isNotBlank()) {
            out.setSpan(ForegroundColorSpan(0xFF8A8A8A.toInt()), markerEnd, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Wrapped lines of an item start under its text, not under its mark.
        val first = (level * em * 1.2f).toInt()
        val markWidth = when {
            task != null -> em * 1.05f
            bullet != null -> em * 0.62f
            else -> em * (0.56f * numbered!!.groupValues[2].length + 0.75f)
        }
        out.setSpan(LeadingMarginSpan.Standard(first, first + markWidth.toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return
    }
    appendInline(out, raw, em)
}

private val BULLETS = listOf("•", "◦", "▪")

/** Two spaces or a tab a level, as most editors write a nested item. */
private fun indentLevel(space: String): Int {
    var columns = 0
    for (c in space) columns += if (c == '\t') 2 else 1
    return columns / 2
}

// ---- inline marks ---------------------------------------------------------------

private class InlineRule(val regex: Regex, val apply: (SpannableStringBuilder, MatchResult, Float) -> Unit)

private fun styled(out: SpannableStringBuilder, inner: String, em: Float, vararg spans: Any) {
    val start = out.length
    appendInline(out, inner, em)
    for (span in spans) out.setSpan(span, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private const val LINK_BLUE = 0xFF1E66D0.toInt()

/** In order of precedence where two start at the same place; the one that starts first wins. */
private val INLINE_RULES = listOf(
    InlineRule(Regex("""\\([\\`*_{}\[\]()#+\-.!$~=|<>])""")) { out, m, _ -> out.append(m.groupValues[1]) },
    InlineRule(Regex("""`([^`]+)`""")) { out, m, _ ->
        val start = out.length
        out.append(m.groupValues[1])
        out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(0x22808080), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    },
    InlineRule(Regex("""\$\$(.+?)\$\$""")) { out, m, _ -> math(out, m.groupValues[1], display = true) },
    InlineRule(Regex("""\$(?![\s$])((?:\\.|[^$\\])+?)(?<!\s)\$(?!\d)""")) { out, m, _ -> math(out, m.groupValues[1], display = false) },
    InlineRule(Regex("""\*\*\*(.+?)\*\*\*""")) { out, m, em ->
        styled(out, m.groupValues[1], em, StyleSpan(Typeface.BOLD_ITALIC))
    },
    InlineRule(Regex("""\*\*(.+?)\*\*|__(.+?)__""")) { out, m, em ->
        styled(out, m.groups[1]?.value ?: m.groupValues[2], em, StyleSpan(Typeface.BOLD))
    },
    InlineRule(Regex("""~~(.+?)~~""")) { out, m, em -> styled(out, m.groupValues[1], em, StrikethroughSpan()) },
    InlineRule(Regex("""==(.+?)==""")) { out, m, em -> styled(out, m.groupValues[1], em, BackgroundColorSpan(0x80FFE44D.toInt())) },
    InlineRule(Regex("""<u>(.+?)</u>""")) { out, m, em -> styled(out, m.groupValues[1], em, UnderlineSpan()) },
    InlineRule(Regex("""\[\[([^\]|]+)(?:\|([^\]]+))?]]""")) { out, m, _ ->
        val start = out.length
        out.append(m.groups[2]?.value ?: m.groupValues[1])
        out.setSpan(UnderlineSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(LINK_BLUE), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    },
    InlineRule(Regex("""\[([^\]]+)]\(([^)]*)\)""")) { out, m, em ->
        styled(out, m.groupValues[1], em, UnderlineSpan(), ForegroundColorSpan(LINK_BLUE))
    },
    InlineRule(Regex("""(?<![*\w])\*([^*\s](?:[^*]*?[^*\s])?)\*(?!\*)|(?<![\w_])_([^_\s](?:[^_]*?[^_\s])?)_(?![\w_])""")) { out, m, em ->
        styled(out, m.groups[1]?.value ?: m.groupValues[2], em, StyleSpan(Typeface.ITALIC))
    },
)

/** [text]'s inline marks appended to [out] without the marks; marks inside marks are kept too (**bold *and italic***). */
private fun appendInline(out: SpannableStringBuilder, text: String, em: Float) {
    var i = 0
    while (i < text.length) {
        var best: MatchResult? = null
        var rule: InlineRule? = null
        for (r in INLINE_RULES) {
            val m = r.regex.find(text, i) ?: continue
            if (best == null || m.range.first < best.range.first) { best = m; rule = r }
        }
        if (best == null || rule == null) { out.append(text, i, text.length); return }
        out.append(text, i, best.range.first)
        rule.apply(out, best, em)
        i = best.range.last + 1
    }
}

private fun math(out: SpannableStringBuilder, tex: String, display: Boolean) {
    val start = out.length
    out.append('￼')
    out.setSpan(TexSpan(tex.trim(), display), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

// ---- blocks drawn by hand ------------------------------------------------------------

/** The grey band behind a code block's lines. */
private class CodeBlockSpan : LineBackgroundSpan {
    private val fill = Paint().apply { color = 0x1A808080 }
    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int,
    ) {
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), fill)
    }
}

/** A thin line across the box, for ---. */
private class RuleSpan(private val em: Float) : LineBackgroundSpan {
    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int,
    ) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBDBDBD.toInt() }
        val mid = (top + bottom) / 2f
        val half = max(1f, em * 0.03f)
        canvas.drawRect(left.toFloat(), mid - half, right.toFloat(), mid + half, line)
    }
}

/**
 * A Markdown table: the first row bold on a grey band, lines between the
 * cells, each column aligned as its |:--:| says. A cell's own marks (bold,
 * code, $maths$) are drawn as anywhere else.
 */
internal class TableSpan(private val rows: List<List<String>>, private val aligns: List<Char>, private val em: Float) : ReplacementSpan() {
    private class Laid(val cells: List<List<StaticLayout>>, val widths: FloatArray, val heights: FloatArray, val width: Float, val height: Float)

    private var laid: Laid? = null
    private var laidSize = 0f
    private var laidColor = 0

    private fun layout(paint: Paint): Laid {
        laid?.let { if (laidSize == paint.textSize && laidColor == paint.color) return it }
        val padX = paint.textSize * 0.45f
        val padY = paint.textSize * 0.22f
        val columns = rows.maxOf { it.size }
        val cells = rows.mapIndexed { r, row ->
            (0 until columns).map { c ->
                val spanned = SpannableStringBuilder().also { appendInline(it, row.getOrElse(c) { "" }, paint.textSize) }
                if (r == 0 && spanned.isNotEmpty()) spanned.setSpan(StyleSpan(Typeface.BOLD), 0, spanned.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val textPaint = TextPaint(paint)
                val width = ceil(Layout.getDesiredWidth(spanned, textPaint)).toInt().coerceIn(1, 4096)
                val align = when (aligns.getOrElse(c) { 'l' }) { 'c' -> Layout.Alignment.ALIGN_CENTER; 'r' -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
                StaticLayout.Builder.obtain(spanned, 0, spanned.length, textPaint, width).setAlignment(align).setIncludePad(false).build()
            }
        }
        val widths = FloatArray(columns) { c -> cells.maxOf { it[c].width.toFloat() } + padX * 2 }
        val heights = FloatArray(cells.size) { r -> cells[r].maxOf { it.height.toFloat() } + padY * 2 }
        return Laid(cells, widths, heights, widths.sum(), heights.sum()).also { laid = it; laidSize = paint.textSize; laidColor = paint.color }
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val t = layout(paint)
        if (fm != null) {
            val below = (paint.textSize * 0.25f).toInt()
            fm.descent = max(fm.descent, below)
            fm.bottom = max(fm.bottom, below)
            fm.ascent = minOf(fm.ascent, -(ceil(t.height).toInt() - below))
            fm.top = minOf(fm.top, fm.ascent)
        }
        return ceil(t.width + 1).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val t = layout(paint)
        val below = paint.textSize * 0.25f
        val y0 = y + below - t.height
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBDBDBD.toInt(); style = Paint.Style.STROKE; strokeWidth = max(1f, em * 0.03f) }
        val band = Paint().apply { color = 0x14808080 }
        canvas.drawRect(x, y0, x + t.width, y0 + t.heights[0], band)
        var cy = y0
        for ((r, row) in t.cells.withIndex()) {
            var cx = x
            for ((c, cell) in row.withIndex()) {
                val w = t.widths[c]
                val dx = when (aligns.getOrElse(c) { 'l' }) { 'c' -> (w - cell.width) / 2; 'r' -> w - cell.width - paint.textSize * 0.45f; else -> paint.textSize * 0.45f }
                canvas.save()
                canvas.translate(cx + dx, cy + (t.heights[r] - cell.height) / 2)
                cell.draw(canvas)
                canvas.restore()
                cx += w
            }
            cy += t.heights[r]
        }
        canvas.drawRect(x, y0, x + t.width, y0 + t.height, line)
        var lx = x
        for (c in 0 until t.widths.size - 1) { lx += t.widths[c]; canvas.drawLine(lx, y0, lx, y0 + t.height, line) }
        var ly = y0
        for (r in 0 until t.heights.size - 1) { ly += t.heights[r]; canvas.drawLine(x, ly, x + t.width, ly, line) }
    }
}
