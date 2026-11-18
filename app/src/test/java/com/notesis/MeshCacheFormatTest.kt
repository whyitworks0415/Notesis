package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteOrder

/** The cache layout ink_batch.cpp reads (see its host checks for the reading side). */
class MeshCacheFormatTest {
    @Test fun `header then each mesh, little-endian, ending on four bytes`() {
        val bytes = meshCacheBytes(42L, listOf(
            MeshRecord(0xff112233.toInt(), floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), shortArrayOf(0, 1, 2)),
            MeshRecord(0xff000000.toInt(), floatArrayOf(5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f), shortArrayOf(0, 1, 2, 0, 2, 3)),
        )).order(ByteOrder.LITTLE_ENDIAN)
        // 24 header + (28 + 3*8 + 3*2 + 2 padding) + (28 + 4*8 + 6*2)
        assertEquals(24 + 60 + 72, bytes.remaining())
        assertEquals(MESH_MAGIC, bytes.getInt(0))
        assertEquals(MESH_VERSION, bytes.getInt(4))
        assertEquals(42L, bytes.getLong(8))
        assertEquals(2, bytes.getInt(16))
        // First mesh: colour, bounds, counts.
        assertEquals(0xff112233.toInt(), bytes.getInt(24))
        assertEquals(0f, bytes.getFloat(28), 0f)
        assertEquals(10f, bytes.getFloat(36), 0f)
        assertEquals(3, bytes.getInt(44))
        assertEquals(3, bytes.getInt(48))
        assertEquals(10f, bytes.getFloat(52 + 8), 0f)
        assertEquals(2.toShort(), bytes.getShort(52 + 24 + 4))
        assertEquals(0.toShort(), bytes.getShort(52 + 24 + 6))   // the padding
        // Second mesh starts on the four-byte boundary after it.
        assertEquals(0xff000000.toInt(), bytes.getInt(84))
        assertEquals(5f, bytes.getFloat(88), 0f)
        assertEquals(12f, bytes.getFloat(100), 0f)
        assertEquals(4, bytes.getInt(104))
        assertEquals(6, bytes.getInt(108))
    }

    @Test fun `no meshes is a header alone`() {
        val bytes = meshCacheBytes(7L, emptyList()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, bytes.remaining())
        assertEquals(0, bytes.getInt(16))
    }
}
