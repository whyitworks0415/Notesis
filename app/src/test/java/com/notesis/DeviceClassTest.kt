package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceClassTest {
    @Test fun `the Galaxy Tab S6 Lite runs light`() {
        // Its 4 GB reports as about 3.6 GB; the model alone is enough too.
        assertTrue(DeviceClass.isSlow(lowRam = false, totalMemoryBytes = 3_600_000_000L, model = "SM-P610"))
        assertTrue(DeviceClass.isSlow(lowRam = false, totalMemoryBytes = 0L, model = "SM-P625"))
    }

    @Test fun `a device that says it is low on memory runs light`() {
        assertTrue(DeviceClass.isSlow(lowRam = true, totalMemoryBytes = 8_000_000_000L, model = "Pixel"))
    }

    @Test fun `a tablet with 8 GB keeps every effect`() {
        assertFalse(DeviceClass.isSlow(lowRam = false, totalMemoryBytes = 7_600_000_000L, model = "SM-X710"))
        // Memory not known: not judged slow on that alone.
        assertFalse(DeviceClass.isSlow(lowRam = false, totalMemoryBytes = 0L, model = "SM-X710"))
    }
}
