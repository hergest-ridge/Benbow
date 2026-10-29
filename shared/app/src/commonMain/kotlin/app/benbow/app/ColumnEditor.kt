package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.tag_picker_done
import app.benbow.app.resources.trackers_archive
import app.benbow.app.resources.trackers_archive_hint
import app.benbow.app.resources.trackers_day_by
import app.benbow.app.resources.trackers_day_end
import app.benbow.app.resources.trackers_day_start
import app.benbow.app.resources.trackers_emoji
import app.benbow.app.resources.trackers_goal
import app.benbow.app.resources.trackers_goal_day
import app.benbow.app.resources.trackers_goal_hint
import app.benbow.app.resources.trackers_goal_month
import app.benbow.app.resources.trackers_goal_week
import app.benbow.app.resources.trackers_hours
import app.benbow.app.resources.trackers_hours_from
import app.benbow.app.resources.trackers_hours_hint
import app.benbow.app.resources.trackers_hours_to
import app.benbow.app.resources.trackers_ink
import app.benbow.app.resources.trackers_name
import app.benbow.app.resources.trackers_option_add
import app.benbow.app.resources.trackers_option_color
import app.benbow.app.resources.trackers_option_name
import app.benbow.app.resources.trackers_option_remove
import app.benbow.app.resources.trackers_options
import app.benbow.app.resources.trackers_range
import app.benbow.app.resources.trackers_short
import app.benbow.app.resources.trackers_timer_one
import app.benbow.app.resources.trackers_type
import app.benbow.app.resources.trackers_type_hint
import app.benbow.app.resources.trackers_unarchive
import app.benbow.app.resources.trackers_unit
import app.benbow.app.resources.untitled
import app.benbow.core.tracker.ChoiceOption
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.DayBy
import app.benbow.core.tracker.GoalPer
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.uuidV7
import app.benbow.ui.theme.BenbowPalette
import app.benbow.ui.theme.BenbowTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A column's editor (ADR-050, B5): its header texts and emoji, its ink, its type and what the type asks for (a unit and
 * a goal, a rating's range, a choice's options with their colors and emoji, the sleep sheet's hours, which day a span
 * counts for, one timer at a time), and the archive. Choices are saved at once, texts a moment after typing stops and
 * when the editor closes. Changing the type rewrites no cell: values read again if the type comes back.
 */
