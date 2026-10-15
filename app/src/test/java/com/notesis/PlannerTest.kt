package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class PlannerTest {
    @Test fun monthGridStartsOnTheSundayBeforeTheFirst() {
        // October 2026 begins on a Thursday.
        val grid = monthGrid(LocalDate.of(2026, 10, 15))
        assertEquals(42, grid.size)
        assertEquals(LocalDate.of(2026, 9, 27), grid.first())
        assertEquals(DayOfWeek.SUNDAY, grid.first().dayOfWeek)
        assertEquals(LocalDate.of(2026, 10, 1), grid[4])
    }

    @Test fun aMonthBeginningOnSundayStartsOnItsFirst() {
        // February 2026 begins on a Sunday.
        assertEquals(LocalDate.of(2026, 2, 1), monthGrid(LocalDate.of(2026, 2, 10)).first())
    }
}
