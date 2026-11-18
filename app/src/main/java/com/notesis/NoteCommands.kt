package com.notesis

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/*
 * The note commands (Todo.md, C1 and C2): `notesis notes list`, `note read`,
 * `page add` and the rest, each a line of words in and a JSON answer out, so
 * that a terminal, or an agent working in one, can read and change notes the
 * way the screens do.
 *
 * They work on notes as they are stored. A note open on screen keeps its
 * own copy and would write over a change, so the screen is held first: it
 * writes what it shows and waits, the change is made, and it reads the note
 * again (OpenNotes.kt, LiveNotes): its own screen, a part of a split or the
 * pop-up panel. A read of a note on screen first has its screen write what
 * is new, so it reads what the owner sees. Every change keeps what it replaced, and `undo` puts
 * back the last line (or batch of lines) whole. Nothing is ever deleted for
 * good: a note goes to the trash, and a page taken out comes back with undo.
 *
 * What touches files, ink and pictures is behind [NoteWorkspace], so all of
 * this - reading the words, finding notes, moving pages, the outline, undo -
 * is plain code the tests run against a workspace in memory.
 */

/** What the commands need from the app; [NoteStoreWorkspace] is the real one. */
internal interface NoteWorkspace {
    /** Notes not in the trash. */
    fun notes(): List<NoteMeta>
    fun trashed(): List<NoteMeta>
    /** Shown on screen now, so to be read but not changed. */
    fun isOpen(id: String): Boolean
    fun load(id: String): Document
    fun save(id: String, title: String, document: Document)
    /** A Markdown note's text. */
    fun markdown(id: String): String?
    fun rename(id: String, title: String): Boolean
    fun setFolder(id: String, folder: String)
    fun setTags(id: String, tags: List<String>)
    fun trash(id: String)
    fun restore(id: String)
    /** How many strokes [page] holds. */
    fun inkCount(id: String, page: Page): Int
    /** A text box for [page], drawn and stored, not yet put on the page. */
    fun textBox(id: String, content: TextBoxContent): PageImage?
    /** Adds a stroke along each of [lines] (points as x, y in page units); how many were made. */
    fun addInk(id: String, document: Document, page: Page, lines: List<List<FloatArray>>, color: Int, width: Float): Int
    /** Takes away the strokes touching [area] (left, top, right, bottom), or all with null; how many. */
    fun eraseInk(id: String, document: Document, page: Page, area: FloatArray?): Int
    /** Writes the note out; the path of the file made. [pages] are indices from 0. */
    fun export(id: String, title: String, format: ExportKind, pages: List<Int>, withInk: Boolean): String?
    /** A copy of everything a change to the note's pages can touch, for [bring]. */
    fun keep(id: String): Any
    fun bring(id: String, kept: Any)
    /** Lets go of a copy from [keep] that will not be brought back: its change was taken back, or is too old to be. */
    fun release(kept: Any) {}

    /**
     * Asks the screen showing [id] to write what it shows and wait, so a change
     * can be made under it; false when it cannot (it is not a screen that
     * takes changes, or a stroke is being drawn and did not end in time).
     */
    fun hold(id: String): Boolean = false
    /** Lets the screen held for [id] go on; [changed]: it reads the note again first. */
    fun letGo(id: String, changed: Boolean) {}

    // What the commands added for agents (Todo.md Q1-Q5) need. Each has a do-nothing default, so a
    // workspace for tests gives only what its tests use.

    /** The bookmarks of the note's PDF, by PDF page from 0. */
    fun pdfOutline(id: String): List<PdfMark> = emptyList()
    /** The printed text of the PDF page under each of [pages]: null for a page with no PDF under it. */
    fun pdfText(id: String, pages: List<Page>): List<String?> = pages.map { null }
    /** A picture of page [pageIndex] as it looks, [width] pixels wide, as JPEG; null when it cannot be drawn. */
    fun pageImage(id: String, pageIndex: Int, width: Int): ByteArray? = null
    /** Pages whose text (typed, PDF) holds [query]. */
    fun search(query: String, limit: Int): List<SearchHit> = emptyList()
    fun saveMarkdown(id: String, title: String, text: String) {}
    /** A new handwritten note; its id. */
    fun createNote(title: String, folder: String, background: PageBackground, pages: Int): String? = null
    fun createMarkdown(title: String, folder: String, text: String): String? = null
    fun duplicateNote(id: String, title: String): String? = null
    fun mergeNotes(ids: List<String>, title: String): String? = null
    fun setFavorite(id: String, on: Boolean) {}
    fun setLabel(id: String, color: Int) {}
    fun folders(): List<String> = emptyList()
    fun createFolder(path: String): Boolean = false
    /** Takes away a folder no note is in; false when one is. */
    fun removeEmptyFolder(path: String): Boolean = false
    fun renameFolder(from: String, to: String): Boolean = false
    /** The notes on screen now. */
    fun openNotes(): Set<String> = emptySet()
    fun appVersion(): String = ""
    /** The strokes of [from] put on [to] as well. */
    fun copyInk(id: String, document: Document, from: Page, to: Page) {}
    /** A copy of the note's picture [imageId] under a new id; the new id. */
    fun copyImage(id: String, imageId: String): String? = null
    /** Puts a file the commands made where Termux and other apps can read it (Download/Notesis); the path there, or null. */
    fun shareFile(path: String): String? = null
    /** Takes a file [shareFile] put out away again. */
    fun unshareFile(path: String) {}
}

/** A bookmark of a note's PDF: its title, the PDF page it opens (from 0), how deep it sits. */
internal data class PdfMark(val title: String, val pdfPage: Int, val level: Int)

/** A page that holds what was searched for. */
internal data class SearchHit(val noteId: String, val title: String, val pageIndex: Int, val snippet: String)

internal enum class ExportKind { PDF, PNG, MD }

/** A command that could not be done, and how it is written. */
internal class CommandError(
    message: String,
    val usage: String? = null,
    /** What went wrong, as a word an agent can act on: NOT_FOUND, AMBIGUOUS, OPEN_ON_SCREEN, LOCKED, BAD_PAGE, NOT_SUPPORTED, NOT_READY, INVALID. */
    val code: String = "INVALID",
    /** Notes it may have meant, "id title", when the one named was not found or was not alone. */
    val candidates: List<String> = emptyList(),
) : Exception(message)

/**
 * The words of a command line: split at spaces, with "double" or 'single'
 * quotes keeping spaces in, and a backslash taking the next character as it
 * is. Null for a quote left open.
 */
internal fun commandWords(line: String): List<String>? {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var inWord = false
    var quote: Char? = null
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            // \n and \t stay as written, for the text commands to make a new line and a tab of; any other escaped character is itself.
            c == '\\' && quote != '\'' && i + 1 < line.length -> {
                val next = line[i + 1]
                if (next == 'n' || next == 't') word.append('\\')
                word.append(next); inWord = true; i++
            }
            quote != null -> if (c == quote) quote = null else word.append(c)
            c == '"' || c == '\'' -> { quote = c; inWord = true }
            c.isWhitespace() -> if (inWord) { words += word.toString(); word.clear(); inWord = false }
            else -> { word.append(c); inWord = true }
        }
        i++
    }
    if (quote != null) return null
    if (inWord) words += word.toString()
    return words
}

/**
 * The lines an SVG path draws, in its own units: one polyline per subpath,
 * curves flattened into short straight pieces. Handles M L H V C S Q T Z in
 * both cases; an arc (A) is drawn as a straight line to where it ends.
 */
internal fun svgPolylines(d: String): List<List<FloatArray>> {
    val tokens = Regex("[MmLlHhVvCcSsQqTtAaZz]|[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?").findAll(d).map { it.value }.toList()
    val lines = mutableListOf<MutableList<FloatArray>>()
    var line: MutableList<FloatArray>? = null
    var x = 0f; var y = 0f
    var startX = 0f; var startY = 0f
    var lastControlX = 0f; var lastControlY = 0f
    var lastCommand = ' '
    var command = ' '
    var i = 0
    fun number(): Float {
        val token = tokens.getOrNull(i) ?: throw CommandError("The path ends in the middle of a command.")
        val value = token.toFloatOrNull() ?: throw CommandError("The path has \"$token\" where a number should be.")
        if (!value.isFinite()) throw CommandError("The path has a number too large.")
        i++
        return value
    }
    fun lineTo(nx: Float, ny: Float) {
        val current = line ?: mutableListOf(floatArrayOf(x, y)).also { line = it; lines += it }
        current += floatArrayOf(nx, ny)
        x = nx; y = ny
    }
    fun curve(points: Int, at: (Float) -> FloatArray, length: Float) {
        val steps = ceil(length / CURVE_STEP).toInt().coerceIn(points, MAX_CURVE_STEPS)
        for (s in 1..steps) at(s / steps.toFloat()).let { lineTo(it[0], it[1]) }
    }
    while (i < tokens.size) {
        val token = tokens[i]
        if (token[0].isLetter()) { command = token[0]; i++ }
        else if (command == ' ') throw CommandError("A path starts with M, a move to where the pen goes down.")
        val relative = command.isLowerCase()
        val ox = if (relative) x else 0f
        val oy = if (relative) y else 0f
        when (command.uppercaseChar()) {
            'M' -> {
                x = ox + number(); y = oy + number()
                startX = x; startY = y
                line = null
                // Pairs after a move are lines.
                command = if (relative) 'l' else 'L'
            }
            'L' -> lineTo(ox + number(), oy + number())
            'H' -> lineTo(ox + number(), y)
            'V' -> lineTo(x, oy + number())
            'C', 'S' -> {
                val (c1x, c1y) = if (command.uppercaseChar() == 'C') ox + number() to oy + number()
                    else if (lastCommand.uppercaseChar() in "CS") 2 * x - lastControlX to 2 * y - lastControlY else x to y
                val c2x = ox + number(); val c2y = oy + number()
                val ex = ox + number(); val ey = oy + number()
                val sx = x; val sy = y
                val length = hypot(c1x - sx, c1y - sy) + hypot(c2x - c1x, c2y - c1y) + hypot(ex - c2x, ey - c2y)
                curve(4, { t ->
                    val u = 1 - t
                    floatArrayOf(u * u * u * sx + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * ex,
                        u * u * u * sy + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * ey)
                }, length)
                lastControlX = c2x; lastControlY = c2y
            }
            'Q', 'T' -> {
                val (cx, cy) = if (command.uppercaseChar() == 'Q') ox + number() to oy + number()
                    else if (lastCommand.uppercaseChar() in "QT") 2 * x - lastControlX to 2 * y - lastControlY else x to y
                val ex = ox + number(); val ey = oy + number()
                val sx = x; val sy = y
                curve(3, { t ->
                    val u = 1 - t
                    floatArrayOf(u * u * sx + 2 * u * t * cx + t * t * ex, u * u * sy + 2 * u * t * cy + t * t * ey)
                }, hypot(cx - sx, cy - sy) + hypot(ex - cx, ey - cy))
                lastControlX = cx; lastControlY = cy
            }
            'A' -> {
                repeat(5) { number() }
                lineTo(ox + number(), oy + number())
            }
            'Z' -> {
                if (line != null && (x != startX || y != startY)) lineTo(startX, startY)
                x = startX; y = startY
                line = null
            }
            else -> throw CommandError("The path has a command \"$command\" it cannot draw.")
        }
        lastCommand = command
        if (command.uppercaseChar() == 'Z') command = ' '
    }
    return lines.filter { it.size >= 2 }
}

