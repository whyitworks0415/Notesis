package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class SnipLinkTest {
    @Test
    fun `same note links by page id`() {
        assertEquals("page:p-7", snipLinkTarget("n1", "p-7", 6, "n1"))
    }

    @Test
    fun `another note links by note and page number`() {
        assertEquals("note:n1#6", snipLinkTarget("n1", "p-7", 6, "n2"))
    }
}
