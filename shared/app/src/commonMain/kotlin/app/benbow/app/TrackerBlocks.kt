package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.home_tracker_pick
import app.benbow.app.resources.tab_trackers
import app.benbow.app.resources.trackers_empty
import app.benbow.app.resources.untitled
import app.benbow.client.BlockSize
import app.benbow.core.tracker.CellField
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerField
import app.benbow.core.tracker.TrackerRecord
import app.benbow.core.tracker.TrackerSpans
import app.benbow.ui.theme.BenbowPalette
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringResource

// Trackers outside their screen (ADR-050, B7): Home's two blocks and the calendar's quiet line. No lazy lists here: a
// row of Home blocks is measured ahead by its content (HomeBlocks.kt).

/** The records a block shows: a tracker's live columns and their cells and spans over some days. */
private class TrackerDays(
    val columns: List<TrackerColumn>,
    val cells: Map<String, Map<String, String?>>,
    val spans: Map<String, List<TrackerRecord>>,
) {
    fun seconds(column: TrackerColumn, day: String): Long = TrackerSpans.total(spans[column.id].orEmpty(), day, column.dayBy)
}

/** [trackerId]'s columns outside the archive, with their cells and spans on [days]. */
@Composable
private fun trackerDays(services: AppServices, trackerId: String, days: List<LocalDate>): TrackerDays {
    val all by remember(trackerId) { services.trackers.columns(trackerId) }.collectAsStateWithLifecycle(emptyList())
    val columns = remember(all) { all.filter { !it.archived } }
    val ids = remember(columns) { columns.map { it.id } }
    val timed = remember(columns) { columns.filter { it.type == ColumnType.Time }.map { it.id } }
    val cells by remember(ids, days.first(), days.last()) {
        services.trackers.cells(ids, "${days.first()}", "${days.last()}")
    }.collectAsStateWithLifecycle(emptyList())
    val spans by remember(timed, days.first(), days.last()) {
        if (timed.isEmpty()) {
            flowOf(emptyList<TrackerRecord>())
        } else {
            services.trackers.spans(timed, "${days.first().minus(1, DateTimeUnit.DAY)}", "${days.last().plus(1, DateTimeUnit.DAY)}")
        }
    }.collectAsStateWithLifecycle(emptyList())
    return remember(columns, cells, spans) {
        TrackerDays(columns, cells.associate { it.id to it.fields }, spans.groupBy { it[SpanField.COLUMN].orEmpty() })
    }
}

/**
 * «Трекеры» on Home: the way to them. M lists each tracker with how many of its columns are marked today (a count that
 * only goes up), S its trackers' emoji. A tracker opens its sheet, the rest of the block the trackers.
 */
