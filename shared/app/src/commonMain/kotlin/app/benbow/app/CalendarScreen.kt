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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.add
import app.benbow.app.resources.calendar_all_day
import app.benbow.app.resources.calendar_empty
import app.benbow.app.resources.calendar_entry
import app.benbow.app.resources.calendar_event
import app.benbow.app.resources.cancel
import app.benbow.app.resources.event_delete
import app.benbow.app.resources.event_save
import app.benbow.app.resources.event_time_hint
import app.benbow.app.resources.event_title_hint
import app.benbow.app.resources.months_nominative
import app.benbow.app.resources.next
import app.benbow.app.resources.previous
import app.benbow.app.resources.weekdays_short
import app.benbow.client.DayInfo
import app.benbow.client.EntrySummary
import app.benbow.client.calendar.CalendarEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
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

internal class CalendarViewModel(private val services: AppServices) : ViewModel() {
    val selected = MutableStateFlow(LocalDate.parse(services.today()))
    val month = MutableStateFlow(selected.value.monthStart())

    @OptIn(ExperimentalCoroutinesApi::class)
    val days: StateFlow<Map<String, DayInfo>> = month.flatMapLatest { services.repo.monthDays(it.toString(), it.monthEnd().toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** From the calendar's own file (ADR-027), never from the journal. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: StateFlow<List<CalendarEvent>> = month.flatMapLatest {
        services.calendar.events(it.minus(7, DateTimeUnit.DAY).toString(), it.monthEnd().plus(7, DateTimeUnit.DAY).toString())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun addEvent(title: String, time: String?) {
        val day = selected.value.toString()
        services.scope.launch { services.calendar.createEvent(day, title, time) }
    }

    fun saveEvent(event: CalendarEvent, title: String, time: String?) {
        services.scope.launch { services.calendar.updateEvent(event.id, title, time?.let { "${event.day}T$it" } ?: event.day) }
    }

    fun deleteEvent(event: CalendarEvent) {
        services.scope.launch { services.calendar.moveToTrash(event.id) }
    }
}

/** Month at a glance (entries as dots, the day's mood as a tint, events marked), the chosen day below; swipe for days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { CalendarViewModel(services) }
    val month by vm.month.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val today = remember { LocalDate.parse(services.today()) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${stringArrayResource(Res.array.months_nominative)[month.month.number - 1]} ${month.year}") },
                actions = {
                    IconButton({ vm.shiftMonth(-1) }) { Icon(Icons.ChevronLeft, stringResource(Res.string.previous)) }
                    IconButton({ vm.shiftMonth(1) }) { Icon(Icons.ChevronRight, stringResource(Res.string.next)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MonthGrid(month, selected, today, days, { day -> events.any { it.covers(day) } }, onSelect = vm::select)
            HorizontalDivider(Modifier.padding(top = 4.dp))
            var drag by remember { mutableStateOf(0f) }
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
                        TextButton({ adding = !adding }) { Text(stringResource(Res.string.calendar_event)) }
                        IconButton({ nav.open(Route.Reminders(selected.toString())) }) { Text("🔔") }
                    }
                }
                if (adding) {
                    item(key = "add") {
                        EventForm { title, time ->
                            vm.addEvent(title, time)
                            adding = false
                        }
                    }
                }
                val dayEvents = events.filter { it.covers(selected.toString()) }
                items(dayEvents, key = { "e" + it.id }) { event ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = event }.padding(horizontal = 24.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            event.time ?: stringResource(Res.string.calendar_all_day),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(event.title, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                items(entries, key = {
                    it.id
                }) { EntryCard(it, onClick = { nav.open(Route.Editor(it.id, it.day)) }, media = services.media) }
                if (dayEvents.isEmpty() && entries.isEmpty() && !adding) {
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
    editing?.let { event ->
        EventDialog(
            event,
            onSave = { title, time -> vm.saveEvent(event, title, time) },
            onDelete = { vm.deleteEvent(event) },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun MonthGrid(
    month: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    days: Map<String, DayInfo>,
    hasEvents: (String) -> Boolean,
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
        monthGrid(month).forEach { week ->
            Row {
                week.forEach { date ->
                    val key = date.toString()
                    val info = days[key]
                    val inMonth = date.month == month.month
                    // Warm for good days, sea blue for hard ones; untinted when no mood was noted.
                    val tint = info?.mood?.let { mood ->
                        (if (mood >= 0) colors.primary else colors.secondary).copy(alpha = (0.12 + 0.12 * mood.absoluteValue).toFloat())
                    } ?: Color.Transparent
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(10.dp)).background(tint)
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
                                repeat(minOf(info?.entries ?: 0, 3)) { Box(Modifier.size(4.dp).background(colors.primary, CircleShape)) }
                                if (hasEvents(key)) Box(Modifier.size(4.dp).background(colors.secondary, RoundedCornerShape(1.dp)))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** An event's title and time, or its removal. The fuller event sheet comes with the calendar layer (7.3b). */
@Composable
private fun EventDialog(event: CalendarEvent, onSave: (String, String?) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var title by remember(event.id) { mutableStateOf(event.title) }
    var time by remember(event.id) { mutableStateOf(event.time.orEmpty()) }
    val parsed = parseTime(time)
    val valid = title.isNotBlank() && (time.isBlank() || parsed != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fullDateLabel(event.day)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    time,
                    { time = it },
                    placeholder = { Text(stringResource(Res.string.event_time_hint)) },
                    singleLine = true,
                    isError = time.isNotBlank() && parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton({
                onSave(title.trim(), parsed)
                onDismiss()
            }, enabled = valid) { Text(stringResource(Res.string.event_save)) }
        },
        dismissButton = {
            Row {
                TextButton({
                    onDelete()
                    onDismiss()
                }) { Text(stringResource(Res.string.event_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}

@Composable
private fun EventForm(onAdd: (String, String?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    val parsed = parseTime(time)
    val valid = title.isNotBlank() && (time.isBlank() || parsed != null)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(title, {
            title = it
        }, placeholder = { Text(stringResource(Res.string.event_title_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                time,
                { time = it },
                placeholder = { Text(stringResource(Res.string.event_time_hint)) },
                singleLine = true,
                isError = time.isNotBlank() && parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            TextButton({ onAdd(title.trim(), parsed) }, enabled = valid) { Text(stringResource(Res.string.add)) }
        }
    }
}
