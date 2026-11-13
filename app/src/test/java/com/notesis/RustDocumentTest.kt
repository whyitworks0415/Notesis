package com.notesis

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import kotlin.random.Random

class RustDocumentTest {
    companion object {
        @JvmStatic @BeforeClass fun connect() {
            CppCore.enabled = true
            CppCore.prepare()
            repeat(200) { if (CppCore.ready) return@repeat; Thread.sleep(5) }
            assertTrue("C++ core did not load", CppCore.ready)
            assertTrue("Rust ABI did not connect", RustNative.ready)
        }
    }

    private fun page(version: Int, count: Int) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(NoteStore.MAGIC); out.writeInt(version); out.writeInt(count)
            repeat(count) { i ->
                out.writeInt(0x100 + i); out.writeInt(0xff102030.toInt()); out.writeFloat(2.5f); out.writeFloat(.01f)
                out.writeInt(3); out.write(byteArrayOf(7, 8, 9))
                if (version >= 3) out.writeByte(4)
                if (version >= 4) out.writeByte(Tool.NO_NIB)
            }
        }
    }.toByteArray()

    private inline fun withFile(block: (File) -> Unit) {
        val directory = java.nio.file.Files.createTempDirectory("notesis-문서-📝-").toFile()
        try { block(File(directory, "page.bin")) } finally { directory.deleteRecursively() }
    }
    private fun push(writer: RustPageWriter, i: Int) {
        writer.push(0x100 + i, 0xff102030.toInt(), 2.5f, .01f, byteArrayOf(7, 8, 9, 99), 3, 4, Tool.NO_NIB)
    }

    @Test fun `streaming JNI reader matches the C++ index on every legacy file version`() = withFile { file ->
        for (version in 1..4) {
            val bytes = page(version, 128); file.writeBytes(bytes)
            val index = CppCore.scanPage(ByteBuffer.allocateDirect(bytes.size).apply { put(bytes) }, bytes.size)!!
            RustPageReader.open(file)!!.use { reader ->
                assertEquals(version, reader.version); assertEquals(128, reader.count)
                repeat(reader.count) { i ->
                    val payload = reader.next()!!; val meta = reader.metadata; val at = 1 + i * 8
                    assertArrayEquals(byteArrayOf(7, 8, 9), payload)
                    assertArrayEquals(intArrayOf(index[at], index[at+1], index[at+2], index[at+3], index[at+6], index[at+7]), meta)
                }
                assertNull(reader.next())
            }
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun `native full write and incremental append preserve exact version four bytes`() = withFile { file ->
        RustPageWriter.open(file, 3)!!.use { writer -> repeat(3) { push(writer, it) }; writer.commit() }
        assertArrayEquals(page(4, 3), file.readBytes())
        // A killed old append left bytes after the count's committed prefix.
        file.appendBytes(byteArrayOf(77, 88, 99))
        RustPageWriter.append(file, 3, 5)!!.use { writer -> for (i in 3..4) push(writer, i); writer.commit() }
        assertArrayEquals(page(4, 5), file.readBytes())
    }

    @Test fun `aborted incomplete and mismatched writers leave the committed save intact`() = withFile { file ->
        val before = page(4, 2); file.writeBytes(before)
        RustPageWriter.open(file, 3)!!.use { push(it, 0) }
        assertArrayEquals(before, file.readBytes())
        RustPageWriter.append(file, 2, 4)!!.use { push(it, 2) }
        assertArrayEquals(before, file.readBytes())
        try {
            RustPageWriter.open(file, 3)!!.use { push(it, 0); it.commit() }
            fail("Incomplete replacement committed")
        } catch (_: IOException) { }
        assertArrayEquals(before, file.readBytes())
        assertNull(RustPageWriter.append(file, 1, 3))
        assertArrayEquals(before, file.readBytes())
        assertEquals(1, file.parentFile!!.listFiles()!!.size)
    }

    @Test fun `independent readers can close concurrently without sharing cursor progress`() = withFile { file ->
        file.writeBytes(page(4, 100))
        val pool = Executors.newFixedThreadPool(6)
        try {
            val jobs = (0 until 24).map { pool.submit {
                RustPageReader.open(file)!!.use { reader ->
                    repeat(100) { i -> assertNotNull(reader.next()); assertEquals(0x100 + i, reader.metadata[0]) }
                    assertNull(reader.next())
                }
            } }
            jobs.forEach { it.get() }
        } finally { pool.shutdownNow() }
    }

    @Test fun `rust edit history matches the original semantics including UTF16 selections and clock wrapping`() {
        data class Reference(val limit: Int = 200) {
            val back = ArrayDeque<EditHistory.State>(); val forward = ArrayDeque<EditHistory.State>()
            var lastAt = 0L; var lastGrew: Boolean? = null
            fun record(before: EditHistory.State, after: EditHistory.State, typed: Boolean, now: Long) {
                if (before.text == after.text) return
                val grew = after.text.length > before.text.length
                if (!(typed && lastGrew == grew && now - lastAt < 1000 && back.isNotEmpty())) {
                    back.addLast(before); while (back.size > limit) back.removeFirst()
                }
                forward.clear(); lastAt = now; lastGrew = if (typed) grew else null
            }
            fun move(current: EditHistory.State, redo: Boolean): EditHistory.State? {
                val from = if (redo) forward else back; val to = if (redo) back else forward
                val state = from.removeLastOrNull() ?: return null
                to.addLast(current); lastGrew = null; return state
            }
        }
        val random = Random(44182); val reference = Reference()
        var current = EditHistory.State("한글📝\uD800", 0, 5)
        EditHistory().use { history ->
            repeat(2000) { index ->
                when (random.nextInt(5)) {
                    0, 1, 2 -> {
                        val text = if (index % 7 == 0) current.text else if (random.nextBoolean()) current.text + "가📝" else current.text.dropLast(1)
                        val next = EditHistory.State(text, random.nextInt(text.length + 1), text.length)
                        val typed = random.nextBoolean(); val time = if (index % 13 == 0) Long.MIN_VALUE else index * 200L
                        reference.record(current, next, typed, time); history.record(current, next, typed, time); current = next
                    }
                    else -> {
                        val redo = random.nextBoolean(); val expected = reference.move(current, redo)
                        val actual = if (redo) history.redo(current) else history.undo(current)
                        assertEquals(expected, actual); if (actual != null) current = actual
                    }
                }
                assertEquals(reference.back.isNotEmpty(), history.canUndo)
                assertEquals(reference.forward.isNotEmpty(), history.canRedo)
            }
        }
    }

    @Test fun `closed handles and invalid JNI capacities fail instead of accessing freed memory`() = withFile { file ->
        file.writeBytes(page(4, 1))
        val id = RustNative.openReader(file.absolutePath.toByteArray(Charsets.UTF_8))
        RustNative.closeReader(id); RustNative.closeReader(id)
        try { RustNative.readerInfo(id); fail("Closed reader was accepted") } catch (_: IllegalStateException) { }
        try { RustNative.readNext(id, IntArray(1)); fail("Short metadata accepted") } catch (_: IllegalArgumentException) { }
        val writer = RustNative.openWriter(file.absolutePath.toByteArray(Charsets.UTF_8), 1)
        try {
            try { RustNative.writeNext(writer, 0, -1, 1f, .01f, ByteArray(1), 2, 0, -128); fail("Oversized payload accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { RustNative.abortWriter(writer); RustNative.abortWriter(writer) }
        assertArrayEquals(page(4, 1), file.readBytes())
    }

    @Test fun `resource kinds cannot close each other's ownership token`() = withFile { file ->
        file.writeBytes(page(4, 1))
        val reader = RustNative.openReader(file.absolutePath.toByteArray(Charsets.UTF_8))
        val history = RustNative.newHistory(10)
        try {
            assertNotEquals(reader, history)
            RustNative.closeHistory(reader)
            RustNative.closeReader(history)
            assertEquals(1, RustNative.readerInfo(reader)[1])
            assertEquals(1, RustNative.recordHistory(history, "a", 2, 1, 1, true, 100))
        } finally { RustNative.closeReader(reader); RustNative.closeHistory(history) }
    }

    @Test fun `a hostile saved length fails before allocating its claimed payload`() = withFile { file ->
        val bytes = page(4, 1)
        ByteBuffer.wrap(bytes).putInt(28, Int.MAX_VALUE)
        file.writeBytes(bytes)
        RustPageReader.open(file)!!.use { reader ->
            try { reader.next(); fail("Missing huge payload was accepted") } catch (_: IOException) { }
        }
    }
}
