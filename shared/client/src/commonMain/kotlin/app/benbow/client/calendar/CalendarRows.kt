package app.benbow.client.calendar

import app.benbow.client.ClientJson
import app.benbow.client.SWATCHES
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.calendar.db.Event
import app.benbow.client.clocksSerializer
import app.benbow.core.calendar.CalendarField
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.EventField
import app.benbow.core.calendar.MY_CALENDAR
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.HlcClock
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
