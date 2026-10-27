package app.benbow.server

import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.CalendarOp
import app.benbow.core.calendar.CalendarRules
import app.benbow.core.calendar.Ics
import app.benbow.core.calendar.IcsTime
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.SyncRules
import app.benbow.server.backup.BackupJobs
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.calendar.SqliteCalendarStore
import app.benbow.server.storage.CALENDAR_HIGH_WATER
import app.benbow.server.storage.Database
import app.benbow.server.storage.HighWater
import app.benbow.server.storage.Restore
import app.benbow.server.storage.SqliteSyncStore
import app.benbow.testing.CalendarSimulation
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The calendar store beside the journal (ADR-027): its own file, snapshots, restore and epoch. */
class CalendarStoreTest {
    private val dir = Files.createTempDirectory("benbow-calendar")

    private fun event(op: String, title: String, wall: Long, id: String = "e1") =
        CalendarOp(op, CalendarKind.EVENT, id, mapOf("title" to title, "start" to "2026-10-14"), Hlc(wall, 0, "phone"))

    @Test
    fun calendarSimulationHoldsOnSqlite() = runTest {
        val simulation = CalendarSimulation { SqliteCalendarStore(CalendarDatabase.inMemory()) }
        checkAll(150, Arb.long()) { seed -> simulation.run(seed) }
        simulation.assertRetriesCovered()
    }

    @Test
    fun theCalendarHasItsOwnFileAndRefusesAnotherStoresFile() {
        val storeId = Database.open(dir, "0.6.0").use { it.storeId }
        CalendarDatabase.open(dir, "0.6.0", storeId).use { calendar ->
            assertEquals(storeId, calendar.storeId)
            assertEquals(1, calendar.epoch)
            CalendarRules(SqliteCalendarStore(calendar)).push(listOf(event("o1", "Врач", 1)))
        }
        assertTrue(dir.resolve("db/calendar.sqlite").exists())
        CalendarDatabase.open(dir, "0.7.0", storeId).close()
        assertEquals(
            listOf("calendar"),
            dir.resolve("backups/calendar").listDirectoryEntries("*.sqlite").map { it.name.substringBefore('-') },
            "a new server version snapshots the calendar first, in its own folder",
        )
        assertFalse(dir.resolve("backups/db").exists(), "and leaves the journal's snapshots alone")
        assertFailsWith<IllegalStateException> { CalendarDatabase.open(dir, "0.7.0", "another-store") }
        Database.open(dir, "0.7.0").close() // the journal opens whatever the calendar file is
    }

    @Test
    fun calendarSnapshotsLiveApartAndItsFailureNeverStopsTheJournal() {
        val mirror = Files.createTempDirectory("benbow-calendar-mirror")
        val db = Database.open(dir, "test")
        val calendar = CalendarDatabase.open(dir, "test", db.storeId)
        val jobs = BackupJobs(db, dir, mirror, calendar = calendar, disks = TestDisks())
        SyncRules(SqliteSyncStore(db)).push(listOf(CreateEntry("j1", "entry-1", "journal", "2026-10-15", 1)))
        CalendarRules(SqliteCalendarStore(calendar)).push(listOf(event("c1", "Врач", 1)))

        jobs.runNow()
        assertNotNull(jobs.status.calendarSnapshotAt)
        assertEquals(null, jobs.status.calendarError)
        assertEquals(1, mirror.resolve("calendar").listDirectoryEntries("*.sqlite").size)
        assertTrue(mirror.resolve("calendar/manifest.json").exists())
        assertFalse(jobs.runCalendar(), "nothing changed: no new calendar snapshot")
        assertEquals(1, mirror.resolve("db").listDirectoryEntries("*.sqlite").size, "the journal's copies sit in their own folder")

        calendar.close() // the calendar breaks
        SyncRules(SqliteSyncStore(db)).push(listOf(CreateEntry("j2", "entry-2", "journal", "2026-10-15", 2)))
        Thread.sleep(1_100) // snapshot names have second resolution
        jobs.runNow()
        assertNotNull(jobs.status.calendarError)
        assertEquals(null, jobs.status.lastError)
        assertEquals(2, db.backups!!.history().size, "the journal still got its snapshot")
        assertEquals(1, mirror.resolve("calendar").listDirectoryEntries("*.sqlite").size, "the journal's run keeps the calendar's copies")
        db.close()
    }

