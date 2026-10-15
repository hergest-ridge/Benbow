package app.benbow.client.calendar

import app.benbow.client.unzipFiles
import app.benbow.core.calendar.EventField
import app.benbow.core.calendar.Ics
import app.benbow.core.calendar.IcsCalendar
import app.benbow.core.calendar.IcsEvent
import app.benbow.core.calendar.IcsTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** An event ready to import: its UID (dedup on re-import) and Benbow's fields, times already local. */
data class ImportEvent(val uid: String, val fields: Map<String, String>) {
    val day: String get() = fields.getValue(EventField.START).take(DATE_LENGTH)

    /** The last day it may show on: its end, its series' UNTIL, or forever for an open series. */
    val lastDay: String get() {
        val single = fields[EventField.END]?.take(DATE_LENGTH)?.takeIf { it > day } ?: day
        val rule = fields[EventField.RRULE] ?: return single
        return Recurrence.parse(rule)?.let { it.until?.toString() ?: FOREVER } ?: single
    }
}

/** One calendar found in a file: its name, Benbow color (from a Benbow export), events, and what couldn't be read. */
data class ImportCalendar(
    val name: String,
    val color: String?,
    val events: List<ImportEvent>,
    /** VEVENTs without a readable start. */
    val unreadable: Int = 0,
    /** Times in a zone this device doesn't know (e.g. Outlook's Windows names): kept as written. */
    val unknownZones: Int = 0,
)

/**
 * Reads what calendars export (ADR-028): a `.ics` file or a ZIP of them, like Google Calendar's export with one file
 * per calendar. Zoned and UTC times become local times of [zone]; all-day ends lose ICS's exclusive day; a changed day
 * of a series becomes a day the series skips plus an event of its own; cancelled events are left out.
 */
object IcsImport {
    /** A ZIP bigger than this unpacked is refused (no zip bombs). */
    private const val MAX_UNPACKED = 200L * 1024 * 1024

    suspend fun read(fileName: String, bytes: ByteArray, zone: TimeZone = TimeZone.currentSystemDefault()): List<ImportCalendar> {
        val texts = if (bytes.size > 1 && bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte()
        ) {
            unzip(bytes)
        } else {
            listOf(fileName to bytes)
        }
        return texts.flatMap { (name, data) ->
            Ics.parse(data.decodeToString().removePrefix("")).map { convert(it, name.substringAfterLast('/').removeSuffix(".ics"), zone) }
        }
    }

    private suspend fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> =
        unzipFiles(bytes, MAX_UNPACKED).filter { (name, _) -> name.endsWith(".ics", ignoreCase = true) }

    private fun convert(calendar: IcsCalendar, fallbackName: String, zone: TimeZone): ImportCalendar {
        var unknown = 0
        var unreadable = calendar.skipped
        val local = { t: IcsTime -> local(t, zone) { unknown++ } }
        val (changedDays, series) = calendar.events.partition { it.recurrenceId != null }
        // A changed or cancelled day of a series: the series skips it; a changed one comes as an event of its own.
        val skippedByUid = changedDays.groupBy {
            it.uid
        }.mapValues { (_, days) -> days.mapNotNull { e -> e.recurrenceId?.let(local)?.take(DATE_LENGTH) } }
        val events = (series + changedDays).filter { !it.cancelled }.mapNotNull { e ->
            val fields = fields(e, local, skippedByUid[e.uid].takeIf { e.recurrenceId == null }.orEmpty())
            if (fields == null) unreadable++
            val uid = e.uid?.let { uid -> e.recurrenceId?.let { "$uid/${it.value}" } ?: uid }
            fields?.let { ImportEvent(uid ?: "benbow-import-${(e.summary + it[EventField.START]).hashCode().toUInt()}", it) }
        }
        return ImportCalendar(calendar.name?.takeIf { it.isNotBlank() } ?: fallbackName, calendar.color, events, unreadable, unknown)
    }

