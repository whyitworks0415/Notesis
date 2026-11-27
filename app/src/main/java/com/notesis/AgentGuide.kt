package com.notesis

/*
 * What an agent is told about Notesis before it starts (Todo.md, Q3): the
 * rules it works under and recipes for what owners ask most, so it reaches
 * for the right command the first time instead of reading every note to
 * find its way. The same words go out by four roads: notesis_help, the MCP
 * prompts, the instructions file the Termux setup writes for each agent,
 * and the panel's Copy button. Kept short, since every agent reads it whole.
 */

/** Where the guide sits in an agent's instructions file, so setting up again replaces it and leaves the owner's own lines alone. */
internal const val GUIDE_START = "<!-- notesis:start -->"
internal const val GUIDE_END = "<!-- notesis:end -->"

internal val AGENT_GUIDE = """
# Notesis notes (MCP server "notesis")

Tools: `notesis_help` lists the commands; `notesis_run` runs command lines. The lines of one call are one batch: all are done, or none.

## Rules
- A note open on the tablet - on its own, in a split or in the pop-up panel - takes changes and shows them at once, and is read as it is on screen. While a stroke is being written a change waits a moment (OPEN_ON_SCREEN if it waited too long: try again). A note on screen is not put in the trash.
- Deleting moves a note to the trash. `undo` takes back the latest change.
- The owner may be asked before a change. A refusal ("denied") is their answer: do not send it again.
- Once you know a note's id, use it. A title that fits several notes fails with AMBIGUOUS and lists them; one not found lists likely ones.
- Ask for little at a time: `notes list --brief`, then `note read NOTE --outline`, then `--pages`. A long answer says "truncated" and gives the `next` command.
- A note read before: `note read NOTE --since MODIFIED` answers "unchanged" instead of the note again.
- `preview LINE` says what a line would do without doing it.
- Answer the owner in the language they write to you in. Never make up page numbers you could not read: say so.

## Recipes
Find something: `search WORDS`, then `note read NOTE --pages N --text-only`.

Outline a PDF note (its table of contents):
1. `pdf info NOTE`: does the PDF carry bookmarks, a text layer, printed page numbers?
2. Bookmarks: `toc import-pdf NOTE`, then `toc show NOTE`. Done.
3. No bookmarks: `pdf text NOTE --pages 1-8` to find its contents page, then one batch of
   `toc set NOTE P "TITLE" --pdf --level L`, where P = printed page + printedOffset from `pdf info`.
4. No text layer (a scan): `page image NOTE N` shows a page as a picture.

Summarise a handwritten note: `note read NOTE --text-only`. A page with ink but no text: `page image NOTE PAGE` shows it.

Beside the pages (the whiteboard): `ink add NOTE board --near 3 --path "..."` draws from page 3's corner (x past the page's width is to its right); `note read` gives its ink count under "board".

Write into notes: a Markdown note takes `md append NOTE "TEXT" --under HEADING` or `md replace NOTE OLD NEW`;
a handwritten page takes `text add NOTE PAGE "TEXT" --md`. In TEXT, \n starts a new line.
With --md the text is Markdown and LaTeX: # heading, **bold**, ==mark==, lists, | tables |, ${'$'}x^2${'$'} in a line, ${'$'}${'$'}\frac{a}{b}${'$'}${'$'} on its own.
Its look: `text style NOTE PAGE BOX --size 40 --align center --line 1.4 --bg #FFF59D ...` (`help text style` lists them);
`text list` gives each box's number and look. PAGE board puts text beside the pages (--near 3 --x X --y Y, as for ink).

Organise: `folder list`, `tags list`, then `note mv`, `note tag`, `folder new` in one batch, so one `undo` takes it all back.

Hand a file to the terminal: `export NOTE --format pdf --to shared` puts it in Download/Notesis
(readable in Termux after `termux-setup-storage`, and in Debian under /sdcard/Download/Notesis).
""".trimIndent()

/** A ready request an agent's owner can pick (MCP prompts): its name, what it does, the note it asks for if any, and the words. */
internal class AgentPrompt(val name: String, val title: String, val description: String, val noteArgument: Boolean, val text: (String) -> String)

internal val AGENT_PROMPTS = listOf(
    AgentPrompt("build_toc_from_pdf", "Outline a PDF note", "Sets a PDF note's outline from its bookmarks or its contents page.", true) { note ->
        "Set the outline (table of contents) of the Notesis note \"$note\" from its PDF. Start with `pdf info $note`. " +
            "If the PDF has bookmarks, use `toc import-pdf`. If not, read its contents page with `pdf text`, then set every entry " +
            "in one batch of `toc set ... --pdf` lines, using the printed page offset `pdf info` gives. Show me `toc show` at the end."
    },
    AgentPrompt("summarize_note", "Summarise a note", "Reads a note and sums it up.", true) { note ->
        "Read the Notesis note \"$note\" (`note read $note --outline` first, then the pages in parts with --text-only) and " +
            "summarise it: the main points, then anything left open. Pages with ink and no text: `page image`. Change nothing."
    },
    AgentPrompt("organize_notes", "Organise notes", "Proposes folders and tags for the notes, then files them once allowed.", false) { _ ->
        "Look over my Notesis notes (`notes list --brief`, `folder list`, `tags list`) and propose a tidier set of folders and tags. " +
            "Show me the plan first. Once I agree, make it as one batch of `folder new`, `note mv` and `note tag` lines, so one undo takes it back."
    },
)

/**
 * [existing] (an agent's instructions file, or "" when there is none) with
 * the guide between its markers: put in place of the one there before, or
 * added at the end, the owner's own lines kept as they were.
 */
internal fun withGuide(existing: String, guide: String = AGENT_GUIDE): String {
    val block = "$GUIDE_START\n${guide.trim()}\n$GUIDE_END"
    val start = existing.indexOf(GUIDE_START)
    val end = existing.indexOf(GUIDE_END)
    if (start >= 0 && end > start) return existing.substring(0, start) + block + existing.substring(end + GUIDE_END.length)
    if (existing.isBlank()) return block + "\n"
    return existing.trimEnd('\n') + "\n\n" + block + "\n"
}
