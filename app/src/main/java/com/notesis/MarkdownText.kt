package com.notesis

/**
 * Markdown read the way a note-taking editor needs it, without Android:
 * where each piece of syntax is in the source (to style it while it is
 * edited, its marks hidden away from the line being written), the blocks it
 * makes (to read it), its [[links]], #tags and headings, and the edits a
 * formatting bar makes. Written for Notesis; Obsidian's behaviour is the
 * model, none of its code is.
 */

/** What a stretch of Markdown source is. */
internal enum class MdKind {
    HEADING, MARKER, BOLD, ITALIC, STRIKE, HIGHLIGHT, CODE, LINK, WIKILINK, TAG,
    QUOTE, BULLET, TASK_OPEN, TASK_DONE, DONE_TEXT, CODE_BLOCK, FENCE, RULE, FRONT_MATTER,
}

/** [start, end) of the source is [kind]; [level] for headings, [target] for links. */
internal data class MdSpan(val start: Int, val end: Int, val kind: MdKind, val level: Int = 0, val target: String? = null)

/** The lines of [text] as [start, end) pairs, the line break left out. */
internal fun lineRanges(text: String): List<IntRange> {
    val lines = ArrayList<IntRange>()
    var start = 0
    while (true) {
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        lines += start until end
        if (end >= text.length) break
        start = end + 1
    }
    return lines
}

private val HEADING = Regex("^(#{1,6})([ \t]+)")
private val RULE = Regex("^ {0,3}([-*_])([ \t]*\\1){2,}[ \t]*$")
private val QUOTE = Regex("^( {0,3}>[ \t]?)")
private val LIST = Regex("^([ \t]*)([-*+]|\\d{1,9}[.)])([ \t]+)(\\[([ xX])\\][ \t]+)?")
private val FENCE = Regex("^ {0,3}(```|~~~)")

/** Every piece of syntax in [text], in order of where it starts. */
internal fun markdownSpans(text: String): List<MdSpan> {
    val spans = ArrayList<MdSpan>()
    val lines = lineRanges(text)
    var fence: String? = null
    var index = 0
    // Front matter: a block of --- lines at the very top, kept as it is.
    if (lines.size > 1 && text.substring(lines[0].first, lines[0].last + 1) == "---") {
        val close = (1 until lines.size).firstOrNull { text.substring(lines[it].first, lines[it].last + 1) == "---" }
        if (close != null) {
            spans += MdSpan(0, lines[close].last + 1, MdKind.FRONT_MATTER)
            index = close + 1
        }
    }
    while (index < lines.size) {
        val range = lines[index++]
        val start = range.first
        val end = range.last + 1
        val line = text.substring(start, end)
        val opened = FENCE.find(line)
        if (fence != null) {
            if (opened != null && opened.groupValues[1] == fence && line.trim() == fence) {
                spans += MdSpan(start, end, MdKind.FENCE)
                fence = null
            } else {
                spans += MdSpan(start, end, MdKind.CODE_BLOCK)
            }
            continue
        }
        if (opened != null) {
            fence = opened.groupValues[1]
            spans += MdSpan(start, end, MdKind.FENCE)
            continue
        }
        HEADING.find(line)?.let { match ->
            val level = match.groupValues[1].length
            spans += MdSpan(start, end, MdKind.HEADING, level = level)
            spans += MdSpan(start, start + match.value.length, MdKind.MARKER)
            inlineSpans(text, start + match.value.length, end, spans)
            continue
        }
        if (RULE.matches(line)) {
            spans += MdSpan(start, end, MdKind.RULE)
            continue
        }
        QUOTE.find(line)?.let { match ->
            spans += MdSpan(start, end, MdKind.QUOTE)
            spans += MdSpan(start, start + match.value.length, MdKind.MARKER)
            inlineSpans(text, start + match.value.length, end, spans)
            continue
        }
        LIST.find(line)?.let { match ->
            val bulletStart = start + match.groupValues[1].length
            val bulletEnd = bulletStart + match.groupValues[2].length + match.groupValues[3].length
            spans += MdSpan(bulletStart, bulletEnd, MdKind.BULLET, target = match.groupValues[2])
            var content = bulletEnd
            if (match.groups[4] != null) {
                val box = match.groups[4]!!
                val done = match.groupValues[5].isNotBlank()
                content = start + box.range.last + 1
                spans += MdSpan(start + box.range.first, content, if (done) MdKind.TASK_DONE else MdKind.TASK_OPEN)
                if (done && content < end) spans += MdSpan(content, end, MdKind.DONE_TEXT)
            }
            inlineSpans(text, content, end, spans)
            continue
        }
        inlineSpans(text, start, end, spans)
    }
    spans.sortWith(compareBy({ it.start }, { -it.end }))
    return spans
}

