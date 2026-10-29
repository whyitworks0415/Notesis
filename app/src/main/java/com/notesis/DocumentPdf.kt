package com.notesis

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/**
 * A document opened from a file - text, Markdown, Word, PowerPoint, Excel,
 * HWP - set as pages of a PDF ([options]: paper, turn, text size, margins),
 * to be written on like any imported PDF. Used where the document view
 * cannot lay the file out itself (Word); the rest print from that view, as
 * they look there (see printLayoutToPdf).
 * Its text as the parser read it, not its original layout: headings, body,
 * list items, code and tables, each section starting a new page when the
 * document has several (a slide, a sheet), the rest flowing on.
 */
internal fun writeDocumentPdf(document: PreviewDocument, out: OutputStream, options: PdfLayoutOptions = PdfLayoutOptions()) {
    val pdf = PdfDocument()
    try {
        DocumentPdfWriter(pdf, options).apply {
            title(document.title.substringBeforeLast('.'))
            document.sections.forEachIndexed { index, section ->
                if (index > 0 && document.sections.size > 1) newPage()
                if (document.sections.size > 1 && section.title.isNotBlank()) heading(section.title.trim())
                for (block in section.blocks) when (block) {
                    is PreviewBlock.Paragraph -> paragraph(block)
                    is PreviewBlock.Table -> table(block.rows)
                }
            }
            finish()
        }
        pdf.writeTo(out)
    } finally {
        pdf.close()
    }
}

/** Lays text down the page, turning to a new one when the next part does not fit. */
private class DocumentPdfWriter(private val pdf: PdfDocument, options: PdfLayoutOptions) {
    // The paper and its turn, in points; the margins and the type as asked.
    private val PAGE_W = (if (options.landscape) options.paper.heightMm else options.paper.widthMm) * PT_PER_MM
    private val PAGE_H = (if (options.landscape) options.paper.widthMm else options.paper.heightMm) * PT_PER_MM
    private val MARGIN = options.margin.mm * PT_PER_MM
    private val scale = options.scale
    private val BODY_PT = BASE_BODY_PT * scale
    private val TITLE_PT = BASE_TITLE_PT * scale
    private val HEADING_PT = BASE_HEADING_PT * scale
    private val CELL_PT = BASE_CELL_PT * scale
    private val GAP = BASE_GAP * scale
    private val INDENT = BASE_INDENT * scale

