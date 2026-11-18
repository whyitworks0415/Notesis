package com.notesis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/*
 * Change cards (Todo.md, C2): what a command is about to do, in words, so
 * the owner can say yes or no to an agent before anything is changed, and
 * what was changed afterwards, with a way to take it back.
 *
 * The words are kept as a template and its places ("Rename “{0}” to “{1}”",
 * ["Maths", "Algebra"]), so the screen can translate the template and fill
 * the places in; nothing here needs Android.
 */

/** One command line in words. [changes] is false for reading, which is never asked about. */
internal data class ChangeLine(val template: String, val args: List<String> = emptyList(), val changes: Boolean) {
    /** The sentence, the template put through [translate] first. */
    fun text(translate: (String) -> String = { it }): String {
        var out = translate(template)
        for ((i, arg) in args.withIndex()) out = out.replace("{$i}", arg)
        return out
    }
}

/** Options that take the word after them, and those that stand alone: so a word that is a value is not taken for a note. */
private val VALUED_OPTIONS = setOf("after", "count", "level", "x", "y", "size", "color", "width", "path", "points", "rect",
    "format", "pages", "folder", "tag", "search", "limit", "offset", "max-chars", "from", "paper", "text", "title", "under", "to", "since", "near")

/** The commands that only read: never asked about, whatever the owner chose. */
internal val READ_COMMANDS = setOf("help", "status", "preview", "search", "notes list", "note read", "tags list", "folder list",
    "page image", "toc show", "pdf info", "pdf text", "pdf find", "text list", "md outline", "md links")

private fun snippet(text: String, max: Int = 60): String {
    val one = text.replace('\n', ' ').trim()
    return if (one.length <= max) one else one.take(max - 1) + "…"
}

/**
 * [words] (a command line's words, without a leading `notesis`) as what it
 * does. The note it names is looked up in [notes] as running it would, and
 * shown by title; one not found is shown as typed, and running it will say so.
 */
