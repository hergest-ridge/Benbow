package app.benbow.server.storage

import java.sql.Connection
import java.util.UUID

/**
 * Forward-only schema steps tracked by `PRAGMA user_version` (data-safety S10). The caller backs up
 * an existing database before calling [apply]; a newer schema is refused.
 */
object Migrations {
    const val SCHEMA_VERSION = 3

    private val steps: List<List<String>> = listOf(
        // 1: store identity
        listOf(
            "CREATE TABLE store_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "INSERT INTO store_meta(key, value) VALUES ('store_id', '${UUID.randomUUID()}'), ('epoch', '1'), " +
                "('created_at', '${System.currentTimeMillis()}')",
        ),
        // 2: entries, append-only revisions, sync bookkeeping, devices
        listOf(
            """CREATE TABLE entries (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                day TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                head_rev TEXT,
                conflict_heads TEXT NOT NULL DEFAULT '[]',
                fields TEXT NOT NULL DEFAULT '{}'
            )""",
            """CREATE TABLE revisions (
                id TEXT PRIMARY KEY,
                entry_id TEXT NOT NULL REFERENCES entries(id),
                parents TEXT NOT NULL,
                body TEXT NOT NULL,
                body_sha256 TEXT NOT NULL,
                origin TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""",
            "CREATE INDEX revisions_by_entry ON revisions(entry_id, created_at)",
            // S1 enforced by the database itself: revisions are never changed or removed.
            "CREATE TRIGGER revisions_no_update BEFORE UPDATE ON revisions BEGIN SELECT RAISE(ABORT, 'revisions are append-only'); END",
            "CREATE TRIGGER revisions_no_delete BEFORE DELETE ON revisions BEGIN SELECT RAISE(ABORT, 'revisions are append-only'); END",
            """CREATE TABLE applied_ops (
                op_id TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                message TEXT,
                device_id TEXT,
                applied_at INTEGER NOT NULL
            )""",
            "CREATE TABLE change_log (seq INTEGER PRIMARY KEY AUTOINCREMENT, entry_id TEXT NOT NULL, at INTEGER NOT NULL)",
            """CREATE TABLE devices (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                platform TEXT NOT NULL,
                token_hash TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                last_seen_at INTEGER,
                revoked_at INTEGER
            )""",
        ),
        // 3: media files by SHA-256 (ADR-009) and resumable uploads
        listOf(
            """CREATE TABLE blobs (
                sha256 TEXT PRIMARY KEY,
                size INTEGER NOT NULL,
                mime TEXT NOT NULL,
                path TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                verified_at INTEGER NOT NULL
            )""",
            """CREATE TABLE uploads (
                id TEXT PRIMARY KEY,
                sha256 TEXT NOT NULL,
                size INTEGER NOT NULL,
                mime TEXT NOT NULL,
                name TEXT,
                captured_at INTEGER,
                received INTEGER NOT NULL,
                device_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""",
            "CREATE INDEX uploads_by_sha ON uploads(sha256)",
            // Attachment links of an entry (AttachmentState by attachment id, LWW per link)
            "ALTER TABLE entries ADD COLUMN attachments TEXT NOT NULL DEFAULT '{}'",
        ),
    )

    init {
        check(steps.size == SCHEMA_VERSION)
    }

    fun apply(connection: Connection) {
        val version = connection.userVersion()
        check(version <= SCHEMA_VERSION) { "Database schema $version is newer than this server ($SCHEMA_VERSION). Update the server." }
        if (version == SCHEMA_VERSION) return
        for (target in version + 1..SCHEMA_VERSION) {
            connection.autoCommit = false
            try {
                connection.createStatement().use { st ->
                    steps[target - 1].forEach(st::execute)
                    st.execute("PRAGMA user_version = $target")
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }
}
