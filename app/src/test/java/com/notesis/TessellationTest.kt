package com.notesis

import org.junit.Assert.assertEquals
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
}
