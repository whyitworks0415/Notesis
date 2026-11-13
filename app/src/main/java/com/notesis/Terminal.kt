package com.notesis

import org.json.JSONArray
import org.json.JSONObject

/*
 * The terminal's rules (Todo.md, T1), without Android: what Tab completes,
 * how the lines typed before are walked with up and down, which words the
 * terminal answers itself, and how a command's JSON answer is printed. The
 * panel is in TerminalPanel.kt; the commands themselves in NoteCommands.kt.
 */

/** What the terminal answers itself, before anything reaches the note commands. */
internal enum class TerminalBuiltin(val word: String, val about: String) {
    CLEAR("clear", "clear the screen"),
    HISTORY("history", "the lines run before"),
    JSON("json", "answers as JSON, or as text again"),
    EXIT("exit", "close the terminal"),
}

/** The built-in a line asks for, or null for a note command. */
internal fun terminalBuiltin(line: String): TerminalBuiltin? {
    val word = line.trim()
    return TerminalBuiltin.entries.firstOrNull { it.word == word }
}

/** The command lines in what was typed or pasted: one per line, blanks and comments left out. */
internal fun terminalLines(text: String): List<String> =
    text.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

/** What Tab made of the line: the line and its cursor, and the choices left when it could not pick one. */
internal data class Completion(val line: String, val cursor: Int, val options: List<String>)

/** The commands' first words, and for each the words that may follow it. */
private val COMMAND_WORDS: Map<String, List<String>> =
    COMMAND_USAGE.keys.map { it.split(' ') }.groupBy({ it[0] }, { it.getOrNull(1) })
        .mapValues { (_, seconds) -> seconds.filterNotNull().distinct() }

/**
 * Completes the word before [cursor]: a command, the word after it, an
 * --option of that command, or the name of a note (quoted when it has a
 * space). One choice is filled in with a space after it; several are filled
 * in as far as they agree, and listed.
 */
internal fun completeLine(line: String, cursor: Int, noteNames: List<String>): Completion {
    val at = cursor.coerceIn(0, line.length)
    val before = line.substring(0, at)
    // The word being typed starts after the last space outside quotes, or at its opening quote.
    var start = 0
    var quote: Char? = null
    for ((i, c) in before.withIndex()) {
        when {
            quote != null -> if (c == quote) quote = null
            c == '"' || c == '\'' -> { quote = c; start = i }
            c == ' ' -> start = i + 1
        }
    }
    val partial = before.substring(start).trimStart('"', '\'')
    val words = (commandWords(before.substring(0, start)) ?: emptyList()).let { if (it.firstOrNull() == "notesis") it.drop(1) else it }
    val candidates: List<String> = when {
        words.isEmpty() -> (COMMAND_WORDS.keys + TerminalBuiltin.entries.map { it.word }).sorted()
        words.size == 1 && COMMAND_WORDS[words[0]].orEmpty().isNotEmpty() -> COMMAND_WORDS.getValue(words[0])
        partial.startsWith("--") -> {
            val usage = COMMAND_USAGE[words.take(2).joinToString(" ")] ?: COMMAND_USAGE[words[0]] ?: ""
            Regex("--[a-z-]+").findAll(usage).map { it.value }.distinct().toList()
        }
        words[0] == "help" -> COMMAND_WORDS.keys.sorted()
        // The note is the first word after the command.
        else -> {
            val command = if (COMMAND_WORDS[words[0]].orEmpty().isNotEmpty()) 2 else 1
            val positional = words.drop(command).filterNot { it.startsWith("--") }
            if (positional.isEmpty() && words[0] != "undo") noteNames else emptyList()
        }
    }
    val matching = candidates.filter { it.startsWith(partial, ignoreCase = true) }.distinct()
    if (matching.isEmpty()) return Completion(line, at, emptyList())
    fun written(word: String) = if (word.any { it == ' ' || it == '"' }) "\"" + word.replace("\"", "\\\"") + "\"" else word
    if (matching.size == 1) {
        val word = written(matching[0]) + " "
        val next = line.substring(0, start) + word + line.substring(at).trimStart()
        return Completion(next, start + word.length, emptyList())
    }
    // As far as they all agree; a name with a space keeps its opening quote.
    var common = matching[0]
    for (m in matching.drop(1)) {
        var n = 0
        while (n < common.length && n < m.length && common[n].equals(m[n], ignoreCase = true)) n++
        common = common.substring(0, n)
    }
    if (common.length <= partial.length) return Completion(line, at, matching)
    // A quote opens only once the part they share has a space in it.
    val shown = if (common.contains(' ')) "\"" + common else common
    return Completion(line.substring(0, start) + shown + line.substring(at), start + shown.length, matching)
}

