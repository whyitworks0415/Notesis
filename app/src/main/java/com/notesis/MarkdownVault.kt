package com.notesis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

/** A Markdown file in a vault: its name without ".md", where it is in the folder, and its document. */
internal data class VaultFile(val name: String, val path: String, val uri: Uri, val modified: Long)

/**
 * A folder of Markdown files picked on the device - an Obsidian vault, or
 * any folder - read and written where it is, so the same files open in
 * Obsidian or anything else. Folders whose names start with a dot
 * (".obsidian", ".trash") are left alone.
 */
internal class MarkdownVault(private val context: Context, val tree: Uri) {
    private val resolver get() = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    /** The folder's own name. */
    val label: String by lazy {
        runCatching {
            resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: "Vault"
    }

    /** Every Markdown file in the folder and the folders in it, by path. */
    fun files(): List<VaultFile> {
        val found = ArrayList<VaultFile>()
        fun walk(parentId: String, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH || found.size >= MAX_FILES) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val folders = ArrayList<Pair<String, String>>()
            runCatching {
                resolver.query(children, columns, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext() && found.size < MAX_FILES) {
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val mime = cursor.getString(2).orEmpty()
                        if (name.startsWith(".")) continue
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            folders += id to name
                        } else if (isMarkdownName(name)) {
                            found += VaultFile(
                                name = markdownTitle(name),
                                path = prefix + name,
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                modified = cursor.getLong(3),
                            )
                        }
                    }
                }
            }
            for ((id, name) in folders) walk(id, "$prefix$name/", depth + 1)
        }
        walk(rootId, "", 0)
        return found.sortedBy { it.path.lowercase(Locale.ROOT) }.also { listed[tree.toString()] = it }
    }

    /**
     * What [files] found last time in this process, or null: walking a folder
     * through the system's document picker is slow, so the library shows
     * this at once and walks again behind it.
     */
    fun lastListed(): List<VaultFile>? = listed[tree.toString()]

    fun read(uri: Uri): String =
        resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Can't read the file")

    fun write(uri: Uri, text: String) {
        // "wt": the file is cut to what is written, or a shorter text would leave the old one's tail.
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Can't write the file")
    }

    /** A new note named [name] at the top of the folder, or the next free name like it. */
    fun create(name: String, text: String): VaultFile? = runCatching {
        val taken = files().map { linkKey(it.name) }.toHashSet()
        val base = name.trim().ifBlank { "Untitled" }.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
        var title = base
        var n = 1
        while (linkKey(title) in taken) title = "$base ${++n}"
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val uri = DocumentsContract.createDocument(resolver, parent, "text/markdown", "$title.md") ?: return null
        write(uri, text)
        VaultFile(title, "$title.md", uri, System.currentTimeMillis())
    }.getOrNull()

    /**
     * A file of the folder by name, as Obsidian finds what ![[name]] shows:
     * by its path from the top if it has one, else the first file of that
     * name anywhere. Remembered, as walking the folder is slow.
     */
    fun openAttachment(name: String): java.io.InputStream? {
        val wanted = name.trim().removePrefix("/").removePrefix("./")
        val uri = attachments[tree.toString() + "|" + wanted.lowercase(Locale.ROOT)] ?: findAttachment(wanted)?.also {
            attachments[tree.toString() + "|" + wanted.lowercase(Locale.ROOT)] = it
        } ?: return null
        return runCatching { resolver.openInputStream(uri) }.getOrNull()
    }

    private fun findAttachment(wanted: String): Uri? {
        val leaf = wanted.substringAfterLast('/').lowercase(Locale.ROOT)
        var found: Uri? = null
        var visited = 0
        fun walk(parentId: String, prefix: String, depth: Int) {
            if (found != null || depth > MAX_DEPTH || visited > MAX_FILES) return
            val folders = ArrayList<Pair<String, String>>()
            runCatching {
                resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId), arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext() && found == null) {
                        visited++
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        if (name.startsWith(".")) continue
                        if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) folders += id to name
                        else if (name.lowercase(Locale.ROOT) == leaf &&
                            (!wanted.contains('/') || (prefix + name).equals(wanted, ignoreCase = true))) {
                            found = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        }
                    }
                }
            }
            for ((id, name) in folders) walk(id, "$prefix$name/", depth + 1)
        }
        walk(rootId, "", 0)
        return found
    }

    /** Keeps [input] at the top of the folder under a free name like [name]; the name it got. */
    fun addAttachment(name: String, input: java.io.InputStream): String? = runCatching {
        val base = name.substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "file" }
        var chosen = base
        var n = 1
        while (findAttachment(chosen) != null) chosen = base.substringBeforeLast('.') + " ${++n}." + base.substringAfterLast('.', "bin")
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(chosen.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "application/octet-stream"
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val uri = DocumentsContract.createDocument(resolver, parent, mime, chosen) ?: return null
        resolver.openOutputStream(uri, "w")?.use { input.copyTo(it) } ?: return null
        attachments[tree.toString() + "|" + chosen.lowercase(Locale.ROOT)] = uri
        chosen
    }.getOrNull()

    /** Writes [bytes] as the attachment [name], over the one there or as a new file at the vault's top. */
    fun replaceAttachment(name: String, bytes: ByteArray): Boolean = runCatching {
        val wanted = name.trim().removePrefix("/").removePrefix("./")
        val key = tree.toString() + "|" + wanted.lowercase(Locale.ROOT)
        val uri = attachments[key] ?: findAttachment(wanted) ?: run {
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(wanted.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "application/octet-stream"
            DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), mime, wanted.substringAfterLast('/'))
        } ?: return false
        attachments[key] = uri
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return false
        true
    }.getOrDefault(false)

    companion object {
        private val listed = java.util.concurrent.ConcurrentHashMap<String, List<VaultFile>>()
        private val attachments = java.util.concurrent.ConcurrentHashMap<String, Uri>()
        private const val MAX_FILES = 5000
        private const val MAX_DEPTH = 12
        private const val PREFS = "vaults"
        private const val KEY_LAST = "last"

        /** Keeps the right to read and write [tree] across restarts, and makes it the vault opened next time. */
        fun adopt(context: Context, tree: Uri) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST, tree.toString()).apply()
        }

        /** The vault opened last, while the app still may write to it. */
        fun last(context: Context): Uri? {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null) ?: return null
            val uri = Uri.parse(saved)
            val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            return uri.takeIf { held }
        }
    }
}

internal fun isMarkdownName(name: String): Boolean =
    name.lowercase(Locale.ROOT).let { it.endsWith(".md") || it.endsWith(".markdown") }

internal fun markdownTitle(fileName: String): String =
    fileName.removeSuffix(".md").removeSuffix(".MD").removeSuffix(".markdown")
