package app.benbow.core.calendar

import app.benbow.core.LocalDays
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Benbow's calendars as files any calendar app reads (ADR-027, ADR-028): one `.ics` per calendar with what it shows
 * (events outside the trash, calendars not deleted), and `calendar.json` with every record as it is, deleted ones
 * included. Times are floating local times, as Benbow keeps them. Used by the server's mirror and the app's ZIP.
 */
object CalendarExport {
    data class File(val name: String, val text: String)

    /** A calendar or event record: its id and field values. */
    @Serializable
    data class Record(val id: String, val fields: Map<String, String?>)

    @Serializable
    private data class Dump(val calendars: List<Record>, val events: List<Record>)

    private val json = Json { prettyPrint = true }

    const val JSON_FILE = "calendar.json"

    /**
     * The `.ics` files and [JSON_FILE]. [defaultName] names a calendar without a name of its own (the built-in
     * «Мои события»); [now] is the export time (DTSTAMP).
     */
    fun files(calendars: List<Record>, events: List<Record>, defaultName: String, now: Long): List<File> {
        val taken = HashSet<String>()
        val ics = shown(calendars, events).map { (calendar, own) ->
            val name = calendar.fields[CalendarField.NAME]?.takeIf { it.isNotBlank() } ?: defaultName
            File(fileName(name, calendar.id, taken), ics(name, calendar.fields[CalendarField.COLOR], own, now))
        }
        return ics + File(JSON_FILE, json.encodeToString(Dump(calendars.sortedBy { it.id }, events.sortedBy { it.id })))
    }

    /** One calendar's `.ics`: its events outside the trash. */
    fun ics(name: String, color: String?, events: List<Record>, now: Long): String = Ics.write(
        IcsCalendar(
            name,
            color,
            events.filter {
                it.fields[EventField.DELETED] != "true"
            }.sortedWith(compareBy({ it.fields[EventField.START] }, { it.id })).mapNotNull(::event),
        ),
        utcStamp(now),
    )

    /** "Работа.ics": safe on Windows, Android and Linux; a second calendar with the same name gets its id's end. */
    fun fileName(name: String, id: String, taken: MutableSet<String> = HashSet()): String {
        val safe = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimEnd('.').take(MAX_NAME).ifBlank { "calendar" }
        val file = if (taken.add(safe.lowercase())) safe else "$safe-${id.takeLast(6)}".also { taken.add(it.lowercase()) }
        return "$file.ics"
    }

    /** Calendars as shown: the built-in one first (always), then the others not deleted, each with its events. */
    private fun shown(calendars: List<Record>, events: List<Record>): List<Pair<Record, List<Record>>> {
        val byCalendar = events.groupBy { it.fields[EventField.CALENDAR] ?: MY_CALENDAR }
        val records = calendars.associateBy { it.id }
        val ids = (listOf(MY_CALENDAR) + calendars.map { it.id } + byCalendar.keys).distinct()
            .filter { records[it]?.fields?.get(EventField.DELETED) != "true" }
        return ids.map { id -> (records[id] ?: Record(id, emptyMap())) to byCalendar[id].orEmpty() }
    }

    /** A Benbow event as a VEVENT; null when its start can't be read. */
    internal fun event(record: Record): IcsEvent? {
        val v = record.fields
        val start = v[EventField.START]?.let(::time) ?: return null
        val end =
            v[EventField.END]?.let { endOf(start, it) }?.takeIf { it.value > start.value }
                ?: if (start.isDate) IcsTime(plusDays(start.value, 1)) else null
        // Skipped days of a series: dates for an all-day one, the start's time on those days for a timed one.
        val skipped = v[EventField.EXDATE]?.split(',').orEmpty().mapNotNull { time(it.trim()) }.map { day ->
            if (start.isDate) day else IcsTime(day.value + start.value.drop(DATE))
        }
        return IcsEvent(
            uid = v[EventField.UID] ?: "${record.id}@benbow",
            summary = v[EventField.TITLE].orEmpty(),
            description = v[EventField.DESCRIPTION],
            location = v[EventField.LOCATION],
            start = start,
            end = end,
            rrule = v[EventField.RRULE],
            exdates = skipped,
        )
    }

    /** "2026-10-14" → a DATE; "2026-10-14T09:30" → a floating DATE-TIME. */
    private fun time(text: String): IcsTime? {
        val date = text.take(DAY)
        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) return null
        val clock = text.drop(DAY).removePrefix("T")
        val digits = date.replace("-", "")
        if (clock.isEmpty()) return IcsTime(digits)
        if (!Regex("""\d{2}:\d{2}""").matches(clock)) return null
        return IcsTime(digits + "T" + clock.replace(":", "") + "00")
    }

    /** DTEND: exclusive for all-day events (the day after the last), the same kind of value as DTSTART. */
    private fun endOf(start: IcsTime, end: String): IcsTime? {
        val time = time(end) ?: return null
        return when {
            start.isDate -> IcsTime(plusDays(time.value.take(DATE), 1))
            time.isDate -> IcsTime(plusDays(time.value, 1) + "T000000")
            else -> time
        }
    }

    /** "20261231" plus [days], by the proleptic Gregorian calendar. */
    internal fun plusDays(date: String, days: Int): String {
        val start = LocalDays.epochDay(date.take(4).toInt(), date.substring(4, 6).toInt(), date.substring(6, 8).toInt())
        val (y, m, d) = LocalDays.civil(start + days)
        return y.toString().padStart(4, '0') + m.toString().padStart(2, '0') + d.toString().padStart(2, '0')
    }

    /** DTSTAMP: "YYYYMMDDTHHMMSSZ" of [millis]. */
    internal fun utcStamp(millis: Long): String {
        val seconds = millis.floorDiv(1000)
        val (y, m, d) = LocalDays.civil(seconds.floorDiv(86_400))
        val s = seconds.mod(86_400)
        return y.toString().padStart(4, '0') + m.toString().padStart(2, '0') + d.toString().padStart(2, '0') + "T" +
            (s / 3600).toString().padStart(2, '0') + (s / 60 % 60).toString().padStart(2, '0') + (s % 60).toString().padStart(2, '0') + "Z"
    }

    private const val DAY = 10 // "YYYY-MM-DD"
    private const val DATE = 8 // "YYYYMMDD"
    private const val MAX_NAME = 80
}
