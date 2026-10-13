package app.benbow.server.storage

import app.benbow.core.api.RevisionInfo
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.EntryState
import app.benbow.core.sync.LwwValue
import app.benbow.core.sync.OpResult
import app.benbow.core.sync.OpStatus
import app.benbow.core.sync.RevisionRecord
import app.benbow.core.sync.SyncStore
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** [SyncStore] on the server database; one instance per request so applied ops record the device. */
class SqliteSyncStore(private val db: Database, private val deviceId: String? = null) : SyncStore {
    private val json = Json

    override fun <T> transaction(block: () -> T): T = db.transaction { block() }

    private fun <T> query(block: (Connection) -> T): T = db.locked(block)

    override fun appliedResult(opId: String): OpResult? = query { c ->
        c.prepareStatement("SELECT status, message FROM applied_ops WHERE op_id = ?").use { st ->
            st.setString(1, opId)
            st.executeQuery().use { rs -> if (rs.next()) OpResult(opId, OpStatus.valueOf(rs.getString(1)), rs.getString(2)) else null }
        }
    }

    override fun recordApplied(result: OpResult) = query { c ->
        c.prepareStatement("INSERT INTO applied_ops(op_id, status, message, device_id, applied_at) VALUES (?, ?, ?, ?, ?)").use { st ->
            st.setString(1, result.opId)
            st.setString(2, result.status.name)
            st.setString(3, result.message)
            st.setString(4, deviceId)
            st.setLong(5, System.currentTimeMillis())
            st.executeUpdate()
        }
        Unit
    }

    override fun entry(id: String): EntryState? = query { c ->
        c.prepareStatement(
            "SELECT id, kind, day, created_at, head_rev, conflict_heads, fields, attachments FROM entries WHERE id = ?",
        ).use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toEntry() else null }
        }
    }

    override fun putEntry(entry: EntryState) = query { c ->
        c.prepareStatement(
            """INSERT INTO entries(id, kind, day, created_at, head_rev, conflict_heads, fields, attachments) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET head_rev = excluded.head_rev, conflict_heads = excluded.conflict_heads,
               fields = excluded.fields, attachments = excluded.attachments""",
        ).use { st ->
            st.setString(1, entry.id)
            st.setString(2, entry.kind)
            st.setString(3, entry.day)
            st.setLong(4, entry.createdAt)
            st.setString(5, entry.head)
            st.setString(6, json.encodeToString(entry.conflictHeads))
            st.setString(7, json.encodeToString(entry.fields))
            st.setString(8, json.encodeToString(entry.attachments))
            st.executeUpdate()
        }
        Unit
    }

    override fun revision(id: String): RevisionRecord? = query { c ->
        c.prepareStatement("SELECT id, entry_id, parents, body, origin FROM revisions WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    RevisionRecord(
                        rs.getString(1),
                        rs.getString(2),
                        json.decodeFromString(rs.getString(3)),
                        rs.getString(4),
                        rs.getString(5),
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun putRevision(revision: RevisionRecord) = query { c ->
        c.prepareStatement(
            "INSERT INTO revisions(id, entry_id, parents, body, body_sha256, origin, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setString(1, revision.id)
            st.setString(2, revision.entryId)
            st.setString(3, json.encodeToString(revision.parents))
            st.setString(4, revision.body)
            st.setString(5, sha256(revision.body))
            st.setString(6, revision.origin)
            st.setLong(7, System.currentTimeMillis())
            st.executeUpdate()
        }
        Unit
    }

    override fun appendChange(entryId: String): Long = query { c ->
        c.prepareStatement("INSERT INTO change_log(entry_id, at) VALUES (?, ?)").use { st ->
            st.setString(1, entryId)
            st.setLong(2, System.currentTimeMillis())
            st.executeUpdate()
        }
        c.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    override fun changesSince(cursor: Long, limit: Int): List<Pair<Long, String>> = query { c ->
        c.prepareStatement("SELECT seq, entry_id FROM change_log WHERE seq > ? ORDER BY seq LIMIT ?").use { st ->
            st.setLong(1, cursor)
            st.setInt(2, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1) to rs.getString(2)) } }
        }
    }

    override fun newRevisionId(): String = "srv-${UUID.randomUUID()}"

    /** Revision history of an entry, newest first, without bodies. */
    fun revisionsOf(entryId: String): List<RevisionInfo> = query { c ->
        c.prepareStatement(
            "SELECT id, parents, origin, created_at, body_sha256 FROM revisions WHERE entry_id = ? ORDER BY created_at DESC, rowid DESC",
        )
            .use { st ->
                st.setString(1, entryId)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                RevisionInfo(
                                    rs.getString(1),
                                    json.decodeFromString(rs.getString(2)),
                                    rs.getString(3),
                                    rs.getLong(4),
                                    rs.getString(5),
                                ),
                            )
                        }
                    }
                }
            }
    }

    private fun ResultSet.toEntry() = EntryState(
        id = getString(1),
        kind = getString(2),
        day = getString(3),
        createdAt = getLong(4),
        head = getString(5),
        conflictHeads = json.decodeFromString(getString(6)),
        fields = json.decodeFromString<Map<String, LwwValue>>(getString(7)),
        attachments = json.decodeFromString<Map<String, AttachmentState>>(getString(8)),
    )
}

internal fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
    "%02x".format(it)
}
