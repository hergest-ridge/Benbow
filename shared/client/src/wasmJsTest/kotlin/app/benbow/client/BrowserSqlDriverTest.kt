package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The browser's database (ADR-029): the journal's schema and queries on sql.js, and its files on the shelf. */
class BrowserSqlDriverTest {
    private class MemoryShelf : FileShelf {
        val files = mutableMapOf<String, JsAny>()

        override suspend fun load(key: String): JsAny? = files[key]

        override suspend fun store(key: String, bytes: JsAny) {
            files[key] = bytes
        }
    }

    @Test
    fun aNoteSurvivesAReloadAndIsFoundBySearch() = runTest {
        val shelf = MemoryShelf()
        val driver = BrowserSqlDriver.open("benbow.db", BenbowDb.Schema, shelf)
        val id = JournalRepository(BenbowDb(driver), { 1_760_000_000_123 }).createNote("2026-10-15", "Ёлка у дома")
        driver.flush()
        assertFalse(driver.unsaved)

        val repo = JournalRepository(BenbowDb(BrowserSqlDriver.open("benbow.db", BenbowDb.Schema, shelf)), { 0 })
        val entry = repo.journal().first().single()
        assertEquals(id, entry.id)
        assertEquals(1_760_000_000_123, entry.createdAt)
        assertEquals(listOf(id), repo.search(SearchQuery("елка")).first().map { it.id }, "FTS4 with the app's own ё folding")
    }

    @Test
    fun numbersKeepEveryBit() = runTest {
        val driver = BrowserSqlDriver.open("numbers.db", BenbowDb.Schema, MemoryShelf())
        driver.execute(null, "CREATE TABLE n(i INTEGER, v INTEGER, d REAL, b BLOB)", 0)
        val longs = listOf(0L, -1L, 1_760_000_000_123L, (1L shl 53) + 1, Long.MAX_VALUE, Long.MIN_VALUE)
        longs.forEachIndexed { i, v ->
            driver.execute(null, "INSERT INTO n VALUES (?, ?, ?, ?)", 4) {
                bindLong(0, i.toLong())
                bindLong(1, v)
                bindDouble(2, 0.1 * i)
                bindBytes(3, byteArrayOf(-1, 0, i.toByte()))
            }
        }
        val back = driver.executeQuery(null, "SELECT v, d, b FROM n ORDER BY i", { c ->
            val rows = mutableListOf<Triple<Long?, Double?, List<Byte>?>>()
            while (c.next().value) rows += Triple(c.getLong(0), c.getDouble(1), c.getBytes(2)?.toList())
            QueryResult.Value(rows)
        }, 0).value
        assertEquals(longs, back.map { it.first })
        assertEquals(longs.indices.map { 0.1 * it }, back.map { it.second })
        assertEquals(longs.indices.map { listOf<Byte>(-1, 0, it.toByte()) }, back.map { it.third })
        val found = driver.executeQuery(null, "SELECT COUNT(*) FROM n WHERE v = ?", { c ->
            c.next()
            QueryResult.Value(c.getLong(0))
        }, 1) {
            bindLong(0, Long.MAX_VALUE)
        }.value
        assertEquals(1, found, "a bound 64-bit value matches the stored one")
    }

    @Test
    fun aFailedTransactionLeavesNothing() = runTest {
        val driver = BrowserSqlDriver.open("tx.db", BenbowDb.Schema, MemoryShelf())
        val repo = JournalRepository(BenbowDb(driver), { 1 })
        runCatching {
            BenbowDb(driver).transaction {
                repo.createNote("2026-10-15", "half")
                error("power cut")
            }
        }
        assertTrue(repo.journal().first().isEmpty())
    }

    /** The journal as the first apps wrote it: just the entry table. */
    private object Version1 : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 1L

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(
                null,
                """
                CREATE TABLE entry (
                    id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, day TEXT NOT NULL, created_at INTEGER NOT NULL,
                    head_rev TEXT, body TEXT NOT NULL, title TEXT NOT NULL DEFAULT '', preview TEXT NOT NULL DEFAULT '',
                    conflict_heads TEXT NOT NULL DEFAULT '[]', fields TEXT NOT NULL DEFAULT '{}', updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
                0,
            )
            return QueryResult.Unit
        }

        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Unit
    }

    @Test
    fun anOldFileIsKeptBeforeMigrating() = runTest {
        val shelf = MemoryShelf()
        val old = BrowserSqlDriver.open("benbow.db", Version1, shelf)
        old.execute(
            null,
            "INSERT INTO entry(id, kind, day, created_at, head_rev, body, updated_at) VALUES ('e1', 'note', '2026-10-13', 1, 'r1', '{}', 1)",
            0,
        )
        old.flush()
        val before = shelf.files.getValue("benbow.db")

        val migrated = BenbowDb(BrowserSqlDriver.open("benbow.db", BenbowDb.Schema, shelf))
        assertEquals("r1", migrated.benbowQueries.allEntries().executeAsOne().head_rev)
        val kept = shelf.files.entries.single { it.key.startsWith("benbow.db.before-v${BenbowDb.Schema.version}-") }
        assertTrue(kept.value === before, "the version 1 file, as it was")
        assertTrue(shelf.files.getValue("benbow.db") !== before, "the migrated file saved right away")
    }

    @Test
    fun aDamagedFileIsKeptAsideNotLost() = runTest {
        val shelf = MemoryShelf()
        val junk = ByteArray(4096) { 7 }.toUint8Array()
        shelf.files["benbow.db"] = junk
        val driver = BrowserSqlDriver.open("benbow.db", BenbowDb.Schema, shelf)
        assertTrue(JournalRepository(BenbowDb(driver), { 1 }).journal().first().isEmpty())
        val aside = shelf.files.entries.single { it.key.startsWith("benbow.db.damaged-") }
        assertTrue(aside.value === junk)
    }
}
