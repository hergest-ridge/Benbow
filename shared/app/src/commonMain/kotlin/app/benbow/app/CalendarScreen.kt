package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.calendar_all_day
import app.benbow.app.resources.calendar_calendars
import app.benbow.app.resources.calendar_empty
import app.benbow.app.resources.calendar_entry
import app.benbow.app.resources.calendar_event
import app.benbow.app.resources.calendar_more
import app.benbow.app.resources.calendar_my_events
import app.benbow.app.resources.calendar_until
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.months_nominative
import app.benbow.app.resources.next
import app.benbow.app.resources.previous
import app.benbow.app.resources.weekdays_short
import app.benbow.client.DayInfo
import app.benbow.client.EntrySummary
import app.benbow.client.calendar.CalendarEvent
import app.benbow.client.calendar.CalendarItem
import app.benbow.client.calendar.CalendarRepository
import app.benbow.client.calendar.defaultCalendarColor
import app.benbow.core.calendar.CalendarExport
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.MY_CALENDAR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import java.io.OutputStream
import kotlin.math.absoluteValue

internal fun LocalDate.monthStart() = LocalDate(year, month, 1)

internal fun LocalDate.monthEnd() = monthStart().plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)

/** The weeks shown for a month, Monday first, including the neighbours' days that fill the first and last week. */
internal fun monthGrid(month: LocalDate): List<List<LocalDate>> {
    val first = month.monthStart()
    val start = first.minus(first.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    val last = month.monthEnd()
    val end = last.plus(7 - last.dayOfWeek.isoDayNumber, DateTimeUnit.DAY)
    return generateSequence(start) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= end }.toList().chunked(7)
}

/** "9:30" → "09:30"; null when it isn't a time. */
internal fun parseTime(text: String): String? = Regex("""^\s*([01]?\d|2[0-3])[:.]([0-5]\d)\s*$""").find(text)?.let { m ->
    m.groupValues[1].padStart(2, '0') + ":" + m.groupValues[2]
}

/** A multi-day event's piece of the thin bar in one day cell; [starts] / [ends] round its ends (also at week edges). */
internal data class EventBar(val color: String, val starts: Boolean, val ends: Boolean)

/** What a day cell shows of the events, quieter than the journal: one bar, up to three dots, and whether more hide. */
internal data class DayMarks(val bar: EventBar? = null, val dots: List<String> = emptyList(), val more: Boolean = false)

/** Marks for every day of [days]; [colorOf] gives an event's calendar color. */
internal fun dayMarks(days: List<LocalDate>, events: List<CalendarEvent>, colorOf: (CalendarEvent) -> String): Map<String, DayMarks> =
    days.associate { date ->
        val day = date.toString()
        val covering = events.filter { it.covers(day) }
        val long = covering.firstOrNull { it.lastDay > it.day }
        val bar = long?.let {
            EventBar(
                colorOf(it),
                starts = day == it.day || date.dayOfWeek == DayOfWeek.MONDAY,
                ends =
                day == it.lastDay || date.dayOfWeek == DayOfWeek.SUNDAY,
            )
        }
        val rest = covering - setOfNotNull(long)
        day to DayMarks(bar, rest.take(MAX_DOTS).map(colorOf), rest.size > MAX_DOTS)
    }

private const val MAX_DOTS = 3
private const val FOLDED_EVENTS = 3

internal class CalendarViewModel(private val services: AppServices) : ViewModel() {
    val selected = MutableStateFlow(LocalDate.parse(services.today()))
    val month = MutableStateFlow(selected.value.monthStart())

