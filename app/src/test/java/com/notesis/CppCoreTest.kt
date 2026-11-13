package com.notesis

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import kotlin.random.Random

class CppCoreTest {
    companion object {
        @JvmStatic @BeforeClass fun load() {
            System.loadLibrary("notesis_core")
            CppCore.prepare()
            repeat(200) { if (CppCore.ready) return; Thread.sleep(5) }
            assertTrue("Native core did not load", CppCore.ready)
        }
    }

    private fun expected(mode: Int, x: FloatArray, y: FloatArray, t: LongArray, width: Float) = when (mode) {
        0 -> penTidyKeepMask(x.size, width, x, y, t)
        1 -> tidiedPenKeepMask(x.size, width, x, y, t)
        else -> spurAndDwellKeepMask(x.size, width, x, y, t)
    }

    @Test fun `all native cleanup modes match the original algorithms on seeded input`() {
        val r = Random(292832)
        repeat(2000) { run ->
            val count = r.nextInt(3, 400)
            var px = r.nextFloat() * 50
            var py = r.nextFloat() * 50
            val x = FloatArray(count) { px += (r.nextFloat() - .5f) * if (run % 2 == 0) .3f else 8f; px }
            val y = FloatArray(count) { py += (r.nextFloat() - .5f) * if (run % 2 == 0) .3f else 8f; py }
            val t = LongArray(count) { it * 4L }
            val width = r.nextFloat() * 20 + .01f
            if (run % 5 == 0 && count > 6) {
                x[0] = 0f; y[0] = 0f; x[1] = 1f; y[1] = 0f; x[2] = .1f; y[2] = 0f
            }
            if (run % 11 == 0) x[count / 2] = Float.NaN
            for (mode in 0..2) {
                assertArrayEquals("run=$run mode=$mode", expected(mode, x, y, t, width),
                    CppCore.tidyMask(x, y, t, count, width, mode))
            }
        }
    }

    @Test fun `timestamps preserve JVM wrapping and input arrays stay unchanged`() {
        val x = floatArrayOf(0f, 0f, 1f, .1f, .2f, 4f, 5f)
        val y = FloatArray(x.size)
        val t = LongArray(x.size) { if (it % 2 == 0) Long.MAX_VALUE else Long.MIN_VALUE }
        val before = x.copyOf()
        for (mode in 0..2) assertArrayEquals(expected(mode, x, y, t, 4f),
            CppCore.tidyMask(x, y, t, x.size, 4f, mode))
        assertArrayEquals(before, x, 0f)
    }

    private fun page(version: Int, count: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x4e545353); out.writeInt(version); out.writeInt(count)
            repeat(count) { i ->
                out.writeInt(0x100 + i); out.writeInt(0xff102030.toInt()); out.writeFloat(2.5f); out.writeFloat(.01f)
                out.writeInt(3); out.write(byteArrayOf(7, 8, 9))
                if (version >= 3) out.writeByte(4)
                if (version >= 4) out.writeByte(128)
            }
        }
    }.toByteArray()

    private fun scan(bytes: ByteArray): IntArray? = CppCore.scanPage(
        ByteBuffer.allocateDirect(bytes.size).apply { put(bytes) }, bytes.size)

    @Test fun `native reader preserves versions metadata order payload and signed nib`() {
        for (version in 1..4) for (count in listOf(0, 1, 47, 5000)) {
            val bytes = page(version, count)
            val index = scan(bytes)!!
            assertEquals(version, index[0]); assertEquals(1 + count * 8, index.size)
            repeat(count) { i ->
                val at = 1 + i * 8
                assertEquals(0x100 + i, index[at])
                assertEquals(0xff102030.toInt(), index[at + 1])
                assertEquals(2.5f, Float.fromBits(index[at + 2]), 0f)
                assertEquals(.01f, Float.fromBits(index[at + 3]), 0f)
                assertArrayEquals(byteArrayOf(7, 8, 9), bytes.copyOfRange(index[at + 4], index[at + 4] + index[at + 5]))
                assertEquals(if (version >= 3) 4 else 0, index[at + 6]); assertEquals(Tool.NO_NIB, index[at + 7])
            }
        }
    }

    @Test fun `truncated invalid and hostile framing returns no partial index`() {
        for (version in 1..4) {
            val good = page(version, 3)
            for (size in 0 until good.size) assertNull(scan(good.copyOf(size)))
            assertNotNull(scan(good + byteArrayOf(99, 88)))
        }
        for ((offset, value) in listOf(0 to 0, 4 to 5, 8 to -1, 8 to Int.MAX_VALUE, 28 to -1, 28 to Int.MAX_VALUE)) {
            val bytes = page(4, 1)
            ByteBuffer.wrap(bytes).putInt(offset, value)
            assertNull(scan(bytes))
        }
    }

    @Test fun `JNI validates arrays counts modes and buffer capacity`() {
        fun invalid(call: () -> Unit) {
            try { call(); fail("Expected invalid argument") } catch (_: IllegalArgumentException) { }
        }
        invalid { CppCore.tidyMask(FloatArray(1), FloatArray(1), LongArray(1), 2, 1f, 0) }
        invalid { CppCore.tidyMask(FloatArray(1), FloatArray(1), LongArray(1), -1, 1f, 0) }
        invalid { CppCore.tidyMask(FloatArray(1), FloatArray(1), LongArray(1), 1, 1f, 3) }
        invalid { CppCore.tidyMask(FloatArray(1), FloatArray(1), LongArray(1), 1, Float.NaN, 0) }
        invalid { CppCore.scanPage(ByteBuffer.allocate(12), 12) }
        invalid { CppCore.scanPage(ByteBuffer.allocateDirect(12), 13) }
        invalid { CppCore.scanPage(ByteBuffer.allocateDirect(12), -1) }
    }

    @Test fun `file adapter preserves input and framing and never rewrites the source`() {
        val file = java.io.File.createTempFile("notesis-core-", ".bin")
        try {
            CppCore.enabled = true
            for (version in 1..4) {
                val bytes = page(version, 20)
                file.writeBytes(bytes)
                val result = CppPageReader.read(file)!!
                assertEquals(version, result.version)
                assertEquals(20, result.records.size)
                for ((i, record) in result.records.withIndex()) {
                    assertEquals(0x100 + i, record.rawTool)
                    assertEquals(2.5f, record.size, 0f)
                    assertEquals(if (version >= 3) 4 else 0, record.layer)
                    assertEquals(Tool.NO_NIB, record.nib)
                    assertArrayEquals(byteArrayOf(7, 8, 9), record.inputs)
                }
                assertArrayEquals(bytes, file.readBytes())
            }
            CppCore.enabled = false
            assertNull(CppPageReader.read(file))
            CppCore.enabled = true
            file.writeBytes(page(4, 1).dropLast(1).toByteArray())
            assertNull(CppPageReader.read(file))
            java.io.RandomAccessFile(file, "rw").use { it.setLength(CppPageReader.MAX_BYTES + 1L) }
            assertNull(CppPageReader.read(file))
        } finally {
            CppCore.enabled = true
            CppPageReader.trim()
            file.delete()
        }
    }

    @Test fun `short stroke and disabled core use the reference path`() {
        val x = FloatArray(16)
        val y = FloatArray(16)
        val t = LongArray(16)
        val expected = BooleanArray(16) { it % 2 == 0 }
        assertSame(expected, CppCore.keepMask(16, 1f, x, y, t, 0) { expected })
        try {
            CppCore.enabled = false
            assertSame(expected, CppCore.keepMask(16, 1f, x, y, t, 0) { expected })
        } finally { CppCore.enabled = true }
    }
}
