package app.benbow.app

import app.benbow.client.LookFields
import app.benbow.client.tracker.TrackerRepository
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

    /** Nothing here (a day's time is put in on the sleep sheet or in its day's spans). */
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

/**
 * What a right click on a day clears (owner, ADR-058), back to blank paper, and what «Вернуть» puts back: a value as it
 * was, a count's total, or the day's spans (to the trash and out again). A day's note stays: it isn't the column's value.
 */
internal sealed interface DayClear {
    data class Value(val was: String) : DayClear

    data class Count(val was: Double) : DayClear

    data class Spans(val ids: List<String>) : DayClear
}

/** A right click on [day]'s cell: null when it holds nothing to clear (a time column's spans counted for that day). */
internal fun dayClear(column: TrackerColumn, day: String, fields: Map<String, String?>?, spans: List<TrackerRecord>): DayClear? =
    when (column.type) {
        ColumnType.Check, ColumnType.Scale, ColumnType.Choice, ColumnType.Note -> fields?.get(CellField.VALUE)?.let(DayClear::Value)
        ColumnType.Count -> fields?.let(TrackerCells::count)?.takeIf { it != 0.0 }?.let(DayClear::Count)
        ColumnType.Time -> spansOn(spans, day, column.dayBy).map { it.id }.takeIf { it.isNotEmpty() }?.let(DayClear::Spans)
        null -> null
    }

/** A right click on a sleep row: every span drawn in its hours ([starts]), so the row is blank after; null when it is. */
internal fun rowClear(spans: List<TrackerRecord>, starts: List<LocalDateTime>): DayClear? {
    if (starts.isEmpty()) return null
    val from = seconds(starts.first())
    val to = seconds(starts.last()) + HOUR
    return bounds(spans).filter { (_, b) -> b.first < to && b.second > from }.map { (span, _) -> span.id }
        .takeIf { it.isNotEmpty() }?.let(DayClear::Spans)
}

internal fun TrackerRepository.clearDay(column: String, day: String, clear: DayClear) {
    when (clear) {
        is DayClear.Value -> setCell(column, day, null)
        is DayClear.Count -> setCount(column, day, 0.0)
        is DayClear.Spans -> together { clear.ids.forEach(::moveSpanToTrash) }
    }
}

