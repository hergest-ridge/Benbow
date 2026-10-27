package app.benbow.client.calendar

import app.benbow.client.calendar.db.CalendarDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

/** An installed app keeps its calendar file across updates; each schema step migrates it in place. */
class CalendarMigrationTest {
    @Test
    fun aV1FileGainsTheTrackersTableKeepsItsEventsAndPullsEverythingAgain() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // The file as apps before 7.21 B3 left it (schema 1), with an event, a queued op and a cursor.
        listOf(
            "CREATE TABLE calendar (id TEXT NOT NULL PRIMARY KEY, fields TEXT NOT NULL DEFAULT '{}', " +
                "field_clocks TEXT NOT NULL DEFAULT '{}', updated_at INTEGER NOT NULL)",
            "CREATE TABLE event (id TEXT NOT NULL PRIMARY KEY, fields TEXT NOT NULL DEFAULT '{}', " +
                "field_clocks TEXT NOT NULL DEFAULT '{}', " +
                "calendar TEXT NOT NULL, title TEXT NOT NULL, start TEXT, start_day TEXT, end_day TEXT, " +
                "deleted INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)",
            "CREATE INDEX event_by_days ON event(start_day, end_day)",
            "CREATE TABLE outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, op_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, " +
                "record_id TEXT NOT NULL, op TEXT NOT NULL, created_at INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending', last_error TEXT)",
            "CREATE INDEX outbox_by_record ON outbox(kind, record_id, state)",
            "CREATE TABLE kv (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            "INSERT INTO event(id, fields, calendar, title, start, start_day, end_day, updated_at) " +
                "VALUES ('e1', '{\"title\":\"Врач\"}', 'mine', 'Врач', '2026-10-14', '2026-10-14', '2026-10-14', 1)",
            "INSERT INTO outbox(op_id, kind, record_id, op, created_at) VALUES ('o1', 'event', 'e1', '{}', 1)",
            "INSERT INTO kv(key, value) VALUES ('sync.cursor', '57'), ('sync.epoch', '1')",
        ).forEach { driver.execute(null, it, 0) }

        CalendarDb.Schema.migrate(driver, 1, CalendarDb.Schema.version)

        val q = CalendarDb(driver).calendarQueries
        assertEquals(listOf("Врач"), q.allEvents().executeAsList().map { it.title })
        assertEquals(1, q.pendingCount().executeAsOne(), "the queued op stays")
        assertEquals("0", q.getValue("sync.cursor").executeAsOne(), "the next sync pulls the records this app skipped")
        assertEquals("1", q.getValue("sync.epoch").executeAsOne())
        assertEquals(0, q.allTrackerRecords().executeAsList().size)
    }
}
