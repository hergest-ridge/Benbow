package app.benbow.client.calendar

import app.benbow.client.ClientJson
import app.benbow.client.PairingException
import app.benbow.client.RejectedOp
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.calendar.db.Event
import app.benbow.client.clocksSerializer
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.CalendarOp
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.HlcClock
import app.benbow.core.uuidV7
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.CoroutineContext

/** The built-in calendar of events made in Benbow («Мои события»); it has no record until it is renamed or recolored. */
const val MY_CALENDAR = "mine"

/** Fields of an event record (docs/data-model.md#calendar). */
object EventField {
    const val CALENDAR = "calendar"
    const val TITLE = "title"
    const val START = "start"
    const val END = "end"
    const val DESCRIPTION = "description"
    const val DELETED = "deleted"
}

/** A calendar event; [start] and [end] are local "YYYY-MM-DD" (all day) or "YYYY-MM-DDTHH:MM". */
data class CalendarEvent(
    val id: String,
    val calendar: String,
    val title: String,
    val start: String,
    val end: String? = null,
    val description: String? = null,
) {
    val day: String get() = start.take(DATE_LENGTH)

    /** The last day it shows on (the start day unless it lasts longer). */
    val lastDay: String get() = end?.take(DATE_LENGTH)?.takeIf { it > day } ?: day

    /** "HH:MM", null for all day. */
    val time: String? get() = start.substringAfter('T', "").takeIf { it.isNotEmpty() }

    fun covers(date: String): Boolean = date in day..lastDay
}

/**
 * The calendar on this device (ADR-027): its own file, apart from the journal. Every write is one local
 * transaction that also queues the op, so nothing is lost while the server is out of reach (S5).
 */
class CalendarRepository(
    private val db: CalendarDb,
    private val now: () -> Long,
    private val context: CoroutineContext = Dispatchers.Default,
) {
    private val q get() = db.calendarQueries

    /** Events on any day of [fromDay]..[toDay], outside the trash. */
    fun events(fromDay: String, toDay: String): Flow<List<CalendarEvent>> =
        q.eventsBetween(fromDay = fromDay, toDay = toDay).asFlow().mapToList(context).map { rows -> rows.mapNotNull { it.toEvent() } }

    /** Also finds events in the trash. */
    fun event(id: String): CalendarEvent? = q.selectEvent(id).executeAsOneOrNull()?.toEvent()

    /** An event on [day], at [time] ("HH:MM") or all day. */
    fun createEvent(day: String, title: String, time: String?, calendar: String = MY_CALENDAR): String {
        val id = uuidV7(now())
        put(
            CalendarKind.EVENT,
            id,
            mapOf(
                EventField.CALENDAR to calendar,
                EventField.TITLE to title.trim(),
                EventField.START to (
                    time?.let {
                        "${day}T$it"
                    } ?: day
                    ),
            ),
        )
        return id
    }

    /** New title and start; the other fields stay. */
    fun updateEvent(id: String, title: String, start: String) =
        put(CalendarKind.EVENT, id, mapOf(EventField.TITLE to title.trim(), EventField.START to start))

    fun moveToTrash(id: String) = put(CalendarKind.EVENT, id, mapOf(EventField.DELETED to "true"))

    fun restore(id: String) = put(CalendarKind.EVENT, id, mapOf(EventField.DELETED to null))

    /** Sets [fields] of a record: shown at once, sent with the next sync. */
    fun put(kind: String, id: String, fields: Map<String, String?>) {
        db.transaction {
            val hlc = CalendarClock(db, now).next()
            val (values, clocks) = db.recordState(kind, id)
            db.storeRecord(kind, id, values + fields, clocks + fields.mapValues { hlc }, now())
            val op = CalendarOp(uuidV7(now()), kind, id, fields, hlc)
            q.enqueue(op.opId, kind, id, ClientJson.encodeToString(CalendarOp.serializer(), op), now())
        }
    }

    fun pendingCount(): Flow<Long> = q.pendingCount().asFlow().mapToOne(context)

    fun rejectedCount(): Flow<Long> = q.rejectedCount().asFlow().mapToOne(context)

    fun rejectedOps(): Flow<List<RejectedOp>> =
        q.rejectedOps().asFlow().mapToList(context).map { rows -> rows.map { RejectedOp(it.op_id, it.record_id, it.last_error) } }

    fun retryRejected() {
        q.retryRejected()
    }

    /** Refuses (like the journal) while an op hasn't reached the server; checked before unpairing. */
    fun checkSynced() {
        if (q.pendingCount().executeAsOne() + q.rejectedCount().executeAsOne() > 0) {
            throw PairingException("Some calendar changes haven't reached the server yet")
        }
    }

    /** After unpairing: the cache belonged to that server's store. */
    fun forget() {
        db.transaction {
            checkSynced()
            q.clearRecords()
            listOf(CalendarSync.STORE, CalendarSync.EPOCH, CalendarSync.CURSOR).forEach(q::deleteValue)
        }
    }
}