/** Points written "x,y x,y; x,y x,y": a line per group between semicolons or bars. */
internal fun pointLines(text: String): List<List<FloatArray>> =
    text.split(';', '|').map { group ->
        Regex("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?").findAll(group).map { it.value.toFloat() }.toList().let { numbers ->
            if (numbers.size % 2 != 0) throw CommandError("Points come in pairs: \"x,y x,y\".")
            numbers.chunked(2).map { floatArrayOf(it[0], it[1]) }
        }
    }.filter { it.isNotEmpty() }

/** A colour as "#RRGGBB", "#AARRGGBB" or one of a few names. */
internal fun commandColor(text: String): Int {
    val named = mapOf("black" to 0xFF000000, "blue" to 0xFF1E5BD8, "red" to 0xFFD32F2F, "green" to 0xFF2E7D32,
        "gray" to 0xFF757575, "grey" to 0xFF757575, "orange" to 0xFFEF6C00, "purple" to 0xFF7B1FA2)
    named[text.lowercase()]?.let { return it.toInt() }
    val hex = text.removePrefix("#")
    val value = hex.toLongOrNull(16)?.takeIf { hex.length == 6 || hex.length == 8 }
        ?: throw CommandError("\"$text\" is not a colour: use #RRGGBB or a name such as blue.")
    return (if (hex.length == 6) value or 0xFF000000 else value).toInt()
}

/** A title as it is compared: Hangul composed one way, any case, spaces left out. */
internal fun noteKey(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).lowercase().filter { !it.isWhitespace() }

/** How many single letters must change to turn [a] into [b]. */
internal fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    var previous = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val current = IntArray(b.length + 1)
        current[0] = i + 1
        for (j in b.indices) current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
        previous = current
    }
    return previous[b.length]
}

/** The notes most like [ref], best first: for an answer that says what was perhaps meant. */
internal fun likelyNotes(ref: String, notes: List<NoteMeta>, max: Int = 5): List<NoteMeta> {
    val key = noteKey(ref)
    if (key.isEmpty()) return emptyList()
    return notes.map { it to noteKey(it.title) }
        .map { (note, title) ->
            val shared = title.contains(key) || key.contains(title)
            note to (if (shared) 0 else editDistance(key, title))
        }
        .filter { (note, distance) -> distance <= maxOf(2, key.length / 2) || noteKey(note.title).take(2) == key.take(2) }
        .sortedBy { it.second }
        .take(max)
        .map { it.first }
}

/**
 * The note [ref] names among [notes]: its id, its title (any case, spaces and
 * the way Hangul is composed aside), the start of its id, or a part of its
 * title - the first of these that names exactly one note. When none does, the
 * error carries the notes it most likely meant.
 */
internal fun findNote(ref: String, notes: List<NoteMeta>): NoteMeta {
    val wanted = ref.trim()
    if (wanted.isEmpty()) throw CommandError("Name a note by its id or its title.", code = "NOT_FOUND")
    notes.firstOrNull { it.id == wanted }?.let { return it }
    val key = noteKey(wanted)
    val tries = listOf<(NoteMeta) -> Boolean>(
        { it.title.equals(wanted, ignoreCase = true) },
        { noteKey(it.title) == key },
        { wanted.length >= 4 && it.id.startsWith(wanted) },
        { it.title.contains(wanted, ignoreCase = true) },
        { key.isNotEmpty() && noteKey(it.title).contains(key) },
    )
    for (test in tries) {
        val found = notes.filter(test)
        if (found.size == 1) return found[0]
        if (found.size > 1) throw CommandError("\"$ref\" names ${found.size} notes. Use the id.", code = "AMBIGUOUS",
            candidates = found.take(8).map { "${it.id} ${it.title}" })
    }
    val likely = likelyNotes(wanted, notes)
    throw CommandError("No note is called \"$ref\"." + (if (likely.isEmpty()) " `notes list` shows them." else " Perhaps one of the candidates."),
        code = "NOT_FOUND", candidates = likely.map { "${it.id} ${it.title}" })
}

/** A page number from 1, or "last", as an index from 0. */
internal fun pageIndex(word: String, count: Int): Int {
    if (word.equals("last", ignoreCase = true)) return count - 1
    val number = word.toIntOrNull() ?: throw CommandError("\"$word\" is not a page number.", code = "BAD_PAGE")
    if (number !in 1..count) throw CommandError("The note has pages 1 to $count.", code = "BAD_PAGE")
    return number - 1
}

/** The words of one command, sorted into those in order and the --options. */
private class Args(val positional: List<String>, val options: Map<String, String?>) {
    fun has(name: String) = name in options
    fun text(name: String): String? = options[name]
    fun float(name: String, range: ClosedFloatingPointRange<Float>): Float? = options[name]?.let { raw ->
        val value = raw.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw CommandError("--$name wants a number, not \"$raw\".")
        if (value !in range) throw CommandError("--$name goes from ${range.start.clean()} to ${range.endInclusive.clean()}.")
        value
    }
    fun long(name: String): Long? = options[name]?.let { raw ->
        raw.toLongOrNull()?.takeIf { it >= 0 } ?: throw CommandError("--$name wants a time as the \"modified\" number a note gives, not \"$raw\".")
    }
    fun int(name: String, range: IntRange): Int? = options[name]?.let { raw ->
        val value = raw.toIntOrNull() ?: throw CommandError("--$name wants a whole number, not \"$raw\".")
        if (value !in range) throw CommandError("--$name goes from ${range.first} to ${range.last}.")
        value
    }

    companion object {
        /** [valued] options take the word after them; [flags] stand alone. */
        fun of(words: List<String>, valued: Set<String>, flags: Set<String>, usage: String): Args {
            val positional = mutableListOf<String>()
            val options = mutableMapOf<String, String?>()
            var i = 0
            while (i < words.size) {
                val word = words[i]
                if (word.startsWith("--") && word.length > 2) {
                    val (name, inline) = word.drop(2).split('=', limit = 2).let { it[0] to it.getOrNull(1) }
                    when (name) {
                        in valued -> {
                            options[name] = inline ?: words.getOrNull(i + 1)?.also { i++ }
                                ?: throw CommandError("--$name needs a value.", usage)
                        }
                        in flags -> options[name] = null
                        else -> throw CommandError("There is no option --$name here.", usage)
                    }
                } else positional += word
                i++
            }
            return Args(positional, options)
        }
    }
}

private fun Float.clean(): String = if (this == toInt().toFloat()) toInt().toString() else toString()

/** The commands, as they are written, for `help` and for answers to a wrong one. */
internal val COMMAND_USAGE = linkedMapOf(
    "help" to "help [COMMAND]",
    "status" to "status",
    "notes list" to "notes list [--folder PATH] [--tag TAG] [--search WORDS] [--since TIME] [--trash] [--limit N] [--offset N] [--brief]",
    "search" to "search WORDS [--limit N]   (titles, typed text, PDF text)",
    "note read" to "note read NOTE [--pages 1-3,5] [--outline] [--text-only] [--sizes] [--max-chars N] [--from N] [--since TIME]",
    "note new" to "note new TITLE [--folder PATH] [--paper blank|lined|grid|dot|narrow|cornell] [--pages N]",
    "note new-md" to "note new-md TITLE [--folder PATH] [--text TEXT]",
    "note dup" to "note dup NOTE [--title TITLE]",
    "note merge" to "note merge NOTE NOTE... --title TITLE",
    "note rename" to "note rename NOTE TITLE",
    "note mv" to "note mv NOTE FOLDER   (\"/\" for the top level)",
    "note tag" to "note tag NOTE [+TAG|TAG] [-TAG] [--clear]",
    "note fav" to "note fav NOTE [--off]",
    "note label" to "note label NOTE COLOR|none",
    "note trash" to "note trash NOTE",
    "note restore" to "note restore NOTE",
    "tags list" to "tags list",
    "folder list" to "folder list",
    "folder new" to "folder new PATH",
    "folder rename" to "folder rename PATH NEW-PATH",
    "page add" to "page add NOTE [--after PAGE] [--count N]",
    "page rm" to "page rm NOTE PAGES   (such as 3 or 2-4)",
    "page mv" to "page mv NOTE PAGE TO",
    "page dup" to "page dup NOTE PAGE",
    "page paper" to "page paper NOTE PAGES blank|lined|grid|dot|narrow|cornell",
    "page bookmark" to "page bookmark NOTE PAGE [--off]",
    "page image" to "page image NOTE PAGE [--width 200-1600]   (a picture of the page as it looks)",
    "toc show" to "toc show NOTE [--pdf]   (--pdf: the PDF's own bookmarks)",
    "toc set" to "toc set NOTE PAGE TITLE [--level 0-5] [--pdf]   (--pdf: PAGE is the PDF's page number)",
    "toc rm" to "toc rm NOTE PAGE",
    "toc clear" to "toc clear NOTE",
    "toc import-pdf" to "toc import-pdf NOTE [--keep]   (the PDF's bookmarks as the outline)",
    "pdf info" to "pdf info NOTE",
    "pdf text" to "pdf text NOTE [--pages 1-3] [--max-chars N]",
    "pdf find" to "pdf find NOTE WORDS",
    "text list" to "text list NOTE [--pages 1-3]",
    "text add" to "text add NOTE PAGE TEXT [--x X] [--y Y] [--size PT] [--color #RRGGBB] [--width W] [--md]",
    "text edit" to "text edit NOTE PAGE BOX TEXT   (BOX is its number in text list)",
    "text rm" to "text rm NOTE PAGE BOX",
    "ink add" to "ink add NOTE PAGE|board (--path \"M 10 10 L 200 80\" | --points \"x,y x,y; x,y ...\") [--color #RRGGBB] [--width W] [--near PAGE]",
    "ink erase" to "ink erase NOTE PAGE|board (--rect X,Y,W,H | --all) [--near PAGE]",
    "md outline" to "md outline NOTE",
    "md links" to "md links NOTE",
    "md write" to "md write NOTE TEXT",
    "md append" to "md append NOTE TEXT [--under HEADING]",
    "md replace" to "md replace NOTE OLD NEW [--all]",
    "export" to "export NOTE [--format pdf|png|md] [--pages 1-3] [--no-ink] [--to shared]",
    "preview" to "preview COMMAND...   (what the line would do; does nothing)",
    "undo" to "undo",
)

/** Commands of one word; the rest are two, such as `page add`. */
internal val SINGLE_WORD_COMMANDS = setOf("help", "status", "search", "export", "preview", "undo")

/** Paper names as commands write them. */
internal val PAPER_NAMES = linkedMapOf(
    "blank" to PageBackground.BLANK, "lined" to PageBackground.LINED, "grid" to PageBackground.GRID,
    "dot" to PageBackground.DOT, "narrow" to PageBackground.NARROW_LINED, "cornell" to PageBackground.CORNELL,
)

/**
 * Runs note commands on [workspace] and keeps what each change replaced, so
 * `undo` can put it back. One of these lives as long as the terminal it
 * serves; undo does not outlast it.
 */
internal class NoteCommands(private val workspace: NoteWorkspace) {
    private class Step(val id: Long, val label: String, val reverts: List<() -> Unit>, val releases: List<() -> Unit>,
        /** The notes whose content it changed, for undo to hold their screens too. */
        val notes: Set<String>)
    private class Done(
        val result: Any, val label: String? = null,
        /** Lets go of what [revert] kept, once it can no longer be called. */
        val release: (() -> Unit)? = null,
        /** Last, so a trailing lambda at a call is the way to take the change back. */
        val revert: (() -> Unit)? = null,
    )

