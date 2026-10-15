package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSamplingTest {
    @Test fun bigPhotoIsDecodedAtAPowerOfTwoStep() {
        // 8000x6000 (48MP) to at least 2048 along the long side: 1/2 gives 4000, 1/4 gives 2000.
        assertEquals(2, sampleSizeFor(8000, 6000, 2048))
        assertEquals(4, sampleSizeFor(8192, 6144, 2048))
    }

    @Test fun smallPictureIsDecodedWhole() {
        assertEquals(1, sampleSizeFor(1200, 800, 2048))
        assertEquals(1, sampleSizeFor(2048, 1000, 2048))
    }

    @Test fun pdfCacheCeilingFollowsTotalMemory() {
        val gb = 1024L * 1024 * 1024
        assertEquals(128L * 1024 * 1024, pdfCacheCeilingFor(2 * gb))
        assertEquals(256L * 1024 * 1024, pdfCacheCeilingFor(4 * gb))
        assertEquals(384L * 1024 * 1024, pdfCacheCeilingFor(8 * gb))
    }
}
