package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Where text is being typed straight onto the page: the page and the point
 * in it (or the box being changed), and where that is in the window, with
 * how large a page unit is there.
 */
internal data class InlineTextTarget(
    val position: Triple<Int, Float, Float>?,
    val replacing: PageImage?,
    val windowAt: Offset,
    val pageScale: Float,
    val initial: TextBoxContent?,
)

/**
 * Text typed where the page was tapped, at about the size it will have
 * there, instead of in a dialog. Markdown is drawn when it is done. A thin
 * strip over it sizes the text, opens the full styling, and finishes.
 */
@Composable
internal fun InlineTextEditor(
    target: InlineTextTarget,
    /** Where the text's corner is in the window and a page unit's size in pixels, now; null to stay put. */
    locate: () -> Pair<Offset, Float>?,
    /** The text is Markdown, drawn as it is typed. */
    markdown: Boolean,
    text: String,
    onText: (String) -> Unit,
    size: Float,
    onSize: (Float) -> Unit,
    onMoreStyles: () -> Unit,
    onDone: () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current
    var windowAt by remember(target) { mutableStateOf(target.windowAt) }
    var pageScale by remember(target) { mutableStateOf(target.pageScale) }
    LaunchedEffect(target) { runCatching { focus.requestFocus() } }
    // Kept on the page: each frame, where its corner has gone and how far the page is zoomed.
    LaunchedEffect(target) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val (at, scale) = locate() ?: continue
            if (at != windowAt) windowAt = at
            if (scale != pageScale) pageScale = scale
        }
    }
    // The box's text size in page units, on screen at the page's zoom.
    val fontSize = with(density) { (size * pageScale).coerceIn(4f, 400f).toSp() }
    // A box given a width by resizing keeps it here too, the text wrapping inside.
    val wrap = target.initial?.wrapWidth?.takeIf { it > 0f }?.let { with(density) { (it * pageScale).toDp() } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        Column(
            Modifier.offset {
                IntOffset((windowAt.x - origin.x).toInt(),
                    (windowAt.y - origin.y - 44.dp.toPx()).toInt().coerceAtLeast(0))
            },
        ) {
            Row(
                Modifier.background(MaterialTheme.colorScheme.surface, Ui.Shape.chip)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, Ui.Shape.chip)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = { onSize((size - 4f).coerceAtLeast(TextBoxContent.SIZE_RANGE.start)) }) { Text("A−") }
                TextButton(onClick = { onSize((size + 4f).coerceAtMost(TextBoxContent.SIZE_RANGE.endInclusive)) }) { Text("A+") }
                TextButton(onClick = onMoreStyles) { Text("Styles") }
                TextButton(onClick = onDone) { Text("Done") }
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                textStyle = inlineTextStyle(target.initial, fontSize),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (markdown) MarkdownLiveTransformation else VisualTransformation.None,
                modifier = Modifier.focusRequester(focus)
                    .then(if (wrap != null) Modifier.width(wrap) else Modifier.widthIn(min = 160.dp, max = 720.dp))
                    .background(target.initial?.background?.takeIf { it != 0 }?.let { Color(it) } ?: Color.White.copy(alpha = 0.92f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    .padding(6.dp),
            )
        }
    }
}

/**
 * The typed text in the box's own look, as it will be drawn: its font, bold
 * and italic, colour, letter and line spacing and alignment, so what is
 * typed on the page does not jump when it is put down.
 */
internal fun inlineTextStyle(content: TextBoxContent?, fontSize: androidx.compose.ui.unit.TextUnit): TextStyle {
    val box = content ?: TextBoxContent("")
    return TextStyle(
        fontSize = fontSize,
        color = Color(box.color),
        fontFamily = androidx.compose.ui.text.font.FontFamily(box.typeface()),
        letterSpacing = androidx.compose.ui.unit.TextUnit(box.letterSpacing, androidx.compose.ui.unit.TextUnitType.Em),
        lineHeight = androidx.compose.ui.unit.TextUnit(box.lineSpacing * 1.2f, androidx.compose.ui.unit.TextUnitType.Em),
        textAlign = when (box.align) {
            TextBoxContent.ALIGN_CENTER -> androidx.compose.ui.text.style.TextAlign.Center
            TextBoxContent.ALIGN_END -> androidx.compose.ui.text.style.TextAlign.End
            TextBoxContent.ALIGN_JUSTIFY -> androidx.compose.ui.text.style.TextAlign.Justify
            else -> androidx.compose.ui.text.style.TextAlign.Start
        },
    )
}

/**
 * What a link leads to, shown beside the pen resting over it: the note's or
 * page's picture and its name, or the address. Draws only; it takes no touch.
 */
