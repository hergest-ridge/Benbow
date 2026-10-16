package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.category_new
import app.benbow.app.resources.color_as_category
import app.benbow.app.resources.gentle
import app.benbow.app.resources.gentle_count
import app.benbow.app.resources.gentle_levels
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.tag_archived
import app.benbow.app.resources.tag_archived_hint
import app.benbow.app.resources.tag_category
import app.benbow.app.resources.tag_category_new
import app.benbow.app.resources.tag_category_rule
import app.benbow.app.resources.tag_color
import app.benbow.app.resources.tag_description
import app.benbow.app.resources.tag_description_hint
import app.benbow.app.resources.tag_gentle_hint
import app.benbow.app.resources.tag_merge
import app.benbow.app.resources.tag_merge_do
import app.benbow.app.resources.tag_merge_pick
import app.benbow.app.resources.tag_merge_text
import app.benbow.app.resources.tag_merge_title
import app.benbow.app.resources.tag_merge_with_it
import app.benbow.app.resources.tag_name
import app.benbow.app.resources.tag_name_taken
import app.benbow.app.resources.tag_picker_done
import app.benbow.app.resources.tag_trash_text
import app.benbow.app.resources.tag_trash_title
import app.benbow.app.resources.tags_uncategorized
import app.benbow.client.GentleRule
import app.benbow.client.TagCount
import app.benbow.client.TagRef
import app.benbow.client.archiveTag
import app.benbow.client.createCategory
import app.benbow.client.describeTag
import app.benbow.client.mergeTags
import app.benbow.client.renameTag
import app.benbow.client.ruleFor
import app.benbow.client.setGentleLevel
import app.benbow.client.setTagCategory
import app.benbow.client.setTagColor
import app.benbow.client.tagName
import app.benbow.client.tags
import app.benbow.client.trashTag
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * A tag's editor (7.9 T4): its name, a few words about it, category, color, Бережно's level and the archive; merging
 * into another tag and the trash at the bottom (each with a "Вернуть" after). Choices are saved at once; the name and
 * the description when the editor closes (or on Enter). Every change is a registry field: entries never change.
 */
