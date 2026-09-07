package com.notesis

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** A deliberately small, immutable model for documents opened read-only. */
internal data class PreviewDocument(
    val title: String,
    val kind: PreviewKind,
    val sections: List<PreviewSection>,
    val notice: String? = null,
)

internal enum class PreviewKind(val label: String) {
    TEXT("텍스트"),
    MARKDOWN("마크다운"),
    WORD("문서"),
    PRESENTATION("프레젠테이션"),
    WORKBOOK("스프레드시트"),
    HANGUL("한글 문서"),
}

internal data class PreviewSection(
    val title: String,
    val blocks: List<PreviewBlock>,
)

internal sealed interface PreviewBlock {
    data class Paragraph(
        val text: String,
        val level: Int = 0,
        val monospace: Boolean = false,
    ) : PreviewBlock

    data class Table(val rows: List<List<String>>) : PreviewBlock
}

internal object OfficeDocumentParser {
    private const val MAX_SECTIONS = 1_000
    private const val MAX_PARAGRAPHS = 20_000
    private const val MAX_ROWS = 10_000
    private const val MAX_COLUMNS = 256
    private const val MAX_CELL_CHARS = 32_000
    private const val MAX_ZIP_ENTRY_BYTES = 24 * 1024 * 1024
    private const val MAX_ZIP_BYTES = 96 * 1024 * 1024

