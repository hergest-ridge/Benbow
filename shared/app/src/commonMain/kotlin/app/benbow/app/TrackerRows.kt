package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benbow.app.resources.Res
import app.benbow.app.resources.trackers_total
import app.benbow.app.resources.trackers_week
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.GoalPer
import app.benbow.core.tracker.HourWindow
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerFigures
import app.benbow.core.tracker.TrackerRecord
import app.benbow.core.tracker.TrackerSpans
import app.benbow.ui.theme.BenbowTheme
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * The columns' icons and header texts; it stays while the months swipe under it. A time column names its hours (the
 * sleep sheet) and has its timer: ▶ starts it, ■ stops it, its time ticking meanwhile.
 */
@Composable
internal fun SheetHeader(columns: List<TrackerColumn>, figures: Boolean, write: TrackerWrite) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().height(SheetSizes.HEADER_HEIGHT).padding(horizontal = SheetSizes.SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(SheetSizes.DAY_WIDTH))
        columns.forEach { column ->
            val icon = column.record[ColumnField.ICON]?.let(::iconEmoji)
            val window = column.sheetHours
            if (window != null) {
                Column(Modifier.weight(window.hours.size * SheetSizes.HOUR_WEIGHT), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(listOfNotNull(icon, column.name).joinToString(" "), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        TimerButton(column, write)
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // A whole day on a phone leaves no room for every number: then every third (0, 3, 6, …).
                        val every = if (maxWidth / window.hours.size < SheetSizes.HOUR_LABEL) 3 else 1
                        Row(Modifier.fillMaxWidth()) {
                            window.hours.forEach { hour ->
                                Text(
                                    if (hour % every == 0) "$hour" else "",
                                    modifier = Modifier.weight(1f),
                                    color = faint,
                                    fontSize = 9.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                if (figures) Box(Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(icon ?: column.short.take(1), fontSize = 18.sp, maxLines = 1)
                    if (column.type == ColumnType.Time) {
                        TimerButton(column, write)
                    } else {
                        Text(
                            column.short,
                            color = faint,
                            fontSize = 10.sp,
                            lineHeight = 11.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A day's line: its number and weekday, then a cell per column; days to come are faint and wait. A line ends each week.
 * On the sleep sheet a tap paints or clears an hour, a drag along the row paints the hours it passes (or clears them,
 * when it started on a painted one; [hourGestures]), and a long press alone opens the day's spans ([onSpans]).
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
            DayLabel(day, weekday, day == today)
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
                        modifier = Modifier.weight(window.hours.size * SheetSizes.HOUR_WEIGHT).fillMaxHeight(),
                    )
                    if (figures) {
                        Text(
                            TrackerSpans.total(own, date, column.dayBy).takeIf { it > 0 }?.let(::clockText).orEmpty(),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
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
                        modifier = Modifier.weight(1f),
                        write = write,
                        onSpans = { onSpans(column) },
                        onAsk = { onAsk(column) },
                    )
                }
            }
        }
        if (day.dayOfWeek == DayOfWeek.SUNDAY) {
            HorizontalDivider(Modifier.padding(horizontal = SheetSizes.SIDE), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * A day's hours of a sleep column, along a row ([DayRow]) or down a day's column when [down] (a month across): a tap
 * paints or clears one, a drag the hours it passes ([hourGestures], [dragEdits]), a long press alone opens the day's
 * spans ([onSpans]).
 */
@Composable
internal fun HourStrip(
    column: TrackerColumn,
    day: LocalDate,
    window: HourWindow,
    own: List<TrackerRecord>,
    enabled: Boolean,
    write: TrackerWrite,
    onSpans: () -> Unit,
    modifier: Modifier,
    down: Boolean = false,
) {
    val date = "$day"
    val haptic = LocalHapticFeedback.current
    val starts = hourStarts(window, day, column.dayBy)
    val painted = starts.map { covered(own, it) }
    var preview by remember(column.id, date) { mutableStateOf<IntRange?>(null) }
    val tapHour = { i: Int ->
        val edits = hourTap(own, starts[i])
        write { together { edits.forEach { editSpan(column.id, it) } } }
    }
    val gestures = if (!enabled) {
        Modifier
    } else {
        Modifier.hand().hourGestures(
            starts.size,
            own,
            down,
            onTap = tapHour,
            onHold = { onSpans() },
            onDrag = { range ->
                if (preview == null && range != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                preview = range
            },
            onDrop = { from, to ->
                val edits = dragEdits(own, starts, from, to)
                write { together { edits.forEach { editSpan(column.id, it) } } }
            },
        )
    }
    val cell = @Composable { i: Int, weight: Modifier ->
        HourCell(
            column,
            painted = painted[i],
            first = i == 0 || !painted[i - 1],
            last = i == starts.lastIndex || !painted[i + 1],
            previewed = preview?.contains(i) == true,
            enabled = enabled,
            tag = "${TrackerCells.id(column.id, date)}:$i",
            modifier = weight,
            vertical = down,
        ) { tapHour(i) }
    }
    if (down) {
        Column(modifier.then(gestures)) { starts.indices.forEach { cell(it, Modifier.weight(1f)) } }
    } else {
        Row(modifier.then(gestures)) { starts.indices.forEach { cell(it, Modifier.weight(1f)) } }
    }
}

/**
 * Taps and drags on a day's hours ([count] of them along the row, or down when [down]; gestures restarting when [key]
 * changes): a tap on one ([onTap]); a drag over them ([onDrag] while it goes, null once it ends; [onDrop] with where it
 * started and ended), which a mouse starts by pressing and moving (owner, ADR-051) and a finger after a long press, so a
 * finger still scrolls the sheet; a long press with no drag, by either, opens the day's spans ([onHold]).
 */
private fun Modifier.hourGestures(
    count: Int,
    key: Any,
    down: Boolean = false,
    onTap: (Int) -> Unit,
    onHold: (Int) -> Unit,
    onDrag: (IntRange?) -> Unit,
    onDrop: (Int, Int) -> Unit,
): Modifier = pointerInput(count, key, down) {
    detectTapGestures(onTap = { onTap(hourAt(if (down) it.y else it.x, if (down) size.height else size.width, count)) })
}.pointerInput(count, key, down) {
    // After the taps' detector in the chain, so it sees each event first and takes what it drags (no tap after a drag).
    val at = { p: Offset -> hourAt(if (down) p.y else p.x, if (down) size.height else size.width, count) }
    awaitEachGesture {
        val press = awaitFirstDown(requireUnconsumed = false)
        var clicked = false
        val start = if (press.type == PointerType.Mouse) {
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                awaitTouchSlopOrCancellation(press.id) { change, _ -> change.consume() }.also { clicked = it == null }
            }
        } else {
            awaitLongPressOrCancellation(press.id).also { clicked = it == null }
        }
        if (clicked) return@awaitEachGesture // a tap, or a finger scrolling the sheet
        // A mouse that moved paints at once; one held still, or a finger's long press, may still be only a hold.
        val held = start == null || press.type != PointerType.Mouse
        val from = at(press.position)
        var to = at((start ?: press).position)
        var moved = !held
        onDrag(minOf(from, to)..maxOf(from, to))
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == press.id }
            if (change == null) {
                onDrag(null)
                return@awaitEachGesture
            }
            change.consume()
            if (!change.pressed) break
            val now = at(change.position)
            if (now != to) {
                to = now
                moved = true
                onDrag(minOf(from, to)..maxOf(from, to))
            }
        }
        onDrag(null)
        if (moved) onDrop(from, to) else onHold(from)
    }
}

@Composable
internal fun DayLabel(day: LocalDate, weekday: String, today: Boolean) {
    val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
    Column(Modifier.width(SheetSizes.DAY_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${day.day}",
            fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 16.sp,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            weekday,
            color = if (weekend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 11.sp,
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
    val dark = BenbowTheme.colors.dark
    Row(
        Modifier.fillMaxWidth().height(SheetSizes.WEEK_HEIGHT).padding(horizontal = SheetSizes.SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.trackers_week),
            modifier = Modifier.width(SheetSizes.DAY_WIDTH),
            color = colors.onSurfaceVariant,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        columns.forEach { column ->
            column.sheetHours?.let { Box(Modifier.weight(it.hours.size * SheetSizes.HOUR_WEIGHT)) }
            val own = spans[column.id].orEmpty()
            val byDay = dates.mapNotNull { day -> cells[TrackerCells.id(column.id, day)]?.let { day to it } }.toMap()
            val reached =
                column.goalPer == GoalPer.Week && dates.lastOrNull()?.let { TrackerFigures.reached(column, byDay, own, it) } == true
            val ink = column.record[ColumnField.COLOR]?.let { BenbowTheme.colors.tokens.mark(it, dark) } ?: colors.primary
            Text(
                figureText(column, TrackerFigures.of(column, byDay, own, dates)),
                modifier = Modifier.weight(1f).then(
                    if (reached) Modifier.padding(horizontal = 4.dp).background(ink.copy(alpha = SheetSizes.PART_ALPHA)) else Modifier,
                ),
                color = colors.onSurfaceVariant,
                fontSize = 10.sp,
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
            style = MaterialTheme.typography.labelSmall,
        )
        columns.forEach { column ->
            column.sheetHours?.let { Box(Modifier.weight(it.hours.size * SheetSizes.HOUR_WEIGHT)) }
            val byDay = dates.mapNotNull { day -> cells[TrackerCells.id(column.id, day)]?.let { day to it } }.toMap()
            Text(
                figureText(column, TrackerFigures.of(column, byDay, spans[column.id].orEmpty(), dates)),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
