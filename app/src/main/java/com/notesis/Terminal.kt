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
internal fun terminalOutput(answer: String): List<TerminalLine> {
    val json = runCatching { JSONObject(answer) }.getOrNull() ?: return listOf(TerminalLine(answer, TerminalTone.OUTPUT))
    if (!json.optBoolean("ok")) {
        val out = mutableListOf(TerminalLine("error: " + json.optString("error"), TerminalTone.ERROR))
        if (json.has("line")) out += TerminalLine("on line ${json.optInt("line")}" +
            (json.optString("undone").takeIf { it.isNotEmpty() }?.let { ". $it" } ?: ""), TerminalTone.ERROR)
        json.optString("usage").takeIf { it.isNotEmpty() }?.let { usage ->
            out += usage.split('\n').map { TerminalLine("  $it", TerminalTone.HINT) }
        }
        return out
    }
    val body = json.opt("result") ?: json.opt("results")
    val out = mutableListOf(TerminalLine(prettyJson(body), TerminalTone.OUTPUT))
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
