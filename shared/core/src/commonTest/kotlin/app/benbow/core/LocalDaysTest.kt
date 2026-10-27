package app.benbow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Local days and times without kotlinx-datetime (core runs on the server too). */
class LocalDaysTest {
    @Test
    fun daysReadAndCountByTheCalendar() {
        assertEquals(0L, LocalDays.parse("1970-01-01"))
        assertEquals("2026-10-27", LocalDays.format(LocalDays.parse("2026-10-27")!!))
        assertEquals("2024-03-01", LocalDays.plus("2024-02-28", 2), "2024 is a leap year")
        assertEquals("2026-03-01", LocalDays.plus("2026-02-28", 1))
        assertEquals("2026-12-31", LocalDays.plus("2027-01-01", -1))
        assertEquals(2, LocalDays.weekday("2026-10-27"), "a Tuesday")
        assertEquals(7, LocalDays.weekday("2026-11-01"), "a Sunday")
        for (wrong in listOf("2026-02-29", "2026-13-01", "2026-10-32", "26-10-27", "2026/10/27", "")) {
            assertNull(LocalDays.parse(wrong), wrong)
        }
    }

    @Test
    fun timesAreSecondsFromTheEpochWithOrWithoutSeconds() {
        assertEquals(LocalDays.parse("2026-10-27")!! * 86_400 + 7 * 3600 + 5 * 60, LocalDays.seconds("2026-10-27T07:05"))
        assertEquals(LocalDays.seconds("2026-10-27T07:05")!! + 30, LocalDays.seconds("2026-10-27T07:05:30"))
        for (wrong in listOf("2026-10-27", "2026-10-27T24:00", "2026-10-27T07:60", "2026-10-27 07:05", "2026-10-27T07:05:61")) {
            assertNull(LocalDays.seconds(wrong), wrong)
        }
    }
}
