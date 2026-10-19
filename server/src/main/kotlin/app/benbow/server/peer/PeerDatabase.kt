package app.benbow.server.peer

import app.benbow.server.storage.configureDurable
import app.benbow.server.storage.userVersion
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories

data class Contact(val id: String, val name: String, val card: PeerCard, val addedAt: Long)

/** A letter waiting to go or gone and not yet acknowledged. */
data class Outgoing(
    val contact: String,
    val seq: Long,
    val letterId: String,
    val kind: String,
    val text: String?,
    val createdAt: Long,
    val sentAt: Long?,
)

/** A test note either way, for the admin page. */
data class NoteView(
    val outgoing: Boolean,
    val seq: Long,
    val text: String,
    val at: Long,
    val sentAt: Long?,
    val ackedAt: Long?,
    val receivedAt: Long?,
)

/** The shared mailbox. [secure] is false only in tests against a local server. */
data class MailSettings(val host: String, val port: Int, val user: String, val password: String, val secure: Boolean = true) {
    val complete get() = host.isNotBlank() && user.isNotBlank() && password.isNotBlank() && port in 1..65535
}

/**
 * The link with friends' servers (ADR-039): contacts, letters out and in, mailbox settings. Its own file
 * `db/peers.sqlite` with the journal's durability settings; nothing here ever touches the journal's file.
 */
class PeerDatabase private constructor(private val connection: Connection) : AutoCloseable {
    private val lock = ReentrantLock()

