package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.menu
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.next
import app.benbow.app.resources.previous
import app.benbow.app.resources.today
import app.benbow.app.resources.trackers_archive
import app.benbow.app.resources.trackers_columns_empty
import app.benbow.app.resources.trackers_figures_hide
import app.benbow.app.resources.trackers_figures_show
import app.benbow.app.resources.trackers_look
import app.benbow.app.resources.trackers_setup
import app.benbow.app.resources.trackers_unarchive
import app.benbow.app.resources.trackers_view_month
import app.benbow.app.resources.trackers_view_week
import app.benbow.app.resources.trackers_view_year
import app.benbow.app.resources.untitled
import app.benbow.app.resources.weekdays_short
import app.benbow.client.LookFields
import app.benbow.client.tracker.TrackerRepository
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerField
import app.benbow.core.tracker.TrackerRecord
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** A day's cells of a month by their id (`<column>:<day>`), and a month's spans by their column. */
internal typealias SheetCells = Map<String, Map<String, String?>>
internal typealias SheetSpans = Map<String, List<TrackerRecord>>

/** A write to the trackers, in the order the taps came ([TrackerWrites]). */
internal typealias TrackerWrite = (TrackerRepository.() -> Unit) -> Unit

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
    val write: TrackerWrite = { services.trackerWrites(it) }
    val untitled = stringResource(Res.string.untitled)
    val figures = tracker?.get(TrackerField.FIGURES) != FIGURES_HIDDEN
    var looking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = pageOf(today)) { MONTH_PAGES }
    val weeks = rememberPagerState(initialPage = WEEK_ORIGIN) { WEEK_PAGES }
    val years = rememberPagerState(initialPage = YEAR_ORIGIN) { YEAR_PAGES }
    // Where it opens is the tracker's own (`view`, on every device); a tap on a year's day looks at its month here only.
    var view by remember(trackerId, tracker != null) { mutableStateOf(SheetView.of(tracker?.get(TrackerField.VIEW))) }
    var yearColumn by rememberSaveable(trackerId) { mutableStateOf<String?>(null) }
    val active = when (view) {
        SheetView.Month -> pager
        SheetView.Week -> weeks
        SheetView.Year -> years
    }
    val todayPage = when (view) {
        SheetView.Month -> pageOf(today)
        SheetView.Week -> WEEK_ORIGIN
        SheetView.Year -> YEAR_ORIGIN
    }
    val scope = rememberCoroutineScope()
    val shownMonth = monthOfPage(pager.currentPage)
    val page = MaterialTheme.colorScheme.surfaceVariant
    val months = stringArrayResource(Res.array.months_genitive)
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { if (back) BackButton(nav::back) },
            title = {
                Column {
                    Text(tracker?.let { trackerTitle(it, untitled) }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (view) {
                            SheetView.Month -> monthTitle(shownMonth.toString().take(7), withYear = true).orEmpty()
                            SheetView.Week -> weekTitle(weekOfPage(today, weeks.currentPage), months)
                            SheetView.Year -> "${yearOfPage(today, years.currentPage)}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
            actions = {
                if (tracker != null) {
                    if (active.currentPage != todayPage) {
                        IconButton({ scope.launch { active.animateScrollToPage(todayPage) } }, modifier = Modifier.hand()) {
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
                            scope.launch { active.animateScrollToPage(active.currentPage - 1) }
                        }, modifier = Modifier.hand()) { Icon(Icons.ChevronLeft, stringResource(Res.string.previous)) }
                        IconButton({
                            scope.launch { active.animateScrollToPage(active.currentPage + 1) }
                        }, modifier = Modifier.hand()) { Icon(Icons.ChevronRight, stringResource(Res.string.next)) }
                    }
                    Box {
                        IconButton({ menu = true }, Modifier.hand()) { Icon(Icons.More, stringResource(Res.string.menu)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(Res.string.trackers_setup)) }, {
                                menu = false
                                nav.open(Route.TrackerEditor(trackerId))
                            }, Modifier.hand(), leadingIcon = { Icon(Icons.Edit, null) })
                            val archived = tracker.archived
                            DropdownMenuItem({
                                Text(stringResource(if (archived) Res.string.trackers_unarchive else Res.string.trackers_archive))
                            }, {
                                menu = false
                                write { updateTracker(trackerId, mapOf(TrackerField.ARCHIVED to if (archived) null else "true")) }
                            }, Modifier.hand(), leadingIcon = { Icon(if (archived) Icons.Unarchive else Icons.Archive, null) })
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
        if (tracker != null && columns?.isEmpty() == true) {
            // A blank sheet: its columns are made in «Настроить трекер».
            Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(Res.string.trackers_columns_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
                TextButton({ nav.open(Route.TrackerEditor(trackerId)) }, Modifier.padding(top = 12.dp).hand()) {
                    Text(stringResource(Res.string.trackers_setup))
                }
            }
        } else if (tracker != null && columns != null) {
            Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                // The month, a week or a year; choosing one makes it where the tracker opens.
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SheetView.entries.forEach { each ->
                        FilterChip(view == each, {
                            view = each
                            write { updateTracker(trackerId, mapOf(TrackerField.VIEW to each.token)) }
                        }, { Text(stringResource(each.label)) }, modifier = Modifier.hand())
                    }
                }
                // The tracker's own look, as a note's (its color and layers, ADR-047), drawn muted so the marks stay the
                // main thing; as wide as a page of a notebook.
                val base = swatch(tracker[TrackerField.BACKGROUND]) ?: page
                val look = remember(tracker) { LookFields.of(tracker.fields) }
                val wide = view == SheetView.Month && columns.any { it.sheetHours != null }
                Column(
                    Modifier.widthIn(max = if (wide) SheetSizes.WIDE_SHEET else SheetSizes.SHEET)
                        .fillMaxSize()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(base)
                        .then(entryBackdrop(tracker.id, look, base, muted = true, moving = true)),
                ) {
                    when (view) {
                        SheetView.Month -> {
                            SheetHeader(columns, figures, write)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            HorizontalPager(pager, beyondViewportPageCount = 1, modifier = Modifier.weight(1f).fillMaxWidth()) { at ->
                                MonthPage(services, columns, monthOfPage(at), today, figures, write)
                            }
                        }

                        SheetView.Week -> HorizontalPager(
                            weeks,
                            beyondViewportPageCount = 1,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { at ->
                            WeekPage(services, columns, weekOfPage(today, at), today, figures, write)
                        }

                        SheetView.Year -> HorizontalPager(years, modifier = Modifier.weight(1f).fillMaxWidth()) { at ->
                            YearPage(
                                services,
                                columns,
                                yearColumn,
                                yearOfPage(today, at),
                                today,
                                figures,
                                onChoose = { yearColumn = it },
                                onMonth = { month ->
                                    view = SheetView.Month
                                    scope.launch { pager.scrollToPage(pageOf(month)) }
                                },
                            )
                        }
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

/** One month of the sheet: its days down the page (today a few rows from the top), then its figures. */
@Composable
internal fun MonthPage(
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
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (days.indexOf(today) - SheetSizes.TODAY_ABOVE).coerceAtLeast(0))
    val weekdays = stringArrayResource(Res.array.weekdays_short)
    var asked by remember { mutableStateOf<Pair<TrackerColumn, LocalDate>?>(null) }
    var spansOf by remember { mutableStateOf<Pair<TrackerColumn, LocalDate>?>(null) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            items(days, key = { it.toString() }) { day ->
                val weekday = weekdays.getOrElse(day.dayOfWeek.isoDayNumber - 1) { "" }
                DayRow(columns, day, weekday, today, figures, cells, spans, write, onSpans = { spansOf = it to day }) { asked = it to day }
                // A week's figures under its Sunday, and under the month's last day for the week it cuts.
                if (figures && (day.dayOfWeek == DayOfWeek.SUNDAY || day == days.last())) {
                    val week = weekOf(day, days).filter { it <= today }
                    if (week.isNotEmpty()) WeekRow(columns, week, cells, spans)
                }
            }
        }
        if (figures) TotalsRow(columns, days.filter { it <= today }, cells, spans)
    }
    asked?.let { (column, day) ->
        CellDialog(column, "$day", cells[TrackerCells.id(column.id, "$day")], write) { asked = null }
    }
    spansOf?.let { (column, day) ->
        DaySpansDialog(column, day, spans[column.id].orEmpty(), write) { spansOf = null }
    }
}

/** `figures` when the eye has hidden them. */
internal const val FIGURES_HIDDEN = "hidden"

internal val SheetView.label: StringResource get() = when (this) {
    SheetView.Month -> Res.string.trackers_view_month
    SheetView.Week -> Res.string.trackers_view_week
    SheetView.Year -> Res.string.trackers_view_year
}