private const val DATE_LENGTH = 10

internal val valuesSerializer = MapSerializer(String.serializer(), String.serializer().nullable)

private fun Event.toEvent(): CalendarEvent? {
    val start = start ?: return null
    val values = ClientJson.decodeFromString(valuesSerializer, fields)
    return CalendarEvent(id, calendar, title, start, values[EventField.END], values[EventField.DESCRIPTION])
}

/** A record's field values and their clocks, empty when it isn't here. */
internal fun CalendarDb.recordState(kind: String, id: String): Pair<Map<String, String?>, Map<String, Hlc>> {
    val (fields, clocks) = when (kind) {
        CalendarKind.EVENT -> calendarQueries.selectEvent(id).executeAsOneOrNull()?.let { it.fields to it.field_clocks }
        CalendarKind.CALENDAR -> calendarQueries.selectCalendar(id).executeAsOneOrNull()?.let { it.fields to it.field_clocks }
        else -> null
    } ?: return emptyMap<String, String?>() to emptyMap()
    return ClientJson.decodeFromString(valuesSerializer, fields) to ClientJson.decodeFromString(clocksSerializer, clocks)
}

/** Writes a record with the columns the calendar's queries read; a kind this build doesn't know isn't kept. */
internal fun CalendarDb.storeRecord(kind: String, id: String, values: Map<String, String?>, clocks: Map<String, Hlc>, now: Long) {
    val fields = ClientJson.encodeToString(valuesSerializer, values)
    val fieldClocks = ClientJson.encodeToString(clocksSerializer, clocks)
    when (kind) {
        CalendarKind.EVENT -> {
            val start = values[EventField.START]
            val startDay = start?.take(DATE_LENGTH)
            val endDay = values[EventField.END]?.take(DATE_LENGTH)?.takeIf { startDay != null && it > startDay } ?: startDay
            calendarQueries.upsertEvent(
                id,
                fields,
                fieldClocks,
                values[EventField.CALENDAR] ?: MY_CALENDAR,
                values[EventField.TITLE].orEmpty(),
                start,
                startDay,
                endDay,
                if (values[EventField.DELETED] == "true") 1L else 0L,
                now,
            )
        }

        CalendarKind.CALENDAR -> calendarQueries.upsertCalendar(id, fields, fieldClocks, now)
    }
}

/**
 * HLC of this device's calendar writes, kept in the calendar file: every call starts from the stored value, so
 * writes and syncs never move it back and it survives restarts.
 */
internal class CalendarClock(private val db: CalendarDb, private val now: () -> Long) {
    private val q get() = db.calendarQueries

    fun next(): Hlc = with(stored()) { next().also(::persist) }

    fun observe(remote: Hlc) = with(stored()) {
        observe(remote)
        persist(current)
    }

    private fun stored() = HlcClock(
        q.getValue(NODE).executeAsOneOrNull() ?: uuidV7(now()).also { q.putValue(NODE, it) },
        q.getValue(HLC).executeAsOneOrNull()?.let { ClientJson.decodeFromString(Hlc.serializer(), it) },
        now,
    )

    private fun persist(hlc: Hlc) = q.putValue(HLC, ClientJson.encodeToString(Hlc.serializer(), hlc))

    private companion object {
        const val NODE = "clock.node"
        const val HLC = "clock.hlc"
    }
}
