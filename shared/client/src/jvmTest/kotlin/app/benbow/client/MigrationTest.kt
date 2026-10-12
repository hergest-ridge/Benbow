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
}
