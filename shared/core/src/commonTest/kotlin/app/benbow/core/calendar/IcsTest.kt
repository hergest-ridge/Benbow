package app.benbow.core.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IcsTest {
    /** Shaped like a Google Calendar export (synthetic): a zone block, alarms, folded and escaped text. */
    private val google = listOf(
        "BEGIN:VCALENDAR",
        "PRODID:-//Google Inc//Google Calendar 70.9054//EN",
        "VERSION:2.0",
        "X-WR-CALNAME:Работа",
        "X-WR-TIMEZONE:Europe/Moscow",
        "BEGIN:VTIMEZONE",
        "TZID:Europe/Moscow",
        "BEGIN:STANDARD",
        "DTSTART:19700101T000000",
        "TZOFFSETFROM:+0300",
        "TZOFFSETTO:+0300",
        "END:STANDARD",
        "END:VTIMEZONE",
        "BEGIN:VEVENT",
        "DTSTART;TZID=Europe/Moscow:20261014T093000",
        "DTEND;TZID=Europe/Moscow:20261014T103000",
        "RRULE:FREQ=WEEKLY;BYDAY=WE",
        "EXDATE;TZID=Europe/Moscow:20261021T093000,20261028T093000",
        "UID:abc123@google.com",
        "SUMMARY:Планёрка\\, отдел продаж",
        "DESCRIPTION:Первая строка\\nвторая\\; с точкой с запятой и очень длинная строк",
        " а\\, которая переносится",
        "LOCATION:Офис",
        "BEGIN:VALARM",
        "ACTION:DISPLAY",
        "DESCRIPTION:This is an event reminder",
        "TRIGGER:-P0DT0H10M0S",
        "END:VALARM",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART;VALUE=DATE:20261231",
        "DTEND;VALUE=DATE:20270101",
        "UID:ny@google.com",
        "SUMMARY:Новый год",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART:20261015T070000Z",
        "DURATION:PT1H",
        "SUMMARY:Созвон",
        "STATUS:CANCELLED",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "SUMMARY:Без начала",
        "END:VEVENT",
        "END:VCALENDAR",
    ).joinToString("\r\n") + "\r\n"

    @Test
    fun readsWhatCalendarsExport() {
        val calendar = Ics.parse(google).single()
        assertEquals("Работа", calendar.name)
        assertEquals(1, calendar.skipped, "the event without a start is counted, not fatal")
        val (meeting, newYear, call) = calendar.events
        assertEquals("Планёрка, отдел продаж", meeting.summary)
        assertEquals("Первая строка\nвторая; с точкой с запятой и очень длинная строка, которая переносится", meeting.description)
        assertEquals(IcsTime("20261014T093000", tzid = "Europe/Moscow"), meeting.start)
        assertEquals(listOf("20261021T093000", "20261028T093000"), meeting.exdates.map { it.value })
        assertEquals("FREQ=WEEKLY;BYDAY=WE", meeting.rrule)
        assertEquals("Офис", meeting.location, "the alarm's own DESCRIPTION didn't leak into the event")
        assertEquals(IcsTime("20261231"), newYear.start)
        assertTrue(newYear.start.isDate)
        assertEquals(IcsTime("20261015T070000", utc = true), call.start)
        assertEquals("PT1H", call.duration)
        assertTrue(call.cancelled)
    }

    @Test
    fun writesFoldedLinesThatReadBack() {
        val long = "Очень длинное описание события, ".repeat(6) + "с эмодзи 🎂 и запятыми, точками; и\nпереносом."
        val calendar = IcsCalendar(
            name = "Годовщины, семья",
            color = "blossom",
            events = listOf(
                IcsEvent("1@benbow", "День рождения; мамы", long, "Дом", IcsTime("20261103"), IcsTime("20261104"), rrule = "FREQ=YEARLY"),
                IcsEvent(
                    "2@benbow",
                    "Врач",
                    start = IcsTime("20261014T093000"),
                    end = IcsTime("20261014T101500"),
                    exdates = listOf(IcsTime("20261021T093000")),
                ),
            ),
        )
        val text = Ics.write(calendar, "20261015T125337Z")
        val lines = text.split("\r\n").dropLast(1)
        assertTrue(lines.all { it.encodeToByteArray().size <= 75 }, "no line longer than 75 bytes")
        assertTrue(lines.none { '�' in it.encodeToByteArray().decodeToString() }, "no character cut in half")
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
        assertEquals(calendar, Ics.parse(text).single())
    }
}
