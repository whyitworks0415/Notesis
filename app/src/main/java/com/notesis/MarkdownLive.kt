package com.notesis

/*
 * Markdown as the block editor sees it (Todo.md, E1), without Android: the
 * note cut into blocks the way Obsidian's live preview and Notion show it -
 * a heading, a list item, a code block, a table, each drawn as it reads and
 * opened for editing when touched - and the text of each drawn with its
 * marks gone, by the rules CommonMark and GitHub give, plus Obsidian's own
 * ([[links]], ![[embeds]], ==highlights==, %%comments%%, callouts, $math$).
 *
 * Kept apart from the screen so every rule here is tested on its own.
 */

// ---- blocks -------------------------------------------------------------------------------------

internal enum class LiveKind {
    BLANK, PARAGRAPH, HEADING, LIST_ITEM, QUOTE, CODE, MATH, TABLE, RULE, FRONT_MATTER, COMMENT, FOOTNOTE, LINK_DEF,
}

/**
 * One block: [start, end) of the source, the line break after it left out,
 * so blocks and the breaks between them cover the text exactly. [level] is
 * a heading's level or a list item's depth from 0; [info] a code block's
 * language.
 */
internal data class LiveBlock(val start: Int, val end: Int, val kind: LiveKind, val level: Int = 0, val info: String = "") {
    fun text(source: String): String = source.substring(start, end)
    /** Whether the cursor at [offset] is in this block: its ends count. */
    operator fun contains(offset: Int): Boolean = offset in start..end
}

private val ATX = Regex("^ {0,3}(#{1,6})(?:[ \t]+|$)")
private val SETEXT_1 = Regex("^ {0,3}=+[ \t]*$")
private val SETEXT_2 = Regex("^ {0,3}-+[ \t]*$")
private val THEMATIC = Regex("^ {0,3}([-*_])([ \t]*\\1){2,}[ \t]*$")
private val QUOTE_LINE = Regex("^ {0,3}>")
private val LIST_ITEM = Regex("^([ \t]*)([-*+]|\\d{1,9}[.)])(?:[ \t]+|$)")
private val FENCE_OPEN = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val FOOTNOTE_DEF = Regex("^ {0,3}\\[\\^([^\\]\\s]+)]:")
private val LINK_DEF_LINE = Regex("^ {0,3}\\[([^\\]]+)]:[ \t]*(<[^>]*>|\\S+)(?:[ \t]+(\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?[ \t]*$")
private val TABLE_RULE = Regex("^ {0,3}\\|?[ \t]*:?-+:?[ \t]*(\\|[ \t]*:?-+:?[ \t]*)*\\|?[ \t]*$")

/** Columns a run of spaces and tabs takes, a tab to the next stop of four. */
internal fun indentWidth(prefix: String): Int {
    var width = 0
    for (c in prefix) width = if (c == '\t') width + 4 - width % 4 else width + 1
    return width
}

/** [text] cut into blocks, in order, covering it all. */
internal fun liveBlocks(text: String): List<LiveBlock> {
    val lines = lineRanges(text)
    fun line(i: Int) = text.substring(lines[i].first, lines[i].last + 1)
    val blocks = ArrayList<LiveBlock>()
    fun add(from: Int, to: Int, kind: LiveKind, level: Int = 0, info: String = "") {
        blocks += LiveBlock(lines[from].first, lines[to].last + 1, kind, level, info)
    }
    // The content columns of the list items still open, for how deep the next one sits.
    val listIndents = ArrayList<Int>()
    var i = 0
    if (lines.size > 1 && line(0).trimEnd() == "---") {
        val close = (1 until lines.size).firstOrNull { line(it).trimEnd() == "---" || line(it).trimEnd() == "..." }
        if (close != null) { add(0, close, LiveKind.FRONT_MATTER); i = close + 1 }
    }
    while (i < lines.size) {
        val raw = line(i)
        val from = i
        val fence = FENCE_OPEN.find(raw)?.takeIf { m -> !(m.groupValues[1][0] == '`' && m.groupValues[2].contains('`')) }
        when {
            fence != null -> {
                val mark = fence.groupValues[1]
                var j = i + 1
                while (j < lines.size) {
                    val t = line(j).trim()
                    if (t.length >= mark.length && t.all { it == mark[0] } && line(j).indexOf(mark[0]) <= 3) break
                    j++
                }
                add(from, minOf(j, lines.size - 1), LiveKind.CODE, info = fence.groupValues[2].trim().substringBefore(' '))
                i = j + 1
                listIndents.clear()
                continue
            }
            raw.trimStart().startsWith("$$") -> {
                val body = raw.trimStart().removePrefix("$$")
                var j = i
                if (!body.trimEnd().endsWith("$$") || body.isBlank()) {
                    j = i + 1
                    while (j < lines.size && !line(j).trimEnd().endsWith("$$")) j++
                }
                add(from, minOf(j, lines.size - 1), LiveKind.MATH)
                i = j + 1
                continue
            }
            raw.trimStart().startsWith("%%") -> {
                val rest = raw.trimStart().removePrefix("%%")
                var j = i
                if (!rest.contains("%%")) {
                    j = i + 1
                    while (j < lines.size && !line(j).contains("%%")) j++
                }
                add(from, minOf(j, lines.size - 1), LiveKind.COMMENT)
                i = j + 1
                continue
            }
            raw.contains('|') && i + 1 < lines.size && TABLE_RULE.matches(line(i + 1)) && line(i + 1).contains('-') &&
                (raw.trim().startsWith("|") || line(i + 1).contains('|')) -> {
                var j = i + 2
                while (j < lines.size && line(j).contains('|') && line(j).isNotBlank()) j++
                add(from, j - 1, LiveKind.TABLE)
                i = j
                listIndents.clear()
                continue
            }
            QUOTE_LINE.containsMatchIn(raw) -> {
                var j = i + 1
                while (j < lines.size && QUOTE_LINE.containsMatchIn(line(j))) j++
                add(from, j - 1, LiveKind.QUOTE)
                i = j
                listIndents.clear()
                continue
            }
            raw.isBlank() -> add(i, i, LiveKind.BLANK)
            ATX.containsMatchIn(raw) -> { add(i, i, LiveKind.HEADING, ATX.find(raw)!!.groupValues[1].length); listIndents.clear() }
            FOOTNOTE_DEF.containsMatchIn(raw) -> add(i, i, LiveKind.FOOTNOTE)
            LINK_DEF_LINE.matches(raw) -> add(i, i, LiveKind.LINK_DEF)
            LIST_ITEM.containsMatchIn(raw) && !THEMATIC.matches(raw) -> {
                val indent = indentWidth(LIST_ITEM.find(raw)!!.groupValues[1])
                while (listIndents.isNotEmpty() && listIndents.last() >= indent) listIndents.removeAt(listIndents.lastIndex)
                add(i, i, LiveKind.LIST_ITEM, listIndents.size)
                listIndents += indent
            }
            THEMATIC.matches(raw) -> { add(i, i, LiveKind.RULE); listIndents.clear() }
            // A line under a list item, indented, carries the item on rather than starting code.
            indentWidth(raw.takeWhile { it == ' ' || it == '\t' }) >= 4 && listIndents.isEmpty() &&
                (blocks.isEmpty() || blocks.last().kind == LiveKind.BLANK || blocks.last().kind == LiveKind.CODE && blocks.last().info == INDENTED) -> {
                var j = i + 1
                while (j < lines.size && indentWidth(line(j).takeWhile { it == ' ' || it == '\t' }) >= 4 && line(j).isNotBlank()) j++
                add(from, j - 1, LiveKind.CODE, info = INDENTED)
                i = j
                continue
            }
            // A line with === or --- under it is a heading of two lines.
            i + 1 < lines.size && line(i + 1).isNotBlank() && (SETEXT_1.matches(line(i + 1)) || SETEXT_2.matches(line(i + 1))) -> {
                add(i, i + 1, LiveKind.HEADING, if (SETEXT_1.matches(line(i + 1))) 1 else 2)
                i += 2
                listIndents.clear()
                continue
            }
            else -> {
                add(i, i, LiveKind.PARAGRAPH)
                if (indentWidth(raw.takeWhile { it == ' ' || it == '\t' }) == 0) listIndents.clear()
            }
        }
        i++
    }
    return blocks
}

