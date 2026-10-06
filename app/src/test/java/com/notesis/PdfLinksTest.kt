package com.notesis

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLinksTest {
    @Test
    fun `web link is indexed with the same crop and rotation as internal links`() {
        val file = File.createTempFile("notesis-web-links", ".pdf")
        try {
            PDDocument().use { document ->
                val source = PDPage(PDRectangle(100f, 200f)).apply { rotation = 90 }
                document.addPage(source)
                source.annotations.add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(10f, 20f, 20f, 20f)
                    action = PDActionURI().apply { uri = "https://example.com/study" }
                })
                document.save(file)
            }
            val link = loadPdfPageLinks(file).getValue(0).single()
            assertEquals("https://example.com/study", link.webUrl)
            assertEquals(-1, link.targetPageIndex)
            assertTrue(link.contains(30f * PdfSource.POINTS_TO_WORLD, 20f * PdfSource.POINTS_TO_WORLD))
            assertFalse(link.contains(1f, 1f))
        } finally { file.delete() }
    }
    @Test
    fun `internal goto annotation is indexed in rendered page coordinates`() {
        val file = File.createTempFile("notesis-links", ".pdf")
        try {
            PDDocument().use { document ->
                val source = PDPage(PDRectangle(100f, 200f))
                val target = PDPage(PDRectangle(100f, 200f))
                document.addPage(source)
                document.addPage(target)
                val destination = PDPageFitDestination().apply { page = target }
                source.annotations.add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(10f, 20f, 20f, 20f)
                    action = PDActionGoTo().apply { setDestination(destination) }
                })
                document.save(file)
            }

            val link = loadPdfPageLinks(file).getValue(0).single()
            assertEquals(1, link.targetPageIndex)
            assertTrue(link.contains(20.9f, 334f))
            assertFalse(link.contains(5f, 5f))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `rotated link rectangle follows the displayed page`() {
        val page = PDPage(PDRectangle(100f, 200f)).apply { rotation = 90 }
        val link = pdfRectangleToPage(page, 10f, 20f, 30f, 40f, 4)

        assertEquals(20f * PdfSource.POINTS_TO_WORLD, link.left, 0.01f)
        assertEquals(10f * PdfSource.POINTS_TO_WORLD, link.top, 0.01f)
        assertEquals(40f * PdfSource.POINTS_TO_WORLD, link.right, 0.01f)
        assertEquals(30f * PdfSource.POINTS_TO_WORLD, link.bottom, 0.01f)
        assertEquals(4, link.targetPageIndex)
    }
}
