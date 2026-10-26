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
import androidx.compose.ui.unit.sp
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
import app.benbow.app.resources.search
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

@Composable
internal fun DayHeader(day: String) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Text(
            dayLabel(day),
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        )
    }
}

@Composable
internal fun QuickNoteBar(
    services: AppServices,
    onSend: (String) -> Unit,
    onNewEntry: (() -> Unit)? = null,
    hint: String? = null,
    /** Inside a thread (a wide window's post), not a bar at the foot: no backing of its own, no system insets. */
    inline: Boolean = false,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val dictation = rememberFieldDictation({ text }) { text = it }
    Surface(color = if (inline) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            (if (inline) Modifier else Modifier.navigationBarsPadding().imePadding()).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint ?: stringResource(Res.string.quick_note_hint)) },
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
            DictationKey(services, dictation, Modifier.padding(start = 6.dp, bottom = 1.dp))
            if (text.isBlank()) {
                if (onNewEntry != null) {
                    IconButton(onNewEntry, Modifier.padding(bottom = 4.dp).hand()) {
                        Icon(Icons.Edit, stringResource(Res.string.new_entry))
                    }
                }
            } else {
                IconButton(
                    onClick = {
                        onSend(text)
                        text = ""
                    },
                    modifier = Modifier.padding(bottom = 4.dp).hand(),
                ) { Icon(Icons.Send, stringResource(Res.string.send), tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}