/** The code block's [LiveBlock.info] for one indented four spaces rather than fenced. */
internal const val INDENTED = "\u0000indented"

/** The block the cursor at [offset] is in, by index; the first one that holds it. */
internal fun blockAt(blocks: List<LiveBlock>, offset: Int): Int {
    if (blocks.isEmpty()) return -1
    var lo = 0
    var hi = blocks.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (blocks[mid].end < offset) lo = mid + 1 else hi = mid
    }
    return lo
}

// ---- inline -------------------------------------------------------------------------------------

internal enum class InlineKind {
    BOLD, ITALIC, STRIKE, HIGHLIGHT, CODE, LINK, WIKILINK, EMBED, IMAGE, TAG, MATH, FOOTNOTE_REF,
    UNDERLINE, SUB, SUP, KBD, COMMENT,
}

/** [start, end) of the shown text is [kind]; [target] is where a link goes, an image's source, a tag's name. */
internal data class InlineSpan(val start: Int, val end: Int, val kind: InlineKind, val target: String? = null)

/**
 * A line of Markdown as it reads: [shown] with the marks gone, its [spans],
 * and for each shown character the source offset it came from ([sourceOf],
 * one longer than [shown], its last entry the end of the source), so a tap
 * on the text lands the cursor in the right place.
 */
internal class InlineText(val shown: String, val spans: List<InlineSpan>, val sourceOf: IntArray) {
    /** The source offset for a cursor before shown character [at]. */
    fun sourceAt(at: Int): Int = sourceOf[at.coerceIn(0, shown.length)]
}

/** Reference link definitions, `[name]: url`, by name in lower case. */
internal fun linkDefinitions(text: String): Map<String, String> =
    lineRanges(text).mapNotNull { r -> LINK_DEF_LINE.find(text.substring(r.first, r.last + 1)) }
        .filter { !it.groupValues[1].startsWith("^") }
        .associate { it.groupValues[1].trim().lowercase() to it.groupValues[2].removeSurrounding("<", ">") }

private const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
private fun Char.isPunct() = this in PUNCTUATION || Character.getType(this).let {
    it == Character.CONNECTOR_PUNCTUATION.toInt() || it == Character.DASH_PUNCTUATION.toInt() ||
        it == Character.START_PUNCTUATION.toInt() || it == Character.END_PUNCTUATION.toInt() ||
        it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || it == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
        it == Character.OTHER_PUNCTUATION.toInt() || it == Character.MATH_SYMBOL.toInt() || it == Character.CURRENCY_SYMBOL.toInt()
}

private val BARE_URL = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<]+")
private val AUTOLINK = Regex("^<((?i:[a-z][a-z0-9+.-]{1,31}):[^\\s<>]*|[\\w.!#$%&'*+/=?^`{|}~-]+@[\\w-]+(?:\\.[\\w-]+)+)>")
private val INLINE_TAG = Regex("^#([\\p{L}\\p{N}_/-]*[\\p{L}_/-][\\p{L}\\p{N}_/-]*)")
private val HTML_TAG = Regex("^<(/?)([a-zA-Z][a-zA-Z0-9]*)\\b[^<>]*?(/?)>")
private val HTML_STYLES = mapOf(
    "b" to InlineKind.BOLD, "strong" to InlineKind.BOLD, "i" to InlineKind.ITALIC, "em" to InlineKind.ITALIC,
    "s" to InlineKind.STRIKE, "del" to InlineKind.STRIKE, "strike" to InlineKind.STRIKE, "u" to InlineKind.UNDERLINE,
    "ins" to InlineKind.UNDERLINE, "mark" to InlineKind.HIGHLIGHT, "sub" to InlineKind.SUB, "sup" to InlineKind.SUP,
    "kbd" to InlineKind.KBD, "code" to InlineKind.CODE,
)

/**
 * [source] (a line or a few, with no block marks) as it reads. [base] is
 * added to every source offset, for a piece cut out of a longer text;
 * [definitions] are the note's `[name]: url` lines.
 */
internal fun inlineText(source: String, base: Int = 0, definitions: Map<String, String> = emptyMap()): InlineText =
    InlineParser(source, base, definitions).run()

private class InlineParser(val src: String, val base: Int, val definitions: Map<String, String>) {
    /** A piece of the output: plain characters, a run of * _ ~ = to be matched, or a finished piece. */
    private sealed class Node
    private class Chars(val text: StringBuilder = StringBuilder(), val from: MutableList<Int> = mutableListOf()) : Node()
    private class Delim(val char: Char, val original: Int, val at: Int, val canOpen: Boolean, val canClose: Boolean) : Node() {
        var count = original
        /** False once it lies inside a match: then it can match nothing outside it. */
        var active = true
        var opened = false
        var closed = false
        /** Its spare marks are drawn on its left only when it opened and closed nothing. */
        val spareLeft get() = opened && !closed
    }
    private class Atom(val shown: String, val from: IntArray, val kinds: List<Pair<InlineKind, String?>>,
                       val inner: InlineText? = null) : Node()
    private class Style(val opener: Node, val closer: Node, val kind: InlineKind)

    private val nodes = ArrayList<Node>()
    private val styles = ArrayList<Style>()
    private val htmlOpen = ArrayList<Pair<String, Node>>()