@Composable
internal fun ColumnEditor(services: AppServices, column: TrackerColumn, onDismiss: () -> Unit) {
    val write: TrackerWrite = { services.trackerWrites(it) }
    val newId = { uuidV7(services.now()) }
    var draft by remember(column.id) { mutableStateOf(ColumnDraft.of(column)) }
    val latest by rememberUpdatedState(draft)
    val current by rememberUpdatedState(column)
    val save = {
        val changes = latest.changes(current)
        if (changes.isNotEmpty()) write { updateColumn(current.id, changes) }
    }
    LaunchedEffect(draft) {
        delay(SAVE_DELAY_MILLIS)
        save()
    }
    DisposableEffect(Unit) { onDispose { save() } }
    val close = {
        save()
        onDismiss()
    }
    val set = { fields: Map<String, String?> -> write { updateColumn(column.id, fields) } }
    val type = column.type
    PickerFrame(close) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    columnTitle(column, stringResource(Res.string.untitled)),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(close, Modifier.hand()) { Text(stringResource(Res.string.tag_picker_done)) }
            }
            OutlinedTextField(
                draft.name,
                { draft = draft.copy(name = it) },
                label = { Text(stringResource(Res.string.trackers_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().testTag("column-name"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    draft.short,
                    { draft = draft.copy(short = it) },
                    label = { Text(stringResource(Res.string.trackers_short)) },
                    placeholder = { Text(draft.name, maxLines = 1) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    draft.emoji,
                    { draft = draft.copy(emoji = it) },
                    label = { Text(stringResource(Res.string.trackers_emoji)) },
                    singleLine = true,
                    modifier = Modifier.width(EMOJI_FIELD),
                )
            }
            EmojiRow(COLUMN_EMOJI) { draft = draft.copy(emoji = it) }
            Section(stringResource(Res.string.trackers_ink)) {
                InkPicker(column.record[ColumnField.COLOR]) { set(mapOf(ColumnField.COLOR to it)) }
            }
            Section(stringResource(Res.string.trackers_type)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    COLUMN_TYPES.forEach { each ->
                        FilterChip(type == each, {
                            if (type != each) {
                                val fields = typeFields(column, each, newId)
                                // The options written with the type are the draft's too, so a later save keeps them.
                                fields[ColumnField.OPTIONS]?.let { draft = draft.copy(options = ChoiceOption.list(it)) }
                                set(fields)
                            }
                        }, { Text(stringResource(each.label)) }, modifier = Modifier.hand())
                    }
                }
                Hint(stringResource(Res.string.trackers_type_hint))
            }
            when (type) {
                ColumnType.Count -> {
                    OutlinedTextField(
                        draft.unit,
                        { draft = draft.copy(unit = it) },
                        label = { Text(stringResource(Res.string.trackers_unit)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GoalFields(draft.goal, column.goalPer, GoalPer.entries, { draft = draft.copy(goal = it) }) {
                        set(mapOf(ColumnField.GOAL_PER to it.token))
                    }
                }

                ColumnType.Check -> {
                    val per = column.goalPer.takeIf { it != GoalPer.Day } ?: GoalPer.Week
                    GoalFields(draft.goal, per, listOf(GoalPer.Week, GoalPer.Month), { draft = draft.copy(goal = it) }) {
                        set(mapOf(ColumnField.GOAL_PER to it.token))
                    }
                }

                ColumnType.Scale -> Section(stringResource(Res.string.trackers_range)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SCALE_RANGES.forEach { (low, high) ->
                            FilterChip(column.min == low && column.max == high, {
                                set(mapOf(ColumnField.MIN to "$low", ColumnField.MAX to "$high"))
                            }, { Text("$low–$high") }, modifier = Modifier.hand())
                        }
                    }
                }

                ColumnType.Choice -> OptionsEditor(draft.options, newId) { draft = draft.copy(options = it) }

                ColumnType.Time -> TimeFields(column, draft, { draft = it }, set)

                ColumnType.Note, null -> Unit
            }
            val archived = column.archived
            Hint(stringResource(Res.string.trackers_archive_hint))
            TextButton({
                set(mapOf(ColumnField.ARCHIVED to if (archived) null else "true"))
                if (!archived) close()
            }, Modifier.hand()) { Text(stringResource(if (archived) Res.string.trackers_unarchive else Res.string.trackers_archive)) }
        }
    }
}

/** A goal and what it counts in: a day, a week or a month ([pers] offered). It only ever fills once reached. */
@Composable
private fun GoalFields(goal: String, per: GoalPer, pers: List<GoalPer>, onGoal: (String) -> Unit, onPer: (GoalPer) -> Unit) {
    Section(stringResource(Res.string.trackers_goal)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                goal,
                onGoal,
                singleLine = true,
                isError = goal.isNotBlank() && typedNumber(goal) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(GOAL_FIELD),
            )
            pers.forEach { each ->
                FilterChip(per == each, { onPer(each) }, { Text(stringResource(each.label)) }, modifier = Modifier.hand())
            }
        }
        Hint(stringResource(Res.string.trackers_goal_hint))
    }
}