    private fun fields(e: IcsEvent, local: (IcsTime) -> String?, skipped: List<String>): Map<String, String>? {
        val start = local(e.start) ?: return null
        val end = e.end?.let { end(start, it, local) } ?: e.duration?.let { end(start, it) }
        val exdates = (e.exdates.mapNotNull { local(it)?.take(DATE_LENGTH) } + skipped).distinct().sorted()
        return listOfNotNull(
            EventField.TITLE to e.summary.trim().ifEmpty { UNTITLED },
            EventField.START to start,
            end?.let { EventField.END to it },
            e.description?.trim()?.takeIf { it.isNotEmpty() }?.let { EventField.DESCRIPTION to it },
            e.location?.trim()?.takeIf { it.isNotEmpty() }?.let { EventField.LOCATION to it },
            e.rrule?.takeIf { e.recurrenceId == null }?.let { EventField.RRULE to it },
            exdates.takeIf { it.isNotEmpty() && e.rrule != null }?.let { EventField.EXDATE to it.joinToString(",") },
        ).toMap()
    }

    /** DTEND: an all-day event's is exclusive (the day after its last); no end when it is the start or before it. */
    private fun end(start: String, end: IcsTime, local: (IcsTime) -> String?): String? {
        val value = local(end) ?: return null
        val last = if (end.isDate) LocalDate.parse(value).minus(1, DateTimeUnit.DAY).toString() else value
        return when {
            start.length == DATE_LENGTH -> last.take(DATE_LENGTH).takeIf { it > start }
            else -> last.takeIf { it > start }
        }
    }

    /** DURATION ("P1D", "PT1H30M", "P1W"): an all-day event of N days ends on its N-th; a timed one N later. */
    private fun end(start: String, duration: String): String? {
        val length = parseDuration(duration) ?: return null
        if (start.length == DATE_LENGTH) {
            val days = length.inWholeDays.toInt()
            return if (days > 1) LocalDate.parse(start).plus(days - 1, DateTimeUnit.DAY).toString() else null
        }
        val at = LocalDateTime.parse(start).toInstant(TimeZone.UTC).plus(length).toLocalDateTime(TimeZone.UTC)
        return format(at).takeIf { it > start }
    }

    private fun parseDuration(text: String): Duration? {
        val m =
            Regex("""^\+?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""").find(text.trim().uppercase()) ?: return null
        val (w, d, h, min, s) = m.destructured
        return (w.toLongOrNull() ?: 0).days * 7 + (d.toLongOrNull() ?: 0).days + (h.toLongOrNull() ?: 0).hours +
            (min.toLongOrNull() ?: 0).minutes + (s.toLongOrNull() ?: 0).seconds
    }

    /** "YYYY-MM-DD" for a date, "YYYY-MM-DDTHH:MM" in [zone] for a time; null when it isn't one. */
    private fun local(t: IcsTime, zone: TimeZone, onUnknownZone: () -> Unit): String? = runCatching {
        val v = t.value
        val date = LocalDate(v.take(4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())
        if (t.isDate) return@runCatching date.toString()
        val written = LocalDateTime(date, LocalTime(v.substring(9, 11).toInt(), v.substring(11, 13).toInt(), v.substring(13, 15).toInt()))
        val source = when {
            t.utc -> TimeZone.UTC
            t.tzid != null -> runCatching { TimeZone.of(t.tzid!!) }.getOrNull().also { if (it == null) onUnknownZone() }
            else -> null
        }
        format(source?.let { written.toInstant(it).toLocalDateTime(zone) } ?: written)
    }.getOrNull()

    private fun format(t: LocalDateTime) =
        t.date.toString() + "T" + t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')

    /** Google writes no SUMMARY for an untitled event. */
    const val UNTITLED = "Без названия"
}

/** What the preview shows of a calendar from a file, for the period from [from] (null = all of it). */
data class ImportSummary(val events: Int, val already: Int, val firstYear: Int?, val lastYear: Int?) {
    val new: Int get() = events - already
}

fun ImportCalendar.summary(from: String?, known: Set<String>): ImportSummary {
    val shown = events.filter { from == null || it.lastDay >= from }
    val years = shown.map { it.day.take(4).toInt() }
    return ImportSummary(shown.size, shown.count { it.uid in known }, years.minOrNull(), years.maxOrNull())
}
