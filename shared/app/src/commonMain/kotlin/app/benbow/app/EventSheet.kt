package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.add
import app.benbow.app.resources.calendar_my_events
import app.benbow.app.resources.cancel
import app.benbow.app.resources.event_delete
import app.benbow.app.resources.event_delete_day
import app.benbow.app.resources.event_delete_series
import app.benbow.app.resources.event_delete_series_question
import app.benbow.app.resources.event_description_hint
import app.benbow.app.resources.event_end_add
import app.benbow.app.resources.event_end_clear
import app.benbow.app.resources.event_end_time_hint
import app.benbow.app.resources.event_location_hint
import app.benbow.app.resources.event_new
import app.benbow.app.resources.event_pick_day
import app.benbow.app.resources.event_repeat_custom
import app.benbow.app.resources.event_repeats
import app.benbow.app.resources.event_save
import app.benbow.app.resources.event_time_hint
import app.benbow.app.resources.event_title_hint
import app.benbow.app.resources.event_write
import app.benbow.client.calendar.CalendarEvent
import app.benbow.client.calendar.CalendarItem
import app.benbow.core.calendar.EventField
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The repeats the sheet offers; an imported rule outside them stays as it is ("Свой повтор"). */
internal val REPEATS = listOf(null, "FREQ=DAILY", "FREQ=WEEKLY", "FREQ=MONTHLY", "FREQ=YEARLY")

/**
 * What the event sheet edits. A repeating event is edited as a series: [day] is where it starts. [time] and
 * [endTime] are "HH:MM" or null; no end day means it ends on its start day.
 */
internal data class EventDraft(
    val title: String,
    val day: LocalDate,
    val time: String? = null,
    val endDay: LocalDate? = null,
    val endTime: String? = null,
    val rrule: String? = null,
    val location: String = "",
    val description: String = "",
    val calendar: String,
) {
    val hasEnd: Boolean get() = endDay != null || endTime != null

    /** A title, and an end after the start. */
    val valid: Boolean get() {
        if (title.isBlank()) return false
        val last = endDay ?: day
        return last > day || (last == day && (endTime == null || time == null || endTime > time))
    }

    fun fields(): Map<String, String?> = mapOf(
        EventField.TITLE to title.trim(),
        EventField.START to day.toString() + (time?.let { "T$it" } ?: ""),
        EventField.END to if (hasEnd) (endDay ?: day).toString() + (endTime?.let { "T$it" } ?: "") else null,
        EventField.RRULE to rrule,
        EventField.LOCATION to location.trim().ifEmpty { null },
        EventField.DESCRIPTION to description.trim().ifEmpty { null },
        EventField.CALENDAR to calendar,
    )

    /** Only what changed against [before], so concurrent edits of other fields on other devices survive. */
    fun changes(before: EventDraft): Map<String, String?> {
        val old = before.fields()
        return fields().filter { (field, value) -> old[field] != value }
    }

    companion object {
        fun new(day: LocalDate, calendar: String) = EventDraft(title = "", day = day, calendar = calendar)

        /** The series behind an occurrence: its start day, and its end as far from it as this occurrence's. */
        fun of(event: CalendarEvent): EventDraft {
            val day = LocalDate.parse(event.seriesStart.take(10))
            val span = LocalDate.parse(event.day).daysUntil(LocalDate.parse(event.lastDay))
            return EventDraft(
                title = event.title,
                day = day,
                time = event.time,
                endDay = event.end?.let { day.plus(span, DateTimeUnit.DAY) }?.takeIf { span > 0 },
                endTime = event.endTime,
                rrule = event.rrule,
                location = event.location.orEmpty(),
                description = event.description.orEmpty(),
                calendar = event.calendar,
            )
        }
    }
}