    fun parse(fileName: String, bytes: ByteArray): PreviewDocument {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "txt" -> plainText(fileName, bytes, markdown = false)
            "md", "markdown" -> plainText(fileName, bytes, markdown = true)
            "docx" -> parseDocx(fileName, unzip(bytes))
            "pptx" -> parsePptx(fileName, unzip(bytes))
            "xlsx" -> parseXlsx(fileName, unzip(bytes))
            "hwpx" -> parseHwpx(fileName, unzip(bytes))
            "hwp" -> parseHwp(fileName, CompoundFile(bytes))
            "doc" -> parseDoc(fileName, CompoundFile(bytes))
            "ppt" -> parsePpt(fileName, CompoundFile(bytes))
            "xls" -> parseXls(fileName, CompoundFile(bytes))
            else -> throw UnsupportedDocumentException("지원하지 않는 파일 형식입니다: .$extension")
        }
    }

    private fun plainText(name: String, bytes: ByteArray, markdown: Boolean): PreviewDocument {
        val text = decodeText(bytes)
        val blocks = if (markdown) markdownBlocks(text) else text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split(Regex("\n{2,}"))
            .asSequence()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .take(MAX_PARAGRAPHS)
            .map { PreviewBlock.Paragraph(it) }
            .toList()
        return PreviewDocument(
            title = name,
            kind = if (markdown) PreviewKind.MARKDOWN else PreviewKind.TEXT,
            sections = listOf(PreviewSection("본문", blocks.ifEmpty { listOf(PreviewBlock.Paragraph("빈 문서")) })),
        )
    }

    private fun markdownBlocks(text: String): List<PreviewBlock> {
        val result = mutableListOf<PreviewBlock>()
        val paragraph = StringBuilder()
        val code = StringBuilder()
        var inCode = false
        fun flushParagraph() {
            val value = paragraph.toString().trim()
            if (value.isNotEmpty()) result += PreviewBlock.Paragraph(value)
            paragraph.setLength(0)
        }
        text.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { line ->
            if (result.size >= MAX_PARAGRAPHS) return@forEach
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    result += PreviewBlock.Paragraph(code.toString().trimEnd(), monospace = true)
                    code.setLength(0)
                } else {
                    flushParagraph()
                }
                inCode = !inCode
                return@forEach
            }
            if (inCode) {
                code.appendLine(line)
                return@forEach
            }
            val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
            when {
                heading != null -> {
                    flushParagraph()
                    result += PreviewBlock.Paragraph(
                        heading.groupValues[2],
                        level = heading.groupValues[1].length,
                    )
                }
                line.isBlank() -> flushParagraph()
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                }
            }
        }
        if (inCode && code.isNotEmpty()) result += PreviewBlock.Paragraph(code.toString().trimEnd(), monospace = true)
        flushParagraph()
        return result
    }

    private fun parseDocx(name: String, entries: Map<String, ByteArray>): PreviewDocument {
        val xml = entries.findEntry("word/document.xml")
            ?: throw UnsupportedDocumentException("DOCX 본문을 찾을 수 없습니다.")
        val blocks = parseWordXml(xml)
        return PreviewDocument(
            name,
            PreviewKind.WORD,
            listOf(PreviewSection("본문", blocks.ifEmpty { listOf(PreviewBlock.Paragraph("빈 문서")) })),
            "읽기 전용 · 텍스트와 표를 표시합니다",
        )
    }

    private fun parseWordXml(xml: ByteArray): List<PreviewBlock> {
        val blocks = mutableListOf<PreviewBlock>()
        var paragraph: StringBuilder? = null
        var paragraphStyle = ""
        var table: MutableList<List<String>>? = null
        var row: MutableList<String>? = null
        var cell: StringBuilder? = null
        var captureText = false
        sax(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (element(localName, qName)) {
                    "tbl" -> if (table == null) table = mutableListOf()
                    "tr" -> if (table != null) row = mutableListOf()
                    "tc" -> if (row != null) cell = StringBuilder()
                    "p" -> {
                        paragraph = StringBuilder()
                        paragraphStyle = ""
                    }
                    "pStyle" -> paragraphStyle = attr(attributes, "val") ?: ""
                    "t" -> captureText = true
                    "tab" -> paragraph?.append('\t')
                    "br", "cr" -> paragraph?.append('\n')
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (captureText) paragraph?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (element(localName, qName)) {
                    "t" -> captureText = false
                    "p" -> {
                        val value = paragraph?.toString()?.trim().orEmpty()
                        if (value.isNotEmpty()) {
                            if (cell != null) {
                                if (cell!!.isNotEmpty()) cell!!.append('\n')
                                cell!!.append(value)
                            } else if (blocks.size < MAX_PARAGRAPHS) {
                                val level = Regex("(?i)(heading|title)\\s*([1-6])?")
                                    .find(paragraphStyle)?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                                blocks += PreviewBlock.Paragraph(value, level)
                            }
                        }
                        paragraph = null
                    }
                    "tc" -> {
                        row?.add(cell?.toString().orEmpty().take(MAX_CELL_CHARS))
                        cell = null
                    }
                    "tr" -> {
                        if (row != null && table!!.size < MAX_ROWS) table!!.add(row!!.take(MAX_COLUMNS))
                        row = null
                    }
                    "tbl" -> {
                        val value = table
                        if (!value.isNullOrEmpty()) blocks += PreviewBlock.Table(value)
                        table = null
                    }
                }
            }
        })
        return blocks
    }

    private fun parsePptx(name: String, entries: Map<String, ByteArray>): PreviewDocument {
        val slides = entries.entries
            .filter { it.key.matches(Regex("(?i)ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { numberedName(it.key) }
            .take(MAX_SECTIONS)
            .mapIndexed { index, entry ->
                val paragraphs = parseParagraphXml(entry.value, "p", "t")
                PreviewSection(
                    "슬라이드 ${index + 1}",
                    paragraphs.mapIndexed { textIndex, text ->
                        PreviewBlock.Paragraph(text, if (textIndex == 0) 1 else 0)
                    }.ifEmpty { listOf(PreviewBlock.Paragraph("내용 없음")) },
                )
            }
        if (slides.isEmpty()) throw UnsupportedDocumentException("PPTX 슬라이드를 찾을 수 없습니다.")
        return PreviewDocument(name, PreviewKind.PRESENTATION, slides, "읽기 전용 · 슬라이드의 텍스트를 표시합니다")
    }

    private fun parseHwpx(name: String, entries: Map<String, ByteArray>): PreviewDocument {
        val sections = entries.entries
            .filter { it.key.matches(Regex("(?i)contents/section\\d+\\.xml")) }
            .sortedBy { numberedName(it.key) }
            .take(MAX_SECTIONS)
            .mapIndexed { index, entry ->
                val paragraphs = parseParagraphXml(entry.value, "p", "t")
                PreviewSection(
                    "구역 ${index + 1}",
                    paragraphs.map { PreviewBlock.Paragraph(it) }
                        .ifEmpty { listOf(PreviewBlock.Paragraph("내용 없음")) },
                )
            }
        if (sections.isEmpty()) throw UnsupportedDocumentException("HWPX 본문을 찾을 수 없습니다.")
        return PreviewDocument(name, PreviewKind.HANGUL, sections, "읽기 전용 · 문서의 텍스트를 표시합니다")
    }

    private fun parseParagraphXml(xml: ByteArray, paragraphTag: String, textTag: String): List<String> {
        val values = mutableListOf<String>()
        var paragraph: StringBuilder? = null
        var capture = false
        sax(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (element(localName, qName)) {
                    paragraphTag -> paragraph = StringBuilder()
                    textTag -> capture = true
                    "tab" -> paragraph?.append('\t')
                    "lineBreak", "br" -> paragraph?.append('\n')
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) paragraph?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (element(localName, qName)) {
                    textTag -> capture = false
                    paragraphTag -> {
                        val text = paragraph?.toString()?.trim().orEmpty()
                        if (text.isNotEmpty() && values.size < MAX_PARAGRAPHS) values += text
                        paragraph = null
                    }
                }
            }
        })
        return values
    }

    private data class SheetRef(val name: String, val relation: String)

    private fun parseXlsx(name: String, entries: Map<String, ByteArray>): PreviewDocument {
        val strings = entries.findEntry("xl/sharedStrings.xml")?.let(::parseSharedStrings).orEmpty()
        val relations = entries.findEntry("xl/_rels/workbook.xml.rels")?.let(::parseRelationships).orEmpty()
        val sheetRefs = entries.findEntry("xl/workbook.xml")?.let(::parseSheetRefs).orEmpty()
        val targets = sheetRefs.mapNotNull { sheet ->
            val target = relations[sheet.relation] ?: return@mapNotNull null
            sheet.name to normalizeZipPath(if (target.startsWith('/')) target.drop(1) else "xl/$target")
        }
        val fallback = entries.keys
            .filter { it.matches(Regex("(?i)xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy(::numberedName)
            .mapIndexed { index, path -> "시트 ${index + 1}" to path }
        val selected = (targets.ifEmpty { fallback }).take(MAX_SECTIONS)
        val sections = selected.mapNotNull { (sheetName, path) ->
            val xml = entries.findEntry(path) ?: return@mapNotNull null
            PreviewSection(sheetName, listOf(PreviewBlock.Table(parseSheet(xml, strings))))
        }
        if (sections.isEmpty()) throw UnsupportedDocumentException("Excel 시트를 찾을 수 없습니다.")
        return PreviewDocument(name, PreviewKind.WORKBOOK, sections, "읽기 전용 · 수식은 파일에 저장된 계산값으로 표시합니다")
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var item: StringBuilder? = null
        var capture = false
        sax(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (element(localName, qName)) {
                    "si" -> item = StringBuilder()
                    "t" -> capture = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) item?.append(ch, start, length)
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (element(localName, qName)) {
                    "t" -> capture = false
                    "si" -> {
                        if (result.size < MAX_ROWS * MAX_COLUMNS) result += item.toString().take(MAX_CELL_CHARS)
                        item = null
                    }
                }
            }
        })
        return result
    }

    private fun parseRelationships(xml: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        sax(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (element(localName, qName) == "Relationship") {
                    val id = attr(attributes, "Id")
                    val target = attr(attributes, "Target")
                    if (id != null && target != null) result[id] = target
                }
            }
        })
        return result
    }

    private fun parseSheetRefs(xml: ByteArray): List<SheetRef> {
        val result = mutableListOf<SheetRef>()
        sax(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (element(localName, qName) == "sheet") {
                    val name = attr(attributes, "name") ?: "시트 ${result.size + 1}"
                    val relation = attr(attributes, "id") ?: return
                    result += SheetRef(name, relation)
                }
            }
        })
        return result
    }

    private fun parseSheet(xml: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val cells = sortedMapOf<Int, MutableMap<Int, String>>()
        var rowIndex = 0
        var columnIndex = 0
        var type = ""
        var value = StringBuilder()
        var inline = StringBuilder()
        var captureValue = false
        var captureInline = false
        sax(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (element(localName, qName)) {
                    "row" -> rowIndex = (attr(attributes, "r")?.toIntOrNull()?.minus(1) ?: rowIndex).coerceAtLeast(0)
                    "c" -> {
                        val reference = attr(attributes, "r").orEmpty()
                        columnIndex = reference.takeWhile(Char::isLetter).let(::columnNumber)
                        type = attr(attributes, "t").orEmpty()
                        value = StringBuilder()
                        inline = StringBuilder()
                    }
                    "v" -> captureValue = true
                    "t" -> if (type == "inlineStr") captureInline = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (captureValue) value.append(ch, start, length)
                if (captureInline) inline.append(ch, start, length)
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (element(localName, qName)) {
                    "v" -> captureValue = false
                    "t" -> captureInline = false
                    "c" -> if (rowIndex < MAX_ROWS && columnIndex in 0 until MAX_COLUMNS) {
                        val raw = if (type == "inlineStr") inline.toString() else value.toString()
                        val shown = when (type) {
                            "s" -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            else -> raw
                        }
                        if (shown.isNotEmpty()) cells.getOrPut(rowIndex) { mutableMapOf() }[columnIndex] = shown.take(MAX_CELL_CHARS)
                    }
                }
            }
        })
        if (cells.isEmpty()) return listOf(listOf("빈 시트"))
        val lastRow = cells.lastKey().coerceAtMost(MAX_ROWS - 1)
        val lastColumn = cells.values.maxOfOrNull { it.keys.maxOrNull() ?: 0 }?.coerceAtMost(MAX_COLUMNS - 1) ?: 0
        return (0..lastRow).map { row -> (0..lastColumn).map { column -> cells[row]?.get(column).orEmpty() } }
    }

    private fun parseHwp(name: String, file: CompoundFile): PreviewDocument {
        val header = file.stream("FileHeader") ?: throw UnsupportedDocumentException("올바른 HWP 5.x 파일이 아닙니다.")
        val compressed = header.size > 39 && (header.int32(36) and 1) != 0
        val sectionNames = file.streamNames()
            .filter { it.matches(Regex("(?i)(BodyText/)?Section\\d+")) }
            .sortedBy(::numberedName)
            .take(MAX_SECTIONS)
        val sections = sectionNames.mapIndexed { index, streamName ->
            val source = file.stream(streamName) ?: ByteArray(0)
            val data = if (compressed) inflateRaw(source) else source
            val paragraphs = hwpParagraphs(data)
            PreviewSection(
                "구역 ${index + 1}",
                paragraphs.map { PreviewBlock.Paragraph(it) }
                    .ifEmpty { listOf(PreviewBlock.Paragraph("내용 없음")) },
            )
        }
        if (sections.isEmpty()) throw UnsupportedDocumentException("HWP 본문을 찾을 수 없습니다.")
        return PreviewDocument(name, PreviewKind.HANGUL, sections, "읽기 전용 · HWP 5.x 문서의 텍스트를 표시합니다")
    }

    private fun hwpParagraphs(data: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var offset = 0
        while (offset + 4 <= data.size && result.size < MAX_PARAGRAPHS) {
            val header = data.int32(offset)
            offset += 4
            val tag = header and 0x3FF
            var size = header ushr 20
            if (size == 0xFFF) {
                if (offset + 4 > data.size) break
                size = data.int32(offset)
                offset += 4
            }
            if (size < 0 || offset + size > data.size) break
            if (tag == 67 && size >= 2) {
                val text = String(data, offset, size - size % 2, StandardCharsets.UTF_16LE)
                    .map { if (it == '\t' || it == '\n' || it.code >= 0x20) it else ' ' }
                    .joinToString("")
                    .replace(Regex("[ \\t]+"), " ")
                    .trim()
                if (text.isNotEmpty()) result += text
            }
            offset += size
        }
        return result
    }

    private fun parseDoc(name: String, file: CompoundFile): PreviewDocument {
        val word = file.stream("WordDocument") ?: throw UnsupportedDocumentException("Word 문서 스트림을 찾을 수 없습니다.")
        val tableName = if (word.size > 12 && (word.u16(10) and 0x0200) != 0) "1Table" else "0Table"
        val table = file.stream(tableName)
        val text = if (table != null) extractWordPieces(word, table) else ""
        val fallback = text.ifBlank { extractReadableText(word) }
        val paragraphs = fallback
            .replace('\u0007', '\t')
            .split('\r', '\n')
            .map { cleanBinaryText(it) }
            .filter { it.isNotBlank() }
            .take(MAX_PARAGRAPHS)
            .map { PreviewBlock.Paragraph(it) }
        if (paragraphs.isEmpty()) throw UnsupportedDocumentException("이 DOC 파일에서 표시할 텍스트를 찾지 못했습니다.")
        return PreviewDocument(name, PreviewKind.WORD, listOf(PreviewSection("본문", paragraphs)), "읽기 전용 · 구형 DOC의 텍스트를 표시합니다")
    }

    private fun extractWordPieces(word: ByteArray, table: ByteArray): String {
        if (word.size < 426 || word.u16(0) != 0xA5EC) return ""
        val fcClx = word.int32(418)
        val lcbClx = word.int32(422)
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > table.size) return ""
        var cursor = fcClx
        val end = fcClx + lcbClx
        while (cursor < end && table[cursor].toInt() == 0x01) {
            if (cursor + 3 > end) return ""
            cursor += 3 + table.u16(cursor + 1)
        }
        if (cursor + 5 > end || table[cursor].toInt() != 0x02) return ""
        val plcSize = table.int32(cursor + 1)
        cursor += 5
        if (plcSize < 4 || cursor + plcSize > end) return ""
        val pieces = (plcSize - 4) / 12
        val pcdStart = cursor + (pieces + 1) * 4
        val result = StringBuilder()
        for (index in 0 until pieces) {
            val cpStart = table.int32(cursor + index * 4)
            val cpEnd = table.int32(cursor + (index + 1) * 4)
            val characters = cpEnd - cpStart
            val encodedFc = table.int32(pcdStart + index * 8 + 2)
            val compressed = (encodedFc and 0x40000000) != 0
            val fileOffset = if (compressed) (encodedFc and 0x3FFFFFFF) / 2 else encodedFc and 0x3FFFFFFF
            val byteCount = if (compressed) characters else characters * 2
            if (characters <= 0 || fileOffset < 0 || fileOffset + byteCount > word.size) continue
            result.append(
                if (compressed) decodeLegacy(word.copyOfRange(fileOffset, fileOffset + byteCount))
                else String(word, fileOffset, byteCount, StandardCharsets.UTF_16LE),
            )
        }
        return result.toString()
    }

    private fun parsePpt(name: String, file: CompoundFile): PreviewDocument {
        val stream = file.stream("PowerPoint Document")
            ?: throw UnsupportedDocumentException("PowerPoint 문서 스트림을 찾을 수 없습니다.")
        val slides = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        fun walk(start: Int, end: Int, depth: Int, insideSlide: Boolean) {
            if (depth > 64) return
            var offset = start
            while (offset + 8 <= end && offset + 8 <= stream.size) {
                val options = stream.u16(offset)
                val type = stream.u16(offset + 2)
                val length = stream.int32(offset + 4)
                val body = offset + 8
                val finish = body + length
                if (length < 0 || finish > end || finish > stream.size) break
                if (type == 1006) {
                    if (current.isNotEmpty()) slides += current
                    current = mutableListOf()
                    walk(body, finish, depth + 1, true)
                    offset = finish
                    continue
                }
                if (insideSlide) when (type) {
                    4000 -> cleanBinaryText(String(stream, body, length - length % 2, StandardCharsets.UTF_16LE))
                    4008 -> cleanBinaryText(decodeLegacy(stream.copyOfRange(body, finish)))
                    else -> ""
                }.takeIf { it.isNotBlank() }?.let { current += it }
                if ((options and 0x000F) == 0x000F) walk(body, finish, depth + 1, insideSlide)
                offset = finish
            }
        }
        walk(0, stream.size, 0, false)
        if (current.isNotEmpty()) slides += current
        val sections = slides.take(MAX_SECTIONS).mapIndexed { index, lines ->
            PreviewSection(
                "슬라이드 ${index + 1}",
                lines.take(MAX_PARAGRAPHS).mapIndexed { line, text -> PreviewBlock.Paragraph(text, if (line == 0) 1 else 0) },
            )
        }
        if (sections.isEmpty()) throw UnsupportedDocumentException("이 PPT 파일에서 표시할 텍스트를 찾지 못했습니다.")
        return PreviewDocument(name, PreviewKind.PRESENTATION, sections, "읽기 전용 · 구형 PPT의 텍스트를 표시합니다")
    }

    private fun parseXls(name: String, file: CompoundFile): PreviewDocument {
        val workbook = file.stream("Workbook") ?: file.stream("Book")
            ?: throw UnsupportedDocumentException("Excel 통합 문서 스트림을 찾을 수 없습니다.")
        val records = biffRecords(workbook)
        val boundSheets = records.filter { it.id == 0x0085 }.mapIndexedNotNull { index, record ->
            if (record.data.size < 8) return@mapIndexedNotNull null
            val position = record.data.int32(0)
            val length = record.data[6].toInt() and 0xFF
            val unicode = (record.data[7].toInt() and 1) != 0
            val available = if (unicode) (record.data.size - 8) / 2 else record.data.size - 8
            val count = minOf(length, available)
            val sheetName = if (unicode) String(record.data, 8, count * 2, StandardCharsets.UTF_16LE)
            else decodeLegacy(record.data.copyOfRange(8, 8 + count))
            position to sheetName.ifBlank { "시트 ${index + 1}" }
        }.toMap()
        val sharedStrings = parseBiffSharedStrings(records)
        val sheets = mutableListOf<Pair<String, MutableMap<Int, MutableMap<Int, String>>>>()
        var current: MutableMap<Int, MutableMap<Int, String>>? = null
        records.forEach { record ->
            if (record.id == 0x0809 && record.data.size >= 4 && record.data.u16(2) == 0x0010) {
                current = mutableMapOf()
                sheets += (boundSheets[record.offset] ?: "시트 ${sheets.size + 1}") to current!!
                return@forEach
            }
            val cells = current ?: return@forEach
            fun put(row: Int, column: Int, value: String) {
                if (row in 0 until MAX_ROWS && column in 0 until MAX_COLUMNS && value.isNotEmpty()) {
                    cells.getOrPut(row) { mutableMapOf() }[column] = value.take(MAX_CELL_CHARS)
                }
            }
            val d = record.data
            when (record.id) {
                0x00FD -> if (d.size >= 10) put(d.u16(0), d.u16(2), sharedStrings.getOrNull(d.int32(6)).orEmpty())
                0x0203 -> if (d.size >= 14) put(d.u16(0), d.u16(2), formatNumber(d.double(6)))
                0x027E -> if (d.size >= 10) put(d.u16(0), d.u16(2), formatNumber(decodeRk(d.int32(6))))
                0x0205 -> if (d.size >= 8) put(d.u16(0), d.u16(2), if (d[7].toInt() == 0) if (d[6].toInt() == 0) "FALSE" else "TRUE" else "#ERROR")
                0x0204 -> if (d.size >= 8) {
                    val length = d.u16(6).coerceAtMost(d.size - 8)
                    put(d.u16(0), d.u16(2), decodeLegacy(d.copyOfRange(8, 8 + length)))
                }
                0x0006 -> if (d.size >= 14) {
                    val special = d[12].toInt() == 0xFF && d[13].toInt() == 0xFF
                    if (!special) put(d.u16(0), d.u16(2), formatNumber(d.double(6)))
                }
            }
        }
        val sections = sheets.take(MAX_SECTIONS).map { (sheetName, cells) ->
            val lastRow = (cells.keys.maxOrNull() ?: 0).coerceAtMost(MAX_ROWS - 1)
            val lastColumn = (cells.values.maxOfOrNull { it.keys.maxOrNull() ?: 0 } ?: 0).coerceAtMost(MAX_COLUMNS - 1)
            val rows = if (cells.isEmpty()) listOf(listOf("빈 시트")) else (0..lastRow).map { row ->
                (0..lastColumn).map { column -> cells[row]?.get(column).orEmpty() }
            }
            PreviewSection(sheetName, listOf(PreviewBlock.Table(rows)))
        }
        if (sections.isEmpty()) throw UnsupportedDocumentException("이 XLS 파일에서 표시할 시트를 찾지 못했습니다.")
        return PreviewDocument(name, PreviewKind.WORKBOOK, sections, "읽기 전용 · 구형 XLS의 저장된 셀 값을 표시합니다")
    }

    private data class BiffRecord(val id: Int, val data: ByteArray, val offset: Int)

    private fun biffRecords(bytes: ByteArray): List<BiffRecord> {
        val records = mutableListOf<BiffRecord>()
        var offset = 0
        while (offset + 4 <= bytes.size) {
            val id = bytes.u16(offset)
            val size = bytes.u16(offset + 2)
            if (offset + 4 + size > bytes.size) break
            records += BiffRecord(id, bytes.copyOfRange(offset + 4, offset + 4 + size), offset)
            offset += 4 + size
        }
        return records
    }

    private fun parseBiffSharedStrings(records: List<BiffRecord>): List<String> {
        val sstIndex = records.indexOfFirst { it.id == 0x00FC }
        if (sstIndex < 0) return emptyList()
        val chunks = mutableListOf<ByteArray>()
        chunks += records[sstIndex].data
        var next = sstIndex + 1
        while (next < records.size && records[next].id == 0x003C) chunks += records[next++].data
        // Joining CONTINUE records covers ordinary files. Very large rich strings can put a
        // one-byte encoding flag at a continuation boundary; malformed tails are ignored.
        val data = ByteArray(chunks.sumOf { it.size })
        var copied = 0
        chunks.forEach { chunk -> chunk.copyInto(data, copied); copied += chunk.size }
        if (data.size < 8) return emptyList()
        val unique = data.int32(4).coerceIn(0, MAX_ROWS * MAX_COLUMNS)
        val result = mutableListOf<String>()
        var offset = 8
        repeat(unique) {
            if (offset + 3 > data.size) return@repeat
            val characters = data.u16(offset)
            val flags = data[offset + 2].toInt() and 0xFF
            offset += 3
            val richRuns = if ((flags and 0x08) != 0 && offset + 2 <= data.size) data.u16(offset).also { offset += 2 } else 0
            val extension = if ((flags and 0x04) != 0 && offset + 4 <= data.size) data.int32(offset).also { offset += 4 } else 0
            val unicode = (flags and 0x01) != 0
            val byteCount = characters * if (unicode) 2 else 1
            if (byteCount < 0 || offset + byteCount > data.size) return result
            val value = if (unicode) String(data, offset, byteCount, StandardCharsets.UTF_16LE)
            else decodeLegacy(data.copyOfRange(offset, offset + byteCount))
            result += cleanBinaryText(value).take(MAX_CELL_CHARS)
            offset += byteCount + richRuns * 4 + extension
            if (offset > data.size) return result
        }
        return result
    }

    private fun decodeRk(value: Int): Double {
        val divided = (value and 1) != 0
        val integer = (value and 2) != 0
        val number = if (integer) (value shr 2).toDouble() else {
            java.lang.Double.longBitsToDouble((value.toLong() and 0xFFFFFFFCL) shl 32)
        }
        return if (divided) number / 100.0 else number
    }

    private fun formatNumber(value: Double): String =
        if (value.isFinite() && value == kotlin.math.floor(value) && kotlin.math.abs(value) < Long.MAX_VALUE) value.toLong().toString()
        else value.toString()

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val path = normalizeZipPath(entry.name)
                    val useful = path.endsWith(".xml", ignoreCase = true) ||
                        path.endsWith(".rels", ignoreCase = true)
                    if (useful && path.isNotEmpty() && !path.startsWith("../")) {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var entryBytes = 0
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            total += read
                            if (entryBytes > MAX_ZIP_ENTRY_BYTES || total > MAX_ZIP_BYTES) {
                                throw UnsupportedDocumentException("문서 내부 데이터가 너무 큽니다.")
                            }
                            output.write(buffer, 0, read)
                        }
                        result[path] = output.toByteArray()
                    }
                }
                zip.closeEntry()
            }
        }
        if (result.isEmpty()) throw UnsupportedDocumentException("압축 문서가 비어 있거나 손상되었습니다.")
        return result
    }

    private fun sax(xml: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.parse(org.xml.sax.InputSource(ByteArrayInputStream(xml)))
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.startsWithBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.startsWithBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.startsWithBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            decodeLegacy(bytes)
        }
    }

    private fun decodeLegacy(bytes: ByteArray): String = runCatching {
        String(bytes, charset("MS949"))
    }.getOrElse { String(bytes, Charsets.ISO_8859_1) }

    private fun extractReadableText(bytes: ByteArray): String {
        val unicode = String(bytes, StandardCharsets.UTF_16LE)
        val runs = Regex("[\\p{L}\\p{N}\\p{P}\\p{Zs}\\t\\r\\n]{4,}")
            .findAll(unicode).joinToString("\n") { it.value }
        return if (runs.length >= 8) runs else decodeLegacy(bytes)
    }

    private fun cleanBinaryText(value: String): String = value
        .map { if (it == '\t' || it == '\r' || it == '\n' || it.code >= 0x20) it else ' ' }
        .joinToString("")
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    private fun inflateRaw(bytes: ByteArray): ByteArray = runCatching {
        InflaterInputStream(ByteArrayInputStream(bytes), Inflater(true)).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_ZIP_ENTRY_BYTES) throw UnsupportedDocumentException("HWP 구역이 너무 큽니다.")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }.getOrElse { throw UnsupportedDocumentException("압축된 HWP 본문을 읽지 못했습니다.", it) }

    private fun Map<String, ByteArray>.findEntry(path: String): ByteArray? =
        this[path] ?: entries.firstOrNull { it.key.equals(path, ignoreCase = true) }?.value

    private fun element(localName: String?, qName: String?): String =
        localName?.takeIf(String::isNotEmpty) ?: qName.orEmpty().substringAfter(':')

    private fun attr(attributes: Attributes, name: String): String? {
        for (index in 0 until attributes.length) {
            if (attributes.getLocalName(index).equals(name, true) ||
                attributes.getQName(index).substringAfter(':').equals(name, true)
            ) return attributes.getValue(index)
        }
        return null
    }

    private fun normalizeZipPath(path: String): String {
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) else parts += ".."
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun numberedName(name: String): Int = Regex("(\\d+)(?!.*\\d)")
        .find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun columnNumber(letters: String): Int {
        if (letters.isEmpty()) return 0
        var value = 0
        letters.uppercase(Locale.ROOT).forEach { value = value * 26 + (it - 'A' + 1) }
        return (value - 1).coerceAtLeast(0)
    }
}

