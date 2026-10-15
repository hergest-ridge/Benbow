package app.benbow.client.calendar

import app.benbow.client.calendar.db.CalendarDb
import app.benbow.core.calendar.CalendarKind
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/** Calendars with colors and checkboxes, repeats and the calendar's trash (7.3b). */
class CalendarLayerTest {
    private var time = 1_000L
    private val calendar =
        CalendarRepository(CalendarDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema)), { time++ })

    private suspend fun titles(from: String = "2026-10-01", to: String = "2026-10-31") = calendar.events(from, to).first().map { it.title }

    @Test
    fun calendarsHaveNamesColorsCountsAndCheckboxes() = runTest {
        val work = calendar.createCalendar("Работа", "storm")
        val anniversaries = calendar.createCalendar("Годовщины", "blossom")
        calendar.createEvent("2026-10-14", "Врач", null)
        calendar.createEvent("2026-10-15", "Созвон", "10:00", calendar = work)

        val list = calendar.calendars().first()
        assertEquals(listOf(MY_CALENDAR, anniversaries, work), list.map { it.id }, "the built-in first, then by name")
        assertEquals(CalendarItem(MY_CALENDAR, null, MY_CALENDAR_COLOR, visible = true, events = 1), list[0])
        assertEquals(CalendarItem(work, "Работа", "storm", visible = true, events = 1), list[2])

        calendar.setVisible(work, false)
        assertEquals(listOf("Врач"), titles(), "a hidden calendar's events leave the grid")
        assertEquals(false, calendar.calendars().first().first { it.id == work }.visible)
        calendar.setVisible(work, true)

        calendar.updateCalendar(MY_CALENDAR, name = "Личное", color = "coral")
        assertEquals(CalendarItem(MY_CALENDAR, "Личное", "coral", visible = true, events = 1), calendar.calendars().first()[0])
    }

    @Test
    fun aDeletedCalendarTakesItsEventsToTheTrashAndBack() = runTest {
        val work = calendar.createCalendar("Работа", "storm")
        calendar.createEvent("2026-10-15", "Созвон", "10:00", calendar = work)
        calendar.deleteCalendar(work)
        calendar.deleteCalendar(MY_CALENDAR) // the built-in one can't go
        assertEquals(listOf(MY_CALENDAR), calendar.calendars().first().map { it.id })
        assertEquals(listOf("Работа"), calendar.trashedCalendars().first().map { it.name })
        assertEquals(emptyList(), titles())
        calendar.restoreCalendar(work)
        assertEquals(listOf("Созвон"), titles())
    }

    @Test
    fun repeatingEventsShowOnEachOfTheirDays() = runTest {
        val yoga = calendar.createEvent("2026-10-07", "Йога", "19:00")
        calendar.put(CalendarKind.EVENT, yoga, mapOf(EventField.RRULE to "FREQ=WEEKLY"))
        val trip = calendar.createEvent("2026-09-30", "Дача", null)
        calendar.put(CalendarKind.EVENT, trip, mapOf(EventField.END to "2026-10-02", EventField.RRULE to "FREQ=MONTHLY;COUNT=2"))

        val october = calendar.events("2026-10-01", "2026-10-31").first()
        val yogas = october.filter { it.title == "Йога" }
        assertEquals(listOf("2026-10-07", "2026-10-14", "2026-10-21", "2026-10-28"), yogas.map { it.day })
        assertEquals(listOf("19:00"), yogas.map { it.time }.distinct())
        assertEquals("2026-10-07T19:00", yogas.last().seriesStart, "every occurrence knows where its series starts")
        assertEquals(
            listOf("2026-09-30" to "2026-10-02", "2026-10-30" to "2026-11-01"),
            october.filter { it.title == "Дача" }.map { it.start to it.end },
            "a multi-day series that began before the range still shows",
        )

        calendar.skipDay(yoga, "2026-10-14")
        assertEquals(
            listOf("2026-10-07", "2026-10-21", "2026-10-28"),
            calendar.events("2026-10-01", "2026-10-31").first().filter {
                it.title ==
                    "Йога"
            }.map { it.day },
        )
        assertEquals(
            1,
            calendar.events("2027-03-01", "2027-03-31").first().count {
                it.title == "Йога" && it.day == "2027-03-03"
            },
            "years on",
        )
    }

    @Test
    fun deletedEventsWaitInTheTrash() = runTest {
        val doctor = calendar.createEvent("2026-10-14", "Врач", null)
        calendar.createEvent("2026-10-14", "Созвон", null)
        calendar.moveToTrash(doctor)
        assertEquals(listOf("Врач"), calendar.trashedEvents().first().map { it.title })
        assertEquals(listOf("Созвон"), titles())
        calendar.restore(doctor)
        assertEquals(emptyList(), calendar.trashedEvents().first())
        assertEquals(setOf("Врач", "Созвон"), titles().toSet())
    }
}
