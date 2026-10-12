package app.benbow.server

import app.benbow.core.sync.EntryState
import app.benbow.core.sync.RevisionRecord
import app.benbow.server.storage.Database
import app.benbow.server.storage.SqliteSyncStore
import app.benbow.testing.SyncSimulation
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StorageTest {
    @Test
    fun syncSimulationHoldsOnSqlite() = runTest {
        val simulation = SyncSimulation { SqliteSyncStore(Database.inMemory()) }
        checkAll(150, Arb.long()) { seed -> simulation.run(seed) }
        simulation.assertInterestingPathsCovered()
    }

    @Test
    fun revisionsAreAppendOnlyInTheDatabaseItself() {
        val db = Database.inMemory()
        val store = SqliteSyncStore(db)
        store.putEntry(EntryState("e1", "journal", "2026-10-13", 1))
        store.putRevision(RevisionRecord("r1", "e1", emptyList(), "{}", "edit"))
        assertFailsWith<SQLException> { db.locked { it.createStatement().execute("UPDATE revisions SET body = 'x'") } }
        assertFailsWith<SQLException> { db.locked { it.createStatement().execute("DELETE FROM revisions") } }
        assertEquals("{}", store.revision("r1")!!.body)
    }

    @Test
    fun upgradingAnExistingStoreMakesAVerifiedBackupFirst() {
        val dir = Files.createTempDirectory("benbow-migration")
        val file = dir.resolve("db").also { Files.createDirectories(it) }.resolve("benbow.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE store_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                st.execute("INSERT INTO store_meta VALUES ('store_id', 'old-store'), ('epoch', '1')")
                st.execute("PRAGMA user_version = 1")
            }
        }
        Database.open(dir, "1.0").use { db ->
            assertEquals("old-store", db.storeId, "identity survives the upgrade")
            val backup = db.backups!!.history().single()
            assertTrue(backup.verified && backup.reason == "pre-migration-v1")
            assertEquals(mapOf("store_meta" to 2L), backup.rows)
            assertTrue(dir.resolve("backups/db").listDirectoryEntries().single().exists())
        }
        Database.open(dir, "1.0").use { db -> assertEquals(1, db.backups!!.history().size, "same version: no new backup") }
        Database.open(dir, "1.1").use { db ->
            val update = db.backups!!.history().last()
            assertTrue(update.verified && update.reason == "pre-update-1.0", "a new server version backs up first: $update")
        }
    }

    @Test
    fun newerSchemaIsRefused() {
        val dir = Files.createTempDirectory("benbow-newer")
        Database.open(dir, "1.0").close()
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("db/benbow.sqlite")}").use {
            it.createStatement().execute("PRAGMA user_version = 99")
        }
        val error = assertFailsWith<IllegalStateException> { Database.open(dir, "1.0") }
        assertTrue("newer" in error.message.orEmpty())
    }
}
