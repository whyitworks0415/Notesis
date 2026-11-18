package com.notesis

import android.content.Context
import java.io.File

/**
 * Earlier versions of a note, so a change regretted after the note was closed
 * - when its undo is gone - can still be taken back.
 *
 * When a note that was changed is closed, a copy of its files is put aside
 * (not more often than [MIN_GAP_MS] for one note), the newest [KEEP_PER_NOTE]
 * kept per note and [MAX_TOTAL_BYTES] across all of them. The note's PDF is
 * not copied: it is the document written on, which writing never changes,
 * and it can be the largest file by far. Restoring first puts the note as it
 * is now aside as a version too, so a restore can itself be undone.
 */
internal class NoteHistory(filesDir: File) {
    constructor(context: Context) : this(context.filesDir)

    private val notes = File(filesDir, "notes")
    private val root = File(filesDir, "history")

    /** The versions kept of [id], newest first, by the time each was taken. */
    fun versions(id: String): List<Long> =
        File(root, id).listFiles { file -> file.isDirectory }.orEmpty()
            .mapNotNull { it.name.toLongOrNull() }.sortedDescending()

    /** Puts aside the note as it is now, unless a version was taken a moment ago. Off the main thread. */
    fun keep(id: String, now: Long = System.currentTimeMillis(), force: Boolean = false): Boolean {
        val source = File(notes, id)
        if (!source.isDirectory) return false
        val latest = versions(id).firstOrNull()
        if (!force && latest != null) {
            // Nothing changed since the last version, or one was taken a moment ago: nothing to keep.
            if (now - latest < MIN_GAP_MS) return false
            if (newestChange(source) <= latest) return false
        }
        val target = File(File(root, id), now.toString())
        val partial = File(File(root, id), "$now.part")
        val ok = runCatching {
            partial.deleteRecursively()
            copyNote(source, partial)
            partial.renameTo(target)
        }.getOrDefault(false)
        if (!ok) partial.deleteRecursively()
        prune(id)
        return ok
    }

    /**
     * Puts version [at] of [id] back in place of the note's files, the PDF
     * left as it is. The note must not be open. False when the version is gone.
     */
    fun restore(id: String, at: Long): Boolean {
        val version = File(File(root, id), at.toString())
        val note = File(notes, id)
        if (!version.isDirectory || !note.isDirectory) return false
        keep(id, force = true)
        return runCatching {
            note.listFiles().orEmpty().filter { it.name !in KEPT_IN_PLACE }.forEach { it.deleteRecursively() }
            version.copyRecursively(note, overwrite = true)
        }.getOrDefault(false)
    }

    private fun newestChange(dir: File): Long =
        dir.walkTopDown().filter { it.isFile && it.name != "doc.pdf" && it.extension != "mesh" }.maxOfOrNull { it.lastModified() } ?: 0L

    /** The space every version takes, in bytes. */
    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun copyNote(source: File, target: File) {
        target.mkdirs()
        source.listFiles().orEmpty().forEach { file ->
            if (file.name in KEPT_IN_PLACE || !worthKeeping(file)) return@forEach
            if (file.isDirectory) copyNote(file, File(target, file.name))
            else file.copyTo(File(target, file.name), overwrite = true)
        }
    }

    /** Half-written files and the GPU renderer's caches (made again from the strokes) are not part of a version. */
    private fun worthKeeping(file: File): Boolean =
        !file.name.endsWith(".tmp") && !file.name.endsWith(".part") && file.extension != "mesh"

    private fun prune(id: String) {
        versions(id).drop(KEEP_PER_NOTE).forEach { File(File(root, id), it.toString()).deleteRecursively() }
        // Across all notes: the oldest versions go first while the total is over the cap.
        var total = totalBytes()
        if (total <= MAX_TOTAL_BYTES) return
        val all = root.listFiles().orEmpty().flatMap { note ->
            note.listFiles { file -> file.isDirectory }.orEmpty().mapNotNull { dir -> dir.name.toLongOrNull()?.let { it to dir } }
        }.sortedBy { it.first }
        for ((_, dir) in all) {
            if (total <= MAX_TOTAL_BYTES) break
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (dir.deleteRecursively()) total -= size
        }
    }

    companion object {
        const val KEEP_PER_NOTE = 10
        const val MIN_GAP_MS = 30 * 60 * 1000L
        const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
        /** Files the versions leave alone: the PDF written on, and recordings, which are not changed by writing. */
        val KEPT_IN_PLACE = setOf("doc.pdf", "recordings")
    }
}