    private fun chars(): Chars = (nodes.lastOrNull() as? Chars) ?: Chars().also { nodes += it }
    private fun literal(c: Char, at: Int) { chars().also { it.text.append(c); it.from += at } }
    private fun literal(text: String, at: Int) { for ((k, c) in text.withIndex()) literal(c, at + k) }
    private fun marker(): Node = Atom("", IntArray(0), emptyList()).also { nodes += it }

    fun run(): InlineText {
        var i = 0
        while (i < src.length) i = step(i)
        resolveEmphasis()
        return build()
    }

    /** Reads what starts at [i]; where the next thing starts. */
    private fun step(i: Int): Int {
        val c = src[i]
        when (c) {
            '\\' -> if (i + 1 < src.length && src[i + 1] in PUNCTUATION) { literal(src[i + 1], i + 1); return i + 2 }
                else if (i + 1 < src.length && src[i + 1] == '\n') { literal('\n', i + 1); return i + 2 }
            '`' -> return codeSpan(i)
            '$' -> math(i)?.let { return it }
            '%' -> if (src.startsWith("%%", i)) {
                val close = src.indexOf("%%", i + 2)
                if (close >= 0) {
                    nodes += Atom("", IntArray(0), listOf(InlineKind.COMMENT to src.substring(i + 2, close)))
                    return close + 2
                }
            }
            '!' -> {
                if (src.startsWith("![[", i)) wiki(i + 1, embed = true)?.let { return it }
                if (src.startsWith("![", i)) image(i)?.let { return it }
            }
            '[' -> {
                if (src.startsWith("[[", i)) wiki(i, embed = false)?.let { return it }
                if (src.startsWith("[^", i)) footnote(i)?.let { return it }
                link(i)?.let { return it }
            }
            '<' -> {
                AUTOLINK.find(src.substring(i))?.let { m ->
                    val address = m.groupValues[1]
                    val target = if ('@' in address && ':' !in address) "mailto:$address" else address
                    atom(address, i + 1, InlineKind.LINK, target)
                    return i + m.value.length
                }
                html(i)?.let { return it }
            }
            '#' -> if (i == 0 || src[i - 1].isWhitespace() || src[i - 1] == '(') {
                INLINE_TAG.find(src.substring(i))?.let { m ->
                    atom(m.value, i, InlineKind.TAG, m.groupValues[1])
                    return i + m.value.length
                }
            }
            '*', '_' -> return delimiter(i, c, 1)
            '~', '=' -> if (src.startsWith("$c$c", i)) return delimiter(i, c, 2)
            'h', 'H', 'w', 'W' -> if (i == 0 || !src[i - 1].isLetterOrDigit()) bareUrl(i)?.let { return it }
        }
        literal(c, i)
        return i + 1
    }

    private fun atom(shown: String, from: Int, kind: InlineKind, target: String?) {
        nodes += Atom(shown, IntArray(shown.length) { from + it }, listOf(kind to target))
    }

    private fun codeSpan(i: Int): Int {
        var n = 0
        while (i + n < src.length && src[i + n] == '`') n++
        var j = i + n
        while (j < src.length) {
            if (src[j] == '`') {
                var m = 0
                while (j + m < src.length && src[j + m] == '`') m++
                if (m == n) {
                    var s = i + n
                    var e = j
                    // One space each side is taken off, so `` `x` `` can show backticks.
                    if (e - s >= 2 && src[s] == ' ' && src[e - 1] == ' ' && src.substring(s, e).isNotBlank()) { s++; e-- }
                    atom(src.substring(s, e).replace('\n', ' '), s, InlineKind.CODE, null)
                    return j + m
                }
                j += m
            } else j++
        }
        literal("`".repeat(n), i)
        return i + n
    }

    private fun math(i: Int): Int? {
        if (src.startsWith("$$", i)) {
            val close = src.indexOf("$$", i + 2)
            if (close > i + 2) { atom(src.substring(i + 2, close), i + 2, InlineKind.MATH, "display"); return close + 2 }
            return null
        }
        // $x$: no space just inside either mark, and no digit straight after, so "$5 and $6" stays money.
        if (i + 1 >= src.length || src[i + 1].isWhitespace()) return null
        var j = i + 1
        while (j < src.length && src[j] != '\n') {
            if (src[j] == '\\') { j += 2; continue }
            if (src[j] == '$') {
                if (!src[j - 1].isWhitespace() && (j + 1 >= src.length || !src[j + 1].isDigit())) {
                    atom(src.substring(i + 1, j), i + 1, InlineKind.MATH, null)
                    return j + 1
                }
                return null
            }
            j++
        }
        return null
    }

    /** [[target|alias]] with its [ at [i]; for an embed the ! is just before. */
    private fun wiki(i: Int, embed: Boolean): Int? {
        val close = src.indexOf("]]", i + 2)
        if (close < 0) return null
        val inside = src.substring(i + 2, close)
        if (inside.isBlank() || '\n' in inside || '[' in inside) return null
        val pipe = inside.indexOf('|')
        val target = (if (pipe < 0) inside else inside.substring(0, pipe)).trim()
        if (embed) {
            val shown = target
            nodes += Atom(shown, IntArray(shown.length) { (i + 2 + it).coerceAtMost(close - 1) }, listOf(InlineKind.EMBED to inside.trim()))
        } else {
            val alias = if (pipe < 0) "" else inside.substring(pipe + 1)
            val from = if (alias.isNotBlank()) i + 2 + pipe + 1 + (alias.length - alias.trimStart().length) else i + 2
            val shown = alias.trim().ifEmpty { target.replace(Regex("#\\^?"), " › ").trim() }
            nodes += Atom(shown, IntArray(shown.length) { (from + it).coerceAtMost(close - 1) }, listOf(InlineKind.WIKILINK to target))
        }
        return close + 2
    }

    private fun footnote(i: Int): Int? {
        val close = src.indexOf(']', i + 2)
        if (close < 0) return null
        val id = src.substring(i + 2, close)
        if (id.isEmpty() || id.any { it.isWhitespace() }) return null
        atom(id, i + 2, InlineKind.FOOTNOTE_REF, id)
        return close + 1
    }

