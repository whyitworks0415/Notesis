package com.notesis

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StableUpdateFeedTest {
    private fun release(code: Int, lab: Boolean) = JSONObject()
        .put("tag_name", if (lab) "lab-v0.90.21-lab.8-$code-preview" else "v0.90.21-$code")
        .put("prerelease", lab)
        .put("assets", JSONArray().put(JSONObject().put("name", if (lab) "Notesis-Lab.apk" else "Notesis.apk")
            .put("browser_download_url", "https://example.com/$code.apk").put("size", 123)))

    @Test fun `a stable release after many Lab previews is offered to ordinary users`() {
        val payload = JSONArray()
        repeat(34) { payload.put(release(200 + it, true)) }
        payload.put(release(221, false))
        val requested = ArrayList<Int>()
        val found = AppUpdate.collectReleasePages { page -> requested += page; payload.toString() }
        assertEquals(listOf(221L), found.map { it.versionCode })
        assertEquals(listOf(1), requested)
        assertEquals(221L, AppUpdate.newestFor(found, beta = false)!!.versionCode)
        assertFalse(found.single().manual)
    }

    @Test fun `a newer stable release on a later page beats the earlier result`() {
        val first = JSONArray().put(release(211, false))
        repeat(99) { first.put(release(it, true)) }
        val second = JSONArray().put(release(221, false)).put(release(211, false))
        val requested = ArrayList<Int>()
        val found = AppUpdate.collectReleasePages { page ->
            requested += page
            when (page) { 1 -> first.toString(); 2 -> second.toString(); else -> error("Past the last page") }
        }
        assertEquals(listOf(221L, 211L), found.map { it.versionCode })
        assertEquals(listOf(1, 2), requested)
    }

    @Test fun `a later network failure preserves already discovered releases`() {
        val first = JSONArray().put(release(211, false))
        repeat(99) { first.put(release(it, true)) }
        val found = AppUpdate.collectReleasePages { page -> if (page == 1) first.toString() else null }
        assertEquals(listOf(211L), found.map { it.versionCode })
    }

    @Test fun `malformed entries and pages cannot discard other valid results`() {
        val payload = JSONArray().put("invalid").put(JSONObject().put("draft", true)).put(release(221, false))
        assertEquals(221L, AppUpdate.collectReleasePages { payload.toString() }.single().versionCode)
        assertTrue(AppUpdate.collectReleasePages { "not JSON" }.isEmpty())
    }

    @Test fun `a history containing only previews is bounded to five network requests`() {
        val first = JSONArray()
        repeat(100) { first.put(release(it, true)) }
        val requested = ArrayList<Int>()
        assertTrue(AppUpdate.collectReleasePages { page -> requested += page; first.toString() }.isEmpty())
        assertEquals(listOf(1, 2, 3, 4, 5), requested)
    }

    @Test fun `a mislabeled Lab APK cannot be offered under a stable tag`() {
        val mislabeled = release(999, true).put("tag_name", "v0.90.21-999")
        assertNull(AppUpdate.parseRelease(mislabeled))
        assertNull(AppUpdate.parseRelease(release(999, true).put("tag_name", "lab-v0.90.21-999")))
    }

    @Test fun `a manual stable build stays restricted to developer enabled copies`() {
        val manual = release(222, false).put("body", "<!-- update: manual -->")
        val found = AppUpdate.collectReleasePages { JSONArray().put(manual).put(release(221, false)).toString() }
        assertEquals(221L, AppUpdate.newestFor(found, beta = false)!!.versionCode)
        assertEquals(222L, AppUpdate.newestFor(found, beta = true)!!.versionCode)
    }
}
