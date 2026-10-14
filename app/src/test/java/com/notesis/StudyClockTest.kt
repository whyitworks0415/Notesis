package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StudyClockTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(seoul).toInstant().toEpochMilli()

    @Test fun studyWithinOneDayStaysOnThatDay() {
        assertEquals(
            listOf("2026-10-14" to 30 * 60_000L),
            splitByDay(at("2026-10-14T21:00:00"), at("2026-10-14T21:30:00"), seoul),
        )
    }

    @Test fun studyPastMidnightCountsOnBothDays() {
        assertEquals(
            listOf("2026-10-14" to 20 * 60_000L, "2026-10-15" to 15 * 60_000L),
            splitByDay(at("2026-10-14T23:40:00"), at("2026-10-15T00:15:00"), seoul),
        )
    }

    @Test fun noTimeIsNoStudy() {
        assertEquals(emptyList<Pair<String, Long>>(), splitByDay(at("2026-10-14T10:00:00"), at("2026-10-14T10:00:00"), seoul))
        assertEquals(emptyList<Pair<String, Long>>(), splitByDay(at("2026-10-14T10:00:00"), at("2026-10-14T09:00:00"), seoul))
    }

    @Test fun alarmStillAheadIsToday() {
        assertEquals(at("2026-10-14T07:30:00"), nextAlarmAt(7, 30, at("2026-10-14T06:00:00"), seoul))
    }

    @Test fun alarmAlreadyPassedIsTomorrow() {
        assertEquals(at("2026-10-15T07:30:00"), nextAlarmAt(7, 30, at("2026-10-14T08:00:00"), seoul))
        // Set at the very minute it names, it waits a day rather than going off at once.
        assertEquals(at("2026-10-15T07:30:00"), nextAlarmAt(7, 30, at("2026-10-14T07:30:00"), seoul))
    }

    @Test fun timerRoundsUpToTheSecondLeft() {
        assertEquals("25:00", formatClock(25 * 60_000L))
        assertEquals("00:01", formatClock(1L))
        assertEquals("00:00", formatClock(0L))
        assertEquals("1:05:09", formatClock((3600 + 5 * 60 + 9) * 1000L))
    }

    @Test fun stopwatchCountsHundredths() {
        assertEquals("01:23.45", formatStopwatch(83_450L))
        assertEquals("1:01:23.45", formatStopwatch(3_683_450L))
    }

    @Test fun elapsedAlwaysShowsHours() {
        assertEquals("0:12:05", formatElapsed(725_999L))
        assertEquals("2:00:00", formatElapsed(7_200_000L))
        assertEquals("0:00:00", formatElapsed(-5L))
    }

    @Test fun studyShortIsHoursAndMinutes() {
        assertEquals("0:00", formatStudyShort(59_999L))
        assertEquals("3:07", formatStudyShort((3 * 60 + 7) * 60_000L))
    }

    @Test fun weekEndsToday() {
        val week = lastSevenDays(LocalDate.of(2026, 10, 14))
        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 10, 8), week.first())
        assertEquals(LocalDate.of(2026, 10, 14), week.last())
    }
}