    /** The bracket that closes the one at [open], brackets inside balanced, or -1. */
    private fun closeBracket(open: Int): Int {
        var depth = 0
        var j = open
        while (j < src.length) {
            when (src[j]) {
                '\\' -> j++
                '`' -> { val n = src.indexOf('`', j + 1); if (n > 0) j = n }
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return j }
            }
            j++
        }
        return -1
    }

    /** `(url "title")` at [at]: the address and where it ends. */
    private fun destination(at: Int): Pair<String, Int>? {
        if (at >= src.length || src[at] != '(') return null
        var j = at + 1
        while (j < src.length && src[j] == ' ') j++
        val url = StringBuilder()
        if (j < src.length && src[j] == '<') {
            val end = src.indexOf('>', j)
            if (end < 0) return null
            url.append(src, j + 1, end)
            j = end + 1
        } else {
            var depth = 0
            while (j < src.length && !src[j].isWhitespace()) {
                if (src[j] == '(') depth++
                if (src[j] == ')') { if (depth == 0) break; depth-- }
                if (src[j] == '\\' && j + 1 < src.length) j++
                url.append(src[j]); j++
            }
        }
        while (j < src.length && src[j] == ' ') j++
        if (j < src.length && src[j] in "\"'(") {
            val closer = if (src[j] == '(') ')' else src[j]
            val end = src.indexOf(closer, j + 1)
            if (end < 0) return null
            j = end + 1
            while (j < src.length && src[j] == ' ') j++
        }
        if (j >= src.length || src[j] != ')') return null
        return url.toString() to j + 1
    }

    private fun link(i: Int): Int? {
        val close = closeBracket(i)
        if (close < 0) return null
        val label = src.substring(i + 1, close)
        val (target, end) = destination(close + 1) ?: run {
            // [text][name], [text][] and [name], from the note's definitions.
            var name = label
            var after = close + 1
            if (close + 1 < src.length && src[close + 1] == '[') {
                val c2 = src.indexOf(']', close + 2)
                if (c2 > 0) { if (c2 > close + 2) name = src.substring(close + 2, c2); after = c2 + 1 }
            }
            val url = definitions[name.trim().lowercase()] ?: return null
            url to after
        }
        val inner = InlineParser(label, base + i + 1, definitions).run()
        nodes += Atom(inner.shown, IntArray(inner.shown.length) { inner.sourceOf[it] - base }, listOf(InlineKind.LINK to target), inner)
        return end
    }

    private fun image(i: Int): Int? {
        val close = closeBracket(i + 1)
        if (close < 0) return null
        val alt = src.substring(i + 2, close)
        val (target, end) = destination(close + 1) ?: return null
        nodes += Atom(alt, IntArray(alt.length) { i + 2 + it }, listOf(InlineKind.IMAGE to target))
        return end
    }

    private fun html(i: Int): Int? {
        val m = HTML_TAG.find(src.substring(i)) ?: return null
        val name = m.groupValues[2].lowercase()
        val end = i + m.value.length
        if (name == "br") { nodes += Atom("\n", intArrayOf(i), emptyList()); return end }
        val kind = HTML_STYLES[name] ?: return null
        if (m.groupValues[1].isEmpty()) {
            htmlOpen += name to marker()
        } else {
            val k = htmlOpen.indexOfLast { it.first == name }
            if (k < 0) return end
            val opener = htmlOpen[k].second
            while (htmlOpen.size > k) htmlOpen.removeAt(htmlOpen.lastIndex)
            styles += Style(opener, marker(), kind)
        }
        return end
    }

    private fun bareUrl(i: Int): Int? {
        val m = BARE_URL.find(src, i)?.takeIf { it.range.first == i } ?: return null
        var url = m.value
        // Trailing punctuation belongs to the sentence, and a ) only when it closes one in the address.
        while (url.isNotEmpty() && url.last() in "?!.,:*_~;'\"") url = url.dropLast(1)
        while (url.endsWith(")") && url.count { it == ')' } > url.count { it == '(' }) url = url.dropLast(1)
        if (url.length < 5) return null
        atom(url, i, InlineKind.LINK, if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url)
        return i + url.length
    }

    private fun delimiter(i: Int, c: Char, unit: Int): Int {
        var n = 0
        while (i + n < src.length && src[i + n] == c) n++
        if (unit == 2 && n != 2) { literal(src.substring(i, i + n), i); return i + n }
        val before = if (i == 0) ' ' else src[i - 1]
        val after = if (i + n >= src.length) ' ' else src[i + n]
        val leftFlanking = !after.isWhitespace() && (!after.isPunct() || before.isWhitespace() || before.isPunct())
        val rightFlanking = !before.isWhitespace() && (!before.isPunct() || after.isWhitespace() || after.isPunct())
        val (open, close) = if (c == '_') {
            (leftFlanking && (!rightFlanking || before.isPunct())) to (rightFlanking && (!leftFlanking || after.isPunct()))
        } else leftFlanking to rightFlanking
        nodes += Delim(c, n, i, open, close)
        return i + n
    }

    /** Matches the * _ ~~ == runs as CommonMark does: each closer with the nearest opener of its kind before it. */
    private fun resolveEmphasis() {
        val delims = nodes.filterIsInstance<Delim>()
        for (ci in delims.indices) {
            val closer = delims[ci]
            if (!closer.canClose) continue
            var oi = ci - 1
            while (closer.count > 0 && oi >= 0) {
                val opener = delims[oi]
                if (!opener.active || opener.count == 0 || !opener.canOpen || opener.char != closer.char) { oi--; continue }
                // The rule of three: runs that could both open and close do not pair when their lengths add to a multiple of 3.
                if (opener.char in "*_" && (closer.canOpen || opener.canClose) &&
                    (opener.original + closer.original) % 3 == 0 && !(opener.original % 3 == 0 && closer.original % 3 == 0)) {
                    oi--; continue
                }
                val use = when {
                    opener.char in "~=" -> if (opener.count >= 2 && closer.count >= 2) 2 else 0
                    opener.count >= 2 && closer.count >= 2 -> 2
                    else -> 1
                }
                if (use == 0) { oi--; continue }
                opener.count -= use; closer.count -= use
                opener.opened = true; closer.closed = true
                // What lies between them is inside now, and can match nothing outside.
                for (k in oi + 1 until ci) delims[k].active = false
                styles += Style(opener, closer, when (opener.char) {
                    '~' -> InlineKind.STRIKE
                    '=' -> InlineKind.HIGHLIGHT
                    else -> if (use == 2) InlineKind.BOLD else InlineKind.ITALIC
                })
            }
        }
    }

    private fun build(): InlineText {
        val shown = StringBuilder()
        val from = ArrayList<Int>()
        val out = ArrayList<InlineSpan>()
        // Where each node's own marks, hidden, sit in the text shown.
        val inside = HashMap<Node, Int>()
        for (node in nodes) {
            when (node) {
                is Chars -> { shown.append(node.text); for (k in node.from) from += base + k }
                is Atom -> {
                    val at = shown.length
                    inside[node] = at
                    shown.append(node.shown)
                    for (k in node.shown.indices) from += base + node.from[k]
                    for ((kind, target) in node.kinds) out += InlineSpan(at, shown.length, kind, target)
                    node.inner?.let { inner -> for (s in inner.spans) out += s.copy(start = s.start + at, end = s.end + at) }
                }
                is Delim -> {
                    // A matched run's used marks are hidden, on the side facing what they wrap; spare ones stay as text.
                    if (node.spareLeft) {
                        for (k in 0 until node.count) { shown.append(node.char); from += base + node.at + k }
                        inside[node] = shown.length
                    } else {
                        inside[node] = shown.length
                        for (k in 0 until node.count) {
                            shown.append(node.char); from += base + node.at + node.original - node.count + k
                        }
                    }
                }
            }
        }
        from += base + src.length
        for (style in styles) {
            val s = inside[style.opener] ?: continue
            val e = inside[style.closer] ?: continue
            if (e > s) out += InlineSpan(s, e, style.kind)
        }
        return InlineText(shown.toString(), out.sortedWith(compareBy({ it.start }, { -it.end })), from.toIntArray())
    }
}

