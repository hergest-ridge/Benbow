package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.calendar_calendars
import app.benbow.app.resources.calendar_delete
import app.benbow.app.resources.calendar_export
import app.benbow.app.resources.calendar_my_events
import app.benbow.app.resources.calendar_name_hint
import app.benbow.app.resources.calendar_new
import app.benbow.app.resources.cancel
import app.benbow.app.resources.event_save
import app.benbow.client.SWATCHES
import app.benbow.client.calendar.CalendarItem
import org.jetbrains.compose.resources.stringResource

/**
 * The calendars, like Google's list: a checkbox shows or hides one (on every device), a tap renames or recolors it.
 * «Мои события» is built in and can't be deleted; a deleted calendar goes to the trash with its events.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarsSheet(
    calendars: List<CalendarItem>,
    onVisible: (CalendarItem, Boolean) -> Unit,
    onSave: (calendar: CalendarItem?, name: String?, color: String?) -> Unit,
    onDelete: (CalendarItem) -> Unit,
    onExport: (CalendarItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<CalendarItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(Res.string.calendar_calendars),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            calendars.forEach { calendar ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = calendar }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(calendar.visible, { onVisible(calendar, it) })
                    ColorDot(calendar.color, size = 14)
                    Text(calendarName(calendar), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        calendar.events.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }
            TextButton({ creating = true }, Modifier.padding(horizontal = 12.dp)) { Text(stringResource(Res.string.calendar_new)) }
        }
    }
    val target = editing
    if (target != null || creating) {
        CalendarDialog(
            calendar = target,
            suggestedColor = SWATCHES.firstOrNull { color -> calendars.none { it.color == color } } ?: SWATCHES.first(),
            onSave = { name, color -> onSave(target, name, color) },
            onDelete = target?.takeIf { !it.builtIn }?.let { { onDelete(it) } },
            onExport = target?.let { { onExport(it) } },
            onDismiss = {
                editing = null
                creating = false
            },
        )
    }
}

/** A new calendar ([calendar] null) or one being renamed and recolored; what didn't change comes back as null. */
@Composable
private fun CalendarDialog(
    calendar: CalendarItem?,
    suggestedColor: String,
    onSave: (String?, String?) -> Unit,
    onDelete: (() -> Unit)?,
    onExport: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val initialName = calendar?.let { it.name ?: stringResource(Res.string.calendar_my_events) }.orEmpty()
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(calendar?.color ?: suggestedColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    placeholder = { Text(stringResource(Res.string.calendar_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwatchPicker(color, noneLabel = null, onPick = { picked -> picked?.let { color = it } })
                if (onExport != null) {
                    TextButton({
                        onExport()
                        onDismiss()
                    }) { Text(stringResource(Res.string.calendar_export)) }
                }
                if (onDelete != null) {
                    TextButton({
                        onDelete()
                        onDismiss()
                    }) { Text(stringResource(Res.string.calendar_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton({
                onSave(
                    name.trim().takeIf {
                        calendar == null || it != initialName
                    },
                    color.takeIf { calendar == null || it != calendar.color },
                )
                onDismiss()
            }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.event_save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
