package com.notesis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Rebuildable full-text cache for note search.
 *
 * Files remain the source of truth. The small state table lets an existing
 * library be indexed lazily on its first search and only revisits notes whose
 * metadata changed afterwards.
 */
internal class NoteSearchIndex(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE note_search USING fts4(" +
                "noteId, title, body, tokenize=unicode61, notindexed=noteId)",
        )
        db.execSQL(
            "CREATE TABLE note_search_state(" +
                "noteId TEXT PRIMARY KEY NOT NULL, modified INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS note_search")
        db.execSQL("DROP TABLE IF EXISTS note_search_state")
        onCreate(db)
    }

    fun synchronize(notes: List<NoteMeta>, bodyOf: (NoteMeta) -> String) {
        val db = writableDatabase
        val live = notes.mapTo(HashSet(notes.size)) { it.id }
        val stale = mutableListOf<String>()
        db.rawQuery("SELECT noteId FROM note_search_state", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (id !in live) stale += id
            }
        }
        stale.forEach { remove(db, it) }
        for (note in notes) {
            if (!isCurrent(db, note.id, note.modified)) {
                put(db, note.id, note.title, bodyOf(note), note.modified)
            }
        }
    }

    fun put(noteId: String, title: String, body: String, modified: Long) {
        put(writableDatabase, noteId, title, body, modified)
    }

    fun remove(noteId: String) {
        remove(writableDatabase, noteId)
    }

    /** Updates cheap metadata without rereading the note body on every autosave. */
    fun updateMetadata(noteId: String, title: String, modified: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val indexed = db.update(
                "note_search",
                ContentValues().apply { put("title", title) },
                "noteId = ?",
                arrayOf(noteId),
            )
            // Do not create state for a missing FTS row. The next search will
            // notice that it is absent and build the complete entry including body.
            if (indexed > 0) {
                db.update(
                    "note_search_state",
                    ContentValues().apply { put("modified", modified) },
                    "noteId = ?",
                    arrayOf(noteId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun match(query: String): Set<String> {
        val expression = matchExpression(query)
        if (expression.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT noteId FROM note_search WHERE note_search MATCH ?",
            arrayOf(expression),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0)
        }
        return result
    }

    private fun isCurrent(db: SQLiteDatabase, noteId: String, modified: Long): Boolean =
        db.rawQuery(
            "SELECT modified FROM note_search_state WHERE noteId = ?",
            arrayOf(noteId),
        ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == modified }

    private fun put(
        db: SQLiteDatabase,
        noteId: String,
        title: String,
        body: String,
        modified: Long,
    ) {
        db.beginTransaction()
        try {
            db.delete("note_search", "noteId = ?", arrayOf(noteId))
            db.insertOrThrow("note_search", null, ContentValues().apply {
                put("noteId", noteId)
                put("title", title)
                put("body", body)
            })
            db.insertWithOnConflict(
                "note_search_state",
                null,
                ContentValues().apply {
                    put("noteId", noteId)
                    put("modified", modified)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun remove(db: SQLiteDatabase, noteId: String) {
        db.delete("note_search", "noteId = ?", arrayOf(noteId))
        db.delete("note_search_state", "noteId = ?", arrayOf(noteId))
    }

    private fun matchExpression(query: String): String = TOKEN.findAll(query)
        .map { token -> "\"${token.value.replace("\"", "\"\"")}\"*" }
        .joinToString(" AND ")

    companion object {
        private const val DATABASE_NAME = "note-search.db"
        private const val DATABASE_VERSION = 1
        private val TOKEN = Regex("[\\p{L}\\p{N}_]+")
    }
}
