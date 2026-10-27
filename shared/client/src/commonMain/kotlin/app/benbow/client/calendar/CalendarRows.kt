package app.benbow.client.calendar

import app.benbow.client.ClientJson
import app.benbow.client.SWATCHES
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.calendar.db.Event
import app.benbow.client.clocksSerializer
import app.benbow.core.calendar.CalendarField
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.CalendarOp
import app.benbow.core.calendar.EventField
import app.benbow.core.calendar.MY_CALENDAR
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.HlcClock
import app.benbow.core.tracker.CellField
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerField
import app.benbow.core.tracker.TrackerKind
import app.benbow.core.uuidV7
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

internal val valuesSerializer = MapSerializer(String.serializer(), String.serializer().nullable)

internal fun decodeValues(json: String): Map<String, String?> = ClientJson.decodeFromString(valuesSerializer, json)

internal fun Event.toEvent(): CalendarEvent? {
    val start = start ?: return null
    val values = decodeValues(fields)
    return CalendarEvent(
        id = id,
        calendar = calendar,
        title = title,
        start = start,
        end = values[EventField.END],
        description = values[EventField.DESCRIPTION],
        location = values[EventField.LOCATION],
        rrule = values[EventField.RRULE],
        exdates = values[EventField.EXDATE]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
    )
}

/** A calendar record as its panel shows it; one without a color of its own gets a steady one from its id. */
internal fun calendarItem(id: String, values: Map<String, String?>, events: Int): CalendarItem = CalendarItem(
    id = id,
    name = values[CalendarField.NAME]?.takeIf { it.isNotBlank() },
    color = values[CalendarField.COLOR]?.takeIf { it in SWATCHES } ?: defaultCalendarColor(id),
    visible = values[CalendarField.VISIBLE] != "false",
    events = events,
)

/** A record's field values and their clocks, empty when it isn't here. */
internal fun CalendarDb.recordState(kind: String, id: String): Pair<Map<String, String?>, Map<String, Hlc>> {
    val (fields, clocks) = when (kind) {
        CalendarKind.EVENT -> calendarQueries.selectEvent(id).executeAsOneOrNull()?.let { it.fields to it.field_clocks }
        CalendarKind.CALENDAR -> calendarQueries.selectCalendar(id).executeAsOneOrNull()?.let { it.fields to it.field_clocks }
        in TrackerKind.all -> calendarQueries.selectTrackerRecord(kind, id).executeAsOneOrNull()?.let { it.fields to it.field_clocks }
        else -> null
    } ?: return emptyMap<String, String?>() to emptyMap()
    return decodeValues(fields) to ClientJson.decodeFromString(clocksSerializer, clocks)
}

/**
 * Writes a record with the columns the calendar's queries read; a kind this build doesn't know isn't kept. A
 * repeating event's last day is its rule's UNTIL (plus its length) or [FOREVER], so range queries find it.
 */
internal fun CalendarDb.storeRecord(kind: String, id: String, values: Map<String, String?>, clocks: Map<String, Hlc>, now: Long) {
    val fields = ClientJson.encodeToString(valuesSerializer, values)
    val fieldClocks = ClientJson.encodeToString(clocksSerializer, clocks)
    when (kind) {
        CalendarKind.EVENT -> {
            val start = values[EventField.START]
            val startDay = start?.take(DATE_LENGTH)
            val endDay = values[EventField.END]?.take(DATE_LENGTH)?.takeIf { startDay != null && it > startDay } ?: startDay
            val rule = values[EventField.RRULE]?.let(Recurrence::parse)
            val lastDay = if (rule == null || startDay == null) {
                endDay
            } else {
                val span = parseDay(startDay)?.let { first -> parseDay(endDay ?: startDay)?.let { first.daysUntil(it) } } ?: 0
                rule.until?.plus(span, DateTimeUnit.DAY)?.toString() ?: FOREVER
            }
            calendarQueries.upsertEvent(
                id,
                fields,
                fieldClocks,
                values[EventField.CALENDAR] ?: MY_CALENDAR,
                values[EventField.TITLE].orEmpty(),
                start,
                startDay,
                lastDay,
                if (values[EventField.DELETED] == "true") 1L else 0L,
                now,
            )
        }

        CalendarKind.CALENDAR -> calendarQueries.upsertCalendar(id, fields, fieldClocks, now)

        in TrackerKind.all -> {
            val (owner, day, endDay) = trackerColumns(kind, values)
            val deleted = if (values[TrackerField.DELETED] == "true") 1L else 0L
            calendarQueries.upsertTrackerRecord(kind, id, fields, fieldClocks, owner, day, endDay, deleted, now)
        }
    }
}

/** A tracker record's derived columns: whose it is (a column's tracker, a cell's or span's column) and its day(s). */
private fun trackerColumns(kind: String, values: Map<String, String?>): Triple<String?, String?, String?> = when (kind) {
    TrackerKind.COLUMN -> Triple(values[ColumnField.TRACKER], null, null)

    TrackerKind.CELL -> Triple(values[CellField.COLUMN], values[CellField.DAY], values[CellField.DAY])

    TrackerKind.SPAN -> {
        val start = values[SpanField.START]?.take(DATE_LENGTH)
        // A span still running (no end yet) shows on the day it started.
        Triple(values[SpanField.COLUMN], start, values[SpanField.END]?.take(DATE_LENGTH)?.takeIf { start != null && it > start } ?: start)
    }

    else -> Triple(null, null, null)
}

/**
 * Sets [fields] of a record inside the caller's transaction: stored at once with a fresh clock and queued for the
 * server, so nothing is lost while it is out of reach (S5). Calendars, events and trackers' records alike.
 */
internal fun CalendarDb.putRecord(kind: String, id: String, fields: Map<String, String?>, now: () -> Long) {
    val hlc = CalendarClock(this, now).next()
    val (values, clocks) = recordState(kind, id)
    storeRecord(kind, id, values + fields, clocks + fields.mapValues { hlc }, now())
    val op = CalendarOp(uuidV7(now()), kind, id, fields, hlc)
    calendarQueries.enqueue(op.opId, kind, id, ClientJson.encodeToString(CalendarOp.serializer(), op), now())
}

/**
 * HLC of this device's calendar writes, kept in the calendar file: every call starts from the stored value, so
 * writes and syncs never move it back and it survives restarts.
 */
internal class CalendarClock(private val db: CalendarDb, private val now: () -> Long) {
    private val q get() = db.calendarQueries

    fun next(): Hlc = with(stored()) { next().also(::persist) }

    /** This device's id in the calendar file: what its own count parts are written under (ADR-050). */
    fun node(): String = q.getValue(NODE).executeAsOneOrNull() ?: uuidV7(now()).also { q.putValue(NODE, it) }

    fun observe(remote: Hlc) = with(stored()) {
        observe(remote)
        persist(current)
    }

    private fun stored() = HlcClock(
        node(),
        q.getValue(HLC).executeAsOneOrNull()?.let { ClientJson.decodeFromString(Hlc.serializer(), it) },
        now,
    )

    private fun persist(hlc: Hlc) = q.putValue(HLC, ClientJson.encodeToString(Hlc.serializer(), hlc))

    private companion object {
        const val NODE = "clock.node"
        const val HLC = "clock.hlc"
    }
}
