package com.notesis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NoteLockTest {
    @Test
    fun sameSaltSamePasswordMatchesAndNothingElseDoes() {
        val salt = ByteArray(16) { it.toByte() }
        val hash = passwordHash("1234", salt, iterations = 1000)
        assertEquals(32, hash.size)
        assertArrayEquals(hash, passwordHash("1234", salt, iterations = 1000))
        assertFalse(hash.contentEquals(passwordHash("1235", salt, iterations = 1000)))
        assertFalse(hash.contentEquals(passwordHash("1234", ByteArray(16), iterations = 1000)))
    }
}