/**
 * The lines run before, newest last, walked with up and down as a shell
 * does: up from the line being written keeps it, and down past the newest
 * brings it back.
 */
internal class TerminalHistory(private val limit: Int = 200) {
    private val lines = ArrayList<String>()
    private var at = -1
    private var draft = ""

    val all: List<String> get() = lines

    /** Puts back lines kept from before, oldest first, under any typed since. */
    fun restore(saved: List<String>) {
        val typed = lines.toList()
        lines.clear()
        (saved + typed).map { it.trim() }.filter { it.isNotEmpty() }.forEach { if (lines.lastOrNull() != it) lines += it }
        while (lines.size > limit) lines.removeAt(0)
        at = -1
    }

    fun add(line: String) {
        val clean = line.trim()
        if (clean.isEmpty()) return
        if (lines.lastOrNull() != clean) lines += clean
        while (lines.size > limit) lines.removeAt(0)
        at = -1
    }

    /** The line before the one shown; [current] is kept as the draft when leaving it. */
    fun up(current: String): String? {
        if (lines.isEmpty()) return null
        if (at == -1) { draft = current; at = lines.size }
        if (at == 0) return lines[0]
        at--
        return lines[at]
    }

    fun down(): String? {
        if (at == -1) return null
        at++
        if (at >= lines.size) { at = -1; return draft }
        return lines[at]
    }
}

/** How a printed line reads: a command typed, an answer, a failure, or a hint. */
internal enum class TerminalTone { INPUT, OUTPUT, ERROR, HINT }

internal data class TerminalLine(val text: String, val tone: TerminalTone)

/**
 * A command's JSON answer as the terminal prints it: what it found or did,
 * indented, with the undo hint and a failure's usage on lines of their own.
 */
internal fun terminalOutput(answer: String, asJson: Boolean = false): List<TerminalLine> {
    val json = runCatching { JSONObject(answer) }.getOrNull() ?: return listOf(TerminalLine(answer, TerminalTone.OUTPUT))
    if (!json.optBoolean("ok")) {
        val out = mutableListOf(TerminalLine("error: " + json.optString("error"), TerminalTone.ERROR))
        if (json.has("line")) out += TerminalLine("on line ${json.optInt("line")}" +
            (json.optString("undone").takeIf { it.isNotEmpty() }?.let { ". $it" } ?: ""), TerminalTone.ERROR)
        json.optJSONArray("candidates")?.let { candidates ->
            out += TerminalLine("did you mean:", TerminalTone.HINT)
            for (i in 0 until candidates.length()) out += TerminalLine("  " + candidates.optString(i), TerminalTone.HINT)
        }
        json.optString("usage").takeIf { it.isNotEmpty() }?.let { usage ->
            out += usage.split('\n').map { TerminalLine("  $it", TerminalTone.HINT) }
        }
        return out
    }
    val body = withoutPictureData(json.opt("result") ?: json.opt("results"))
    val out = mutableListOf(TerminalLine(if (asJson) prettyJson(body) else readable(body), TerminalTone.OUTPUT))
    json.optString("undo").takeIf { it.isNotEmpty() }?.let { out += TerminalLine(it, TerminalTone.HINT) }
    return out
}

/** [value] as indented JSON, slashes left as they are written. */
internal fun prettyJson(value: Any?): String = when (value) {
    is JSONObject -> value.toString(2)
    is JSONArray -> value.toString(2)
    null -> "null"
    else -> value.toString()
}.replace("\\/", "/")

/** Whether an answer changed notes, so the library should read them again. */
internal fun terminalChangedNotes(answer: String): Boolean = runCatching {
    val json = JSONObject(answer)
    json.optBoolean("ok") && (json.has("undo") || json.optJSONObject("result")?.has("undone") == true)
}.getOrDefault(false)

/** How much of a long answer is shown; the rest is said to be there. */
internal fun clipLines(text: String, max: Int): String {
    val lines = text.split('\n')
    if (lines.size <= max) return text
    return lines.take(max).joinToString("\n") + "\n… {0} more lines".replace("{0}", (lines.size - max).toString())
}

/** [value] with each picture's data put as its size: a page as base64 is a screenful of letters no one reads. */
internal fun withoutPictureData(value: Any?): Any? = when (value) {
    is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(withoutPictureData(value.opt(i))) }
    is JSONObject -> JSONObject().also { out ->
        for (key in value.keys()) {
            val item = value.opt(key)
            val data = (item as? JSONObject)?.optString("data").orEmpty()
            if (key == "image" && data.isNotEmpty()) out.put(key, "picture, ${data.length * 3 / 4 / 1024} KB ${(item as JSONObject).optString("mime")}")
            else out.put(key, withoutPictureData(item))
        }
    }
    else -> value
}