    private var page: PdfDocument.Page? = null
    private var number = 0
    private var y = 0f

    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = BODY_PT; color = INK }
    private val mono = TextPaint(body).apply { typeface = Typeface.MONOSPACE; textSize = BODY_PT * 0.92f }
    private val headingPaint = TextPaint(body).apply { textSize = HEADING_PT; typeface = Typeface.DEFAULT_BOLD }
    private val titlePaint = TextPaint(body).apply { textSize = TITLE_PT; typeface = Typeface.DEFAULT_BOLD }
    private val cellPaint = TextPaint(body).apply { textSize = CELL_PT }
    private val headerCellPaint = TextPaint(cellPaint).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RULE; strokeWidth = 0.6f; style = Paint.Style.STROKE }
    private val codeFill = Paint().apply { color = CODE_FILL }

    private val width get() = PAGE_W - 2 * MARGIN

    fun newPage() {
        page?.let { pdf.finishPage(it) }
        number++
        page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), number).create())
        y = MARGIN
    }

    private fun canvas() = (page ?: run { newPage(); page!! }).canvas

    /** Room for [height] more on this page, or a new page. */
    private fun room(height: Float) {
        if (page == null || y + height > PAGE_H - MARGIN && y > MARGIN) newPage()
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()

    /**
     * Draws [layout] at [x], a page at a time: a block taller than what is
     * left of the page breaks between its lines rather than running off it.
     */
    private fun flow(layout: StaticLayout, x: Float, background: Paint? = null) {
        var line = 0
        while (line < layout.lineCount) {
            room(layout.getLineBottom(line) - layout.getLineTop(line).toFloat())
            val available = PAGE_H - MARGIN - y
            val top = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= available) last++
            val height = (layout.getLineBottom(last) - top).toFloat()
            val c = canvas()
            background?.let { c.drawRect(x - 4f, y - 2f, x + layout.width + 4f, y + height + 2f, it) }
            c.save()
            c.translate(x, y - top)
            c.clipRect(0f, top.toFloat(), layout.width.toFloat(), top + height)
            layout.draw(c)
            c.restore()
            y += height
            line = last + 1
        }
    }

    fun title(text: String) {
        if (text.isBlank()) return
        flow(layout(text, titlePaint, width), MARGIN)
        y += GAP * 1.5f
    }

    fun heading(text: String) {
        room(HEADING_PT * 3)
        y += GAP
        flow(layout(text, headingPaint, width), MARGIN)
        y += GAP * 0.5f
    }

    fun paragraph(block: PreviewBlock.Paragraph) {
        val text = if (block.monospace) block.text.trimEnd() else block.text.trim()
        if (text.isEmpty()) return
        when {
            block.monospace -> {
                flow(layout(text, mono, width - 8f), MARGIN + 4f, codeFill)
            }
            block.level > 0 -> {
                val indent = INDENT * block.level
                val bullet = layout("•", body, INDENT)
                room(bullet.height.toFloat())
                val c = canvas()
                c.save()
                c.translate(MARGIN + indent - INDENT * 0.7f, y)
                bullet.draw(c)
                c.restore()
                flow(layout(text, body, width - indent), MARGIN + indent)
            }
            else -> flow(layout(text, body, width), MARGIN)
        }
        y += GAP
    }

    /**
     * A table as a grid of equal columns, each cell's text wrapped in it; a
     * row too tall for what is left of the page starts the next one.
     */
    fun table(rows: List<List<String>>) {
        val kept = rows.filter { row -> row.any { it.isNotBlank() } }
        if (kept.isEmpty()) return
        val columns = kept.maxOf { it.size }.coerceIn(1, MAX_COLUMNS)
        val cell = width / columns
        kept.forEachIndexed { index, row ->
            val paint = if (index == 0) headerCellPaint else cellPaint
            val layouts = (0 until columns).map { column ->
                layout(row.getOrElse(column) { "" }.trim(), paint, cell - 2 * CELL_PAD)
            }
            val height = (layouts.maxOf { it.height } + 2 * CELL_PAD).coerceAtMost(PAGE_H - 2 * MARGIN)
            room(height)
            val c = canvas()
            layouts.forEachIndexed { column, cellLayout ->
                val left = MARGIN + column * cell
                c.drawRect(left, y, left + cell, y + height, rule)
                c.save()
                c.translate(left + CELL_PAD, y + CELL_PAD)
                c.clipRect(0f, 0f, cell - 2 * CELL_PAD, height - 2 * CELL_PAD)
                cellLayout.draw(c)
                c.restore()
            }
            y += height
        }
        y += GAP
    }

    fun finish() {
        // An empty document is still a page to write on.
        if (page == null) newPage()
        page?.let { pdf.finishPage(it) }
        page = null
    }

    private companion object {
        const val PT_PER_MM = 72f / 25.4f
        const val BASE_GAP = 8f
        const val BASE_INDENT = 18f
        const val BASE_TITLE_PT = 20f
        const val BASE_HEADING_PT = 15f
        const val BASE_BODY_PT = 11f
        const val BASE_CELL_PT = 9f
        const val CELL_PAD = 4f
        /** Past this many columns a table's cells are too narrow to read; the rest are left out. */
        const val MAX_COLUMNS = 12
        val INK = Color.rgb(0x1D, 0x1B, 0x20)
        val RULE = Color.rgb(0xB0, 0xB4, 0xBA)
        val CODE_FILL = Color.rgb(0xF2, 0xF3, 0xF5)
    }
}
