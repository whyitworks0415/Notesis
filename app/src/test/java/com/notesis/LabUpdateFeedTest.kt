package com.notesis

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LabUpdateFeedTest {
    private fun release(code: Int, lab: Boolean) = JSONObject()
        .put("tag_name", if (lab) "lab-v0.90.21-lab.5-$code-preview" else "v0.90.20-$code")
        .put("prerelease", lab)
        .put("assets", JSONArray().put(JSONObject().put("name", "Notesis-Lab.apk")
            .put("browser_download_url", "https://example.com/$code.apk").put("size", 123)))

    @Test fun `a Lab release after 34 normal releases remains discoverable`() {
        val payload = JSONArray()
        repeat(34) { payload.put(release(200 + it, false)) }
        payload.put(release(216, true))
        val requested = ArrayList<Int>()
        val found = AppUpdate.collectReleasePages { page -> requested += page; payload.toString() }
        assertEquals(listOf(216L), found.map { it.versionCode })
        assertEquals(listOf(1), requested)
        assertNull(AppUpdate.newestFor(found, beta = false))
    }

    @Test fun `newer Labs on later pages beat an older Lab on the first page`() {
        val first = JSONArray().put(release(213, true))
        repeat(99) { first.put(release(it, false)) }
        val second = JSONArray().put(release(216, true)).put(release(213, true))
        val requested = ArrayList<Int>()
        val found = AppUpdate.collectReleasePages { page ->
            requested += page
            when (page) { 1 -> first.toString(); 2 -> second.toString(); else -> error("Past the last page") }
        }
        assertEquals(listOf(216L, 213L), found.map { it.versionCode })
        assertEquals(listOf(1, 2), requested)
    }

    @Test fun `a later network failure preserves already discovered releases`() {
        val first = JSONArray().put(release(215, true))
        repeat(99) { first.put(release(it, false)) }
        val found = AppUpdate.collectReleasePages { page -> if (page == 1) first.toString() else null }
        assertEquals(listOf(215L), found.map { it.versionCode })
    }

    @Test fun `malformed entries and pages cannot discard other valid results`() {
        val payload = JSONArray().put("invalid").put(JSONObject().put("draft", true)).put(release(216, true))
        assertEquals(216L, AppUpdate.collectReleasePages { payload.toString() }.single().versionCode)
        assertTrue(AppUpdate.collectReleasePages { "not JSON" }.isEmpty())
    }

    @Test fun `a full history is bounded to five network requests`() {
        val first = JSONArray()
        repeat(100) { first.put(release(it, false)) }
        val requested = ArrayList<Int>()
        assertTrue(AppUpdate.collectReleasePages { page -> requested += page; first.toString() }.isEmpty())
        assertEquals(listOf(1, 2, 3, 4, 5), requested)
    }
}
