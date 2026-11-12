package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class TableAsTextTest {
    @Test fun `an old table comes back as its text, a row a line and tabs between cells`() {
        assertEquals("Item\tCost\nPen\t3\nTotal\t=SUM(B2:B2)",
            tableAsText(listOf(listOf("Item", "Cost"), listOf("Pen", "3"), listOf("Total", "=SUM(B2:B2)"))))
    }

    @Test fun `empty cells at the end of a row and empty rows at the end are dropped`() {
        assertEquals("a\tb\nc", tableAsText(listOf(listOf("a", "b", ""), listOf("c", ""), listOf("", ""))))
        assertEquals("", tableAsText(emptyList()))
    }
}
