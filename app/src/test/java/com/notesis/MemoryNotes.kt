package com.notesis

/** Notes in memory: each a list of pages, ink counted per page id. */
internal class MemoryNotes : NoteWorkspace {
    val metas = mutableMapOf<String, NoteMeta>()
    val trash = mutableSetOf<String>()
    val pages = mutableMapOf<String, MutableList<Page>>()
    val ink = mutableMapOf<String, Int>()
    val texts = mutableMapOf<String, String>()
    var open: String? = null
    var saves = 0

    fun add(id: String, title: String, pageCount: Int = 3, folder: String = "", tags: List<String> = emptyList(),
            kind: NoteKind = NoteKind.INK) {
        metas[id] = NoteMeta(id, title, 0L, pageCount, 0, folder = folder, tags = tags, kind = kind)
        pages[id] = MutableList(pageCount) { Page(id = "$id-p${it + 1}") }
    }

    private fun copy(page: Page) = Page(id = page.id, width = page.width, height = page.height,
        background = page.background, tocTitle = page.tocTitle, tocLevel = page.tocLevel, besideOf = page.besideOf)
        .also { it.images.addAll(page.images) }

    override fun notes() = metas.values.filter { it.id !in trash }
    override fun trashed() = metas.values.filter { it.id in trash }
    override fun isOpen(id: String) = open == id
    override fun load(id: String) = Document(pages.getValue(id).map(::copy).toMutableList())
    override fun save(id: String, title: String, document: Document) {
        pages[id] = document.pages.map(::copy).toMutableList(); saves++
    }
    override fun markdown(id: String) = texts[id]
    override fun rename(id: String, title: String): Boolean { metas[id] = metas.getValue(id).copy(title = title); return true }
    override fun setFolder(id: String, folder: String) { metas[id] = metas.getValue(id).copy(folder = folder) }
    override fun setTags(id: String, tags: List<String>) { metas[id] = metas.getValue(id).copy(tags = tags) }
    override fun trash(id: String) { trash += id }
    override fun restore(id: String) { trash -= id }
    override fun handwriting(id: String, page: Page) = if (page.id.endsWith("p1")) "hello world" else null
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
    override fun keep(id: String): Any = pages.getValue(id).map(::copy) to ink.toMap()
    @Suppress("UNCHECKED_CAST")
    /** How many kept copies were let go, for the tests of undo's housekeeping. */
    var released = 0
    override fun release(kept: Any) { released++ }

    override fun bring(id: String, kept: Any) {
        val (savedPages, savedInk) = kept as Pair<List<Page>, Map<String, Int>>
        pages[id] = savedPages.map(::copy).toMutableList()
        ink.clear(); ink.putAll(savedInk)
    }
}
