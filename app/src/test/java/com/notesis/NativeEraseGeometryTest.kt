package com.notesis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class NativeEraseGeometryTest {
    companion object {
        @JvmStatic @BeforeClass fun backend() {
            SpatialNative.prepare()
            repeat(200) { if (SpatialNative.ready) return; Thread.sleep(5) }
            assertTrue("Native host bridge is required", SpatialNative.ready)
        }
    }
    @Test fun `JNI cut plans agree with the existing Kotlin geometry on random sweeps`() {
        val random = Random(9421)
        val workspace = PartialEraseWorkspace()
        repeat(1000) {
            val count = random.nextInt(2, 700)
            workspace.prepare(count)
            var x = 0f; var y = 0f
            repeat(count) { i ->
                x += random.nextFloat() * 20 - 10; y += random.nextFloat() * 20 - 10
                workspace.put(i, x, y)
            }
            val ax = random.nextFloat() * 100 - 50; val ay = random.nextFloat() * 100 - 50
            val bx = random.nextFloat() * 100 - 50; val by = random.nextFloat() * 100 - 50
            val reach = random.nextFloat() * 10
            assertEquals(keptRuns(workspace.xs, workspace.ys, count, ax, ay, bx, by, reach),
                workspace.runs(count, ax, ay, bx, by, reach))
        }
    }
    @Test fun `native path actually cuts long lines and rejects invalid buffers`() {
        val count = 512
        val points = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder())
        val out = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder())
        repeat(count) { points.putFloat(it * 0.37f).putFloat(0f) }
        val found = SpatialNative.cutRuns(points, count, 30.17f, -5f, 30.17f, 5f, 2.31f, out)
        assertEquals(2, found)
        val expected = keptRuns(FloatArray(count) { it * 0.37f }, FloatArray(count), count,
            30.17f, -5f, 30.17f, 5f, 2.31f)!!
        assertEquals(expected, List(found) { out.getInt(it * 8) until out.getInt(it * 8 + 4) })
        assertEquals(-2, SpatialNative.cutRuns(ByteBuffer.allocate(count * 8), count, 0f, 0f, 0f, 0f, 1f, out))
        assertEquals(-2, SpatialNative.cutRuns(points, count, 0f, 0f, 0f, 0f, 1f, ByteBuffer.allocateDirect(4)))
    }
    @Test fun `reused coordinate arrays do not leak old samples into a shorter stroke`() {
        val workspace = PartialEraseWorkspace()
        workspace.prepare(1024); repeat(1024) { workspace.put(it, it.toFloat(), 0f) }
        val buffer = workspace.xs
        workspace.prepare(20); repeat(20) { workspace.put(it, it.toFloat(), 0f) }
        assertSame(buffer, workspace.xs)
        assertNull(workspace.runs(20, 500f, 0f, 500f, 10f, 2f))
        assertEquals(keptRuns(workspace.xs, workspace.ys, 20, 9f, -5f, 9f, 5f, 2f),
            workspace.runs(20, 9f, -5f, 9f, 5f, 2f))
    }
}
