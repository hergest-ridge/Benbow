package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benbow.app.resources.Res
import app.benbow.app.resources.trackers_total
import app.benbow.app.resources.trackers_week
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.GoalPer
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerFigures
import app.benbow.core.tracker.TrackerSpans
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** A column's usual weight across a month down the page: one, or half one an hour of the sleep sheet. */
internal val TrackerColumn.baseWeight: Float get() = sheetHours?.let { it.hours.size * SheetSizes.HOUR_WEIGHT } ?: 1f

/** A column's weight as drawn: its usual one times its own size (owner, ADR-058). */
internal fun TrackerColumn.weight(sizes: ColumnSizes): Float = baseWeight * sizes.width(this)

/**
 * The columns' icons and header texts; it stays while the months swipe under it. A time column names its hours (the
 * sleep sheet). Where the tracker's columns stretch ([ColumnSizes.stretchable]), a border between two columns moves
 * them both, the others staying put, and is written once let go; a double click puts the two back.
 */
@Composable
internal fun SheetHeader(columns: List<TrackerColumn>, figures: Boolean, write: TrackerWrite) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val sizes = LocalSheetLook.current.sizes
    val widths = remember { mutableStateMapOf<String, Int>() }
    val least = with(LocalDensity.current) { SheetSizes.LEAST_COLUMN.toPx() }
    Row(
        Modifier.fillMaxWidth().height(SheetSizes.HEADER_HEIGHT).padding(horizontal = SheetSizes.SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(SheetSizes.DAY_WIDTH))
        columns.forEachIndexed { i, column ->
            val icon = column.record[ColumnField.ICON]?.let(::iconEmoji)
            val window = column.sheetHours
            val next = columns.getOrNull(i + 1)
            Box(Modifier.weight(column.weight(sizes)).fillMaxHeight().onSizeChanged { widths[column.id] = it.width }) {
                if (window != null) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                listOfNotNull(icon, column.name).joinToString(" "),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            // A whole day on a phone leaves no room for every number: then every third (0, 3, 6, …).
                            val every = if (maxWidth / window.hours.size < SheetSizes.HOUR_LABEL) 3 else 1
                            Row(Modifier.fillMaxWidth()) {
                                window.hours.forEach { hour ->
                                    Text(
                                        if (hour % every == 0) "$hour" else "",
                                        modifier = Modifier.weight(1f),
                                        color = faint,
                                        fontSize = SheetSizes.HOUR_NUMBER,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(icon ?: column.short.take(1), fontSize = 20.sp, maxLines = 1)
                        Text(
                            column.short,
                            color = faint,
                            fontSize = SheetSizes.SHORT,
                            lineHeight = 12.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (sizes.stretchable && next != null) {
                    StretchBorder(
                        vertical = true,
                        tag = "stretch:${column.id}",
                        onDrag = { dx ->
                            val (a, b) = resizePair(
                                sizes.width(column),
                                column.baseWeight,
                                widths[column.id]?.toFloat() ?: 0f,
                                sizes.width(next),
                                next.baseWeight,
                                widths[next.id]?.toFloat() ?: 0f,
                                dx,
                                least,
                            )
                            sizes.setWidth(column.id, a)
                            sizes.setWidth(next.id, b)
                        },
                        onStop = {
                            val (a, b) = sizes.width(column) to sizes.width(next)
                            write {
                                together {
                                    updateColumn(column.id, mapOf(ColumnField.WIDTH to factorText(a)))
                                    updateColumn(next.id, mapOf(ColumnField.WIDTH to factorText(b)))
                                }
                            }
                        },
                        onReset = {
                            sizes.setWidth(column.id, 1f)
                            sizes.setWidth(next.id, 1f)
                            write {
                                together {
                                    updateColumn(column.id, mapOf(ColumnField.WIDTH to null))
                                    updateColumn(next.id, mapOf(ColumnField.WIDTH to null))
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            if (window != null && figures) Box(Modifier.weight(SheetSizes.FIGURE_WEIGHT))
        }
    }
}

/**
 * A day's line: its number and weekday, then a cell per column; days to come are faint and wait. A line ends each week
 * (with the grid, its heavier line where the style has one). On the sleep sheet the hours are a strip ([HourStrip]).
 */
@Composable
internal fun DayRow(
    columns: List<TrackerColumn>,
    day: LocalDate,
    weekday: String,
    today: LocalDate,
    figures: Boolean,
    cells: SheetCells,
    spans: SheetSpans,
    write: TrackerWrite,
    onSpans: (TrackerColumn) -> Unit,
    onAsk: (TrackerColumn) -> Unit,
) {
    val future = day > today
    val date = "$day"
    val sizes = LocalSheetLook.current.sizes
    val grid = sheetGrid()
    val sunday = day.dayOfWeek == DayOfWeek.SUNDAY
    Column {
        Row(
            Modifier.fillMaxWidth().height(SheetSizes.ROW_HEIGHT)
                .then(
                    if (day ==
                        today
                    ) {
                        Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = SheetSizes.TODAY_ALPHA))
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = SheetSizes.SIDE)
                .alpha(if (future) SheetSizes.FUTURE_ALPHA else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DayLabel(day, weekday, day == today, Modifier.fillMaxHeight().gridCell(grid, strongRight = true, strongBottom = sunday))
            columns.forEach { column ->
                val own = spans[column.id].orEmpty()
                val window = column.sheetHours
                if (window != null) {
                    HourStrip(
                        column,
                        day,
                        window,
                        own,
                        enabled = !future,
                        write = write,
                        onSpans = { onSpans(column) },
                        modifier = Modifier.weight(column.weight(sizes)).fillMaxHeight(),
                    )
                    if (figures) {
                        FigureSlot(
                            TrackerSpans.total(own, date, column.dayBy).takeIf { it > 0 }?.let(::clockText).orEmpty(),
                            Modifier.gridCell(grid, strongBottom = sunday),
                        )
                    }
                } else {
                    DayCell(
                        column,
                        date,
                        cells[TrackerCells.id(column.id, date)],
                        own,
                        figures,
                        enabled = !future,
                        modifier = Modifier.weight(column.weight(sizes)),
                        write = write,
                        onSpans = { onSpans(column) },
                        onAsk = { onAsk(column) },
                        strongBottom = sunday,
                    )
                }
            }
        }
        if (sunday && grid == null) {
            HorizontalDivider(Modifier.padding(horizontal = SheetSizes.SIDE), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** A sleep column's day length beside its hours. */
@Composable
private fun RowScope.FigureSlot(text: String, modifier: Modifier) {
    Box(Modifier.weight(SheetSizes.FIGURE_WEIGHT).fillMaxHeight().then(modifier), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun DayLabel(day: LocalDate, weekday: String, today: Boolean, modifier: Modifier = Modifier) {
    val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
    Column(
        modifier.width(SheetSizes.DAY_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "${day.day}",
            fontWeight = if (today) FontWeight.Bold else FontWeight.Medium,
            fontSize = SheetSizes.DAY_NUMBER,
            lineHeight = 19.sp,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            weekday,
            color = if (weekend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = SheetSizes.WEEKDAY,
            lineHeight = 12.sp,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * A week's figures under its Sunday (or the month's last day), quieter than the month's: its days in this month so
 * far. A goal for the week, once reached, fills its figure with the column's ink; nothing marks it missed.
 */
@Composable
internal fun WeekRow(columns: List<TrackerColumn>, days: List<LocalDate>, cells: SheetCells, spans: SheetSpans) {
    val dates = remember(days) { days.map { "$it" } }
    val colors = MaterialTheme.colorScheme
    val sizes = LocalSheetLook.current.sizes
    Row(
        Modifier.fillMaxWidth().height(SheetSizes.WEEK_HEIGHT).padding(horizontal = SheetSizes.SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.trackers_week),
            modifier = Modifier.width(SheetSizes.DAY_WIDTH),
            color = colors.onSurfaceVariant,
            fontSize = SheetSizes.SMALL_FIGURE,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        columns.forEach { column ->
            if (column.sheetHours != null) Box(Modifier.weight(column.weight(sizes)))
            val own = spans[column.id].orEmpty()
            val byDay = dates.mapNotNull { day -> cells[TrackerCells.id(column.id, day)]?.let { day to it } }.toMap()
            val reached =
                column.goalPer == GoalPer.Week && dates.lastOrNull()?.let { TrackerFigures.reached(column, byDay, own, it) } == true
            val ink = columnInk(column)
            Text(
                figureText(column, TrackerFigures.of(column, byDay, own, dates)),
                modifier = Modifier.weight(if (column.sheetHours != null) SheetSizes.FIGURE_WEIGHT else column.weight(sizes)).then(
                    if (reached) Modifier.padding(horizontal = 4.dp).background(ink.copy(alpha = SheetSizes.PART_ALPHA)) else Modifier,
                ),
                color = colors.onSurfaceVariant,
                fontSize = SheetSizes.SMALL_FIGURE,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** The month's figures so far: they only count up (ux.md "Psychology"), and the eye hides them. */
@Composable
internal fun TotalsRow(columns: List<TrackerColumn>, days: List<LocalDate>, cells: SheetCells, spans: SheetSpans) {
    val dates = remember(days) { days.map { "$it" } }
    val sizes = LocalSheetLook.current.sizes
    Row(
        Modifier.fillMaxWidth().height(SheetSizes.TOTALS_HEIGHT)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = SheetSizes.FOOT_ALPHA))
            .padding(horizontal = SheetSizes.SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.trackers_total),
            modifier = Modifier.width(SheetSizes.DAY_WIDTH),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
        )
        columns.forEach { column ->
            if (column.sheetHours != null) Box(Modifier.weight(column.weight(sizes)))
            val byDay = dates.mapNotNull { day -> cells[TrackerCells.id(column.id, day)]?.let { day to it } }.toMap()
            Text(
                figureText(column, TrackerFigures.of(column, byDay, spans[column.id].orEmpty(), dates)),
                modifier = Modifier.weight(if (column.sheetHours != null) SheetSizes.FIGURE_WEIGHT else column.weight(sizes)),
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