/**
 * An event, new ([event] null) or as it is: title, when, repeat, place, note and calendar. A repeating event's
 * deletion asks: only this day, or the whole series.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventSheet(
    event: CalendarEvent?,
    initial: EventDraft,
    calendars: List<CalendarItem>,
    onSave: (EventDraft) -> Unit,
    onDelete: (wholeSeries: Boolean) -> Unit,
    onWrite: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(event?.id) { mutableStateOf(initial) }
    var timeText by remember(event?.id) { mutableStateOf(initial.time.orEmpty()) }
    var endTimeText by remember(event?.id) { mutableStateOf(initial.endTime.orEmpty()) }
    var picking by remember { mutableStateOf<((LocalDate) -> Unit)?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    val time = parseTime(timeText)
    val endTime = parseTime(endTimeText)
    val current = draft.copy(time = time, endTime = endTime)
    val valid = current.valid && (timeText.isBlank() || time != null) && (endTimeText.isBlank() || endTime != null)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (event == null) Text(stringResource(Res.string.event_new), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                draft.title,
                { draft = draft.copy(title = it) },
                placeholder = { Text(stringResource(Res.string.event_title_hint)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({
                    picking = { draft = draft.copy(day = it) }
                }, modifier = Modifier.hand()) { Text(fullDateLabel(draft.day.toString())) }
                TimeField(timeText, { timeText = it }, stringResource(Res.string.event_time_hint), Modifier.weight(1f))
            }
            if (draft.hasEnd || endTimeText.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({
                        picking = { draft = draft.copy(endDay = it) }
                    }, modifier = Modifier.hand()) { Text(fullDateLabel((draft.endDay ?: draft.day).toString())) }
                    TimeField(endTimeText, { endTimeText = it }, stringResource(Res.string.event_end_time_hint), Modifier.weight(1f))
                    IconButton({
                        draft = draft.copy(endDay = null, endTime = null)
                        endTimeText = ""
                    }, modifier = Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.event_end_clear)) }
                }
            } else {
                TextButton({
                    draft = draft.copy(endDay = draft.day)
                }, modifier = Modifier.hand()) { Text(stringResource(Res.string.event_end_add)) }
            }
            RepeatMenu(draft.rrule) { draft = draft.copy(rrule = it) }
            OutlinedTextField(
                draft.location,
                { draft = draft.copy(location = it) },
                placeholder = { Text(stringResource(Res.string.event_location_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                draft.description,
                { draft = draft.copy(description = it) },
                placeholder = { Text(stringResource(Res.string.event_description_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                calendars.forEach { calendar ->
                    FilterChip(
                        selected = calendar.id == draft.calendar,
                        onClick = { draft = draft.copy(calendar = calendar.id) },
                        label = { Text(calendarName(calendar)) },
                        leadingIcon = { ColorDot(calendar.color) },
                        modifier = Modifier.hand(),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (event != null) {
                    TextButton({ if (event.repeats) askDelete = true else onDelete(true) }, modifier = Modifier.hand()) {
                        Text(stringResource(Res.string.event_delete), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onWrite, modifier = Modifier.hand()) { Text(stringResource(Res.string.event_write)) }
                }
                Spacer(Modifier.weight(1f))
                Button({ onSave(current) }, enabled = valid, modifier = Modifier.hand(valid)) {
                    Text(stringResource(if (event == null) Res.string.add else Res.string.event_save))
                }
            }
        }
    }
    picking?.let { onPick ->
        DayPicker(draft.day, onPick = {
            onPick(it)
            picking = null
        }, onDismiss = { picking = null })
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            text = { Text(stringResource(Res.string.event_delete_series_question)) },
            confirmButton = {
                TextButton({ onDelete(true) }, modifier = Modifier.hand()) { Text(stringResource(Res.string.event_delete_series)) }
            },
            dismissButton = {
                TextButton({ onDelete(false) }, modifier = Modifier.hand()) { Text(stringResource(Res.string.event_delete_day)) }
            },
        )
    }
}

@Composable
internal fun calendarName(calendar: CalendarItem): String = calendar.name ?: stringResource(Res.string.calendar_my_events)

@Composable
internal fun ColorDot(color: String, size: Int = 10) {
    Box(Modifier.size(size.dp).background(markColor(color) ?: MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun TimeField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier) {
    OutlinedTextField(
        value,
        onChange,
        placeholder = { Text(hint) },
        singleLine = true,
        isError = value.isNotBlank() && parseTime(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun RepeatMenu(rule: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = stringArrayResource(Res.array.event_repeats)
    val index = REPEATS.indexOf(rule)
    Box {
        OutlinedButton({ open = true }, modifier = Modifier.hand()) {
            Text(
                if (index >=
                    0
                ) {
                    labels[index]
                } else {
                    stringResource(Res.string.event_repeat_custom)
                },
            )
        }
        DropdownMenu(open, { open = false }) {
            REPEATS.forEachIndexed { i, choice ->
                DropdownMenuItem(text = { Text(labels[i]) }, onClick = {
                    onPick(choice)
                    open = false
                }, modifier = Modifier.hand())
            }
        }
    }
}

/** A day from the date picker (it speaks UTC midnights). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPicker(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                onPick(state.selectedDateMillis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date } ?: initial)
            }, modifier = Modifier.hand()) { Text(stringResource(Res.string.event_pick_day)) }
        },
        dismissButton = { TextButton(onDismiss, modifier = Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    ) { DatePicker(state) }
}
