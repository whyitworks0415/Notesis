package com.notesis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a Markdown note lives, as the editor sees it: a note in the library,
 * or a file in a vault folder. The editor reads, writes, names its links and
 * finds its backlinks through this, and never touches the storage itself.
 */
internal interface MarkdownSource {
    val key: String
    val title: String
    // load and save block, and are run one at a time on MarkdownIo.serial by the editor, so a
    // note opened again is read after the save it was left with.
    /** Under the title: where the text is kept. */
    val where: String
    suspend fun load(): String
    suspend fun save(text: String)
    /** The names a [[link]] can point at, this note's among them. */
    suspend fun names(): List<String>
    /** The notes that link to this one, by name. */
    suspend fun backlinks(): List<String>
    /** Follows a [[link]] to [name]: that note, or a new one by that name; found off the main thread, opened on it. */
    suspend fun follow(name: String)
}

/** A Markdown note in the library (note.md in its folder). */
internal class StoreMarkdownSource(
    private val store: NoteStore,
    private val note: NoteMeta,
    private val open: (NoteMeta) -> Unit,
) : MarkdownSource {
    override val key = "store:${note.id}"
    override val title = note.title
    override val where = "Markdown · note.md"

    private fun markdownNotes() = store.list().filter { it.kind == NoteKind.MARKDOWN }

    override suspend fun load(): String = store.loadMarkdown(note.id)
    override suspend fun save(text: String) = store.saveMarkdown(note.id, note.title, text)
    override suspend fun names(): List<String> = withContext(Dispatchers.IO) { markdownNotes().map { it.title }.distinct() }

    override suspend fun backlinks(): List<String> = withContext(Dispatchers.IO) {
        val me = linkKey(note.title)
        markdownNotes().filter { it.id != note.id }.mapNotNull { other ->
            runCatching { store.loadMarkdown(other.id) }.getOrNull()
                ?.takeIf { me in wikiTargets(it) }?.let { other.title }
        }
    }

    override suspend fun follow(name: String) {
        val key = linkKey(name)
        val target = withContext(Dispatchers.IO) {
            markdownNotes().firstOrNull { linkKey(it.title) == key }
                ?: store.createMarkdown(name.trim(), "# ${name.trim()}\n\n", note.folder)
        }
        open(target)
    }
}

/** A file in a vault folder, read and written where it is. */
internal class VaultMarkdownSource(
    private val vault: MarkdownVault,
    private val file: VaultFile,
    private val open: (VaultFile) -> Unit,
) : MarkdownSource {
    override val key = "vault:${file.uri}"
    override val title = file.name
    override val where = "${vault.label} · ${file.path}"

    override suspend fun load(): String = vault.read(file.uri)
    override suspend fun save(text: String) = vault.write(file.uri, text)
    override suspend fun names(): List<String> = withContext(Dispatchers.IO) { vault.files().map { it.name }.distinct() }

    override suspend fun backlinks(): List<String> = withContext(Dispatchers.IO) {
        val me = linkKey(file.name)
        vault.files().filter { it.uri != file.uri }.take(BACKLINK_FILES).mapNotNull { other ->
            runCatching { vault.read(other.uri) }.getOrNull()?.takeIf { me in wikiTargets(it) }?.let { other.name }
        }
    }

    override suspend fun follow(name: String) {
        val key = linkKey(name.substringAfterLast('/'))
        val target = withContext(Dispatchers.IO) {
            vault.files().firstOrNull { linkKey(it.name) == key }
                ?: vault.create(name.substringAfterLast('/'), "# ${name.trim()}\n\n")
        }
        target?.let(open)
    }

    private companion object {
        /** Backlinks are read from at most this many files, so a huge vault does not stall. */
        const val BACKLINK_FILES = 800
    }
}
