package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class MomentsTest {
    private var time = 1_000L

    private inner class Device(server: FakeServer, name: String) {
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val repo = JournalRepository(db, { time++ })
        val engine = SyncEngine(db, { FakeApi(server) }, { time++ })

        init {
            Settings(db).connection = Connection("https://pc:8765", "AA", "token-$name", server.storeId, server.epoch, "device-$name")
        }
    }

    @Test
    fun anEntryMovesInTimeOnEveryDevice() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val desktop = Device(server, "desktop")
        val late = phone.repo.createNote("2026-10-14", "Написал ночью про вечер")
        phone.repo.createNote("2026-10-10", "Утро субботы")
        phone.engine.sync()
        desktop.engine.sync()

        phone.repo.moveTo(late, LocalDateTime(2026, 10, 10, 21, 5))
        assertEquals(
            listOf("Написал ночью про вечер", "Утро субботы"),
            phone.repo.entriesForDay("2026-10-10").first().map {
                it.title
            }.reversed(),
        )
        assertEquals(emptyList(), phone.repo.entriesForDay("2026-10-14").first(), "gone from the day it was written")
        assertEquals("2026-10-10T21:05", phone.repo.entry(late).first()!!.fields["when"])

        phone.engine.sync()
        desktop.engine.sync()
        assertEquals("2026-10-10", desktop.repo.entry(late).first()!!.day, "the other device shows it there too")
        assertEquals("2026-10-14", server.store.entry(late)!!.day, "the day it was made stays on record")
    }
}