internal fun describeCommand(words: List<String>, notes: List<NoteMeta>): ChangeLine {
    if (words.isEmpty()) return ChangeLine("Not a command: {0}", listOf(""), changes = false)
    val single = words[0] in SINGLE_WORD_COMMANDS
    val name = if (single || words.size < 2) words[0] else "${words[0]} ${words[1]}"
    val rest = words.drop(if (single || words.size < 2) 1 else 2)
    val positional = ArrayList<String>()
    var i = 0
    while (i < rest.size) {
        val w = rest[i]
        if (w.startsWith("--") && w.length > 2) {
            val option = w.drop(2).substringBefore('=')
            if (option in VALUED_OPTIONS && '=' !in w) i++
        } else positional += w
        i++
    }
    val flags = rest.filter { it.startsWith("--") }.map { it.drop(2).substringBefore('=') }.toSet()
    fun valueOf(option: String): String? {
        val at = rest.indexOfFirst { it == "--$option" || it.startsWith("--$option=") }
        if (at < 0) return null
        return if ('=' in rest[at]) rest[at].substringAfter('=') else rest.getOrNull(at + 1)
    }
    val ref = positional.firstOrNull().orEmpty()
    val title = runCatching { findNote(ref, notes).title }.getOrDefault(ref).ifEmpty { "?" }
    fun page(index: Int) = positional.getOrElse(index) { "?" }

    return when (name) {
        "note rename" -> ChangeLine("Rename “{0}” to “{1}”", listOf(title, positional.drop(1).joinToString(" ")), true)
        "note mv" -> {
            val folder = normalizeFolderPath(positional.getOrElse(1) { "" }) ?: ""
            if (folder.isEmpty()) ChangeLine("Move “{0}” to the top level", listOf(title), true)
            else ChangeLine("Move “{0}” to the folder “{1}”", listOf(title, folder), true)
        }
        "note tag" -> ChangeLine("Change the tags of “{0}”: {1}",
            listOf(title, (if ("clear" in flags) listOf("clear") else emptyList<String>()).plus(positional.drop(1)).joinToString(" ")), true)
        "note trash" -> ChangeLine("Move “{0}” to the trash", listOf(title), true)
        "note restore" -> ChangeLine("Restore “{0}” from the trash", listOf(title), true)
        "page add" -> {
            val count = valueOf("count")?.toIntOrNull() ?: 1
            if (count == 1) ChangeLine("Add a page to “{0}”", listOf(title), true)
            else ChangeLine("Add {1} pages to “{0}”", listOf(title, count.toString()), true)
        }
        "page rm" -> ChangeLine("Remove pages {1} from “{0}”", listOf(title, positional.drop(1).joinToString(",")), true)
        "page mv" -> ChangeLine("Move page {1} of “{0}” to position {2}", listOf(title, page(1), page(2)), true)
        "toc set" -> ChangeLine("Set the outline entry of page {1} of “{0}”: “{2}”",
            listOf(title, page(1), snippet(positional.drop(2).joinToString(" "))), true)
        "toc rm" -> ChangeLine("Remove the outline entry of page {1} of “{0}”", listOf(title, page(1)), true)
        "toc clear" -> ChangeLine("Clear the outline of “{0}”", listOf(title), true)
        "text add" -> ChangeLine("Add text to page {1} of “{0}”: “{2}”",
            listOf(title, page(1), snippet(positional.drop(2).joinToString(" "))), true)
        "ink add" -> if (page(1).equals("board", true)) ChangeLine("Draw beside the pages of “{0}”", listOf(title), true)
            else ChangeLine("Draw on page {1} of “{0}”", listOf(title, page(1)), true)
        "ink erase" -> if (page(1).equals("board", true)) ChangeLine("Erase ink beside the pages of “{0}”", listOf(title), true) else
            if ("all" in flags) ChangeLine("Erase all the ink on page {1} of “{0}”", listOf(title, page(1)), true)
            else ChangeLine("Erase the ink in an area of page {1} of “{0}”", listOf(title, page(1)), true)
        "note new" -> ChangeLine("Make a handwritten note “{0}”", listOf(positional.joinToString(" ")), true)
        "note new-md" -> ChangeLine("Make a Markdown note “{0}”", listOf(positional.joinToString(" ")), true)
        "note dup" -> ChangeLine("Copy “{0}” as a new note", listOf(title), true)
        "note merge" -> ChangeLine("Join {0} notes into a new note “{1}”",
            listOf(positional.size.toString(), valueOf("title") ?: "?"), true)
        "note fav" -> if ("off" in flags) ChangeLine("Unpin “{0}” from the favourites", listOf(title), true)
            else ChangeLine("Pin “{0}” to the favourites", listOf(title), true)
        "note label" -> ChangeLine("Set the colour label of “{0}”: {1}", listOf(title, page(1)), true)
        "folder new" -> ChangeLine("Make the folder “{0}”", listOf(positional.joinToString(" ")), true)
        "folder rename" -> ChangeLine("Move the folder “{0}” to “{1}”", listOf(ref.ifEmpty { "?" }, page(1)), true)
        "page dup" -> ChangeLine("Copy page {1} of “{0}”", listOf(title, page(1)), true)
        "page paper" -> ChangeLine("Change the paper of pages {1} of “{0}” to {2}", listOf(title, page(1), page(2)), true)
        "page bookmark" -> if ("off" in flags) ChangeLine("Take the bookmark off page {1} of “{0}”", listOf(title, page(1)), true)
            else ChangeLine("Bookmark page {1} of “{0}”", listOf(title, page(1)), true)
        "toc import-pdf" -> ChangeLine("Set the outline of “{0}” from its PDF's bookmarks", listOf(title), true)
        "text edit" -> ChangeLine("Change text box {2} on page {1} of “{0}” to “{3}”",
            listOf(title, page(1), page(2), snippet(positional.drop(3).joinToString(" "))), true)
        "text rm" -> ChangeLine("Remove text box {2} from page {1} of “{0}”", listOf(title, page(1), page(2)), true)
        "md write" -> ChangeLine("Replace all the text of “{0}”", listOf(title), true)
        "md append" -> ChangeLine("Add to “{0}”: “{1}”", listOf(title, snippet(positional.drop(1).joinToString(" "))), true)
        "md replace" -> ChangeLine("In “{0}”, change “{1}” to “{2}”", listOf(title, snippet(page(1), 40), snippet(page(2), 40)), true)
        "export" -> if (valueOf("to") == "shared") ChangeLine("Put a copy of “{0}” in Download/Notesis, where other apps can read it", listOf(title), true)
            else ChangeLine("Read: {0}", listOf(name), changes = false)
        "undo" -> ChangeLine("Undo the last change", emptyList(), true)
        in READ_COMMANDS -> ChangeLine("Read: {0}", listOf(name), changes = false)
        else -> ChangeLine("Not a command: {0}", listOf(words.joinToString(" ").take(80)), changes = false)
    }
}

/** What an agent's changes are met with: asked about first, or made at once with a card to take them back. */
internal enum class AgentPolicy(val label: String, val saved: String) {
    ASK("Ask first", "ask"), AUTO("Make them at once", "auto");

