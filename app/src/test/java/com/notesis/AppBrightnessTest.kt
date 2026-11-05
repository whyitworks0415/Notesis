package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

/** The app's two brightness controls: the screen's own, and the veil over the app. */
class AppBrightnessTest {
    @Test
    fun `the screen's brightness stays between dim and full, or follows the system`() {
        assertEquals(AppBrightness.FOLLOW_SYSTEM, AppBrightness.screenValue(-1f))
        assertEquals(AppBrightness.MIN_SCREEN, AppBrightness.screenValue(0f))
        assertEquals(0.6f, AppBrightness.screenValue(0.6f))
        assertEquals(1f, AppBrightness.screenValue(3f))
    }

    @Test
    fun `no veil when it is off or full, a dark one at the least`() {
        assertEquals(0, AppBrightness.veilAlpha(AppBrightness.FOLLOW_SYSTEM))
        assertEquals(0, AppBrightness.veilAlpha(1f))
        assertEquals(204, AppBrightness.veilAlpha(0.2f))
    }
}
