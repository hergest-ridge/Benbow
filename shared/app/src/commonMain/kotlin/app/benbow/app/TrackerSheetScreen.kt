package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.menu
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.next
import app.benbow.app.resources.previous
import app.benbow.app.resources.save
import app.benbow.app.resources.today
import app.benbow.app.resources.trackers_cell_count
import app.benbow.app.resources.trackers_cell_note
import app.benbow.app.resources.trackers_cell_text
import app.benbow.app.resources.trackers_clear
import app.benbow.app.resources.trackers_figures_hide
import app.benbow.app.resources.trackers_figures_show
import app.benbow.app.resources.trackers_look
import app.benbow.app.resources.trackers_total
import app.benbow.app.resources.untitled
import app.benbow.app.resources.weekdays_short
import app.benbow.client.LookFields
import app.benbow.client.tracker.TrackerRepository
import app.benbow.core.tracker.CellField
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerField
import app.benbow.core.tracker.TrackerFigures
import app.benbow.core.tracker.TrackerRecord
import app.benbow.core.tracker.TrackerSpans
import app.benbow.ui.theme.BenbowPalette
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** A day's cells of a month by their id (`<column>:<day>`), and a month's spans by their column. */
private typealias SheetCells = Map<String, Map<String, String?>>
private typealias SheetSpans = Map<String, List<TrackerRecord>>

/** A write to the trackers, in the order the taps came. */
private typealias TrackerWrite = (TrackerRepository.() -> Unit) -> Unit

