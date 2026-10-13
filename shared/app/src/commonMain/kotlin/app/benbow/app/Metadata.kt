package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import app.benbow.app.resources.Res
import app.benbow.app.resources.add
import app.benbow.app.resources.mood
import app.benbow.app.resources.mood_clear
import app.benbow.app.resources.tag_add
import app.benbow.app.resources.tag_hint
import app.benbow.app.resources.tag_remove
import app.benbow.client.TagCount
import app.benbow.client.tagName
import org.jetbrains.compose.resources.stringResource

/** -2 .. 2 as faces; words for feelings come later as tags of the "Emotions" category (ux.md#psychology). */
internal val MOODS = listOf(-2 to "😣", -1 to "🙁", 0 to "😐", 1 to "🙂", 2 to "😄")

internal fun moodEmoji(mood: Int?): String? = MOODS.firstOrNull { it.first == mood }?.second

/** Mood and tags of an entry as chips under the title bar; everything optional and quick to change. */
@Composable
internal fun MetadataRow(
    mood: Int?,
    tags: List<String>,
    knownTags: List<TagCount>,
    onMood: (Int?) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { picking = !picking }, label = { Text(moodEmoji(mood) ?: stringResource(Res.string.mood)) })
            tags.forEach { tag ->
                val remove = stringResource(Res.string.tag_remove, tag)
                InputChip(
                    selected = false,
                    onClick = { onRemoveTag(tag) },
                    label = { Text("#$tag") },
                    trailingIcon = { Icon(Icons.Close, remove, Modifier.size(16.dp)) },
                )
            }
            AssistChip(onClick = { adding = true }, label = { Text("+ " + stringResource(Res.string.tag_add)) })
        }
        if (picking) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MOODS.forEach { (value, face) ->
                    TextButton({
                        onMood(value)
                        picking = false
                    }) { Text(face, style = MaterialTheme.typography.titleLarge) }
                }
                if (mood != null) {
                    TextButton({
                        onMood(null)
                        picking = false
                    }) { Text(stringResource(Res.string.mood_clear)) }
                }
            }
        }
        if (adding) {
            TagInput(knownTags.filter { it.name !in tags }, onDone = { adding = false }, onAdd = onAddTag)
        }
    }
}

/** A tag as Keep shows labels: a quiet rounded chip, no "#". */
@Composable
internal fun TagChip(name: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Type a tag or tap a suggestion; Done or Enter adds it and closes. */
@Composable
private fun TagInput(known: List<TagCount>, onDone: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val clean = tagName(text)
    val suggestions = known.filter { clean.isEmpty() || it.name.startsWith(clean, ignoreCase = true) }.take(12)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val add = { name: String ->
        onAdd(name)
        onDone()
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(Res.string.tag_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (clean.isNotEmpty()) add(clean) else onDone() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            TextButton({ add(clean) }, enabled = clean.isNotEmpty()) { Text(stringResource(Res.string.add)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { tag -> SuggestionChip(onClick = { add(tag.name) }, label = { Text("#${tag.name}") }) }
        }
    }
}

/** Built-in templates to start a new, empty entry from; optional, one tap. */
@Composable
internal fun TemplateRow(onPick: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(app.benbow.client.Templates.builtIn.size) { i ->
            val template = app.benbow.client.Templates.builtIn[i]
            SuggestionChip(onClick = { onPick(template.id) }, label = { Text(template.name) })
        }
    }
}
