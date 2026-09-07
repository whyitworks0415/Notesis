package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeDocumentParserTest {
    @Test
    fun markdownKeepsHeadingsAndCode() {
        val parsed = OfficeDocumentParser.parse(
            "readme.md",
            "# 제목\n\n본문입니다.\n\n```kotlin\nval answer = 42\n```".toByteArray(),
        )

        assertEquals(PreviewKind.MARKDOWN, parsed.kind)
        val blocks = parsed.sections.single().blocks
        assertEquals(1, (blocks[0] as PreviewBlock.Paragraph).level)
        assertTrue((blocks[2] as PreviewBlock.Paragraph).monospace)
    }

    @Test
    fun docxReadsParagraphsHeadingsAndTableCells() {
        val bytes = zip(
            "word/document.xml" to """
                <w:document xmlns:w="urn:w"><w:body>
                  <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>보고서</w:t></w:r></w:p>
                  <w:tbl><w:tr>
                    <w:tc><w:p><w:r><w:t>이름</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>값</w:t></w:r></w:p></w:tc>
                  </w:tr></w:tbl>
                </w:body></w:document>
            """.trimIndent(),
        )

        val parsed = OfficeDocumentParser.parse("report.docx", bytes)

        assertEquals(PreviewKind.WORD, parsed.kind)
        val heading = parsed.sections.single().blocks[0] as PreviewBlock.Paragraph
        assertEquals("보고서", heading.text)
        assertEquals(1, heading.level)
        val table = parsed.sections.single().blocks[1] as PreviewBlock.Table
        assertEquals(listOf("이름", "값"), table.rows.single())
    }

    @Test
    fun pptxOrdersSlidesNumerically() {
        val bytes = zip(
            "ppt/slides/slide10.xml" to slide("열 번째"),
            "ppt/slides/slide2.xml" to slide("두 번째"),
            "ppt/slides/slide1.xml" to slide("첫 번째"),
        )

        val parsed = OfficeDocumentParser.parse("deck.pptx", bytes)

        assertEquals(3, parsed.sections.size)
        assertEquals("첫 번째", (parsed.sections[0].blocks[0] as PreviewBlock.Paragraph).text)
        assertEquals("두 번째", (parsed.sections[1].blocks[0] as PreviewBlock.Paragraph).text)
        assertEquals("열 번째", (parsed.sections[2].blocks[0] as PreviewBlock.Paragraph).text)
    }

    @Test
    fun xlsxResolvesSheetNamesSharedStringsAndBooleans() {
        val bytes = zip(
            "xl/workbook.xml" to """
                <workbook xmlns:r="urn:r"><sheets><sheet name="성적" r:id="rId1"/></sheets></workbook>
            """.trimIndent(),
            "xl/_rels/workbook.xml.rels" to """
                <Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>
            """.trimIndent(),
            "xl/sharedStrings.xml" to """
                <sst><si><t>학생</t></si><si><t>민지</t></si></sst>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet><sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>100</v></c></row>
                  <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2" t="b"><v>1</v></c></row>
                </sheetData></worksheet>
            """.trimIndent(),
        )

        val parsed = OfficeDocumentParser.parse("scores.xlsx", bytes)

        assertEquals("성적", parsed.sections.single().title)
        val rows = (parsed.sections.single().blocks.single() as PreviewBlock.Table).rows
        assertEquals(listOf("학생", "100"), rows[0])
        assertEquals(listOf("민지", "TRUE"), rows[1])
    }

    @Test
    fun hwpxCombinesTextRunsIntoParagraphs() {
        val bytes = zip(
            "Contents/section0.xml" to """
                <hp:section xmlns:hp="urn:hp"><hp:p><hp:run><hp:t>안녕</hp:t></hp:run><hp:run><hp:t>하세요</hp:t></hp:run></hp:p></hp:section>
            """.trimIndent(),
        )

        val parsed = OfficeDocumentParser.parse("sample.hwpx", bytes)

        assertEquals(PreviewKind.HANGUL, parsed.kind)
        assertEquals("안녕하세요", (parsed.sections.single().blocks.single() as PreviewBlock.Paragraph).text)
    }

    @Test
    fun hwp5ReadsParagraphTextFromCompoundFile() {
        val header = ByteArray(4_096)
        val text = "한글 바이너리 문서".toByteArray(Charsets.UTF_16LE)
        val section = ByteArray(4_096)
        section.putInt(0, (text.size shl 20) or 67)
        text.copyInto(section, 4)

        val parsed = OfficeDocumentParser.parse(
            "sample.hwp",
            compound("FileHeader" to header, "Section0" to section),
        )

        assertEquals("한글 바이너리 문서", (parsed.sections.single().blocks.single() as PreviewBlock.Paragraph).text)
    }

    @Test
    fun oldPptReadsTextAtom() {
        val text = "구형 파워포인트".toByteArray(Charsets.UTF_16LE)
        val atom = ByteArray(8 + text.size).also {
            it.putShort(2, 4_000)
            it.putInt(4, text.size)
            text.copyInto(it, 8)
        }
        val stream = ByteArray(4_096).also {
            it.putShort(0, 0x000F)
            it.putShort(2, 1_006)
            it.putInt(4, atom.size)
            atom.copyInto(it, 8)
        }

        val parsed = OfficeDocumentParser.parse("sample.ppt", compound("PowerPoint Document" to stream))

        assertEquals("구형 파워포인트", (parsed.sections.single().blocks.single() as PreviewBlock.Paragraph).text)
    }

    @Test
    fun oldDocReadsUnicodePieceTable() {
        val value = "구형 워드 문서\r둘째 문단"
        val encoded = value.toByteArray(Charsets.UTF_16LE)
        val word = ByteArray(4_096).also {
            it.putShort(0, 0xA5EC)
            it.putInt(418, 0)
            it.putInt(422, 21)
            encoded.copyInto(it, 512)
        }
        val table = ByteArray(4_096).also {
            it[0] = 0x02
            it.putInt(1, 16)
            it.putInt(5, 0)
            it.putInt(9, value.length)
            it.putInt(15, 512)
        }

        val parsed = OfficeDocumentParser.parse(
            "sample.doc",
            compound("WordDocument" to word, "0Table" to table),
        )

        assertEquals(2, parsed.sections.single().blocks.size)
        assertEquals("구형 워드 문서", (parsed.sections.single().blocks.first() as PreviewBlock.Paragraph).text)
    }

    @Test
    fun oldXlsReadsLabelAndNumberCells() {
        fun record(id: Int, data: ByteArray) = ByteArray(4 + data.size).also {
            it.putShort(0, id)
            it.putShort(2, data.size)
            data.copyInto(it, 4)
        }
        val labelText = "학생".toByteArray(charset("MS949"))
        val label = ByteArray(8 + labelText.size).also {
            it.putShort(0, 0); it.putShort(2, 0); it.putShort(6, labelText.size)
            labelText.copyInto(it, 8)
        }
        val number = ByteArray(14).also {
            it.putShort(0, 0); it.putShort(2, 1); it.putDouble(6, 100.0)
        }
        val bof = ByteArray(4).also { it.putShort(0, 0x0600); it.putShort(2, 0x0010) }
        val payload = record(0x0809, bof) + record(0x0204, label) + record(0x0203, number)
        val stream = ByteArray(4_096).also { payload.copyInto(it) }

        val parsed = OfficeDocumentParser.parse("sample.xls", compound("Workbook" to stream))

        val rows = (parsed.sections.single().blocks.single() as PreviewBlock.Table).rows
        assertEquals(listOf("학생", "100"), rows.single())
    }

    @Test(expected = UnsupportedDocumentException::class)
    fun rejectsUnknownExtension() {
        OfficeDocumentParser.parse("archive.zip", ByteArray(0))
    }

    private fun slide(text: String) = """
        <p:sld xmlns:p="urn:p" xmlns:a="urn:a"><p:cSld><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:cSld></p:sld>
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    /** Builds a tiny version-3 Compound Binary File with regular 4KB streams. */
    private fun compound(vararg streams: Pair<String, ByteArray>): ByteArray {
        require(streams.size <= 3)
        require(streams.all { it.second.size == 4_096 })
        val sectorsPerStream = 8
        val fatSector = 1 + streams.size * sectorsPerStream
        val bytes = ByteArray((fatSector + 2) * 512) { 0 }
        val header = bytes
        byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        ).copyInto(header)
        header.putShort(24, 0x003E)
        header.putShort(26, 0x0003)
        header.putShort(28, 0xFFFE)
        header.putShort(30, 9)
        header.putShort(32, 6)
        header.putInt(44, 1)
        header.putInt(48, 0)
        header.putInt(56, 4_096)
        header.putInt(60, -2)
        header.putInt(64, 0)
        header.putInt(68, -2)
        header.putInt(72, 0)
        repeat(109) { header.putInt(76 + it * 4, -1) }
        header.putInt(76, fatSector)

        fun directoryEntry(index: Int, name: String, type: Int, start: Int, size: Long) {
            val offset = 512 + index * 128
            val encodedName = (name + '\u0000').toByteArray(Charsets.UTF_16LE)
            encodedName.copyInto(bytes, offset)
            bytes.putShort(offset + 64, encodedName.size)
            bytes[offset + 66] = type.toByte()
            bytes.putInt(offset + 68, -1)
            bytes.putInt(offset + 72, -1)
            bytes.putInt(offset + 76, -1)
            bytes.putInt(offset + 116, start)
            bytes.putLong(offset + 120, size)
        }
        directoryEntry(0, "Root Entry", 5, -2, 0)
        streams.forEachIndexed { index, (name, data) ->
            val start = 1 + index * sectorsPerStream
            directoryEntry(index + 1, name, 2, start, data.size.toLong())
            data.copyInto(bytes, (start + 1) * 512)
        }

        val fatOffset = (fatSector + 1) * 512
        repeat(128) { bytes.putInt(fatOffset + it * 4, -1) }
        bytes.putInt(fatOffset, -2)
        streams.indices.forEach { index ->
            val start = 1 + index * sectorsPerStream
            repeat(sectorsPerStream - 1) { sector -> bytes.putInt(fatOffset + (start + sector) * 4, start + sector + 1) }
            bytes.putInt(fatOffset + (start + sectorsPerStream - 1) * 4, -2)
        }
        bytes.putInt(fatOffset + fatSector * 4, -3)
        return bytes
    }

    private fun ByteArray.putShort(offset: Int, value: Int) {
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    }

    private fun ByteArray.putInt(offset: Int, value: Int) {
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun ByteArray.putLong(offset: Int, value: Long) {
        ByteBuffer.wrap(this, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
    }

    private fun ByteArray.putDouble(offset: Int, value: Double) {
        ByteBuffer.wrap(this, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value)
    }
}