    private val history = ArrayDeque<Step>()
    /** Notes on screen held while this batch runs; let go, and read again if changed, when it ends. */
    private val held = LinkedHashSet<String>()
    /** Notes whose content this batch changed. */
    private val touched = LinkedHashSet<String>()
    private var lastStepId = 0L

    /** How many changes [undo] can still take back. */
    val undoable: Int get() = history.size

    /** One command line; its answer as JSON text. */
    fun run(line: String): String = runAll(listOf(line))

    /**
     * What each of [lines] would do, without doing it: for a card that asks
     * before a change is made. A note a line names is found as running it
     * would find it; reading changes nothing.
     */
    @Synchronized
    fun preview(lines: List<String>): List<ChangeLine> {
        val notes = runCatching { workspace.notes() + workspace.trashed() }.getOrDefault(emptyList())
        return cleanLines(lines).map { line ->
            val words = commandWords(line)?.let { if (it.firstOrNull() == "notesis") it.drop(1) else it }
            if (words == null) ChangeLine("Not a command: {0}", listOf(line.take(80)), changes = false)
            else describeCommand(words, notes)
        }
    }

    /**
     * Takes back the change numbered [stepId] (from its answer's "step"), only
     * while it is still the latest: an older one would take the newer ones
     * with it, or leave them standing on what is no longer there.
     */
    @Synchronized
    fun undoIfLast(stepId: Long): String {
        if (history.lastOrNull()?.id != stepId) {
            return failure(CommandError("Newer changes were made after this one, so it cannot be taken back on its own. `undo` takes back the latest first."))
        }
        return runAll(listOf("undo"))
    }

    /**
     * Several lines as one change: done in order, and if one fails the ones
     * before it are taken back, so the notes are as they were or as all the
     * lines together leave them. One `undo` takes the whole batch back.
     */
    @Synchronized
    fun runAll(lines: List<String>): String {
        held.clear()
        touched.clear()
        var ok = false
        try {
            val answer = runBatch(lines)
            ok = runCatching { JSONObject(answer).optBoolean("ok") }.getOrDefault(false)
            return answer
        } finally {
            // A batch that failed was taken back on disk, which is what the screen still shows.
            for (id in held) runCatching { workspace.letGo(id, changed = ok && id in touched) }
            held.clear()
        }
    }

    private fun runBatch(lines: List<String>): String {
        val commands = cleanLines(lines)
        if (commands.isEmpty()) return failure(CommandError("Nothing to do. `notesis help` lists the commands."))
        val done = mutableListOf<Done>()
        for ((index, line) in commands.withIndex()) {
            try {
                val words = commandWords(line) ?: throw CommandError("A quote is left open.")
                val command = if (words.firstOrNull() == "notesis") words.drop(1) else words
                if (command.firstOrNull() == "undo" && commands.size > 1) throw CommandError("undo goes on a line of its own.")
                done += execute(command)
            } catch (error: CommandError) {
                // Taken back in reverse, so each revert finds what it left.
                for (step in done.asReversed()) { step.revert?.let { runCatching(it) }; step.release?.let { runCatching(it) } }
                return failure(error, line = index + 1, of = commands.size, undone = done.count { it.revert != null })
            } catch (error: Exception) {
                for (step in done.asReversed()) { step.revert?.let { runCatching(it) }; step.release?.let { runCatching(it) } }
                return failure(CommandError("It failed: ${error.message ?: error.javaClass.simpleName}"),
                    line = index + 1, of = commands.size, undone = done.count { it.revert != null })
            }
        }
        val changes = done.filter { it.revert != null }
        var step = 0L
        if (changes.isNotEmpty()) {
            step = ++lastStepId
            history.addLast(Step(step, changes.joinToString("; ") { it.label.orEmpty() }, changes.map { it.revert!! },
                changes.mapNotNull { it.release }, touched.toSet()))
            // A change too old to take back no longer needs the copy kept for it.
            while (history.size > UNDO_STEPS) history.removeFirst().releases.forEach { runCatching(it) }
        }
        return JSONObject().apply {
            put("ok", true)
            if (done.size == 1) put("result", done[0].result) else put("results", JSONArray(done.map { it.result }))
            if (changes.isNotEmpty()) {
                put("undo", "notesis undo takes back: " + history.last().label)
                put("step", step)
                put("changes", JSONArray(changes.map { it.label.orEmpty() }))
            }
        }.toString()
    }

    private fun cleanLines(lines: List<String>): List<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun failure(error: CommandError, line: Int = 1, of: Int = 1, undone: Int = 0): String = JSONObject().apply {
        put("ok", false)
        put("error", error.message)
        put("code", error.code)
        if (error.candidates.isNotEmpty()) put("candidates", JSONArray(error.candidates))
        error.usage?.let { put("usage", it) }
        if (of > 1) {
            put("line", line)
            if (undone > 0) put("undone", "The $undone change(s) before it were taken back.")
        }
    }.toString()

    private fun execute(words: List<String>): Done {
        if (words.isEmpty()) throw CommandError("Nothing to do. `notesis help` lists the commands.")
        val single = SINGLE_WORD_COMMANDS
        val name = if (words[0] in single || words.size < 2) words[0] else "${words[0]} ${words[1]}"
        val rest = words.drop(if (words[0] in single || words.size < 2) 1 else 2)
        val usage = COMMAND_USAGE[name] ?: throw CommandError("There is no command \"${words.joinToString(" ").take(40)}\". `help` lists them.",
            code = "UNKNOWN_COMMAND")
        fun args(valued: Set<String> = emptySet(), flags: Set<String> = emptySet()) = Args.of(rest, valued, flags, usage)
        return when (name) {
            "help" -> help(rest)
            "undo" -> undo()
            "status" -> status()
            "preview" -> preview(rest, usage)
            "search" -> search(args(setOf("limit")), usage)
            "notes list" -> listNotes(args(setOf("folder", "tag", "search", "limit", "offset", "since"), setOf("trash", "brief")))
            "note read" -> readNote(args(setOf("pages", "max-chars", "from", "since"), setOf("outline", "text-only", "sizes")), usage)
            "note new" -> newNote(args(setOf("folder", "paper", "pages")), usage)
            "note new-md" -> newMarkdown(args(setOf("folder", "text")), usage)
            "note dup" -> duplicateNote(args(setOf("title")), usage)
            "note merge" -> mergeNotes(args(setOf("title")), usage)
            "note rename" -> renameNote(args(), usage)
            "note mv" -> moveNote(args(), usage)
            "note tag" -> tagNote(args(flags = setOf("clear")), usage)
            "note fav" -> favoriteNote(args(flags = setOf("off")), usage)
            "note label" -> labelNote(args(), usage)
            "note trash" -> trashNote(args(), usage)
            "note restore" -> restoreNote(args(), usage)
            "tags list" -> listTags()
            "folder list" -> listFolders()
            "folder new" -> newFolder(args(), usage)
            "folder rename" -> renameFolder(args(), usage)
            "page add" -> addPages(args(setOf("after", "count")), usage)
            "page rm" -> removePages(args(), usage)
            "page mv" -> movePage(args(), usage)
            "page dup" -> duplicatePage(args(), usage)
            "page paper" -> setPaper(args(), usage)
            "page bookmark" -> bookmarkPage(args(flags = setOf("off")), usage)
            "page image" -> pageImage(args(setOf("width")), usage)
            "toc show" -> showToc(args(flags = setOf("pdf")), usage)
            "toc set" -> setToc(args(setOf("level"), setOf("pdf")), usage)
            "toc rm" -> removeToc(args(), usage)
            "toc clear" -> clearToc(args(), usage)
            "toc import-pdf" -> importPdfToc(args(flags = setOf("keep")), usage)
            "pdf info" -> pdfInfo(args(), usage)
            "pdf text" -> pdfText(args(setOf("pages", "max-chars")), usage)
            "pdf find" -> pdfFind(args(), usage)
            "text list" -> listText(args(setOf("pages")), usage)
            "text add" -> addText(args(setOf("x", "y", "size", "color", "width"), setOf("md")), usage)
            "text edit" -> editText(args(), usage)
            "text rm" -> removeText(args(), usage)
            "ink add" -> addInk(args(setOf("path", "points", "color", "width", "near")), usage)
            "ink erase" -> eraseInk(args(setOf("rect", "near"), setOf("all")), usage)
            "md outline" -> markdownOutline(args(), usage)
            "md links" -> markdownLinks(args(), usage)
            "md write" -> writeMarkdown(args(), usage)
            "md append" -> appendMarkdown(args(setOf("under")), usage)
            "md replace" -> replaceMarkdown(args(flags = setOf("all")), usage)
            "export" -> export(args(setOf("format", "pages", "to"), setOf("no-ink")), usage)
            else -> throw CommandError("There is no command \"$name\".", code = "UNKNOWN_COMMAND")
        }
    }

    // ---- reading -------------------------------------------------------------

    private fun help(rest: List<String>): Done {
        val wanted = rest.joinToString(" ")
        val shown = if (wanted.isBlank()) COMMAND_USAGE.values.toList()
            else COMMAND_USAGE.filterKeys { it.startsWith(wanted) }.values.toList().ifEmpty {
                throw CommandError("There is no command \"$wanted\".", code = "UNKNOWN_COMMAND")
            }
        return Done(JSONObject().apply {
            put("commands", JSONArray(shown))
            put("notes", "NOTE is a note's id or title. PAGE is a page number from 1, or last. " +
                "A note open on screen takes the change and shows it at once. PAGE board is the ink beside the pages. " +
                "`undo` takes back the last change. A failure has a code (NOT_FOUND, AMBIGUOUS, OPEN_ON_SCREEN, ...) " +
                "and, for a note not found, the notes it may have meant.")
        })
    }

    private fun status(): Done {
        val notes = workspace.notes()
        val open = workspace.openNotes()
        return Done(JSONObject().apply {
            workspace.appVersion().takeIf { it.isNotEmpty() }?.let { put("version", it) }
            put("notes", notes.size)
            put("handwritten", notes.count { it.kind == NoteKind.INK })
            put("markdown", notes.count { it.kind == NoteKind.MARKDOWN })
            put("trash", workspace.trashed().size)
            put("folders", workspace.folders().size)
            put("openOnScreen", JSONArray(notes.filter { it.id in open }.map { "${it.id} ${it.title}" }))
            put("undoSteps", history.size)
        })
    }

    private fun preview(rest: List<String>, usage: String): Done {
        val words = (if (rest.firstOrNull() == "notesis") rest.drop(1) else rest).ifEmpty { throw CommandError("Give the line to look at.", usage) }
        val line = describeCommand(words, workspace.notes() + workspace.trashed())
        return Done(JSONObject().put("does", line.text()).put("changes", line.changes))
    }

    private fun search(args: Args, usage: String): Done {
        val query = args.positional.joinToString(" ").trim().ifEmpty { throw CommandError("Give the words to look for.", usage) }
        val limit = args.int("limit", 1..MAX_SEARCH) ?: SEARCH_LIMIT
        val hits = workspace.search(query, limit + 1)
        return Done(JSONObject().apply {
            put("query", query)
            put("count", hits.size.coerceAtMost(limit))
            put("hits", JSONArray(hits.take(limit).map { hit ->
                JSONObject().put("id", hit.noteId).put("title", hit.title).also {
                    if (hit.pageIndex >= 0) it.put("page", hit.pageIndex + 1)
                    if (hit.snippet.isNotBlank()) it.put("snippet", hit.snippet.trim().take(200))
                }
            }))
            if (hits.size > limit) put("more", "There are more: `search ${commandQuote(query)} --limit ${(limit * 2).coerceAtMost(MAX_SEARCH)}`.")
        })
    }

