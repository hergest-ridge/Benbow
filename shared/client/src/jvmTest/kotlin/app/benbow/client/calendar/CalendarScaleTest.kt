package app.benbow.client.calendar

import app.benbow.client.Connection
import app.benbow.client.FakeApi
import app.benbow.client.FakeServer
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.core.calendar.EventField
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * A big Google calendar (7.3d): 20,000 events over five years plus weekly series, on a file DB like the phone's.
 * Prints the timings; the bounds are loose, to catch an order of magnitude, not to benchmark.
 */
class CalendarScaleTest {
    private var time = 1_000L

    private fun fileDb() = CalendarDb(
        JdbcSqliteDriver(
            "jdbc:sqlite:" + Files.createTempFile("calendar", ".db"),
            Properties().apply {
                put("journal_mode", "WAL")
                put("synchronous", "FULL")
            },
            CalendarDb.Schema,
        ),
    )

    private val big = ImportCalendar(
        "Time tracking",
        null,
        (0 until 20_000).map { i ->
            val day = LocalDate(2021, 11, 1).plus(i / 11, DateTimeUnit.DAY)
            ImportEvent(
                "t$i@google.com",
                mapOf(
                    EventField.TITLE to "Запись $i",
                    EventField.START to "${day}T${(8 + i % 11).toString().padStart(2, '0')}:00",
                ),
            )
        } + (0 until 50).map { i ->
            ImportEvent(
                "w$i@google.com",
                mapOf(
                    EventField.TITLE to "Каждую неделю $i",
                    EventField.START to "2022-01-0${1 + i % 7}T19:00",
                    EventField.RRULE to "FREQ=WEEKLY",
                ),
            )
        },
    )

    @Test
    fun twentyThousandEventsStayQuick() = runBlocking {
        val phoneDb = fileDb()
        val phone = CalendarRepository(phoneDb, { time++ })
        val (imported, importTime) = measureTimedValue { phone.import(listOf(ImportChoice(big, "fog")), from = null) }
        assertEquals(20_050, imported.events)

        repeat(2) { phone.events("2026-09-28", "2026-11-08").first() } // warm up
        val (month, monthTime) = measureTimedValue { phone.events("2026-09-28", "2026-11-08").first() }
        val (oldMonth, oldMonthTime) = measureTimedValue { phone.events("2022-02-28", "2022-04-10").first() }
        val (_, panelTime) = measureTimedValue { phone.calendars().first() }
        val (_, uidsTime) = measureTimedValue { phone.knownUids() }

        val server = FakeServer()
        val connection = Connection("https://server", "AA", "token", server.storeId, 1, "phone")
        val sync = CalendarSync(phoneDb, { time++ })
        val (pushed, pushTime) = measureTimedValue { sync.sync(FakeApi(server), connection) }
        assertIs<CalendarSyncResult.Done>(pushed)
        val desktop = fileDb()
        val (pulled, pullTime) = measureTimedValue { CalendarSync(desktop, { time++ }).sync(FakeApi(server), connection) }
        assertEquals(20_051, (pulled as CalendarSyncResult.Done).pulled)

        println(
            "20k: import $importTime, month ${month.size} events in $monthTime, a month of 2022 ${oldMonth.size} in $oldMonthTime, " +
                "panel $panelTime, known UIDs $uidsTime, push $pushTime, pull on another device $pullTime",
        )
        assertTrue(month.any { it.title.startsWith("Каждую неделю") } && oldMonth.size > 400)
        assertTrue(importTime.inWholeSeconds < 60, "import took $importTime")
        assertTrue(
            monthTime.inWholeMilliseconds < 1_000 && oldMonthTime.inWholeMilliseconds < 1_000,
            "a month took $monthTime / $oldMonthTime",
        )
    }
}
