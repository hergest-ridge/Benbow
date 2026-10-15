package app.benbow.core.calendar

import app.benbow.core.sync.Hlc
import app.benbow.core.sync.LwwValue
import app.benbow.core.sync.OpResult
import app.benbow.core.sync.OpStatus
import kotlinx.serialization.Serializable

/** Calendar sync protocol this build speaks (ADR-027, docs/sync.md#calendar-store); separate from the journal's. */
object CalendarProtocol {
    const val VERSION = 1
}

/** What a calendar record is: a calendar (name, color, shown or hidden) or an event. */
object CalendarKind {
    const val CALENDAR = "calendar"
    const val EVENT = "event"
    val all = setOf(CALENDAR, EVENT)
}

/**
 * Sets [fields] of one calendar record, all with [hlc]; each field keeps its latest value (LWW by HLC).
 * A record exists from its first put, so an import is one op per event. Idempotent by [opId].
 */
@Serializable
data class CalendarOp(val opId: String, val kind: String, val id: String, val fields: Map<String, String?>, val hlc: Hlc)

@Serializable
data class CalendarRecord(val kind: String, val id: String, val fields: Map<String, LwwValue> = emptyMap())

/** `POST /api/v1/calendar/push`: ops applied in order, one transaction each. The answer is a `PushResponse`. */
@Serializable
data class CalendarPushRequest(val protocol: Int, val ops: List<CalendarOp>)

/** `GET /api/v1/calendar/pull`: records changed after the cursor, oldest change first. */
@Serializable
data class CalendarPullResponse(val changes: List<CalendarRecord>, val nextCursor: Long, val hasMore: Boolean)

/** The calendar store's part of `GET /api/v1/info`; absent when the server has no calendar store (older, or it failed to open). */
@Serializable
data class CalendarInfo(val protocol: Int, val epoch: Long, val cursor: Long)

data class CalendarChange(val seq: Long, val kind: String, val id: String)

/** Server-side storage the calendar rules run on; [transaction] is one SQL transaction on the server. */
interface CalendarStore {
    fun <T> transaction(block: () -> T): T
    fun appliedResult(opId: String): OpResult?
    fun recordApplied(result: OpResult)
    fun record(kind: String, id: String): CalendarRecord?
    fun putRecord(record: CalendarRecord)

    /** Appends to the change log and returns the new sequence number. */
    fun appendChange(kind: String, id: String): Long

    /** Records changed after [cursor], oldest first. */
    fun changesSince(cursor: Long, limit: Int): List<CalendarChange>
}

/**
 * The calendar's sync contract: idempotent ops (S4), per-field LWW by HLC, nothing else. There are no
 * revisions, merges or conflicts: the calendar is worth less than the journal and kept apart from it (ADR-027).
 */
class CalendarRules(private val store: CalendarStore) {
    fun push(ops: List<CalendarOp>): List<OpResult> = ops.map { op ->
        store.transaction {
            store.appliedResult(op.opId)?.copy(status = OpStatus.Duplicate) ?: apply(op).also(store::recordApplied)
        }
    }

    fun pull(cursor: Long, limit: Int): CalendarPullResponse {
        val changes = store.changesSince(cursor, limit + 1)
        val page = changes.take(limit)
        val records = page.map { it.kind to it.id }.distinct().map { (kind, id) ->
            store.record(kind, id) ?: error("change log points to missing $kind $id")
        }
        return CalendarPullResponse(records, page.lastOrNull()?.seq ?: cursor, changes.size > limit)
    }

    private fun apply(op: CalendarOp): OpResult {
        val problem = when {
            op.kind !in CalendarKind.all -> "unknown kind ${op.kind}"
            op.id.isBlank() -> "empty id"
            op.fields.isEmpty() -> "no fields"
            op.fields.keys.any { it.isBlank() } -> "empty field name"
            else -> null
        }
        if (problem != null) return OpResult(op.opId, OpStatus.Rejected, problem)
        val current = store.record(op.kind, op.id) ?: CalendarRecord(op.kind, op.id)
        val won = op.fields.filter { (field, _) -> current.fields[field]?.let { op.hlc > it.hlc } ?: true }
        if (won.isNotEmpty()) store.putRecord(current.copy(fields = current.fields + won.mapValues { LwwValue(it.value, op.hlc) }))
        // Logged even when every field lost: the device skipped this record while its op was pending and must re-pull it.
        store.appendChange(op.kind, op.id)
        return OpResult(op.opId, OpStatus.Applied)
    }
}
