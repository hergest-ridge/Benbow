package app.benbow.server.calendar

import app.benbow.core.calendar.CalendarChange
import app.benbow.core.calendar.CalendarRecord
import app.benbow.core.calendar.CalendarStore
import app.benbow.core.sync.LwwValue
import app.benbow.core.sync.OpResult
import app.benbow.core.sync.OpStatus
import kotlinx.serialization.json.Json
import java.sql.Connection

/** [CalendarStore] on the calendar file; one instance per request so applied ops record the device. */
class SqliteCalendarStore(private val db: CalendarDatabase, private val deviceId: String? = null) : CalendarStore {
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

    override fun record(kind: String, id: String): CalendarRecord? = query { c ->
        c.prepareStatement("SELECT fields FROM records WHERE kind = ? AND id = ?").use { st ->
            st.setString(1, kind)
            st.setString(2, id)
            st.executeQuery().use { rs ->
                if (rs.next()) CalendarRecord(kind, id, Json.decodeFromString<Map<String, LwwValue>>(rs.getString(1))) else null
            }
        }
    }

    override fun putRecord(record: CalendarRecord) = query { c ->
        c.prepareStatement(
            """INSERT INTO records(kind, id, fields, updated_at) VALUES (?, ?, ?, ?)
               ON CONFLICT(kind, id) DO UPDATE SET fields = excluded.fields, updated_at = excluded.updated_at""",
        ).use { st ->
            st.setString(1, record.kind)
            st.setString(2, record.id)
            st.setString(3, Json.encodeToString(record.fields))
            st.setLong(4, System.currentTimeMillis())
            st.executeUpdate()
        }
        Unit
    }

    override fun appendChange(kind: String, id: String): Long = query { c ->
        c.prepareStatement("INSERT INTO change_log(kind, record_id, at) VALUES (?, ?, ?)").use { st ->
            st.setString(1, kind)
            st.setString(2, id)
            st.setLong(3, System.currentTimeMillis())
            st.executeUpdate()
        }
        c.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    override fun changesSince(cursor: Long, limit: Int): List<CalendarChange> = query { c ->
        c.prepareStatement("SELECT seq, kind, record_id FROM change_log WHERE seq > ? ORDER BY seq LIMIT ?").use { st ->
            st.setLong(1, cursor)
            st.setInt(2, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(CalendarChange(rs.getLong(1), rs.getString(2), rs.getString(3))) }
            }
        }
    }
}
