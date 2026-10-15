package app.benbow.client.calendar

import app.benbow.client.ClientJson
import app.benbow.client.PairingException
import app.benbow.client.RejectedOp
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.core.calendar.CalendarExport
import app.benbow.core.calendar.CalendarField
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.CalendarOp
import app.benbow.core.calendar.EventField
import app.benbow.core.calendar.MY_CALENDAR
import app.benbow.core.calendar.MY_CALENDAR_NAME
import app.benbow.core.uuidV7
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext

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

    /**
     * Events (repeating ones as their occurrences) on any day of [fromDay]..[toDay], outside the trash and
     * outside hidden or deleted calendars; by start.
     */
    fun events(fromDay: String, toDay: String): Flow<List<CalendarEvent>> {
        val from = LocalDate.parse(fromDay)
        val to = LocalDate.parse(toDay)
        return combine(
            q.eventsBetween(fromDay = fromDay, toDay = toDay).asFlow().mapToList(context),
            q.allCalendars().asFlow().mapToList(context),
        ) { rows, calendars ->
            val hidden = calendars.filter { row ->
                decodeValues(row.fields).let { it[CalendarField.VISIBLE] == "false" || it[CalendarField.DELETED] == "true" }
            }.map { it.id }.toSet()
            rows.filter { it.calendar !in hidden }
                .mapNotNull { it.toEvent() }
                .flatMap { it.occurrences(from, to) }
                .sortedWith(compareBy({ it.start }, { it.id }))
        }
    }

    /** The event as stored (a series as its first occurrence); also finds one in the trash. */
    fun event(id: String): CalendarEvent? = q.selectEvent(id).executeAsOneOrNull()?.toEvent()

    /** The built-in «Мои события» first, then the others by name; deleted ones are in [trashedCalendars]. */
    fun calendars(): Flow<List<CalendarItem>> = combine(
        q.allCalendars().asFlow().mapToList(context),
        q.eventCounts().asFlow().mapToList(context),
    ) { rows, counts ->
        val events = counts.associate { it.calendar to it.events.toInt() }
        val records = rows.associate { it.id to decodeValues(it.fields) }
        val others = records.filter { (id, values) -> id != MY_CALENDAR && values[CalendarField.DELETED] != "true" }
            .map { (id, values) -> calendarItem(id, values, events[id] ?: 0) }
            .sortedBy { it.name.orEmpty().lowercase() }
        listOf(calendarItem(MY_CALENDAR, records[MY_CALENDAR].orEmpty(), events[MY_CALENDAR] ?: 0)) + others
    }

    /** An event on [day], at [time] ("HH:MM") or all day. */
    fun createEvent(day: String, title: String, time: String?, calendar: String = MY_CALENDAR): String = createEvent(
        mapOf(EventField.CALENDAR to calendar, EventField.TITLE to title.trim(), EventField.START to (time?.let { "${day}T$it" } ?: day)),
    )

    fun createEvent(fields: Map<String, String?>): String {
        val id = uuidV7(now())
        put(CalendarKind.EVENT, id, fields)
        return id
    }

    /** New title and start; the other fields stay. */
    fun updateEvent(id: String, title: String, start: String) =
        put(CalendarKind.EVENT, id, mapOf(EventField.TITLE to title.trim(), EventField.START to start))

    fun moveToTrash(id: String) = put(CalendarKind.EVENT, id, mapOf(EventField.DELETED to "true"))

    fun restore(id: String) = put(CalendarKind.EVENT, id, mapOf(EventField.DELETED to null))

    /** One day of a series goes, the rest stays (EXDATE). */
    fun skipDay(id: String, day: String) {
        val event = event(id) ?: return
        put(CalendarKind.EVENT, id, mapOf(EventField.EXDATE to (event.exdates + day).sorted().joinToString(",")))
    }

    /** Events in the trash, most recently changed first. */
    fun trashedEvents(): Flow<List<CalendarEvent>> = q.trashedEvents().asFlow().mapToList(context).map { rows ->
        rows.mapNotNull { it.toEvent() }
    }

    fun createCalendar(name: String, color: String): String {
        val id = uuidV7(now())
        put(CalendarKind.CALENDAR, id, mapOf(CalendarField.NAME to name.trim(), CalendarField.COLOR to color))
        return id
    }

    /** Renames and recolors; null leaves that one as it is. The built-in calendar gets its record here. */
    fun updateCalendar(id: String, name: String? = null, color: String? = null) = put(
        CalendarKind.CALENDAR,
        id,
        listOfNotNull(name?.let { CalendarField.NAME to it.trim() }, color?.let { CalendarField.COLOR to it }).toMap(),
    )

    fun setVisible(id: String, visible: Boolean) =
        put(CalendarKind.CALENDAR, id, mapOf(CalendarField.VISIBLE to if (visible) null else "false"))

    /** The whole calendar goes to the trash with its events; «Мои события» stays. */
    fun deleteCalendar(id: String) {
        if (id != MY_CALENDAR) put(CalendarKind.CALENDAR, id, mapOf(CalendarField.DELETED to "true"))
    }

    fun restoreCalendar(id: String) = put(CalendarKind.CALENDAR, id, mapOf(CalendarField.DELETED to null))

    fun trashedCalendars(): Flow<List<CalendarItem>> = q.allCalendars().asFlow().mapToList(context).map { rows ->
        rows.map {
            it.id to decodeValues(it.fields)
        }.filter { it.second[CalendarField.DELETED] == "true" }.map { calendarItem(it.first, it.second, 0) }
    }

    /** Every calendar as an `.ics` file plus `calendar.json` (ADR-028), for the archive export. */
    fun exportFiles(now: Long): List<CalendarExport.File> = CalendarExport.files(
        q.allCalendars().executeAsList().map { CalendarExport.Record(it.id, decodeValues(it.fields)) },
        q.allEvents().executeAsList().map { CalendarExport.Record(it.id, decodeValues(it.fields)) },
        MY_CALENDAR_NAME,
        now,
    )

    /** One calendar as an `.ics` file named [name]: what it shows, in floating local times. */
    fun exportIcs(calendar: CalendarItem, name: String, now: Long): String = CalendarExport.ics(
        name,
        calendar.color,
        q.allEvents().executeAsList().filter { it.calendar == calendar.id }.map { CalendarExport.Record(it.id, decodeValues(it.fields)) },
        now,
    )

    /** Sets [fields] of a record: shown at once, sent with the next sync. Nothing to set, nothing sent. */
    fun put(kind: String, id: String, fields: Map<String, String?>) {
        if (fields.isEmpty()) return
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
