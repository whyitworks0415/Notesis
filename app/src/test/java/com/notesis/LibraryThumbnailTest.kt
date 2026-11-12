package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryThumbnailTest {
    @Test fun `a note without a picture gets one on its first save`() {
        assertTrue(thumbnailDue(exists = false, behind = false, stale = false, closing = false))
    }

    @Test fun `a changed first page is drawn again once the interval has passed`() {
        assertTrue(thumbnailDue(exists = true, behind = true, stale = true, closing = false))
        assertFalse(thumbnailDue(exists = true, behind = true, stale = false, closing = false))
    }

    @Test fun `a change skipped while writing is drawn when the note closes`() {
        assertTrue(thumbnailDue(exists = true, behind = true, stale = false, closing = true))
    }

    @Test fun `nothing changed on the first page, nothing is drawn, closing or not`() {
        assertFalse(thumbnailDue(exists = true, behind = false, stale = true, closing = true))
    }

    @Test fun `the warm-up takes as many pictures as fit in three quarters of the cache`() {
        // 480 x 640 x 4 bytes is 1.2 MB a card.
        assertEquals(30, prefetchCount(48L * 1024 * 1024, 480, 4f / 3f))
        assertEquals(0, prefetchCount(0, 480, 4f / 3f))
        assertEquals(0, prefetchCount(48L * 1024 * 1024, 0, 4f / 3f))
        assertEquals(0, prefetchCount(48L * 1024 * 1024, 480, Float.NaN))
    }
}
