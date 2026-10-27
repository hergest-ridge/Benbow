package app.benbow.core.tracker

import app.benbow.core.LocalDays

/** A column's figure at the foot of some days. Figures only count up: no streaks, percentages or missed days (ux.md). */
sealed interface TrackerFigure {
    /** How many days are marked (checks, notes). */
    data class Days(val count: Int) : TrackerFigure

    /** A count's sum. */
    data class Sum(val value: Double) : TrackerFigure

    /** A rating's average. */
    data class Average(val value: Double) : TrackerFigure

    /** A choice's most frequent option (the first to get there on a tie) and on how many days. */
    data class Top(val option: String, val days: Int) : TrackerFigure

    /** A time column's average over the days it has time on, and all of it, in seconds. */
    data class Time(val average: Long, val total: Long) : TrackerFigure
}

/** The figures and goals of a column over its cells (by day, "YYYY-MM-DD") and its spans. */
object TrackerFigures {
    /** [column]'s figure over [days]; null while nothing in them is marked: no figure rather than a zero. */
    fun of(
        column: TrackerColumn,
        cells: Map<String, Map<String, String?>>,
        spans: List<TrackerRecord>,
        days: List<String>,
    ): TrackerFigure? {
        val type = column.type ?: return null
        if (type == ColumnType.Time) {
            val totals = days.map { TrackerSpans.total(spans, it, column.dayBy) }.filter { it > 0 }
            return if (totals.isEmpty()) null else TrackerFigure.Time(totals.sum() / totals.size, totals.sum())
        }
        val marked = days.mapNotNull { day -> cells[day]?.takeIf { TrackerCells.marked(type, it) } }
        if (marked.isEmpty()) return null
        return when (type) {
            ColumnType.Check, ColumnType.Note -> TrackerFigure.Days(marked.size)

            ColumnType.Count -> TrackerFigure.Sum(marked.sumOf(TrackerCells::count))

            ColumnType.Scale -> TrackerFigure.Average(marked.mapNotNull { it[CellField.VALUE]?.toIntOrNull() }.average())

            ColumnType.Choice -> {
                val top = marked.mapNotNull { it[CellField.VALUE] }.groupingBy { it }.eachCount().maxBy { it.value }
                TrackerFigure.Top(top.key, top.value)
            }

            ColumnType.Time -> null
        }
    }

    /**
     * Whether [column]'s goal is reached on [day]: a count or a time (hours) that day; marked days in [day]'s week
     * (Monday to Sunday) or month. A goal only ever fills once reached: nothing marks it missed.
     */
    fun reached(column: TrackerColumn, cells: Map<String, Map<String, String?>>, spans: List<TrackerRecord>, day: String): Boolean {
        val goal = column.goal ?: return false
        val type = column.type ?: return false
        fun has(d: String): Boolean = if (type ==
            ColumnType.Time
        ) {
            TrackerSpans.total(spans, d, column.dayBy) > 0
        } else {
            cells[d]?.let { TrackerCells.marked(type, it) } == true
        }
        return when (column.goalPer) {
            GoalPer.Day -> when (type) {
                ColumnType.Count -> (cells[day]?.let(TrackerCells::count) ?: 0.0) >= goal
                ColumnType.Time -> TrackerSpans.total(spans, day, column.dayBy) >= goal * SECONDS_AN_HOUR
                else -> false
            }

            GoalPer.Week, GoalPer.Month -> period(day, column.goalPer).count(::has) >= goal
        }
    }

    /** The days of [day]'s week (Monday first) or month. */
    internal fun period(day: String, per: GoalPer): List<String> {
        val epoch = LocalDays.parse(day) ?: return emptyList()
        return when (per) {
            GoalPer.Day -> listOf(day)

            GoalPer.Week -> {
                val monday = epoch - (epoch + 3).mod(7)
                (0..6).map { LocalDays.format(monday + it) }
            }

            GoalPer.Month -> {
                val (year, month, _) = LocalDays.civil(epoch)
                generateSequence(LocalDays.epochDay(year, month, 1)) { it + 1 }
                    .takeWhile { LocalDays.civil(it).second == month }
                    .map(LocalDays::format)
                    .toList()
            }
        }
    }

    private const val SECONDS_AN_HOUR = 3600
}
