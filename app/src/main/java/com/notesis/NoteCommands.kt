package com.notesis

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.hypot

/*
 * The note commands (Todo.md, C1 and C2): `notesis notes list`, `note read`,
 * `page add` and the rest, each a line of words in and a JSON answer out, so
 * that a terminal, or an agent working in one, can read and change notes the
 * way the screens do.
 *
 * They work on notes as they are stored, never on one open on screen: the
 * screen keeps its own copy and would write over the change, so a note that
 * is open is only read. Every change keeps what it replaced, and `undo` puts
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
    /** What the handwriting on [page] was read as for search, if it has been. */
    fun handwriting(id: String, page: Page): String?
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
}

internal enum class ExportKind { PDF, PNG, MD }

/** A command that could not be done, and how it is written. */
internal class CommandError(message: String, val usage: String? = null) : Exception(message)

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
            c == '\\' && quote != '\'' && i + 1 < line.length -> { word.append(line[i + 1]); inWord = true; i++ }
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

/**
 * The note [ref] names among [notes]: its id, its title (any case), the
 * start of its id, or a part of its title - the first of these that names
 * exactly one note.
 */
internal fun findNote(ref: String, notes: List<NoteMeta>): NoteMeta {
    val wanted = ref.trim()
    if (wanted.isEmpty()) throw CommandError("Name a note by its id or its title.")
    notes.firstOrNull { it.id == wanted }?.let { return it }
    val tries = listOf<(NoteMeta) -> Boolean>(
        { it.title.equals(wanted, ignoreCase = true) },
        { wanted.length >= 4 && it.id.startsWith(wanted) },
        { it.title.contains(wanted, ignoreCase = true) },
    )
    for (test in tries) {
        val found = notes.filter(test)
        if (found.size == 1) return found[0]
        if (found.size > 1) throw CommandError("\"$ref\" names ${found.size} notes: " +
            found.take(5).joinToString("; ") { "${it.id} \"${it.title}\"" } + ". Use the id.")
    }
    throw CommandError("No note is called \"$ref\". `notesis notes list` shows them.")
}

/** A page number from 1, or "last", as an index from 0. */
internal fun pageIndex(word: String, count: Int): Int {
    if (word.equals("last", ignoreCase = true)) return count - 1
    val number = word.toIntOrNull() ?: throw CommandError("\"$word\" is not a page number.")
    if (number !in 1..count) throw CommandError("The note has pages 1 to $count.")
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
    "notes list" to "notes list [--folder PATH] [--tag TAG] [--search WORDS] [--trash]",
    "note read" to "note read NOTE [--pages 1-3,5]",
    "note rename" to "note rename NOTE TITLE",
    "note mv" to "note mv NOTE FOLDER   (\"/\" for the top level)",
    "note tag" to "note tag NOTE [+TAG|TAG] [-TAG] [--clear]",
    "note trash" to "note trash NOTE",
    "note restore" to "note restore NOTE",
    "page add" to "page add NOTE [--after PAGE] [--count N]",
    "page rm" to "page rm NOTE PAGES   (such as 3 or 2-4)",
    "page mv" to "page mv NOTE PAGE TO",
    "toc show" to "toc show NOTE",
    "toc set" to "toc set NOTE PAGE TITLE [--level 0-5]",
    "toc rm" to "toc rm NOTE PAGE",
    "toc clear" to "toc clear NOTE",
    "text add" to "text add NOTE PAGE TEXT [--x X] [--y Y] [--size PT] [--color #RRGGBB] [--width W] [--md]",
    "ink add" to "ink add NOTE PAGE (--path \"M 10 10 L 200 80\" | --points \"x,y x,y; x,y ...\") [--color #RRGGBB] [--width W]",
    "ink erase" to "ink erase NOTE PAGE (--rect X,Y,W,H | --all)",
    "export" to "export NOTE [--format pdf|png|md] [--pages 1-3] [--no-ink]",
    "undo" to "undo",
    "help" to "help [COMMAND]",
)

/**
 * Runs note commands on [workspace] and keeps what each change replaced, so
 * `undo` can put it back. One of these lives as long as the terminal it
 * serves; undo does not outlast it.
 */
internal class NoteCommands(private val workspace: NoteWorkspace) {
    private class Step(val id: Long, val label: String, val reverts: List<() -> Unit>, val releases: List<() -> Unit>)
    private class Done(
        val result: Any, val label: String? = null,
        /** Lets go of what [revert] kept, once it can no longer be called. */
        val release: (() -> Unit)? = null,
        /** Last, so a trailing lambda at a call is the way to take the change back. */
        val revert: (() -> Unit)? = null,
    )

