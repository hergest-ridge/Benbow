package app.benbow.client.calendar

import app.benbow.client.Connection
import app.benbow.client.FakeApi
import app.benbow.client.FakeServer
import app.benbow.client.JournalRepository
import app.benbow.client.PairingException
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.core.calendar.CalendarKind
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The calendar's own file on the device and its own channel to the server (ADR-027). */
class CalendarClientTest {
    private var time = 1_000L
    private val now = { time++ }
    private val connection = Connection("https://server", "AA", "token", "store-1", 1, "phone")

    private fun calendarDb() = CalendarDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema))

    private fun journalDb() = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))

    private inner class Device(api: FakeApi) {
        val db = calendarDb()
        val calendar = CalendarRepository(db, now)
        val sync = CalendarSync(db, now)
        var api = api

        suspend fun sync() = sync.sync(api, connection)

        suspend fun titles(from: String = "2026-10-01", to: String = "2026-10-31") = calendar.events(from, to).first().map { it.title }
    }

    @Test
    fun eventsShowOnEachOfTheirDaysAndLeaveWithTheTrash() = runTest {
        val calendar = CalendarRepository(calendarDb(), now)
        calendar.createEvent("2026-10-14", "Врач", "09:30")
        val birthday = calendar.createEvent("2026-10-14", "День рождения Маши", null)
        val trip = calendar.createEvent("2026-10-20", "Поездка", null)
        calendar.put(CalendarKind.EVENT, trip, mapOf(EventField.END to "2026-10-23"))

        assertEquals(
            listOf("День рождения Маши", "Врач"),
            calendar.events("2026-10-14", "2026-10-14").first().map {
                it.title
            },
            "all day first",
        )
        assertEquals(listOf("Поездка"), calendar.events("2026-10-22", "2026-10-22").first().map { it.title })
        assertTrue(calendar.event(trip)!!.covers("2026-10-23"))
        assertEquals("09:30", calendar.events("2026-10-14", "2026-10-14").first().last().time)

        calendar.moveToTrash(birthday)
        assertEquals(listOf("Врач"), calendar.events("2026-10-14", "2026-10-14").first().map { it.title })
        calendar.restore(birthday)
        assertEquals(2, calendar.events("2026-10-14", "2026-10-14").first().size)
    }

    @Test
    fun devicesShareEventsThroughTheCalendarChannelOnly() = runTest {
        val server = FakeServer()
        val phone = Device(FakeApi(server))
        val desktop = Device(FakeApi(server))
        val id = phone.calendar.createEvent("2026-10-14", "Врач", "09:30")
        assertIs<CalendarSyncResult.Done>(phone.sync())
        assertIs<CalendarSyncResult.Done>(desktop.sync())
        assertEquals(listOf("Врач"), desktop.titles())
        assertTrue(server.log.isEmpty(), "nothing went through the journal's channel")

        desktop.calendar.updateEvent(id, "Стоматолог", "2026-10-14T10:00")
        phone.calendar.put(CalendarKind.EVENT, id, mapOf(EventField.DESCRIPTION to "Взять снимки"))
        phone.sync() // the phone's own write stays while it's pending; then it's pushed
        desktop.sync()
        phone.sync()
        for (device in listOf(phone, desktop)) {
            val event = device.calendar.event(id)!!
            assertEquals("Стоматолог", event.title)
            assertEquals("Взять снимки", event.description, "different fields merge")
        }
    }

    @Test
    fun aLostResponseIsRetriedAndTheServerDedupes() = runTest {
        val server = FakeServer()
        val api = FakeApi(server)
        val phone = Device(api)
        phone.calendar.createEvent("2026-10-14", "Врач", null)
        api.dropResponse = true
        assertIs<CalendarSyncResult.Failed>(phone.sync())
        assertEquals(1L, phone.calendar.pendingCount().first(), "kept until the server answers")
        api.dropResponse = false
        assertIs<CalendarSyncResult.Done>(phone.sync())
        assertEquals(0L, phone.calendar.pendingCount().first())
        assertEquals(1, server.calendarStore.changesSince(0, 100).distinctBy { it.id }.size)
    }

    @Test
    fun anOlderServerKeepsEventsWaitingOnTheDevice() = runTest {
        val server = FakeServer().apply { calendarOn = false }
        val phone = Device(FakeApi(server))
        phone.calendar.createEvent("2026-10-14", "Врач", null)
        assertEquals(CalendarSyncResult.Unsupported, phone.sync())
        assertEquals(1L, phone.calendar.pendingCount().first())
        server.calendarOn = true
        assertIs<CalendarSyncResult.Done>(phone.sync())
        assertEquals(0L, phone.calendar.pendingCount().first())
    }

    @Test
    fun aRestoredCalendarGetsBackWhatTheDeviceHas() = runTest {
        val server = FakeServer()
        val phone = Device(FakeApi(server))
        val doctor = phone.calendar.createEvent("2026-10-14", "Врач", null)
        phone.sync()
        val snapshot = server.calendarLog.toList()
        val trip = phone.calendar.createEvent("2026-10-20", "Поездка", null)
        phone.calendar.updateEvent(doctor, "Стоматолог", "2026-10-14")
        phone.sync()

        val restored = server.calendarRestoredTo(snapshot, calendarEpoch = 2)
        phone.api = FakeApi(restored)
        assertIs<CalendarSyncResult.Done>(phone.sync())
        val titles = listOf(doctor, trip).map {
            restored.calendarStore.record(CalendarKind.EVENT, it)?.fields?.get(EventField.TITLE)?.value
        }
        assertEquals(listOf("Стоматолог", "Поездка"), titles, "the device's newer values went back")
        assertEquals(listOf("Стоматолог", "Поездка"), phone.titles())
        assertEquals(restored.log, server.log, "the journal was never involved")
    }

    @Test
    fun aCalendarCacheNeverMixesTwoStores() = runTest {
        val phone = Device(FakeApi(FakeServer()))
        phone.calendar.createEvent("2026-10-14", "Врач", null)
        phone.sync()
        phone.calendar.createEvent("2026-10-15", "Ещё", null)
        val other = FakeServer(storeId = "store-2")
        phone.api = FakeApi(other)
        assertEquals(CalendarSyncResult.WrongServer, phone.sync())
        assertTrue(other.calendarLog.isEmpty())
        assertEquals(1L, phone.calendar.pendingCount().first())
    }

    @Test
    fun oldJournalEventsMoveOnceAndTheirEntriesGoToTheTrash() = runTest {
        val journalDb = journalDb()
        val journal = JournalRepository(journalDb, now)
        val calendar = CalendarRepository(calendarDb(), now)
        val doctor = journal.createEntry("2026-10-14", kind = "event", body = JournalRepository.textBody("Врач\nВзять снимки"))
        journal.setField(doctor, "start", "2026-10-14T09:30")
        val birthday = journal.createEntry("2026-10-16", kind = "event", body = JournalRepository.textBody("День рождения Маши"))
        val note = journal.createNote("2026-10-14", "Обычная запись")

        assertEquals(2, moveJournalEvents(journalDb, journal, calendar))
        val moved = calendar.event(doctor)!!
        assertEquals(CalendarEvent(doctor, MY_CALENDAR, "Врач", "2026-10-14T09:30", null, "Взять снимки"), moved)
        assertEquals("2026-10-16", calendar.event(birthday)!!.start)
        assertEquals(setOf(doctor, birthday), journal.trash().first().map { it.id }.toSet(), "entries kept in the trash, not erased")
        assertEquals(listOf(note), journal.journal().first().map { it.id })

        val ops = calendar.pendingCount().first()
        assertEquals(0, moveJournalEvents(journalDb, journal, calendar), "a second run finds nothing")
        journal.restore(doctor) // brought back from the trash by hand: it isn't written to the calendar twice
        assertEquals(1, moveJournalEvents(journalDb, journal, calendar))
        assertEquals(ops, calendar.pendingCount().first())
        assertNull(journal.journal().first().find { it.id == doctor })
    }

    @Test
    fun forgettingTheServerWaitsForTheCalendarToo() = runTest {
        val phone = Device(FakeApi(FakeServer()))
        phone.calendar.createEvent("2026-10-14", "Врач", null)
        assertFailsWith<PairingException> { phone.calendar.forget() }
        phone.sync()
        phone.calendar.forget()
        assertEquals(0L, phone.db.calendarQueries.eventCount().executeAsOne())
    }
}
