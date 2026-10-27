package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.ink_names
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.trackers_archived
import app.benbow.app.resources.trackers_column_add
import app.benbow.app.resources.trackers_columns
import app.benbow.app.resources.trackers_columns_empty
import app.benbow.app.resources.trackers_down
import app.benbow.app.resources.trackers_emoji
import app.benbow.app.resources.trackers_name
import app.benbow.app.resources.trackers_setup
import app.benbow.app.resources.trackers_type_check
import app.benbow.app.resources.trackers_type_check_about
import app.benbow.app.resources.trackers_type_choice
import app.benbow.app.resources.trackers_type_choice_about
import app.benbow.app.resources.trackers_type_count
import app.benbow.app.resources.trackers_type_count_about
import app.benbow.app.resources.trackers_type_note
import app.benbow.app.resources.trackers_type_note_about
import app.benbow.app.resources.trackers_type_scale
import app.benbow.app.resources.trackers_type_scale_about
import app.benbow.app.resources.trackers_type_time
import app.benbow.app.resources.trackers_type_time_about
import app.benbow.app.resources.trackers_unarchive
import app.benbow.app.resources.trackers_up
import app.benbow.app.resources.untitled
import app.benbow.client.IoDispatcher
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerKind
import app.benbow.core.tracker.TrackerRecord
import app.benbow.core.uuidV7
import app.benbow.ui.theme.BenbowPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * «Настроить трекер» (ADR-050, B5): its name and emoji, and its columns in order, each opening its own editor; ↑/↓
 * move one, «Добавить колонку» asks for a type first; archived columns wait at the end with «Вернуть из архива». Choices
 * are saved at once, texts a moment after typing stops and when the page closes. Every change is a field of the
 * tracker's or a column's record: no cell is ever rewritten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackerEditorScreen(services: AppServices, nav: Navigator, trackerId: String) {
    val repo = services.trackers
    val trackers by remember { repo.trackers() }.collectAsStateWithLifecycle(null)
    val tracker = trackers?.firstOrNull { it.id == trackerId }
    val allColumns by remember(trackerId) { repo.columns(trackerId) }.collectAsStateWithLifecycle(null)
    val write: TrackerWrite = { services.trackerWrites(it) }
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.trackers_setup)) })
    }) { padding ->
        val columns = allColumns
        if (tracker != null && columns != null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                TrackerForm(
                    tracker,
                    columns,
                    write,
                    onEdit = { editing = it },
                    onAdd = { adding = true },
                    onTrash = { name ->
                        write { moveToTrash(trackerId) }
                        services.messages.tryEmit(AppMessage.TrackerTrashed(trackerId, name))
                        nav.backTo { it !is Route.TrackerEditor && it !is Route.TrackerSheet }
                    },
                )
            }
        }
    }
    if (adding) {
        TypeChooser(onDismiss = { adding = false }) { type ->
            adding = false
            val taken = allColumns.orEmpty().map { it.record[ColumnField.COLOR] }
            val ink = nextInk(BenbowPalette.tokens, taken)
            scope.launch {
                val name = getString(type.label)
                editing = withContext(IoDispatcher) {
                    repo.addColumn(trackerId, newColumn(type, name, ink) { uuidV7(services.now()) })
                }
            }
        }
    }
    editing?.let { id ->
        allColumns?.firstOrNull { it.id == id }?.let { column -> ColumnEditor(services, column) { editing = null } }
    }
}

