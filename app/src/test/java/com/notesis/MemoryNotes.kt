package com.notesis

/** Notes in memory: each a list of pages, ink counted per page id. */
internal class MemoryNotes : NoteWorkspace {
    val metas = mutableMapOf<String, NoteMeta>()
    val trash = mutableSetOf<String>()
    val pages = mutableMapOf<String, MutableList<Page>>()
    val ink = mutableMapOf<String, Int>()
    val texts = mutableMapOf<String, String>()
    /** What is off each note's pages (its text boxes), kept across a load and a save as the store keeps board.bin and meta.json. */
    val boards = mutableMapOf<String, MutableList<PageImage>>()
    var open: String? = null
    /** Whether the screen showing [open] can be held for a change (the note screen can; a split part cannot). */
    var live = true
    /** Each hold and letting go of a screen, in order: "hold n1", "let go n1 changed". */
    val screen = mutableListOf<String>()
    var saves = 0
    /** Each PDF note's page texts, by PDF page, and its bookmarks. */
    val pdfTexts = mutableMapOf<String, List<String>>()
    val outlines = mutableMapOf<String, List<PdfMark>>()
    val folderSet = sortedSetOf<String>()
    val shared = mutableListOf<String>()
    private var made = 0

    fun add(id: String, title: String, pageCount: Int = 3, folder: String = "", tags: List<String> = emptyList(),
            kind: NoteKind = NoteKind.INK, modified: Long = 0L) {
        metas[id] = NoteMeta(id, title, modified, pageCount, 0, folder = folder, tags = tags, kind = kind)
        pages[id] = if (kind == NoteKind.MARKDOWN) mutableListOf() else MutableList(pageCount) { Page(id = "$id-p${it + 1}") }
    }

    /** A note whose pages each show a page of a PDF holding [texts], after [before] pages of its own. */
    fun addPdf(id: String, title: String, texts: List<String>, before: Int = 0, outline: List<PdfMark> = emptyList()) {
        add(id, title, pageCount = before + texts.size)
        pages.getValue(id).drop(before).forEachIndexed { i, page -> page.pdfPageIndex = i; page.background = PageBackground.PDF }
        pdfTexts[id] = texts
        outlines[id] = outline
    }

    private fun copy(page: Page) = Page(id = page.id, width = page.width, height = page.height,
        background = page.background, templateId = page.templateId, pdfPageIndex = page.pdfPageIndex, tocTitle = page.tocTitle,
        tocLevel = page.tocLevel, bookmarked = page.bookmarked, besideOf = page.besideOf)
        .also { it.images.addAll(page.images) }

    override fun notes() = metas.values.filter { it.id !in trash }
    override fun trashed() = metas.values.filter { it.id in trash }
    override fun isOpen(id: String) = open == id
    override fun load(id: String) = Document(pages.getValue(id).map(::copy).toMutableList())
        .also { document -> boards[id]?.let { document.board.images.addAll(it.map { image -> image.copy() }) } }
    override fun save(id: String, title: String, document: Document) {
        pages[id] = document.pages.map(::copy).toMutableList(); saves++
        boards[id] = document.board.images.map { it.copy() }.toMutableList()
    }
    override fun markdown(id: String) = texts[id]
    override fun rename(id: String, title: String): Boolean { metas[id] = metas.getValue(id).copy(title = title); return true }
    override fun setFolder(id: String, folder: String) { metas[id] = metas.getValue(id).copy(folder = folder) }
    override fun setTags(id: String, tags: List<String>) { metas[id] = metas.getValue(id).copy(tags = tags) }
    override fun trash(id: String) { trash += id }
    override fun restore(id: String) { trash -= id }
    override fun inkCount(id: String, page: Page) = ink[page.id] ?: 0
    override fun textBox(id: String, content: TextBoxContent) = PageImage("img", 0f, 0f, 200f, 50f, content)
    override fun addInk(id: String, document: Document, page: Page, lines: List<List<FloatArray>>, color: Int, width: Float): Int {
        ink[page.id] = (ink[page.id] ?: 0) + lines.size; return lines.size
    }
    override fun eraseInk(id: String, document: Document, page: Page, area: FloatArray?): Int {
        val had = ink[page.id] ?: 0; ink[page.id] = 0; return had
    }
    override fun export(id: String, title: String, format: ExportKind, pages: List<Int>, withInk: Boolean) =
        "/out/$title.${format.name.lowercase()}"
    override fun keep(id: String): Any = Triple(pages.getValue(id).map(::copy), ink.toMap(), boards[id]?.map { it.copy() })
    @Suppress("UNCHECKED_CAST")
    /** How many kept copies were let go, for the tests of undo's housekeeping. */
    var released = 0
    override fun release(kept: Any) { released++ }

