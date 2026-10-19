package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppUpdateTest {
    @Test
    fun releaseTagsCarryNameAndCode() {
        assertEquals("0.45.2" to 113L, AppUpdate.parseTag("v0.45.2-113"))
        assertEquals("1.0.0-beta" to 7L, AppUpdate.parseTag("v1.0.0-beta-7"))
        assertEquals("0.9" to 12L, AppUpdate.parseTag("0.9-12"))
    }

    @Test
    fun tagsWithoutACodeAreNotOurs() {
        assertNull(AppUpdate.parseTag("v0.45.2"))
        assertNull(AppUpdate.parseTag("latest"))
        assertNull(AppUpdate.parseTag(""))
    }

    @Test
    fun notesLoseTheirMarkdownAndRepeatedTitle() {
        val notes = AppUpdate.plainNotes(
            """
            # Notesis 0.45.3 (114)

            요약입니다.

            ## 업데이트
            - **설정**에서 `확인`을 누릅니다.
              - 안쪽 항목
            """.trimIndent(),
        )
        assertEquals("요약입니다.\n\n업데이트\n• 설정에서 확인을 누릅니다.\n  • 안쪽 항목", notes)
    }

    @Test
    fun everyLicenseTheScreenNamesIsBundled() {
        val assets = File("src/main/assets")
        OPEN_SOURCE_COMPONENTS.flatMap { it.texts }.distinct().forEach { path ->
            assertTrue("missing asset $path", File(assets, path).isFile)
        }
    }
}
