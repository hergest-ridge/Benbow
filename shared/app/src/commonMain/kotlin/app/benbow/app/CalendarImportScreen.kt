package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.import_already
import app.benbow.app.resources.import_done
import app.benbow.app.resources.import_done_already
import app.benbow.app.resources.import_empty
import app.benbow.app.resources.import_events
import app.benbow.app.resources.import_failed
import app.benbow.app.resources.import_finish
import app.benbow.app.resources.import_go
import app.benbow.app.resources.import_hint
import app.benbow.app.resources.import_period_all
import app.benbow.app.resources.import_period_from
import app.benbow.app.resources.import_period_future
import app.benbow.app.resources.import_reading
import app.benbow.app.resources.import_title
import app.benbow.app.resources.import_undo
import app.benbow.app.resources.import_undone
import app.benbow.app.resources.import_unreadable
import app.benbow.app.resources.import_working
import app.benbow.app.resources.import_zones
import app.benbow.app.resources.months_genitive
import app.benbow.client.ByteSource
import app.benbow.client.SWATCHES
import app.benbow.client.calendar.CalendarRepository
import app.benbow.client.calendar.IcsImport
import app.benbow.client.calendar.ImportCalendar
import app.benbow.client.calendar.ImportChoice
import app.benbow.client.calendar.ImportResult
import app.benbow.client.calendar.summary
import app.benbow.client.readUpTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

internal sealed interface CalendarImportState {
    data object Idle : CalendarImportState

    data object Reading : CalendarImportState

    /** [reason] null: the file had no calendars in it. */
    data class Failed(val reason: String?) : CalendarImportState

    /** What the file holds, the colors new calendars would get, and the UIDs already here (for "уже есть"). */
    data class Preview(val calendars: List<ImportCalendar>, val colors: List<String>, val known: Set<String>) : CalendarImportState

    data object Importing : CalendarImportState

    data class Done(val result: ImportResult) : CalendarImportState

    data class Undone(val events: Int) : CalendarImportState
}

/**
 * Importing a calendar file (7.3d): read it without writing anything, show what's in it, then write the chosen
 * calendars as one batch that "Отменить импорт" takes back to the trash. Lives in the app scope, so leaving the
 * screen doesn't stop a running import.
 */
internal class CalendarImports(private val calendar: CalendarRepository, private val scope: CoroutineScope) {
    val state = MutableStateFlow<CalendarImportState>(CalendarImportState.Idle)

    fun open(name: String, open: () -> ByteSource) {
        state.value = CalendarImportState.Reading
        scope.launch {
            state.value = try {
                val bytes = open().use { it.readUpTo(MAX_FILE + 1) }
                require(bytes.size <= MAX_FILE) { "the file is over ${MAX_FILE / 1024 / 1024} MB" }
                val calendars = IcsImport.read(name, bytes).filter { it.events.isNotEmpty() || it.unreadable > 0 }
                if (calendars.isEmpty()) {
                    CalendarImportState.Failed(null)
                } else {
                    val used = calendar.calendars().first().map { it.color }.toMutableSet()
                    val colors = calendars.map { c ->
                        (
                            c.color?.takeIf {
                                it in SWATCHES
                            } ?: SWATCHES.firstOrNull { it !in used } ?: SWATCHES[used.size % SWATCHES.size]
                            ).also {
                            used +=
                                it
                        }
                    }
                    CalendarImportState.Preview(calendars, colors, calendar.knownUids())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CalendarImportState.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    fun import(choices: List<ImportChoice>, from: String?) {
        state.value = CalendarImportState.Importing
        scope.launch { state.value = CalendarImportState.Done(calendar.import(choices, from)) }
    }

    fun undo(batch: String) {
        state.value = CalendarImportState.Importing
        scope.launch { state.value = CalendarImportState.Undone(calendar.undoImport(batch)) }
    }

    fun close() {
        if (state.value != CalendarImportState.Importing) state.value = CalendarImportState.Idle
    }

    private companion object {
        const val MAX_FILE = 100 * 1024 * 1024
    }
}

private enum class Period { ALL, FUTURE, FROM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarImportScreen(services: AppServices, nav: Navigator) {
    val imports = services.calendarImports
    val state by imports.state.collectAsStateWithLifecycle()
    val close = {
        imports.close()
        nav.back()
    }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(close) }, title = { Text(stringResource(Res.string.import_title)) })
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                CalendarImportState.Idle -> Unit

                CalendarImportState.Reading -> Working(stringResource(Res.string.import_reading))

                CalendarImportState.Importing -> Working(stringResource(Res.string.import_working))

                is CalendarImportState.Failed -> Message(
                    s.reason?.let {
                        stringResource(Res.string.import_failed, it)
                    } ?: stringResource(Res.string.import_empty),
                )

                is CalendarImportState.Preview -> ImportPreview(s, today = services.today()) { choices, from ->
                    imports.import(choices, from)
                }

                is CalendarImportState.Done -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(Res.string.import_done, s.result.events, s.result.calendars),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (s.result.already > 0) Text(stringResource(Res.string.import_done_already, s.result.already))
                    Button(close, modifier = Modifier.hand()) { Text(stringResource(Res.string.import_finish)) }
                    OutlinedButton({
                        imports.undo(s.result.batch)
                    }, modifier = Modifier.hand()) { Text(stringResource(Res.string.import_undo)) }
                }

                is CalendarImportState.Undone -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(Res.string.import_undone, s.events))
                    Button(close, modifier = Modifier.hand()) { Text(stringResource(Res.string.import_finish)) }
                }
            }
        }
    }
}