// ---- reading what a block holds ----------------------------------------------------------------

/** A list item's parts: its indent, mark ("-", "1."), task state (null for no box), and where its text starts. */
internal data class ListParts(val indent: String, val mark: String, val task: Boolean?, val contentStart: Int)

private val ITEM_PARTS = Regex("^([ \t]*)([-*+]|\\d{1,9}[.)])(?:[ \t]+(\\[([ xX])\\](?:[ \t]+|$))?|$)")

internal fun listParts(line: String): ListParts? {
    val m = ITEM_PARTS.find(line) ?: return null
    return ListParts(m.groupValues[1], m.groupValues[2], m.groups[3]?.let { m.groupValues[4].isNotBlank() }, m.value.length)
}

/** Where a heading's words start in its line, and the line without its closing #s. */
internal fun headingContent(block: String): Pair<Int, String> {
    val first = block.substringBefore('\n')
    ATX.find(first)?.let { m ->
        val content = first.substring(m.value.length).replace(Regex("[ \t]+#+[ \t]*$|^#+[ \t]*$"), "").trimEnd()
        return m.value.length to content
    }
    // A heading of two lines: the words are the first.
    val lead = first.length - first.trimStart().length
    return lead to first.trim()
}

/** A quote's lines without their > marks: each with its depth and where its text starts in the block. */
internal data class QuoteLine(val depth: Int, val contentStart: Int, val text: String)

internal fun quoteLines(block: String): List<QuoteLine> {
    var offset = 0
    return block.split('\n').map { line ->
        var j = 0
        var depth = 0
        while (true) {
            var k = j
            while (k < line.length && k - j < 3 && line[k] == ' ') k++
            if (k < line.length && line[k] == '>') {
                depth++
                j = k + 1
                if (j < line.length && line[j] == ' ') j++
            } else break
        }
        QuoteLine(depth, offset + j, line.substring(j)).also { offset += line.length + 1 }
    }
}

/** A table: its header, how each column is aligned, and its rows, cells as written. */
internal data class MdTable(val header: List<String>, val align: List<TableAlign>, val rows: List<List<String>>) {
    val columns: Int get() = header.size
}

internal enum class TableAlign { NONE, LEFT, CENTER, RIGHT }

/** The cells of a table row: split at | that are not escaped and not in code. */
internal fun tableRow(line: String): List<String> {
    var text = line.trim()
    if (text.startsWith("|")) text = text.substring(1)
    if (text.endsWith("|") && !text.endsWith("\\|")) text = text.dropLast(1)
    val cells = ArrayList<String>()
    val cell = StringBuilder()
    var code = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length && text[i + 1] == '|' -> { cell.append('|'); i++ }
            c == '`' -> { code = !code; cell.append(c) }
            c == '|' && !code -> { cells += cell.toString().trim(); cell.setLength(0) }
            else -> cell.append(c)
        }
        i++
    }
    cells += cell.toString().trim()
    return cells
}

internal fun parseTable(block: String): MdTable? {
    val lines = block.split('\n').filter { it.isNotBlank() }
    if (lines.size < 2 || !TABLE_RULE.matches(lines[1])) return null
    val header = tableRow(lines[0])
    val align = tableRow(lines[1]).map { cell ->
        val c = cell.trim()
        when {
            c.startsWith(":") && c.endsWith(":") -> TableAlign.CENTER
            c.endsWith(":") -> TableAlign.RIGHT
            c.startsWith(":") -> TableAlign.LEFT
            else -> TableAlign.NONE
        }
    }
    val columns = header.size
    fun fit(cells: List<String>) = List(columns) { cells.getOrElse(it) { "" } }
    return MdTable(header, List(columns) { align.getOrElse(it) { TableAlign.NONE } }, lines.drop(2).map { fit(tableRow(it)) })
}

/** How wide [text] shows in a fixed-width font: Hangul and other wide letters take two columns. */
internal fun displayWidth(text: String): Int = text.sumOf { c ->
    val wide = c.code in 0x1100..0x115F || c.code in 0x2E80..0xA4CF || c.code in 0xAC00..0xD7A3 ||
        c.code in 0xF900..0xFAFF || c.code in 0xFE30..0xFE4F || c.code in 0xFF00..0xFF60 || c.code in 0xFFE0..0xFFE6
    if (wide) 2 else 1
}.toInt()

/** [table] written back, its columns padded to line up. */
internal fun formatTable(table: MdTable): String {
    fun escape(cell: String) = cell.replace("\n", "<br>").replace(Regex("(?<!\\\\)\\|"), "\\\\|")
    val header = table.header.map(::escape)
    val rows = table.rows.map { row -> row.map(::escape) }
    val widths = IntArray(table.columns) { c ->
        maxOf(3, displayWidth(header[c]), rows.maxOfOrNull { displayWidth(it[c]) } ?: 0)
    }
    fun pad(cell: String, c: Int): String {
        val space = widths[c] - displayWidth(cell)
        return when (table.align[c]) {
            TableAlign.RIGHT -> " ".repeat(space) + cell
            TableAlign.CENTER -> " ".repeat(space / 2) + cell + " ".repeat(space - space / 2)
            else -> cell + " ".repeat(space)
        }
    }
    fun line(cells: List<String>) = "| " + cells.mapIndexed { c, cell -> pad(cell, c) }.joinToString(" | ") + " |"
    val rule = "| " + (0 until table.columns).joinToString(" | ") { c ->
        val w = widths[c]
        when (table.align[c]) {
            TableAlign.LEFT -> ":" + "-".repeat(w - 1)
            TableAlign.CENTER -> ":" + "-".repeat(w - 2) + ":"
            TableAlign.RIGHT -> "-".repeat(w - 1) + ":"
            TableAlign.NONE -> "-".repeat(w)
        }
    } + " |"
    return (listOf(line(header), rule) + rows.map(::line)).joinToString("\n")
}

internal fun MdTable.withCell(row: Int, column: Int, text: String): MdTable =
    if (row < 0) copy(header = header.toMutableList().also { it[column] = text })
    else copy(rows = rows.mapIndexed { r, cells -> if (r == row) cells.toMutableList().also { it[column] = text } else cells })

internal fun MdTable.withRow(at: Int): MdTable =
    copy(rows = rows.toMutableList().also { it.add(at.coerceIn(0, rows.size), List(columns) { "" }) })

