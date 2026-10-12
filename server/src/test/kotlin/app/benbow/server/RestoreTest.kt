package app.benbow.server

import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.SyncRules
import app.benbow.server.storage.Database
import app.benbow.server.storage.HighWater
import app.benbow.server.storage.Restore
import app.benbow.server.storage.SqliteSyncStore
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runbook step 1 (data-safety.md): restoring a snapshot keeps the old files and moves the store to a new epoch. */
class RestoreTest {
    private val dir = Files.createTempDirectory("benbow-restore")

    private fun Database.create(id: String) =
        SyncRules(SqliteSyncStore(this)).push(listOf(CreateEntry("op-$id", id, "journal", "2026-10-13", 1)))

    private fun Database.snapshot() = locked { backups!!.snapshot(it, "drill", latestCursor()) }

    @Test
    fun restoreKeepsTheReplacedDatabaseAndBumpsTheEpoch() {
        val db = Database.open(dir, "test")
        HighWater(dir).check(db)
        db.create("before")
        db.snapshot()
        db.create("after")
        HighWater(dir).record(db)
        val store = db.storeId
        db.close()

        val result = Restore.run(dir, "latest")
        assertEquals(2, result.epoch)
        assertFalse(result.otherStore)
        assertTrue(result.replacedInto!!.resolve("benbow.sqlite").exists(), "nothing is deleted")

        val restored = Database.open(dir, "test")
        assertFalse(HighWater(dir).check(restored), "a proper restore needs no second bump")
        assertEquals(store, restored.storeId)
        assertEquals(2, restored.epoch)
        assertTrue(SqliteSyncStore(restored).entry("before") != null)
        assertTrue(SqliteSyncStore(restored).entry("after") == null)
        restored.close()

        // Restoring again (say, an even older snapshot) still moves forward.
        assertEquals(3, Restore.run(dir, "latest").epoch)
        assertEquals(2, dir.resolve("db").listDirectoryEntries("replaced-*").size)
    }

    @Test
    fun aDatabaseCopiedBackByHandIsNoticedAtStartup() {
        val db = Database.open(dir, "test")
        HighWater(dir).check(db)
        db.create("before")
        val snapshot = db.backups!!.file(db.snapshot())
        db.create("after")
        HighWater(dir).record(db)
        db.close()

        val live = dir.resolve("db").resolve("benbow.sqlite")
        Files.copy(snapshot, live, StandardCopyOption.REPLACE_EXISTING)
        dir.resolve("db").listDirectoryEntries("benbow.sqlite-*").forEach(Files::delete)

        val reopened = Database.open(dir, "test")
        assertTrue(HighWater(dir).check(reopened))
        assertEquals(2, reopened.epoch, "devices will reconcile instead of trusting reused change numbers")
        assertTrue(snapshot.name.startsWith("benbow-"))
        reopened.close()
    }
}
