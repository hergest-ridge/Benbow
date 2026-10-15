package app.benbow.app

import app.benbow.client.calendar.CalendarEvent
import app.benbow.core.calendar.EventField
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun aTripIsOneBarAcrossItsDaysAndTheRestAreDots() {
        val trip = CalendarEvent("t", "work", "Поездка", "2026-10-17", end = "2026-10-20")
        val day = { n: Int, title: String -> CalendarEvent("$title$n", "mine", title, "2026-10-19") }
        val events = listOf(trip) + (1..4).map { day(it, "Дело") }
        val days = monthGrid(LocalDate(2026, 10, 1)).flatten()
        val marks = dayMarks(days, events) { it.calendar }

        assertEquals(EventBar("work", starts = true, ends = false), marks.getValue("2026-10-17").bar, "it starts on Saturday")
        assertEquals(EventBar("work", starts = false, ends = true), marks.getValue("2026-10-18").bar, "Sunday ends the week's piece")
        assertEquals(EventBar("work", starts = true, ends = false), marks.getValue("2026-10-19").bar, "Monday starts the next")
        assertEquals(EventBar("work", starts = false, ends = true), marks.getValue("2026-10-20").bar)
        assertEquals(null, marks.getValue("2026-10-21").bar)
        assertEquals(listOf("mine", "mine", "mine"), marks.getValue("2026-10-19").dots)
        assertTrue(marks.getValue("2026-10-19").more, "four on one day: three dots and a plus")
        assertEquals(DayMarks(), marks.getValue("2026-10-22"))
    }

    @Test
    fun theSheetSendsOnlyWhatChanged() {
        val event = CalendarEvent("e", "mine", "Врач", "2026-10-14T09:30", description = "Снимки")
        val before = EventDraft.of(event)
        val after = before.copy(title = "Стоматолог", time = "10:00")
        assertEquals(mapOf(EventField.TITLE to "Стоматолог", EventField.START to "2026-10-14T10:00"), after.changes(before))
        assertEquals(emptyMap(), before.changes(before))
        assertFalse(before.copy(title = " ").valid)
        assertFalse(before.copy(endTime = "09:00").valid, "an end before the start")
        assertTrue(before.copy(endTime = "10:15").valid)
        assertEquals("2026-10-14T10:15", before.copy(endTime = "10:15").fields()[EventField.END], "same day, its end time")
    }

    @Test
    fun anOccurrenceOpensItsSeries() {
        // The third Monday's occurrence of a weekly two-day event that began on October 5.
        val occurrence = CalendarEvent(
            "y",
            "mine",
            "Курсы",
            "2026-10-19T19:00",
            end = "2026-10-20T21:00",
            rrule = "FREQ=WEEKLY",
            seriesStart = "2026-10-05T19:00",
        )
        val draft = EventDraft.of(occurrence)
        assertEquals(LocalDate(2026, 10, 5), draft.day)
        assertEquals(LocalDate(2026, 10, 6), draft.endDay)
        assertEquals("2026-10-06T21:00", draft.fields()[EventField.END])
        assertEquals("FREQ=WEEKLY", draft.rrule)
    }
}
