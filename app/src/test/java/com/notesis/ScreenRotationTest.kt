package com.notesis

import android.content.pm.ActivityInfo
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenRotationTest {
    @Test fun `a tablet that is naturally upright is held on the side it is on`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, lockedOrientation(Surface.ROTATION_0, landscapeNow = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, lockedOrientation(Surface.ROTATION_90, landscapeNow = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, lockedOrientation(Surface.ROTATION_180, landscapeNow = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, lockedOrientation(Surface.ROTATION_270, landscapeNow = true))
    }

    @Test fun `a tablet that is naturally sideways is held on the side it is on`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, lockedOrientation(Surface.ROTATION_0, landscapeNow = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, lockedOrientation(Surface.ROTATION_90, landscapeNow = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, lockedOrientation(Surface.ROTATION_180, landscapeNow = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, lockedOrientation(Surface.ROTATION_270, landscapeNow = false))
    }
}
