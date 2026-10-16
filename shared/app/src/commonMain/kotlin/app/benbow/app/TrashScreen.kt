package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.restore
import app.benbow.app.resources.trash
import app.benbow.app.resources.trash_calendar
import app.benbow.app.resources.trash_calendar_item
import app.benbow.app.resources.trash_empty
import app.benbow.app.resources.trash_hint
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The journal's trash, then the calendar's (ADR-027): deleted events and whole calendars come back from here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashScreen(services: AppServices, nav: Navigator) {
    val entries by services.repo.trash().collectAsStateWithLifecycle(null)
    val events by services.calendar.trashedEvents().collectAsStateWithLifecycle(emptyList())
    val calendars by services.calendar.trashedCalendars().collectAsStateWithLifecycle(emptyList())
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.trash)) })
    }) { padding ->
        val items = entries ?: return@Scaffold
        if (items.isEmpty() && events.isEmpty() && calendars.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.trash_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(Res.string.trash_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            items(items, key = { it.id }) { entry ->
                EntryCard(entry, onClick = { nav.open(Route.Editor(entry.id, entry.day)) }) {
                    TextButton({
                        services.scope.launch { services.repo.restore(entry.id) }
                    }, modifier = Modifier.hand()) { Text(stringResource(Res.string.restore)) }
                }
            }
            if (events.isNotEmpty() || calendars.isNotEmpty()) {
                item(key = "calendar") {
                    Text(
                        stringResource(Res.string.trash_calendar),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
            }
            items(calendars, key = { "c" + it.id }) { calendar ->
                TrashRow(stringResource(Res.string.trash_calendar_item, calendarName(calendar)), null) {
                    services.scope.launch { services.calendar.restoreCalendar(calendar.id) }
                }
            }
            items(events, key = { "e" + it.id }) { event ->
                TrashRow(event.title, dayLabel(event.day)) { services.scope.launch { services.calendar.restore(event.id) } }
            }
        }
    }
}

@Composable
private fun TrashRow(title: String, detail: String?, onRestore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TextButton(onRestore, modifier = Modifier.hand()) { Text(stringResource(Res.string.restore)) }
    }
}