private val CODE_SPAN = Regex("`([^`\n]+)`")
private val WIKILINK = Regex("\\[\\[([^\\[\\]\\n|#]+)(#[^\\[\\]\\n|]*)?(\\|([^\\[\\]\\n]+))?\\]\\]")
private val LINK = Regex("\\[([^\\[\\]\\n]+)\\]\\(([^()\\s]+)\\)")
private val BOLD = Regex("(\\*\\*|__)(?=\\S)([^\\n]+?)(?<=\\S)\\1")
private val ITALIC = Regex("(?<![*\\w])([*_])(?=[^\\s*_])([^*_\\n]+?)(?<=[^\\s*_])\\1(?![*\\w])")
private val STRIKE = Regex("~~(?=\\S)([^\\n]+?)(?<=\\S)~~")
private val HIGHLIGHT = Regex("==(?=\\S)([^\\n]+?)(?<=\\S)==")
private val TAG = Regex("(?<=^|[\\s(])#([\\p{L}\\p{N}_/-]*[\\p{L}_/-][\\p{L}\\p{N}_/-]*)")

/** The inline syntax of [text] between [from] and [to]: code first, then links, then emphasis and tags. */
private fun inlineSpans(text: String, from: Int, to: Int, out: MutableList<MdSpan>) {
    if (from >= to) return
    // Code and links are found first and blanked out, so nothing is read inside them twice.
    val work = StringBuilder(text.substring(from, to))
    fun blank(start: Int, end: Int) { for (i in start until end) work.setCharAt(i, ' ') }
    CODE_SPAN.findAll(work.toString()).toList().forEach { m ->
        val s = from + m.range.first
        val e = from + m.range.last + 1
        out += MdSpan(s, s + 1, MdKind.MARKER)
        out += MdSpan(s + 1, e - 1, MdKind.CODE)
        out += MdSpan(e - 1, e, MdKind.MARKER)
        blank(m.range.first, m.range.last + 1)
    }
    WIKILINK.findAll(work.toString()).toList().forEach { m ->
        val s = from + m.range.first
        val e = from + m.range.last + 1
        val target = m.groupValues[1].trim()
        val alias = m.groups[4]
        if (alias != null) {
            val aliasStart = from + alias.range.first
            out += MdSpan(s, aliasStart, MdKind.MARKER)
            out += MdSpan(aliasStart, e - 2, MdKind.WIKILINK, target = target)
        } else {
            out += MdSpan(s, s + 2, MdKind.MARKER)
            out += MdSpan(s + 2, e - 2, MdKind.WIKILINK, target = target)
        }
        out += MdSpan(e - 2, e, MdKind.MARKER)
        blank(m.range.first, m.range.last + 1)
    }
    LINK.findAll(work.toString()).toList().forEach { m ->
        val s = from + m.range.first
        val e = from + m.range.last + 1
        val textEnd = s + 1 + m.groupValues[1].length
        out += MdSpan(s, s + 1, MdKind.MARKER)
        out += MdSpan(s + 1, textEnd, MdKind.LINK, target = m.groupValues[2])
        out += MdSpan(textEnd, e, MdKind.MARKER)
        // The link's words may still be bold or italic; only its address is blanked.
        blank(m.range.first, m.range.first + 1)
        blank(textEnd - from, m.range.last + 1)
    }
    fun wrapped(regex: Regex, kind: MdKind, markGroup: Int?) {
        regex.findAll(work.toString()).toList().forEach { m ->
            val mark = markGroup?.let { m.groupValues[it].length } ?: 2
            val s = from + m.range.first
            val e = from + m.range.last + 1
            out += MdSpan(s, s + mark, MdKind.MARKER)
            out += MdSpan(s + mark, e - mark, kind)
            out += MdSpan(e - mark, e, MdKind.MARKER)
            blank(m.range.first, m.range.first + mark)
            blank(m.range.last + 1 - mark, m.range.last + 1)
        }
    }
    wrapped(BOLD, MdKind.BOLD, 1)
    wrapped(STRIKE, MdKind.STRIKE, null)
    wrapped(HIGHLIGHT, MdKind.HIGHLIGHT, null)
    wrapped(ITALIC, MdKind.ITALIC, 1)
    TAG.findAll(work.toString()).forEach { m ->
        out += MdSpan(from + m.range.first, from + m.range.last + 1, MdKind.TAG, target = m.groupValues[1])
    }
}

