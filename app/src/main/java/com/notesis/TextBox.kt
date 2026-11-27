package com.notesis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.KeyEvent
import android.widget.EditText
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keep editable text alongside the portable image used by page renderers and PDF export.
 * [text] is the plain text, for search and for files written before styling;
 * [html] carries per-range bold, italic, underline, strikethrough and colour.
 */
data class TextBoxContent(
    val text: String,
    val size: Float = 32f,
    val color: Int = 0xFF000000.toInt(),
    val html: String? = null,
    /** A system family name ("serif", "monospace", ...), blank for the default. */
    val font: String = "",
    /** Fill behind the text, 0 for none. */
    val background: Int = 0,
    val corner: Float = 0f,
    val padding: Float = DEFAULT_PADDING,
    /** The smallest the box is drawn, in page units; a sticky note is a square however short its text. */
    val minSize: Float = 0f,
    /** [text] is Markdown: headings, bold, italic, lists, quotes, code, tables and LaTeX ($x^2$, $$...$$) are drawn as such. */
    val markdown: Boolean = false,
    /**
     * How wide the box is kept, in page units, the text wrapping inside it; 0
     * for as wide as its longest line. Set by resizing the box, so a bigger
     * box holds more on a line instead of drawing larger letters.
     */
    val wrapWidth: Float = 0f,
    /** Where lines sit across the box: [ALIGN_START], [ALIGN_CENTER], [ALIGN_END] or [ALIGN_JUSTIFY]. */
    val align: Int = ALIGN_START,
    /** Room added between letters, in em: 0.1 is a tenth of the letter size, below 0 draws them closer. */
    val letterSpacing: Float = 0f,
    /** Line height as a multiple of the font's own; 1 is as it always was. */
    val lineSpacing: Float = 1f,
    /** Room added after each paragraph, in page units. */
    val paragraphSpacing: Float = 0f,
    /** The first line of each paragraph starts this far in, in em. */
    val indent: Float = 0f,
    /** The whole box bold or italic; parts are made so with the marks instead. */
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** A line around the box, 0 for none, [borderWidth] page units wide. */
    val border: Int = 0,
    val borderWidth: Float = 2f,
) {
    companion object {
        /** What every text box had before padding could be set, in page units. */
        const val DEFAULT_PADDING = 4f
        const val ALIGN_START = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_END = 2
        const val ALIGN_JUSTIFY = 3
        val SIZE_RANGE = 6f..200f
        val LETTER_RANGE = -0.1f..1f
        val LINE_RANGE = 0.7f..3f
        val PARAGRAPH_RANGE = 0f..120f
        val INDENT_RANGE = 0f..8f
        val PADDING_RANGE = 0f..80f
        val CORNER_RANGE = 0f..80f
        val BORDER_RANGE = 0.5f..20f
    }
}

/** The fonts offered; each is a family Android ships with, so nothing is downloaded. */
internal val TEXT_FONTS = listOf(
    "" to "Default", "serif" to "Serif", "monospace" to "Monospace", "sans-serif-condensed" to "Condensed",
    "sans-serif-light" to "Light", "sans-serif-medium" to "Medium", "sans-serif-black" to "Black", "casual" to "Casual",
    "cursive" to "Cursive", "serif-monospace" to "Typewriter",
)

/** The words an agent or the terminal may use for each alignment, the first being the one shown. */
internal val TEXT_ALIGNS = listOf(
    TextBoxContent.ALIGN_START to listOf("left", "start"), TextBoxContent.ALIGN_CENTER to listOf("center", "centre", "middle"),
    TextBoxContent.ALIGN_END to listOf("right", "end"), TextBoxContent.ALIGN_JUSTIFY to listOf("justify", "justified"),
)

internal fun alignName(align: Int): String = TEXT_ALIGNS.firstOrNull { it.first == align }?.second?.first() ?: "left"

internal fun alignOf(word: String): Int? = TEXT_ALIGNS.firstOrNull { (_, words) -> word.lowercase() in words }?.first

/** The family [word] names, by its name or its label in [TEXT_FONTS], any case; "default" for none. */
internal fun fontOf(word: String): String? {
    val w = word.trim().lowercase()
    if (w == "default" || w == "sans" || w == "sans-serif") return ""
    if (w == "mono") return "monospace"
    return TEXT_FONTS.firstOrNull { (family, label) -> family == w || label.lowercase() == w }?.first
}

