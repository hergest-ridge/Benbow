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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benbow.app.resources.Res
import app.benbow.app.resources.trackers_total
import app.benbow.app.resources.trackers_week
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.GoalPer
import app.benbow.core.tracker.HourWindow
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
 * them both, the others staying put, and is written once let go; a double click puts the two back. With a drawer (a
 * sleep sheet on a phone, ADR-059) the hours come first and the rest slides out from the right.
 */
@Composable
internal fun SheetHeader(columns: List<TrackerColumn>, figures: Boolean, write: TrackerWrite) {
    val look = LocalSheetLook.current
    val sizes = look.sizes
    val drawer = look.drawer
    val widths = remember { mutableStateMapOf<String, Int>() }
    val least = with(LocalDensity.current) { SheetSizes.LEAST_COLUMN.toPx() }
    Row(
        Modifier.fillMaxWidth().height(SheetSizes.HEADER_HEIGHT).padding(horizontal = sheetSide()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(SheetSizes.DAY_WIDTH))
        if (drawer != null) {
            val (hours, others) = columns.partition { it.sheetHours != null }
            hours.forEach { HoursHeader(it, it.sheetHours!!, Modifier.weight(it.baseWeight).fillMaxHeight()) }
            DrawerPart(drawer, drawerWidth(hours, others, figures)) {
                if (figures) hours.forEach { Box(Modifier.width(SheetSizes.DRAWER_FIGURE)) }
                others.forEach { ColumnHeader(it, Modifier.width(SheetSizes.DRAWER_COLUMN).fillMaxHeight()) }
            }
            DrawerGrip(drawer, header = true)
            return@Row
        }
        columns.forEachIndexed { i, column ->
            val window = column.sheetHours
            val next = columns.getOrNull(i + 1)
            Box(Modifier.weight(column.weight(sizes)).fillMaxHeight().onSizeChanged { widths[column.id] = it.width }) {
                if (window != null) HoursHeader(column, window, Modifier.fillMaxSize()) else ColumnHeader(column, Modifier.fillMaxSize())
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
 * A sleep column's header: its emoji and name over its hours' numbers, each centred on its hour and free to run over
 * its neighbours (so «12» never shows as «1»); a whole day on a phone numbers every third.
 */
@Composable
private fun HoursHeader(column: TrackerColumn, window: HourWindow, modifier: Modifier) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val icon = column.record[ColumnField.ICON]?.let(::iconEmoji)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(listOfNotNull(icon, column.name).joinToString(" "), maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            val every = if (maxWidth / window.hours.size < SheetSizes.HOUR_LABEL) 3 else 1
            Row(Modifier.fillMaxWidth()) {
                window.hours.forEach { hour ->
                    Text(
                        if (hour % every == 0) "$hour" else "",
                        modifier = Modifier.weight(1f).wrapContentWidth(unbounded = true),
                        color = faint,
                        fontSize = SheetSizes.HOUR_NUMBER,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** A column's emoji and its short header text. */
@Composable
private fun ColumnHeader(column: TrackerColumn, modifier: Modifier) {
    val icon = column.record[ColumnField.ICON]?.let(::iconEmoji)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(icon ?: column.short.take(1), fontSize = 20.sp, maxLines = 1)
        Text(
            column.short,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = SheetSizes.SHORT,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** How wide the drawer's part of a row is: a day's length per sleep column (with the figures), a cell per other column. */
internal fun drawerWidth(hours: List<TrackerColumn>, others: List<TrackerColumn>, figures: Boolean): Dp =
    (if (figures) SheetSizes.DRAWER_FIGURE * hours.size else 0.dp) + SheetSizes.DRAWER_COLUMN * others.size

/** The row's sides: none edge to edge with a drawer (owner: every dp to the hours), else a little. */
@Composable
internal fun sheetSide(): Dp = if (LocalSheetLook.current.drawer != null) 0.dp else SheetSizes.SIDE

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
    val look = LocalSheetLook.current
    val sizes = look.sizes
    val drawer = look.drawer
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
                .padding(horizontal = sheetSide())
                .alpha(if (future) SheetSizes.FUTURE_ALPHA else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DayLabel(day, weekday, day == today, Modifier.fillMaxHeight().gridCell(grid, strongRight = true, strongBottom = sunday))
            if (drawer != null) {
                // The hours take the row; a day's length and the other columns slide out from the right.
                val (hours, others) = columns.partition { it.sheetHours != null }
                hours.forEach { column ->
                    HourStrip(
                        column,
                        day,
                        column.sheetHours!!,
                        spans[column.id].orEmpty(),
                        enabled = !future,
                        write = write,
                        onSpans = { onSpans(column) },
                        modifier = Modifier.weight(column.baseWeight).fillMaxHeight(),
                    )
                }
                DrawerPart(drawer, drawerWidth(hours, others, figures)) {
                    if (figures) {
                        hours.forEach { column ->
                            val total = TrackerSpans.total(spans[column.id].orEmpty(), date, column.dayBy)
                            Box(
                                Modifier.width(SheetSizes.DRAWER_FIGURE).fillMaxHeight().gridCell(grid, strongBottom = sunday),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    total.takeIf {
                                        it > 0
                                    }?.let(::clockText).orEmpty(),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                    others.forEach { column ->
                        DayCell(
                            column,
                            date,
                            cells[TrackerCells.id(column.id, date)],
                            spans[column.id].orEmpty(),
                            figures,
                            enabled = !future,
                            modifier = Modifier.width(SheetSizes.DRAWER_COLUMN),
                            write = write,
                            onSpans = { onSpans(column) },
                            onAsk = { onAsk(column) },
                            strongBottom = sunday,
                        )
                    }
                }
                DrawerGrip(drawer)
                return@Row
            }
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
            HorizontalDivider(Modifier.padding(horizontal = sheetSide()), color = MaterialTheme.colorScheme.outlineVariant)
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
    val look = LocalSheetLook.current
    val sizes = look.sizes
    Row(
        Modifier.fillMaxWidth().height(SheetSizes.WEEK_HEIGHT).padding(horizontal = sheetSide()),
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
        val figure = { column: TrackerColumn ->
            val byDay = dates.mapNotNull { day -> cells[TrackerCells.id(column.id, day)]?.let { day to it } }.toMap()
            figureText(column, TrackerFigures.of(column, byDay, spans[column.id].orEmpty(), dates))
        }
        look.drawer?.let { drawer ->
            DrawerFigures(drawer, columns, figure, SheetSizes.SMALL_FIGURE, colors.onSurfaceVariant)
            return@Row
        }
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
    val look = LocalSheetLook.current
    val sizes = look.sizes
    Row(
        Modifier.fillMaxWidth().height(SheetSizes.TOTALS_HEIGHT)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = SheetSizes.FOOT_ALPHA))
            .padding(horizontal = sheetSide()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.trackers_total),
            modifier = Modifier.width(SheetSizes.DAY_WIDTH),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
        )
        look.drawer?.let { drawer ->
            val figure = { column: TrackerColumn ->
                val byDay = dates.mapNotNull { day -> cells[TrackerCells.id(column.id, day)]?.let { day to it } }.toMap()
                figureText(column, TrackerFigures.of(column, byDay, spans[column.id].orEmpty(), dates))
            }
            DrawerFigures(drawer, columns, figure, SheetSizes.FIGURE_TEXT, MaterialTheme.colorScheme.onSurface)
            return@Row
        }
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

/** A week's or a month's figures with a drawer: under the hours nothing, in the drawer each column's figure. */
@Composable
private fun RowScope.DrawerFigures(
    drawer: SideDrawer,
    columns: List<TrackerColumn>,
    figure: (TrackerColumn) -> String,
    size: TextUnit,
    color: Color,
) {
    val (hours, others) = columns.partition { it.sheetHours != null }
    hours.forEach { Box(Modifier.weight(it.baseWeight)) }
    DrawerPart(drawer, drawerWidth(hours, others, figures = true)) {
        hours.forEach {
            Text(
                figure(it),
                Modifier.width(SheetSizes.DRAWER_FIGURE),
                color = color,
                fontSize = size,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        others.forEach {
            Text(
                figure(it),
                Modifier.width(SheetSizes.DRAWER_COLUMN),
                color = color,
                fontSize = size,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
    Box(Modifier.width(SheetSizes.GRIP))
}
