package app.benbow.app

import app.benbow.client.LookFields
import app.benbow.core.tracker.CellField
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.DayBy
import app.benbow.core.tracker.HourWindow
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerField
import app.benbow.core.tracker.TrackerFigure
import app.benbow.core.tracker.TrackerRecord
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

// The trackers' sheet (ADR-050, B4): what a month shows and what a tap writes, as plain functions over the records the
// sheet reads (TrackerSheetScreen.kt draws them).

/** What a tap on a day's cell does, by the column's type. */
internal sealed interface CellTap {
    /** The cell's value becomes [value] (null clears it). */
    data class Set(val value: String?) : CellTap

    /** A count: one more from this device. */
    data object Add : CellTap

    /** A rating or a choice: a small row of its values to pick from ([pickValues]). */
    data object Pick : CellTap

    /** A note: its text is asked for. */
    data object Ask : CellTap

    /** Nothing here (a day's time is put in on the sleep sheet, or by a timer later). */
    data object None : CellTap
}

/** A check flips; a count adds one; a rating or a choice offers its values; a note asks for its text. */
internal fun tapOf(column: TrackerColumn, fields: Map<String, String?>?): CellTap = when (column.type) {
    ColumnType.Check -> CellTap.Set(if (fields?.get(CellField.VALUE) == "true") null else "true")
    ColumnType.Count -> CellTap.Add
    ColumnType.Scale, ColumnType.Choice -> CellTap.Pick
    ColumnType.Note -> CellTap.Ask
    ColumnType.Time, null -> CellTap.None
}

/** The values a rating or a choice offers, in order: its levels, or its options' ids. */
internal fun pickValues(column: TrackerColumn): List<String> = when (column.type) {
    ColumnType.Scale -> (column.min..column.max).map { "$it" }
    ColumnType.Choice -> column.options.map { it.id }
    else -> emptyList()
}

/** How full a cell is drawn, 0 to 1: a count by its goal (full once reached), a rating by its level. */
internal fun TrackerColumn.fill(fields: Map<String, String?>?): Float {
    val value = fields?.get(CellField.VALUE)
    return when (type) {
        ColumnType.Check -> if (value == "true") 1f else 0f

        ColumnType.Count -> {
            val n = fields?.let(TrackerCells::count) ?: 0.0
            goal?.let { (n / it).toFloat().coerceIn(0f, 1f) } ?: if (n > 0) 1f else 0f
        }

        ColumnType.Scale -> value?.toIntOrNull()?.let { ((it - min + 1).toFloat() / (max - min + 1)).coerceIn(0f, 1f) } ?: 0f

        ColumnType.Choice -> if (options.any { it.id == value }) 1f else 0f

        ColumnType.Note -> if (value.isNullOrBlank()) 0f else 1f

        ColumnType.Time, null -> 0f
    }
}

/** A column's figure at the month's foot in words: days, a sum, an average, the most frequent option, a time. */
internal fun figureText(column: TrackerColumn, figure: TrackerFigure?): String = when (figure) {
    null -> ""

    is TrackerFigure.Days -> "${figure.count}"

    is TrackerFigure.Sum -> countText(figure.value)

    is TrackerFigure.Average -> decimal(figure.value)

    is TrackerFigure.Top -> column.options.find { it.id == figure.option }?.let { option ->
        "${option.icon?.let(::iconEmoji) ?: option.name} ${figure.days}"
    } ?: "${figure.days}"

    is TrackerFigure.Time -> clockText(figure.average)
}

/** Every day of [month]'s month, the 1st first. */
internal fun monthDays(month: LocalDate): List<LocalDate> {
    val first = LocalDate(month.year, month.month, 1)
    val next = first.plus(1, DateTimeUnit.MONTH)
    return generateSequence(first) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it < next }.toList()
}

/** A count as the sheet writes it: "3", "2,5". */
internal fun countText(value: Double): String = TrackerCells.number(value).replace('.', ',')

