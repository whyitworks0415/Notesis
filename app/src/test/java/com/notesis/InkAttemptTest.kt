package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InkAttemptTest {
    private fun attempt(text: String, score: Float? = null) =
        InkAttempt(if (text.isEmpty()) emptyList() else listOf(OcrReading(text, "ko")), score)

    @Test fun theLikelierScoreWins() {
        val levelled = attempt("인요", score = 4.2f)
        val written = attempt("안녕하세요", score = 1.3f)
        assertSame(written, betterAttempt(levelled, written))
        assertSame(written, betterAttempt(written, levelled))
    }

    @Test fun withoutScoresTheReadingThatKeepsMoreLettersWins() {
        val levelled = attempt("인요")
        val written = attempt("안녕하세요")
        assertSame(written, betterAttempt(levelled, written))
        // One score alone is not compared with nothing.
        assertSame(written, betterAttempt(attempt("인요", 1f), written))
    }

    @Test fun anEmptyAttemptNeverWins() {
        val some = attempt("안녕")
        assertSame(some, betterAttempt(some, attempt("")))
        assertSame(some, betterAttempt(attempt(""), some))
    }

    @Test fun aTieKeepsTheFirst() {
        val a = attempt("안녕")
        val b = attempt("인녕")
        assertSame(a, betterAttempt(a, b))
    }

    @Test fun twoSyllablesForFiveSyllablesOfInkLookShort() {
        // About 1500 wide and 300 tall: room for some five letters.
        assertTrue(readingLooksShort("인요", 1500f, 300f))
        assertFalse(readingLooksShort("안녕하세요", 1500f, 300f))
        // English letters are narrower than tall: a word is not taken as short.
        assertFalse(readingLooksShort("hello", 900f, 250f))
        // Too short a line to tell.
        assertFalse(readingLooksShort("안", 500f, 300f))
        assertFalse(readingLooksShort("", 500f, 0f))
    }
}
