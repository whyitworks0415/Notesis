package com.notesis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageAttachmentTest {
    @Test fun `an attachment reads back as it was written`() {
        val comment = PageAttachment(PageAttachment.Kind.COMMENT, text = "Check the sign", url = "https://example.com")
        val page = PageAttachment(PageAttachment.Kind.PAGE, text = "Practice page", noteId = "abc", pageIndex = 3)
        assertEquals(comment, PageAttachment.fromJson(JSONObject(comment.toJson().toString())))
        assertEquals(page, PageAttachment.fromJson(JSONObject(page.toJson().toString())))
    }

    @Test fun `nothing to show is not read back`() {
        assertNull(PageAttachment.fromJson(JSONObject().put("kind", "COMMENT").put("text", " ")))
        assertNull(PageAttachment.fromJson(JSONObject().put("kind", "PAGE").put("note", "")))
        assertNull(PageAttachment.fromJson(JSONObject().put("kind", "SOMETHING")))
    }

    @Test fun `a typed address is made one a browser opens`() {
        assertEquals("https://example.com", webAddress("example.com"))
        assertEquals("http://example.com/a", webAddress(" http://example.com/a "))
        assertEquals("", webAddress("  "))
    }
}