/** The box written for meta.json; the settings added later only when they differ from what boxes always had. */
internal fun TextBoxContent.toJson(): JSONObject = JSONObject()
    .put("value", text).put("size", size.toDouble()).put("color", color)
    .put("html", html ?: "").put("font", font).put("bg", background)
    .put("corner", corner.toDouble()).put("pad", padding.toDouble())
    .put("min", minSize.toDouble())
    .put("md", markdown)
    .put("wrap", wrapWidth.toDouble())
    .apply {
        if (align != TextBoxContent.ALIGN_START) put("align", align)
        if (letterSpacing != 0f) put("ls", letterSpacing.toDouble())
        if (lineSpacing != 1f) put("lh", lineSpacing.toDouble())
        if (paragraphSpacing != 0f) put("ps", paragraphSpacing.toDouble())
        if (indent != 0f) put("indent", indent.toDouble())
        if (bold) put("b", true)
        if (italic) put("i", true)
        if (border != 0) put("border", border).put("bw", borderWidth.toDouble())
    }

/** A box read back from [json]; a table box from before tables were taken out comes back as its text. */
internal fun textBoxContentFrom(json: JSONObject): TextBoxContent {
    val table = json.optJSONArray("table")?.let { rows ->
        (0 until rows.length()).map { r ->
            val row = rows.optJSONArray(r) ?: JSONArray()
            (0 until row.length()).map { c -> row.optString(c) }
        }
    }
    return TextBoxContent(table?.let(::tableAsText) ?: json.optString("value"), json.optDouble("size", 32.0).toFloat(),
        json.optInt("color", 0xFF000000.toInt()),
        html = json.optString("html", "").ifBlank { null },
        font = json.optString("font", ""),
        background = json.optInt("bg", 0),
        corner = json.optDouble("corner", 0.0).toFloat(),
        padding = json.optDouble("pad", TextBoxContent.DEFAULT_PADDING.toDouble()).toFloat(),
        minSize = json.optDouble("min", 0.0).toFloat(),
        markdown = json.optBoolean("md", false),
        wrapWidth = json.optDouble("wrap", 0.0).toFloat(),
        align = json.optInt("align", TextBoxContent.ALIGN_START).coerceIn(0, 3),
        letterSpacing = json.optDouble("ls", 0.0).toFloat().coerceIn(TextBoxContent.LETTER_RANGE),
        lineSpacing = json.optDouble("lh", 1.0).toFloat().coerceIn(TextBoxContent.LINE_RANGE),
        paragraphSpacing = json.optDouble("ps", 0.0).toFloat().coerceIn(TextBoxContent.PARAGRAPH_RANGE),
        indent = json.optDouble("indent", 0.0).toFloat().coerceIn(TextBoxContent.INDENT_RANGE),
        bold = json.optBoolean("b", false),
        italic = json.optBoolean("i", false),
        border = json.optInt("border", 0),
        borderWidth = json.optDouble("bw", 2.0).toFloat().coerceIn(TextBoxContent.BORDER_RANGE),
    )
}

/** The text with its marks drawn; [em] is the letter size in pixels, for the indents of lists and quotes. */
internal fun TextBoxContent.styledText(em: Float = size * 2f): CharSequence =
    html?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).trimEnd() }
        ?: if (markdown) markdownSpanned(text, em) else text

private fun CharSequence.trimEnd(): CharSequence {
    var end = length
    while (end > 0 && this[end - 1].isWhitespace()) end--
    return subSequence(0, end)
}

/** The family and the whole box's bold and italic, as one face. */
internal fun TextBoxContent.typeface(): Typeface {
    val style = when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    return if (font.isNotBlank()) Typeface.create(font, style) else Typeface.create(Typeface.DEFAULT, style)
}

private fun textPaint(content: TextBoxContent, scale: Float) =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textSize = content.size.coerceIn(TextBoxContent.SIZE_RANGE) * scale
        color = content.color
        typeface = content.typeface()
        letterSpacing = content.letterSpacing.coerceIn(TextBoxContent.LETTER_RANGE)
    }

