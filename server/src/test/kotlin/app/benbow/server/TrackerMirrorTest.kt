package app.benbow.server

import app.benbow.core.calendar.CalendarOp
import app.benbow.core.calendar.CalendarRules
import app.benbow.core.sync.Hlc
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerKind
import app.benbow.server.backup.BackupJobs
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.calendar.SqliteCalendarStore
import app.benbow.server.storage.Database
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `export/trackers/` (ADR-050): a folder per tracker outside the trash, following renames, beside the calendar's files. */
class TrackerMirrorTest {
    private val dir = Files.createTempDirectory("benbow-trackers")

    private fun op(id: String, kind: String, record: String, fields: Map<String, String?>, wall: Long) =
        CalendarOp(id, kind, record, fields, Hlc(wall, 0, "phone"))

    @Test
    fun eachTrackerHasAFolderThatFollowsRenamesAndTheTrash() {
        val db = Database.open(dir, "test")
        val calendar = CalendarDatabase.open(dir, "test", db.storeId)
        val jobs = BackupJobs(db, dir, null, calendar = calendar)
        val rules = CalendarRules(SqliteCalendarStore(calendar))
        val export = dir.resolve("export/trackers")
        jobs.runCalendar()
        assertFalse(export.exists(), "no tracker, no folder")

        rules.push(
            listOf(
                op("o1", TrackerKind.TRACKER, "t1", mapOf("name" to "Привычки"), 1),
                op("o2", TrackerKind.COLUMN, "walk", mapOf("tracker" to "t1", "name" to "Прогулка", "type" to "check"), 2),
                op("o3", TrackerKind.CELL, "walk:2026-10-27", TrackerCells.create("walk", "2026-10-27") + ("value" to "true"), 3),
            ),
        )
        jobs.runCalendar()
        assertEquals(setOf("tracker.json", "2026.csv"), export.resolve("Привычки").listDirectoryEntries().map { it.name }.toSet())
        assertTrue("2026-10-27;да" in export.resolve("Привычки/2026.csv").readText())
        val own = export.resolve("Привычки/мои заметки.txt").also { it.writeText("моё") }

        rules.push(listOf(op("o4", TrackerKind.TRACKER, "t1", mapOf("name" to "Каждый день"), 4)))
        jobs.runCalendar()
        assertTrue(export.resolve("Каждый день/tracker.json").exists())
        assertEquals(listOf("tracker.json", "2026.csv").map { export.resolve("Привычки").resolve(it).exists() }, listOf(false, false))
        assertTrue(own.exists(), "a file the mirror didn't write is left alone, and so is its folder")

        rules.push(listOf(op("o5", TrackerKind.TRACKER, "t1", mapOf("deleted" to "true"), 5)))
        jobs.runCalendar()
        val folders = export.listDirectoryEntries().filter { it.isDirectory() }.map { it.name }
        assertEquals(listOf("Привычки"), folders, "one in the trash goes too; the owner's own file keeps its folder")
        assertTrue(dir.resolve("export/calendars").exists(), "the calendar's files stay where they are")
        calendar.close()
        db.close()
    }
}
