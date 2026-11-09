package com.notesis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabOpenAndBenchTest {
    @Test fun `a page picture is kept only where it can stand in for the page honestly`() {
        assertTrue(pagePictureWanted(strokes = 400, masks = 0, needsGpu = false, translucentLayers = false))
        // Tape hides what is under it; a picture of the ink alone would show it.
        assertFalse(pagePictureWanted(400, masks = 1, needsGpu = false, translucentLayers = false))
        // Pencil graphite comes out blank on a bitmap.
        assertFalse(pagePictureWanted(400, 0, needsGpu = true, translucentLayers = false))
        assertFalse(pagePictureWanted(400, 0, false, translucentLayers = true))
        // A nearly empty page opens at once anyway.
        assertFalse(pagePictureWanted(PAGE_PICTURE_MIN_STROKES - 1, 0, false, false))
    }

    @Test fun `a picture is as wide as the screen, within its budget of pixels`() {
        // An A4 page on a 1200 px wide view: the view's width.
        assertEquals(1200, pagePictureWidth(1200, 1240f, 1754f))
        // A wider screen is held to the ceiling.
        assertEquals(PAGE_PICTURE_MAX_WIDTH, pagePictureWidth(2560, 1240f, 1754f))
        // A very tall page is narrowed to the same count of pixels.
        val width = pagePictureWidth(1400, 1000f, 8000f)
        assertTrue(width.toLong() * (width * 8f).toLong() <= PAGE_PICTURE_MAX_PIXELS + width * 8L)
        assertEquals(0, pagePictureWidth(0, 1240f, 1754f))
        assertEquals(0, pagePictureWidth(1200, 0f, 1754f))
    }

    @Test fun `a kept picture is refused once the page's file is not what it was`() {
        val root = File(System.getProperty("java.io.tmpdir"), "notesis-pictures-${System.nanoTime()}")
        try {
            val pages = File(root, "notes/n1/pages").apply { mkdirs() }
            val bin = File(pages, "p1.bin").apply { writeBytes(ByteArray(64)) }
            val store = PagePictureStore(File(root, "cache"),
                pageFile = { note, page -> File(root, "notes/$note/pages/$page.bin") },
                maskFile = { note, page -> File(root, "notes/$note/pages/$page.mask") })
            // Nothing kept yet.
            assertNull(store.read("n1", "p1"))
            assertEquals(mapOf("p1" to pageFileStamp(bin.length(), bin.lastModified())), store.stamps("n1", listOf("p1", "gone")))
            // A key that does not match the file as it is now: refused before anything is decoded.
            val kept = File(root, "cache/page-pictures/n1").apply { mkdirs() }
            File(kept, "p1.webp").writeBytes(ByteArray(8))
            File(kept, "p1.key").writeText(pageFileStamp(1L, 1L))
            assertNull(store.read("n1", "p1"))
            // Dropped when the page is saved again.
            store.drop("n1", "p1")
            assertFalse(File(kept, "p1.webp").exists())
            assertFalse(File(kept, "p1.key").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `native code is held back after two runs in a row that did not close cleanly`() {
        assertEquals(0, nextUncleanRuns(0, lastRunLeftOpen = false))
        assertEquals(1, nextUncleanRuns(0, lastRunLeftOpen = true))
        assertEquals(2, nextUncleanRuns(1, lastRunLeftOpen = true))
        // A clean run in between does not add to the count; the count itself is cleared on leaving.
        assertEquals(1, nextUncleanRuns(1, lastRunLeftOpen = false))
        assertEquals(0, nextUncleanRuns(-5, lastRunLeftOpen = false))
        assertFalse(nativeHeldBack(0))
        assertFalse(nativeHeldBack(1))
        assertTrue(nativeHeldBack(UNCLEAN_LIMIT))
    }

    @Test fun `the benchmark's strokes stay inside the middle of the page`() {
        val width = 1200f
        val height = 2000f
        for (index in 0 until LabBenchmark.WRITE_STROKES) {
            val line = benchStrokeLine(index, width, height)
            var t = 0f
            while (t <= 1f) {
                assertTrue(line.x(t) in width * 0.1f..width * 0.9f)
                assertTrue(line.y(t) in height * 0.2f..height * 0.8f)
                t += 0.01f
            }
        }
        // Rows repeat after the last one rather than running off the page.
        assertEquals(benchStrokeLine(0, width, height).y(0f), benchStrokeLine(BENCH_ROWS, width, height).y(0f), 0f)
    }

    @Test fun `the report says what was run and what its numbers are not`() {
        val report = benchmarkReport("idle · 60 Hz", "Samsung SM-P610", "0.90.21-lab.5 (216)", "performance hint on",
            written = 30, writing = "wet ink p50 1.0ms", scrolling = "frames p50 16.7ms", opens = "note open: none")
        assertTrue(report.contains("30 of 30 strokes"))
        assertTrue(report.contains("not time to light"))
        assertTrue(report.indexOf("== Writing") < report.indexOf("== Scrolling"))
        assertTrue(report.endsWith("note open: none"))
    }

    @Test fun `an opening reads as its three times and whether a picture stood in`() {
        val opening = NoteOpenClock.Opening(startedAt = 1000L, fromTap = true).apply {
            firstFrameMs = 80; inkShownMs = 95; geometryMs = 640; pictureUsed = true; strokes = 1200
        }
        val line = NoteOpenClock.openingLine(opening)
        assertTrue(line.contains("from tap"))
        assertTrue(line.contains("first frame 80 ms"))
        assertTrue(line.contains("ink shown 95 ms (kept picture)"))
        assertTrue(line.contains("ink built 640 ms"))
        assertTrue(opening.done)
    }
}
