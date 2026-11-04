package com.notesis

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * A text box's Markdown drawn as text: headings (#, ##, ###) larger and
 * bold, **bold**, *italic* or _italic_, ~~struck~~, `code`, [links](url) as
 * underlined text, "- " and "* " as bullets, "- [ ]" / "- [x]" as boxes, "> "
 * as a quote. The marks themselves are taken out. Plain text comes back as
 * it was.
 */
internal fun markdownSpanned(source: String): CharSequence {
    val out = SpannableStringBuilder()
    val lines = source.split('\n')
    for ((index, raw) in lines.withIndex()) {
        val start = out.length
        var line = raw
        var heading = 0
        var quote = false
        when {
            line.startsWith("### ") -> { heading = 3; line = line.removePrefix("### ") }
            line.startsWith("## ") -> { heading = 2; line = line.removePrefix("## ") }
            line.startsWith("# ") -> { heading = 1; line = line.removePrefix("# ") }
            line.startsWith("> ") -> { quote = true; line = line.removePrefix("> ") }
        }
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        line = when {
            trimmed.startsWith("- [ ] ") || trimmed.startsWith("* [ ] ") -> " ".repeat(indent) + "☐ " + trimmed.substring(6)
            trimmed.startsWith("- [x] ", true) || trimmed.startsWith("* [x] ", true) ->
                " ".repeat(indent) + "☑ " + trimmed.substring(6)
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ->
                " ".repeat(indent) + "• " + trimmed.substring(2)
            else -> line
        }
        appendInline(out, line)
        val end = out.length
        if (heading > 0 && end > start) {
            out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(when (heading) { 1 -> 1.6f; 2 -> 1.35f; else -> 1.15f }), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (quote && end > start) {
            out.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(0xFF6B6B6B.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(LeadingMarginSpan.Standard(24), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (index < lines.lastIndex) out.append('\n')
    }
    return out
}

/** One line's inline marks, appended to [out] without the marks. */
private fun appendInline(out: SpannableStringBuilder, line: String) {
    var i = 0
    while (i < line.length) {
        val rest = line.substring(i)
        val match = INLINE.find(rest)
        if (match == null || match.range.first != 0) {
            // Copy up to the next mark, or everything.
            val next = match?.range?.first ?: rest.length
            out.append(rest, 0, next)
            i += next
            if (match == null) break
            continue
        }
        val whole = match.value
        val start = out.length
        when {
            whole.startsWith("**") || whole.startsWith("__") -> {
                out.append(match.groups[1]?.value ?: match.groups[2]?.value ?: "")
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            whole.startsWith("~~") -> {
                out.append(match.groups[3]?.value ?: "")
                out.setSpan(StrikethroughSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            whole.startsWith("`") -> {
                out.append(match.groups[4]?.value ?: "")
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(0x22808080), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            whole.startsWith("[") -> {
                out.append(match.groups[5]?.value ?: "")
                out.setSpan(UnderlineSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(0xFF1E66D0.toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            else -> {
                out.append(match.groups[7]?.value ?: match.groups[8]?.value ?: "")
                out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        i += whole.length
    }
}

private val INLINE = Regex(
    """\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|`([^`]+)`|\[([^\]]+)\]\(([^)]*)\)|\*([^*\s][^*]*?)\*|_([^_\s][^_]*?)_""",
)