/** The tracker's name, emoji and columns; texts are saved from [TrackerDraft] a moment after typing stops. */
@Composable
private fun TrackerForm(
    tracker: TrackerRecord,
    columns: List<TrackerColumn>,
    write: TrackerWrite,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onTrash: (String) -> Unit,
) {
    var draft by remember(tracker.id) { mutableStateOf(TrackerDraft.of(tracker)) }
    val latest by rememberUpdatedState(draft)
    val current by rememberUpdatedState(tracker)
    val save = {
        val changes = latest.changes(current)
        if (changes.isNotEmpty()) write { updateTracker(current.id, changes) }
    }
    LaunchedEffect(draft) {
        delay(SAVE_DELAY_MILLIS)
        save()
    }
    DisposableEffect(Unit) { onDispose { save() } }
    val untitled = stringResource(Res.string.untitled)
    Column(
        Modifier.widthIn(max = READABLE).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            draft.name,
            { draft = draft.copy(name = it) },
            label = { Text(stringResource(Res.string.trackers_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            draft.emoji,
            { draft = draft.copy(emoji = it) },
            label = { Text(stringResource(Res.string.trackers_emoji)) },
            singleLine = true,
            modifier = Modifier.width(EMOJI_FIELD),
        )
        EmojiRow(TRACKER_EMOJI) { draft = draft.copy(emoji = it) }
        Section(stringResource(Res.string.trackers_columns)) {
            val live = columns.filter { !it.archived }
            if (live.isEmpty()) Hint(stringResource(Res.string.trackers_columns_empty))
            live.forEachIndexed { i, column ->
                ColumnRow(column, untitled, canUp = i > 0, canDown = i < live.lastIndex, onOpen = { onEdit(column.id) }) { delta ->
                    write { place(TrackerKind.COLUMN, column.id, live.filter { it.id != column.id }.map { it.record }, i + delta) }
                }
            }
            OutlinedButton(onAdd, Modifier.fillMaxWidth().hand()) {
                Icon(Icons.Add, null, Modifier.size(18.dp))
                Text(stringResource(Res.string.trackers_column_add), Modifier.padding(start = 8.dp))
            }
            val archived = columns.filter { it.archived }
            if (archived.isNotEmpty()) {
                Text(
                    stringResource(Res.string.trackers_archived),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                archived.forEach { column ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(columnTitle(column, untitled), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton({ write { updateColumn(column.id, mapOf(ColumnField.ARCHIVED to null)) } }, Modifier.hand()) {
                            Text(stringResource(Res.string.trackers_unarchive))
                        }
                    }
                }
            }
        }
        TextButton({ onTrash(trackerTitle(tracker, untitled)) }, Modifier.align(Alignment.End).hand()) {
            Text(stringResource(Res.string.move_to_trash), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A column in the editor: its emoji, name and type; a tap opens its editor, ↑/↓ move it. */
@Composable
private fun ColumnRow(
    column: TrackerColumn,
    untitled: String,
    canUp: Boolean,
    canDown: Boolean,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).hand().clickable(onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            column.record[ColumnField.ICON]?.let(::iconEmoji) ?: column.short.take(1),
            Modifier.width(40.dp),
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
        )
        Column(Modifier.weight(1f)) {
            Text(column.name.ifBlank { untitled }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            column.type?.let {
                Text(
                    stringResource(it.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton({ onMove(-1) }, enabled = canUp, modifier = Modifier.hand(canUp)) {
            Icon(Icons.ArrowUp, stringResource(Res.string.trackers_up), Modifier.size(20.dp))
        }
        IconButton({ onMove(1) }, enabled = canDown, modifier = Modifier.hand(canDown)) {
            Icon(Icons.ArrowDown, stringResource(Res.string.trackers_down), Modifier.size(20.dp))
        }
    }
}

/** «Добавить колонку»: the types, each with a line on what it is for. */
@Composable
private fun TypeChooser(onDismiss: () -> Unit, onPick: (ColumnType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.trackers_column_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                COLUMN_TYPES.forEach { type ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).hand().clickable { onPick(type) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text(stringResource(type.label), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(type.about),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Emoji offered in one tap. */
@Composable
internal fun EmojiRow(emoji: List<String>, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        emoji.forEach { each ->
            Box(Modifier.size(40.dp).clip(CircleShape).hand().clickable { onPick(each) }, contentAlignment = Alignment.Center) {
                Text(each, fontSize = 22.sp)
            }
        }
    }
}

/** The editor's inks (`BenbowPalette`): a column's marks, a choice's option. */
@Composable
internal fun InkPicker(selected: String?, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val names = stringArrayResource(Res.array.ink_names)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BenbowPalette.tokens.forEachIndexed { i, token ->
            val on = token == selected
            Box(
                Modifier.size(32.dp)
                    .clip(CircleShape)
                    .background(BenbowPalette.text(token, dark) ?: Color.Transparent)
                    .border(if (on) 3.dp else 1.dp, if (on) colors.onSurface else colors.outlineVariant, CircleShape)
                    .hand()
                    .clickable { onPick(token) }
                    .semantics {
                        contentDescription = names.getOrElse(i) { token }
                        this.selected = on
                    },
            )
        }
    }
}

/** A column's emoji and name, as lists of columns show it. */
internal fun columnTitle(column: TrackerColumn, untitled: String): String =
    listOfNotNull(column.record[ColumnField.ICON]?.let(::iconEmoji), column.name.ifBlank { untitled }).joinToString(" ")

internal val ColumnType.label: StringResource get() = when (this) {
    ColumnType.Check -> Res.string.trackers_type_check
    ColumnType.Count -> Res.string.trackers_type_count
    ColumnType.Scale -> Res.string.trackers_type_scale
    ColumnType.Choice -> Res.string.trackers_type_choice
    ColumnType.Time -> Res.string.trackers_type_time
    ColumnType.Note -> Res.string.trackers_type_note
}

private val ColumnType.about: StringResource get() = when (this) {
    ColumnType.Check -> Res.string.trackers_type_check_about
    ColumnType.Count -> Res.string.trackers_type_count_about
    ColumnType.Scale -> Res.string.trackers_type_scale_about
    ColumnType.Choice -> Res.string.trackers_type_choice_about
    ColumnType.Time -> Res.string.trackers_type_time_about
    ColumnType.Note -> Res.string.trackers_type_note_about
}

/** Texts are saved this long after typing stops (and when the editor closes). */
internal const val SAVE_DELAY_MILLIS = 600L

internal val EMOJI_FIELD = 96.dp
