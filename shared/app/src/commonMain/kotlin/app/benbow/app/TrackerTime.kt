package app.benbow.app

import app.benbow.core.tracker.DayBy
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerRecord
import app.benbow.core.tracker.TrackerSpans
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

// The trackers' time (ADR-050, B6): a timer's running time, the hours a drag on the sleep sheet covers, a day's spans as
// typed in its sheet, a week's days. Plain functions; TrackerSheetScreen.kt and DaySpansDialog.kt draw them. Times are
// local and floating ("YYYY-MM-DDTHH:MM:SS"), as spans keep them (ADR-028).

/** A local time as spans and timers keep it. */
internal fun timeOf(time: LocalDateTime): String = timeText(seconds(time))

/** Seconds a timer started at [running] has run by [now]; none for a start that doesn't read or lies ahead. */
internal fun runningSeconds(running: String, now: LocalDateTime): Long =
    runCatching { LocalDateTime.parse(running) }.getOrNull()?.let { (seconds(now) - seconds(it)).coerceAtLeast(0) } ?: 0

/** A timer's running time as its header ticks: "4:05" under an hour (minutes and seconds), "1:04:05" over one. */
internal fun tickText(seconds: Long): String {
    fun two(n: Long) = n.toString().padStart(2, '0')
    val hours = seconds / HOUR
    val minutes = seconds % HOUR / 60
    return if (hours > 0) "$hours:${two(minutes)}:${two(seconds % 60)}" else "$minutes:${two(seconds % 60)}"
}

/** The hour under [x] in a row of [count] hours [width] wide. */
internal fun hourAt(x: Float, width: Int, count: Int): Int = if (width <= 0) 0 else (x / width * count).toInt().coerceIn(0, count - 1)

/**
 * A drag along a day's hours ([starts]) from [from] to [to] (indexes, either way): it paints them when it started on an
 * empty hour, else takes them out of the spans; one long press with no drag opens the day's spans instead.
 */
internal fun dragEdits(spans: List<TrackerRecord>, starts: List<LocalDateTime>, from: Int, to: Int): List<SpanEdit> {
    val low = minOf(from, to).coerceIn(starts.indices)
    val high = maxOf(from, to).coerceIn(starts.indices)
    val range = seconds(starts[low]) to seconds(starts[high]) + HOUR
    return if (covered(spans, starts[from.coerceIn(starts.indices)])) {
        eraseSpans(spans, range.first, range.second)
    } else {
        paintSpans(spans, range.first, range.second)
    }
}

/** The spans with an end that count for [day] (the day [by] gives each), by their start. */
internal fun spansOn(spans: List<TrackerRecord>, day: String, by: DayBy): List<TrackerRecord> =
    spans.filter { !it.deleted && it[SpanField.END] != null && TrackerSpans.dayOf(it, by) == day }.sortedBy { it[SpanField.START] }

/** A time's clock, "23:10"; "" for none. */
internal fun clockAt(time: String?): String = time?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    ?.let { clockOf(it.hour * 60 + it.minute) }
    .orEmpty()

/** A span as typed in a day's sheet: its id (null: a new one), its start and end clocks; [gone]: to the trash. */
internal data class SpanDraft(val id: String?, val start: String, val end: String, val gone: Boolean = false)

/** A new span's clocks to start from: a night for a span counted by its end, an hour of the morning otherwise. */
internal fun newSpanDraft(by: DayBy): SpanDraft = if (by ==
    DayBy.End
) {
    SpanDraft(null, "23:00", "07:00")
} else {
    SpanDraft(null, "09:00", "10:00")
}

/**
 * Where a span typed as [start]..[end] (minutes after midnight) lies for [day]: one counted by its end ends that day and
 * starts the day before when its start comes later on the clock (a night); one counted by its start starts that day and
 * ends the day after when its end comes earlier. Null for a span of no length.
 */
internal fun spanTimes(day: LocalDate, by: DayBy, start: Int, end: Int): Pair<String, String>? {
    if (start == end) return null
    fun at(date: LocalDate, minutes: Int) = timeOf(LocalDateTime(date, LocalTime(minutes / 60, minutes % 60)))
    return when (by) {
        DayBy.End -> at(if (start < end) day else day.minus(1, DateTimeUnit.DAY), start) to at(day, end)
        DayBy.Start -> at(day, start) to at(if (end > start) day else day.plus(1, DateTimeUnit.DAY), end)
    }
}

/**
 * What a day's sheet writes on «Сохранить» ([drafts] over [spans], the day's spans as listed): new spans added, times
 * that were changed written (a clock left as it was keeps its seconds), spans let go to the trash. Null when a typed
 * time doesn't read: nothing is written then.
 */
internal fun spanEdits(day: LocalDate, by: DayBy, drafts: List<SpanDraft>, spans: List<TrackerRecord>): List<SpanEdit>? {
    val byId = spans.associateBy { it.id }
    val edits = mutableListOf<SpanEdit>()
    for (draft in drafts) {
        val old = draft.id?.let(byId::get)
        if (draft.gone) {
            if (old != null) edits += SpanEdit.Trash(old.id)
            continue
        }
        // Left as it was: nothing to read again (a timer stopped within its minute has no length on the clock).
        if (old != null && draft.start == clockAt(old[SpanField.START]) && draft.end == clockAt(old[SpanField.END])) continue
        val (start, end) = spanTimes(day, by, typedClock(draft.start) ?: return null, typedClock(draft.end) ?: return null) ?: return null
        if (draft.id == null) {
            edits += SpanEdit.Add(start, end)
            continue
        }
        if (old == null) continue // gone meanwhile
        // A clock left as it was on the same date keeps its time to the second (a timer's stop).
        fun kept(field: String, typed: String, computed: String): String {
            val was = old[field]
            return if (was != null && typed == clockAt(was) && was.take(DATE) == computed.take(DATE)) was else computed
        }
        val fields = buildMap {
            kept(SpanField.START, draft.start, start).takeIf { it != old[SpanField.START] }?.let { put(SpanField.START, it) }
            kept(SpanField.END, draft.end, end).takeIf { it != old[SpanField.END] }?.let { put(SpanField.END, it) }
        }
        if (fields.isNotEmpty()) edits += SpanEdit.Change(old.id, fields)
    }
    return edits
}

/** [day]'s week (Monday to Sunday) among [days]. */
internal fun weekOf(day: LocalDate, days: List<LocalDate>): List<LocalDate> {
    val monday = day.minus(day.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    val sunday = monday.plus(6, DateTimeUnit.DAY)
    return days.filter { it in monday..sunday }
}

/** "YYYY-MM-DD", the date part of a time. */
private const val DATE = 10