// ---- reading ------------------------------------------------------------------------------

/** A block of a Markdown note, as it is read. [start] is where it begins in the source. */
internal sealed interface MdBlock {
    val start: Int
    data class Heading(val level: Int, val text: String, override val start: Int) : MdBlock
    data class Paragraph(val text: String, override val start: Int) : MdBlock
    /** A list item: [marker] "•" or "1.", [task] null when it is no task, [taskAt] where its [ ] is. */
    data class Item(val depth: Int, val marker: String, val task: Boolean?, val text: String,
                    override val start: Int, val taskAt: Int) : MdBlock
    data class Quote(val text: String, override val start: Int) : MdBlock
    data class Code(val language: String, val text: String, override val start: Int) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>, override val start: Int) : MdBlock
    data class Rule(override val start: Int) : MdBlock
    data class FrontMatter(val text: String, override val start: Int) : MdBlock
}

private val TABLE_DIVIDER = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

/** [text] as blocks to read. */
internal fun markdownBlocks(text: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val lines = lineRanges(text)
    fun line(i: Int) = text.substring(lines[i].first, lines[i].last + 1)
    var i = 0
    if (lines.size > 1 && line(0) == "---") {
        val close = (1 until lines.size).firstOrNull { line(it) == "---" }
        if (close != null) {
            blocks += MdBlock.FrontMatter((1 until close).joinToString("\n") { line(it) }, 0)
            i = close + 1
        }
    }
    val paragraph = StringBuilder()
    var paragraphStart = 0
    fun flush() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim(), paragraphStart)
        paragraph.setLength(0)
    }
    while (i < lines.size) {
        val raw = line(i)
        val start = lines[i].first
        val fence = FENCE.find(raw)
        when {
            fence != null -> {
                flush()
                val marker = fence.groupValues[1]
                val language = raw.trim().removePrefix(marker).trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && line(i).trim() != marker) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(line(i))
                    i++
                }
                blocks += MdBlock.Code(language, body.toString(), start)
            }
            HEADING.containsMatchIn(raw) -> {
                flush()
                val match = HEADING.find(raw)!!
                blocks += MdBlock.Heading(match.groupValues[1].length, raw.substring(match.value.length).trim().trimEnd('#').trim(), start)
            }
            RULE.matches(raw) -> { flush(); blocks += MdBlock.Rule(start) }
            QUOTE.containsMatchIn(raw) -> {
                flush()
                val quote = StringBuilder()
                while (i < lines.size && QUOTE.containsMatchIn(line(i))) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(line(i).replaceFirst(QUOTE, ""))
                    i++
                }
                i--
                blocks += MdBlock.Quote(quote.toString(), start)
            }
            LIST.containsMatchIn(raw) -> {
                flush()
                val match = LIST.find(raw)!!
                val indent = match.groupValues[1].replace("\t", "    ").length
                val bullet = match.groupValues[2]
                val marker = if (bullet[0].isDigit()) bullet.dropLast(1) + "." else "•"
                val task = match.groups[4]?.let { match.groupValues[5].isNotBlank() }
                val taskAt = match.groups[4]?.let { start + it.range.first } ?: -1
                blocks += MdBlock.Item(indent / 2, marker, task, raw.substring(match.value.length), start, taskAt)
            }
            raw.contains('|') && i + 1 < lines.size && TABLE_DIVIDER.matches(line(i + 1)) -> {
                flush()
                val header = tableCells(raw)
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && line(i).contains('|') && line(i).isNotBlank()) {
                    rows += tableCells(line(i))
                    i++
                }
                i--
                blocks += MdBlock.Table(header, rows, start)
            }
            raw.isBlank() -> flush()
            else -> {
                if (paragraph.isEmpty()) paragraphStart = start else paragraph.append('\n')
                paragraph.append(raw)
            }
        }
        i++
    }
    flush()
    return blocks
}

