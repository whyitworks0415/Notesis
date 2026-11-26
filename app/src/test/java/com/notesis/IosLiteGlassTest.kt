package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IosLiteGlassTest {
    @Test fun `the one blur sits between the clear layer and the frost as far as the frost shows`() {
        assertEquals(0f, iosLiteBlurDp(0f, 14f, 0f), 1e-6f)
        assertEquals(14f, iosLiteBlurDp(0f, 14f, 1f), 1e-6f)
        assertEquals(0.6f + (14f - 0.6f) * 0.73f, iosLiteBlurDp(0.6f, 14f, 0.73f), 1e-5f)
        // An opacity past the ends is held to them; a blur never goes below nothing.
        assertEquals(14f, iosLiteBlurDp(0f, 14f, 3f), 1e-6f)
        assertTrue(iosLiteBlurDp(5f, 0f, 2f) >= 0f)
    }

    @Test fun `iOS light is told by its name and looks like iOS`() {
        assertEquals(LiquidStyle.IOS_LITE, LiquidStyle.fromName("IOS_LITE"))
        assertTrue(LiquidStyle.IOS_LITE.looksIos && LiquidStyle.IOS.looksIos)
        assertTrue(!LiquidStyle.NOTESIS.looksIos && !LiquidStyle.MORNYE.looksIos)
    }
}
