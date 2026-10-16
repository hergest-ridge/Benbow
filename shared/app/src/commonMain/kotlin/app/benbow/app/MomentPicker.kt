package app.benbow.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.moment_next
import app.benbow.app.resources.moment_save
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** Moves an entry in time: pick the day, then the time (24 h). The date picker speaks UTC midnights. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MomentPicker(initial: LocalDateTime, onPick: (LocalDateTime) -> Unit, onDismiss: () -> Unit) {
    var day by remember { mutableStateOf<LocalDate?>(null) }
    val chosen = day
    if (chosen == null) {
        val dates = rememberDatePickerState(initialSelectedDateMillis = initial.date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton({
                    day =
                        dates.selectedDateMillis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
                            ?: initial.date
                }, modifier = Modifier.hand()) { Text(stringResource(Res.string.moment_next)) }
            },
            dismissButton = { TextButton(onDismiss, modifier = Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
        ) { DatePicker(dates) }
    } else {
        val time = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton({
                    onPick(LocalDateTime(chosen, LocalTime(time.hour, time.minute)))
                }, modifier = Modifier.hand()) { Text(stringResource(Res.string.moment_save)) }
            },
            dismissButton = { TextButton(onDismiss, modifier = Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
            text = { TimePicker(time) },
        )
    }
}