/** The text with what applies to whole paragraphs added: room after each, and the first line's indent. */
private fun paragraphs(content: TextBoxContent, styled: CharSequence, scale: Float): CharSequence {
    if (content.paragraphSpacing <= 0f && content.indent <= 0f) return styled
    val out = SpannableStringBuilder(styled)
    if (content.paragraphSpacing > 0f && out.isNotEmpty()) {
        out.setSpan(ParagraphGap((content.paragraphSpacing * scale).toInt()), 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }
    if (content.indent > 0f) {
        val first = (content.indent * content.size.coerceIn(TextBoxContent.SIZE_RANGE) * scale).toInt()
        var start = 0
        while (start <= out.length) {
            val end = out.indexOf('\n', start).let { if (it < 0) out.length else it }
            // Lists, quotes and code keep their own indent; only plain paragraphs are indented.
            val own = out.getSpans(start, maxOf(start, end), android.text.style.LeadingMarginSpan::class.java).isNotEmpty() ||
                out.getSpans(start, maxOf(start, end), android.text.style.AlignmentSpan::class.java).isNotEmpty()
            if (end > start && !own) {
                out.setSpan(android.text.style.LeadingMarginSpan.Standard(first, 0), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start = end + 1
        }
    }
    return out
}

/** Room under the last line of each paragraph but the last. */
private class ParagraphGap(private val gap: Int) : android.text.style.LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        if (end in 1 until text.length && text[end - 1] == '\n') {
            fm.descent += gap
            fm.bottom += gap
        }
    }
}

private fun layoutAlignment(align: Int) = when (align) {
    TextBoxContent.ALIGN_CENTER -> Layout.Alignment.ALIGN_CENTER
    TextBoxContent.ALIGN_END -> Layout.Alignment.ALIGN_OPPOSITE
    else -> Layout.Alignment.ALIGN_NORMAL
}

/** The styled text and its paint at [scale] times the box's own units, for measuring and laying out alike. */
private fun prepared(content: TextBoxContent, scale: Float): Pair<CharSequence, TextPaint> {
    val paint = textPaint(content, scale)
    return paragraphs(content, content.styledText(paint.textSize), scale) to paint
}

/** The text laid out [width] pixels wide at [scale] times the box's own units. */
internal fun textBoxLayout(content: TextBoxContent, width: Int, scale: Float): StaticLayout {
    val (styled, paint) = prepared(content, scale)
    return layoutOf(content, styled, paint, width)
}

private fun layoutOf(content: TextBoxContent, styled: CharSequence, paint: TextPaint, width: Int): StaticLayout =
    StaticLayout.Builder.obtain(styled, 0, styled.length, paint, width.coerceAtLeast(1))
        .setIncludePad(true).setAlignment(layoutAlignment(content.align))
        .setLineSpacing(0f, content.lineSpacing.coerceIn(TextBoxContent.LINE_RANGE))
        .setJustificationMode(if (content.align == TextBoxContent.ALIGN_JUSTIFY) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
        .build()

/** Background, border, then text, into a box [width] x [height] at [scale]; the canvas origin is the box's corner. */
internal fun drawTextBox(canvas: Canvas, content: TextBoxContent, layout: StaticLayout, width: Float, height: Float, scale: Float) {
    val r = content.corner * scale
    if (content.background != 0) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = content.background }
        canvas.drawRoundRect(0f, 0f, width, height, r, r, fill)
    }
    if (content.border != 0) {
        val w = content.borderWidth.coerceIn(TextBoxContent.BORDER_RANGE) * scale
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = content.border; style = Paint.Style.STROKE; strokeWidth = w }
        canvas.drawRoundRect(w / 2, w / 2, width - w / 2, height - w / 2, maxOf(0f, r - w / 2), maxOf(0f, r - w / 2), line)
    }
    val inset = content.textInset() * scale
    canvas.save()
    canvas.translate(inset, inset)
    layout.draw(canvas)
    canvas.restore()
}

/** How far the text sits in from the box's edge, in page units: the padding, and a border inside it. */
internal fun TextBoxContent.textInset(): Float =
    padding + if (border != 0) borderWidth.coerceIn(TextBoxContent.BORDER_RANGE) else 0f

private class TextBoxSize(val layout: StaticLayout, val width: Int, val height: Int)

private fun textBoxSize(content: TextBoxContent, scale: Float): TextBoxSize {
    val pad = (content.textInset() * scale).toInt()
    val maxWidth = 1600
    val (styled, paint) = prepared(content, scale)
    val wrap = if (content.wrapWidth > 0f) ((content.wrapWidth * scale).toInt() - pad * 2).coerceIn(16, 4096) else null
    val desired = wrap ?: kotlin.math.ceil(Layout.getDesiredWidth(styled, paint)).toInt().coerceIn(1, maxWidth)
    val layout = layoutOf(content, styled, paint, desired)
    return TextBoxSize(layout, maxOf(desired + pad * 2, (content.minSize * scale).toInt()),
        maxOf(layout.height + pad * 2, (content.minSize * scale).toInt()))
}

