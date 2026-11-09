package com.notesis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class NativeBoundsTreeTest {
    companion object {
        @JvmStatic @BeforeClass fun loadBackend() {
            SpatialNative.prepare()
            repeat(200) { if (SpatialNative.ready) return; Thread.sleep(5) }
            assertTrue("Run tools/performance-lab/check-native.sh and provide notesis.hostNativeDir", SpatialNative.ready)
        }
    }

    @Test fun `packed JNI queries match inclusive brute force bounds and keep sparse IDs`() {
        val random = Random(3487)
        val boxes = List(4096) {
            val x = random.nextFloat() * 20000 - 10000
            val y = random.nextFloat() * 20000 - 10000
            floatArrayOf(x, y, x + random.nextFloat() * 100, y + random.nextFloat() * 100)
        }
        val tree = NativeBoundsTree.build(boxes.size) { b -> boxes.forEachIndexed { i, r ->
            r.forEach { b.putFloat(it) }; b.putInt(i * 3)
        } }!!
        repeat(500) {
            val x = random.nextFloat() * 20000 - 10000
            val y = random.nextFloat() * 20000 - 10000
            val right = x + 2000; val bottom = y + 2000
            val expected = boxes.indices.filter { i -> val r = boxes[i]; r[2] >= x && r[0] <= right && r[3] >= y && r[1] <= bottom }.map { it * 3 }
            val count = tree.query(x, y, right, bottom)
            assertTrue(count >= 0)
            assertEquals(expected, (0 until count).map { tree.ids.get(it) }.sorted())
        }
        assertEquals(0, tree.query(1f, 1f, -1f, -1f))
    }

    @Test fun `small pages retain the Kotlin path without native tree buffers`() {
        assertNull(NativeBoundsTree.build(511) { error("must not allocate") })
        assertNull(NativeBoundsTree.build(200001) { error("must not allocate") })
    }

    @Test fun `JNI rejects heap buffers and undersized output without writing them`() {
        assertEquals(-1, SpatialNative.build(ByteBuffer.allocate(512 * 20), 512, ByteBuffer.allocateDirect(512 * 64)))
        assertEquals(-1, SpatialNative.build(ByteBuffer.allocateDirect(20), 512, ByteBuffer.allocateDirect(64)))
        val items = ByteBuffer.allocateDirect(512 * 20).order(ByteOrder.nativeOrder())
        repeat(512) { items.putFloat(0f).putFloat(0f).putFloat(1f).putFloat(1f).putInt(it) }
        val nodes = ByteBuffer.allocateDirect(512 * 64)
        val built = SpatialNative.build(items, 512, nodes)
        assertTrue(built > 0)
        assertEquals(-1, SpatialNative.query(items, 512, nodes, built, 0f, 0f, 1f, 1f, ByteBuffer.allocateDirect(4)))
    }
}