// ---- links, tags, headings ------------------------------------------------------------------

/** How a link target and a note's name are compared: case and surrounding space aside, without ".md". */
internal fun linkKey(name: String): String = name.trim().removeSuffix(".md").lowercase()

/** The notes [text] links to with [[...]], by [linkKey]. */
internal fun wikiTargets(text: String): Set<String> =
    WIKILINK.findAll(text).map { linkKey(it.groupValues[1]) }.toSet()

/** The #tags in [text], outside code. */
internal fun tagsIn(text: String): Set<String> =
    markdownSpans(text).filter { it.kind == MdKind.TAG }.mapNotNull { it.target }.toSet()

internal data class MdHeading(val level: Int, val title: String, val start: Int)

/** The headings of [text], for its outline. */
internal fun outlineOf(text: String): List<MdHeading> =
    markdownBlocks(text).filterIsInstance<MdBlock.Heading>().map { MdHeading(it.level, it.text, it.start) }

// ---- editing --------------------------------------------------------------------------------

/** Text with its selection after an edit. */
internal data class MdEdit(val text: String, val start: Int, val end: Int = start)

/**
 * Wraps the selection in [mark] (bold **, italic *, code `, ~~, ==), or takes
 * the mark off where it is already wrapped. With nothing selected the marks
 * go in either side of the cursor.
 */
internal fun toggleWrap(text: String, start: Int, end: Int, mark: String): MdEdit {
    val s = minOf(start, end).coerceIn(0, text.length)
    val e = maxOf(start, end).coerceIn(0, text.length)
    val n = mark.length
    // Marks just outside the selection.
    if (s >= n && e + n <= text.length && text.substring(s - n, s) == mark && text.substring(e, e + n) == mark) {
        return MdEdit(text.removeRange(e, e + n).removeRange(s - n, s), s - n, e - n)
    }
    // The selection is itself wrapped.
    val picked = text.substring(s, e)
    if (picked.length >= 2 * n && picked.startsWith(mark) && picked.endsWith(mark)) {
        val inner = picked.substring(n, picked.length - n)
        return MdEdit(text.replaceRange(s, e, inner), s, s + inner.length)
    }
    return MdEdit(text.substring(0, s) + mark + picked + mark + text.substring(e), s + n, e + n)
}

/** What a formatting button puts at the start of the selected lines. */
internal enum class LineStyle { HEADING, BULLET, NUMBERED, TASK, QUOTE }

private val ANY_PREFIX = Regex("^([ \t]*)(#{1,6}[ \t]+|>[ \t]?|([-*+]|\\d{1,9}[.)])[ \t]+(\\[[ xX]\\][ \t]+)?)?")

/**
 * Gives the selected lines [style], or takes it off lines that have it: a
 * heading steps # → ## → ### → none. One kind of line start at a time, so
 * a bullet made a task loses its plain bullet.
 */
internal fun toggleLineStyle(text: String, start: Int, end: Int, style: LineStyle): MdEdit {
    val lines = lineRanges(text)
    val s = minOf(start, end)
    val e = maxOf(start, end)
    val picked = lines.filter { it.last + 1 >= s && it.first <= e }.ifEmpty { listOf(lines.last()) }
    val out = StringBuilder(text)
    var shift = 0
    var newStart = start
    var newEnd = end
    var number = 1
    for (range in picked) {
        val lineStart = range.first + shift
        val lineEnd = range.last + 1 + shift
        val line = out.substring(lineStart, lineEnd)
        val match = ANY_PREFIX.find(line)!!
        val indent = match.groupValues[1]
        val prefix = match.groupValues[2]
        val replacement = when (style) {
            LineStyle.HEADING -> {
                val level = Regex("^(#{1,6})").find(prefix)?.groupValues?.get(1)?.length ?: 0
                if (level in 1..2) "#".repeat(level + 1) + " " else if (level == 0) "# " else ""
            }
            LineStyle.BULLET -> if (Regex("^[-*+][ \t]+$").matches(prefix)) "" else "- "
            LineStyle.NUMBERED -> if (Regex("^\\d{1,9}[.)][ \t]+$").matches(prefix)) "" else "${number++}. "
            LineStyle.TASK -> if (prefix.contains('[')) "" else "- [ ] "
            LineStyle.QUOTE -> if (prefix.startsWith(">")) "" else "> "
        }
        val at = lineStart + indent.length
        out.replace(at, at + prefix.length, replacement)
        val delta = replacement.length - prefix.length
        if (start >= at) newStart = maxOf(at, newStart + delta)
        if (end >= at) newEnd = maxOf(at, newEnd + delta)
        shift += delta
    }
    return MdEdit(out.toString(), newStart.coerceIn(0, out.length), newEnd.coerceIn(0, out.length))
}