/** A choice's options: each with its color, emoji and name; «Убрать» leaves the cells that hold it as they are. */
@Composable
private fun OptionsEditor(options: List<ChoiceOption>, newId: () -> String, onChange: (List<ChoiceOption>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = BenbowTheme.colors.dark
    Section(stringResource(Res.string.trackers_options)) {
        options.forEachIndexed { i, option ->
            val change = { next: ChoiceOption -> onChange(options.mapIndexed { j, each -> if (j == i) next else each }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                var picking by remember { mutableStateOf(false) }
                Box {
                    Box(
                        Modifier.size(28.dp)
                            .clip(CircleShape)
                            .background(option.color?.let { BenbowTheme.colors.tokens.mark(it, dark) } ?: colors.outlineVariant)
                            .hand()
                            .clickable { picking = true }
                            .semantics { contentDescription = option.name },
                    )
                    DropdownMenu(picking, { picking = false }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(Res.string.trackers_option_color), style = MaterialTheme.typography.labelLarge)
                            InkPicker(option.color, Modifier.padding(top = 8.dp).width(INK_MENU)) {
                                picking = false
                                change(option.copy(color = it))
                            }
                        }
                    }
                }
                OutlinedTextField(
                    option.icon?.let(::iconEmoji).orEmpty(),
                    { change(option.copy(icon = emojiToken(it))) },
                    singleLine = true,
                    modifier = Modifier.width(OPTION_EMOJI),
                )
                OutlinedTextField(
                    option.name,
                    { change(option.copy(name = it)) },
                    placeholder = { Text(stringResource(Res.string.trackers_option_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton({ onChange(options.filterIndexed { j, _ -> j != i }) }, Modifier.hand()) {
                    Icon(Icons.Close, stringResource(Res.string.trackers_option_remove))
                }
            }
        }
        TextButton({
            onChange(options + ChoiceOption(newId(), "", nextInk(BenbowPalette.tokens, options.map { it.color })))
        }, Modifier.hand()) { Text(stringResource(Res.string.trackers_option_add)) }
    }
}

/** A time column: the sleep sheet's hours (none: a day's total), which day a span counts for, one timer at a time. */
@Composable
private fun TimeFields(column: TrackerColumn, draft: ColumnDraft, onDraft: (ColumnDraft) -> Unit, set: (Map<String, String?>) -> Unit) {
    Section(stringResource(Res.string.trackers_hours)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                draft.from,
                { onDraft(draft.copy(from = it)) },
                label = { Text(stringResource(Res.string.trackers_hours_from)) },
                placeholder = { Text("21:00") },
                singleLine = true,
                isError = draft.from.isNotBlank() && typedClock(draft.from) == null,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                draft.to,
                { onDraft(draft.copy(to = it)) },
                label = { Text(stringResource(Res.string.trackers_hours_to)) },
                placeholder = { Text("11:00") },
                singleLine = true,
                isError = draft.to.isNotBlank() && typedClock(draft.to) == null,
                modifier = Modifier.weight(1f),
            )
        }
        Hint(stringResource(Res.string.trackers_hours_hint))
    }
    Section(stringResource(Res.string.trackers_day_by)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(DayBy.Start to Res.string.trackers_day_start, DayBy.End to Res.string.trackers_day_end).forEach { (by, label) ->
                FilterChip(column.dayBy == by, {
                    set(mapOf(ColumnField.DAY_BY to by.token))
                }, { Text(stringResource(label)) }, Modifier.hand())
            }
        }
    }
    val one = column.record[ColumnField.TIMER] == TIMER_ONE
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.trackers_timer_one), Modifier.weight(1f))
        Switch(one, { on -> set(mapOf(ColumnField.TIMER to if (on) TIMER_ONE else null)) }, Modifier.hand())
    }
}

private val GoalPer.label: StringResource get() = when (this) {
    GoalPer.Day -> Res.string.trackers_goal_day
    GoalPer.Week -> Res.string.trackers_goal_week
    GoalPer.Month -> Res.string.trackers_goal_month
}

/** `timer = one`: starting this column's timer stops the tracker's others. */
private const val TIMER_ONE = "one"

private val GOAL_FIELD = 96.dp
private val OPTION_EMOJI = 72.dp
private val INK_MENU = 216.dp