internal fun TrackerRepository.unclearDay(column: String, day: String, clear: DayClear) {
    when (clear) {
        is DayClear.Value -> setCell(column, day, clear.was)
        is DayClear.Count -> setCount(column, day, clear.was)
        is DayClear.Spans -> together { clear.ids.forEach { updateSpan(it, mapOf(SpanField.DELETED to null)) } }
    }
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
 * sets (a font is a note's alone) and only tokens; null takes a layer off.
 */
internal fun trackerLook(layers: Map<String, String?>): Map<String, String?> =
    layers.filterKeys { it in LookFields.LAYERS && it != LookFields.FONT }
        .map { (layer, token) -> TrackerField.LOOK_PREFIX + layer to token?.takeIf(LookFields::isToken) }
        .toMap()

/** The layers the screen behind a tracker's sheet takes (ADR-051): a note's pattern, light and motion. */
internal val SCREEN_LAYERS = listOf(LookLayer.Pattern, LookLayer.Light, LookLayer.Motion)

/** The screen's look by layer, from the tracker's `screen_<layer>` fields (a newer app's layers too: drawing skips them). */
internal fun screenLook(fields: Map<String, String?>): Map<String, String> = fields.mapNotNull { (key, value) ->
    if (key.startsWith(TrackerField.SCREEN_PREFIX) && value != null) key.removePrefix(TrackerField.SCREEN_PREFIX) to value else null
}.toMap()

/** «Фон экрана»: its layers as the tracker's fields, only [SCREEN_LAYERS] and only tokens; null takes a layer off. */
internal fun trackerScreen(layers: Map<String, String?>): Map<String, String?> =
    layers.filterKeys { layer -> SCREEN_LAYERS.any { it.field == layer } }
        .map { (layer, token) -> TrackerField.SCREEN_PREFIX + layer to token?.takeIf(LookFields::isToken) }
        .toMap()

/** A month with its days along the top and the columns down the side (owner, ADR-051), set in «Настроить трекер». */
internal val TrackerRecord.across: Boolean get() = this[TrackerField.LAYOUT] == LAYOUT_ACROSS

internal const val LAYOUT_ACROSS = "across"

/** The emoji of an `emoji:` icon token; other schemes (a hand-drawn set) come later and give none here. */
internal fun iconEmoji(icon: String): String? = if (icon.startsWith(EMOJI)) icon.removePrefix(EMOJI).ifEmpty { null } else null

private const val EMOJI = "emoji:"

/**
 * The hours a time column shows on [day]'s row, as the times they start: its window's clock hours on the day they fall
 * on. A night over midnight belongs to the day one wakes ([DayBy.End]: its evening hours are the day before) or to the
 * day it starts ([DayBy.Start]: its morning hours are the next day). A window from midnight is the day itself, a whole
 * one too ("00:00-00:00", the sleep sheet's).
 */
internal fun hourStarts(window: HourWindow, day: LocalDate, by: DayBy): List<LocalDateTime> {
    val fromHour = window.from / 60
    val crosses = fromHour > 0 && window.to <= window.from
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
 * A tap on the hour from [start] on the sleep sheet: a painted hour is taken out of its span ([eraseSpans]), an empty one
 * joins the spans beside it or becomes one of its own ([paintSpans]).
 */
internal fun hourTap(spans: List<TrackerRecord>, start: LocalDateTime): List<SpanEdit> {
    val from = seconds(start)
    return if (covered(spans, start)) eraseSpans(spans, from, from + HOUR) else paintSpans(spans, from, from + HOUR)
}

/**
 * The edits that make [from]..[to] (seconds, floating) painted: spans it overlaps or touches become one, the first of
 * them stretched over all and the others to the trash; none: a span of its own. Nothing when it is painted already.
 */
internal fun paintSpans(spans: List<TrackerRecord>, from: Long, to: Long): List<SpanEdit> {
    val touching = bounds(spans).filter { (_, b) -> b.first <= to && b.second >= from }.sortedBy { (_, b) -> b.first }
    val first = touching.firstOrNull() ?: return listOf(SpanEdit.Add(timeText(from), timeText(to)))
    val start = minOf(from, touching.minOf { (_, b) -> b.first })
    val end = maxOf(to, touching.maxOf { (_, b) -> b.second })
    val (span, b) = first
    val fields = buildMap {
        if (start != b.first) put(SpanField.START, timeText(start))
        if (end != b.second) put(SpanField.END, timeText(end))
    }
    return listOfNotNull(fields.takeIf { it.isNotEmpty() }?.let { SpanEdit.Change(span.id, it) }) +
        touching.drop(1).map { (other, _) -> SpanEdit.Trash(other.id) }
}

/**
 * The edits that take [from]..[to] (seconds) out of the spans: one inside it goes to the trash, one over an end is
 * shortened, one around it is split in two.
 */
internal fun eraseSpans(spans: List<TrackerRecord>, from: Long, to: Long): List<SpanEdit> =
    bounds(spans).filter { (_, b) -> b.first < to && b.second > from }.flatMap { (span, b) ->
        val (s, e) = b
        when {
            s >= from && e <= to -> listOf(SpanEdit.Trash(span.id))
            s >= from -> listOf(SpanEdit.Change(span.id, mapOf(SpanField.START to timeText(to))))
            e <= to -> listOf(SpanEdit.Change(span.id, mapOf(SpanField.END to timeText(from))))
            else -> listOf(SpanEdit.Change(span.id, mapOf(SpanField.END to timeText(from))), SpanEdit.Add(timeText(to), timeText(e)))
        }
    }

/** Spans outside the trash that have an end, with their start and end in seconds. */
private fun bounds(spans: List<TrackerRecord>): List<Pair<TrackerRecord, Pair<Long, Long>>> =
    spans.filter { !it.deleted }.mapNotNull { span ->
        val start = span[SpanField.START]?.let(::parseLocal) ?: return@mapNotNull null
        val end = span[SpanField.END]?.let(::parseLocal) ?: return@mapNotNull null
        span to (seconds(start) to seconds(end))
    }

private fun parseLocal(text: String): LocalDateTime? = runCatching { LocalDateTime.parse(text) }.getOrNull()

/** Floating local times counted as if in UTC: no zone, no daylight saving (ADR-028). */
internal fun seconds(time: LocalDateTime): Long = time.toInstant(TimeZone.UTC).epochSeconds

/** "YYYY-MM-DDTHH:MM:SS", as spans keep their times. */
internal fun timeText(seconds: Long): String {
    val time = Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.UTC)
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${time.date}T${two(time.hour)}:${two(time.minute)}:${two(time.second)}"
}

internal const val HOUR = 3600L