/** "3,4", or "3" for a whole number: one decimal with a comma, as Russian writes it. */
internal fun decimal(value: Double): String {
    val tenths = (value * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"
}

/** A length of time, "7:05", to the nearest minute. */
internal fun clockText(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
}

/** A tracker's emoji and name, as its list and its sheet's title show them; [untitled] for one without a name. */
internal fun trackerTitle(tracker: TrackerRecord, untitled: String): String =
    listOfNotNull(tracker[TrackerField.ICON]?.let(::iconEmoji), tracker[TrackerField.NAME]?.takeIf { it.isNotBlank() } ?: untitled)
        .joinToString(" ")

internal val TrackerRecord.archived: Boolean get() = this[TrackerField.ARCHIVED] == "true"

internal val TrackerColumn.archived: Boolean get() = record[ColumnField.ARCHIVED] == "true"

/** The header text where a column is narrow: its short one, else its name. */
internal val TrackerColumn.short: String get() = record[ColumnField.SHORT]?.takeIf { it.isNotBlank() } ?: name

/** A time column drawn as the sleep sheet's hours: one with a window. */
internal val TrackerColumn.sheetHours: HourWindow? get() = if (type == ColumnType.Time) hours else null

/**
 * «Оформление трекера»: the look sheet's layers as the tracker's fields (`look_<layer>`, ADR-047), only layers the app
 * sets and only tokens; null takes a layer off.
 */
internal fun trackerLook(layers: Map<String, String?>): Map<String, String?> = layers.filterKeys { it in LookFields.LAYERS }
    .map { (layer, token) -> TrackerField.LOOK_PREFIX + layer to token?.takeIf(LookFields::isToken) }
    .toMap()

/** The emoji of an `emoji:` icon token; other schemes (a hand-drawn set) come later and give none here. */
internal fun iconEmoji(icon: String): String? = if (icon.startsWith(EMOJI)) icon.removePrefix(EMOJI).ifEmpty { null } else null

private const val EMOJI = "emoji:"

/**
 * The hours a time column shows on [day]'s row, as the times they start: its window's clock hours on the day they fall
 * on. A night over midnight belongs to the day one wakes ([DayBy.End]: its evening hours are the day before) or to the
 * day it starts ([DayBy.Start]: its morning hours are the next day).
 */
internal fun hourStarts(window: HourWindow, day: LocalDate, by: DayBy): List<LocalDateTime> {
    val fromHour = window.from / 60
    val crosses = window.to <= window.from
    return window.hours.map { hour ->
        val date = when {
            !crosses -> day
            by == DayBy.End && hour >= fromHour -> day.minus(1, DateTimeUnit.DAY)
            by == DayBy.Start && hour < fromHour -> day.plus(1, DateTimeUnit.DAY)
            else -> day
        }
        LocalDateTime(date, LocalTime(hour, 0))
    }
}

/** Whether any of [spans] (outside the trash, with an end) covers part of the hour from [start]. */
internal fun covered(spans: List<TrackerRecord>, start: LocalDateTime): Boolean {
    val from = seconds(start)
    return bounds(spans).any { (_, b) -> b.first < from + HOUR && b.second > from }
}

/** A change to a time column's spans, as a tap on an hour asks. */
internal sealed interface SpanEdit {
    data class Add(val start: String, val end: String) : SpanEdit

    data class Change(val id: String, val fields: Map<String, String?>) : SpanEdit

    data class Trash(val id: String) : SpanEdit
}

/**
 * A tap on the hour from [start] on the sleep sheet: an empty hour joins the span that ends or starts beside it (both:
 * they become one), else becomes a span of its own; a painted hour is taken out of its span (its first or last hour
 * shortens it, one in the middle splits it in two, a span of that hour alone goes to the trash). Dragging along a row
 * and minutes come with B6.
 */
internal fun hourTap(spans: List<TrackerRecord>, start: LocalDateTime): List<SpanEdit> {
    val from = seconds(start)
    val to = from + HOUR
    val live = bounds(spans)
    live.firstOrNull { (_, b) -> b.first < to && b.second > from }?.let { (span, b) ->
        val (s, e) = b
        return when {
            s >= from && e <= to -> listOf(SpanEdit.Trash(span.id))
            s >= from -> listOf(SpanEdit.Change(span.id, mapOf(SpanField.START to timeText(to))))
            e <= to -> listOf(SpanEdit.Change(span.id, mapOf(SpanField.END to timeText(from))))
            else -> listOf(SpanEdit.Change(span.id, mapOf(SpanField.END to timeText(from))), SpanEdit.Add(timeText(to), timeText(e)))
        }
    }
    val before = live.firstOrNull { (_, b) -> b.second == from }
    val after = live.firstOrNull { (_, b) -> b.first == to }
    return when {
        before != null && after != null -> listOf(
            SpanEdit.Change(before.first.id, mapOf(SpanField.END to timeText(after.second.second))),
            SpanEdit.Trash(after.first.id),
        )

        before != null -> listOf(SpanEdit.Change(before.first.id, mapOf(SpanField.END to timeText(to))))

        after != null -> listOf(SpanEdit.Change(after.first.id, mapOf(SpanField.START to timeText(from))))

        else -> listOf(SpanEdit.Add(timeText(from), timeText(to)))
    }
}

/** Spans outside the trash that have an end, with their start and end in seconds. */
private fun bounds(spans: List<TrackerRecord>): List<Pair<TrackerRecord, Pair<Long, Long>>> =
    spans.filter { !it.deleted }.mapNotNull { span ->
        val start = span[SpanField.START]?.let(::parseTime) ?: return@mapNotNull null
        val end = span[SpanField.END]?.let(::parseTime) ?: return@mapNotNull null
        span to (seconds(start) to seconds(end))
    }

private fun parseTime(text: String): LocalDateTime? = runCatching { LocalDateTime.parse(text) }.getOrNull()

/** Floating local times counted as if in UTC: no zone, no daylight saving (ADR-028). */
private fun seconds(time: LocalDateTime): Long = time.toInstant(TimeZone.UTC).epochSeconds

/** "YYYY-MM-DDTHH:MM:SS", as spans keep their times. */
internal fun timeText(seconds: Long): String {
    val time = Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.UTC)
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${time.date}T${two(time.hour)}:${two(time.minute)}:${two(time.second)}"
}

private const val HOUR = 3600L
