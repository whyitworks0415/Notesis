package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTuningTest {
    @Test fun `mesh detail scales the error and is held to its range`() {
        assertEquals(0.1f, tunedEpsilon(0.1f, 1f), 1e-7f)
        assertEquals(0.05f, tunedEpsilon(0.1f, 0.5f), 1e-7f)
        assertEquals(0.1f * InkTuning.MESH_DETAIL_RANGE.endInclusive, tunedEpsilon(0.1f, 100f), 1e-6f)
        assertEquals(0.1f * InkTuning.MESH_DETAIL_RANGE.start, tunedEpsilon(0.1f, 0f), 1e-7f)
    }

    @Test fun `by default new ink is folded on the GPU and every stroke is tidied`() {
        // The app's own behaviour, whatever developer mode was left at, once put back.
        assertFalse(InkTuning.foldInBackground)
        assertTrue(InkTuning.tidyAtLift && InkTuning.heartbeat && InkTuning.backgroundRaster)
        assertEquals(1f, InkTuning.meshDetail, 0f)
        assertEquals(1f, InkTuning.tileBudget, 0f)
    }
}
