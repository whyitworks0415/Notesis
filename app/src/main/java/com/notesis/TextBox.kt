package com.notesis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.Html
import android.text.Layout
import android.text.Spannable
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
) {
    companion object {
        /** What every text box had before padding could be set, in page units. */
        const val DEFAULT_PADDING = 4f
    }
}

/** The fonts offered; each is a family Android ships with, so nothing is downloaded. */
internal val TEXT_FONTS = listOf(
    "" to "기본", "serif" to "명조", "monospace" to "고정폭", "sans-serif-condensed" to "좁은",
    "sans-serif-light" to "가는", "casual" to "손글씨풍", "cursive" to "필기체",
)

internal fun TextBoxContent.styledText(): CharSequence =
    html?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).trimEnd() } ?: text

private fun CharSequence.trimEnd(): CharSequence {
    var end = length
    while (end > 0 && this[end - 1].isWhitespace()) end--
    return subSequence(0, end)
}

private fun textPaint(content: TextBoxContent, scale: Float) =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textSize = content.size.coerceIn(12f, 96f) * scale
        color = content.color
        if (content.font.isNotBlank()) typeface = Typeface.create(content.font, Typeface.NORMAL)
    }

/** The text laid out [width] pixels wide at [scale] times the box's own units. */
internal fun textBoxLayout(content: TextBoxContent, width: Int, scale: Float): StaticLayout {
    val styled = content.styledText()
    return StaticLayout.Builder.obtain(styled, 0, styled.length, textPaint(content, scale), width.coerceAtLeast(1))
        .setIncludePad(true).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
}

/** Background, then text, into a box [width] x [height] at [scale]; the canvas origin is the box's corner. */
internal fun drawTextBox(canvas: Canvas, content: TextBoxContent, layout: StaticLayout, width: Float, height: Float, scale: Float) {
    if (content.background != 0) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = content.background }
        val r = content.corner * scale
        canvas.drawRoundRect(0f, 0f, width, height, r, r, fill)
    }
    canvas.save()
    canvas.translate(content.padding * scale, content.padding * scale)
    layout.draw(canvas)
    canvas.restore()
}

internal fun renderTextBox(content: TextBoxContent): Bitmap {
    val scale = 2f
    val pad = (content.padding * scale).toInt()
    val maxWidth = 1600
    val desired = Layout.getDesiredWidth(content.styledText(), textPaint(content, scale)).toInt().coerceIn(1, maxWidth)
    val layout = textBoxLayout(content, desired, scale)
    require(layout.height <= 8192) { "텍스트가 너무 깁니다. 상자를 나눠 주세요." }
    val width = desired + pad * 2
    val height = layout.height + pad * 2
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        drawTextBox(Canvas(it), content, layout, width.toFloat(), height.toFloat(), scale)
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

@Composable
internal fun TextBoxDialog(initial: TextBoxContent?, onDismiss: () -> Unit, onSave: (TextBoxContent) -> Unit) {
    var size by remember { mutableStateOf(widthLabel(initial?.size ?: 32f)) }
    var color by remember { mutableIntStateOf(initial?.color ?: 0xFF000000.toInt()) }
    var font by remember { mutableStateOf(initial?.font.orEmpty()) }
    var background by remember { mutableIntStateOf(initial?.background ?: 0) }
    var corner by remember { mutableFloatStateOf(initial?.corner ?: 0f) }
    var padding by remember { mutableFloatStateOf(initial?.padding ?: TextBoxContent.DEFAULT_PADDING) }
    var editor by remember { mutableStateOf<EditText?>(null) }
    var empty by remember { mutableStateOf(initial?.text.isNullOrBlank()) }
    var rangeColor by remember { mutableStateOf(false) }
    val parsedSize = parseWidth(size, 12f..96f)
    fun mark(m: Mark) { editor?.toggle(m) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "텍스트 상자 추가" else "텍스트 편집") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { mark(Mark.BOLD) }) { Text("B", fontWeight = FontWeight.Bold) }
                TextButton(onClick = { mark(Mark.ITALIC) }) { Text("I", fontStyle = FontStyle.Italic) }
                TextButton(onClick = { mark(Mark.UNDERLINE) }) { Text("U", textDecoration = TextDecoration.Underline) }
                TextButton(onClick = { mark(Mark.STRIKE) }) { Text("S", textDecoration = TextDecoration.LineThrough) }
                TextButton(onClick = { rangeColor = !rangeColor }) { Text("선택 영역 색") }
            }
            if (rangeColor) ColorInput(color) { picked -> editor?.colorRange(picked) }
            AndroidView(
                factory = { context ->
                    EditText(context).apply {
                        minLines = 3
                        maxLines = 8
                        setText(initial?.styledText() ?: "")
                        // Ctrl/Cmd shortcuts for the four marks, as in the rest of the app.
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN || !(event.isCtrlPressed || event.isMetaPressed)) {
                                return@setOnKeyListener false
                            }
                            when {
                                keyCode == KeyEvent.KEYCODE_B -> toggle(Mark.BOLD)
                                keyCode == KeyEvent.KEYCODE_I -> toggle(Mark.ITALIC)
                                keyCode == KeyEvent.KEYCODE_U -> toggle(Mark.UNDERLINE)
                                keyCode == KeyEvent.KEYCODE_X && event.isShiftPressed -> toggle(Mark.STRIKE)
                                keyCode == KeyEvent.KEYCODE_S && event.isMetaPressed -> toggle(Mark.STRIKE)
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
                            }
                        })
                        editor = this
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(size, { size = it }, label = { Text("글자 크기 (12–96)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, isError = parsedSize == null, modifier = Modifier.fillMaxWidth())
            Text("글꼴", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TEXT_FONTS.forEach { (family, label) ->
                    SettingsChoiceChip(selected = font == family, onClick = { font = family }, label = label,
                        modifier = Modifier.padding(end = 6.dp))
                }
            }
            Text("기본 글자 색", style = MaterialTheme.typography.bodyMedium)
            ColorInput(color) { color = it }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SkinSwitch(checked = background != 0,
                    onCheckedChange = { background = if (it) 0xFFFFF59D.toInt() else 0 })
                Spacer(Modifier.width(10.dp))
                Text("배경")
            }
            if (background != 0) {
                ColorInput(background) { background = it }
                Text("모서리 ${corner.toInt()}", style = MaterialTheme.typography.bodySmall)
                SkinSlider(corner, { corner = it }, 0f..40f)
            }
            Text("여백 ${padding.toInt()}", style = MaterialTheme.typography.bodySmall)
            SkinSlider(padding, { padding = it }, 0f..40f)
            Text("글자를 선택하고 B·I·U·S를 누르면 그 부분에만, 선택하지 않으면 전체에 적용됩니다.",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = !empty && parsedSize != null,
            onClick = {
                val typed = editor?.text ?: return@TextButton
                val html = if (typed.hasStyle()) Html.toHtml(typed, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE) else null
                onSave(TextBoxContent(typed.toString(), parsedSize ?: 32f, color, html, font, background, corner, padding))
            }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
