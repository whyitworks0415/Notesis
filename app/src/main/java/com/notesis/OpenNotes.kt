package com.notesis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/*
 * The notes on screen now, so the note commands - the terminal, an agent's
 * connection - read them but never change them: the screen keeps its own
 * copy of an open note and writes it back, over anything else done to it.
 * A note counts as open in the note screen, in each part of a split screen,
 * in the reference panel, and in the Markdown editor.
 */
internal object OpenNotes {
    private val held = HashMap<Any, Set<String>>()

    @Synchronized fun set(owner: Any, ids: Set<String>) { held[owner] = ids }

    @Synchronized fun clear(owner: Any) { held.remove(owner) }

    @Synchronized fun isOpen(id: String): Boolean = held.values.any { id in it }

    @Synchronized fun all(): Set<String> = held.values.flatten().toSet()
}

/** Holds [ids] as open for as long as this is composed; the ids may change. */
@Composable
internal fun HoldOpenNotes(ids: Set<String>) {
    val owner = remember { Any() }
    SideEffect { OpenNotes.set(owner, ids) }
    DisposableEffect(owner) { onDispose { OpenNotes.clear(owner) } }
}

/** One note store for the whole app, so every reader and writer of notes waits on the same saves. */
internal object SharedNotes {
    @Volatile private var store: NoteStore? = null

    fun store(context: android.content.Context): NoteStore =
        store ?: synchronized(this) { store ?: NoteStore(context.applicationContext).also { store = it } }

    /** For tests, which each have a files directory of their own: the next asking makes a store for it. */
    internal fun resetForTests() { synchronized(this) { store = null } }
}
