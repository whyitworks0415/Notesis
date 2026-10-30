package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun release(code: Long, manual: Boolean) =
        AppRelease("0.$code", code, "https://example.com/a.apk", 1L, null, "", manual = manual)

    @Test
    fun manualReleasesAreMarkedInTheirNotes() {
        assertTrue(AppUpdate.isManual("# Notesis\n<!-- update: manual -->\n- 내용"))
        assertTrue(AppUpdate.isManual("<!--UPDATE:Manual-->"))
        assertFalse(AppUpdate.isManual("# Notesis\n- 수동 업데이트가 아닙니다"))
        assertEquals("내용", AppUpdate.plainNotes("<!-- update: manual -->\n내용"))
    }

    @Test
    fun aManualReleaseIsOfferedOnlyWithAnAutomaticOneDue() {
        val noRecent = { error("not asked") }
        assertTrue(AppUpdate.shouldNotify(release(10, manual = false), 9) { noRecent() })
        assertFalse(AppUpdate.shouldNotify(release(10, manual = false), 10) { noRecent() })
        assertFalse(AppUpdate.shouldNotify(release(10, manual = true), 9) { listOf(release(10, true)) })
        // An automatic 9 was due for someone on 8; the manual 10 is what they get.
        assertTrue(AppUpdate.shouldNotify(release(10, manual = true), 8) { listOf(release(10, true), release(9, false)) })
        assertFalse(AppUpdate.shouldNotify(release(10, manual = true), 9) { listOf(release(10, true), release(9, false)) })
    }
}
