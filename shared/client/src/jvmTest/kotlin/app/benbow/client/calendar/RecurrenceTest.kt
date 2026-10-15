package app.benbow.client.calendar

import app.benbow.client.calendar.Recurrence.Freq
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecurrenceTest {
    private fun d(text: String) = LocalDate.parse(text)

    private fun dates(rule: String, start: String, from: String, to: String, except: Set<String> = emptySet()) =
        Recurrence.parse(rule)!!.dates(d(start), d(from), d(to), except.map(::d).toSet()).map { it.toString() }

    @Test
    fun weeklyOnTheStartsWeekdayOrOnGivenDays() {
        assertEquals(listOf("2026-10-14", "2026-10-21", "2026-10-28"), dates("FREQ=WEEKLY", "2026-10-14", "2026-10-01", "2026-10-31"))
        assertEquals(
            listOf("2026-10-14", "2026-10-19", "2026-10-21", "2026-10-26", "2026-10-28"),
            dates("FREQ=WEEKLY;BYDAY=MO,WE", "2026-10-14", "2026-10-01", "2026-10-31"),
            "never before the start, even in its own week",
        )
        assertEquals(listOf("2026-10-14", "2026-10-28"), dates("FREQ=WEEKLY;INTERVAL=2", "2026-10-14", "2026-10-01", "2026-10-31"))
    }

    @Test
    fun dailyOnWeekdaysOnly() {
        assertEquals(
            listOf("2026-10-16", "2026-10-19", "2026-10-20"),
            dates("RRULE:FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR", "2026-10-16", "2026-10-16", "2026-10-20"),
        )
    }

    @Test
    fun monthlyByDayOfMonthOrByNthWeekday() {
        assertEquals(
            listOf("2026-01-31", "2026-03-31", "2026-05-31"),
            dates("FREQ=MONTHLY", "2026-01-31", "2026-01-01", "2026-06-30"),
            "months without a 31st are skipped",
        )
        assertEquals(listOf("2026-10-13", "2026-11-10"), dates("FREQ=MONTHLY;BYDAY=2TU", "2026-10-13", "2026-10-01", "2026-11-30"))
        assertEquals(listOf("2026-10-30", "2026-11-27"), dates("FREQ=MONTHLY;BYDAY=-1FR", "2026-10-30", "2026-10-01", "2026-11-30"))
        assertEquals(listOf("2026-10-31", "2026-11-30"), dates("FREQ=MONTHLY;BYMONTHDAY=-1", "2026-10-31", "2026-10-01", "2026-11-30"))
    }

    @Test
    fun yearlyAndTheLeapDay() {
        assertEquals(listOf("2028-02-29", "2032-02-29"), dates("FREQ=YEARLY", "2024-02-29", "2025-01-01", "2032-12-31"))
        assertEquals(listOf("2030-05-09"), dates("FREQ=YEARLY", "1990-05-09", "2030-01-01", "2030-12-31"), "a birthday decades on")
    }

    @Test
    fun countUntilAndSkippedDays() {
        assertEquals(
            listOf("2026-10-01", "2026-10-03"),
            dates("FREQ=DAILY;COUNT=3", "2026-10-01", "2026-10-01", "2026-10-31", except = setOf("2026-10-02")),
            "a skipped day still counts",
        )
        assertEquals(
            listOf("2026-10-05", "2026-10-12"),
            dates("FREQ=WEEKLY;UNTIL=20261012T235959Z", "2026-10-05", "2026-10-01", "2026-12-31"),
        )
        assertEquals(
            listOf("2026-10-26"),
            dates("FREQ=WEEKLY;COUNT=4", "2026-10-05", "2026-10-20", "2026-12-31"),
            "only what falls in the range",
        )
    }

    @Test
    fun rulesOutsideTheSubsetAreLeftAlone() {
        assertNull(Recurrence.parse("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1"))
        assertNull(Recurrence.parse("FREQ=HOURLY"))
        assertNull(Recurrence.parse("FREQ=WEEKLY;BYDAY=2MO"))
        assertNull(Recurrence.parse("FREQ=YEARLY;BYMONTHDAY=1"))
        val rule = Recurrence(Freq.MONTHLY, interval = 2, count = 5, byDay = listOf(Recurrence.Weekday(DayOfWeek.TUESDAY, 2)))
        assertEquals("FREQ=MONTHLY;INTERVAL=2;COUNT=5;BYDAY=2TU", rule.format())
        assertEquals(rule, Recurrence.parse(rule.format()))
        assertEquals(Recurrence(Freq.WEEKLY), Recurrence.parse("FREQ=WEEKLY;WKST=MO"))
    }
}