    private fun listNotes(args: Args): Done {
        val folder = args.text("folder")?.let { normalizeFolderPath(it) ?: "" }
        val tag = args.text("tag")
        val search = args.text("search")?.let(::noteKey)
        val limit = args.int("limit", 1..MAX_LIST) ?: LIST_LIMIT
        val offset = args.int("offset", 0..Int.MAX_VALUE) ?: 0
        val since = args.long("since")
        val all = if (args.has("trash")) workspace.trashed() else workspace.notes()
        // Ids shortened as far as they stay unique: a list of notes is mostly ids otherwise. Every command takes them.
        val ids = shortIds(all.map { it.id })
        val notes = all.filter { meta ->
            (since == null || meta.modified > since) &&
            (folder == null || meta.folder == folder || meta.folder.startsWith("$folder/")) &&
                (tag == null || meta.tags.any { it.equals(tag, ignoreCase = true) }) &&
                (search == null || noteKey(meta.title).contains(search))
        }.sortedByDescending { it.modified }
        val shown = notes.drop(offset).take(limit)
        return Done(JSONObject().apply {
            put("total", notes.size)
            put("count", shown.size)
            if (offset > 0) put("offset", offset)
            put("notes", JSONArray(shown.map { (if (args.has("brief")) briefJson(it) else noteJson(it)).put("id", ids[it.id] ?: it.id) }))
            if (offset + shown.size < notes.size) put("next", "--offset ${offset + shown.size}")
        })
    }

    private fun briefJson(meta: NoteMeta): JSONObject = JSONObject().apply {
        put("id", meta.id)
        put("title", meta.title)
        if (meta.kind == NoteKind.MARKDOWN) put("kind", "markdown")
        if (meta.folder.isNotEmpty()) put("folder", meta.folder)
    }

    // What is empty or usual is left out: an agent pays for every field of every note.
    private fun noteJson(meta: NoteMeta): JSONObject = JSONObject().apply {
        put("id", meta.id)
        put("title", meta.title)
        if (meta.kind == NoteKind.MARKDOWN) put("kind", "markdown")
        if (meta.folder.isNotEmpty()) put("folder", meta.folder)
        if (meta.tags.isNotEmpty()) put("tags", JSONArray(meta.tags))
        if (meta.kind == NoteKind.INK) put("pages", meta.pageCount)
        put("modified", meta.modified)
        if (meta.favorite) put("favorite", true)
        if (meta.locked) put("locked", true)
    }

    private fun readNote(args: Args, usage: String): Done {
        val meta = note(args, usage)
        if (meta.locked) throw CommandError("\"${meta.title}\" is locked. Open it in the app with the password.", code = "LOCKED")
        // Read before and not changed since: said in a word rather than given again.
        args.long("since")?.let { since ->
            if (meta.modified in 1..since) return Done(JSONObject().put("id", meta.id).put("unchanged", true).put("modified", meta.modified))
        }
        val json = noteJson(meta)
        val budget = args.int("max-chars", MIN_BUDGET..MAX_BUDGET) ?: READ_BUDGET
        if (meta.kind == NoteKind.MARKDOWN) {
            val text = workspace.markdown(meta.id) ?: ""
            json.put("length", text.length)
            if (args.has("outline")) {
                json.put("outline", headingsJson(text))
                return Done(json)
            }
            val from = (args.int("from", 0..Int.MAX_VALUE) ?: 0).coerceAtMost(text.length)
            val part = text.substring(from, (from + budget).coerceAtMost(text.length))
            json.put("text", part)
            if (from > 0) json.put("from", from)
            if (from + part.length < text.length) {
                json.put("truncated", true)
                json.put("next", "note read ${meta.id} --from ${from + part.length}")
            }
            return Done(json)
        }
        val document = workspace.load(meta.id)
        if (args.has("outline")) {
            json.put("toc", tocJson(document))
            val pdf = document.pages.count { it.pdfPageIndex >= 0 }
            if (pdf > 0) json.put("pdfPages", pdf)
            return Done(json)
        }
        val indices = args.text("pages")?.let { parsePageRange(it, document.pages.size)
            ?: throw CommandError("\"$it\" is not a page range of this note, which has pages 1 to ${document.pages.size}.", code = "BAD_PAGE") }
            ?: document.pages.indices.toList()
        val textOnly = args.has("text-only")
        val pages = JSONArray()
        var used = json.toString().length
        var stopped = -1
        for ((at, index) in indices.withIndex()) {
            val page = pageJson(meta, document, index, textOnly, args.has("sizes"))
            if (textOnly && !page.has("text") && !page.has("toc")) continue
            val size = page.toString().length
            if (pages.length() > 0 && used + size > budget) { stopped = at; break }
            pages.put(page)
            used += size
        }
        json.put("pages", pages)
        // The ink off the pages (the whiteboard), when there is any: how much.
        val boardInk = workspace.inkCount(meta.id, document.board)
        if (boardInk > 0) json.put("board", JSONObject().put("ink", boardInk))
        if (stopped >= 0) {
            json.put("truncated", true)
            val flags = (if (textOnly) " --text-only" else "") + (if (args.has("sizes")) " --sizes" else "")
            json.put("next", "note read ${meta.id} --pages ${pageList(indices.drop(stopped))}$flags")
        }
        return Done(json)
    }

    private fun pageJson(meta: NoteMeta, document: Document, index: Int, textOnly: Boolean, sizes: Boolean): JSONObject {
        val page = document.pages[index]
        return JSONObject().apply {
            put("page", index + 1)
            if (!textOnly) put("id", page.id)
            if (sizes) put("size", JSONArray(listOf(page.width.clean(), page.height.clean())))
            page.tocTitle?.let { put("toc", JSONObject().put("title", it).put("level", page.tocLevel)) }
            if (!textOnly) {
                if (page.bookmarked) put("bookmarked", true)
                if (page.pdfPageIndex >= 0) put("pdfPage", page.pdfPageIndex + 1)
                page.besideOf?.let { host -> document.pages.indexOfFirst { it.id == host }.takeIf { it >= 0 }?.let { put("practiceFor", it + 1) } }
            }
            val typed = page.images.mapNotNull { image -> image.textContent?.let { image to it } }
            if (typed.isNotEmpty()) put("text", JSONArray(typed.map { (image, content) ->
                if (textOnly) content.text else JSONObject().put("text", content.text).put("x", image.x.clean()).put("y", image.y.clean())
            }))
            if (!textOnly) {
                val pictures = page.images.count { it.textContent == null }
                if (pictures > 0) put("pictures", pictures)
                put("ink", workspace.inkCount(meta.id, page))
            }
        }
    }

    private fun tocJson(document: Document): JSONArray =
        JSONArray(document.pages.withIndex().filter { it.value.tocTitle != null }.map { (index, page) ->
            JSONObject().put("page", index + 1).put("title", page.tocTitle).put("level", page.tocLevel)
                .also { if (page.tocHighlighted) it.put("highlighted", true) }
        })

    private fun showToc(args: Args, usage: String): Done {
        val meta = inkNote(args, usage)
        val document = workspace.load(meta.id)
        if (args.has("pdf")) {
            val marks = workspace.pdfOutline(meta.id)
            return Done(JSONObject().apply {
                put("note", meta.title)
                put("pdfBookmarks", JSONArray(marks.map { mark ->
                    JSONObject().put("title", mark.title).put("level", mark.level).put("pdfPage", mark.pdfPage + 1).also { entry ->
                        notePageOfPdf(document, mark.pdfPage)?.let { entry.put("page", it + 1) }
                    }
                }))
                if (marks.isEmpty()) put("note", "The PDF has no bookmarks of its own. `pdf text` reads its contents page.")
            })
        }
        return Done(JSONObject().put("note", meta.title).put("toc", tocJson(document)))
    }

    private fun pdfInfo(args: Args, usage: String): Done {
        val meta = inkNote(args, usage)
        val document = workspace.load(meta.id)
        val pdf = pdfPages(meta, document)
        val sample = pdf.take(PDF_SAMPLE)
        val texts = workspace.pdfText(meta.id, sample.map { document.pages[it] })
        val withText = texts.count { !it.isNullOrBlank() }
        val bookmarks = workspace.pdfOutline(meta.id).size
        val offset = printedPageOffset(sample.map { document.pages[it].pdfPageIndex }.zip(texts))
        return Done(JSONObject().apply {
            put("note", meta.title)
            put("pages", document.pages.size)
            put("pdfPages", pdf.size)
            put("textLayer", when (withText) { 0 -> "none"; sample.size -> "yes"; else -> "partly" })
            put("bookmarks", bookmarks)
            offset?.let {
                put("printedOffset", it)
                put("printedPages", "PDF page = printed page + $it")
            }
            put("next", JSONArray(buildList {
                if (bookmarks > 0) add("toc import-pdf ${meta.id}   (the PDF's own bookmarks as the outline)")
                if (withText > 0) add("pdf text ${meta.id} --pages 1-${pdf.size.coerceAtMost(8)}   (find the contents page)")
                else add("page image ${meta.id} 1   (no text layer: look at the pages)")
            }))
        })
    }

    private fun pdfText(args: Args, usage: String): Done {
        val meta = inkNote(args, usage)
        val document = workspace.load(meta.id)
        val wanted = args.text("pages")?.let { parsePageRange(it, document.pages.size)
            ?: throw CommandError("\"$it\" is not a page range of this note, which has pages 1 to ${document.pages.size}.", code = "BAD_PAGE") }
        val all = pdfPages(meta, document)
        val chosen = wanted?.filter { document.pages[it].pdfPageIndex >= 0 } ?: all
        if (chosen.isEmpty()) throw CommandError("None of those pages has a PDF page under it.", code = "NO_PDF")
        val budget = args.int("max-chars", MIN_BUDGET..MAX_BUDGET) ?: PDF_TEXT_BUDGET
        val pages = JSONArray()
        var used = 0
        var stopped = -1
        var blank = 0
        loop@ for (chunk in chosen.chunked(PDF_CHUNK)) {
            val texts = workspace.pdfText(meta.id, chunk.map { document.pages[it] })
            for ((i, index) in chunk.withIndex()) {
                val text = texts.getOrNull(i)?.trim().orEmpty()
                if (text.isEmpty()) blank++
                if (pages.length() > 0 && used + text.length > budget) { stopped = chosen.indexOf(index); break@loop }
                pages.put(JSONObject().put("page", index + 1).put("pdfPage", document.pages[index].pdfPageIndex + 1).put("text", text))
                used += text.length
            }
        }
        return Done(JSONObject().apply {
            put("note", meta.title)
            put("chars", used)
            put("pages", pages)
            if (stopped >= 0) {
                put("truncated", true)
                put("next", "pdf text ${meta.id} --pages ${pageList(chosen.drop(stopped))}")
            }
            if (blank == pages.length()) put("hint", "These pages have no text layer (scanned pictures). `page image` shows them.")
        })
    }

