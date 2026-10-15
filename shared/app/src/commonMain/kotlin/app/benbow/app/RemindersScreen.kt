package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.add
import app.benbow.app.resources.event_time_hint
import app.benbow.app.resources.reminder_daily
import app.benbow.app.resources.reminder_delete
import app.benbow.app.resources.reminder_done
import app.benbow.app.resources.reminder_hint
import app.benbow.app.resources.reminder_nudge
import app.benbow.app.resources.reminder_nudge_text
import app.benbow.app.resources.reminder_once
import app.benbow.app.resources.reminder_today
import app.benbow.app.resources.reminder_tomorrow
import app.benbow.app.resources.reminder_weekly
import app.benbow.app.resources.reminders
import app.benbow.app.resources.reminders_empty
import app.benbow.app.resources.reminders_past
import app.benbow.client.ReminderItem
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** Gentle, opt-in reminders (ux.md#psychology): upcoming first, a one-tap evening nudge, nothing nagging. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemindersScreen(services: AppServices, nav: Navigator, route: Route.Reminders) {
    val reminders by services.repo.reminders().collectAsStateWithLifecycle(null)
    val askPermission = rememberNotificationPermission()
    val nudgeText = stringResource(Res.string.reminder_nudge_text)
    val add: (String, String, String?) -> Unit = { text, at, repeat ->
        askPermission()
        services.scope.launch { services.repo.createReminder(text, at, repeat) }
    }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.reminders)) })
    }) { padding ->
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val all = reminders ?: return@Scaffold
        val (upcoming, past) = all.partition { it.nextAt(now) != null }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "form") { ReminderForm(services.today(), route.day, add) }
            if (all.none { it.repeat == ReminderItem.DAILY }) {
                item(key = "nudge") {
                    TextButton({ add(nudgeText, "${services.today()}T21:00", ReminderItem.DAILY) }, Modifier.padding(horizontal = 12.dp)) {
                        Text("🔔 " + stringResource(Res.string.reminder_nudge))
                    }
                }
            }
            if (all.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(Res.string.reminders_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(upcoming.sortedBy { it.nextAt(now) }, key = { it.id }) { ReminderRow(services, it, now) }
            if (past.isNotEmpty()) {
                item(key = "past") {
                    Text(
                        stringResource(Res.string.reminders_past),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                    )
                }
                items(past, key = { it.id }) { ReminderRow(services, it, now) }
            }
        }
    }
}

@Composable
private fun ReminderRow(services: AppServices, reminder: ReminderItem, now: LocalDateTime) {
    val next = reminder.nextAt(now)
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(reminder.text, style = MaterialTheme.typography.bodyLarge)
            val whenText = (next ?: runCatching { LocalDateTime.parse(reminder.at) }.getOrNull())?.let { at ->
                "${dayLabel(at.date.toString())}, ${at.time.toString().take(5)}"
            }.orEmpty()
            val repeat = when (reminder.repeat) {
                ReminderItem.DAILY -> " · " + stringResource(Res.string.reminder_daily).lowercase()
                ReminderItem.WEEKLY -> " · " + stringResource(Res.string.reminder_weekly).lowercase()
                else -> ""
            }
            Text(whenText + repeat, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (next != null && reminder.repeat == null) {
            TextButton({
                services.scope.launch { services.repo.setReminderDone(reminder.id, true) }
            }) { Text(stringResource(Res.string.reminder_done)) }
        }
        IconButton({
            services.scope.launch { services.repo.moveToTrash(reminder.id) }
        }) { Icon(Icons.Delete, stringResource(Res.string.reminder_delete)) }
    }
}

@Composable
private fun ReminderForm(today: String, preset: String?, onAdd: (String, String, String?) -> Unit) {
    val todayDate = LocalDate.parse(today)
    var text by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(preset ?: today) }
    var time by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf<String?>(null) }
    val parsed = parseTime(time)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, {
            text = it
        }, placeholder = { Text(stringResource(Res.string.reminder_hint)) }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(day == today, { day = today }, { Text(stringResource(Res.string.reminder_today)) })
            val tomorrow = todayDate.plus(1, DateTimeUnit.DAY).toString()
            FilterChip(day == tomorrow, { day = tomorrow }, { Text(stringResource(Res.string.reminder_tomorrow)) })
            if (preset != null && preset != today &&
                preset != tomorrow
            ) {
                FilterChip(day == preset, { day = preset }, { Text(dayLabel(preset)) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(repeat == null, { repeat = null }, { Text(stringResource(Res.string.reminder_once)) })
            FilterChip(repeat == ReminderItem.DAILY, { repeat = ReminderItem.DAILY }, { Text(stringResource(Res.string.reminder_daily)) })
            FilterChip(repeat == ReminderItem.WEEKLY, {
                repeat = ReminderItem.WEEKLY
            }, { Text(stringResource(Res.string.reminder_weekly)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                time,
                { time = it },
                placeholder = { Text(stringResource(Res.string.event_time_hint)) },
                singleLine = true,
                isError = time.isNotBlank() && parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button({
                onAdd(text.trim(), "${day}T$parsed", repeat)
                text = ""
                time = ""
            }, enabled = text.isNotBlank() && parsed != null) { Text(stringResource(Res.string.add)) }
        }
    }
}

/** Asks for the notification permission where the platform needs one (Android 13+); a no-op elsewhere. */
@Composable
internal expect fun rememberNotificationPermission(): () -> Unit
