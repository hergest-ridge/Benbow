package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.category_description_hint
import app.benbow.app.resources.category_down
import app.benbow.app.resources.category_emoji
import app.benbow.app.resources.category_empty
import app.benbow.app.resources.category_gentle_hint
import app.benbow.app.resources.category_trash_text
import app.benbow.app.resources.category_trash_title
import app.benbow.app.resources.category_up
import app.benbow.app.resources.color_default
import app.benbow.app.resources.gentle
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.tag_color
import app.benbow.app.resources.tag_description
import app.benbow.app.resources.tag_name
import app.benbow.app.resources.tag_picker_done
import app.benbow.app.resources.tags
import app.benbow.client.EMOTIONS
import app.benbow.client.GentleRule
import app.benbow.client.TagRef
import app.benbow.client.describeCategory
import app.benbow.client.renameCategory
import app.benbow.client.ruleFor
import app.benbow.client.setCategoryColor
import app.benbow.client.setCategoryEmoji
import app.benbow.client.setCategoryOrder
import app.benbow.client.setGentleLevel
import app.benbow.client.tags
import app.benbow.client.trashCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A category's editor (7.9 T4): name, a few words, an emoji, its color, Бережно's level for all its tags, its place
 * among the others, its tags, and the trash («Эмоции» stays: check-ins put feelings there). Choices are saved at once;
 * the texts when the editor closes (or on Enter).
 */
@Composable
internal fun CategoryEditor(services: AppServices, categoryId: String, onDismiss: () -> Unit) {
    val repo = services.repo
    val registry = LocalTags.current
    val rules by remember { repo.gentleRules() }.collectAsStateWithLifecycle(null)
    val tags by remember { repo.tags() }.collectAsStateWithLifecycle(emptyList())
    val order = registry.categories.map { it.id }
    if (categoryId !in order) {
        // In the trash meanwhile: close. Just made: the registry catches up in a moment.
        val gone = categoryId in registry.categoryTrash
        LaunchedEffect(gone) {
            if (!gone) delay(CATCH_UP_MILLIS)
            onDismiss()
        }
        return
    }
    val current = registry.categoryName(categoryId)
    var name by remember(categoryId) { mutableStateOf(current) }
    var description by remember(categoryId) { mutableStateOf(registry.categoryDescriptions[categoryId].orEmpty()) }
    var emoji by remember(categoryId) { mutableStateOf(registry.categoryEmoji[categoryId].orEmpty()) }
    var trashing by remember { mutableStateOf(false) }
    val write = { action: () -> Unit -> services.scope.launch { action() } }
    val save = {
        if (name.isNotBlank() && name.trim() != current) write { repo.renameCategory(categoryId, name) }
        if (description.trim() !=
            registry.categoryDescriptions[categoryId].orEmpty()
        ) {
            write { repo.describeCategory(categoryId, description) }
        }
        if (emoji.trim() != registry.categoryEmoji[categoryId].orEmpty()) write { repo.setCategoryEmoji(categoryId, emoji) }
    }
    val close = {
        save()
        onDismiss()
    }
    val move = { by: Int ->
        val i = order.indexOf(categoryId)
        val j = (i + by).coerceIn(order.indices)
        if (i != j) write { repo.setCategoryOrder(order.toMutableList().apply { add(j, removeAt(i)) }) }
    }
    PickerFrame(close) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(emoji.trim(), name.trim().ifEmpty { current }).filter { it.isNotEmpty() }.joinToString(" "),
                    style = MaterialTheme.typography.titleLarge,
                    color = markColor(registry.categoryColor(categoryId)) ?: MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(close, Modifier.hand()) { Text(stringResource(Res.string.tag_picker_done)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(stringResource(Res.string.tag_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    emoji,
                    { emoji = it.take(EMOJI_MAX) },
                    label = { Text(stringResource(Res.string.category_emoji)) },
                    singleLine = true,
                    modifier = Modifier.width(96.dp),
                )
            }
            OutlinedTextField(
                description,
                { description = it },
                label = { Text(stringResource(Res.string.tag_description)) },
                placeholder = { Text(stringResource(Res.string.category_description_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Section(stringResource(Res.string.tag_color)) {
                SwatchPicker(registry.categoryColors[categoryId], stringResource(Res.string.color_default), {
                    write { repo.setCategoryColor(categoryId, it) }
                })
            }
            Section(stringResource(Res.string.gentle)) {
                Hint(stringResource(Res.string.category_gentle_hint))
                val level = rules?.ruleFor(GentleRule.CATEGORY, categoryId)?.level ?: GentleRule.OFF
                GentleLevelChips(level) { write { repo.setGentleLevel(GentleRule.CATEGORY, categoryId, current, it) } }
            }
            Section(stringResource(Res.string.tags)) {
                val mine = tags.filter { it.category == categoryId }
                if (mine.isEmpty()) {
                    Hint(stringResource(Res.string.category_empty))
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        mine.forEach { TagChip(TagRef(it.id, it.name)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({
                    move(-1)
                }, Modifier.hand(), enabled = order.first() != categoryId) { Text(stringResource(Res.string.category_up)) }
                TextButton({
                    move(1)
                }, Modifier.hand(), enabled = order.last() != categoryId) { Text(stringResource(Res.string.category_down)) }
                if (categoryId != EMOTIONS) {
                    TextButton({ trashing = true }, Modifier.hand()) {
                        Text(stringResource(Res.string.move_to_trash), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (trashing) {
        AlertDialog(
            onDismissRequest = { trashing = false },
            title = { Text(stringResource(Res.string.category_trash_title, current)) },
            text = { Text(stringResource(Res.string.category_trash_text)) },
            confirmButton = {
                TextButton({
                    trashing = false
                    write { if (repo.trashCategory(categoryId)) services.messages.tryEmit(AppMessage.CategoryTrashed(categoryId, current)) }
                    onDismiss()
                }, Modifier.hand()) { Text(stringResource(Res.string.move_to_trash)) }
            },
            dismissButton = { TextButton({ trashing = false }, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** An emoji may be several characters (skin tones, flags, joined people). */
private const val EMOJI_MAX = 16
