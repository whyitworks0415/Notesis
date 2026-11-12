package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttachTest {
    @Test fun `a typed range names pages from 1, each once and in order`() {
        assertEquals(listOf(2), parsePageRange("3", 10))
        assertEquals(listOf(2, 3, 4), parsePageRange("3-5", 10))
        assertEquals(listOf(0, 3, 4, 5, 8), parsePageRange("1, 4-6, 9", 10))
        assertEquals(listOf(2, 3, 4), parsePageRange("5-3", 10))
        assertEquals(listOf(0, 1), parsePageRange("1,2,1", 10))
        assertEquals(listOf(1, 2), parsePageRange(" 2 ~ 3 ", 10))
    }

    @Test fun `a range that names nothing or a page the note lacks does not read`() {
        assertNull(parsePageRange("", 10))
        assertNull(parsePageRange("0", 10))
        assertNull(parsePageRange("11", 10))
        assertNull(parsePageRange("2-12", 10))
        assertNull(parsePageRange("a", 10))
        assertNull(parsePageRange("1-2-3", 10))
        assertNull(parsePageRange("1", 0))
    }

    @Test fun `the sheet's choices stand for their pages`() {
        assertEquals(listOf(4), chatPages(ChatPages.THIS, 4, 10, ""))
        assertEquals((0 until 3).toList(), chatPages(ChatPages.ALL, 0, 3, ""))
        assertEquals(listOf(0, 1), chatPages(ChatPages.SOME, 0, 3, "1-2"))
        assertNull(chatPages(ChatPages.SOME, 0, 3, "9"))
    }

    @Test fun `the handwriting file has each page that was read, and is nothing when none was`() {
        val text = handwritingText("Biology", listOf(0 to "cells", 1 to null, 2 to "  ", 3 to "DNA"))!!
        assertTrue(text.startsWith("Biology\n"))
        assertTrue(text.contains("--- Page 1 ---\ncells"))
        assertTrue(text.contains("--- Page 4 ---\nDNA"))
        assertTrue(!text.contains("Page 2") && !text.contains("Page 3 "))
        assertNull(handwritingText("Biology", listOf(0 to null, 1 to "")))
    }

    @Test fun `a file's name is the title made safe`() {
        assertEquals("Bio 1 - 3 pages.pdf", chatFileName("Bio/1", "3 pages", "pdf"))
        assertEquals("Note.txt", chatFileName("///", "", "txt"))
        assertEquals("생물 정리 - handwriting.txt", chatFileName("생물 정리", "handwriting", "txt"))
    }
}