    private fun pdfFind(args: Args, usage: String): Done {
        val meta = inkNote(args, usage)
        val query = args.positional.drop(1).joinToString(" ").trim().ifEmpty { throw CommandError("Give the words to look for.", usage) }
        val needle = query.lowercase().replace(Regex("\\s+"), " ")
        val document = workspace.load(meta.id)
        val hits = JSONArray()
        var more = false
        loop@ for (chunk in pdfPages(meta, document).chunked(PDF_CHUNK)) {
            val texts = workspace.pdfText(meta.id, chunk.map { document.pages[it] })
            for ((i, index) in chunk.withIndex()) {
                val flat = texts.getOrNull(i).orEmpty().replace(Regex("\\s+"), " ")
                val at = flat.lowercase().indexOf(needle)
                if (at < 0) continue
                if (hits.length() >= MAX_FIND) { more = true; break@loop }
                val snippet = flat.substring((at - SNIPPET).coerceAtLeast(0), (at + needle.length + SNIPPET).coerceAtMost(flat.length)).trim()
                hits.put(JSONObject().put("page", index + 1).put("pdfPage", document.pages[index].pdfPageIndex + 1).put("snippet", snippet))
            }
        }
        return Done(JSONObject().put("query", query).put("count", hits.length()).put("hits", hits).also { if (more) it.put("more", true) })
    }

    private fun pageImage(args: Args, usage: String): Done {
        val meta = inkNote(args, usage)
        val document = workspace.load(meta.id)
        val index = pageIndex(args.positional.getOrNull(1) ?: throw CommandError("Give the page.", usage), document.pages.size)
        val width = args.int("width", 200..1600) ?: IMAGE_WIDTH
        val bytes = workspace.pageImage(meta.id, index, width) ?: throw CommandError("The page could not be drawn.", code = "NOT_SUPPORTED")
        return Done(JSONObject().put("page", index + 1).put("width", width).put("bytes", bytes.size)
            .put("image", JSONObject().put("mime", "image/jpeg").put("data", java.util.Base64.getEncoder().encodeToString(bytes))))
    }

    private fun listText(args: Args, usage: String): Done {
        val meta = inkNote(args, usage)
        val document = workspace.load(meta.id)
        val indices = args.text("pages")?.let { parsePageRange(it, document.pages.size)
            ?: throw CommandError("\"$it\" is not a page range of this note, which has pages 1 to ${document.pages.size}.", code = "BAD_PAGE") }
            ?: document.pages.indices.toList()
        val pages = indices.mapNotNull { index ->
            val boxes = textBoxes(document.pages[index])
            if (boxes.isEmpty()) null else JSONObject().put("page", index + 1).put("boxes", JSONArray(boxes.withIndex().map { (n, image) ->
                JSONObject().put("box", n + 1).put("text", image.textContent!!.text).put("x", image.x.clean()).put("y", image.y.clean())
                    .put("width", image.width.clean()).put("height", image.height.clean())
                    .also { if (image.textContent.markdown) it.put("md", true) }
            }))
        }
        return Done(JSONObject().put("note", meta.title).put("pages", JSONArray(pages)))
    }

    private fun markdownOutline(args: Args, usage: String): Done {
        val meta = markdownNote(args, usage)
        return Done(JSONObject().put("note", meta.title).put("outline", headingsJson(workspace.markdown(meta.id).orEmpty())))
    }

    private fun markdownLinks(args: Args, usage: String): Done {
        val meta = markdownNote(args, usage)
        val notes = workspace.notes()
        val out = wikiLinks(workspace.markdown(meta.id).orEmpty())
        val key = noteKey(meta.title)
        val back = notes.filter { it.id != meta.id && it.kind == NoteKind.MARKDOWN && !it.locked }
            .filter { other -> wikiLinks(workspace.markdown(other.id).orEmpty()).any { noteKey(it) == key } }
        return Done(JSONObject().apply {
            put("note", meta.title)
            put("links", JSONArray(out.map { link ->
                JSONObject().put("to", link).also { entry ->
                    notes.firstOrNull { noteKey(it.title) == noteKey(link) }?.let { entry.put("id", it.id) } ?: entry.put("missing", true)
                }
            }))
            put("backlinks", JSONArray(back.map { JSONObject().put("id", it.id).put("title", it.title) }))
        })
    }

    private fun export(args: Args, usage: String): Done {
        val meta = note(args, usage)
        if (meta.locked) throw CommandError("\"${meta.title}\" is locked.", code = "LOCKED")
        val format = args.text("format")?.let { raw ->
            ExportKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw CommandError("--format is pdf, png or md.", usage)
        } ?: if (meta.kind == NoteKind.MARKDOWN) ExportKind.MD else ExportKind.PDF
        val shared = when (val to = args.text("to")) {
            null -> false
            "shared" -> true
            else -> throw CommandError("--to is shared (Download/Notesis), not \"$to\".", usage)
        }
        if (meta.kind == NoteKind.MARKDOWN && format != ExportKind.MD) throw CommandError("A Markdown note goes out as md.", code = "NOT_SUPPORTED")
        val count = if (meta.kind == NoteKind.INK) workspace.load(meta.id).pages.size else 0
        val pages = args.text("pages")?.let { parsePageRange(it, count)
            ?: throw CommandError("\"$it\" is not a page range of this note, which has pages 1 to $count.", code = "BAD_PAGE") }
            ?: (0 until count).toList()
        if (format == ExportKind.PNG && args.has("no-ink")) throw CommandError("Pictures are of the page as it looks; --no-ink is for pdf.")
        if (format == ExportKind.PNG && pages.size > 1 && pages.zipWithNext().any { (a, b) -> b != a + 1 }) {
            throw CommandError("Pictures are of pages in a row, such as 2-4.")
        }
        val path = workspace.export(meta.id, meta.title, format, pages, !args.has("no-ink"))
            ?: throw CommandError("The note could not be written out.")
        val result = JSONObject().put("file", path).put("format", format.name.lowercase()).put("pages", pages.size)
        if (!shared) return Done(result.put("note", "This file is inside the app. Add --to shared to put it in Download/Notesis, where Termux can read it."))
        val out = workspace.shareFile(path) ?: throw CommandError("The file could not be put in Download/Notesis.")
        return Done(result.put("file", out).put("shared", true), "put \"${meta.title}\" in Download/Notesis") { workspace.unshareFile(out) }
    }

    // ---- changing ------------------------------------------------------------

    private fun undo(): Done {
        val step = history.lastOrNull() ?: throw CommandError("There is nothing to undo.")
        // A note on screen is held as for any change, before anything is taken back.
        for (id in step.notes) workspace.notes().firstOrNull { it.id == id }?.let(::holdIfOpen)
        history.removeLast()
        for (revert in step.reverts.asReversed()) revert()
        step.releases.forEach { runCatching(it) }
        return Done(JSONObject().put("undone", step.label))
    }

    private fun renameNote(args: Args, usage: String): Done {
        val meta = changeable(args, usage)
        val title = args.positional.drop(1).joinToString(" ").trim()
        if (title.isEmpty()) throw CommandError("Give the new title.", usage)
        if (!workspace.rename(meta.id, title)) throw CommandError("The note could not be renamed.")
        return Done(JSONObject().put("id", meta.id).put("title", title), "rename \"${meta.title}\" to \"$title\"") {
            workspace.rename(meta.id, meta.title)
        }
    }

    private fun moveNote(args: Args, usage: String): Done {
        val meta = filable(args, usage)
        val raw = args.positional.getOrNull(1) ?: throw CommandError("Give the folder, or / for the top level.", usage)
        val folder = normalizeFolderPath(raw) ?: ""
        workspace.setFolder(meta.id, folder)
        return Done(JSONObject().put("id", meta.id).put("folder", folder),
            "move \"${meta.title}\" to ${folder.ifEmpty { "the top level" }}") { workspace.setFolder(meta.id, meta.folder) }
    }

    private fun tagNote(args: Args, usage: String): Done {
        val meta = filable(args, usage)
        val tags = if (args.has("clear")) mutableListOf() else meta.tags.toMutableList()
        val changes = args.positional.drop(1)
        if (changes.isEmpty() && !args.has("clear")) throw CommandError("Give tags to add (+tag) or take off (-tag).", usage)
        for (word in changes) {
            val tag = word.removePrefix("+").removePrefix("-").trim()
            if (tag.isEmpty()) continue
            if (word.startsWith("-")) tags.removeAll { it.equals(tag, ignoreCase = true) }
            else if (tags.none { it.equals(tag, ignoreCase = true) }) tags += tag
        }
        workspace.setTags(meta.id, tags)
        return Done(JSONObject().put("id", meta.id).put("tags", JSONArray(tags)), "tags of \"${meta.title}\"") {
            workspace.setTags(meta.id, meta.tags)
        }
    }

    private fun trashNote(args: Args, usage: String): Done {
        val meta = filable(args, usage)
        if (workspace.isOpen(meta.id)) throw CommandError("\"${meta.title}\" is open on screen; a note being looked at is not put in the trash. " +
            "Ask the owner to close it.", code = "OPEN_ON_SCREEN")
        workspace.trash(meta.id)
        return Done(JSONObject().put("id", meta.id).put("trashed", true)
            .put("note", "In the trash: `notesis note restore` or undo brings it back."),
            "move \"${meta.title}\" to the trash") { workspace.restore(meta.id) }
    }

    private fun restoreNote(args: Args, usage: String): Done {
        val ref = args.positional.firstOrNull() ?: throw CommandError("Name the note.", usage)
        val meta = findNote(ref, workspace.trashed())
        workspace.restore(meta.id)
        return Done(JSONObject().put("id", meta.id).put("restored", true), "restore \"${meta.title}\"") {
            workspace.trash(meta.id)
        }
    }

    private fun addPages(args: Args, usage: String): Done = changePages(args, usage, "add pages") { document ->
        val pages = document.pages
        val after = args.text("after")?.let { pageIndex(it, pages.size) } ?: (pages.size - 1)
        val count = args.int("count", 1..MAX_ADDED_PAGES) ?: 1
        val like = pages.getOrNull(after) ?: pages.lastOrNull()
        val added = (1..count).map {
            Page(width = like?.width ?: Page.A4_WIDTH, height = like?.height ?: Page.A4_HEIGHT,
                background = like?.background ?: PageBackground.BLANK, templateId = like?.templateId)
        }
        pages.addAll(after + 1, added)
        document.invalidateLayout()
        JSONObject().put("added", JSONArray((after + 2..after + 1 + count).toList())).put("pages", pages.size)
    }

    private fun removePages(args: Args, usage: String): Done = changePages(args, usage, "remove pages") { document ->
        val pages = document.pages
        val raw = args.positional.drop(1).joinToString(",").ifBlank { throw CommandError("Give the pages to take out.", usage) }
        val gone = parsePageRange(raw, pages.size)
            ?: throw CommandError("\"$raw\" is not a page range of this note, which has pages 1 to ${pages.size}.", code = "BAD_PAGE")
        if (gone.size >= pages.size) throw CommandError("A note keeps at least one page; trash the note instead.")
        val ids = gone.map { pages[it].id }.toSet()
        pages.removeAll { it.id in ids }
        // A practice sheet whose page went stays, as a page of its own.
        for (page in pages) if (page.besideOf in ids) page.besideOf = null
        document.invalidateLayout()
        JSONObject().put("removed", JSONArray(gone.map { it + 1 })).put("pages", pages.size)
            .put("note", "Undo brings them back.")
    }

