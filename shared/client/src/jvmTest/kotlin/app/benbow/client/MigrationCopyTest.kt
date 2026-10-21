package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A copy of the device's database before a newer build migrates it (data-safety "Client-side safety", ADR-042 U3). */
class MigrationCopyTest {
    private val dir = Files.createTempDirectory("benbow-device").toFile()
    private val file = File(dir, "benbow.db")
    private val version = BenbowDb.Schema.version

    private fun userVersion(f: File) = DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c ->
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use {
                it.next()
                it.getLong(1)
            }
        }
    }

    /** A file as an older build left it: this schema, a draft-like row, and an older version number. */
    private fun olderFile() {
        val properties = Properties().apply { put("journal_mode", "WAL") }
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", properties, BenbowDb.Schema).use { driver ->
            BenbowDb(driver).benbowQueries.putValue("draft", "Полстроки, ещё не отправленные")
            driver.execute(null, "PRAGMA user_version = ${version - 1}", 0)
        }
    }

    @Test
    fun anOlderFileIsCopiedWholeBeforeItsMigration() {
        olderFile()
        val copy = assertNotNull(keepBeforeMigrating(file, version, now = 1_000))
        assertEquals("benbow.db.before-v$version-1000", copy.name)
        assertEquals(version - 1, userVersion(copy))
        JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}").use { driver ->
            assertEquals("Полстроки, ещё не отправленные", BenbowDb(driver).benbowQueries.getValue("draft").executeAsOne())
        }
        assertEquals(version - 1, userVersion(file), "the file itself is left for the migration")
    }

    @Test
    fun nothingIsCopiedWithoutAMigration() {
        assertNull(keepBeforeMigrating(file, version), "no file yet: a first start")
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", Properties(), BenbowDb.Schema).close()
        assertNull(keepBeforeMigrating(file, version), "already at this version")
        assertEquals(listOf("benbow.db"), dir.list()!!.filter { !it.endsWith("-wal") && !it.endsWith("-shm") })
    }

    @Test
    fun theNewestThreeCopiesStay() {
        olderFile()
        for (t in 1L..5L) keepBeforeMigrating(file, version, now = t)
        val copies = dir.list()!!.filter { it.contains(".before-v") }.sorted()
        assertEquals(listOf(3L, 4L, 5L).map { "benbow.db.before-v$version-$it" }, copies)
        assertTrue(file.isFile)
    }
}