@Composable
internal fun LinkPreviewCard(windowAt: Offset, title: String, file: java.io.File?, picture: android.graphics.Bitmap?) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val fromFile = rememberFileBitmap(file, 360)
    val shown = picture ?: fromFile
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        Column(
            Modifier.offset {
                IntOffset((windowAt.x - origin.x + 16.dp.toPx()).toInt(), (windowAt.y - origin.y + 16.dp.toPx()).toInt())
            }.widthIn(max = 220.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (shown != null) androidx.compose.foundation.Image(
                bitmap = shown.asImageBitmap(), contentDescription = null,
                modifier = Modifier.widthIn(max = 204.dp).heightIn(max = 260.dp),
            )
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2)
        }
    }
}


/**
 * Markdown drawn as it is typed: the text itself is left exactly as written
 * (every mark stays, so the cursor never jumps), with headings larger and
 * bold, **bold**, *italic*, ~~struck~~ and `code` styled, links underlined,
 * and the marks themselves faint.
 */
internal object MarkdownLiveTransformation : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText =
        androidx.compose.ui.text.input.TransformedText(markdownLive(text.text),
            androidx.compose.ui.text.input.OffsetMapping.Identity)
}

private val LIVE_MARK = androidx.compose.ui.graphics.Color(0x66808080)
private val LIVE_INLINE = Regex("""\*\*(.+?)\*\*|~~(.+?)~~|`([^`]+)`|\[([^\]]+)\]\(([^)]*)\)|(?<![*\w])\*([^*\s][^*]*?)\*(?!\*)|(?<!\w)_([^_\s][^_]*?)_(?!\w)|\$\$(.+?)\$\$|(?<!\\)\$(?![\s$])((?:\\.|[^$\\])+?)(?<!\s)\$(?!\d)|==(.+?)==""")
private val LIVE_MATH = androidx.compose.ui.graphics.Color(0xFF7B1FA2)

internal fun markdownLive(source: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(source)
        var lineStart = 0
        for (line in source.split('\n')) {
            val end = lineStart + line.length
            val heading = Regex("^(#{1,6}) ").find(line)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize = androidx.compose.ui.unit.TextUnit(when (level) { 1 -> 1.6f; 2 -> 1.4f; 3 -> 1.2f; 4 -> 1.1f; 5 -> 1f; else -> 0.95f },
                            androidx.compose.ui.unit.TextUnitType.Em)), lineStart, end)
                    addStyle(androidx.compose.ui.text.SpanStyle(color = LIVE_MARK), lineStart, lineStart + level + 1)
                }
                line.startsWith("> ") -> {
                    addStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = androidx.compose.ui.graphics.Color(0xFF6B6B6B)), lineStart, end)
                }
                Regex("^\\s*([-*+]|\\d+[.)]) ").containsMatchIn(line) -> {
                    val m = Regex("^\\s*([-*+]|\\d+[.)])( \\[[ xX]\\])? ").find(line)!!
                    addStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF1E66D0),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), lineStart, lineStart + m.value.length)
                }
            }
            for (m in LIVE_INLINE.findAll(line)) {
                val s = lineStart + m.range.first
                val e = lineStart + m.range.last + 1
                val whole = m.value
                when {
                    whole.startsWith("**") -> {
                        addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), s, e)
                        addStyle(androidx.compose.ui.text.SpanStyle(color = LIVE_MARK), s, s + 2)
                        addStyle(androidx.compose.ui.text.SpanStyle(color = LIVE_MARK), e - 2, e)
                    }
                    whole.startsWith("~~") -> {
                        addStyle(androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough), s, e)
                        addStyle(androidx.compose.ui.text.SpanStyle(color = LIVE_MARK), s, s + 2)
                        addStyle(androidx.compose.ui.text.SpanStyle(color = LIVE_MARK), e - 2, e)
                    }
                    whole.startsWith("`") -> {
                        addStyle(androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            background = androidx.compose.ui.graphics.Color(0x22808080)), s, e)
                    }
                    whole.startsWith("$") -> {
                        // A formula stays as typed while it is written, in its own colour; it is drawn when the box is put down.
                        addStyle(androidx.compose.ui.text.SpanStyle(color = LIVE_MATH,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif), s, e)
                    }
                    whole.startsWith("==") -> {
                        addStyle(androidx.compose.ui.text.SpanStyle(background = androidx.compose.ui.graphics.Color(0x80FFE44D)), s, e)
                        addStyle(androidx.compose.ui.text.SpanStyle(color = LIVE_MARK), s, s + 2)
                        addStyle(androidx.compose.ui.text.SpanStyle(color = LIVE_MARK), e - 2, e)
                    }
                    whole.startsWith("[") -> {
                        addStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF1E66D0),
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), s, e)
                    }
                    else -> {
                        addStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), s, e)
                        addStyle(androidx.compose.ui.text.SpanStyle(color = LIVE_MARK), s, s + 1)
                        addStyle(androidx.compose.ui.text.SpanStyle(color = LIVE_MARK), e - 1, e)
                    }
                }
            }
            lineStart = end + 1
        }
    }