/** How tall [content] stands on the page, in page units, without drawing it. */
internal fun textBoxHeight(content: TextBoxContent): Float = textBoxSize(content, 2f).height / 2f

internal fun renderTextBox(content: TextBoxContent): Bitmap {
    val scale = 2f
    val size = textBoxSize(content, scale)
    require(size.layout.height <= 8192) { "The text is too long. Split it into more boxes." }
    return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also {
        drawTextBox(Canvas(it), content, size.layout, size.width.toFloat(), size.height.toFloat(), scale)
    }
}

// ---- editing ----------------------------------------------------------------

private enum class Mark { BOLD, ITALIC, UNDERLINE, STRIKE }

private fun spanFor(mark: Mark): CharacterStyle = when (mark) {
    Mark.BOLD -> StyleSpan(Typeface.BOLD)
    Mark.ITALIC -> StyleSpan(Typeface.ITALIC)
    Mark.UNDERLINE -> UnderlineSpan()
    Mark.STRIKE -> StrikethroughSpan()
}

private fun matches(span: Any, mark: Mark): Boolean = when (mark) {
    Mark.BOLD -> span is StyleSpan && span.style == Typeface.BOLD
    Mark.ITALIC -> span is StyleSpan && span.style == Typeface.ITALIC
    Mark.UNDERLINE -> span is UnderlineSpan
    Mark.STRIKE -> span is StrikethroughSpan
}

/** The selected range, or all of it when nothing is selected - a closed box's style applies to the whole. */
private fun EditText.range(): Pair<Int, Int> {
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    return if (start < 0 || start == end) 0 to text.length else start to end
}