@Composable
internal fun TrackersBlock(services: AppServices, size: BlockSize, onTrackers: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier) {
    val trackers by remember { services.trackers.trackers() }.collectAsStateWithLifecycle(emptyList())
    val shown = trackers.filter { !it.archived }
    val untitled = stringResource(Res.string.untitled)
    BlockCard(stringResource(Res.string.tab_trackers), onTrackers, modifier) {
        when {
            shown.isEmpty() -> Text(
                stringResource(Res.string.trackers_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            size == BlockSize.S -> Text(
                shown.joinToString(" ") {
                    it[TrackerField.ICON]?.let(::iconEmoji) ?: "•"
                },
                fontSize = 22.sp,
                maxLines = 1,
            )

            else -> shown.take(TRACKERS_ON_HOME).forEach { tracker ->
                key(tracker.id) { TodayLine(services, tracker, untitled) { onOpen(tracker.id) } }
            }
        }
    }
}

/** A tracker's line in «Трекеры»: its title and today's marks. */
@Composable
private fun TodayLine(services: AppServices, tracker: TrackerRecord, untitled: String, onOpen: () -> Unit) {
    val today = remember { LocalDate.parse(services.today()) }
    val days = trackerDays(services, tracker.id, listOf(today))
    val figures = tracker[TrackerField.FIGURES] != FIGURES_HIDDEN
    val marks = days.columns.mapNotNull { markText(it, days.cells[TrackerCells.id(it.id, "$today")], days.seconds(it, "$today"), figures) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).hand().clickable(onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(trackerTitle(tracker, untitled), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            marks.joinToString(" "),
            Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One tracker on Home (a block's `tracker`): today's marks to tick (S: a check flips, a count adds one, the rest open
 * the sheet), its week (M) or its month (L) as small squares in its columns' inks. Gone (in the trash): nothing.
 */
@Composable
internal fun TrackerBlock(services: AppServices, trackerId: String, size: BlockSize, onOpen: (String) -> Unit, modifier: Modifier) {
    val trackers by remember { services.trackers.trackers() }.collectAsStateWithLifecycle(emptyList())
    val tracker = trackers.firstOrNull { it.id == trackerId } ?: return
    val today = remember { LocalDate.parse(services.today()) }
    val days = remember(size, today) {
        when (size) {
            BlockSize.S -> listOf(today)
            BlockSize.M -> weekOfPage(today, WEEK_ORIGIN)
            else -> monthDays(today)
        }
    }
    val records = trackerDays(services, trackerId, days)
    val figures = tracker[TrackerField.FIGURES] != FIGURES_HIDDEN
    BlockCard(trackerTitle(tracker, stringResource(Res.string.untitled)), { onOpen(trackerId) }, modifier) {
        if (size == BlockSize.S) {
            TodayMarks(services, records, today, figures) { onOpen(trackerId) }
        } else {
            MiniSheet(records, days, today, if (size == BlockSize.M) WEEK_SQUARE else MONTH_SQUARE)
        }
    }
}

/** Today's cells in a row (up to five): a check flips and a count adds one right here; others open the sheet. */
@Composable
private fun TodayMarks(services: AppServices, records: TrackerDays, today: LocalDate, figures: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val day = "$today"
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        records.columns.take(TODAY_MARKS).forEach { column ->
            val fields = records.cells[TrackerCells.id(column.id, day)]
            val fill = pixelFill(column, fields, records.seconds(column, day))
            val ink = column.record[ColumnField.COLOR]?.let { BenbowPalette.text(it, dark) } ?: colors.primary
            val icon = column.record[ColumnField.ICON]?.let(::iconEmoji) ?: column.short.take(1)
            Box(
                Modifier.weight(1f).height(TODAY_HEIGHT).clip(RoundedCornerShape(10.dp))
                    .background(
                        if (fill >
                            0f
                        ) {
                            ink.copy(alpha = SheetSizes.PART_ALPHA + (SheetSizes.MARK_ALPHA - SheetSizes.PART_ALPHA) * fill)
                        } else {
                            Color.Transparent
                        },
                    )
                    .border(1.dp, colors.outline.copy(alpha = SheetSizes.FRAME_ALPHA), RoundedCornerShape(10.dp))
                    .hand()
                    .clickable {
                        when (column.type) {
                            ColumnType.Check -> services.trackerWrites {
                                setCell(column.id, day, if (fields?.get(CellField.VALUE) == "true") null else "true")
                            }

                            ColumnType.Count -> services.trackerWrites { addToCount(column.id, day) }

                            else -> onOpen()
                        }
                    }
                    .testTag("home:" + TrackerCells.id(column.id, day)),
                contentAlignment = Alignment.Center,
            ) {
                Text(markText(column, fields, records.seconds(column, day), figures) ?: icon, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

/** A few days of a tracker in small squares: a row per column (up to six), its emoji first; days to come are faint. */
@Composable
private fun MiniSheet(records: TrackerDays, days: List<LocalDate>, today: LocalDate, square: Dp) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        records.columns.take(MINI_ROWS).forEach { column ->
            val ink = column.record[ColumnField.COLOR]?.let { BenbowPalette.text(it, dark) } ?: colors.primary
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    column.record[ColumnField.ICON]?.let(::iconEmoji) ?: column.short.take(1),
                    Modifier.width(22.dp),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                days.forEach { day ->
                    val date = "$day"
                    val fill = pixelFill(column, records.cells[TrackerCells.id(column.id, date)], records.seconds(column, date))
                    Box(
                        Modifier.weight(1f).height(square).alpha(if (day > today) SheetSizes.FUTURE_ALPHA else 1f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (fill > 0f) {
                                    ink.copy(alpha = SheetSizes.PART_ALPHA + (SheetSizes.MARK_ALPHA - SheetSizes.PART_ALPHA) * fill)
                                } else {
                                    colors.outline.copy(alpha = SheetSizes.FRAME_ALPHA)
                                },
                            ),
                    )
                }
            }
        }
    }
}

/** A Home block's card: its title (with a way on) over its content, the card opening [onOpen]. */
@Composable
private fun BlockCard(title: String, onOpen: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).hand(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

/**
 * The trackers shown in the calendar (a tracker's checkbox in the calendars' sheet, off by default): a quiet line each
 * under the day's title, its marks of that day; a tap opens the tracker.
 */
@Composable
internal fun CalendarTrackers(services: AppServices, day: LocalDate, onOpen: (String) -> Unit) {
    val trackers by remember { services.trackers.trackers() }.collectAsStateWithLifecycle(emptyList())
    val untitled = stringResource(Res.string.untitled)
    Column {
        trackers.filter { it[TrackerField.CALENDAR] == "true" && !it.archived }.forEach { tracker ->
            key(tracker.id) { CalendarLine(services, tracker, day, untitled, onOpen) }
        }
    }
}

/** A tracker's line in the calendar's day. */
@Composable
private fun CalendarLine(services: AppServices, tracker: TrackerRecord, day: LocalDate, untitled: String, onOpen: (String) -> Unit) {
    val records = trackerDays(services, tracker.id, listOf(day))
    val figures = tracker[TrackerField.FIGURES] != FIGURES_HIDDEN
    val date = "$day"
    val marks = records.columns.mapNotNull { markText(it, records.cells[TrackerCells.id(it.id, date)], records.seconds(it, date), figures) }
    Row(
        Modifier.fillMaxWidth().hand().clickable { onOpen(tracker.id) }.padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            trackerTitle(tracker, untitled),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(marks.joinToString("  "), Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Which tracker a Home block shows (its emoji and name by id). */
@Composable
internal fun TrackerPickDialog(titles: Map<String, String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.home_tracker_pick)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                titles.forEach { (id, title) ->
                    Text(
                        title,
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).hand().clickable {
                            onPick(id)
                        }.padding(horizontal = 8.dp, vertical = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** How many trackers «Трекеры» lists, columns S ticks, columns M and L draw. */
private const val TRACKERS_ON_HOME = 5
private const val TODAY_MARKS = 5
private const val MINI_ROWS = 6
private val TODAY_HEIGHT = 44.dp
private val WEEK_SQUARE = 14.dp
private val MONTH_SQUARE = 10.dp
