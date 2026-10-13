package app.benbow.server

import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.PutRevision
import app.benbow.core.sync.SetField
import app.benbow.core.sync.SyncRules
import app.benbow.server.backup.BackupJobs
import app.benbow.server.backup.Retention
import app.benbow.server.storage.BackupRecord
import app.benbow.server.storage.Database
import app.benbow.server.storage.SqliteSyncStore
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupTest {
    private val body = """{"v":1,"blocks":[
{"_key":"k1","_type":"block","style":"h1","children":[{"_type":"span","text":"Вечер у моря","marks":[]}],"markDefs":[]},
{"_key":"k2","_type":"block","style":"normal","children":[{"_type":"span","text":"Тихо.","marks":["strong"]}],"markDefs":[]}
]}"""

    @Test
    fun snapshotsOnlyWhenSomethingChangedAndMirrorsThem() {
        val data = Files.createTempDirectory("benbow-backup")
        val mirror = Files.createTempDirectory("benbow-mirror")
        Database.open(data, "test").use { db ->
            val jobs = BackupJobs(db, data, mirror)
            val rules = SyncRules(SqliteSyncStore(db))
            rules.push(
                listOf(
                    CreateEntry("o1", "entry-0001", "journal", "2026-10-13", 1),
                    PutRevision("o2", "entry-0001", "r1", emptyList(), body),
                ),
            )

            assertTrue(jobs.runOnce(), "first change gets a snapshot")
            assertFalse(jobs.runOnce(), "nothing changed: no new snapshot")
            rules.push(
                listOf(
                    SetField("o3", "entry-0001", "mood", "спокойно", Hlc(5, 0, "phone")),
                    SetField("o4", "entry-0001", "tag:море", "Море", Hlc(6, 0, "phone")),
                    SetField("o5", "entry-0001", "reaction:❤️", "1", Hlc(7, 0, "phone")),
                ),
            )
            Thread.sleep(1_100) // snapshot names have second resolution
            assertTrue(jobs.runOnce())

            val history = db.backups!!.history()
            assertEquals(2, history.size)
            assertTrue(history.all { it.verified })
            assertEquals(
                history.map {
                    it.file
                }.toSet(),
                mirror.resolve("db").listDirectoryEntries().map { it.fileName.toString() }.toSet(),
            )
            assertTrue(mirror.resolve("manifest.json").exists())

            val md = data.resolve("export/2026/10").listDirectoryEntries("*.md").single().readText()
            assertTrue("title: \"Вечер у моря\"" in md, md)
            assertTrue("mood: \"спокойно\"" in md, md)
            assertTrue("tags: [\"Море\"]" in md, md)
            assertTrue("reactions: [\"❤️\"]" in md, md)
            assertTrue("**Тихо.**" in md, md)
            assertTrue(data.resolve("export/2026/10").listDirectoryEntries("*.json").single().readText().contains("\"body\""))
        }
    }

    @Test
    fun retentionKeepsRecentDailyWeeklyMonthlyAndPreUpdate() {
        val day = 24 * 3_600_000L
        val now = 400 * day
        fun record(name: String, ageMs: Long, reason: String = "scheduled", verified: Boolean = true) =
            BackupRecord(name, reason, now - ageMs, verified, emptyMap())
        val records = listOf(
            record("1h", 3_600_000),
            record("2h", 2 * 3_600_000),
            record("3d-early", 67 * 3_600_000), // same calendar day (05:00) as 3d-late (20:00)
            record("3d-late", 52 * 3_600_000),
            record("20d", 20 * day),
            record("100d", 100 * day),
            record("500d", 500 * day),
            record("pre-old", 60 * day, reason = "pre-update-0.1.0"),
            record("broken", 2 * day, verified = false),
        )
        val keep = Retention.keep(records, now, ZoneOffset.UTC)
        assertTrue(listOf("1h", "2h", "3d-late", "20d", "100d", "pre-old", "broken").all { it in keep }, "$keep")
        assertFalse("3d-early" in keep, "older snapshot of the same day goes")
        assertFalse("500d" in keep, "older than a year goes")
    }
}
