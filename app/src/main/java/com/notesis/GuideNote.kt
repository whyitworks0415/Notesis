package com.notesis

import android.content.Context

/**
 * The note a fresh install opens with: what the app does, one page per part of
 * it, in text boxes on ordinary pages - so it is read the way every other note
 * is, searched like one, and written on, which is the point of a page that
 * says "try it here".
 */
internal object GuideNote {
    const val TITLE = "Welcome to Notesis"

    private const val PREFS = "onboarding"
    private const val SEEDED = "guideNoteSeeded"

    /** Puts the guide among the notes once per install; true when it was added now. */
    fun seedOnce(context: Context, store: NoteStore): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SEEDED, false)) return false
        // Marked first: a guide that fails to build is not retried on every start.
        prefs.edit().putBoolean(SEEDED, true).apply()
        return runCatching { create(store) }.getOrNull() != null
    }

    /** A new copy of the guide, for About & help; the person may have deleted the first. */
    fun create(store: NoteStore): NoteMeta? = store.createFromPages(TITLE) { noteId ->
        GUIDE.map { page -> layOut(store, noteId, page) }
    }

    /** One guide page: a heading and its blocks, stacked down the page. */
    private class GuidePage(val heading: String, val blocks: List<Block>, val background: PageBackground = PageBackground.BLANK)

    private sealed class Block {
        class Body(val text: String) : Block()
        class Tip(val text: String) : Block()
        /** Room left empty, for writing in. */
        class Space(val height: Float) : Block()
    }

    private fun layOut(store: NoteStore, noteId: String, guide: GuidePage): Page {
        val page = Page(width = Page.A4_WIDTH, height = Page.A4_HEIGHT, background = guide.background)
        var y = MARGIN_TOP
        fun place(content: TextBoxContent, gapAfter: Float) {
            val bitmap = renderTextBox(content)
            try {
                val added = store.addImage(noteId, bitmap) ?: return
                // Text boxes are rendered at twice their page size; see renderTextBox.
                val width = bitmap.width / 2f
                val height = bitmap.height / 2f
                page.images += PageImage(added.first, MARGIN_SIDE, y, width, height, content)
                y += height + gapAfter
            } finally {
                bitmap.recycle()
            }
        }
        place(TextBoxContent(guide.heading, size = HEADING_SIZE, color = INK,
            html = "<b>${guide.heading.htmlEscaped()}</b>"), HEADING_GAP)
        for (block in guide.blocks) when (block) {
            is Block.Body -> place(TextBoxContent(block.text, size = BODY_SIZE, color = INK), BLOCK_GAP)
            is Block.Tip -> place(TextBoxContent(block.text, size = TIP_SIZE, color = INK, background = TIP_FILL,
                corner = TIP_CORNER, padding = TIP_PADDING), BLOCK_GAP)
            is Block.Space -> y += block.height
        }
        page.height = maxOf(Page.A4_HEIGHT, y + MARGIN_TOP)
        return page
    }

    private fun String.htmlEscaped(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val GUIDE = listOf(
        GuidePage("Welcome to Notesis", listOf(
            Block.Body("A notebook for the pen: handwriting, PDFs and pictures,\n" +
                "on pages that scroll like paper."),
            Block.Body("This note is a short tour, one part of the app per page.\n" +
                "It is a normal note - write on it, move things, delete it.\n" +
                "A fresh copy is in About & help whenever you want one."),
            Block.Tip("Scroll with one finger to reach the next page,\n" +
                "or drag the page slider at the side."),
            Block.Space(120f),
            Block.Body("Try it: write your name in the space below."),
        ), PageBackground.LINED),
        GuidePage("The toolbar", listOf(
            Block.Body("The bar comes in four sizes, 1/4 to 4/4.\n" +
                "Pick one at the top of Functions - the round button on the bar."),
            Block.Body("1/4 is the whole bar: the note, undo and redo, and the pen row\n" +
                "with colours and width. 4/4 is a single button you can drag aside."),
            Block.Body("Tap a tool to pick it. Tap the tool in hand again\n" +
                "to open its settings: colour, width and nib."),
            Block.Tip("Functions holds the rest: picture, camera, layers,\n" +
                "recording, capture, text, clock, calendar, to-do,\n" +
                "calculator, other notes, add page and Settings."),
            Block.Tip("With a keyboard, Ctrl+1 to Ctrl+9 pick the tools\n" +
                "in the order the bar shows them."),
        )),
        GuidePage("Writing", listOf(
            Block.Body("Pen, pencil and highlighter each keep their own\n" +
                "colour and width. The eraser removes whole strokes,\n" +
                "or only what it crosses with the partial eraser on."),
            Block.Body("\"More for this pen\" in the pen settings opens\n" +
                "the finer options: nib, pressure and smoothing."),
            Block.Tip("Hold to make a shape: draw a line, triangle, box or circle\n" +
                "and keep the pen still for half a second. A ring under the pen\n" +
                "says it took. Keep moving to stretch or turn it, then lift."),
            Block.Body("A bent line or a curve with a kink works the same way:\n" +
                "the bends stay where you drew them."),
            Block.Space(80f),
            Block.Body("Try it here: draw a rectangle and hold."),
        ), PageBackground.GRID),
        GuidePage("Shapes and selecting", listOf(
            Block.Body("The Shape tool draws exact shapes by dragging.\n" +
                "Tap it again to choose one: lines, arrows, curves, polygons,\n" +
                "boxes, circles, stars and checkboxes."),
            Block.Body("The Lasso picks up ink, pictures and text boxes.\n" +
                "Drag to move, use the corners to resize and turn,\n" +
                "and the bar above for copy, cut, colour, link and lock."),
            Block.Tip("Select a single shape with the lasso and drag one of\n" +
                "its corners to reshape it."),
        )),
        GuidePage("Fingers and gestures", listOf(
            Block.Body("One finger scrolls. Two fingers pinch to zoom.\n" +
                "Two-finger double tap undoes; three-finger double tap redoes."),
            Block.Body("Three fingers up open a second note in a popup beside this one;\n" +
                "three fingers down close it. Drag its top bar to move it,\n" +
                "and a corner to resize it."),
            Block.Body("Turning three fingers rotates the note; near straight, it snaps."),
            Block.Tip("Gesture mode (the Read tool) lets the pen select PDF text,\n" +
                "follow links and lift masking tape without writing."),
            Block.Body("Everything here can be changed in\n" +
                "Settings › Touch & gestures, three-finger gestures included."),
        )),
        GuidePage("Pages and search", listOf(
            Block.Body("The page panel lists pages, the outline and masks.\n" +
                "Its Search tab finds words in PDFs, text boxes and handwriting.\n" +
                "It closes by itself as soon as you start writing."),
            Block.Body("Pull past the last page to add a new one,\n" +
                "or use Add page in Functions."),
            Block.Tip("Ctrl+F opens search; Ctrl+Alt+G goes to a page."),
        )),
        GuidePage("More on the page", listOf(
            Block.Body("Layers keep ink apart: lock one to protect it,\n" +
                "dim it with opacity, or hide it."),
            Block.Body("Pictures come from the gallery or the camera. Text boxes take\n" +
                "typing, and Capture saves a region at high resolution."),
            Block.Body("Recording ties audio to what you write: in the Read tool,\n" +
                "tap your writing to hear that moment."),
            Block.Tip("The Internet panel opens beside the note,\n" +
                "so an AI chat can sit next to your notes."),
        )),
        GuidePage("Make it yours", listOf(
            Block.Body("Settings › Appearance: Material, Glassmorphism or\n" +
                "Liquid glass, light or dark, icons, colours and corners."),
            Block.Body("Settings › Customize toolbar: which tools each bar size shows,\n" +
                "and in what order."),
            Block.Tip("Back up a note, or all of them, from the home screen menu.\n" +
                "Updates arrive in the app when a new release is out."),
            Block.Space(60f),
            Block.Body("That's the tour. Have fun writing!"),
        ), PageBackground.DOT),
    )

    private const val MARGIN_SIDE = 110f
    private const val MARGIN_TOP = 110f
    private const val HEADING_SIZE = 52f
    private const val BODY_SIZE = 30f
    private const val TIP_SIZE = 28f
    private const val HEADING_GAP = 48f
    private const val BLOCK_GAP = 36f
    private const val TIP_PADDING = 22f
    private const val TIP_CORNER = 18f
    private const val INK = 0xFF23252B.toInt()
    /** A pale sticky yellow: reads as a tip on white, lined, grid and dotted paper alike. */
    private const val TIP_FILL = 0xFFFFF4C2.toInt()
}