    private fun movePage(args: Args, usage: String): Done = changePages(args, usage, "move a page") { document ->
        val pages = document.pages
        val from = pageIndex(args.positional.getOrNull(1) ?: throw CommandError("Give the page and where it goes.", usage), pages.size)
        val to = pageIndex(args.positional.getOrNull(2) ?: throw CommandError("Give where the page goes.", usage), pages.size)
        pages.add(to, pages.removeAt(from))
        document.invalidateLayout()
        JSONObject().put("moved", from + 1).put("to", to + 1)
    }

    private fun setToc(args: Args, usage: String): Done = changePages(args, usage, "outline") { document ->
        val word = args.positional.getOrNull(1) ?: throw CommandError("Give the page.", usage)
        val index = if (!args.has("pdf")) pageIndex(word, document.pages.size) else {
            val pdfPage = word.toIntOrNull() ?: throw CommandError("\"$word\" is not a page number.", code = "BAD_PAGE")
            notePageOfPdf(document, pdfPage - 1) ?: throw CommandError("No page of the note shows PDF page $pdfPage.", code = "BAD_PAGE")
        }
        val title = args.positional.drop(2).joinToString(" ").trim().ifEmpty { throw CommandError("Give the entry's title.", usage) }
        val page = document.pages[index]
        page.tocTitle = title.take(MAX_TOC_TITLE)
        page.tocLevel = args.int("level", 0..MAX_TOC_LEVEL) ?: page.tocLevel.coerceIn(0, MAX_TOC_LEVEL)
        JSONObject().put("page", index + 1).put("title", page.tocTitle).put("level", page.tocLevel)
    }

    private fun removeToc(args: Args, usage: String): Done = changePages(args, usage, "outline") { document ->
        val index = pageIndex(args.positional.getOrNull(1) ?: throw CommandError("Give the page.", usage), document.pages.size)
        val page = document.pages[index]
        if (page.tocTitle == null) throw CommandError("Page ${index + 1} has no outline entry.")
        page.tocTitle = null; page.tocLevel = 0; page.tocHighlighted = false
        JSONObject().put("page", index + 1).put("removed", true)
    }

    private fun clearToc(args: Args, usage: String): Done = changePages(args, usage, "clear the outline") { document ->
        var cleared = 0
        for (page in document.pages) if (page.tocTitle != null) {
            page.tocTitle = null; page.tocLevel = 0; page.tocHighlighted = false
            cleared++
        }
        JSONObject().put("cleared", cleared)
    }

    private fun addText(args: Args, usage: String): Done = changePages(args, usage, "add text") { document ->
        val index = pageIndex(args.positional.getOrNull(1) ?: throw CommandError("Give the page.", usage), document.pages.size)
        val text = unescapeText(args.positional.drop(2).joinToString(" ")).trim()
        if (text.isEmpty()) throw CommandError("Give the text.", usage)
        if (text.length > MAX_TEXT) throw CommandError("A text box holds up to $MAX_TEXT characters.")
        val page = document.pages[index]
        val content = TextBoxContent(text, size = args.float("size", 8f..200f) ?: 32f,
            color = args.text("color")?.let(::commandColor) ?: 0xFF000000.toInt(),
            markdown = args.has("md"), wrapWidth = args.float("width", 40f..page.width) ?: 0f)
        val image = workspace.textBox(meta(args).id, content) ?: throw CommandError("The text box could not be made.")
        image.x = args.float("x", 0f..page.width) ?: TEXT_MARGIN
        image.y = args.float("y", 0f..page.height)
            ?: ((page.images.maxOfOrNull { it.y + it.height }?.plus(TEXT_GAP)) ?: TEXT_MARGIN)
                .coerceAtMost(page.height - image.height).coerceAtLeast(0f)
        page.images += image
        JSONObject().put("page", index + 1).put("x", image.x.clean()).put("y", image.y.clean())
            .put("width", image.width.clean()).put("height", image.height.clean())
    }

    private fun addInk(args: Args, usage: String): Done = changePages(args, usage, "add ink") { document ->
        val word = args.positional.getOrNull(1) ?: throw CommandError("Give the page, or board for off the pages.", usage)
        val board = isBoard(word)
        val index = if (board) -1 else pageIndex(word, document.pages.size)
        val lines = when {
            args.has("path") -> svgPolylines(args.text("path")!!)
            args.has("points") -> pointLines(args.text("points")!!)
            else -> throw CommandError("Give the ink as --path or --points.", usage)
        }
        if (lines.isEmpty()) throw CommandError("The path draws nothing.")
        val total = lines.sumOf { it.size }
        if (total > MAX_INK_POINTS) throw CommandError("That is $total points; one line takes up to $MAX_INK_POINTS.")
        if (board) {
            // Off the pages, in the note's own units: from page --near's corner when one is named, so "right of
            // page 3" is x past that page's width; else from the first page's corner.
            val (dx, dy) = boardOrigin(args, document)
            val placed = lines.map { line -> line.map { floatArrayOf(it[0] + dx, it[1] + dy) } }
            if (placed.any { line -> line.any { abs(it[0]) > MAX_BOARD_REACH || abs(it[1]) > MAX_BOARD_REACH } }) {
                throw CommandError("That is too far off the pages.")
            }
            val made = workspace.addInk(meta(args).id, document, document.board, placed,
                args.text("color")?.let(::commandColor) ?: 0xFF000000.toInt(), args.float("width", 0.5f..100f) ?: 4f)
            return@changePages JSONObject().put("page", "board").put("strokes", made)
                .put("note", "Off the pages shows when the note's whiteboard is on (note settings).")
        }
        val page = document.pages[index]
        val margin = INK_MARGIN
        if (lines.any { line -> line.any { it[0] !in -margin..page.width + margin || it[1] !in -margin..page.height + margin } }) {
            throw CommandError("The ink goes off the page, which is ${page.width.clean()} by ${page.height.clean()}. " +
                "To write beside the pages, use board for the page.")
        }
        val made = workspace.addInk(meta(args).id, document, page, lines,
            args.text("color")?.let(::commandColor) ?: 0xFF000000.toInt(), args.float("width", 0.5f..100f) ?: 4f)
        JSONObject().put("page", index + 1).put("strokes", made)
    }

    private fun eraseInk(args: Args, usage: String): Done = changePages(args, usage, "erase ink") { document ->
        val word = args.positional.getOrNull(1) ?: throw CommandError("Give the page, or board for off the pages.", usage)
        val board = isBoard(word)
        val index = if (board) -1 else pageIndex(word, document.pages.size)
        val area = when {
            args.has("all") -> null
            args.has("rect") -> args.text("rect")!!.split(',').map { it.trim().toFloatOrNull()?.takeIf(Float::isFinite) }.let { n ->
                if (n.size != 4 || n.any { it == null } || n[2]!! <= 0f || n[3]!! <= 0f) throw CommandError("--rect is X,Y,W,H.", usage)
                floatArrayOf(n[0]!!, n[1]!!, n[0]!! + n[2]!!, n[1]!! + n[3]!!)
            }
            else -> throw CommandError("Give --rect X,Y,W,H, or --all for the whole page.", usage)
        }
        if (board) {
            val (dx, dy) = boardOrigin(args, document)
            val moved = area?.let { floatArrayOf(it[0] + dx, it[1] + dy, it[2] + dx, it[3] + dy) }
            return@changePages JSONObject().put("page", "board").put("erased", workspace.eraseInk(meta(args).id, document, document.board, moved))
        }
        val removed = workspace.eraseInk(meta(args).id, document, document.pages[index], area)
        JSONObject().put("page", index + 1).put("erased", removed)
    }

    private fun importPdfToc(args: Args, usage: String): Done {
        val meta = inkNote(args, usage)
        val marks = workspace.pdfOutline(meta.id)
        if (marks.isEmpty()) throw CommandError("The PDF of \"${meta.title}\" has no bookmarks of its own. Read its contents page with " +
            "`pdf text`, then set each entry with `toc set NOTE PAGE TITLE --pdf`.", code = "NO_OUTLINE")
        return changePages(args, usage, "outline from the PDF") { document ->
            if (!args.has("keep")) for (page in document.pages) { page.tocTitle = null; page.tocLevel = 0; page.tocHighlighted = false }
            val set = HashSet<String>()
            var skipped = 0
            for (mark in marks) {
                val page = notePageOfPdf(document, mark.pdfPage)?.let { document.pages[it] }
                // A page holds one entry: the first bookmark on it, usually the one higher up, keeps it.
                if (page == null || page.id in set || mark.title.isBlank()) { skipped++; continue }
                page.tocTitle = mark.title.trim().take(MAX_TOC_TITLE)
                page.tocLevel = mark.level.coerceIn(0, MAX_TOC_LEVEL)
                set += page.id
            }
            JSONObject().put("set", set.size).put("skipped", skipped).put("bookmarks", marks.size)
        }
    }

    private fun newNote(args: Args, usage: String): Done {
        val title = args.positional.joinToString(" ").trim().ifEmpty { throw CommandError("Give the title.", usage) }
        val folder = args.text("folder")?.let { normalizeFolderPath(it) ?: "" } ?: ""
        val paper = args.text("paper")?.let { PAPER_NAMES[it.lowercase()] ?: throw CommandError("--paper is ${PAPER_NAMES.keys.joinToString(", ")}.", usage) }
            ?: PageBackground.BLANK
        val pages = args.int("pages", 1..MAX_ADDED_PAGES) ?: 1
        val id = workspace.createNote(title, folder, paper, pages) ?: throw CommandError("The note could not be made.")
        return Done(JSONObject().put("id", id).put("title", title).put("folder", folder).put("pages", pages),
            "make the note \"$title\"") { workspace.trash(id) }
    }

    private fun newMarkdown(args: Args, usage: String): Done {
        val title = args.positional.joinToString(" ").trim().ifEmpty { throw CommandError("Give the title.", usage) }
        val folder = args.text("folder")?.let { normalizeFolderPath(it) ?: "" } ?: ""
        val text = args.text("text")?.let(::unescapeText).orEmpty()
        if (text.length > MAX_MARKDOWN) throw CommandError("A Markdown note holds up to $MAX_MARKDOWN characters.")
        val id = workspace.createMarkdown(title, folder, text) ?: throw CommandError("The note could not be made.")
        return Done(JSONObject().put("id", id).put("title", title).put("folder", folder),
            "make the Markdown note \"$title\"") { workspace.trash(id) }
    }

    private fun duplicateNote(args: Args, usage: String): Done {
        val meta = note(args, usage)
        if (meta.locked) throw CommandError("\"${meta.title}\" is locked.", code = "LOCKED")
        val title = args.text("title")?.trim()?.ifEmpty { null } ?: "${meta.title} copy"
        val id = workspace.duplicateNote(meta.id, title) ?: throw CommandError("The note could not be copied.")
        return Done(JSONObject().put("id", id).put("title", title), "copy \"${meta.title}\" as \"$title\"") { workspace.trash(id) }
    }