    companion object {
        fun of(saved: String?): AgentPolicy = entries.firstOrNull { it.saved == saved } ?: ASK
    }
}

/** A change an agent made, kept for the list of recent changes and the card's Undo. */
internal data class ChangeRecord(
    val id: Long,
    val client: String,
    val at: Long,
    /** What was done, one sentence each, as the commands reported it. */
    val labels: List<String>,
    /** The commands' step number, for taking just this one back; 0 when it has none. */
    val step: Long,
    val undone: Boolean = false,
)

/** The recent changes agents made, newest first; only what the app is running holds it. */
internal object ChangeLog {
    private const val KEEP = 30
    private val items = ArrayList<ChangeRecord>()
    private var nextId = 0L

    /** Changes whenever the list does, so a screen showing it draws it again. */
    var version by mutableIntStateOf(0)
        private set

    /** The records, newest first. */
    @Synchronized fun all(): List<ChangeRecord> = items.toList()

    @Synchronized fun add(client: String, at: Long, labels: List<String>, step: Long): ChangeRecord {
        val record = ChangeRecord(++nextId, client, at, labels, step)
        items.add(0, record)
        while (items.size > KEEP) items.removeAt(items.lastIndex)
        version++
        return record
    }

    @Synchronized fun markUndone(id: Long) {
        val at = items.indexOfFirst { it.id == id }
        if (at >= 0) items[at] = items[at].copy(undone = true)
        version++
    }

    @Synchronized fun clear() { items.clear(); version++ }
}

/** An agent's change waiting for the owner's word. [decision] is completed with true to allow it, false to refuse it. */
internal class PendingChange(
    val id: Long,
    val client: String,
    val lines: List<ChangeLine>,
) {
    val decision = CompletableFuture<Boolean>()
}

/** How long a change waits to be allowed or refused before it is refused. */
internal const val APPROVAL_WAIT_SECONDS = 120L

/** How many changes may wait at once; more are refused outright, so an agent cannot bury the screen in cards. */
internal const val APPROVAL_QUEUE = 5

/**
 * The changes waiting for a word. The screen shows the first; [request] is
 * called on the thread of the connection, which waits there.
 */
internal object AgentApprovals {
    private val waiting = ArrayList<PendingChange>()
    private var nextId = 0L
    private val listeners = ArrayList<() -> Unit>()

    /** Changes whenever the waiting changes do, so the card on screen is drawn again. */
    var version by mutableIntStateOf(0)
        private set

    /** Called whenever the waiting changes are added to or taken away; on the thread that did it. */
    @Synchronized fun listen(listener: () -> Unit) { listeners += listener }
    @Synchronized fun unlisten(listener: () -> Unit) { listeners -= listener }

    /** The waiting changes, oldest first. */
    @Synchronized fun waiting(): List<PendingChange> = waiting.toList()

    private fun changed() {
        version++
        val copy = synchronized(this) { listeners.toList() }
        for (l in copy) runCatching { l() }
    }

    /** Answers a waiting change. */
    fun decide(pending: PendingChange, allow: Boolean) {
        val removed = synchronized(this) { waiting.remove(pending) }
        pending.decision.complete(allow)
        if (removed) changed()
    }

    /**
     * Waits for the owner's word on [lines]. True only when it was allowed;
     * a refusal, a full queue, no answer in [APPROVAL_WAIT_SECONDS] or the
     * wait cut short are all a no.
     */
    fun request(client: String, lines: List<ChangeLine>, waitSeconds: Long = APPROVAL_WAIT_SECONDS): Boolean {
        val pending = synchronized(this) {
            if (waiting.size >= APPROVAL_QUEUE) return false
            PendingChange(++nextId, client, lines).also { waiting += it }
        }
        changed()
        return try {
            pending.decision.get(waitSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        } finally {
            decide(pending, false)
        }
    }
}

/**
 * How much the agents asked and were answered since the app started, in
 * characters, which is what an agent pays for in tokens (Todo.md, Q1): for
 * the panel, so a habit of reading whole notes shows.
 */
internal object AgentUsage {
    var calls by mutableIntStateOf(0)
        private set
    var sent by mutableIntStateOf(0)
        private set
    var answered by mutableIntStateOf(0)
        private set
    var largest by mutableIntStateOf(0)
        private set
    var images by mutableIntStateOf(0)
        private set

    @Synchronized fun add(sent: Int, answered: Int, images: Int) {
        calls++
        this.sent += sent
        this.answered += answered
        largest = maxOf(largest, answered)
        this.images += images
    }

    @Synchronized fun reset() { calls = 0; sent = 0; answered = 0; largest = 0; images = 0 }
}