@Composable
internal fun TagEditor(services: AppServices, tagId: String, onDismiss: () -> Unit) {
    val repo = services.repo
    val tags by remember { repo.tags() }.collectAsStateWithLifecycle(null)
    val rules by remember { repo.gentleRules() }.collectAsStateWithLifecycle(null)
    val registry = LocalTags.current
    val all = tags ?: return
    val tag = all.firstOrNull { it.id == tagId }
    if (tag == null) {
        LaunchedEffect(Unit) { onDismiss() } // merged or in the trash meanwhile
        return
    }
    var name by remember(tagId) { mutableStateOf(tag.name) }
    var description by remember(tagId) { mutableStateOf(registry.description(tagId).orEmpty()) }
    var newCategory by remember { mutableStateOf(false) }
    var mergeTarget by remember { mutableStateOf<TagCount?>(null) }
    var pickingTarget by remember { mutableStateOf(false) }
    var trashing by remember { mutableStateOf(false) }
    val write = { action: () -> Unit -> services.scope.launch { action() } }
    val save = {
        val clean = tagName(name)
        if (clean.isNotEmpty() && clean != tag.name) write { repo.renameTag(tagId, clean) }
        if (description.trim() != registry.description(tagId).orEmpty()) write { repo.describeTag(tagId, description) }
    }
    val close = {
        save()
        onDismiss()
    }
    val twin = tagName(name).takeIf { it.isNotEmpty() }?.let { typed ->
        all.firstOrNull {
            it.id != tagId &&
                it.name.equals(typed, ignoreCase = true)
        }
    }
    PickerFrame(close) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TagChip(TagRef(tag.id, tagName(name).ifEmpty { tag.name }))
                Spacer(Modifier.width(10.dp))
                Text(
                    pluralStringResource(Res.plurals.gentle_count, tag.uses, tag.uses),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(close, Modifier.hand()) { Text(stringResource(Res.string.tag_picker_done)) }
            }
            OutlinedTextField(
                name,
                { name = it },
                label = { Text(stringResource(Res.string.tag_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )
            twin?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.tag_name_taken, it.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton({ mergeTarget = it }, Modifier.hand()) { Text(stringResource(Res.string.tag_merge_with_it)) }
                }
            }
            OutlinedTextField(
                description,
                { description = it },
                label = { Text(stringResource(Res.string.tag_description)) },
                placeholder = { Text(stringResource(Res.string.tag_description_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Section(stringResource(Res.string.tag_category)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val options = listOf<Pair<String?, String>>(null to stringResource(Res.string.tags_uncategorized)) +
                        registry.categories.map { it.id to it.name }
                    options.forEach { (id, label) ->
                        FilterChip(tag.category == id, { write { repo.setTagCategory(tagId, id) } }, { Text(label) }, Modifier.hand())
                    }
                    FilterChip(false, { newCategory = true }, { Text(stringResource(Res.string.tag_category_new)) }, Modifier.hand())
                }
                // A rule on the category covers this tag's entries too: say so, in Бережно's words.
                val category = tag.category
                val ruled = category?.let { rules?.ruleFor(GentleRule.CATEGORY, it) }?.takeIf { it.veil != null }
                if (category != null && ruled != null) {
                    val level = stringArrayResource(Res.array.gentle_levels)[LEVEL_INDEX.getValue(ruled.level)]
                    Hint(stringResource(Res.string.tag_category_rule, registry.categoryName(category), level.lowercase()))
                }
            }
            Section(stringResource(Res.string.tag_color)) {
                SwatchPicker(registry.ownColor(tagId), stringResource(Res.string.color_as_category), {
                    write { repo.setTagColor(tagId, it) }
                })
            }
            Section(stringResource(Res.string.gentle)) {
                Hint(stringResource(Res.string.tag_gentle_hint))
                val level = rules?.ruleFor(GentleRule.TAG, tagId)?.level ?: GentleRule.OFF
                GentleLevelChips(level) { write { repo.setGentleLevel(GentleRule.TAG, tagId, tag.name, it) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.tag_archived), style = MaterialTheme.typography.bodyLarge)
                    Hint(stringResource(Res.string.tag_archived_hint))
                }
                Switch(tag.archived, { on -> write { repo.archiveTag(tagId, on) } }, Modifier.hand())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ pickingTarget = true }, Modifier.hand()) { Text(stringResource(Res.string.tag_merge)) }
                TextButton({ trashing = true }, Modifier.hand()) {
                    Text(stringResource(Res.string.move_to_trash), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (newCategory) {
        NameDialog(stringResource(Res.string.category_new), onDismiss = { newCategory = false }) { categoryName ->
            newCategory = false
            write { repo.createCategory(categoryName)?.let { repo.setTagCategory(tagId, it) } }
        }
    }
    if (pickingTarget) {
        TagPicker(
            services,
            title = stringResource(Res.string.tag_merge_pick),
            chosen = emptyList(),
            multi = false,
            onPick = { picked -> mergeTarget = all.firstOrNull { it.id == picked.id } },
            onDismiss = { pickingTarget = false },
            hidden = setOf(tagId),
        )
    }
    mergeTarget?.let { target ->
        val from = tagName(name).ifEmpty { tag.name }
        AlertDialog(
            onDismissRequest = { mergeTarget = null },
            title = { Text(stringResource(Res.string.tag_merge_title, from, target.name)) },
            text = { Text(stringResource(Res.string.tag_merge_text, from, target.name)) },
            confirmButton = {
                TextButton({
                    mergeTarget = null
                    write {
                        if (repo.mergeTags(tagId, target.id)) services.messages.tryEmit(AppMessage.TagsMerged(listOf(tagId), target.name))
                    }
                    onDismiss()
                }, Modifier.hand()) { Text(stringResource(Res.string.tag_merge_do)) }
            },
            dismissButton = { TextButton({ mergeTarget = null }, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
        )
    }
    if (trashing) {
        AlertDialog(
            onDismissRequest = { trashing = false },
            title = { Text(stringResource(Res.string.tag_trash_title, tag.name)) },
            text = { Text(stringResource(Res.string.tag_trash_text, pluralStringResource(Res.plurals.gentle_count, tag.uses, tag.uses))) },
            confirmButton = {
                TextButton({
                    trashing = false
                    write {
                        repo.trashTag(tagId)
                        services.messages.tryEmit(AppMessage.TagsTrashed(listOf(tagId), listOf(tag.name)))
                    }
                    onDismiss()
                }, Modifier.hand()) { Text(stringResource(Res.string.move_to_trash)) }
            },
            dismissButton = { TextButton({ trashing = false }, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** A titled part of an editor. */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

/** A quiet line of explanation. */
@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Asks for a name (a new tag or category). */
@Composable
internal fun NameDialog(title: String, onDismiss: () -> Unit, onName: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val done = { if (text.isNotBlank()) onName(text.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text,
                { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(done, Modifier.hand(), enabled = text.isNotBlank()) { Text(stringResource(Res.string.tag_picker_done)) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Where each Бережно level sits in `gentle_levels`. */
internal val LEVEL_INDEX = mapOf(GentleRule.OFF to 0, GentleRule.CURTAIN to 1, GentleRule.QUIET to 2)