    private fun mergeNotes(args: Args, usage: String): Done {
        if (args.positional.size < 2) throw CommandError("Name two notes or more.", usage)
        val notes = workspace.notes()
        val sources = args.positional.map { findNote(it, notes) }.distinctBy { it.id }
        if (sources.size < 2) throw CommandError("Name two different notes or more.", usage)
        sources.firstOrNull { it.kind != NoteKind.INK }?.let { throw CommandError("\"${it.title}\" is a Markdown note; only handwritten notes are joined.", code = "NOT_SUPPORTED") }
        sources.firstOrNull { it.locked }?.let { throw CommandError("\"${it.title}\" is locked.", code = "LOCKED") }
        val title = args.text("title")?.trim()?.ifEmpty { null } ?: throw CommandError("Give the new note's --title.", usage)
        val id = workspace.mergeNotes(sources.map { it.id }, title) ?: throw CommandError("The notes could not be joined.")
        return Done(JSONObject().put("id", id).put("title", title).put("from", JSONArray(sources.map { it.title }))
            .put("note", "The notes joined are left as they were."), "join ${sources.size} notes as \"$title\"") { workspace.trash(id) }
    }

    private fun favoriteNote(args: Args, usage: String): Done {
        val meta = filable(args, usage)
        val on = !args.has("off")
        workspace.setFavorite(meta.id, on)
        return Done(JSONObject().put("id", meta.id).put("favorite", on),
            if (on) "pin \"${meta.title}\" to the favourites" else "unpin \"${meta.title}\"") { workspace.setFavorite(meta.id, meta.favorite) }
    }

    private fun labelNote(args: Args, usage: String): Done {
        val meta = filable(args, usage)
        val raw = args.positional.getOrNull(1) ?: throw CommandError("Give the colour, or none.", usage)
        val color = if (raw.equals("none", ignoreCase = true)) 0 else commandColor(raw)
        workspace.setLabel(meta.id, color)
        return Done(JSONObject().put("id", meta.id).put("label", if (color == 0) "none" else "#%08X".format(color)),
            "label of \"${meta.title}\"") { workspace.setLabel(meta.id, meta.label) }
    }

    private fun listTags(): Done {
        val counts = workspace.notes().flatMap { it.tags }.groupingBy { it }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER)
        return Done(JSONObject().put("tags", JSONArray(counts.map { (tag, n) -> JSONObject().put("tag", tag).put("notes", n) })))
    }

    private fun listFolders(): Done {
        val notes = workspace.notes()
        return Done(JSONObject().put("folders", JSONArray(workspace.folders().sorted().map { folder ->
            JSONObject().put("path", folder).put("notes", notes.count { it.folder == folder })
        })).put("topLevel", notes.count { it.folder.isEmpty() }))
    }

    private fun newFolder(args: Args, usage: String): Done {
        val path = args.positional.joinToString(" ").let(::normalizeFolderPath)?.ifEmpty { null }
            ?: throw CommandError("Give the folder's path, such as School/Maths.", usage)
        if (path in workspace.folders()) throw CommandError("The folder \"$path\" is there already.")
        if (!workspace.createFolder(path)) throw CommandError("The folder could not be made.")
        return Done(JSONObject().put("folder", path), "make the folder \"$path\"") { workspace.removeEmptyFolder(path) }
    }

    private fun renameFolder(args: Args, usage: String): Done {
        val from = args.positional.getOrNull(0)?.let(::normalizeFolderPath)?.ifEmpty { null } ?: throw CommandError("Give the folder.", usage)
        val to = args.positional.getOrNull(1)?.let(::normalizeFolderPath)?.ifEmpty { null } ?: throw CommandError("Give the new path.", usage)
        val folders = workspace.folders()
        if (from !in folders) throw CommandError("There is no folder \"$from\".", code = "NOT_FOUND",
            candidates = folders.filter { noteKey(it).contains(noteKey(from.substringAfterLast('/'))) }.take(5))
        // A note on screen in it is fine: its saves read its folder back from disk rather than writing their own.
        if (!workspace.renameFolder(from, to)) throw CommandError("The folder could not be moved to \"$to\"; is there one already?")
        return Done(JSONObject().put("from", from).put("to", to), "move the folder \"$from\" to \"$to\"") { workspace.renameFolder(to, from) }
    }

    private fun duplicatePage(args: Args, usage: String): Done = changePages(args, usage, "copy a page") { document ->
        val id = meta(args).id
        val index = pageIndex(args.positional.getOrNull(1) ?: throw CommandError("Give the page.", usage), document.pages.size)
        val source = document.pages[index]
        val copy = Page(width = source.width, height = source.height, background = source.background,
            templateId = source.templateId, pdfPageIndex = source.pdfPageIndex)
        var left = 0
        for (image in source.images) {
            val newId = if (image.box == PageImage.BOX_CHECK) java.util.UUID.randomUUID().toString() else workspace.copyImage(id, image.id)
            if (newId == null) left++ else copy.images += image.copy(id = newId)
        }
        workspace.copyInk(id, document, source, copy)
        document.pages.add(index + 1, copy)
        document.invalidateLayout()
        JSONObject().put("copied", index + 1).put("as", index + 2).put("pages", document.pages.size)
            .also { if (left > 0) it.put("note", "$left picture(s) could not be copied.") }
    }

    private fun setPaper(args: Args, usage: String): Done = changePages(args, usage, "paper") { document ->
        val raw = args.positional.getOrNull(1) ?: throw CommandError("Give the pages and the paper.", usage)
        val name = args.positional.getOrNull(2) ?: throw CommandError("Give the paper: ${PAPER_NAMES.keys.joinToString(", ")}.", usage)
        val paper = PAPER_NAMES[name.lowercase()] ?: throw CommandError("The paper is ${PAPER_NAMES.keys.joinToString(", ")}.", usage)
        val indices = parsePageRange(raw, document.pages.size)
            ?: throw CommandError("\"$raw\" is not a page range of this note, which has pages 1 to ${document.pages.size}.", code = "BAD_PAGE")
        var changed = 0
        var left = 0
        for (index in indices) {
            val page = document.pages[index]
            // A PDF page is its PDF, and an endless page is sized for its own kind.
            if (page.pdfPageIndex >= 0 || page.background.isEndless) { left++; continue }
            page.background = paper
            page.templateId = null
            changed++
        }
        if (changed == 0) throw CommandError("Those pages are PDF or endless pages; their paper stays.", code = "NOT_SUPPORTED")
        JSONObject().put("changed", changed).put("paper", name.lowercase()).also { if (left > 0) it.put("left", left) }
    }

    private fun bookmarkPage(args: Args, usage: String): Done = changePages(args, usage, "bookmark") { document ->
        val index = pageIndex(args.positional.getOrNull(1) ?: throw CommandError("Give the page.", usage), document.pages.size)
        document.pages[index].bookmarked = !args.has("off")
        JSONObject().put("page", index + 1).put("bookmarked", document.pages[index].bookmarked)
    }

    private fun editText(args: Args, usage: String): Done = changePages(args, usage, "edit text") { document ->
        val (page, image) = textBoxAt(args, usage, document)
        val text = unescapeText(args.positional.drop(3).joinToString(" ")).trim()
        if (text.isEmpty()) throw CommandError("Give the new text; `text rm` takes a box away.", usage)
        if (text.length > MAX_TEXT) throw CommandError("A text box holds up to $MAX_TEXT characters.")
        val made = workspace.textBox(meta(args).id, image.textContent!!.copy(text = text)) ?: throw CommandError("The text box could not be made.")
        val placed = made.copy(x = image.x, y = image.y).also {
            it.locked = image.locked; it.group = image.group; it.opacity = image.opacity; it.rotation = image.rotation
        }
        page.images[page.images.indexOf(image)] = placed
        JSONObject().put("page", document.pages.indexOf(page) + 1).put("width", placed.width.clean()).put("height", placed.height.clean())
    }

    private fun removeText(args: Args, usage: String): Done = changePages(args, usage, "remove text") { document ->
        val (page, image) = textBoxAt(args, usage, document)
        page.images.remove(image)
        JSONObject().put("page", document.pages.indexOf(page) + 1).put("removed", snippetOf(image.textContent!!.text))
    }

    private fun writeMarkdown(args: Args, usage: String): Done = changeMarkdown(args, usage, "write") { _ ->
        unescapeText(args.positional.drop(1).joinToString(" "))
    }

    private fun appendMarkdown(args: Args, usage: String): Done = changeMarkdown(args, usage, "add to") { old ->
        val addition = unescapeText(args.positional.drop(1).joinToString(" ")).trim('\n')
        if (addition.isBlank()) throw CommandError("Give the text to add.", usage)
        val under = args.text("under")
        if (under == null) (if (old.isBlank()) "$addition\n" else old.trimEnd('\n') + blockGap(old.trimEnd('\n'), addition) + addition + "\n")
        else appendUnderHeading(old, under, addition) ?: throw CommandError("There is no heading \"$under\".", code = "NOT_FOUND",
            candidates = markdownHeadings(old).map { it.title }.take(8))
    }

    private fun replaceMarkdown(args: Args, usage: String): Done = changeMarkdown(args, usage, "replace text") { old ->
        val find = args.positional.getOrNull(1)?.let(::unescapeText)?.ifEmpty { null } ?: throw CommandError("Give the text to find.", usage)
        val with = args.positional.getOrNull(2)?.let(::unescapeText) ?: throw CommandError("Give what goes in its place.", usage)
        val times = old.windowed(find.length, 1).count { it == find }.takeIf { find.isNotEmpty() } ?: 0
        when {
            times == 0 -> throw CommandError("The note has no \"${snippetOf(find)}\".", code = "NOT_FOUND")
            times > 1 && !args.has("all") -> throw CommandError("\"${snippetOf(find)}\" is there $times times. Quote more of it, or add --all.",
                code = "AMBIGUOUS")
        }
        if (args.has("all")) old.replace(find, with) else old.replaceFirst(find, with)
    }

    // ---- shared --------------------------------------------------------------

    private fun meta(args: Args): NoteMeta = findNote(args.positional.first(), workspace.notes())

    private fun markdownNote(args: Args, usage: String): NoteMeta = note(args, usage).also {
        if (it.kind != NoteKind.MARKDOWN) throw CommandError("\"${it.title}\" is handwritten; md commands are for Markdown notes.", code = "NOT_SUPPORTED")
        if (it.locked) throw CommandError("\"${it.title}\" is locked.", code = "LOCKED")
    }

    /** A change to a Markdown note's text; undo puts the text it had back. */
    private fun changeMarkdown(args: Args, usage: String, label: String, change: (String) -> String): Done {
        val meta = changeable(args, usage)
        if (meta.kind != NoteKind.MARKDOWN) throw CommandError("\"${meta.title}\" is handwritten; md commands are for Markdown notes. " +
            "`text add` puts text on a page.", code = "NOT_SUPPORTED")
        val old = workspace.markdown(meta.id) ?: throw CommandError("The note's text could not be read.")
        val new = change(old)
        if (new.length > MAX_MARKDOWN) throw CommandError("A Markdown note holds up to $MAX_MARKDOWN characters.")
        if (new == old) return Done(JSONObject().put("id", meta.id).put("unchanged", true))
        workspace.saveMarkdown(meta.id, meta.title, new)
        return Done(JSONObject().put("id", meta.id).put("length", new.length), "$label \"${meta.title}\"") {
            workspace.saveMarkdown(meta.id, meta.title, old)
        }
    }

    /** The note's pages that have a PDF page under them, by index; an error when none has. */
    private fun pdfPages(meta: NoteMeta, document: Document): List<Int> =
        document.pages.indices.filter { document.pages[it].pdfPageIndex >= 0 }
            .ifEmpty { throw CommandError("\"${meta.title}\" has no PDF in it.", code = "NO_PDF") }

    private fun notePageOfPdf(document: Document, pdfPage: Int): Int? =
        document.pages.indexOfFirst { it.pdfPageIndex == pdfPage }.takeIf { it >= 0 }

    private fun textBoxes(page: Page): List<PageImage> = page.images.filter { it.textContent != null }

    private fun isBoard(word: String) = word.equals("board", ignoreCase = true)

    /** Where board coordinates start: page --near's top-left corner in the note, or the first page's. */
    private fun boardOrigin(args: Args, document: Document): Pair<Float, Float> {
        val near = args.text("near")?.let { pageIndex(it, document.pages.size) } ?: 0
        return document.leftOf(near) to document.topOf(near)
    }

    /** The page and text box a line names: NOTE PAGE BOX. */
    private fun textBoxAt(args: Args, usage: String, document: Document): Pair<Page, PageImage> {
        val index = pageIndex(args.positional.getOrNull(1) ?: throw CommandError("Give the page.", usage), document.pages.size)
        val page = document.pages[index]
        val boxes = textBoxes(page)
        if (boxes.isEmpty()) throw CommandError("Page ${index + 1} has no text boxes.", code = "NOT_FOUND")
        val raw = args.positional.getOrNull(2) ?: throw CommandError("Give the box's number from `text list`.", usage)
        val n = raw.toIntOrNull()?.takeIf { it in 1..boxes.size } ?: throw CommandError("Page ${index + 1} has text boxes 1 to ${boxes.size}.", code = "NOT_FOUND")
        return page to boxes[n - 1]
    }

    private fun note(args: Args, usage: String): NoteMeta {
        val ref = args.positional.firstOrNull() ?: throw CommandError("Name the note.", usage)
        return findNote(ref, workspace.notes())
    }

    private fun inkNote(args: Args, usage: String): NoteMeta = note(args, usage).also {
        if (it.kind != NoteKind.INK) throw CommandError("\"${it.title}\" is a Markdown note; it has no pages.")
        if (it.locked) throw CommandError("\"${it.title}\" is locked.")
    }

    /** A note whose content this line may change: not locked, and, when it is on screen, with its screen held (see [holdIfOpen]). */
    private fun changeable(args: Args, usage: String): NoteMeta = filable(args, usage).also(::holdIfOpen)

    /**
     * A note this line may file (folder, tags, favourite, label): not locked.
     * These live beside the note and its screen's saves read them back, so
     * one on screen needs no holding.
     */
    private fun filable(args: Args, usage: String): NoteMeta = note(args, usage).also {
        if (it.locked) throw CommandError("\"${it.title}\" is locked.", code = "LOCKED")
    }

    /**
     * When [meta] is on screen, holds its screen for the rest of the batch: it
     * writes what it shows and waits, so the change is made on what the owner
     * sees and is not written over; the screen reads the note again when the
     * batch ends. A screen that cannot be held refuses the change.
     */
    private fun holdIfOpen(meta: NoteMeta) {
        touched += meta.id
        if (meta.id in held || !workspace.isOpen(meta.id)) return
        if (!workspace.hold(meta.id)) throw CommandError("\"${meta.title}\" is on screen and could not take the change just now " +
            "(a stroke was being written). Try again in a moment.", code = "OPEN_ON_SCREEN")
        held += meta.id
    }

    /**
     * A change to a note's pages: what they were is kept first, the change is
     * made on the loaded note and saved, and undo puts the kept copy back.
     */
    private fun changePages(args: Args, usage: String, label: String, change: (Document) -> JSONObject): Done {
        val meta = changeable(args, usage)
        if (meta.kind != NoteKind.INK) throw CommandError("\"${meta.title}\" is a Markdown note; it has no pages.")
        val kept = workspace.keep(meta.id)
        val document = workspace.load(meta.id)
        val result = try {
            change(document).also { workspace.save(meta.id, meta.title, document) }
        } catch (error: Exception) {
            // Something may already be written (a stroke file, say): put it all back.
            runCatching { workspace.bring(meta.id, kept) }
            runCatching { workspace.release(kept) }
            throw error
        }
        return Done(result, "$label in \"${meta.title}\"", revert = { workspace.bring(meta.id, kept) }, release = { workspace.release(kept) })
    }

    companion object {
        const val UNDO_STEPS = 20
        /** How much a read gives at once, in characters, unless asked for more or less: about what an agent can take in without trouble. */
        const val READ_BUDGET = 12_000
        const val PDF_TEXT_BUDGET = 12_000
        const val MIN_BUDGET = 500
        const val MAX_BUDGET = 200_000
        const val LIST_LIMIT = 30
        const val MAX_LIST = 500
        const val SEARCH_LIMIT = 30
        const val MAX_SEARCH = 200
        const val MAX_FIND = 40
        const val SNIPPET = 60
        /** Pages read to tell whether a PDF has text and how its pages are numbered. */
        const val PDF_SAMPLE = 12
        /** PDF pages read at a time, so a long PDF is not all read for a short answer. */
        const val PDF_CHUNK = 16
        const val IMAGE_WIDTH = 800
        const val MAX_MARKDOWN = 500_000
        /** How far from the pages ink may be put on the board, in page units: far past any note, not past reason. */
        const val MAX_BOARD_REACH = 200_000f
        const val MAX_ADDED_PAGES = 50
        const val MAX_TOC_LEVEL = 5
        const val MAX_TOC_TITLE = 200
        const val MAX_TEXT = 4000
        const val MAX_INK_POINTS = 20_000
        const val TEXT_MARGIN = 80f
        const val TEXT_GAP = 24f
        /** Ink may start a little off the page, the way a hand does; not far. */
        const val INK_MARGIN = 40f
    }
}

