package app.benbow.server

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.createDirectories

/**
 * The server's SQLite store with the durability settings of docs/data-safety.md (S2, S3, S10).
 * Spike scope: identity metadata only; entries, revisions and the change log come in Phase 2.
 */
class Store private constructor(private val connection: Connection) : AutoCloseable {
    val storeId: String = meta("store_id")
    val epoch: Long = meta("epoch").toLong()

    private fun meta(key: String): String =
        connection.prepareStatement("SELECT value FROM store_meta WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else error("store_meta.$key missing") }
        }

    override fun close() {
        connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        connection.close()
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun open(dataDir: Path): Store {
            val file = dataDir.resolve("db").createDirectories().resolve("benbow.sqlite")
            val connection = DriverManager.getConnection("jdbc:sqlite:$file")
            connection.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=FULL")
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA busy_timeout=5000")
                val check = st.executeQuery("PRAGMA quick_check").use { rs -> if (rs.next()) rs.getString(1) else "no result" }
                check(check == "ok") { "Database integrity check failed: $check. Restore from a verified backup." }
            }
            migrate(connection)
            return Store(connection)
        }

        private fun migrate(connection: Connection) {
            val version = connection.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
            check(version <= SCHEMA_VERSION) { "Database schema $version is newer than this server ($SCHEMA_VERSION). Update the server." }
            if (version == SCHEMA_VERSION) return
            connection.autoCommit = false
            try {
                connection.createStatement().use { st ->
                    st.execute("CREATE TABLE store_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    st.execute("INSERT INTO store_meta(key, value) VALUES ('store_id', '${UUID.randomUUID()}'), ('epoch', '1'), ('created_at', '${System.currentTimeMillis()}')")
                    st.execute("PRAGMA user_version = $SCHEMA_VERSION")
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