/**
 * A tracker's sheet (ADR-050, B4): the month as on paper, days down the page and its columns across, on the records in
 * the calendar's file. Months swipe as in the calendar; a cell answers by its column's type ([tapOf]), a long press opens
 * its exact number and a note; the eye hides the tracker's numbers on every device (`figures`); «Оформление трекера»
 * gives it a note's look, drawn muted under the marks. [back]: a page of its own (narrow), not beside the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackerSheetScreen(services: AppServices, nav: Navigator, trackerId: String, back: Boolean) {
    val trackers by remember { services.trackers.trackers() }.collectAsStateWithLifecycle(null)
    val tracker = trackers?.firstOrNull { it.id == trackerId }
    val allColumns by remember(trackerId) { services.trackers.columns(trackerId) }.collectAsStateWithLifecycle(null)
    val columns = remember(allColumns) { allColumns?.filter { !it.archived } }
    val today = remember { LocalDate.parse(services.today()) }
    val writes = remember { Mutex() }
    val write: TrackerWrite = { action -> services.scope.launch { writes.withLock { services.trackers.action() } } }
    val untitled = stringResource(Res.string.untitled)
    val figures = tracker?.get(TrackerField.FIGURES) != FIGURES_HIDDEN
    var looking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = pageOf(today)) { MONTH_PAGES }
    val scope = rememberCoroutineScope()
    val shownMonth = monthOfPage(pager.currentPage)
    val page = MaterialTheme.colorScheme.surfaceVariant
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { if (back) BackButton(nav::back) },
            title = {
                Column {
                    Text(tracker?.let { trackerTitle(it, untitled) }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        monthTitle(shownMonth.toString().take(7), withYear = true).orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
            actions = {
                if (tracker != null) {
                    if (shownMonth != LocalDate(today.year, today.month, 1)) {
                        IconButton({ scope.launch { pager.animateScrollToPage(pageOf(today)) } }, modifier = Modifier.hand()) {
                            TodayIcon(today.day, stringResource(Res.string.today))
                        }
                    }
                    IconButton({ looking = true }, modifier = Modifier.hand()) {
                        Icon(Icons.Palette, stringResource(Res.string.trackers_look))
                    }
                    IconButton({
                        write { updateTracker(trackerId, mapOf(TrackerField.FIGURES to if (figures) FIGURES_HIDDEN else null)) }
                    }, modifier = Modifier.hand()) {
                        Icon(
                            if (figures) Icons.Eye else Icons.EyeOff,
                            stringResource(if (figures) Res.string.trackers_figures_hide else Res.string.trackers_figures_show),
                        )
                    }
                    // A mouse can't swipe (desktop, web), as in the calendar.
                    if (LocalPagerArrows.current) {
                        IconButton({
                            scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                        }, modifier = Modifier.hand()) { Icon(Icons.ChevronLeft, stringResource(Res.string.previous)) }
                        IconButton({
                            scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        }, modifier = Modifier.hand()) { Icon(Icons.ChevronRight, stringResource(Res.string.next)) }
                    }
                    Box {
                        IconButton({ menu = true }, Modifier.hand()) { Icon(Icons.More, stringResource(Res.string.menu)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(Res.string.move_to_trash)) }, {
                                menu = false
                                write { moveToTrash(trackerId) }
                                services.messages.tryEmit(AppMessage.TrackerTrashed(trackerId, trackerTitle(tracker, untitled)))
                                if (back) nav.back()
                            }, Modifier.hand(), leadingIcon = { Icon(Icons.Delete, null) })
                        }
                    }
                }
            },
        )
    }) { padding ->
        if (tracker != null && columns != null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                // The tracker's own look, as a note's (its color and layers, ADR-047), drawn muted so the marks stay the
                // main thing; as wide as a page of a notebook.
                val base = swatch(tracker[TrackerField.BACKGROUND]) ?: page
                val look = remember(tracker) { LookFields.of(tracker.fields) }
                Column(
                    Modifier.widthIn(max = if (columns.any { it.sheetHours != null }) WIDE_SHEET else SHEET)
                        .fillMaxSize()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(base)
                        .then(entryBackdrop(tracker.id, look, base, muted = true, moving = true)),
                ) {
                    SheetHeader(columns, figures)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HorizontalPager(pager, beyondViewportPageCount = 1, modifier = Modifier.weight(1f).fillMaxWidth()) { at ->
                        MonthPage(services, columns, monthOfPage(at), today, figures, write)
                    }
                }
            }
        }
    }
    if (looking && tracker != null) {
        LookSheet(
            id = tracker.id,
            words = trackerTitle(tracker, untitled) + "\n" + columns.orEmpty().joinToString(" · ") { it.name },
            color = tracker[TrackerField.BACKGROUND],
            look = LookFields.of(tracker.fields),
            page = page,
            onColor = { color -> write { updateTracker(trackerId, mapOf(TrackerField.BACKGROUND to color)) } },
            onLook = { layers -> write { updateTracker(trackerId, trackerLook(layers)) } },
            onDismiss = { looking = false },
            title = Res.string.trackers_look,
        )
    }
}

/** The columns' icons and header texts; it stays while the months swipe under it. A time column names its hours. */
@Composable
private fun SheetHeader(columns: List<TrackerColumn>, figures: Boolean) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(horizontal = SIDE), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(DAY_WIDTH))
        columns.forEach { column ->
            val icon = column.record[ColumnField.ICON]?.let(::iconEmoji)
            val window = column.sheetHours
            if (window != null) {
                Column(Modifier.weight(window.hours.size * HOUR_WEIGHT), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(listOfNotNull(icon, column.name).joinToString(" "), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth()) {
                        window.hours.forEach { hour ->
                            Text(
                                "$hour",
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
                if (figures) Box(Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(icon ?: column.short.take(1), fontSize = 18.sp, maxLines = 1)
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

/** One month of the sheet: its days down the page (today a few rows from the top), then its figures. */
@Composable
private fun MonthPage(
    services: AppServices,
    columns: List<TrackerColumn>,
    month: LocalDate,
    today: LocalDate,
    figures: Boolean,
    write: TrackerWrite,
) {
    val days = remember(month) { monthDays(month) }
    val ids = remember(columns) { columns.map { it.id } }
    val timed = remember(columns) { columns.filter { it.type == ColumnType.Time }.map { it.id } }
    val cellRecords by remember(ids, month) {
        services.trackers.cells(ids, "${days.first()}", "${days.last()}")
    }.collectAsStateWithLifecycle(emptyList())
    // A night's evening can be on the day before the 1st, its morning on the day after the month's last.
    val spanRecords by remember(timed, month) {
        if (timed.isEmpty()) {
            flowOf(emptyList<TrackerRecord>())
        } else {
            services.trackers.spans(timed, "${days.first().minus(1, DateTimeUnit.DAY)}", "${days.last().plus(1, DateTimeUnit.DAY)}")
        }
    }.collectAsStateWithLifecycle(emptyList())
    val cells = remember(cellRecords) { cellRecords.associate { it.id to it.fields } }
    val spans = remember(spanRecords) { spanRecords.groupBy { it[SpanField.COLUMN].orEmpty() } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (days.indexOf(today) - TODAY_ABOVE).coerceAtLeast(0))
    val weekdays = stringArrayResource(Res.array.weekdays_short)
    var asked by remember { mutableStateOf<Pair<TrackerColumn, LocalDate>?>(null) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            items(days, key = { it.toString() }) { day ->
                val weekday = weekdays.getOrElse(day.dayOfWeek.isoDayNumber - 1) { "" }
                DayRow(columns, day, weekday, today, figures, cells, spans, write) { asked = it to day }
            }
        }
        if (figures) TotalsRow(columns, days.filter { it <= today }, cells, spans)
    }
    asked?.let { (column, day) ->
        CellDialog(column, "$day", cells[TrackerCells.id(column.id, "$day")], write) { asked = null }
    }
}

/** A day's line: its number and weekday, then a cell per column; days to come are faint and wait. A line ends each week. */
@Composable
private fun DayRow(
    columns: List<TrackerColumn>,
    day: LocalDate,
    weekday: String,
    today: LocalDate,
    figures: Boolean,
    cells: SheetCells,
    spans: SheetSpans,
    write: TrackerWrite,
    onAsk: (TrackerColumn) -> Unit,
) {
    val future = day > today
    val date = "$day"
    Column {
        Row(
            Modifier.fillMaxWidth().height(ROW_HEIGHT)
                .then(if (day == today) Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = TODAY_ALPHA)) else Modifier)
                .padding(horizontal = SIDE)
                .alpha(if (future) FUTURE_ALPHA else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DayLabel(day, weekday, day == today)
            columns.forEach { column ->
                val own = spans[column.id].orEmpty()
                val window = column.sheetHours
                if (window != null) {
                    val starts = hourStarts(window, day, column.dayBy)
                    val painted = starts.map { covered(own, it) }
                    starts.forEachIndexed { i, start ->
                        HourCell(
                            column,
                            painted = painted[i],
                            first = i == 0 || !painted[i - 1],
                            last = i == starts.lastIndex || !painted[i + 1],
                            enabled = !future,
                            tag = "${TrackerCells.id(column.id, date)}:$i",
                            modifier = Modifier.weight(HOUR_WEIGHT),
                        ) {
                            val edits = hourTap(own, start)
                            write { together { edits.forEach { edit(column.id, it) } } }
                        }
                    }
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
                    Cell(
                        column,
                        date,
                        cells[TrackerCells.id(column.id, date)],
                        own,
                        figures,
                        enabled = !future,
                        modifier = Modifier.weight(1f),
                        write = write,
                        onAsk = { onAsk(column) },
                    )
                }
            }
        }
        if (day.dayOfWeek == DayOfWeek.SUNDAY) {
            HorizontalDivider(Modifier.padding(horizontal = SIDE), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun DayLabel(day: LocalDate, weekday: String, today: Boolean) {
    val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
    Column(Modifier.width(DAY_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
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
 * A day's cell, drawn from the column's tokens: blank paper with a faint frame when empty; a check filled with the
 * column's ink; a count filling up towards its goal; a rating deeper with its level; a choice in its option's color with
 * its emoji; a note's first words. Numbers show only with the figures; a dot in the corner tells of a note to the day.
 * A tap answers by type ([tapOf]); a long press opens the exact number and the note.
 */
@Composable
private fun Cell(
    column: TrackerColumn,
    day: String,
    fields: Map<String, String?>?,
    spans: List<TrackerRecord>,
    figures: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    write: TrackerWrite,
    onAsk: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val ink = column.record[ColumnField.COLOR]?.let { BenbowPalette.text(it, dark) } ?: colors.primary
    val shape = RoundedCornerShape(CELL_CORNER)
    val value = fields?.get(CellField.VALUE)
    var picking by remember { mutableStateOf(false) }
    val known = column.type != null && column.type != ColumnType.Time
    val tap: () -> Unit = {
        when (val answer = tapOf(column, fields)) {
            is CellTap.Set -> write { setCell(column.id, day, answer.value) }
            CellTap.Add -> write { addToCount(column.id, day) }
            CellTap.Pick -> picking = true
            CellTap.Ask -> onAsk()
            CellTap.None -> Unit
        }
    }
    Box(
        modifier.fillMaxHeight()
            .padding(CELL_GAP)
            .clip(shape)
            .border(1.dp, colors.outline.copy(alpha = FRAME_ALPHA), shape)
            .then(if (enabled && known) Modifier.hand().combinedClickable(onClick = tap, onLongClick = onAsk) else Modifier)
            .testTag(TrackerCells.id(column.id, day)),
        contentAlignment = Alignment.Center,
    ) {
        val number = @Composable { text: String ->
            Text(text, color = colors.onSurface, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
        when (column.type) {
            ColumnType.Check -> if (value == "true") Box(Modifier.fillMaxSize().background(ink.copy(alpha = MARK_ALPHA)))

            ColumnType.Count -> {
                val fill = column.fill(fields)
                if (fill > 0f) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(fill)
                            .background(ink.copy(alpha = if (fill >= 1f) MARK_ALPHA else PART_ALPHA)),
                    )
                }
                val count = fields?.let(TrackerCells::count) ?: 0.0
                if (figures && count != 0.0) number(countText(count))
            }

            ColumnType.Scale -> value?.toIntOrNull()?.let { level ->
                Box(Modifier.fillMaxSize().background(ink.copy(alpha = PART_ALPHA + (MARK_ALPHA - PART_ALPHA) * column.fill(fields))))
                if (figures) number("$level")
            }

            ColumnType.Choice -> column.options.find { it.id == value }?.let { option ->
                Box(Modifier.fillMaxSize().background((option.color?.let { BenbowPalette.text(it, dark) } ?: ink).copy(alpha = PART_ALPHA)))
                Text(option.icon?.let(::iconEmoji) ?: option.name.take(1), fontSize = 16.sp, maxLines = 1)
            }

            ColumnType.Note -> if (!value.isNullOrBlank()) {
                Box(Modifier.fillMaxSize().background(ink.copy(alpha = PART_ALPHA)))
                Text(
                    value,
                    Modifier.padding(horizontal = 2.dp),
                    color = colors.onSurface,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Time without a window of hours: the day's time; timers and its spans come with B6.
            ColumnType.Time -> {
                val seconds = TrackerSpans.total(spans, day, column.dayBy)
                if (seconds > 0) {
                    Box(Modifier.fillMaxSize().background(ink.copy(alpha = PART_ALPHA)))
                    if (figures) number(clockText(seconds))
                }
            }

            null -> Unit
        }
        if (!fields?.get(CellField.NOTE).isNullOrBlank()) {
            Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(NOTE_DOT).background(ink, CircleShape))
        }
        DropdownMenu(picking, { picking = false }) {
            ValueRow(column, value, ink, dark) { picked ->
                picking = false
                write { setCell(column.id, day, picked) }
            }
        }
    }
}

/** A rating's levels or a choice's options in a row, in their colors; the one set is ringed, and «×» clears it. */
@Composable
private fun ValueRow(column: TrackerColumn, value: String?, ink: Color, dark: Boolean, onPick: (String?) -> Unit) {
    val values = pickValues(column)
    Row(
        Modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        values.forEachIndexed { i, each ->
            val option = column.options.find { it.id == each }
            val tint = option?.color?.let { BenbowPalette.text(it, dark) } ?: ink
            val level = if (column.type == ColumnType.Scale) (i + 1f) / values.size else 1f
            Box(
                Modifier.size(PICK_SIZE)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = PART_ALPHA + (MARK_ALPHA - PART_ALPHA) * level))
                    .then(if (each == value) Modifier.border(2.dp, tint, CircleShape) else Modifier)
                    .hand()
                    .clickable { onPick(each) }
                    .then(if (option != null) Modifier.semantics { contentDescription = option.name } else Modifier)
                    .testTag("pick:$each"),
                contentAlignment = Alignment.Center,
            ) {
                Text(option?.icon?.let(::iconEmoji) ?: option?.name?.take(1) ?: each, fontSize = if (option != null) 18.sp else 14.sp)
            }
        }
        if (value != null) {
            IconButton({ onPick(null) }, Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.trackers_clear)) }
        }
    }
}

/** An hour of the sleep sheet: painted hours join into one bar with round ends; an empty hour is a faint dot. */
@Composable
private fun HourCell(
    column: TrackerColumn,
    painted: Boolean,
    first: Boolean,
    last: Boolean,
    enabled: Boolean,
    tag: String,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ink = column.record[ColumnField.COLOR]?.let { BenbowPalette.text(it, colors.background.luminance() < 0.5f) } ?: colors.primary
    Box(
        modifier.fillMaxHeight()
            .then(if (enabled) Modifier.hand().clickable(onClick = onTap) else Modifier)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (painted) {
            val round = { at: Boolean -> if (at) BAR_CORNER else 0.dp }
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(BAR_HEIGHT)
                    .padding(start = if (first) 1.dp else 0.dp, end = if (last) 1.dp else 0.dp)
                    .background(
                        ink.copy(alpha = MARK_ALPHA),
                        RoundedCornerShape(
                            topStart = round(first),
                            topEnd = round(last),
                            bottomEnd = round(last),
                            bottomStart = round(first),
                        ),
                    ),
            )
        } else {
            Box(Modifier.size(3.dp).background(colors.outline.copy(alpha = DOT_ALPHA), CircleShape))
        }
    }
}

/** The month's figures so far: they only count up (ux.md "Psychology"), and the eye hides them. */
@Composable
private fun TotalsRow(columns: List<TrackerColumn>, days: List<LocalDate>, cells: SheetCells, spans: SheetSpans) {
    val dates = remember(days) { days.map { "$it" } }
    Row(
        Modifier.fillMaxWidth().height(TOTALS_HEIGHT)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = FOOT_ALPHA))
            .padding(horizontal = SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.trackers_total),
            modifier = Modifier.width(DAY_WIDTH),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
        )
        columns.forEach { column ->
            column.sheetHours?.let { Box(Modifier.weight(it.hours.size * HOUR_WEIGHT)) }
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

/**
 * A long press on a day's cell: a count's exact number, a note column's text, or a note to the day for the others.
 * Only what changed is written, in one transaction.
 */
@Composable
private fun CellDialog(column: TrackerColumn, day: String, fields: Map<String, String?>?, write: TrackerWrite, onDismiss: () -> Unit) {
    val was = fields.orEmpty()
    val count = column.type == ColumnType.Count
    val text = column.type == ColumnType.Note
    val counted = TrackerCells.count(was)
    var number by remember { mutableStateOf(if (count && counted != 0.0) countText(counted) else "") }
    var value by remember { mutableStateOf(was[CellField.VALUE].orEmpty()) }
    var note by remember { mutableStateOf(was[CellField.NOTE].orEmpty()) }
    val total = if (number.isBlank()) 0.0 else number.trim().replace(',', '.').toDoubleOrNull()
    val months = stringArrayResource(Res.array.months_genitive)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${column.name} · ${dateLabel(day, months)}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (count) {
                    OutlinedTextField(
                        number,
                        { number = it },
                        label = { Text(stringResource(Res.string.trackers_cell_count)) },
                        suffix = column.unit?.let { unit -> @Composable { Text(unit) } },
                        isError = total == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.testTag("cell-count"),
                    )
                }
                if (text) {
                    OutlinedTextField(value, { value = it }, label = { Text(stringResource(Res.string.trackers_cell_text)) }, maxLines = 4)
                } else {
                    OutlinedTextField(note, { note = it }, label = { Text(stringResource(Res.string.trackers_cell_note)) }, maxLines = 4)
                }
            }
        },
        confirmButton = {
            TextButton({
                write {
                    together {
                        if (count && total != null && total != counted) setCount(column.id, day, total)
                        if (text && value.trim() != was[CellField.VALUE].orEmpty()) setCell(column.id, day, value.trim().ifEmpty { null })
                        if (!text && note.trim() != was[CellField.NOTE].orEmpty()) {
                            setCell(column.id, day, note.trim().ifEmpty { null }, CellField.NOTE)
                        }
                    }
                }
                onDismiss()
            }, Modifier.hand(total != null), enabled = total != null) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** A tap's change to a time column's spans. */
private fun TrackerRepository.edit(column: String, edit: SpanEdit) {
    when (edit) {
        is SpanEdit.Add -> addSpan(column, edit.start, edit.end)
        is SpanEdit.Change -> updateSpan(edit.id, edit.fields)
        is SpanEdit.Trash -> moveSpanToTrash(edit.id)
    }
}

/** `figures` when the eye has hidden them. */
private const val FIGURES_HIDDEN = "hidden"

private val DAY_WIDTH = 44.dp
private val ROW_HEIGHT = 40.dp
private val HEADER_HEIGHT = 56.dp
private val TOTALS_HEIGHT = 36.dp
private val SIDE = 4.dp
private val CELL_GAP = 3.dp
private val CELL_CORNER = 7.dp
private val BAR_CORNER = 6.dp
private val NOTE_DOT = 4.dp
private val PICK_SIZE = 36.dp
private const val BAR_HEIGHT = 0.56f

/** A page of a notebook: the month's sheet grows no wider on a big window; the sleep sheet's hours need more. */
private val SHEET = 640.dp
private val WIDE_SHEET = 960.dp

/** An hour of the sleep sheet is half as wide as a column. */
private const val HOUR_WEIGHT = 0.5f
private const val FUTURE_ALPHA = 0.35f

/**
 * How strongly marks lie on the paper (owner: calmer than the first spike's 0.85): a full mark, a part of one (a count
 * on its way to the goal, the lowest rating, a choice's tint), a cell's frame, an empty hour's dot, today's line and
 * the foot of the month.
 */
private const val MARK_ALPHA = 0.5f
private const val PART_ALPHA = 0.18f
private const val FRAME_ALPHA = 0.1f
private const val DOT_ALPHA = 0.25f
private const val TODAY_ALPHA = 0.05f
private const val FOOT_ALPHA = 0.04f

/** Rows above today when a month opens on it. */
private const val TODAY_ABOVE = 4