internal fun MdTable.withoutRow(at: Int): MdTable =
    if (at !in rows.indices) this else copy(rows = rows.toMutableList().also { it.removeAt(at) })

internal fun MdTable.withColumn(at: Int): MdTable {
    val c = at.coerceIn(0, columns)
    return MdTable(header.toMutableList().also { it.add(c, "") }, align.toMutableList().also { it.add(c, TableAlign.NONE) },
        rows.map { it.toMutableList().also { row -> row.add(c, "") } })
}

internal fun MdTable.withoutColumn(at: Int): MdTable {
    if (columns <= 1 || at !in 0 until columns) return this
    return MdTable(header.toMutableList().also { it.removeAt(at) }, align.toMutableList().also { it.removeAt(at) },
        rows.map { it.toMutableList().also { row -> row.removeAt(at) } })
}

internal fun MdTable.withAlign(column: Int, to: TableAlign): MdTable =
    copy(align = align.toMutableList().also { it[column] = to })

/** Front matter's lines as key and value, Obsidian's properties; lists kept as written. */
internal fun frontMatterProperties(block: String): List<Pair<String, String>> {
    val lines = block.split('\n').drop(1).dropLast(1)
    val out = ArrayList<Pair<String, String>>()
    for (line in lines) {
        val m = Regex("^([^\\s:#][^:]*):[ \t]*(.*)$").find(line)
        if (m != null) out += m.groupValues[1].trim() to m.groupValues[2].trim()
        else if (line.trimStart().startsWith("- ") && out.isNotEmpty()) {
            val (key, value) = out.last()
            out[out.lastIndex] = key to (if (value.isEmpty()) "" else "$value, ") + line.trimStart().removePrefix("- ").trim()
        }
    }
    return out
}

// ---- editing ------------------------------------------------------------------------------------

/** The n-th task box ("[ ]" after a list mark) in [text], outside code, ticked or unticked. Null when there is none. */
internal fun toggleNthTask(text: String, n: Int): String? {
    var seen = 0
    for (block in liveBlocks(text)) {
        if (block.kind == LiveKind.CODE || block.kind == LiveKind.MATH || block.kind == LiveKind.COMMENT ||
            block.kind == LiveKind.FRONT_MATTER) continue
        var offset = block.start
        for (line in block.text(text).split('\n')) {
            // A task in a quote counts too: its > marks come first.
            val inner = if (block.kind == LiveKind.QUOTE) quoteLines(line).first() else QuoteLine(0, 0, line)
            val parts = listParts(inner.text)
            if (parts?.task != null) {
                if (seen == n) {
                    val at = offset + inner.contentStart + inner.text.indexOf('[', parts.indent.length + parts.mark.length)
                    return toggleTask(text, at)
                }
                seen++
            }
            offset += line.length + 1
        }
    }
    return null
}

/** [text] with the block [index] of [blocks] swapped with the one [delta] away (-1 up, +1 down). */
internal fun moveBlock(text: String, blocks: List<LiveBlock>, index: Int, delta: Int): MdEdit? {
    val other = index + delta
    if (index !in blocks.indices || other !in blocks.indices) return null
    val (a, b) = if (delta < 0) blocks[other] to blocks[index] else blocks[index] to blocks[other]
    val first = a.text(text)
    val second = b.text(text)
    val joined = text.substring(0, a.start) + second + text.substring(a.end, b.start) + first + text.substring(b.end)
    val gap = b.start - a.end
    val at = if (delta < 0) a.start else a.start + second.length + gap
    return MdEdit(joined, at, at + blocks[index].end - blocks[index].start)
}

/** [text] with block [index] written twice, the copy after it. */
internal fun duplicateBlock(text: String, blocks: List<LiveBlock>, index: Int): MdEdit? {
    val block = blocks.getOrNull(index) ?: return null
    val copy = block.text(text)
    val joined = text.substring(0, block.end) + "\n" + copy + text.substring(block.end)
    return MdEdit(joined, block.end + 1, block.end + 1 + copy.length)
}

/** [text] without block [index] and the break after it (or before it, for the last). */
internal fun deleteBlock(text: String, blocks: List<LiveBlock>, index: Int): MdEdit? {
    val block = blocks.getOrNull(index) ?: return null
    val (s, e) = when {
        block.end < text.length -> block.start to block.end + 1
        block.start > 0 -> block.start - 1 to block.end
        else -> block.start to block.end
    }
    val at = s.coerceAtMost(text.length - (e - s))
    return MdEdit(text.removeRange(s, e), at)
}

/** What a block can be turned into. */
internal enum class BlockStyle(val label: String) {
    TEXT("Text"), H1("Heading 1"), H2("Heading 2"), H3("Heading 3"), BULLET("Bulleted list"),
    NUMBERED("Numbered list"), TASK("To-do list"), QUOTE("Quote"),
}

private val LINE_PREFIX = Regex("^([ \t]*)(#{1,6}[ \t]+|(>[ \t]?)+|([-*+]|\\d{1,9}[.)])[ \t]+(\\[[ xX]\\][ \t]+)?)?")

/** The lines from [start] to [end] of [text] given [style] in place of the mark they start with. */
internal fun turnInto(text: String, start: Int, end: Int, style: BlockStyle): MdEdit {
    val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(-1)).let { if (start == 0) 0 else it + 1 }
    val out = StringBuilder(text.substring(0, lineStart))
    var offset = lineStart
    var number = 1
    val lines = text.substring(lineStart, end.coerceAtLeast(lineStart)).split('\n')
    var cursor = start
    for ((k, line) in lines.withIndex()) {
        val m = LINE_PREFIX.find(line)!!
        val indent = if (style == BlockStyle.TEXT || style.name.startsWith("H")) "" else m.groupValues[1]
        val prefix = when (style) {
            BlockStyle.TEXT -> ""
            BlockStyle.H1 -> "# "
            BlockStyle.H2 -> "## "
            BlockStyle.H3 -> "### "
            BlockStyle.BULLET -> "- "
            BlockStyle.NUMBERED -> "${number++}. "
            BlockStyle.TASK -> "- [ ] "
            BlockStyle.QUOTE -> "> "
        }
        val rest = line.substring(m.value.length)
        if (k == 0) cursor = out.length + indent.length + prefix.length + (start - offset - m.value.length).coerceAtLeast(0)
        out.append(indent).append(prefix).append(rest)
        if (k < lines.lastIndex) out.append('\n')
        offset += line.length + 1
    }
    val tail = text.substring(end.coerceAtLeast(lineStart))
    val joined = out.toString() + tail
    return MdEdit(joined, cursor.coerceIn(0, joined.length))
}

/**
 * The list lines from [start] to [end] moved one step in (four spaces, so
 * any mark nests under the one above) or out. Other lines are left alone.
 */