@Composable
private fun Working(text: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text(text, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Message(text: String) {
    Text(text, modifier = Modifier.padding(24.dp))
}

/** The calendars in the file, like the calendars panel: a checkbox, a color (tap for the next), what it holds. */
@Composable
private fun ImportPreview(preview: CalendarImportState.Preview, today: String, onImport: (List<ImportChoice>, String?) -> Unit) {
    val checked = remember(preview) { mutableStateListOf(*preview.calendars.map { it.events.isNotEmpty() }.toTypedArray()) }
    val colors = remember(preview) { mutableStateListOf(*preview.colors.toTypedArray()) }
    var period by remember(preview) { mutableStateOf(Period.ALL) }
    var fromDay by remember(preview) { mutableStateOf(LocalDate.parse(today)) }
    var picking by remember { mutableStateOf(false) }
    val from = when (period) {
        Period.ALL -> null
        Period.FUTURE -> today
        Period.FROM -> fromDay.toString()
    }
    val summaries = remember(preview, from) { preview.calendars.map { it.summary(from, preview.known) } }
    val total = summaries.indices.filter { checked[it] }.sumOf { summaries[it].new }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(Res.string.import_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(period == Period.ALL, {
                    period = Period.ALL
                }, { Text(stringResource(Res.string.import_period_all)) }, modifier = Modifier.hand())
                FilterChip(period == Period.FUTURE, {
                    period = Period.FUTURE
                }, { Text(stringResource(Res.string.import_period_future)) }, modifier = Modifier.hand())
                FilterChip(period == Period.FROM, {
                    picking = true
                }, {
                    val month = stringArrayResource(Res.array.months_genitive)[fromDay.month.number - 1]
                    Text(stringResource(Res.string.import_period_from, "${fromDay.day} $month"))
                }, modifier = Modifier.hand())
            }
        }
        itemsIndexed(preview.calendars) { i, calendar ->
            val summary = summaries[i]
            Row(
                Modifier.fillMaxWidth().hand().clickable { checked[i] = !checked[i] }.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked[i], { checked[i] = it }, modifier = Modifier.hand())
                Box(Modifier.hand().clickable { colors[i] = SWATCHES[(SWATCHES.indexOf(colors[i]) + 1) % SWATCHES.size] }.padding(4.dp)) {
                    ColorDot(colors[i], size = 16)
                }
                Column(Modifier.weight(1f)) {
                    Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                    val years = summary.firstYear?.let { first ->
                        if (first ==
                            summary.lastYear
                        ) {
                            "$first"
                        } else {
                            "$first–${summary.lastYear}"
                        }
                    }
                    val parts = listOfNotNull(
                        pluralStringResource(Res.plurals.import_events, summary.events, summary.events),
                        years,
                        summary.already.takeIf { it > 0 }?.let { stringResource(Res.string.import_already, it) },
                        calendar.unreadable.takeIf { it > 0 }?.let { stringResource(Res.string.import_unreadable, it) },
                        calendar.unknownZones.takeIf { it > 0 }?.let { stringResource(Res.string.import_zones, it) },
                    )
                    Text(
                        parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Button(
                {
                    val choices = preview.calendars.indices.filter { checked[it] }.map { ImportChoice(preview.calendars[it], colors[it]) }
                    onImport(choices, from)
                },
                enabled = total > 0,
                modifier = Modifier.fillMaxWidth().padding(24.dp).hand(total > 0),
            ) { Text(pluralStringResource(Res.plurals.import_go, total, total)) }
        }
    }
    if (picking) {
        DayPicker(fromDay, onPick = {
            fromDay = it
            period = Period.FROM
            picking = false
        }, onDismiss = { picking = false })
    }
}
