package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportNamesTest {
    /** A name as a file system that took its bytes for Latin-1 shows it. */
    private fun asLatin1(text: String, charset: String): String =
        String(text.toByteArray(charset(charset)), Charsets.ISO_8859_1)

    @Test fun `a PDF's title is its name without the extension, however it was opened`() {
        assertEquals("2026 수능완성 원본 미적분", importedTitle("2026 수능완성 원본 미적분.pdf"))
        assertEquals("Lecture 3", importedTitle("Lecture 3.PDF"))
        assertEquals("notes.md", importedTitle("notes.md"))
        assertEquals("notes", importedTitle("notes.md", extension = "md"))
    }

    @Test fun `a Korean name read as Latin letters is read back`() {
        val cp949 = asLatin1("2026학년도 논술 가이드북(2025학년도 기출문제 포함)", "MS949")
        assertEquals("2026학년도 논술 가이드북(2025학년도 기출문제 포함)", importedTitle("$cp949.pdf"))
        val utf8 = asLatin1("숭실대 모의논술", "UTF-8")
        assertEquals("숭실대 모의논술", importedTitle("$utf8.pdf"))
    }

    @Test fun `a name that was right stays as it is`() {
        assertEquals("Café menu", importedTitle("Café menu.pdf"))
        assertEquals("Plain name", importedTitle("Plain name.pdf"))
        assertEquals("이미 한글", importedTitle("이미 한글.pdf"))
    }
}
