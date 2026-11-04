package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Markdown note's card shows its words as they read, not its marks. */
class MarkdownPreviewTest {

    @Test
    fun `marks come out, headings and emphasis keep their places`() {
        val preview = markdownPreview("# Title\nSome **bold** and *it*")
        assertEquals("Title\nSome bold and it", preview.text)
        val heading = preview.spans.first { it.kind == MdKind.HEADING }
        assertEquals("Title", preview.text.substring(heading.start, heading.end))
        val bold = preview.spans.first { it.kind == MdKind.BOLD }
        assertEquals("bold", preview.text.substring(bold.start, bold.end))
        val italic = preview.spans.first { it.kind == MdKind.ITALIC }
        assertEquals("it", preview.text.substring(italic.start, italic.end))
    }

    @Test
    fun `list marks are drawn as bullets`() {
        assertEquals("• one\n• two", markdownPreview("- one\n* two").text)
    }

    @Test
    fun `a long note is cut at a line`() {
        val text = (1..200).joinToString("\n") { "line $it" }
        val preview = markdownPreview(text, maxChars = 50)
        assertTrue(preview.text.length <= 50)
        assertTrue(preview.text.endsWith("line ${preview.text.lines().size}"))
    }
}