/** Turns [mark] on over the range, or off if the whole range already has it. */
private fun EditText.toggle(mark: Mark) {
    val (start, end) = range()
    if (start == end) return
    val text: Editable = text
    val existing = text.getSpans(start, end, CharacterStyle::class.java).filter { matches(it, mark) }
    val covered = existing.any { text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end }
    for (span in existing) {
        val s = text.getSpanStart(span)
        val e = text.getSpanEnd(span)
        text.removeSpan(span)
        // Keep what lies outside the range.
        if (s < start) text.setSpan(spanFor(mark), s, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (e > end) text.setSpan(spanFor(mark), end, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (!covered) text.setSpan(spanFor(mark), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private fun EditText.colorRange(color: Int) {
    val (start, end) = range()
    if (start == end) return
    text.getSpans(start, end, ForegroundColorSpan::class.java).forEach { text.removeSpan(it) }
    text.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private fun Spanned.hasStyle(): Boolean =
    getSpans(0, length, CharacterStyle::class.java).isNotEmpty()

/** Puts [before] and [after] around the selection (or at the cursor), leaving the selection inside them. */
private fun EditText.wrapSelection(before: String, after: String) {
    val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
    val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
    text.insert(end, after)
    text.insert(start, before)
    setSelection(start + before.length, end + before.length)
}

/** The box drawn small for the dialog, at most [maxHeight] pixels tall so a long text cannot take the memory. */
internal fun previewTextBox(content: TextBoxContent, maxHeight: Int = 1200): Bitmap {
    val size = textBoxSize(content, 1f)
    val height = size.height.coerceIn(1, maxHeight)
    return Bitmap.createBitmap(size.width.coerceAtLeast(1), height, Bitmap.Config.ARGB_8888).also {
        drawTextBox(Canvas(it), content, size.layout, size.width.toFloat(), size.height.toFloat(), 1f)
    }
}

/** A labelled slider for one of the dialog's numbers, its value shown with [digits] decimals. */
@Composable
private fun StyleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, digits: Int, onValue: (Float) -> Unit) {
    Text("$label ${String.format(java.util.Locale.US, "%.${digits}f", value)}", style = MaterialTheme.typography.bodySmall)
    SkinSlider(value, onValue, range, step = step)
}

@Composable
internal fun TextBoxDialog(initial: TextBoxContent?, onDismiss: () -> Unit, onSave: (TextBoxContent) -> Unit) {
    val start = initial ?: TextBoxContent("")
    var size by remember { mutableStateOf(widthLabel(start.size)) }
    var color by remember { mutableIntStateOf(start.color) }
    var font by remember { mutableStateOf(start.font) }
    var background by remember { mutableIntStateOf(start.background) }
    var corner by remember { mutableFloatStateOf(start.corner) }
    var padding by remember { mutableFloatStateOf(start.padding) }
    var markdown by remember { mutableStateOf(start.markdown || initial == null && start.html == null) }
    var align by remember { mutableIntStateOf(start.align) }
    var letter by remember { mutableFloatStateOf(start.letterSpacing) }
    var line by remember { mutableFloatStateOf(start.lineSpacing) }
    var paragraph by remember { mutableFloatStateOf(start.paragraphSpacing) }
    var indent by remember { mutableFloatStateOf(start.indent) }
    var bold by remember { mutableStateOf(start.bold) }
    var italic by remember { mutableStateOf(start.italic) }
    var border by remember { mutableIntStateOf(start.border) }
    var borderWidth by remember { mutableFloatStateOf(start.borderWidth) }
    var editor by remember { mutableStateOf<EditText?>(null) }
    var empty by remember { mutableStateOf(start.text.isBlank()) }
    var rangeColor by remember { mutableStateOf(false) }
    // Counts the edits, so the preview is drawn again after each.
    var typed by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val parsedSize = parseWidth(size, TextBoxContent.SIZE_RANGE)

    fun current(): TextBoxContent? {
        val text = editor?.text ?: return null
        val html = if (!markdown && text.hasStyle()) Html.toHtml(text, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE) else null
        return start.copy(text = text.toString(), size = parsedSize ?: start.size, color = color, html = html, font = font,
            background = background, corner = corner, padding = padding, markdown = markdown, align = align,
            letterSpacing = letter, lineSpacing = line, paragraphSpacing = paragraph, indent = indent,
            bold = bold, italic = italic, border = border, borderWidth = borderWidth)
    }

    LaunchedEffect(typed, size, color, font, background, corner, padding, markdown, align, letter, line, paragraph, indent,
        bold, italic, border, borderWidth) {
        kotlinx.coroutines.delay(250)
        val content = current()?.takeIf { it.text.isNotBlank() } ?: run { preview = null; return@LaunchedEffect }
        preview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { previewTextBox(content) }.getOrNull()
        }
    }

    // In Markdown the marks are typed as text (**bold**); otherwise they style the selection itself.
    fun mark(m: Mark) {
        val e = editor ?: return
        if (markdown) when (m) {
            Mark.BOLD -> e.wrapSelection("**", "**")
            Mark.ITALIC -> e.wrapSelection("*", "*")
            Mark.UNDERLINE -> e.wrapSelection("<u>", "</u>")
            Mark.STRIKE -> e.wrapSelection("~~", "~~")
        } else e.toggle(m)
        typed++
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add text box" else "Edit text") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { mark(Mark.BOLD) }) { Text("B", fontWeight = FontWeight.Bold) }
                TextButton(onClick = { mark(Mark.ITALIC) }) { Text("I", fontStyle = FontStyle.Italic) }
                TextButton(onClick = { mark(Mark.UNDERLINE) }) { Text("U", textDecoration = TextDecoration.Underline) }
                TextButton(onClick = { mark(Mark.STRIKE) }) { Text("S", textDecoration = TextDecoration.LineThrough) }
                if (markdown) {
                    TextButton(onClick = { editor?.wrapSelection("==", "=="); typed++ }) { Text("Highlight") }
                    TextButton(onClick = { editor?.wrapSelection("$", "$"); typed++ }) { Text("Formula") }
                    TextButton(onClick = { editor?.wrapSelection("\n$$\n", "\n$$\n"); typed++ }) { Text("Display formula") }
                } else {
                    TextButton(onClick = { rangeColor = !rangeColor }) { Text("Selection color") }
                }
            }
            if (rangeColor && !markdown) ColorInput(color) { picked -> editor?.colorRange(picked); typed++ }
            AndroidView(
                factory = { context ->
                    EditText(context).apply {
                        minLines = 3
                        maxLines = 10
                        // Markdown is edited as written, marks and all; styled text with its styles.
                        setText(if (start.markdown || start.html == null) start.text else start.styledText())
                        // Ctrl/Cmd shortcuts for the four marks, as in the rest of the app.
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN || !(event.isCtrlPressed || event.isMetaPressed)) {
                                return@setOnKeyListener false
                            }
                            when {
                                keyCode == KeyEvent.KEYCODE_B -> mark(Mark.BOLD)
                                keyCode == KeyEvent.KEYCODE_I -> mark(Mark.ITALIC)
                                keyCode == KeyEvent.KEYCODE_U -> mark(Mark.UNDERLINE)
                                keyCode == KeyEvent.KEYCODE_X && event.isShiftPressed -> mark(Mark.STRIKE)
                                keyCode == KeyEvent.KEYCODE_S && event.isMetaPressed -> mark(Mark.STRIKE)
                                else -> return@setOnKeyListener false
                            }
                            true
                        }
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                if ((s?.length ?: 0) > 4000) s?.delete(4000, s.length)
                                empty = s.isNullOrBlank()
                                typed++
                            }
                        })
                        editor = this
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsToggle("Markdown and LaTeX", markdown, { markdown = it },
                hint = "# heading, **bold**, *italic*, ==highlight==, - list, 1. list, | table |, `code`, \$x^2\$, \$\$\\frac{a}{b}\$\$")
            preview?.let { bitmap ->
                Text("Preview", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.Image(bitmap.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                        .background(androidx.compose.ui.graphics.Color.White, Ui.Shape.chip).padding(Ui.Space.s),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    alignment = androidx.compose.ui.Alignment.TopStart)
            }

            Text("Letters", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Ui.Space.m))
            OutlinedTextField(size, { size = it }, label = { Text("Font size (6–200)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, isError = parsedSize == null, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                SettingsChoiceChip(selected = bold, onClick = { bold = !bold }, label = "Bold", modifier = Modifier.padding(end = Ui.Space.s))
                SettingsChoiceChip(selected = italic, onClick = { italic = !italic }, label = "Italic", modifier = Modifier.padding(end = Ui.Space.s))
            }
            Text("Font", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TEXT_FONTS.forEach { (family, label) ->
                    SettingsChoiceChip(selected = font == family, onClick = { font = family }, label = label,
                        modifier = Modifier.padding(end = Ui.Space.s))
                }
            }
            Text("Text color", style = MaterialTheme.typography.bodyMedium)
            ColorInput(color) { color = it }

            Text("Spacing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Ui.Space.m))
            Text("Alignment", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(TextBoxContent.ALIGN_START to "Left", TextBoxContent.ALIGN_CENTER to "Center",
                    TextBoxContent.ALIGN_END to "Right", TextBoxContent.ALIGN_JUSTIFY to "Justify").forEach { (value, label) ->
                    SettingsChoiceChip(selected = align == value, onClick = { align = value }, label = label,
                        modifier = Modifier.padding(end = Ui.Space.s))
                }
            }
            StyleSlider("Letter spacing", letter, TextBoxContent.LETTER_RANGE, 0.01f, 2) { letter = it }
            StyleSlider("Line spacing", line, TextBoxContent.LINE_RANGE, 0.05f, 2) { line = it }
            StyleSlider("Paragraph spacing", paragraph, 0f..80f, 1f, 0) { paragraph = it }
            StyleSlider("First-line indent", indent, TextBoxContent.INDENT_RANGE, 0.5f, 1) { indent = it }
            StyleSlider("Padding", padding, 0f..40f, 1f, 0) { padding = it }

            Text("Box", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Ui.Space.m))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SkinSwitch(checked = background != 0,
                    onCheckedChange = { background = if (it) 0xFFFFF59D.toInt() else 0 })
                Spacer(Modifier.width(Ui.Space.m))
                Text("Background")
            }
            if (background != 0) ColorInput(background) { background = it }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SkinSwitch(checked = border != 0,
                    onCheckedChange = { border = if (it) 0xFF424242.toInt() else 0 })
                Spacer(Modifier.width(Ui.Space.m))
                Text("Border")
            }
            if (border != 0) {
                ColorInput(border) { border = it }
                StyleSlider("Border width", borderWidth, 0.5f..12f, 0.5f, 1) { borderWidth = it }
            }
            if (background != 0 || border != 0) StyleSlider("Corners", corner, 0f..40f, 1f, 0) { corner = it }
            Text(if (markdown) "Select text and tap B·I·U·S to put the marks around it."
                else "Select text and tap B·I·U·S to style just that part; with nothing selected, it applies to all.",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = !empty && parsedSize != null,
            onClick = { current()?.let(onSave) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The text a table box from before tables were taken out is kept as: a row a
 * line, its cells apart by tabs, each as it was typed (a formula stays a
 * formula's text). Nothing written in one is lost; it is plain text now.
 */
internal fun tableAsText(rows: List<List<String>>): String =
    rows.joinToString("\n") { row -> row.joinToString("\t").trimEnd('\t') }.trimEnd('\n')
