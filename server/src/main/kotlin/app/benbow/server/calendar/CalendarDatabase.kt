package app.benbow.server.calendar

import app.benbow.server.storage.Backups
import app.benbow.server.storage.EpochStore
import app.benbow.server.storage.configureDurable
import app.benbow.server.storage.meta
import app.benbow.server.storage.metaOrNull
import app.benbow.server.storage.setMeta
import app.benbow.server.storage.userVersion
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories

/**
 * The calendar store (ADR-027): its own SQLite file `db/calendar.sqlite` beside the journal's, with the same
 * durability settings, its own schema, migrations, epoch and snapshots. Calendar code never opens the journal's file.
 */
class CalendarDatabase private constructor(private val connection: Connection, val backups: Backups?) :
    AutoCloseable,
    EpochStore {
    private val lock = ReentrantLock()

    /** The journal's store id, written when the file was created: a calendar never joins another store (S11). */
    override val storeId: String = locked { it.meta("store_id") }

    @Volatile
    override var epoch: Long = locked { it.meta("epoch").toLong() }
        private set

    fun <T> locked(block: (Connection) -> T): T = lock.withLock { block(connection) }

    /** One SQL transaction; nested calls join the outer one. */
    fun <T> transaction(block: (Connection) -> T): T = locked { c ->
        if (!c.autoCommit) return@locked block(c)
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    override fun latestCursor(): Long = locked { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM change_log").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    override fun bumpEpoch(to: Long) = transaction { c ->
        require(to > epoch) { "Epoch can only grow ($epoch -> $to)" }
        c.setMeta("epoch", to.toString())
        epoch = to
    }

    override fun close() = locked { c ->
        c.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        c.close()
    }

    companion object {
        const val FILE = "calendar.sqlite"

        /**
         * Opens (or creates) the calendar file of the store [storeId]. Like the journal, an existing file gets a
         * verified snapshot before a migration or the first start of another server version (S10). A file that
         * belongs to another store is refused.
         */
        fun open(dataDir: Path, serverVersion: String, storeId: String): CalendarDatabase {
            val file = dataDir.resolve("db").createDirectories().resolve(FILE)
            val connection = DriverManager.getConnection("jdbc:sqlite:$file")
            try {
                val backups = Backups.calendar(dataDir)
                configureDurable(connection)
                val schema = connection.userVersion()
                if (schema in 1 until CalendarMigrations.SCHEMA_VERSION) {
                    backups.snapshot(connection, "pre-migration-v$schema")
                } else if (schema >= 1) {
                    val previous = connection.metaOrNull("server_version")
                    if (previous != serverVersion) backups.snapshot(connection, "pre-update-${previous ?: "unknown"}")
                }
                CalendarMigrations.apply(connection, storeId)
                val owner = connection.meta("store_id")
                check(owner == storeId) { "$file belongs to store $owner, not $storeId: put the right file back or move it away" }
                connection.setMeta("server_version", serverVersion)
                return CalendarDatabase(connection, backups)
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
        }

        /** Throwaway calendar store for tests: same schema and rules, no files, no backups. */
        fun inMemory(storeId: String = "test-store"): CalendarDatabase {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            configureDurable(connection)
            CalendarMigrations.apply(connection, storeId)
            return CalendarDatabase(connection, backups = null)
        }
    }
}

/** Forward-only schema steps of the calendar file, tracked by `PRAGMA user_version` (S10). */
internal object CalendarMigrations {
    const val SCHEMA_VERSION = 1

    private fun steps(storeId: String): List<List<String>> = listOf(
        // 1: identity, records (calendars and events as LWW fields), sync bookkeeping
        listOf(
            "CREATE TABLE store_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "INSERT INTO store_meta(key, value) VALUES ('store_id', '${storeId.replace("'", "''")}'), ('epoch', '1'), " +
                "('created_at', '${System.currentTimeMillis()}')",
            """CREATE TABLE records (
                kind TEXT NOT NULL,
                id TEXT NOT NULL,
                fields TEXT NOT NULL DEFAULT '{}',
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (kind, id)
            )""",
            """CREATE TABLE applied_ops (
                op_id TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                message TEXT,
                device_id TEXT,
                applied_at INTEGER NOT NULL
            )""",
            "CREATE TABLE change_log (seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, record_id TEXT NOT NULL, at INTEGER NOT NULL)",
        ),
    )

    fun apply(connection: Connection, storeId: String) {
        val version = connection.userVersion()
        check(version <= SCHEMA_VERSION) { "Calendar schema $version is newer than this server ($SCHEMA_VERSION). Update the server." }
        val steps = steps(storeId)
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
