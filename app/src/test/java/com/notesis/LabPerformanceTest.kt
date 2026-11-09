package com.notesis

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LabPerformanceTest {
    @Test fun `growing append tails switch back to the grid instead of scanning indefinitely`() {
        assertTrue(nativeBoundsUseful(10128, 10000, 128))
        assertTrue(nativeBoundsUseful(10256, 10000, 256))
        assertFalse(nativeBoundsUseful(10257, 10000, 257))
        assertTrue(nativeBoundsUseful(640, 512, 128))
        assertFalse(nativeBoundsUseful(641, 512, 129))
        assertFalse(nativeBoundsUseful(70000, 10000, 60000))
    }

    @Test fun `erased tree entries and document order slots are accounted independently`() {
        assertFalse(nativeBoundsUseful(1000, 10000, 0))
        assertTrue(nativeBoundsUseful(1000, 1000, 0))
        assertFalse(nativeBoundsUseful(511, 1000, 0))
        assertFalse(nativeBoundsUseful(1000, 1000, -1))
    }

    @Test fun `experimental tags cannot be parsed as original app releases`() {
        val lab = "lab-v0.90.20-lab.1-211-preview"
        assertTrue(isLabReleaseTag(lab))
        assertNull(AppUpdate.parseTag(lab))
        assertFalse(isLabReleaseTag("v0.90.19-210"))
        assertFalse(isLabReleaseTag("lab-v0.90.20-211"))
    }

    @Test fun `Lab update feed ignores original APKs and accepts only its own channel`() {
        fun release(tag: String) = JSONObject().put("tag_name", tag).put("body", "<!-- update: manual -->")
            .put("assets", org.json.JSONArray().put(JSONObject().put("name", "Notesis-Lab.apk")
                .put("browser_download_url", "https://example.com/lab.apk").put("size", 123)))
        assertNull(AppUpdate.parseRelease(release("v0.90.19-210")))
        val found = AppUpdate.parseRelease(release("lab-v0.90.20-lab.1-211-preview"))!!
        assertEquals("0.90.20-lab.1", found.versionName)
        assertEquals(211L, found.versionCode)
        assertTrue(found.manual)
    }

    @Test fun `efficient glass caps work and original option retains requested blur`() {
        for (amount in listOf(0f, 3f, 12f, 18f, 80f)) {
            assertEquals(amount, labBlurDp(amount, false), 0f)
            assertTrue(labBlurDp(amount, true) in 0f..12f)
        }
    }

    @Test fun `cancelled geometry slices stop before starting another native stroke`() {
        var calls = 0
        var wanted = true
        val result = mapWhileWanted((0..999).toList(), { wanted }) {
            calls++
            if (it == 4) wanted = false
            it
        }
        assertEquals((0..4).toList(), result)
        assertEquals(5, calls)
        assertTrue(mapWhileWanted(listOf(1), { false }) { error("cancelled") }.isEmpty())
    }

    @Test fun `uncancelled geometry slices preserve order while dropping failed builds`() {
        assertEquals(listOf(0, 4, 8), mapWhileWanted((0..4).toList(), { true }) { if (it % 2 == 0) it * 2 else null })
    }
}
