package com.notesis

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The title a file brought in by its [name] gets: the same whichever way it
 * came - picked in the app or opened from another one, which kept ".pdf" on
 * the title - and readable when the name's Korean was read as Latin letters.
 */
internal fun importedTitle(name: String, extension: String = "pdf"): String =
    repairedFileName(name.trim()).removeSuffix(".$extension").removeSuffix(".${extension.uppercase()}").trim()

/**
 * [name] read back in the encoding it was written in, when a file system or a
 * download handed its bytes over as Latin-1: "2026ÇÐ³âµµ" is "2026학년도"
 * written in CP949, and a UTF-8 name comes out as "Ã­Â•Â™". A name that
 * already has characters past Latin-1, or whose bytes are not valid in
 * either encoding, is left as it is; CP949 is taken only when it yields
 * Hangul, so a real "Café" is not turned into something else.
 */
internal fun repairedFileName(name: String): String {
    if (name.none { it.code in 0x80..0xFF } || name.any { it.code > 0xFF }) return name
    val bytes = ByteArray(name.length) { name[it].code.toByte() }
    strictDecode(bytes, Charsets.UTF_8)?.let { return it }
    for (charsetName in KOREAN_CHARSETS) {
        val charset = runCatching { Charset.forName(charsetName) }.getOrNull() ?: continue
        val decoded = strictDecode(bytes, charset) ?: continue
        return if (decoded.any { it in '가'..'힣' }) decoded else name
    }
    return name
}

private fun strictDecode(bytes: ByteArray, charset: Charset): String? = runCatching {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

/** CP949 by its names on the JVM and on Android, then EUC-KR, which it extends. */
private val KOREAN_CHARSETS = listOf("x-windows-949", "MS949", "windows-949", "EUC-KR")
