package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

/** Installed apps keep their local DB across updates; every schema step must migrate it in place. */
class MigrationTest {
    @Test
    fun v1EntriesKeepTheirDataAndGainFieldClocks() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
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
        driver.execute(
            null,
            "INSERT INTO entry(id, kind, day, created_at, head_rev, body, fields, updated_at) VALUES ('e1', 'note', '2026-10-13', 1, 'r1', '{}', '{\"deleted\":\"true\"}', 1)",
            0,
        )
        BenbowDb.Schema.migrate(driver, 1, BenbowDb.Schema.version)
        val row = BenbowDb(driver).benbowQueries.allEntries().executeAsOne()
        assertEquals("r1", row.head_rev)
        assertEquals("{\"deleted\":\"true\"}", row.fields)
        assertEquals("{}", row.field_clocks)
    }

    @Test
    fun aNoteAlreadyOnTheBoardLeavesTheJournalWhenItsSpaceBecomesAColumn() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BenbowDb.Schema.create(driver)
        // A v6 file: no space column, no views (as the app before 7.17 K1 left it), one note marked by a newer device.
        listOf(
            "DROP VIEW journal_entry",
            "DROP VIEW board_entry",
            "DROP INDEX entry_by_space",
            "ALTER TABLE entry DROP COLUMN space",
            "ALTER TABLE entry DROP COLUMN card",
        )
            .forEach { driver.execute(null, it, 0) }
        driver.execute(
            null,
            "INSERT INTO entry(id, kind, day, created_at, head_rev, body, fields, updated_at) VALUES " +
                "('n1', 'note', '2026-10-20', 1, 'r1', '{}', '{\"space\":\"board\"}', 1), " +
                "('j1', 'note', '2026-10-20', 2, 'r2', '{}', '{}', 2)",
            0,
        )
        BenbowDb.Schema.migrate(driver, 6, BenbowDb.Schema.version)
        val q = BenbowDb(driver).benbowQueries
        assertEquals(listOf("j1"), q.journal().executeAsList().map { it.id })
        assertEquals(listOf("n1"), q.boardNotes().executeAsList().map { it.id })
    }
}
