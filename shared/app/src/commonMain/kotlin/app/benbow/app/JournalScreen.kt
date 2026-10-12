package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.journal_empty_text
import app.benbow.app.resources.journal_empty_title
import app.benbow.app.resources.journal_title
import app.benbow.app.resources.menu
import app.benbow.app.resources.new_entry
import app.benbow.app.resources.quick_note_hint
import app.benbow.app.resources.send
import app.benbow.app.resources.server
import app.benbow.app.resources.trash
import app.benbow.client.EntrySummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

internal class DayGroup(val day: String, val entries: List<EntrySummary>)

internal class JournalViewModel(private val services: AppServices) : ViewModel() {
    /** Null until the first read from the local DB (milliseconds). */
    val days: StateFlow<List<DayGroup>?> = services.repo.journal()
        .map { entries -> entries.groupBy { it.day }.map { (day, items) -> DayGroup(day, items) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun quickNote(text: String) {
        if (text.isBlank()) return
        services.scope.launch { services.repo.createNote(services.today(), text) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JournalScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { JournalViewModel(services) }
    val days by vm.days.collectAsStateWithLifecycle()
    val status by services.sync.status.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    // Only a pull shows the refresh spinner; background syncs stay quiet.
    var refreshUntil by remember { mutableStateOf<Int?>(null) }
    val refreshing = refreshUntil?.let { status.completed < it } == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.journal_title)) },
                actions = {
                    SyncChip(status) { nav.open(Route.Server) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.More, stringResource(Res.string.menu)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(Res.string.trash)) }, onClick = {
                                menu = false
                                nav.open(Route.Trash)
                            })
                            DropdownMenuItem(text = { Text(stringResource(Res.string.server)) }, onClick = {
                                menu = false
                                nav.open(Route.Server)
                            })
                        }
                    }
                },
            )
        },
        bottomBar = {
            QuickNoteBar(
                onSend = vm::quickNote,
                onNewEntry = { nav.open(Route.Editor(null, services.today(), opened = services.now())) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshUntil = status.completed + 1 + if (status.syncing) 1 else 0
                services.sync.requestSync()
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val groups = days ?: return@PullToRefreshBox
            if (groups.isEmpty()) {
                EmptyJournal()
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                    groups.forEach { group ->
                        stickyHeader(key = "day:${group.day}") { DayHeader(group.day) }
                        items(group.entries, key = { it.id }) { entry ->
                            EntryCard(entry, onClick = { nav.open(Route.Editor(entry.id, entry.day)) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: String) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Text(
            dayLabel(day),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun EmptyJournal() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Res.string.journal_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(Res.string.journal_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Saved-messages style capture: type and send; with an empty field the button opens a full entry instead. */
@Composable
private fun QuickNoteBar(onSend: (String) -> Unit, onNewEntry: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(Res.string.quick_note_hint)) },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.weight(1f),
            )
            if (text.isBlank()) {
                IconButton(onNewEntry, Modifier.padding(bottom = 4.dp)) { Icon(Icons.Edit, stringResource(Res.string.new_entry)) }
            } else {
                IconButton(
                    onClick = {
                        onSend(text)
                        text = ""
                    },
                    modifier = Modifier.padding(bottom = 4.dp),
                ) { Icon(Icons.Send, stringResource(Res.string.send), tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}
