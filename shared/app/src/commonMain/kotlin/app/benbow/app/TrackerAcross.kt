package app.benbow.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benbow.app.resources.Res
import app.benbow.app.resources.trackers_total
import app.benbow.app.resources.weekdays_short
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.HourWindow
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerFigures
import app.benbow.core.tracker.TrackerRecord
import app.benbow.core.tracker.TrackerSpans
import app.benbow.ui.sideScroll
import app.benbow.ui.theme.BenbowTheme
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * A month across (owner, ADR-051; the tracker's `layout`): its days along the top and the tracker's columns down the
 * side, a sleep column's hours each a row of their own, painted down a day's column. Too wide for the window (a phone),
 * the days scroll sideways under names that stay, today a few days from the left; the month's figures end each row.
 * Cells answer as on the page down ([DayCell], [HourStrip]).
 */
@Composable
internal fun AcrossMonthPage(
    services: AppServices,
    columns: List<TrackerColumn>,
    month: LocalDate,
    today: LocalDate,
    figures: Boolean,
    write: TrackerWrite,
) {
    val days = remember(month) { monthDays(month) }
    val (cells, spans) = rememberMonthRecords(services, columns, days)
    val sofar = remember(days, today) { days.filter { it <= today }.map { "$it" } }
    val weekdays = stringArrayResource(Res.array.weekdays_short)
    var asked by remember { mutableStateOf<Pair<TrackerColumn, LocalDate>?>(null) }
    var spansOf by remember { mutableStateOf<Pair<TrackerColumn, LocalDate>?>(null) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = SheetSizes.SIDE)) {
        val names = if (maxWidth < Across.WIDE) Across.NAMES_NARROW else Across.NAMES
        val end = if (figures) Across.TOTAL else 0.dp
        val gaps = Across.WEEK_GAP * days.count { it.dayOfWeek == DayOfWeek.SUNDAY && it != days.last() }
        val day = ((maxWidth - names - end - gaps) / days.size).coerceAtLeast(Across.MIN_DAY)
        val grid = DayGrid(days, today, day, end, scroll)
        LaunchedEffect(month, day) {
            val at = days.indexOf(today) - Across.TODAY_LEFT
            // To a day's left edge: the days before it and the gaps after their Sundays.
            val gapsBefore = days.take(at.coerceAtLeast(0)).count { it.dayOfWeek == DayOfWeek.SUNDAY }
            if (at > 0) scroll.scrollTo(with(density) { (day * at + Across.WEEK_GAP * gapsBefore).roundToPx() })
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(SheetSizes.HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(names))
                grid.Days(Modifier.weight(1f).fillMaxHeight(), last = {
                    if (figures) Label(stringResource(Res.string.trackers_total))
                }) { date ->
                    DayLabel(date, weekdays.getOrElse(date.dayOfWeek.isoDayNumber - 1) { "" }, date == today)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                columns.forEach { column ->
                    val own = spans[column.id].orEmpty()
                    val window = column.sheetHours
                    val byDay = remember(cells, column.id, sofar) {
                        sofar.mapNotNull { date -> cells[TrackerCells.id(column.id, date)]?.let { date to it } }.toMap()
                    }
                    val total = figureText(column, TrackerFigures.of(column, byDay, own, sofar))
                    if (window == null) {
                        Row(Modifier.fillMaxWidth().height(SheetSizes.ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                            ColumnName(column, write, Modifier.width(names))
                            grid.Days(Modifier.weight(1f).fillMaxHeight(), last = { Figure(total) }) { date ->
                                val id = "$date"
                                DayCell(
                                    column,
                                    id,
                                    cells[TrackerCells.id(column.id, id)],
                                    own,
                                    figures,
                                    enabled = date <= today,
                                    modifier = Modifier.fillMaxSize(),
                                    write = write,
                                    onSpans = { spansOf = column to date },
                                    onAsk = { asked = column to date },
                                )
                            }
                        }
                    } else {
                        SleepRows(column, window, own, grid, names, figures, total, write) { spansOf = column to it }
                    }
                    HorizontalDivider(color = BenbowTheme.colors.divider)
                }
            }
        }
    }
    asked?.let { (column, day) ->
        CellDialog(column, "$day", cells[TrackerCells.id(column.id, "$day")], write) { asked = null }
    }
    spansOf?.let { (column, day) ->
        DaySpansDialog(column, day, spans[column.id].orEmpty(), write) { spansOf = null }
    }
}

/**
 * A sleep column across: its name beside its hours, a row each, and every day's hours down its own column; with the
 * figures, a row of each day's length under them.
 */
@Composable
private fun SleepRows(
    column: TrackerColumn,
    window: HourWindow,
    own: List<TrackerRecord>,
    grid: DayGrid,
    names: Dp,
    figures: Boolean,
    total: String,
    write: TrackerWrite,
    onSpans: (LocalDate) -> Unit,
) {
    val hours = window.hours
    Row(Modifier.fillMaxWidth().height(Across.HOUR * hours.size)) {
        ColumnName(column, write, Modifier.width(names - Across.HOUR_NUMBERS))
        Column(Modifier.width(Across.HOUR_NUMBERS).fillMaxHeight()) {
            hours.forEach { hour ->
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Label("$hour") }
            }
        }
        grid.Days(Modifier.weight(1f).fillMaxHeight(), last = { Figure(total) }) { date ->
            HourStrip(
                column,
                date,
                window,
                own,
                enabled = date <= grid.today,
                write = write,
                onSpans = { onSpans(date) },
                modifier = Modifier.fillMaxSize(),
                down = true,
            )
        }
    }
    if (figures) {
        Row(Modifier.fillMaxWidth().height(SheetSizes.WEEK_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(names))
            grid.Days(Modifier.weight(1f).fillMaxHeight()) { date ->
                Label(TrackerSpans.total(own, "$date", column.dayBy).takeIf { it > 0 }?.let(::clockText).orEmpty())
            }
        }
    }
}