/** How finely curves are cut, in page units per piece, and the most pieces one curve gets. */
private const val CURVE_STEP = 4f
private const val MAX_CURVE_STEPS = 64

/** Pages from 0 as they are written from 1, runs joined: [0, 1, 2, 5] is "1-3,6". */
internal fun pageList(indices: List<Int>): String {
    if (indices.isEmpty()) return ""
    val out = StringBuilder()
    var start = indices[0]
    var last = start
    fun flush() {
        if (out.isNotEmpty()) out.append(',')
        out.append(start + 1)
        if (last > start) out.append('-').append(last + 1)
    }
    for (index in indices.drop(1)) {
        if (index == last + 1) { last = index; continue }
        flush(); start = index; last = index
    }
    flush()
    return out.toString()
}

private val PAGE_NUMBER_LINE = Regex("^(?:[-–—]\\s*)?(?:page\\s+|p\\.\\s*)?(\\d{1,4})(?:\\s*[-–—])?(?:\\s*쪽)?$", RegexOption.IGNORE_CASE)

/**
 * How a PDF's printed page numbers sit against its own pages: the PDF page
 * (from 1) less the number printed on it, where most of the [pages] read
 * (PDF index from 0, its text) agree, or null when they do not. A book with
 * twelve pages of front matter prints 1 on PDF page 13: 12.
 */
internal fun printedPageOffset(pages: List<Pair<Int, String?>>): Int? {
    val votes = HashMap<Int, Int>()
    for ((pdfIndex, text) in pages) {
        val lines = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val ends = (lines.take(2) + lines.takeLast(2)).distinct()
        val number = ends.firstNotNullOfOrNull { PAGE_NUMBER_LINE.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() } ?: continue
        val offset = pdfIndex + 1 - number
        if (offset >= 0) votes[offset] = (votes[offset] ?: 0) + 1
    }
    val best = votes.maxByOrNull { it.value } ?: return null
    return best.key.takeIf { best.value >= 2 && best.value * 2 > votes.values.sum() }
}

/** A heading of a Markdown text: how deep, its words, its line from 1. */
internal data class MarkdownHeading(val level: Int, val title: String, val line: Int)

private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")

/** The headings of [text], code blocks left out. */
internal fun markdownHeadings(text: String): List<MarkdownHeading> {
    val out = mutableListOf<MarkdownHeading>()
    var fence = false
    for ((i, line) in text.lines().withIndex()) {
        if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) { fence = !fence; continue }
        if (fence) continue
        HEADING.matchEntire(line)?.let { out += MarkdownHeading(it.groupValues[1].length, it.groupValues[2], i + 1) }
    }
    return out
}

private fun headingsJson(text: String): JSONArray =
    JSONArray(markdownHeadings(text).map { JSONObject().put("level", it.level).put("title", it.title).put("line", it.line) })

/** The notes [text] links to as [[Title]], [[Title|shown]] or [[Title#part]], each once, in order. */
internal fun wikiLinks(text: String): List<String> =
    Regex("\\[\\[([^\\]|#\\n]+)(?:[#|][^\\]\\n]*)?]]").findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

/**
 * [text] with [addition] at the end of the section under the heading
 * [heading] (matched as titles are), before the next heading as high or
 * higher; null when there is no such heading.
 */
internal fun appendUnderHeading(text: String, heading: String, addition: String): String? {
    val headings = markdownHeadings(text)
    val key = noteKey(heading.trimStart('#'))
    val target = headings.firstOrNull { noteKey(it.title) == key } ?: return null
    val next = headings.firstOrNull { it.line > target.line && it.level <= target.level }
    val lines = text.lines().toMutableList()
    val sectionEnd = next?.line?.minus(1) ?: lines.size
    var end = sectionEnd
    // Into the section's last line of text, not after the blank lines before the next heading, which stay where they are.
    while (end > target.line && lines[end - 1].isBlank()) end--
    val gap = if (blockGap(lines.getOrElse(end - 1) { "" }, addition) == "\n") emptyList() else listOf("")
    val inserted = gap + addition.lines() + (if (next != null && end == sectionEnd) listOf("") else emptyList())
    lines.addAll(end, inserted)
    return lines.joinToString("\n").let { if (next == null && !it.endsWith("\n")) it + "\n" else it }
}

private val LIST_ITEM = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s")

/** What goes between [before] and [addition]: a line break when both are items of a list, so the list stays one; else a blank line. */
private fun blockGap(before: String, addition: String): String =
    if (LIST_ITEM.containsMatchIn(before.substringAfterLast('\n')) && LIST_ITEM.containsMatchIn(addition.substringBefore('\n'))) "\n" else "\n\n"

/** Text typed on one line: \n is a new line, \t a tab. */
internal fun unescapeText(text: String): String = text.replace("\\n", "\n").replace("\\t", "\t")

/** [text] as a command word: quoted when it holds a space or a quote. */
internal fun commandQuote(text: String): String =
    if (text.any { it.isWhitespace() || it == '"' || it == '\'' }) "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else text

private fun snippetOf(text: String, max: Int = 60): String {
    val one = text.replace('\n', ' ').trim()
    return if (one.length <= max) one else one.take(max - 1) + "…"
}

/**
 * Each of [ids] cut to the shortest start that no other one shares, and no
 * shorter than [min]: "3f9c2a71" for a 36-letter id. Any command takes a start
 * of an id of four letters or more, so the short one names the note as well.
 */
internal fun shortIds(ids: List<String>, min: Int = 8): Map<String, String> {
    val sorted = ids.distinct().sorted()
    fun shared(a: String, b: String): Int { var n = 0; while (n < a.length && n < b.length && a[n] == b[n]) n++; return n }
    return sorted.withIndex().associate { (i, id) ->
        val need = maxOf(sorted.getOrNull(i - 1)?.let { shared(it, id) } ?: 0, sorted.getOrNull(i + 1)?.let { shared(it, id) } ?: 0) + 1
        id to id.take(maxOf(min, need).coerceAtMost(id.length))
    }
}
