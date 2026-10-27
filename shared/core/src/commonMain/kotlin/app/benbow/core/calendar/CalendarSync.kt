package app.benbow.core.calendar

import app.benbow.core.sync.Hlc
import app.benbow.core.sync.LwwValue
import app.benbow.core.sync.OpResult
import app.benbow.core.sync.OpStatus
import app.benbow.core.tracker.TrackerKind
import kotlinx.serialization.Serializable

/** Calendar sync protocol this build speaks (ADR-027, docs/sync.md#calendar-store); separate from the journal's. */
object CalendarProtocol {
    const val VERSION = 1

    /** The kinds of records the store keeps: calendars and events, and since ADR-050 the trackers' records. */
    val KINDS: Set<String> = CalendarKind.all + TrackerKind.all
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

/**
 * The calendar store's part of `GET /api/v1/info`; absent when the server has no calendar store (older, or it failed to
 * open). [kinds] are the kinds of records it takes (additive: none from a server before trackers, which takes calendars
 * and events only), so an app sends trackers' ops only to a server that keeps them (ADR-050).
 */
@Serializable
data class CalendarInfo(val protocol: Int, val epoch: Long, val cursor: Long, val kinds: List<String> = emptyList())

/** A value a field held until a newer one replaced it, or one that lost to a newer one already there (ADR-050). */
data class FieldHistory(val kind: String, val id: String, val field: String, val value: String?, val hlc: Hlc)

data class CalendarChange(val seq: Long, val kind: String, val id: String)

/** Server-side storage the calendar rules run on; [transaction] is one SQL transaction on the server. */
interface CalendarStore {
    fun <T> transaction(block: () -> T): T
    fun appliedResult(opId: String): OpResult?
    fun recordApplied(result: OpResult)
    fun record(kind: String, id: String): CalendarRecord?
    fun putRecord(record: CalendarRecord)

    /** Keeps a replaced or losing value for good: the store's `field_history`, append-only, never pulled. */
    fun appendHistory(entry: FieldHistory)

    /** The values [kind] [id] held before, oldest first. */
    fun history(kind: String, id: String): List<FieldHistory>

    /** Appends to the change log and returns the new sequence number. */
    fun appendChange(kind: String, id: String): Long

    /** Records changed after [cursor], oldest first. */
    fun changesSince(cursor: Long, limit: Int): List<CalendarChange>
}

/**
 * The calendar's sync contract: idempotent ops (S4), per-field LWW by HLC. There are no revisions, merges or conflicts:
 * the calendar is worth less than the journal and kept apart from it (ADR-027). What a newer value replaces, and a
 * value that comes in older than the one there, goes to the store's history, so no value written is ever discarded,
 * though only the newest shows (ADR-050: trackers' marks can't be imported again as a calendar can).
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
            op.kind !in CalendarProtocol.KINDS -> "unknown kind ${op.kind}"
            op.id.isBlank() -> "empty id"
            op.fields.isEmpty() -> "no fields"
            op.fields.keys.any { it.isBlank() } -> "empty field name"
            else -> null
        }
        if (problem != null) return OpResult(op.opId, OpStatus.Rejected, problem)
        val current = store.record(op.kind, op.id) ?: CalendarRecord(op.kind, op.id)
        val won = op.fields.filter { (field, _) -> current.fields[field]?.let { op.hlc > it.hlc } ?: true }
        for ((field, value) in op.fields) {
            val there = current.fields[field] ?: continue
            when {
                // The same value, or the same write again: nothing would be lost.
                there.value == value || there.hlc == op.hlc -> Unit

                field in won -> store.appendHistory(FieldHistory(op.kind, op.id, field, there.value, there.hlc))

                else -> store.appendHistory(FieldHistory(op.kind, op.id, field, value, op.hlc))
            }
        }
        if (won.isNotEmpty()) store.putRecord(current.copy(fields = current.fields + won.mapValues { LwwValue(it.value, op.hlc) }))
        // Logged even when every field lost: the device skipped this record while its op was pending and must re-pull it.
        store.appendChange(op.kind, op.id)
        return OpResult(op.opId, OpStatus.Applied)
    }
}
