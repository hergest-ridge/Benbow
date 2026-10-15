package app.benbow.server.storage

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories

/** What [HighWater] watches: a store's identity, epoch and latest change (data-safety S11). */
interface EpochStore {
    val storeId: String
    val epoch: Long

    fun latestCursor(): Long

    fun bumpEpoch(to: Long)
}

/**
 * The server's single SQLite connection with the durability settings of docs/data-safety.md
 * (S2, S3, S10). All access is serialized through one lock, like SQLite's single writer.
 */
class Database private constructor(private val connection: Connection, val backups: Backups?) :
    AutoCloseable,
    EpochStore {
    private val lock = ReentrantLock()

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

    /** Latest change-log sequence (0 for an empty store). */
    override fun latestCursor(): Long = locked { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM change_log").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    /** A new epoch tells devices the store went back in time and must be reconciled (docs/sync.md#store-identity). */
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
        /**
         * Opens the store. An existing store gets a verified backup before a schema migration or the
         * first start of a different server version (data-safety S10), so every update is undoable.
         */
        fun open(dataDir: Path, serverVersion: String): Database {
            val file = dataDir.resolve("db").createDirectories().resolve("benbow.sqlite")
            val connection = DriverManager.getConnection("jdbc:sqlite:$file")
            val backups = Backups(dataDir)
            configureDurable(connection)
            val schema = connection.userVersion()
            if (schema in 1 until Migrations.SCHEMA_VERSION) {
                backups.snapshot(connection, "pre-migration-v$schema")
            } else if (schema >= 1) {
                val previous = connection.metaOrNull("server_version")
                if (previous != serverVersion) backups.snapshot(connection, "pre-update-${previous ?: "unknown"}")
            }
            Migrations.apply(connection)
            connection.setMeta("server_version", serverVersion)
            return Database(connection, backups)
        }

        /** Throwaway database for tests: same schema and rules, no files, no backups. */
        fun inMemory(): Database {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            configureDurable(connection)
            Migrations.apply(connection)
            return Database(connection, backups = null)
        }
    }
}

/** WAL, full fsync, foreign keys; refuses a file that fails `quick_check` (data-safety S3, integrity checks). */
internal fun configureDurable(connection: Connection) {
    connection.createStatement().use { st ->
        st.execute("PRAGMA journal_mode=WAL")
        st.execute("PRAGMA synchronous=FULL")
        st.execute("PRAGMA foreign_keys=ON")
        st.execute("PRAGMA busy_timeout=5000")
        val check = st.executeQuery("PRAGMA quick_check").use { rs -> if (rs.next()) rs.getString(1) else "no result" }
        check(check == "ok") { "Database integrity check failed: $check. Restore from a verified backup." }
    }
}

internal fun Connection.meta(key: String): String = metaOrNull(key) ?: error("store_meta.$key missing")

internal fun Connection.metaOrNull(key: String): String? = prepareStatement("SELECT value FROM store_meta WHERE key = ?").use { st ->
    st.setString(1, key)
    st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
}

internal fun Connection.setMeta(key: String, value: String) {
    prepareStatement("INSERT INTO store_meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").use { st ->
        st.setString(1, key)
        st.setString(2, value)
        st.executeUpdate()
    }
}

internal fun Connection.userVersion(): Int = createStatement().use { st ->
    st.executeQuery("PRAGMA user_version").use { rs ->
        rs.next()
        rs.getInt(1)
    }
}
