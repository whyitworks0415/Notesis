package com.notesis

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The start of a Markdown note as it reads, for its card: the marks taken
 * out, list marks and checkboxes drawn as they look, and where each style
 * runs in the text that is left. [spans] carry the same kinds as
 * [markdownSpans], their places moved to [text].
 */
internal data class MdPreview(val text: String, val spans: List<MdSpan>)

private val PREVIEW_KINDS = setOf(
    MdKind.HEADING, MdKind.BOLD, MdKind.ITALIC, MdKind.STRIKE, MdKind.HIGHLIGHT, MdKind.CODE,
    MdKind.LINK, MdKind.WIKILINK, MdKind.TAG, MdKind.QUOTE, MdKind.CODE_BLOCK, MdKind.DONE_TEXT,
)

/** How much of a Markdown note its card reads, cut at a line. */
internal fun markdownCardCut(source: String, maxChars: Int = 1200): String =
    if (source.length <= maxChars) source
    else source.substring(0, source.lastIndexOf('\n', maxChars).takeIf { it > 0 } ?: maxChars)

/** [source] read as it shows, up to about [maxChars] of it, cut at a line. */
internal fun markdownPreview(source: String, maxChars: Int = 900): MdPreview {
    val cut = if (source.length <= maxChars) source
        else source.substring(0, source.lastIndexOf('\n', maxChars).takeIf { it > 0 } ?: maxChars)
    val spans = markdownSpans(cut)
    val dropped = BooleanArray(cut.length)
    // What a mark is drawn as instead, from where it starts to where it ends.
    val drawn = HashMap<Int, Pair<Int, String>>()
    for (span in spans) when (span.kind) {
        MdKind.MARKER, MdKind.FRONT_MATTER, MdKind.FENCE ->
            for (i in span.start until span.end.coerceAtMost(cut.length)) dropped[i] = true
        MdKind.BULLET -> drawn[span.start] = span.end to (if (span.target?.firstOrNull()?.isDigit() == true) "${span.target} " else "• ")
        MdKind.TASK_OPEN -> drawn[span.start] = span.end to "☐ "
        MdKind.TASK_DONE -> drawn[span.start] = span.end to "☑ "
        MdKind.RULE -> drawn[span.start] = span.end to "———"
        else -> Unit
    }
    val out = StringBuilder()
    // Where each place in the source lands in the text left.
    val at = IntArray(cut.length + 1)
    var i = 0
    while (i < cut.length) {
        val swap = drawn[i]
        if (swap != null) {
            val end = swap.first.coerceIn(i + 1, cut.length)
            for (k in i until end) at[k] = out.length
            out.append(swap.second)
            i = end
            continue
        }
        at[i] = out.length
        if (!dropped[i]) out.append(cut[i])
        i++
    }
    at[cut.length] = out.length
    val moved = spans.filter { it.kind in PREVIEW_KINDS }.mapNotNull { span ->
        val start = at[span.start.coerceIn(0, cut.length)]
        val end = at[span.end.coerceIn(0, cut.length)]
        if (end > start) span.copy(start = start, end = end) else null
    }
    return MdPreview(out.toString(), moved)
}

/** [preview] as styled text for a card [body] sp tall, its headings larger and its marks as they look. */
internal fun markdownPreviewText(preview: MdPreview, body: TextUnit, accent: Color, muted: Color): AnnotatedString =
    buildAnnotatedString {
        append(preview.text)
        for (span in preview.spans) {
            val style = when (span.kind) {
                MdKind.HEADING -> SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = body * when (span.level) { 1 -> 1.6f; 2 -> 1.35f; else -> 1.15f },
                )
                MdKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                MdKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                MdKind.STRIKE, MdKind.DONE_TEXT -> SpanStyle(textDecoration = TextDecoration.LineThrough, color = muted)
                MdKind.HIGHLIGHT -> SpanStyle(background = Color(0x55FFE066))
                MdKind.CODE, MdKind.CODE_BLOCK -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14000000))
                MdKind.LINK, MdKind.WIKILINK -> SpanStyle(color = accent)
                MdKind.TAG -> SpanStyle(color = accent, fontSize = body * 0.9f)
                MdKind.QUOTE -> SpanStyle(color = muted, fontStyle = FontStyle.Italic)
                else -> null
            } ?: continue
            addStyle(style, span.start, span.end)
        }
    }

/** The body size of a note's words on its card, small enough for a page of them to show. */
internal val MD_CARD_BODY = 9.sp

/**
 * A handwritten note's text as Markdown: [title] as the heading, then each
 * page under its number with the pieces read off it, one paragraph each.
 */
internal fun inkMarkdown(title: String, pages: List<List<String>>): String {
    val blocks = mutableListOf("# " + title.ifBlank { "Untitled" })
    pages.forEachIndexed { i, pieces ->
        blocks += "## ${i + 1}"
        if (pieces.isEmpty()) blocks += "_(nothing read on this page yet)_" else blocks += pieces
    }
    return blocks.joinToString("\n\n") + "\n"
}
