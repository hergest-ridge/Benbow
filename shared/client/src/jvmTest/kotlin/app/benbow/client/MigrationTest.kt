package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.Hlc
import app.cash.sqldelight.db.QueryResult
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
        val driver = v8()
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

    @Test
    fun attachmentLinksAlreadyHereBecomeRowsAndLaterWritesKeepThemInStep() {
        val driver = v8()
        val link = AttachmentState(AttachmentMeta("a", "f".repeat(64), 10, "image/jpeg", "image"), 1.0, present = false, hlc = Hlc.ZERO)
        val attachments = ClientJson.encodeToString(attachmentsSerializer, mapOf("a" to link))
        driver.execute(
            null,
            "INSERT INTO entry(id, kind, day, created_at, head_rev, body, fields, updated_at, attachments) VALUES " +
                "('e1', 'journal', '2026-10-20', 1, 'r1', '{}', '{}', 1, '$attachments'), " +
                "('e2', 'journal', '2026-10-20', 2, 'r2', '{}', '{}', 2, 'not json')",
            0,
        )
        BenbowDb.Schema.migrate(driver, 8, BenbowDb.Schema.version)
        val q = BenbowDb(driver).benbowQueries
        assertEquals(
            listOf(Triple("e1", "a", 0L)),
            q.attachmentRefs().executeAsList().map {
                Triple(it.entry_id, it.attachment_id, it.present)
            },
        )
        q.updateAttachments("{}", 0, null, 3, "e1")
        assertEquals(emptyList(), q.attachmentRefs().executeAsList())
    }

    @Test
    fun aMigratedFileHasEveryTableIndexViewAndTriggerOfANewOne() {
        val migrated = v8().also { BenbowDb.Schema.migrate(it, 8, BenbowDb.Schema.version) }
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { BenbowDb.Schema.create(it) }
        assertEquals(schemaOf(fresh), schemaOf(migrated))
    }

    /** A v8 file: the current schema without what 8.sqm brings. */
    private fun v8(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BenbowDb.Schema.create(driver)
        listOf("attachment_ref_insert", "attachment_ref_update", "attachment_ref_delete").forEach {
            driver.execute(null, "DROP TRIGGER $it", 0)
        }
        driver.execute(null, "DROP TABLE attachment_ref", 0)
        return driver
    }

    private fun schemaOf(driver: JdbcSqliteDriver): List<String> = driver.executeQuery(
        null,
        "SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
        { cursor ->
            QueryResult.Value(
                buildList {
                    while (cursor.next().value) {
                        add("${cursor.getString(0)} ${cursor.getString(1)}: ${cursor.getString(2)?.replace(Regex("\\s+"), " ")}")
                    }
                },
            )
        },
        0,
    ).value
}