    @OptIn(ExperimentalCoroutinesApi::class)
    val days: StateFlow<Map<String, DayInfo>> = month.flatMapLatest { services.repo.monthDays(it.toString(), it.monthEnd().toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** From the calendar's own file (ADR-027), never from the journal; the grid's weeks plus the days around them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: StateFlow<List<CalendarEvent>> = month.flatMapLatest {
        services.calendar.events(it.minus(7, DateTimeUnit.DAY).toString(), it.monthEnd().plus(7, DateTimeUnit.DAY).toString())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val calendars: StateFlow<List<CalendarItem>> = services.calendar.calendars()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: StateFlow<List<EntrySummary>> = selected.flatMapLatest { services.repo.entriesForDay(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(day: LocalDate) {
        selected.value = day
        month.value = day.monthStart()
    }

    fun shiftMonth(by: Int) {
        month.value = month.value.plus(by, DateTimeUnit.MONTH)
    }

    /** A new event, or the changed fields of [event] (its series when it repeats). */
    fun save(event: CalendarEvent?, draft: EventDraft) = write {
        if (event == null) {
            createEvent(draft.fields().filterValues { it != null })
        } else {
            put(CalendarKind.EVENT, event.id, draft.changes(EventDraft.of(event)))
        }
    }

    /** The whole event (or series) to the trash, or just this day of a series. */
    fun delete(event: CalendarEvent, wholeSeries: Boolean) =
        write { if (wholeSeries) moveToTrash(event.id) else skipDay(event.id, event.day) }

    fun setVisible(calendar: CalendarItem, visible: Boolean) = write { setVisible(calendar.id, visible) }

    /** A new calendar (name and color both given), or what changed of [calendar]. */
    fun saveCalendar(calendar: CalendarItem?, name: String?, color: String?) = write {
        when {
            calendar != null -> updateCalendar(calendar.id, name, color)
            name != null && color != null -> createCalendar(name, color)
        }
    }

    fun deleteCalendar(calendar: CalendarItem) = write { deleteCalendar(calendar.id) }

    /** Writes [calendar] as an `.ics` file where the user chose to save it (ADR-028). */
    fun saveIcs(calendar: CalendarItem, name: String, open: () -> OutputStream) {
        services.scope.launch {
            try {
                open().use { it.write(services.calendar.exportIcs(calendar, name, services.now()).encodeToByteArray()) }
                services.messages.tryEmit(AppMessage.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                services.messages.tryEmit(AppMessage.SaveFailed(e.message ?: e::class.simpleName.orEmpty()))
            }
        }
    }

    private fun write(block: CalendarRepository.() -> Unit) {
        services.scope.launch { services.calendar.block() }
    }
}

/**
 * Month at a glance: the journal first (entries as dots, the day's mood as a tint), events quieter under it (ADR-027,
 * ux.md "Calendar layer"); the chosen day below, its events as a compact strip before the entries. Swipe for days.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { CalendarViewModel(services) }
    val month by vm.month.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val today = remember { LocalDate.parse(services.today()) }
    var sheet by remember { mutableStateOf<EventSheetRequest?>(null) }
    var showCalendars by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<CalendarItem?>(null) }
    val defaultName = stringResource(Res.string.calendar_my_events)
    val openFile = rememberOpenFile(listOf("*/*")) { file ->
        if (file != null) {
            services.calendarImports.open(file.name, file.open)
            nav.open(Route.CalendarImport)
        }
    }
    val saveIcs = rememberSaveFile("text/calendar") { open ->
        val calendar = exporting
        exporting = null
        if (open != null && calendar != null) vm.saveIcs(calendar, calendar.name ?: defaultName, open)
    }
    var unfolded by remember(selected) { mutableStateOf(false) }
    val colors = remember(calendars) { calendars.associate { it.id to it.color } }
    val colorOf = { event: CalendarEvent -> colors[event.calendar] ?: defaultCalendarColor(event.calendar) }
    val grid = remember(month) { monthGrid(month) }
    val marks = remember(grid, events, colors) { dayMarks(grid.flatten(), events, colorOf) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${stringArrayResource(Res.array.months_nominative)[month.month.number - 1]} ${month.year}") },
                actions = {
                    IconButton({ showCalendars = true }) { Icon(Icons.Layers, stringResource(Res.string.calendar_calendars)) }
                    IconButton({ vm.shiftMonth(-1) }) { Icon(Icons.ChevronLeft, stringResource(Res.string.previous)) }
                    IconButton({ vm.shiftMonth(1) }) { Icon(Icons.ChevronRight, stringResource(Res.string.next)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MonthGrid(grid, month, selected, today, days, marks, onSelect = vm::select)
            HorizontalDivider(Modifier.padding(top = 4.dp))
            var drag by remember { mutableStateOf(0f) }
            val dayEvents = events.filter { it.covers(selected.toString()) }
            LazyColumn(
                Modifier.fillMaxSize().pointerInput(selected) {
                    detectHorizontalDragGestures(onDragEnd = {
                        if (drag.absoluteValue > 120) vm.select(selected.plus(if (drag < 0) 1 else -1, DateTimeUnit.DAY))
                        drag = 0f
                    }) { _, amount -> drag += amount }
                },
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item(key = "head") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            fullDateLabel(selected.toString()),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton({
                            nav.open(Route.Editor(null, selected.toString(), opened = services.now()))
                        }) { Text(stringResource(Res.string.calendar_entry)) }
                        TextButton({ sheet = EventSheetRequest(null, EventDraft.new(selected, MY_CALENDAR)) }) {
                            Text(stringResource(Res.string.calendar_event))
                        }
                        IconButton({ nav.open(Route.Reminders(selected.toString())) }) { Text("🔔") }
                    }
                }
                val shown = if (unfolded) dayEvents else dayEvents.take(FOLDED_EVENTS)
                items(shown, key = { "e" + it.id + it.day }) { event ->
                    EventRow(event, selected.toString(), colorOf(event)) { sheet = EventSheetRequest(event, EventDraft.of(event)) }
                }
                if (dayEvents.size > shown.size) {
                    item(key = "more") {
                        TextButton({ unfolded = true }, Modifier.padding(horizontal = 12.dp)) {
                            Text(stringResource(Res.string.calendar_more, dayEvents.size - shown.size))
                        }
                    }
                }
                if (dayEvents.isNotEmpty() && entries.isNotEmpty()) item(key = "gap") { Spacer(Modifier.height(8.dp)) }
                items(entries, key = {
                    it.id
                }) { EntryCard(it, onClick = { nav.open(Route.Editor(it.id, it.day)) }, media = services.media) }
                if (dayEvents.isEmpty() && entries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(Res.string.calendar_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
    sheet?.let { request ->
        val close = { sheet = null }
        EventSheet(
            event = request.event,
            initial = request.draft,
            calendars = calendars,
            onSave = {
                vm.save(request.event, it)
                close()
            },
            onDelete = { whole ->
                request.event?.let { vm.delete(it, whole) }
                close()
            },
            onWrite = {
                val event = request.event ?: return@EventSheet
                close()
                nav.open(Route.Editor(null, event.day, opened = services.now(), heading = event.title))
            },
            onDismiss = close,
        )
    }
    if (showCalendars) {
        CalendarsSheet(
            calendars = calendars,
            onVisible = vm::setVisible,
            onSave = vm::saveCalendar,
            onDelete = vm::deleteCalendar,
            onExport = { calendar ->
                exporting = calendar
                saveIcs(CalendarExport.fileName(calendar.name ?: defaultName, calendar.id))
            },
            onImport = {
                showCalendars = false
                openFile()
            },
            onDismiss = { showCalendars = false },
        )
    }
}

/** The sheet's subject: an event as it is (null = a new one) and what the form starts from. */
private data class EventSheetRequest(val event: CalendarEvent?, val draft: EventDraft)

/** One line of the day's strip: the calendar's color, when, and the title; no card, so entries stay the main thing. */
@Composable
private fun EventRow(event: CalendarEvent, day: String, color: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(4.dp).height(18.dp).background(markColor(color) ?: MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
        Text(
            eventWhen(event, day),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(event.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "09:30", "09:30–10:15", "до 23 октября" (a longer one, before its last day) or "весь день". */
@Composable
private fun eventWhen(event: CalendarEvent, day: String): String {
    val time = event.time
    return when {
        time != null && day == event.day -> time + (event.endTime?.takeIf { event.lastDay == event.day }?.let { "–$it" } ?: "")

        event.lastDay > day -> {
            val last = LocalDate.parse(event.lastDay)
            stringResource(
                Res.string.calendar_until,
                "${last.day} ${stringArrayResource(Res.array.months_genitive)[last.month.number - 1]}",
            )
        }

        else -> stringResource(Res.string.calendar_all_day)
    }
}

@Composable
private fun MonthGrid(
    grid: List<List<LocalDate>>,
    month: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    days: Map<String, DayInfo>,
    marks: Map<String, DayMarks>,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row {
            stringArrayResource(Res.array.weekdays_short).forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        grid.forEach { week ->
            Row {
                week.forEach { date ->
                    val key = date.toString()
                    val info = days[key]
                    val inMonth = date.month == month.month
                    // Warm for good days, sea blue for hard ones; untinted when no mood was noted.
                    val tint = info?.mood?.let { mood ->
                        (if (mood >= 0) colors.primary else colors.secondary).copy(alpha = (0.12 + 0.12 * mood.absoluteValue).toFloat())
                    } ?: Color.Transparent
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        Box(
                            Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(10.dp)).background(tint)
                                .then(if (date == selected) Modifier.border(2.dp, colors.primary, RoundedCornerShape(10.dp)) else Modifier)
                                .clickable { onSelect(date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    date.day.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = when {
                                        !inMonth -> colors.onSurfaceVariant.copy(alpha = 0.4f)
                                        date == today -> colors.primary
                                        else -> colors.onSurface
                                    },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    repeat(minOf(info?.entries ?: 0, 3)) {
                                        Box(Modifier.size(4.dp).background(colors.primary, CircleShape))
                                    }
                                }
                                EventDots(marks[key] ?: DayMarks())
                            }
                        }
                        marks[key]?.bar?.let { EventBarPiece(it, Modifier.align(Alignment.BottomCenter)) }
                    }
                }
            }
        }
    }
}

/** Events under the journal's dots: smaller, in their calendars' colors, "+" when more hide. */
@Composable
private fun EventDots(marks: DayMarks) {
    Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        marks.dots.forEach { Box(Modifier.size(3.dp).background(markColor(it) ?: MaterialTheme.colorScheme.outline, CircleShape)) }
        if (marks.more) Text("+", fontSize = 8.sp, lineHeight = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A thin bar along the cell's bottom that joins the next day's, so a trip reads as one line across the week. */
@Composable
private fun EventBarPiece(bar: EventBar, modifier: Modifier) {
    val round = 2.dp
    Box(
        modifier.fillMaxWidth().padding(
            start = if (bar.starts) 6.dp else 0.dp,
            end = if (bar.ends) 6.dp else 0.dp,
            bottom = 5.dp,
        ).height(3.dp)
            .background(
                markColor(bar.color) ?: MaterialTheme.colorScheme.outline,
                RoundedCornerShape(
                    topStart = if (bar.starts) round else 0.dp,
                    bottomStart = if (bar.starts) round else 0.dp,
                    topEnd = if (bar.ends) round else 0.dp,
                    bottomEnd = if (bar.ends) round else 0.dp,
                ),
            ),
    )
}
