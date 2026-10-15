package app.benbow.core.calendar

import app.benbow.core.calendar.CalendarExport.Record
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarExportTest {
    private fun event(id: String, vararg fields: Pair<String, String?>) = Record(id, fields.toMap())

    private fun vevent(vararg fields: Pair<String, String?>) = CalendarExport.event(event("e1", *fields))!!

    @Test
    fun timesStayLocalAndEndsFollowTheRules() {
        assertEquals(
            IcsTime("20261014") to IcsTime("20261015"),
            vevent(EventField.START to "2026-10-14").let {
                it.start to it.end
            },
            "all day: the next day ends it",
        )
        assertEquals(
            IcsTime("20261230") to IcsTime("20270102"),
            vevent(EventField.START to "2026-12-30", EventField.END to "2027-01-01").let { it.start to it.end },
            "a multi-day one ends the day after its last, over the new year",
        )
        assertEquals(
            IcsTime("20261014T093000") to IcsTime("20261014T101500"),
            vevent(EventField.START to "2026-10-14T09:30", EventField.END to "2026-10-14T10:15").let { it.start to it.end },
            "floating local times, no zone (ADR-028)",
        )
        assertEquals(IcsTime("20261016T000000"), vevent(EventField.START to "2026-10-14T22:00", EventField.END to "2026-10-15").end)
        assertEquals(null, vevent(EventField.START to "2026-10-14T09:30").end)
        assertEquals(
            null,
            vevent(EventField.START to "2026-10-14T09:30", EventField.END to "2026-10-13").end,
            "an end before the start is dropped",
        )
        assertEquals("20000301", CalendarExport.plusDays("20000229", 1))
        assertEquals("20261015T125337Z", CalendarExport.utcStamp(1_792_068_817_802))
        assertEquals("19700101T000000Z", CalendarExport.utcStamp(0))
    }

    @Test
    fun seriesKeepTheirRuleSkippedDaysAndIdentity() {
        val series = vevent(
            EventField.START to "2026-10-14T19:00",
            EventField.RRULE to "FREQ=WEEKLY",
            EventField.EXDATE to "2026-10-21,2026-10-28",
            EventField.TITLE to "Йога",
        )
        assertEquals("FREQ=WEEKLY", series.rrule)
        assertEquals(listOf(IcsTime("20261021T190000"), IcsTime("20261028T190000")), series.exdates)
        assertEquals("e1@benbow", series.uid)
        assertEquals("imported@google.com", vevent(EventField.START to "2026-10-14", EventField.UID to "imported@google.com").uid)
    }

    @Test
    fun oneFilePerShownCalendarAndTheJsonHasEverything() {
        val calendars = listOf(
            Record("work", mapOf(CalendarField.NAME to "Работа", CalendarField.COLOR to "storm")),
            Record("old", mapOf(CalendarField.NAME to "Старый", EventField.DELETED to "true")),
            Record("dup", mapOf(CalendarField.NAME to "работа")),
        )
        val events = listOf(
            event("a", EventField.START to "2026-10-14", EventField.TITLE to "Врач"),
            event("b", EventField.START to "2026-10-15", EventField.TITLE to "Созвон", EventField.CALENDAR to "work"),
            event("c", EventField.START to "2026-10-16", EventField.TITLE to "Удалённое", EventField.DELETED to "true"),
            event("d", EventField.START to "2026-10-17", EventField.TITLE to "В старом", EventField.CALENDAR to "old"),
        )
        val files = CalendarExport.files(calendars, events, defaultName = "Мои события", now = 0)
        assertEquals(listOf("Мои события.ics", "Работа.ics", "работа-dup.ics", "calendar.json"), files.map { it.name })
        val mine = Ics.parse(files[0].text).single()
        assertEquals("Мои события", mine.name)
        assertEquals(listOf("Врач"), mine.events.map { it.summary }, "the trash stays out")
        assertEquals("storm", Ics.parse(files[1].text).single().color)
        val json = files.last().text
        assertTrue(listOf("Удалённое", "В старом", "Старый").all { it in json }, "the JSON keeps deleted ones too")
        assertEquals("a_b_c.ics", CalendarExport.fileName("a/b:c", "id"))
    }
}