internal fun indentLines(text: String, start: Int, end: Int, outdent: Boolean): MdEdit {
    val lines = lineRanges(text)
    val out = StringBuilder(text)
    var shift = 0
    var newStart = start
    var newEnd = end
    for (range in lines.filter { it.last + 1 >= minOf(start, end) && it.first <= maxOf(start, end) }) {
        val at = range.first + shift
        val line = out.substring(at, range.last + 1 + shift)
        val delta = if (!outdent) {
            out.insert(at, "    "); 4
        } else {
            val lead = line.takeWhile { it == ' ' || it == '\t' }
            val take = when {
                lead.startsWith("\t") -> 1
                else -> lead.length.coerceAtMost(4)
            }
            if (take == 0) 0 else { out.delete(at, at + take); -take }
        }
        if (start >= at) newStart = maxOf(at, newStart + delta)
        if (end >= at) newEnd = maxOf(at, newEnd + delta)
        shift += delta
    }
    return MdEdit(out.toString(), newStart.coerceIn(0, out.length), newEnd.coerceIn(0, out.length))
}

// ---- the slash menu -----------------------------------------------------------------------------

/**
 * What "/" offers, Notion's way: a block made where the slash was typed.
 * [snippet] goes in place of the line ("\u0001" is where the cursor goes);
 * [prefix] instead puts a mark at the start of the line and keeps its words.
 */
internal enum class SlashItem(val label: String, val words: String, val prefix: String? = null, val snippet: String? = null) {
    TEXT("Text", "text plain paragraph 본문 텍스트", prefix = ""),
    H1("Heading 1", "heading title h1 제목 큰", prefix = "# "),
    H2("Heading 2", "heading h2 제목 중간", prefix = "## "),
    H3("Heading 3", "heading h3 제목 작은", prefix = "### "),
    BULLET("Bulleted list", "bullet list unordered 목록 글머리", prefix = "- "),
    NUMBERED("Numbered list", "numbered ordered list 번호 목록", prefix = "1. "),
    TASK("To-do list", "todo task checkbox check 할일 체크", prefix = "- [ ] "),
    QUOTE("Quote", "quote blockquote 인용", prefix = "> "),
    CALLOUT("Callout", "callout note info 콜아웃 알림 상자", snippet = "> [!note] \u0001\n> "),
    TIP("Tip callout", "tip callout hint 팁", snippet = "> [!tip] \u0001\n> "),
    WARNING("Warning callout", "warning caution callout 경고 주의", snippet = "> [!warning] \u0001\n> "),
    TOGGLE("Toggle", "toggle fold collapse 토글 접기", snippet = "> [!note]- \u0001\n> "),
    CODE("Code block", "code fence snippet 코드", snippet = "```\u0001\n\n```"),
    MATH("Math block", "math equation latex formula 수식", snippet = "$$\n\u0001\n$$"),
    TABLE("Table", "table grid 표", snippet = "| \u0001   |     |\n| --- | --- |\n|     |     |"),
    DIVIDER("Divider", "divider rule line hr 구분선", snippet = "---\n\u0001"),
    LINK("Link to note", "link wiki note 링크 노트", snippet = "[[\u0001]]"),
    IMAGE("Image", "image picture photo 그림 사진 이미지"),
    FOOTNOTE("Footnote", "footnote reference 각주"),
    DATE("Today's date", "date today 날짜 오늘"),
    COMMENT("Comment", "comment hidden 주석 숨김", snippet = "%% \u0001 %%"),
}

/** A "/" being typed before [cursor] at the start of a line or after a space: where it is and what follows it. */
internal fun slashQueryAt(text: String, cursor: Int): Pair<Int, String>? {
    if (cursor <= 0 || cursor > text.length) return null
    val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
    val slash = text.lastIndexOf('/', cursor - 1)
    if (slash < lineStart) return null
    val query = text.substring(slash + 1, cursor)
    if (query.length > 24 || query.any { it.isWhitespace() || it == '/' }) return null
    val before = text.substring(lineStart, slash)
    // At the start of the line, after a list mark, or after a space: not in the middle of a word or an address.
    if (before.isNotEmpty() && !before.last().isWhitespace()) return null
    return slash to query
}

/** The items whose name or words start with [query], name matches first. */
internal fun slashMatches(query: String): List<SlashItem> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return SlashItem.entries
    val byName = SlashItem.entries.filter { item -> item.label.lowercase().split(' ').any { it.startsWith(q) } || item.label.lowercase().startsWith(q) }
    val byWord = SlashItem.entries.filter { item -> item !in byName && item.words.split(' ').any { it.startsWith(q) } }
    return byName + byWord
}

/**
 * [item] put in for the "/query" from [slash] to [cursor]. A mark replaces
 * the line's own; a block goes in place of the line if nothing else is on
 * it, else on a line of its own after it. [extra] fills what the item needs
 * from outside (an image's name, the date).
 */
internal fun applySlash(text: String, slash: Int, cursor: Int, item: SlashItem, extra: String = ""): MdEdit {
    val removed = text.removeRange(slash, cursor)
    val lineStart = removed.lastIndexOf('\n', slash - 1) + 1
    val lineEnd = removed.indexOf('\n', slash).let { if (it < 0) removed.length else it }
    val line = removed.substring(lineStart, lineEnd)
    item.prefix?.let { prefix ->
        val m = LINE_PREFIX.find(line)!!
        val rest = line.substring(m.value.length)
        val made = prefix + rest
        val joined = removed.substring(0, lineStart) + made + removed.substring(lineEnd)
        val at = lineStart + prefix.length + (slash - lineStart - m.value.length).coerceIn(0, rest.length)
        return MdEdit(joined, at)
    }
    val snippet = when (item) {
        SlashItem.IMAGE -> "![[${extra}]]\u0001"
        SlashItem.DATE -> "$extra\u0001"
        SlashItem.FOOTNOTE -> {
            val n = (Regex("\\[\\^(\\d+)]").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0) + 1
            // The mark where the slash was; its text at the foot of the note.
            val withRef = removed.substring(0, slash) + "[^$n]" + removed.substring(slash)
            val foot = (if (withRef.endsWith("\n")) "" else "\n") + "\n[^$n]: "
            val joined = withRef + foot
            return MdEdit(joined, joined.length)
        }
        else -> item.snippet ?: ""
    }
    val inline = item == SlashItem.LINK || item == SlashItem.COMMENT || item == SlashItem.DATE || item == SlashItem.IMAGE && line.isNotBlank()
    val (joined, at) = if (inline) {
        val s = removed.substring(0, slash) + snippet + removed.substring(slash)
        s to slash
    } else if (line.isBlank()) {
        (removed.substring(0, lineStart) + snippet + removed.substring(lineEnd)) to lineStart
    } else {
        (removed.substring(0, lineEnd) + "\n" + snippet + removed.substring(lineEnd)) to lineEnd + 1
    }
    val caret = joined.indexOf('\u0001', at)
    return if (caret < 0) MdEdit(joined, at + snippet.length) else MdEdit(joined.removeRange(caret, caret + 1), caret)
}

