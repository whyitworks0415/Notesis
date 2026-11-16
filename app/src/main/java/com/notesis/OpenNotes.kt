package com.notesis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/**
 * A screen that can take a change made under it (an agent's or the terminal's
 * command) while it shows the note: it writes what it shows and waits, the
 * change is made on disk, and it reads the note again. Both are called on the
 * main thread.
 */
internal interface LiveNoteScreen {
    /** Writes what is on screen and stops writing until [letGo]; false when it cannot now (a stroke is being drawn). */
    fun hold(): Boolean
    /** Off the main thread, after [hold] or [flush]: waits until what it wrote is on disk. */
    fun settle() {}
    /** Writes what is on screen and goes on, for a read that is to see it; nothing is held. */
    fun flush() {}
    /** Goes on; [changed]: reads the note again first, keeping the view where it was. Calls [done] once it is showing it. */
    fun letGo(changed: Boolean, done: () -> Unit)
    /** As [letGo], told which pages' ink was rewritten (by id, "board" for the whiteboard; null: not known). */
    fun letGo(changed: Boolean, inkPages: Set<String>?, done: () -> Unit) = letGo(changed, done)
}

/** The screens that can take changes, by the note they show (see [LiveNoteScreen]). */
internal object LiveNotes {
    private val screens = HashMap<String, LiveNoteScreen>()
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Whether a screen is held for an agent now, for the screen to say so; read on the main thread. */
    var holding by mutableStateOf<Set<String>>(emptySet())
        private set

    @Synchronized fun register(id: String, screen: LiveNoteScreen) { screens[id] = screen }

    @Synchronized fun unregister(id: String, screen: LiveNoteScreen) { if (screens[id] === screen) screens.remove(id) }

    @Synchronized private fun screen(id: String): LiveNoteScreen? = screens[id]

    /**
     * From a worker thread: holds the screen showing [id], trying again while a
     * stroke is being drawn, for up to [waitMs]. True once held and settled.
     */
    fun hold(id: String, waitMs: Long = HOLD_WAIT_MS): Boolean {
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            val screen = screen(id) ?: return false
            when (onMain { screen.hold().also { if (it) holding = holding + id } }) {
                true -> { screen.settle(); return true }
                // Not run in time, but it may still run: let it go again right after, or the screen would stay held.
                null -> { main.post { screen.letGo(false) { holding = holding - id } }; return false }
                false -> Unit
            }
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(HOLD_RETRY_MS)
        }
    }

    /** From a worker thread: the screen showing [id] writes what it shows, so a read sees what is on screen. */
    fun flush(id: String) {
        val screen = screen(id) ?: return
        if (onMain { screen.flush() } != null) screen.settle()
    }

    fun letGo(id: String, changed: Boolean, inkPages: Set<String>? = null) {
        val screen = screen(id)
        onMain {
            // The veil stays until the screen shows the note as it is now: a stroke drawn before that would be lost.
            if (screen == null) holding = holding - id
            else screen.letGo(changed, inkPages) { holding = holding - id }
        }
    }

    /** [block] on the main thread, waited for; null when it did not run in time. */
    private fun <T> onMain(block: () -> T): T? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: T? = null
        main.post { try { out = block() } finally { latch.countDown() } }
        return if (latch.await(MAIN_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) out else null
    }

    private const val HOLD_WAIT_MS = 4_000L
    private const val HOLD_RETRY_MS = 120L
    private const val MAIN_WAIT_MS = 3_000L
}
