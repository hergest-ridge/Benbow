package app.benbow.client.calendar

import app.benbow.client.calendar.db.CalendarDb
import app.benbow.core.calendar.EventField
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Google Calendar's export into the calendar (7.3d): times, series, preview numbers, dedup and undo. */
class CalendarImportTest {
    private var time = 1_000L
    private val calendar =
        CalendarRepository(CalendarDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema)), { time++ })
    private val warsaw = TimeZone.of("Europe/Warsaw")

    private fun ics(name: String?, vararg events: String) = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0") + listOfNotNull(name?.let { "X-WR-CALNAME:$it" }) +
            events.flatMap { listOf("BEGIN:VEVENT") + it.trimIndent().lines() + "END:VEVENT" } + "END:VCALENDAR"
        ).joinToString("\r\n")

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
            }
        }
    }.toByteArray()

    private fun read(text: String) = IcsImport.read("calendar.ics", text.encodeToByteArray(), warsaw).single()

    private fun ImportCalendar.byTitle(title: String) = events.single { it.fields[EventField.TITLE] == title }.fields

    @Test
    fun timesBecomeLocal() {
        val calendar = read(
            ics(
                "Работа",
                "UID:a\nSUMMARY:Москва\nDTSTART;TZID=Europe/Moscow:20261014T093000\nDTEND;TZID=Europe/Moscow:20261014T103000",
                "UID:b\nSUMMARY:Утро UTC\nDTSTART:20261015T070000Z\nDURATION:PT1H30M",
                "UID:c\nSUMMARY:Как есть\nDTSTART:20261016T180000",
                "UID:d\nSUMMARY:Outlook\nDTSTART;TZID=Russian Standard Time:20261017T120000",
            ),
        )
        assertEquals(
            mapOf(EventField.TITLE to "Москва", EventField.START to "2026-10-14T08:30", EventField.END to "2026-10-14T09:30"),
            calendar.byTitle("Москва"),
        )
        assertEquals(
            "2026-10-15T09:00" to "2026-10-15T10:30",
            calendar.byTitle("Утро UTC").let {
                it[EventField.START] to it[EventField.END]
            },
        )
        assertEquals("2026-10-16T18:00", calendar.byTitle("Как есть")[EventField.START], "floating times stay as written")
        assertEquals("2026-10-17T12:00", calendar.byTitle("Outlook")[EventField.START])
        assertEquals(1, calendar.unknownZones, "a Windows zone name is kept as written and counted")
    }

    @Test
    fun allDayEndsAndUntitledEvents() {
        val calendar = read(
            ics(
                null,
                "UID:a\nSUMMARY:Новый год\nDTSTART;VALUE=DATE:20261231\nDTEND;VALUE=DATE:20270101",
                "UID:b\nSUMMARY:Каникулы\nDTSTART;VALUE=DATE:20261230\nDTEND;VALUE=DATE:20270103",
                "UID:c\nSUMMARY:Поход\nDTSTART;VALUE=DATE:20261101\nDURATION:P3D",
                "UID:d\nDTSTART;VALUE=DATE:20261102",
                "UID:e\nSUMMARY:Без начала",
            ),
        )
        assertEquals("calendar", calendar.name, "no X-WR-CALNAME: the file's name")
        assertEquals(null, calendar.byTitle("Новый год")[EventField.END], "one day: ICS's exclusive end goes")
        assertEquals("2027-01-02", calendar.byTitle("Каникулы")[EventField.END])
        assertEquals("2026-11-03", calendar.byTitle("Поход")[EventField.END])
        assertEquals("2026-11-02", calendar.byTitle(IcsImport.UNTITLED)[EventField.START])
        assertEquals(1, calendar.unreadable)
    }

    @Test
    fun changedAndCancelledDaysOfASeries() {
        val calendar = read(
            ics(
                "Йога",
                "UID:yoga\nSUMMARY:Йога\nDTSTART;TZID=Europe/Warsaw:20261007T190000\nRRULE:FREQ=WEEKLY\nEXDATE;TZID=Europe/Warsaw:20261014T190000",
                "UID:yoga\nRECURRENCE-ID;TZID=Europe/Warsaw:20261021T190000\nSUMMARY:Йога (перенос)\nDTSTART;TZID=Europe/Warsaw:20261022T200000",
                "UID:yoga\nRECURRENCE-ID;TZID=Europe/Warsaw:20261028T190000\nSUMMARY:Йога\nDTSTART;TZID=Europe/Warsaw:20261028T190000\nSTATUS:CANCELLED",
                "UID:gone\nSUMMARY:Отменено\nDTSTART:20261020T100000\nSTATUS:CANCELLED",
            ),
        )
        assertEquals(listOf("Йога", "Йога (перенос)"), calendar.events.map { it.fields[EventField.TITLE] })
        val series = calendar.events[0]
        assertEquals("2026-10-14,2026-10-21,2026-10-28", series.fields[EventField.EXDATE])
        assertEquals("FREQ=WEEKLY", series.fields[EventField.RRULE])
        assertEquals(FOREVER, series.lastDay)
        val moved = calendar.events[1]
        assertEquals("yoga/20261021T190000", moved.uid)
        assertEquals("2026-10-22T20:00", moved.fields[EventField.START])
        assertEquals(null, moved.fields[EventField.RRULE])
    }

    @Test
    fun googlesZipHasACalendarPerFile() {
        val bytes = zip(
            "me@gmail.com.ics" to ics("me@gmail.com", "UID:1\nSUMMARY:Врач\nDTSTART:20261014T093000"),
            "Годовщины_abc@group.calendar.google.com.ics" to
                ics("Годовщины", "UID:2\nSUMMARY:Свадьба\nDTSTART;VALUE=DATE:20261103\nRRULE:FREQ=YEARLY"),
            "readme.txt" to "not a calendar",
        )
        val calendars = IcsImport.read("calendar-export.zip", bytes, warsaw)
        assertEquals(listOf("me@gmail.com", "Годовщины"), calendars.map { it.name })
        assertEquals(listOf(1, 1), calendars.map { it.events.size })
    }

    @Test
    fun importGoesIntoCalendarsByNameOnceAndCanBeUndone() = runTest {
        val work = calendar.createCalendar("Работа", "storm")
        val before = calendar.createEvent("2026-10-01", "Своё событие", null, calendar = work)
        val file = IcsImport.read(
            "export.zip",
            zip(
                "work.ics" to
                    ics("работа", "UID:w1\nSUMMARY:Созвон\nDTSTART:20261015T100000", "UID:w0\nSUMMARY:Давно\nDTSTART;VALUE=DATE:20200101"),
                "family.ics" to ics("Семья", "UID:f1\nSUMMARY:День рождения\nDTSTART;VALUE=DATE:19900509\nRRULE:FREQ=YEARLY"),
            ),
            warsaw,
        )
        val result = calendar.import(file.map { ImportChoice(it, "blossom") }, from = "2026-01-01")
        assertEquals(2, result.events, "the old one-off is before the period; the yearly birthday reaches into it")
        assertEquals(1, result.calendars, "«работа» found «Работа»; «Семья» is new")
        val list = calendar.calendars().first()
        assertEquals(listOf("Работа", "Семья"), list.drop(1).map { it.name })
        assertEquals("blossom", list.last().color)
        assertEquals(setOf("Созвон", "Своё событие"), calendar.events("2026-10-01", "2026-10-31").first().map { it.title }.toSet())
        assertEquals(listOf("День рождения"), calendar.events("2027-05-09", "2027-05-09").first().map { it.title })

        val again = calendar.import(file.map { ImportChoice(it, "blossom") }, from = null)
        assertEquals(1, again.events, "only the one the period left out last time")
        assertEquals(2, again.already, "the rest are here by UID")

        assertEquals(2, calendar.undoImport(result.batch))
        assertEquals(setOf("Своё событие"), calendar.events("2026-10-01", "2026-10-31").first().map { it.title }.toSet())
        assertEquals(listOf("Работа"), calendar.calendars().first().drop(1).map { it.name }, "the calendar it made is in the trash")
        assertEquals("Своё событие", calendar.event(before)!!.title)
        assertTrue(calendar.trashedEvents().first().map { it.title }.containsAll(listOf("Созвон", "День рождения")))
        assertEquals(listOf("Семья"), calendar.trashedCalendars().first().map { it.name })
    }
}