// ---- undo ---------------------------------------------------------------------------------------

/**
 * What the editor can take back and put again. Typing is kept in runs: a
 * change within [MERGE_MS] of the last one, of the same sort (adding or
 * taking away), joins it, so one undo takes back a word or a sentence, not
 * a letter; anything else - a format, a block moved - is a step of its own.
 */
internal class EditHistory(private val limit: Int = 200) {
    data class State(val text: String, val start: Int, val end: Int)
    private val back = ArrayDeque<State>()
    private val forward = ArrayDeque<State>()
    private var lastAt = 0L
    private var lastGrew: Boolean? = null

    val canUndo: Boolean get() = back.isNotEmpty()
    val canRedo: Boolean get() = forward.isNotEmpty()

    /** [before] is about to become [after]; typing ([typed]) may join the run before it. */
    fun record(before: State, after: State, typed: Boolean, now: Long) {
        if (before.text == after.text) return
        val grew = after.text.length > before.text.length
        val joins = typed && lastGrew == grew && now - lastAt < MERGE_MS && back.isNotEmpty()
        if (!joins) {
            back.addLast(before)
            while (back.size > limit) back.removeFirst()
        }
        forward.clear()
        lastAt = now
        lastGrew = if (typed) grew else null
    }

    fun undo(current: State): State? {
        val state = back.removeLastOrNull() ?: return null
        forward.addLast(current)
        lastGrew = null
        return state
    }

    fun redo(current: State): State? {
        val state = forward.removeLastOrNull() ?: return null
        back.addLast(current)
        lastGrew = null
        return state
    }

    companion object { const val MERGE_MS = 1_000L }
}

// ---- code ---------------------------------------------------------------------------------------

internal enum class CodeToken { KEYWORD, STRING, COMMENT, NUMBER }

private val KEYWORDS = setOf(
    "abstract", "and", "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "def", "default",
    "defer", "del", "do", "elif", "else", "enum", "except", "export", "extends", "false", "final", "finally", "fn", "for",
    "from", "fun", "func", "function", "go", "if", "impl", "import", "in", "interface", "internal", "is", "lambda", "let",
    "match", "mut", "new", "nil", "None", "not", "null", "object", "or", "override", "package", "pass", "private",
    "protected", "pub", "public", "raise", "return", "self", "static", "struct", "super", "switch", "this", "throw",
    "trait", "True", "False", "true", "try", "type", "typeof", "use", "val", "var", "void", "when", "where", "while",
    "with", "yield", "int", "long", "float", "double", "bool", "char", "string", "String", "Int", "Unit", "select",
    "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "JOIN", "ORDER", "BY", "GROUP",
)

/**
 * A light colouring of code: strings, comments, numbers and the keywords
 * most languages share. Not a parser of any one language; enough to read by.
 */
internal fun codeTokens(code: String, language: String): List<Triple<Int, Int, CodeToken>> {
    val out = ArrayList<Triple<Int, Int, CodeToken>>()
    val hash = language.lowercase() in setOf("python", "py", "sh", "bash", "shell", "zsh", "ruby", "rb", "yaml", "yml", "r", "toml", "perl")
    val dashes = language.lowercase() in setOf("sql", "lua", "haskell", "hs")
    var i = 0
    while (i < code.length) {
        val c = code[i]
        when {
            code.startsWith("//", i) && !hash || code.startsWith("--", i) && dashes || c == '#' && hash -> {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                out += Triple(i, end, CodeToken.COMMENT); i = end
            }
            code.startsWith("/*", i) && !hash -> {
                val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                out += Triple(i, end, CodeToken.COMMENT); i = end
            }
            c == '"' || c == '\'' || c == '`' -> {
                var j = i + 1
                while (j < code.length && code[j] != c && !(code[j] == '\n' && c != '`')) { if (code[j] == '\\') j++; j++ }
                val end = (j + 1).coerceAtMost(code.length)
                out += Triple(i, end, CodeToken.STRING); i = end
            }
            c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                var j = i
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                out += Triple(i, j, CodeToken.NUMBER); i = j
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                if (code.substring(i, j) in KEYWORDS) out += Triple(i, j, CodeToken.KEYWORD)
                i = j
            }
            else -> i++
        }
    }
    return out
}

/** A code block's lines between its fences (or its indented lines, unindented), and where they start in it. */
internal fun codeBody(block: String, info: String): Pair<Int, String> {
    if (info == INDENTED) return 0 to block.split('\n').joinToString("\n") { line ->
        var k = 0; var w = 0
        while (k < line.length && w < 4 && (line[k] == ' ' || line[k] == '\t')) { w = if (line[k] == '\t') 4 else w + 1; k++ }
        line.substring(k)
    }
    val first = block.indexOf('\n')
    if (first < 0) return block.length to ""
    val lines = block.substring(first + 1).split('\n')
    val closed = lines.size >= 1 && lines.last().trim().let { t -> t.length >= 3 && (t.all { it == '`' } || t.all { it == '~' }) }
    val body = (if (closed) lines.dropLast(1) else lines).joinToString("\n")
    return first + 1 to body
}

/** A math block's formula, without its $$ marks. */
internal fun mathBody(block: String): String = block.trim().removePrefix("$$").removeSuffix("$$").trim()

/** The name an attachment is given, as Obsidian names a pasted picture, made to not clash with [taken]. */
internal fun attachmentName(extension: String, millis: Long, taken: Set<String>): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val stamp = String.format(java.util.Locale.ROOT, "%04d%02d%02d%02d%02d%02d",
        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND))
    val ext = extension.lowercase().ifBlank { "png" }
    var name = "Pasted image $stamp.$ext"
    var n = 1
    while (name.lowercase() in taken) name = "Pasted image $stamp ${++n}.$ext"
    return name
}

/** An embed's file name and the width asked for, from `![[name.png|300]]`. */
internal fun embedParts(target: String): Pair<String, Int?> {
    val name = target.substringBefore('|').trim()
    val size = target.substringAfter('|', "").trim().substringBefore('x').toIntOrNull()
    return name to size
}

internal fun isImageName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")

/** `![[name]]` put in at [at]: on its own line, in place of the line if that is empty, else after it. */
internal fun insertEmbed(text: String, at: Int, name: String): MdEdit {
    val lineStart = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (at == 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
    val embed = "![[$name]]"
    return if (text.substring(lineStart, lineEnd).isBlank()) {
        val joined = text.substring(0, lineStart) + embed + text.substring(lineEnd)
        MdEdit(joined, lineStart + embed.length)
    } else {
        val joined = text.substring(0, lineEnd) + "\n" + embed + text.substring(lineEnd)
        MdEdit(joined, lineEnd + 1 + embed.length)
    }
}