    @Test
    fun aValueReplacedOrComingInOlderStaysInTheHistoryOfTheFile() {
        val storeId = Database.open(dir, "test").use { it.storeId }
        CalendarDatabase.open(dir, "test", storeId).use { calendar ->
            val rules = CalendarRules(SqliteCalendarStore(calendar))
            rules.push(listOf(event("o1", "Врач", 1), event("o2", "Стоматолог", 3), event("o3", "Окулист", 2)))
        }
        CalendarDatabase.open(dir, "test", storeId).use { calendar ->
            val store = SqliteCalendarStore(calendar)
            assertEquals("Стоматолог", store.record(CalendarKind.EVENT, "e1")?.fields?.getValue("title")?.value)
            assertEquals(
                listOf("Врач" to Hlc(1, 0, "phone"), "Окулист" to Hlc(2, 0, "phone")),
                store.history(CalendarKind.EVENT, "e1").map { it.value to it.hlc },
                "the replaced title and the one that came in older, the unchanged start not at all",
            )
        }
    }

    @Test
    fun aCalendarFileOfSchemaOneGetsItsHistoryAfterASnapshot() {
        val storeId = Database.open(dir, "0.16.2").use { it.storeId }
        // The file as servers up to 0.16.2 leave it (schema 1), holding one event.
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("db/calendar.sqlite")}").use { c ->
            c.createStatement().use { st ->
                listOf(
                    "CREATE TABLE store_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                    "INSERT INTO store_meta(key, value) VALUES ('store_id', '$storeId'), ('epoch', '3'), ('created_at', '1'), " +
                        "('server_version', '0.16.2')",
                    "CREATE TABLE records (kind TEXT NOT NULL, id TEXT NOT NULL, fields TEXT NOT NULL DEFAULT '{}', " +
                        "updated_at INTEGER NOT NULL, PRIMARY KEY (kind, id))",
                    "CREATE TABLE applied_ops (op_id TEXT PRIMARY KEY, status TEXT NOT NULL, message TEXT, device_id TEXT, applied_at INTEGER NOT NULL)",
                    "CREATE TABLE change_log (seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, record_id TEXT NOT NULL, at INTEGER NOT NULL)",
                    """INSERT INTO records VALUES ('event', 'e1', '{"title":{"value":"Врач","hlc":{"wall":1,"counter":0,"node":"phone"}}}', 1)""",
                    "INSERT INTO change_log(kind, record_id, at) VALUES ('event', 'e1', 1)",
                    "PRAGMA user_version = 1",
                ).forEach(st::execute)
            }
        }
        CalendarDatabase.open(dir, "0.17.0", storeId).use { calendar ->
            assertEquals(3, calendar.epoch, "the same store and epoch")
            val store = SqliteCalendarStore(calendar)
            assertEquals("Врач", store.record(CalendarKind.EVENT, "e1")?.fields?.getValue("title")?.value)
            CalendarRules(store).push(listOf(event("o2", "Стоматолог", 5)))
            assertEquals(listOf("Врач"), store.history(CalendarKind.EVENT, "e1").map { it.value })
        }
        val snapshots = dir.resolve("backups/calendar").listDirectoryEntries("*.sqlite").map { it.name }
        assertTrue(snapshots.any { "pre-migration-v1" in it }, "a verified snapshot first: $snapshots")
    }

    @Test
    fun theMirrorHasAnIcsPerCalendarAndFollowsRenames() {
        val db = Database.open(dir, "test")
        val calendar = CalendarDatabase.open(dir, "test", db.storeId)
        val jobs = BackupJobs(db, dir, null, calendar = calendar)
        val rules = CalendarRules(SqliteCalendarStore(calendar))
        rules.push(
            listOf(
                CalendarOp("c1", CalendarKind.CALENDAR, "work", mapOf("name" to "Работа", "color" to "storm"), Hlc(1, 0, "phone")),
                event("e1", "Врач", 2),
                CalendarOp(
                    "e2",
                    CalendarKind.EVENT,
                    "e2",
                    mapOf("title" to "Созвон", "start" to "2026-10-15T10:00", "calendar" to "work"),
                    Hlc(3, 0, "phone"),
                ),
            ),
        )
        jobs.runCalendar()
        val export = dir.resolve("export/calendars")
        assertEquals(
            setOf("Мои события.ics", "Работа.ics", "calendar.json"),
            export.listDirectoryEntries().map {
                it.name
            }.filter { !it.startsWith(".") }.toSet(),
        )
        val work = Ics.parse(export.resolve("Работа.ics").readText()).single()
        assertEquals(listOf("Созвон"), work.events.map { it.summary })
        assertEquals(IcsTime("20261015T100000"), work.events.single().start, "floating local time (ADR-028)")

        rules.push(listOf(CalendarOp("c2", CalendarKind.CALENDAR, "work", mapOf("name" to "Офис"), Hlc(4, 0, "phone"))))
        jobs.runCalendar()
        assertTrue(export.resolve("Офис.ics").exists())
        assertFalse(export.resolve("Работа.ics").exists(), "a renamed calendar's old file goes")
        calendar.close()
        db.close()
    }

    @Test
    fun restoringTheCalendarLeavesTheJournalAsItIs() {
        val db = Database.open(dir, "test")
        val storeId = db.storeId
        HighWater(dir).check(db)
        db.close()
        val journalBefore = dir.resolve("db/benbow.sqlite").readBytes()
        val calendar = CalendarDatabase.open(dir, "test", storeId)
        val highWater = HighWater(dir, CALENDAR_HIGH_WATER)
        highWater.check(calendar)
        val rules = CalendarRules(SqliteCalendarStore(calendar))
        rules.push(listOf(event("o1", "Врач", 1)))
        calendar.locked { calendar.backups!!.snapshot(it, "drill", calendar.latestCursor()) }
        rules.push(listOf(event("o2", "Стоматолог", 2)))
        highWater.record(calendar)
        calendar.close()

        val result = Restore.run(dir, "latest", Restore.CALENDAR)
        assertEquals(2, result.epoch)
        assertTrue(result.replacedInto!!.resolve("calendar.sqlite").exists(), "the replaced calendar is kept")
        assertTrue(journalBefore.contentEquals(dir.resolve("db/benbow.sqlite").readBytes()), "the journal file is untouched")
        CalendarDatabase.open(dir, "test", storeId).use { restored ->
            assertEquals(2, restored.epoch)
            assertFalse(highWater.check(restored), "a proper restore needs no second bump")
            val title = SqliteCalendarStore(restored).record(CalendarKind.EVENT, "e1")!!.fields.getValue("title").value
            assertEquals("Врач", title)
        }
        Database.open(dir, "test").use { assertEquals(1, it.epoch, "the journal's epoch stays") }
    }

    @Test
    fun aCalendarFileCopiedBackByHandIsNoticedAtStartup() {
        val storeId = Database.open(dir, "test").use { it.storeId }
        val highWater = HighWater(dir, CALENDAR_HIGH_WATER)
        val old = Files.createTempFile("calendar", ".sqlite")
        CalendarDatabase.open(dir, "test", storeId).use { calendar ->
            highWater.check(calendar)
            CalendarRules(SqliteCalendarStore(calendar)).push(listOf(event("o1", "Врач", 1)))
            calendar.locked { calendar.backups!!.snapshot(it, "drill", calendar.latestCursor()) }
            Files.copy(calendar.backups!!.file(calendar.backups!!.history().last()), old, StandardCopyOption.REPLACE_EXISTING)
            CalendarRules(SqliteCalendarStore(calendar)).push(listOf(event("o2", "Стоматолог", 2)))
            highWater.record(calendar)
        }
        Files.copy(old, dir.resolve("db/calendar.sqlite"), StandardCopyOption.REPLACE_EXISTING)
        listOf("calendar.sqlite-wal", "calendar.sqlite-shm").forEach { Files.deleteIfExists(dir.resolve("db").resolve(it)) }
        CalendarDatabase.open(dir, "test", storeId).use { reopened ->
            assertTrue(highWater.check(reopened))
            assertEquals(2, reopened.epoch)
        }
    }
}