/**
 * Called when a line break has just been typed at [cursor] (right after it):
 * a list, a task list or a quote carries on in the new line, numbers counting
 * up; an empty item ends the list instead. Null when there is nothing to carry.
 */
internal fun continueAfterBreak(text: String, cursor: Int): MdEdit? {
    if (cursor <= 0 || cursor > text.length || text[cursor - 1] != '\n') return null
    val previousEnd = cursor - 1
    val previousStart = text.lastIndexOf('\n', previousEnd - 1) + 1
    val previous = text.substring(previousStart, previousEnd)
    val match = ANY_PREFIX.find(previous) ?: return null
    val indent = match.groupValues[1]
    val prefix = match.groupValues[2]
    if (prefix.isEmpty() || prefix.startsWith("#")) return null
    val rest = previous.substring(match.value.length)
    if (rest.isBlank()) {
        // An empty item: the list ends here, and the empty marker goes.
        val cleared = text.removeRange(previousStart, cursor)
        return MdEdit(cleared, previousStart)
    }
    val next = when {
        prefix.startsWith(">") -> "> "
        prefix.contains('[') -> Regex("^([-*+]|\\d{1,9}[.)])").find(prefix)!!.value.let { bulletAfter(it) } + " [ ] "
        else -> bulletAfter(Regex("^([-*+]|\\d{1,9}[.)])").find(prefix)!!.value) + " "
    }
    val insert = indent + next
    return MdEdit(text.substring(0, cursor) + insert + text.substring(cursor), cursor + insert.length)
}

private fun bulletAfter(bullet: String): String =
    if (bullet[0].isDigit()) "${bullet.dropLast(1).toInt() + 1}${bullet.last()}" else bullet

/** Ticks or unticks the task whose "[ ]" starts at [at]. */
internal fun toggleTask(text: String, at: Int): String {
    if (at < 0 || at + 2 >= text.length || text[at] != '[' || text[at + 2] != ']') return text
    val next = if (text[at + 1] == ' ') 'x' else ' '
    return text.substring(0, at + 1) + next + text.substring(at + 2)
}

/** An unfinished [[link]] being typed before [cursor]: where its name starts and what is typed so far. */
internal fun wikiQueryAt(text: String, cursor: Int): Pair<Int, String>? {
    if (cursor < 2 || cursor > text.length) return null
    val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
    val open = text.lastIndexOf("[[", cursor - 1)
    if (open < lineStart) return null
    val typed = text.substring(open + 2, cursor)
    if (typed.contains("]]") || typed.contains('[') || typed.contains('|')) return null
    return open + 2 to typed
}

/** The [[link]] being typed, from [nameStart] to [cursor], finished as [[name]]. */
internal fun completeWiki(text: String, nameStart: Int, cursor: Int, name: String): MdEdit {
    val closed = text.startsWith("]]", cursor)
    val tail = if (closed) text.substring(cursor + 2) else text.substring(cursor)
    val done = text.substring(0, nameStart) + name + "]]" + tail
    return MdEdit(done, nameStart + name.length + 2)
}

/** Notes whose names start with, then contain, [query] - for the [[ suggestions. */
internal fun suggestNames(names: List<String>, query: String, limit: Int = 8): List<String> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return names.take(limit)
    val starts = names.filter { it.lowercase().startsWith(q) }
    val contains = names.filter { it.lowercase().contains(q) && it !in starts }
    return (starts + contains).take(limit)
}

/** Words in [text], for the count under the editor: Hangul and Latin words alike. */
internal fun wordCount(text: String): Int = Regex("[\\p{L}\\p{N}]+").findAll(text).count()