    private fun <T> transaction(block: (Connection) -> T): T = lock.withLock {
        val c = connection
        if (!c.autoCommit) return@withLock block(c)
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

    fun contacts(): List<Contact> = transaction { c ->
        c.prepareStatement("SELECT id, name, card, added_at FROM contacts WHERE removed_at IS NULL ORDER BY added_at").use { st ->
            st.executeQuery().use { rs -> rs.all { Contact(getString(1), getString(2), PeerCard.decode(getString(3)), getLong(4)) } }
        }
    }

    /** Adds the card, or brings a removed contact back under the new name; its letters are kept either way. */
    fun addContact(name: String, card: PeerCard, now: Long): Contact = transaction { c ->
        c.prepareStatement(
            "INSERT INTO contacts(id, name, card, added_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET name = excluded.name, removed_at = NULL",
        ).use { st ->
            st.setString(1, card.id)
            st.setString(2, name)
            st.setString(3, card.encode())
            st.setLong(4, now)
            st.executeUpdate()
        }
        Contact(card.id, name, card, now)
    }

    fun removeContact(id: String, now: Long) = transaction { c ->
        c.prepareStatement("UPDATE contacts SET removed_at = ? WHERE id = ?").use { st ->
            st.setLong(1, now)
            st.setString(2, id)
            st.executeUpdate()
        }
    }

    /** Queues a letter to [contact] under the next number; it goes with the courier's next round. */
    fun enqueue(contact: String, kind: String, text: String?, now: Long): Outgoing = transaction { c ->
        val seq = c.prepareStatement("SELECT COALESCE(MAX(seq), 0) + 1 FROM outbox WHERE contact = ?").use { st ->
            st.setString(1, contact)
            st.executeQuery().use { rs -> rs.next().let { rs.getLong(1) } }
        }
        val out = Outgoing(contact, seq, UUID.randomUUID().toString(), kind, text, now, null)
        c.prepareStatement("INSERT INTO outbox(contact, seq, letter_id, kind, text, created_at) VALUES (?, ?, ?, ?, ?, ?)").use { st ->
            st.setString(1, contact)
            st.setLong(2, seq)
            st.setString(3, out.letterId)
            st.setString(4, kind)
            st.setString(5, text)
            st.setLong(6, now)
            st.executeUpdate()
        }
        out
    }

    /** Letters to [contact] never sent, and those sent before [sentBefore] and still not acknowledged, in order. */
    fun due(contact: String, sentBefore: Long): List<Outgoing> = transaction { c ->
        c.prepareStatement(
            "SELECT contact, seq, letter_id, kind, text, created_at, sent_at FROM outbox " +
                "WHERE contact = ? AND acked_at IS NULL AND (sent_at IS NULL OR sent_at < ?) ORDER BY seq",
        ).use { st ->
            st.setString(1, contact)
            st.setLong(2, sentBefore)
            st.executeQuery().use { rs -> rs.all { outgoing() } }
        }
    }

    fun markSent(contact: String, seq: Long, now: Long) = transaction { c ->
        c.prepareStatement("UPDATE outbox SET sent_at = ? WHERE contact = ? AND seq = ?").use { st ->
            st.setLong(1, now)
            st.setString(2, contact)
            st.setLong(3, seq)
            st.executeUpdate()
        }
    }

    /** Marks letters [contact] says it has kept; returns how many weren't marked before. */
    fun acknowledge(contact: String, seqs: Collection<Long>, now: Long): Int = transaction { c ->
        c.prepareStatement("UPDATE outbox SET acked_at = ? WHERE contact = ? AND seq = ? AND acked_at IS NULL").use { st ->
            seqs.sumOf { seq ->
                st.setLong(1, now)
                st.setString(2, contact)
                st.setLong(3, seq)
                st.executeUpdate()
            }
        }
    }

    /** Keeps a letter from [contact]; false when it was kept before (a letter sent twice). */
    fun receive(contact: String, letter: Letter, now: Long): Boolean = transaction { c ->
        c.prepareStatement(
            "INSERT OR IGNORE INTO inbox(contact, seq, letter_id, kind, text, sent_at, received_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setString(1, contact)
            st.setLong(2, letter.seq)
            st.setString(3, letter.id)
            st.setString(4, letter.kind)
            st.setString(5, letter.text)
            st.setLong(6, letter.sentAt)
            st.setLong(7, now)
            st.executeUpdate() == 1
        }
    }

    /** The latest notes both ways, newest first. */
    fun notes(contact: String, limit: Int): List<NoteView> = transaction { c ->
        c.prepareStatement(
            "SELECT 1, seq, text, created_at, sent_at, acked_at, NULL FROM outbox " +
                "WHERE contact = ? AND kind = '${Letter.NOTE}' " +
                "UNION ALL SELECT 0, seq, text, sent_at, sent_at, NULL, received_at FROM inbox " +
                "WHERE contact = ? AND kind = '${Letter.NOTE}' " +
                "ORDER BY 4 DESC, 2 DESC LIMIT ?",
        ).use { st ->
            st.setString(1, contact)
            st.setString(2, contact)
            st.setInt(3, limit)
            st.executeQuery().use { rs ->
                rs.all {
                    NoteView(getInt(1) == 1, getLong(2), getString(3).orEmpty(), getLong(4), longOrNull(5), longOrNull(6), longOrNull(7))
                }
            }
        }
    }

    fun mailSettings(): MailSettings? = transaction { c ->
        val values = c.createStatement().use { st ->
            st.executeQuery("SELECT key, value FROM settings").use { rs -> rs.all { getString(1) to getString(2) } }.toMap()
        }
        val host = values["mail.host"] ?: return@transaction null
        MailSettings(host, values["mail.port"]?.toIntOrNull() ?: 993, values["mail.user"].orEmpty(), values["mail.password"].orEmpty())
    }

    /** A blank [MailSettings.password] keeps the stored one, so the page never has to show it. */
    fun saveMailSettings(settings: MailSettings) = transaction { c ->
        val password = settings.password.ifBlank { mailSettings()?.password.orEmpty() }
        c.prepareStatement("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)").use { st ->
            for ((k, v) in listOf(
                "mail.host" to settings.host,
                "mail.port" to "${settings.port}",
                "mail.user" to settings.user,
                "mail.password" to password,
            )) {
                st.setString(1, k)
                st.setString(2, v)
                st.executeUpdate()
            }
        }
    }

    override fun close() = lock.withLock {
        connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        connection.close()
    }

    private fun ResultSet.outgoing() =
        Outgoing(getString(1), getLong(2), getString(3), getString(4), getString(5), getLong(6), longOrNull(7))

    companion object {
        const val FILE = "peers.sqlite"

        fun open(dataDir: Path): PeerDatabase {
            val file = dataDir.resolve("db").createDirectories().resolve(FILE)
            val connection = DriverManager.getConnection("jdbc:sqlite:$file")
            try {
                configureDurable(connection)
                PeerMigrations.apply(connection)
                return PeerDatabase(connection)
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
        }

        fun inMemory(): PeerDatabase {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            configureDurable(connection)
            PeerMigrations.apply(connection)
            return PeerDatabase(connection)
        }
    }
}

private fun <T> ResultSet.all(row: ResultSet.() -> T): List<T> = buildList { while (next()) add(row()) }

private fun ResultSet.longOrNull(column: Int): Long? = getLong(column).takeUnless { wasNull() }

/** Forward-only schema steps of `peers.sqlite`, tracked by `PRAGMA user_version`. */
internal object PeerMigrations {
    const val SCHEMA_VERSION = 1

    private val steps: List<List<String>> = listOf(
        // 1: contacts, letters out and in, settings
        listOf(
            "CREATE TABLE contacts (id TEXT PRIMARY KEY, name TEXT NOT NULL, card TEXT NOT NULL, added_at INTEGER NOT NULL, removed_at INTEGER)",
            """CREATE TABLE outbox (
                contact TEXT NOT NULL, seq INTEGER NOT NULL, letter_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, text TEXT,
                created_at INTEGER NOT NULL, sent_at INTEGER, acked_at INTEGER, PRIMARY KEY (contact, seq)
            )""",
            """CREATE TABLE inbox (
                contact TEXT NOT NULL, seq INTEGER NOT NULL, letter_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, text TEXT,
                sent_at INTEGER NOT NULL, received_at INTEGER NOT NULL, PRIMARY KEY (contact, seq)
            )""",
            "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        ),
    )

    fun apply(connection: Connection) {
        val version = connection.userVersion()
        check(version <= SCHEMA_VERSION) { "Peers schema $version is newer than this server ($SCHEMA_VERSION). Update the server." }
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