internal class UnsupportedDocumentException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Minimal Compound Binary File reader shared by HWP 5 and old Office files.
 * It only exposes named streams and never mutates the source.
 */
internal class CompoundFile(private val bytes: ByteArray) {
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private val rootData: ByteArray

    private data class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        if (bytes.size < 512 || !bytes.startsWithBytes(byteArrayOf(
                0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
            ))) throw UnsupportedDocumentException("구형 문서 컨테이너가 손상되었습니다.")
        sectorSize = 1 shl bytes.u16(30)
        miniSectorSize = 1 shl bytes.u16(32)
        miniCutoff = bytes.int32(56)
        if (sectorSize !in setOf(512, 4096) || miniSectorSize != 64) {
            throw UnsupportedDocumentException("지원하지 않는 문서 컨테이너입니다.")
        }
        val fatSectors = mutableListOf<Int>()
        repeat(109) { index ->
            val sector = bytes.int32(76 + index * 4)
            if (sector >= 0) fatSectors += sector
        }
        var difatSector = bytes.int32(68)
        var remainingDifat = bytes.int32(72).coerceAtMost(1_000_000)
        while (difatSector >= 0 && remainingDifat-- > 0) {
            val sector = sector(difatSector)
            val count = sectorSize / 4 - 1
            repeat(count) { index -> sector.int32(index * 4).takeIf { it >= 0 }?.let(fatSectors::add) }
            difatSector = sector.int32(sectorSize - 4)
        }
        val fatValues = mutableListOf<Int>()
        fatSectors.forEach { fatSector ->
            val sector = sector(fatSector)
            repeat(sectorSize / 4) { fatValues += sector.int32(it * 4) }
        }
        fat = fatValues.toIntArray()
        val directoryStart = bytes.int32(48)
        val directory = chain(directoryStart, fat, false)
        entries = directory.asListOfChunks(128).mapNotNull { raw ->
            val nameBytes = (raw.u16(64) - 2).coerceIn(0, 62)
            val type = raw[66].toInt() and 0xFF
            if (nameBytes <= 0 || type !in 1..5) return@mapNotNull null
            Entry(
                String(raw, 0, nameBytes - nameBytes % 2, StandardCharsets.UTF_16LE),
                type,
                raw.int32(116),
                raw.long64(120),
            )
        }
        val miniFatStart = bytes.int32(60)
        val miniFatCount = bytes.int32(64).coerceAtLeast(0)
        val miniFatBytes = if (miniFatStart >= 0 && miniFatCount > 0) chain(miniFatStart, fat, false) else ByteArray(0)
        miniFat = IntArray(miniFatBytes.size / 4) { miniFatBytes.int32(it * 4) }
        val root = entries.firstOrNull { it.type == 5 }
        rootData = if (root != null && root.start >= 0) chain(root.start, fat, false).copyToSize(root.size) else ByteArray(0)
    }

    fun streamNames(): List<String> = entries.filter { it.type == 2 }.map { it.name }

    fun stream(name: String): ByteArray? {
        val entry = entries.firstOrNull {
            it.type == 2 && (it.name.equals(name, true) || it.name.endsWith("/$name", true))
        } ?: return null
        return if (entry.size in 0 until miniCutoff.toLong() && entry.start >= 0) {
            miniChain(entry.start).copyToSize(entry.size)
        } else {
            chain(entry.start, fat, false).copyToSize(entry.size)
        }
    }

    private fun sector(id: Int): ByteArray {
        val offset = (id.toLong() + 1L) * sectorSize
        if (id < 0 || offset < 0 || offset + sectorSize > bytes.size) {
            throw UnsupportedDocumentException("문서 섹터가 손상되었습니다.")
        }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + sectorSize)
    }

    private fun chain(start: Int, allocation: IntArray, mini: Boolean): ByteArray {
        if (start < 0) return ByteArray(0)
        val output = java.io.ByteArrayOutputStream()
        val seen = mutableSetOf<Int>()
        var current = start
        while (current >= 0 && current < allocation.size && seen.add(current)) {
            val block = if (mini) {
                val offset = current.toLong() * miniSectorSize
                if (offset + miniSectorSize > rootData.size) break
                rootData.copyOfRange(offset.toInt(), offset.toInt() + miniSectorSize)
            } else sector(current)
            if (output.size() + block.size > OfficeDocumentParser.MAX_CONTAINER_BYTES) {
                throw UnsupportedDocumentException("문서 스트림이 너무 큽니다.")
            }
            output.write(block)
            current = allocation[current]
        }
        return output.toByteArray()
    }

    private fun miniChain(start: Int): ByteArray = chain(start, miniFat, true)

    private fun ByteArray.copyToSize(size: Long): ByteArray {
        val end = size.coerceIn(0, this.size.toLong()).toInt()
        return copyOf(end)
    }
}

private const val MAX_CONTAINER_BYTES_FALLBACK = 128 * 1024 * 1024
private val OfficeDocumentParser.MAX_CONTAINER_BYTES: Int get() = MAX_CONTAINER_BYTES_FALLBACK

private fun ByteArray.asListOfChunks(size: Int): List<ByteArray> =
    (0 until this.size / size).map { copyOfRange(it * size, it * size + size) }

private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.int32(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.long64(offset: Int): Long =
    ByteBuffer.wrap(this, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long

private fun ByteArray.double(offset: Int): Double =
    ByteBuffer.wrap(this, offset, 8).order(ByteOrder.LITTLE_ENDIAN).double