/**
 * The month's days as slots of one width, a little gap after each Sunday and [end] wide at the end, sideways on one
 * [scroll] for every row so the days stay one above the other; today's slot is tinted, days to come faint.
 */
private class DayGrid(val days: List<LocalDate>, val today: LocalDate, val day: Dp, val end: Dp, val scroll: ScrollState) {
    @Composable
    fun Days(modifier: Modifier, last: @Composable () -> Unit = {}, slot: @Composable (LocalDate) -> Unit) {
        val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = SheetSizes.TODAY_ALPHA)
        Row(modifier.sideScroll(scroll)) {
            days.forEach { date ->
                Box(
                    Modifier.width(day).fillMaxHeight()
                        .then(if (date == today) Modifier.background(tint) else Modifier)
                        .alpha(if (date > today) SheetSizes.FUTURE_ALPHA else 1f),
                    contentAlignment = Alignment.Center,
                ) { slot(date) }
                if (date.dayOfWeek == DayOfWeek.SUNDAY && date != days.last()) Spacer(Modifier.width(Across.WEEK_GAP))
            }
            if (end > 0.dp) Box(Modifier.width(end).fillMaxHeight(), contentAlignment = Alignment.Center) { last() }
        }
    }
}

/** A column's name down the side: its emoji and short text; a time column's timer beside. */
@Composable
private fun ColumnName(column: TrackerColumn, write: TrackerWrite, modifier: Modifier) {
    Row(modifier.fillMaxHeight().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        column.record[ColumnField.ICON]?.let(::iconEmoji)?.let { Text(it, fontSize = 16.sp, maxLines = 1) }
        Text(
            column.short,
            Modifier.weight(1f).padding(start = 4.dp),
            fontSize = 11.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
        )
        if (column.type == ColumnType.Time) TimerButton(column, write)
    }
}

@Composable
private fun Label(text: String) = Text(
    text,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize = 9.sp,
    textAlign = TextAlign.Center,
    maxLines = 1,
    style = MaterialTheme.typography.labelSmall,
)

@Composable
private fun Figure(text: String) = Text(text, textAlign = TextAlign.Center, maxLines = 1, style = MaterialTheme.typography.labelMedium)

private object Across {
    /** The names down the side, narrower on a phone; a sleep column's hour numbers take a strip of them. */
    val NAMES = 120.dp
    val NAMES_NARROW = 84.dp
    val HOUR_NUMBERS = 22.dp
    val WIDE = 600.dp

    /** No narrower a day than this (a whole month fits the widest sheet); on a phone the days scroll then. */
    val MIN_DAY = 24.dp
    val WEEK_GAP = 4.dp
    val TOTAL = 48.dp

    /** An hour's row of a sleep column. */
    val HOUR = 20.dp
    const val TODAY_LEFT = 3
}