    override fun bring(id: String, kept: Any) {
        val (savedPages, savedInk, savedBoard) = kept as Triple<List<Page>, Map<String, Int>, List<PageImage>?>
        pages[id] = savedPages.map(::copy).toMutableList()
        ink.clear(); ink.putAll(savedInk)
        if (savedBoard == null) boards.remove(id) else boards[id] = savedBoard.map { it.copy() }.toMutableList()
    }

    override fun pdfOutline(id: String) = outlines[id].orEmpty()
    override fun pdfText(id: String, pages: List<Page>) = pages.map { page -> pdfTexts[id]?.getOrNull(page.pdfPageIndex) }
    override fun pageImage(id: String, pageIndex: Int, width: Int) = byteArrayOf(1, 2, 3, 4)
    override fun search(query: String, limit: Int) = notes().filter { it.title.contains(query, true) || texts[it.id].orEmpty().contains(query, true) }
        .map { SearchHit(it.id, it.title, -1, "") }.take(limit)
    override fun saveMarkdown(id: String, title: String, text: String) { texts[id] = text; saves++ }
    override fun createNote(title: String, folder: String, background: PageBackground, pages: Int): String {
        val id = "new${++made}"; add(id, title, pages, folder); return id
    }
    override fun createMarkdown(title: String, folder: String, text: String): String {
        val id = "md${++made}"; add(id, title, folder = folder, kind = NoteKind.MARKDOWN); texts[id] = text; return id
    }
    override fun duplicateNote(id: String, title: String): String {
        val copy = "copy${++made}"
        metas[copy] = metas.getValue(id).copy(id = copy, title = title)
        pages[copy] = pages.getValue(id).map(::copy).toMutableList()
        return copy
    }
    override fun mergeNotes(ids: List<String>, title: String): String {
        val merged = "merged${++made}"
        add(merged, title, 0)
        pages[merged] = ids.flatMap { pages.getValue(it).map(::copy) }.toMutableList()
        return merged
    }
    override fun setFavorite(id: String, on: Boolean) { metas[id] = metas.getValue(id).copy(favorite = on) }
    override fun setLabel(id: String, color: Int) { metas[id] = metas.getValue(id).copy(label = color) }
    override fun folders() = (folderSet + notes().map { it.folder }).filter { it.isNotEmpty() }
        .flatMap { path -> path.split('/').indices.map { path.split('/').take(it + 1).joinToString("/") } }.toSortedSet().toList()
    override fun createFolder(path: String): Boolean { folderSet += path; return true }
    override fun removeEmptyFolder(path: String): Boolean {
        if (notes().any { isInFolder(it.folder, path) }) return false
        folderSet.remove(path); return true
    }
    override fun renameFolder(from: String, to: String): Boolean {
        if (to in folders()) return false
        for (meta in notes().filter { isInFolder(it.folder, from) }) setFolder(meta.id, movedFolderPath(meta.folder, from, to))
        if (folderSet.remove(from)) folderSet += to
        return true
    }
    override fun openNotes() = setOfNotNull(open)
    override fun appVersion() = "test"
    override fun copyImage(id: String, imageId: String) = "copy-$imageId"
    override fun shareFile(path: String): String { val out = "/shared/" + path.substringAfterLast('/'); shared += out; return out }
    override fun unshareFile(path: String) { shared -= path }
    override fun hold(id: String): Boolean { if (!live) return false; screen += "hold $id"; return true }
    override fun letGo(id: String, changed: Boolean) { screen += "let go $id" + if (changed) " changed" else "" }
}
