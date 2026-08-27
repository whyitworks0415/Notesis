package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's user agent is the whole reason the AI sites would not sign in, so
 * the two tokens that give a WebView away are worth pinning down.
 */
class UserAgentTest {

    private val webViewUa =
        "Mozilla/5.0 (Linux; Android 16; SM-X900 Build/BP1A.250505.005; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.7339.51 " +
            "Mobile Safari/537.36"

    @Test
    fun `mobile drops the webview tells and keeps the device`() {
        val ua = uaFor(webViewUa, desktop = false)
        assertFalse("wv token survived: $ua", ua.contains("wv"))
        assertFalse("Version/4.0 survived: $ua", ua.contains("Version/"))
        assertTrue(ua.contains("Chrome/140.0.7339.51"))
        assertTrue(ua.contains("Mobile Safari"))
        assertTrue(ua.contains("Android 16"))
    }

    @Test
    fun `desktop keeps the chrome build and loses the phone`() {
        val ua = uaFor(webViewUa, desktop = true)
        assertFalse(ua.contains("Android"))
        assertFalse(ua.contains("Mobile"))
        assertTrue(ua.contains("Chrome/140.0.7339.51"))
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.7339.51 Safari/537.36",
            ua,
        )
    }

    @Test
    fun `desktop falls back when there is no chrome version to borrow`() {
        assertTrue(uaFor("something else entirely", desktop = true).contains("Chrome/"))
    }
}
