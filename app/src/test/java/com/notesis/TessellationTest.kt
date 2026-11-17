package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom-to-fidelity maths behind crisp strokes when magnified. Runs on the
 * JVM, so it does not need a tablet to say whether this part is right.
 */
class TessellationTest {

    @Test
    fun `buckets settle on powers of two`() {
        assertEquals(1f, tessellationBucket(0.3f))
        assertEquals(1f, tessellationBucket(1f))
        assertEquals(2f, tessellationBucket(1.4f))
        assertEquals(2f, tessellationBucket(2f))
        assertEquals(4f, tessellationBucket(2.1f))
        assertEquals(8f, tessellationBucket(5f))
    }

    @Test
    fun `bucket never exceeds the zoom limit`() {
        assertEquals(MAX_CANVAS_SCALE, tessellationBucket(MAX_CANVAS_SCALE))
        assertEquals(MAX_CANVAS_SCALE, tessellationBucket(1000f))
    }

    @Test
    fun `a bucket always covers the zoom that asked for it`() {
        var scale = 0.1f
        while (scale <= MAX_CANVAS_SCALE) {
            val bucket = tessellationBucket(scale)
            // Tessellating coarser than the zoom in use is the whole defect.
            assertTrue("bucket $bucket too coarse for scale $scale", bucket >= minOf(scale, MAX_CANVAS_SCALE))
            scale += 0.1f
        }
    }

    @Test
    fun `the antialiasing band stays sub-pixel at every zoom`() {
        var scale = 1f
        while (scale <= MAX_CANVAS_SCALE) {
            val epsilon = epsilonFor(tessellationBucket(scale))
            // epsilon is in page units; on screen it is epsilon * scale pixels.
            val onScreenPx = epsilon * scale
            assertTrue(
                "band ${onScreenPx}px at scale $scale",
                onScreenPx <= TESSELLATION_TARGET_PX + 1e-4f,
            )
            scale += 0.1f
        }
    }

    @Test
    fun `zooming out does not ask for finer meshes than needed`() {
        assertTrue(epsilonFor(1f) > epsilonFor(MAX_CANVAS_SCALE))
    }

    @Test
    fun `prediction is suppressed only on a meaningful sharp turn`() {
        assertTrue(shouldSuppressPrediction(10f, 0f, 0f, 10f))
        assertTrue(!shouldSuppressPrediction(10f, 0f, 10f, 2f))
        assertTrue(!shouldSuppressPrediction(0.1f, 0f, 0f, 0.1f))
    }

    @Test
    fun `prediction is clipped to the requested nine millisecond horizon`() {
        assertEquals(0.5f, predictionLeadFraction(100L, 118L, 9L), 0.0001f)
        assertEquals(1f, predictionLeadFraction(100L, 109L, 9L), 0.0001f)
        assertEquals(1f, predictionLeadFraction(100L, 99L, 9L), 0.0001f)
    }

    @Test fun `zoomed out, pages are read in at less detail, down to a quarter`() {
        assertEquals(1f, lodBucket(1f))
        assertEquals(2f, lodBucket(1.5f))
        assertEquals(1f, lodBucket(0.6f))
        assertEquals(0.5f, lodBucket(0.5f))
        assertEquals(0.5f, lodBucket(0.4f))
        assertEquals(0.25f, lodBucket(0.2f))
        assertEquals(0.25f, lodBucket(0.01f))
        assertEquals(lodEpsilonFor(1f) * 4f, lodEpsilonFor(0.25f), 1e-6f)
    }

    @Test fun `coarser meshes are rebuilt, finer ones kept unless far finer`() {
        val want = lodEpsilonFor(1f)
        assertFalse(needsRetessellation(want, want))
        assertTrue(needsRetessellation(want * 2f, want))
        assertFalse(needsRetessellation(want / 2f, want))
        assertFalse(needsRetessellation(want / 4f, want))
        assertTrue(needsRetessellation(want / 8f, want))
    }

    @Test fun `a page serves the buckets its strokes are not rebuilt for`() {
        assertTrue(bucketServes(1f, 1f))
        assertTrue(bucketServes(2f, 1f))
        assertTrue(bucketServes(4f, 1f))
        assertFalse(bucketServes(8f, 1f))
        assertFalse(bucketServes(0.5f, 1f))
        assertFalse(bucketServes(0f, 1f))
        assertFalse(bucketServes(-1f, 1f))
    }
}
