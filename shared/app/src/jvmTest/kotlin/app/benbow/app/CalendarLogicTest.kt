package app.benbow.app

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarLogicTest {
    @Test
    fun monthGridStartsOnMondayAndCoversWholeWeeks() {
        val weeks = monthGrid(LocalDate(2026, 10, 1)) // October 2026 starts on a Thursday
        assertEquals(LocalDate(2026, 9, 28), weeks.first().first())
        assertEquals(LocalDate(2026, 11, 1), weeks.last().last())
        assertTrue(weeks.all { it.size == 7 && it.first().dayOfWeek == DayOfWeek.MONDAY })
        assertEquals(5, weeks.size)
    }

    @Test
    fun timesAreNormalized() {
        assertEquals("09:30", parseTime("9:30"))
        assertEquals("18:05", parseTime(" 18.05 "))
        assertEquals(null, parseTime("25:00"))
        assertEquals(null, parseTime("утром"))
    }
}
