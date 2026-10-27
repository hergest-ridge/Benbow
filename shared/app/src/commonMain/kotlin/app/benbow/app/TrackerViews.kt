package app.benbow.app

import app.benbow.core.tracker.CellField
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerRecord
import app.benbow.core.tracker.TrackerSpans
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

// The trackers' other views and bridges (ADR-050, B7): where a tracker opens, a week's days, a year's pixels, a day's
// marks in a few characters for the calendar and Home. Plain functions; TrackerWeek.kt, TrackerYear.kt and
// TrackerBlocks.kt draw them.

/** Where a tracker opens, its `view` field: the month (the sleep sheet is a month with its hours), a week, a year. */
internal enum class SheetView(val token: String) {
    Month("month"),
    Week("week"),
    Year("year"),
    ;

    companion object {
        fun of(token: String?): SheetView = entries.firstOrNull { it.token == token } ?: Month
    }
}

/** The Monday of [day]'s week. */
internal fun mondayOf(day: LocalDate): LocalDate = day.minus(day.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

/** The seven days, Monday first, of the week [page] pages from [today]'s ([WEEK_ORIGIN]). */
internal fun weekOfPage(today: LocalDate, page: Int): List<LocalDate> {
    val monday = mondayOf(today).plus((page - WEEK_ORIGIN) * 7, DateTimeUnit.DAY)
    return (0..6).map { monday.plus(it, DateTimeUnit.DAY) }
}

/** The year [page] pages from [today]'s ([YEAR_ORIGIN]). */
internal fun yearOfPage(today: LocalDate, page: Int): Int = today.year + page - YEAR_ORIGIN

/** A year's pixels, month by month: 31 places each, the days a month lacks null (the 30th of February). */
internal fun yearDays(year: Int): List<LocalDate?> = (1..12).flatMap { month ->
    (1..31).map { day -> runCatching { LocalDate(year, month, day) }.getOrNull() }
}

/** How full a day's pixel is drawn, 0 to 1: as its cell would be, a day's time by its goal in hours (else eight). */
internal fun pixelFill(column: TrackerColumn, fields: Map<String, String?>?, seconds: Long): Float = if (column.type == ColumnType.Time) {
    (seconds / ((column.goal ?: DEFAULT_HOURS) * HOUR)).toFloat().coerceIn(0f, 1f)
} else {
    column.fill(fields)
}

/**
 * A column's mark on a day in a few characters, for the calendar's row and Home: its emoji when marked (a choice's
 * own), with its number where it has one and [figures] show (a count, a rating, a day's time); null when nothing.
 */
internal fun markText(column: TrackerColumn, fields: Map<String, String?>?, seconds: Long, figures: Boolean): String? {
    val icon = column.record[ColumnField.ICON]?.let(::iconEmoji) ?: column.short.take(1)
    val value = fields?.get(CellField.VALUE)
    fun numbered(number: String) = if (figures) "$icon $number" else icon
    return when (column.type) {
        ColumnType.Check -> icon.takeIf { value == "true" }

        ColumnType.Count -> fields?.let(TrackerCells::count)?.takeIf { it != 0.0 }?.let { numbered(countText(it)) }

        ColumnType.Scale -> value?.toIntOrNull()?.let { numbered("$it") }

        ColumnType.Choice -> column.options.find { it.id == value }?.let { option ->
            option.icon?.let(::iconEmoji)
                ?: option.name.ifBlank { icon }
        }

        ColumnType.Note -> icon.takeIf { !value.isNullOrBlank() }

        ColumnType.Time -> seconds.takeIf { it > 0 }?.let { numbered(clockText(it)) }

        null -> null
    }
}

/** How many of [columns] are marked on [day], by [cells] (by cell id) and [spans] (by column): Home's quiet count. */
internal fun markedOn(
    columns: List<TrackerColumn>,
    cells: Map<String, Map<String, String?>>,
    spans: Map<String, List<TrackerRecord>>,
    day: String,
): Int = columns.count { column ->
    if (column.type == ColumnType.Time) {
        TrackerSpans.total(spans[column.id].orEmpty(), day, column.dayBy) > 0
    } else {
        cells[TrackerCells.id(column.id, day)]?.let { TrackerCells.marked(column.type, it) } == true
    }
}

/** Pages of weeks and years either side of today's. */
internal const val WEEK_PAGES = 4_000
internal const val WEEK_ORIGIN = 2_000
internal const val YEAR_PAGES = 400
internal const val YEAR_ORIGIN = 200

/** A day's time fills its pixel at this many hours when the column has no goal. */
private const val DEFAULT_HOURS = 8.0