    private val history = ArrayDeque<Step>()
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
                changes.mapNotNull { it.release }))
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
        error.usage?.let { put("usage", it) }
        if (of > 1) {
            put("line", line)
            if (undone > 0) put("undone", "The $undone change(s) before it were taken back.")
        }
    }.toString()

    private fun execute(words: List<String>): Done {
        if (words.isEmpty()) throw CommandError("Nothing to do. `notesis help` lists the commands.")
        val single = setOf("undo", "help", "export")
        val name = if (words[0] in single || words.size < 2) words[0] else "${words[0]} ${words[1]}"
        val rest = words.drop(if (words[0] in single || words.size < 2) 1 else 2)
        val usage = COMMAND_USAGE[name] ?: throw CommandError("There is no command \"${words.joinToString(" ").take(40)}\".",
            COMMAND_USAGE.values.joinToString("\n"))
        return when (name) {
            "help" -> help(rest)
            "undo" -> undo()
            "notes list" -> listNotes(Args.of(rest, setOf("folder", "tag", "search"), setOf("trash"), usage))
            "note read" -> readNote(Args.of(rest, setOf("pages"), emptySet(), usage), usage)
            "note rename" -> renameNote(Args.of(rest, emptySet(), emptySet(), usage), usage)
            "note mv" -> moveNote(Args.of(rest, emptySet(), emptySet(), usage), usage)
            "note tag" -> tagNote(Args.of(rest, emptySet(), setOf("clear"), usage), usage)
            "note trash" -> trashNote(Args.of(rest, emptySet(), emptySet(), usage), usage)
            "note restore" -> restoreNote(Args.of(rest, emptySet(), emptySet(), usage), usage)
            "page add" -> addPages(Args.of(rest, setOf("after", "count"), emptySet(), usage), usage)
            "page rm" -> removePages(Args.of(rest, emptySet(), emptySet(), usage), usage)
            "page mv" -> movePage(Args.of(rest, emptySet(), emptySet(), usage), usage)
            "toc show" -> showToc(Args.of(rest, emptySet(), emptySet(), usage), usage)
            "toc set" -> setToc(Args.of(rest, setOf("level"), emptySet(), usage), usage)
            "toc rm" -> removeToc(Args.of(rest, emptySet(), emptySet(), usage), usage)
            "toc clear" -> clearToc(Args.of(rest, emptySet(), emptySet(), usage), usage)
            "text add" -> addText(Args.of(rest, setOf("x", "y", "size", "color", "width"), setOf("md"), usage), usage)
            "ink add" -> addInk(Args.of(rest, setOf("path", "points", "color", "width"), emptySet(), usage), usage)
            "ink erase" -> eraseInk(Args.of(rest, setOf("rect"), setOf("all"), usage), usage)
            "export" -> export(Args.of(rest, setOf("format", "pages"), setOf("no-ink"), usage), usage)
            else -> throw CommandError("There is no command \"$name\".")
        }
    }

    // ---- reading -------------------------------------------------------------

    private fun help(rest: List<String>): Done {
        val wanted = rest.joinToString(" ")
        val shown = if (wanted.isBlank()) COMMAND_USAGE.values.toList()
            else COMMAND_USAGE.filterKeys { it.startsWith(wanted) }.values.toList().ifEmpty {
                throw CommandError("There is no command \"$wanted\".")
            }
        return Done(JSONObject().apply {
            put("commands", JSONArray(shown))
            put("notes", "NOTE is a note's id or title. PAGE is a page number from 1, or last. " +
                "Lines change stored notes only: a note open on screen is read, not changed. " +
                "`undo` takes back the last change.")
        })
    }

    private fun listNotes(args: Args): Done {
        val folder = args.text("folder")?.let { normalizeFolderPath(it) ?: "" }
        val tag = args.text("tag")
        val search = args.text("search")
        val notes = (if (args.has("trash")) workspace.trashed() else workspace.notes()).filter { meta ->
            (folder == null || meta.folder == folder || meta.folder.startsWith("$folder/")) &&
                (tag == null || meta.tags.any { it.equals(tag, ignoreCase = true) }) &&
                (search == null || meta.title.contains(search, ignoreCase = true))
        }
        return Done(JSONObject().apply {
            put("count", notes.size)
            put("notes", JSONArray(notes.map(::noteJson)))
        })
    }

    private fun noteJson(meta: NoteMeta): JSONObject = JSONObject().apply {
        put("id", meta.id)
        put("title", meta.title)
        put("kind", meta.kind.name.lowercase())
        put("folder", meta.folder)
        put("tags", JSONArray(meta.tags))
        if (meta.kind == NoteKind.INK) put("pages", meta.pageCount)
        put("modified", meta.modified)
        if (meta.favorite) put("favorite", true)
        if (meta.locked) put("locked", true)
    }

    private fun readNote(args: Args, usage: String): Done {
        val meta = note(args, usage)
        if (meta.locked) throw CommandError("\"${meta.title}\" is locked. Open it in the app with the password.")
        val json = noteJson(meta)
        if (meta.kind == NoteKind.MARKDOWN) {
            json.put("text", workspace.markdown(meta.id) ?: "")
            return Done(json)
        }
        val document = workspace.load(meta.id)
        val indices = args.text("pages")?.let { parsePageRange(it, document.pages.size)
            ?: throw CommandError("\"$it\" is not a page range of this note, which has pages 1 to ${document.pages.size}.") }
            ?: document.pages.indices.toList()
        json.put("pages", JSONArray(indices.map { index ->
            val page = document.pages[index]
            JSONObject().apply {
                put("page", index + 1)
                put("id", page.id)
                put("size", JSONArray(listOf(page.width.clean(), page.height.clean())))
                page.tocTitle?.let { put("toc", JSONObject().put("title", it).put("level", page.tocLevel)) }
                if (page.bookmarked) put("bookmarked", true)
                if (page.pdfPageIndex >= 0) put("pdfPage", page.pdfPageIndex + 1)
                page.besideOf?.let { host -> document.pages.indexOfFirst { it.id == host }.takeIf { it >= 0 }?.let { put("practiceFor", it + 1) } }
                val typed = page.images.mapNotNull { image -> image.textContent?.let { image to it } }
                if (typed.isNotEmpty()) put("text", JSONArray(typed.map { (image, content) ->
                    JSONObject().put("text", content.text).put("x", image.x.clean()).put("y", image.y.clean())
                }))
                val pictures = page.images.count { it.textContent == null }
                if (pictures > 0) put("pictures", pictures)
                put("ink", workspace.inkCount(meta.id, page))
                workspace.handwriting(meta.id, page)?.trim()?.takeIf { it.isNotEmpty() }?.let { put("handwriting", it) }
            }
        }))
        return Done(json)
    }

    private fun showToc(args: Args, usage: String): Done {
        val meta = inkNote(args, usage)
        val document = workspace.load(meta.id)
        return Done(JSONObject().apply {
            put("note", meta.title)
            put("toc", JSONArray(document.pages.withIndex().filter { it.value.tocTitle != null }.map { (index, page) ->
                JSONObject().put("page", index + 1).put("title", page.tocTitle).put("level", page.tocLevel)
                    .also { if (page.tocHighlighted) it.put("highlighted", true) }
            }))
        })
    }

    private fun export(args: Args, usage: String): Done {
        val meta = note(args, usage)
        if (meta.locked) throw CommandError("\"${meta.title}\" is locked.")
        val format = args.text("format")?.let { raw ->
            ExportKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw CommandError("--format is pdf, png or md.", usage)
        } ?: ExportKind.PDF
        if (meta.kind == NoteKind.MARKDOWN && format != ExportKind.MD) throw CommandError("A Markdown note goes out as md.")
        val count = if (meta.kind == NoteKind.INK) workspace.load(meta.id).pages.size else 0
        val pages = args.text("pages")?.let { parsePageRange(it, count)
            ?: throw CommandError("\"$it\" is not a page range of this note, which has pages 1 to $count.") }
            ?: (0 until count).toList()
        if (format == ExportKind.PNG && args.has("no-ink")) throw CommandError("Pictures are of the page as it looks; --no-ink is for pdf.")
        if (format == ExportKind.PNG && pages.size > 1 && pages.zipWithNext().any { (a, b) -> b != a + 1 }) {
            throw CommandError("Pictures are of pages in a row, such as 2-4.")
        }
        val path = workspace.export(meta.id, meta.title, format, pages, !args.has("no-ink"))
            ?: throw CommandError("The note could not be written out.")
        return Done(JSONObject().put("file", path).put("format", format.name.lowercase()).put("pages", pages.size))
    }

    // ---- changing ------------------------------------------------------------

    private fun undo(): Done {
        val step = history.removeLastOrNull() ?: throw CommandError("There is nothing to undo.")
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
        val meta = changeable(args, usage)
        val raw = args.positional.getOrNull(1) ?: throw CommandError("Give the folder, or / for the top level.", usage)
        val folder = normalizeFolderPath(raw) ?: ""
        workspace.setFolder(meta.id, folder)
        return Done(JSONObject().put("id", meta.id).put("folder", folder),
            "move \"${meta.title}\" to ${folder.ifEmpty { "the top level" }}") { workspace.setFolder(meta.id, meta.folder) }
    }

    private fun tagNote(args: Args, usage: String): Done {
        val meta = changeable(args, usage)
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
        val meta = changeable(args, usage)
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
            ?: throw CommandError("\"$raw\" is not a page range of this note, which has pages 1 to ${pages.size}.")
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
        val index = pageIndex(args.positional.getOrNull(1) ?: throw CommandError("Give the page.", usage), document.pages.size)
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
        val text = args.positional.drop(2).joinToString(" ").replace("\\n", "\n").trim()
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
        val index = pageIndex(args.positional.getOrNull(1) ?: throw CommandError("Give the page.", usage), document.pages.size)
        val lines = when {
            args.has("path") -> svgPolylines(args.text("path")!!)
            args.has("points") -> pointLines(args.text("points")!!)
            else -> throw CommandError("Give the ink as --path or --points.", usage)
        }
        if (lines.isEmpty()) throw CommandError("The path draws nothing.")
        val total = lines.sumOf { it.size }
        if (total > MAX_INK_POINTS) throw CommandError("That is $total points; one line takes up to $MAX_INK_POINTS.")
        val page = document.pages[index]
        val margin = INK_MARGIN
        if (lines.any { line -> line.any { it[0] !in -margin..page.width + margin || it[1] !in -margin..page.height + margin } }) {
            throw CommandError("The ink goes off the page, which is ${page.width.clean()} by ${page.height.clean()}.")
        }
        val made = workspace.addInk(meta(args).id, document, page, lines,
            args.text("color")?.let(::commandColor) ?: 0xFF000000.toInt(), args.float("width", 0.5f..100f) ?: 4f)
        JSONObject().put("page", index + 1).put("strokes", made)
    }

    private fun eraseInk(args: Args, usage: String): Done = changePages(args, usage, "erase ink") { document ->
        val index = pageIndex(args.positional.getOrNull(1) ?: throw CommandError("Give the page.", usage), document.pages.size)
        val area = when {
            args.has("all") -> null
            args.has("rect") -> args.text("rect")!!.split(',').map { it.trim().toFloatOrNull()?.takeIf(Float::isFinite) }.let { n ->
                if (n.size != 4 || n.any { it == null } || n[2]!! <= 0f || n[3]!! <= 0f) throw CommandError("--rect is X,Y,W,H.", usage)
                floatArrayOf(n[0]!!, n[1]!!, n[0]!! + n[2]!!, n[1]!! + n[3]!!)
            }
            else -> throw CommandError("Give --rect X,Y,W,H, or --all for the whole page.", usage)
        }
        val removed = workspace.eraseInk(meta(args).id, document, document.pages[index], area)
        JSONObject().put("page", index + 1).put("erased", removed)
    }

    // ---- shared --------------------------------------------------------------

    private fun meta(args: Args): NoteMeta = findNote(args.positional.first(), workspace.notes())

    private fun note(args: Args, usage: String): NoteMeta {
        val ref = args.positional.firstOrNull() ?: throw CommandError("Name the note.", usage)
        return findNote(ref, workspace.notes())
    }

    private fun inkNote(args: Args, usage: String): NoteMeta = note(args, usage).also {
        if (it.kind != NoteKind.INK) throw CommandError("\"${it.title}\" is a Markdown note; it has no pages.")
        if (it.locked) throw CommandError("\"${it.title}\" is locked.")
    }

    /** A note this line may change: not locked, and not open on screen. */
    private fun changeable(args: Args, usage: String): NoteMeta = note(args, usage).also {
        if (it.locked) throw CommandError("\"${it.title}\" is locked.")
        if (workspace.isOpen(it.id)) throw CommandError("\"${it.title}\" is open on screen. Close it first; it would write over the change.")
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
