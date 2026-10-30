package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.link_address
import app.benbow.app.resources.link_bad_address
import app.benbow.app.resources.link_card_title
import app.benbow.app.resources.link_done
import app.benbow.app.resources.link_entry_none
import app.benbow.app.resources.link_entry_search
import app.benbow.app.resources.link_from_clipboard
import app.benbow.app.resources.link_sheet_card
import app.benbow.app.resources.link_sheet_title
import app.benbow.app.resources.link_tab_entry
import app.benbow.app.resources.link_tab_web
import app.benbow.app.resources.link_unlink
import app.benbow.app.resources.link_words
import app.benbow.client.EntrySummary
import app.benbow.client.SearchQuery
import app.benbow.core.text.Links
import app.benbow.core.text.Mark
import app.benbow.ui.editor.LinkRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The clipboard's text, read only when asked (a tap): on Android reading it shows the system's notice. Null: no clipboard here. */
@Composable
internal expect fun rememberReadClipboard(): (suspend () -> String?)?

/**
 * The link sheet (ADR-038): an address (with the clipboard's a tap away) and the link's words, or an entry found by
 * search; for a card, its address and title. [onDone] gets the link (null: take it off) and the words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkSheet(services: AppServices, request: LinkRequest, onDone: (mark: Mark?, words: String?) -> Unit, onDismiss: () -> Unit) {
    val card = request.cardKey != null
    val content = @Composable {
        var entryTab by remember { mutableStateOf(request.current is Mark.EntryLink) }
        val startAddress = (request.current as? Mark.Link)?.href.orEmpty()
        var address by remember { mutableStateOf(TextFieldValue(startAddress, TextRange(startAddress.length))) }
        var words by remember { mutableStateOf(request.words) }
        var bad by remember { mutableStateOf(false) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(entryTab) { focus.requestFocus() }
        val readClipboard = rememberReadClipboard()
        val scope = rememberCoroutineScope()
        val done = {
            val href = Links.fromField(address.text)
            if (href == null) {
                bad = true
            } else {
                onDone(Mark.Link(href), words)
            }
        }
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (card) Res.string.link_sheet_card else Res.string.link_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!card) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!entryTab, {
                        entryTab = false
                    }, { Text(stringResource(Res.string.link_tab_web)) }, modifier = Modifier.hand())
                    FilterChip(entryTab, {
                        entryTab = true
                    }, { Text(stringResource(Res.string.link_tab_entry)) }, modifier = Modifier.hand())
                }
            }
            if (entryTab && !card) {
                EntryPicker(services, focus) { entry, label ->
                    onDone(Mark.EntryLink(entry.id), words.ifBlank { label })
                }
            } else {
                OutlinedTextField(
                    address,
                    {
                        address = it
                        bad = false
                    },
                    label = { Text(stringResource(Res.string.link_address)) },
                    placeholder = { Text("https://") },
                    isError = bad,
                    supportingText = if (bad) ({ Text(stringResource(Res.string.link_bad_address)) }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (readClipboard != null) {
                    AssistChip(onClick = {
                        scope.launch {
                            val text = readClipboard()?.trim().orEmpty()
                            val href = Links.parse(text) ?: Links.fromField(text)
                            if (href != null) address = TextFieldValue(href, TextRange(href.length))
                        }
                    }, label = { Text(stringResource(Res.string.link_from_clipboard)) }, modifier = Modifier.hand())
                }
                OutlinedTextField(
                    words,
                    { words = it },
                    label = { Text(stringResource(if (card) Res.string.link_card_title else Res.string.link_words)) },
                    placeholder = {
                        Links.fromField(address.text)?.let { Text(Links.display(it), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { done() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row {
                if (request.current != null && !card) {
                    TextButton({ onDone(null, null) }, modifier = Modifier.hand()) { Text(stringResource(Res.string.link_unlink)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss, modifier = Modifier.hand()) { Text(stringResource(Res.string.cancel)) }
                if (!entryTab || card) Button(done, modifier = Modifier.hand()) { Text(stringResource(Res.string.link_done)) }
            }
        }
    }
    if (LocalWide.current) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 480.dp)) {
                Column(Modifier.padding(top = 20.dp)) { content() }
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            content()
        }
    }
}

/** Search as in the Search tab; a tap picks the entry. */
@Composable
private fun EntryPicker(services: AppServices, focus: FocusRequester, onPick: (EntrySummary, label: String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results by remember(query) {
        if (query.isBlank()) flowOf(emptyList()) else services.repo.search(SearchQuery(query.trim()))
    }.collectAsStateWithLifecycle(emptyList())
    OutlinedTextField(
        query,
        { query = it },
        label = { Text(stringResource(Res.string.link_entry_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    if (query.isNotBlank() && results.isEmpty()) {
        Text(
            stringResource(Res.string.link_entry_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items(results.take(30), key = { it.id }) { entry ->
            val label = entry.shownTitle()
            Column(Modifier.fillMaxWidth().hand().clickable { onPick(entry, label) }.padding(vertical = 8.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dayLabel(entry.day), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** An entry under a curtain or quiet (ADR-031) shows by its day only, never its words. */
@Composable
private fun EntrySummary.shownTitle(): String = if (veil != null) dayLabel(day) else title.ifBlank { dayLabel(day) }