/**
 * An answer as text for a person: notes, search hits and outlines as lines,
 * a page's text as text, anything else as `key: value` lines, and what has
 * no plainer form as indented JSON. `json` in the terminal shows the JSON.
 */
internal fun readable(value: Any?): String = when (value) {
    is JSONArray -> (0 until value.length()).joinToString("\n\n") { readable(value.opt(it)) }
    is JSONObject -> readableObject(value)
    null -> ""
    else -> value.toString()
}

private fun readableObject(json: JSONObject): String {
    val lines = mutableListOf<String>()
    fun rest(vararg shown: String) {
        for (key in json.keys()) if (key !in shown) json.opt(key)?.let { lines += "$key: ${flat(it)}" }
    }
    val notes = json.optJSONArray("notes")
    val hits = json.optJSONArray("hits")
    val toc = json.optJSONArray("toc") ?: json.optJSONArray("pdfBookmarks")
    val commands = json.optJSONArray("commands")
    when {
        notes != null -> {
            for (i in 0 until notes.length()) notes.optJSONObject(i)?.let { note ->
                val about = listOfNotNull(
                    note.optString("folder").takeIf { it.isNotEmpty() },
                    note.optJSONArray("tags")?.let { tags -> (0 until tags.length()).joinToString(" ") { "#" + tags.optString(it) } }?.takeIf { it.isNotEmpty() },
                    if (note.has("pages")) "${note.optInt("pages")} p" else note.optString("kind").takeIf { it == "markdown" }?.let { "md" },
                ).joinToString("  ")
                lines += "${note.optString("id").take(8)}  ${note.optString("title")}" + (if (about.isNotEmpty()) "   $about" else "")
            }
            lines += buildString {
                append(notes.length()).append(" of ").append(json.optInt("total", json.optInt("count", notes.length())))
                json.optString("next").takeIf { it.isNotEmpty() }?.let { append("   more: ").append(it) }
            }
        }
        hits != null -> {
            for (i in 0 until hits.length()) hits.optJSONObject(i)?.let { hit ->
                val where = listOfNotNull(hit.optString("title").takeIf { it.isNotEmpty() }, if (hit.has("page")) "p.${hit.optInt("page")}" else null)
                lines += where.joinToString(" ") + (hit.optString("snippet").takeIf { it.isNotEmpty() }?.let { ":  $it" } ?: "")
            }
            lines += "${hits.length()} found" + (json.optString("more").takeIf { it.isNotEmpty() && it != "false" }?.let { "   $it" } ?: "")
        }
        toc != null -> {
            json.optString("note").takeIf { it.isNotEmpty() }?.let { lines += it }
            for (i in 0 until toc.length()) toc.optJSONObject(i)?.let { entry ->
                val page = if (entry.has("page")) "p.${entry.optInt("page")}" else "pdf ${entry.optInt("pdfPage")}"
                lines += page.padEnd(8) + "  ".repeat(entry.optInt("level")) + entry.optString("title")
            }
            if (toc.length() == 0) lines += "(no entries)"
        }
        commands != null -> {
            for (i in 0 until commands.length()) lines += commands.optString(i)
            json.optString("notes").takeIf { it.isNotEmpty() }?.let { lines += ""; lines += it }
        }
        json.has("chars") && json.optJSONArray("pages") != null -> {
            val pages = json.getJSONArray("pages")
            for (i in 0 until pages.length()) pages.optJSONObject(i)?.let { page ->
                lines += "--- p.${page.optInt("page")} (pdf ${page.optInt("pdfPage")}) ---"
                lines += page.optString("text")
            }
            rest("pages", "chars", "note")
        }
        json.optJSONArray("folders") != null -> {
            val folders = json.getJSONArray("folders")
            for (i in 0 until folders.length()) folders.optJSONObject(i)?.let { lines += "${it.optString("path")}  (${it.optInt("notes")})" }
            lines += "top level  (${json.optInt("topLevel")})"
        }
        json.optJSONArray("tags") != null && json.length() == 1 -> {
            val tags = json.getJSONArray("tags")
            for (i in 0 until tags.length()) tags.optJSONObject(i)?.let { lines += "#${it.optString("tag")}  (${it.optInt("notes")})" }
            if (tags.length() == 0) lines += "(no tags)"
        }
        json.keys().asSequence().all { key -> json.opt(key).let { it !is JSONObject && (it !is JSONArray || it.isFlat()) } } -> rest()
        else -> return prettyJson(json)
    }
    return lines.joinToString("\n")
}

private fun JSONArray.isFlat(): Boolean = (0 until length()).none { opt(it) is JSONObject || opt(it) is JSONArray }

private fun flat(value: Any): String = when (value) {
    is JSONArray -> if (value.length() == 0) "-" else (0 until value.length()).joinToString(", ") { value.opt(it).toString() }
    else -> value.toString()
}
