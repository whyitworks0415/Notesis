package com.notesis

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CppLabUpdateTest {
    private fun release(tag: String, apk: String) = JSONObject()
        .put("tag_name", tag).put("prerelease", true)
        .put("assets", JSONArray().put(JSONObject().put("name", apk)
            .put("browser_download_url", "https://example.com/app.apk").put("size", 12)))

    @Test fun `Lab preview is accepted only by Lab parser`() {
        val preview = release("lab-v0.92.1-lab.cpp.1-233-preview", "Notesis-Lab-0.92.1-lab.cpp.1-233.apk")
        val found = AppUpdate.parseLabRelease(preview)!!
        assertEquals(233L, found.versionCode)
        assertEquals("0.92.1-lab.cpp.1", found.versionName)
        assertTrue(found.manual)
        assertNull(AppUpdate.parseRelease(preview))
        assertNull(AppUpdate.parseLabRelease(release("v0.92.1-232", "Notesis-0.92.1-232.apk")))
    }

    @Test fun `mislabelled APK and draft cannot cross installation channels`() {
        val tag = "lab-v0.92.1-lab.cpp.1-233-preview"
        assertNull(AppUpdate.parseLabRelease(release(tag, "Notesis-0.92.1-233.apk")))
        assertNull(AppUpdate.parseLabRelease(release(tag, "Notesis-Lab-233.apk").put("draft", true)))
        assertNull(AppUpdate.parseRelease(release("v0.92.1-999", "Notesis-Lab-999.apk")))
        assertNull(AppUpdate.parseLabRelease(release("lab-v0.92.1-233", "Notesis-Lab-233.apk")))
    }

    @Test fun `mixed feed returns only preview packages for Lab`() {
        val feed = JSONArray().put(release("v0.99.0-999", "Notesis-999.apk"))
            .put(release("lab-v0.92.1-lab.cpp.1-233-preview", "Notesis-Lab-233.apk"))
        val results = AppUpdate.collectReleasePages(lab = true) { feed.toString() }
        assertEquals(listOf(233L), results.map { it.versionCode })
    }
}
