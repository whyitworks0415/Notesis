package com.notesis

/**
 * Where a PDF came from when another app handed it over to be written on (the
 * pen button of Chrome's PDF viewer, "Edit" in a file manager) with leave to
 * write it back: the note can then save its writing into that same file
 * instead of a copy. Kept per note; forgotten once the leave has run out.
 */
internal object PdfOrigins {
    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("pdf-origins", android.content.Context.MODE_PRIVATE)

    fun of(context: android.content.Context, noteId: String): android.net.Uri? =
        prefs(context).getString(noteId, null)?.let(android.net.Uri::parse)

    fun remember(context: android.content.Context, noteId: String, uri: android.net.Uri) {
        prefs(context).edit().putString(noteId, uri.toString()).apply()
    }

    fun forget(context: android.content.Context, noteId: String) {
        prefs(context).edit().remove(noteId).apply()
    }

    /**
     * Writes the note, as "Export as PDF" makes it, into the file it came from.
     * Made whole in a temporary file first, so a failure leaves the original as
     * it was. False when it could not be written; the leave is then forgotten.
     */
    fun saveBack(context: android.content.Context, store: NoteStore, noteId: String): Boolean {
        val uri = of(context, noteId) ?: return false
        val temp = java.io.File(context.cacheDir, "save-back-$noteId.pdf")
        return try {
            if (!temp.outputStream().use { store.exportPdf(noteId, it) }) return false
            context.contentResolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: return false
            true
        } catch (_: SecurityException) {
            forget(context, noteId)
            false
        } catch (_: Exception) {
            false
        } finally {
            temp.delete()
        }
    }
}
