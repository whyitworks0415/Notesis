package com.notesis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** One page of one note whose text contains what was searched for. */
data class PageHit(val pageId: String, val pageIndex: Int, val snippet: String)

/**
 * The text of every page - PDF text and recognised handwriting - copied into
 * SQLite so a search is one query instead of reading every page file of every
 * note on each keystroke.
 *
 * The files stay the source of truth: [sync] copies in whatever changed since
 * the last search, by modification time, so the index can be thrown away at
 * any time and rebuilds itself. Matching stays a substring match (LIKE), which
 * is what finds a Korean word inside a longer one; a token index would not.
 */
class SearchIndex(context: Context) : SQLiteOpenHelper(context, "search.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE pages (note TEXT NOT NULL, file TEXT NOT NULL, mtime INTEGER NOT NULL, " +
                "body TEXT NOT NULL, PRIMARY KEY (note, file))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS pages")
        onCreate(db)
    }

    /** Brings one note's rows in line with the text files in [dir]. */
    @Synchronized
    fun sync(note: String, dir: File) {
        val files = dir.listFiles { file -> isTextFile(file.name) }.orEmpty()
        val db = writableDatabase
        val known = HashMap<String, Long>()
        db.rawQuery("SELECT file, mtime FROM pages WHERE note = ?", arrayOf(note)).use { cursor ->
            while (cursor.moveToNext()) known[cursor.getString(0)] = cursor.getLong(1)
        }
        val present = files.map { it.name }.toHashSet()
        val changed = files.filter { known[it.name] != it.lastModified() }
        val gone = known.keys.filter { it !in present }
        if (changed.isEmpty() && gone.isEmpty()) return
        db.beginTransaction()
        try {
            for (file in changed) {
                val body = runCatching { file.readText() }.getOrNull() ?: continue
                db.insertWithOnConflict(
                    "pages",
                    null,
                    ContentValues().apply {
                        put("note", note)
                        put("file", file.name)
                        put("mtime", file.lastModified())
                        put("body", body)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            for (name in gone) db.delete("pages", "note = ? AND file = ?", arrayOf(note, name))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Page files containing [needle], grouped by note, each with a short excerpt around it. */
    @Synchronized
    fun find(needle: String): Map<String, List<Pair<String, String>>> {
        val pattern = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val found = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        readableDatabase.rawQuery(
            "SELECT note, file, body FROM pages WHERE body LIKE ? ESCAPE '\\'",
            arrayOf(pattern),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val body = cursor.getString(2)
                found.getOrPut(cursor.getString(0)) { mutableListOf() } +=
                    cursor.getString(1) to snippet(body, needle)
            }
        }
        return found
    }

    @Synchronized
    fun removeNote(note: String) {
        writableDatabase.delete("pages", "note = ?", arrayOf(note))
    }

    companion object {
        fun isTextFile(name: String): Boolean =
            name.endsWith(".txt") || name.endsWith(NoteStore.INK_INDEX)

        /** The page id a text file belongs to: the file name without its extension. */
        fun pageIdOf(file: String): String = file.substringBeforeLast('.')

        /** About [radius] characters either side of the first match, on one line. */
        fun snippet(body: String, needle: String, radius: Int = 24): String {
            val at = body.indexOf(needle, ignoreCase = true)
            if (at < 0) return body.take(radius * 2).replace('\n', ' ').trim()
            val from = (at - radius).coerceAtLeast(0)
            val to = (at + needle.length + radius).coerceAtMost(body.length)
            val text = body.substring(from, to).replace('\n', ' ').trim()
            return (if (from > 0) "…" else "") + text + (if (to < body.length) "…" else "")
        }
    }
}
