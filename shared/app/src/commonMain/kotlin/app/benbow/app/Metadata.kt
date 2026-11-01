package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.mood
import app.benbow.app.resources.mood_clear
import app.benbow.app.resources.tag_add
import app.benbow.app.resources.tag_remove
import app.benbow.app.resources.tags
import app.benbow.client.TagRef
import app.benbow.ui.mouseSwipe
import org.jetbrains.compose.resources.stringResource

/** -2 .. 2 as faces; words for feelings come later as tags of the "Emotions" category (ux.md#psychology). */
internal val MOODS = listOf(-2 to "😣", -1 to "🙁", 0 to "😐", 1 to "🙂", 2 to "😄")

internal fun moodEmoji(mood: Int?): String? = MOODS.firstOrNull { it.first == mood }?.second

/**
 * Mood and tags of an entry as chips under the title bar; everything optional and quick to change. A tap on a tag or on
 * «+ тег» opens the tag picker (7.9 T3); a tag's × takes it off.
 */
@Composable
internal fun MetadataRow(
    services: AppServices,
    mood: Int?,
    tags: List<TagRef>,
    onMood: (Int?) -> Unit,
    /** A known tag picked (its id), or a new one made with a name and a category. */
    onAddTag: (id: String, name: String) -> Unit,
    onCreateTag: (name: String, category: String?) -> Unit,
    onRemoveTag: (TagRef) -> Unit,
    /** A note on the board has no mood (ADR-040): only its tags. */
    withMood: Boolean = true,
) {
    var picking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (withMood) {
                AssistChip(onClick = {
                    picking = !picking
                }, label = { Text(moodEmoji(mood) ?: stringResource(Res.string.mood)) }, modifier = Modifier.hand())
            }
            tags.forEach { tag ->
                val name = LocalTags.current.name(tag)
                val remove = stringResource(Res.string.tag_remove, name)
                InputChip(
                    selected = false,
                    onClick = { adding = true },
                    label = { Text(name) },
                    trailingIcon = {
                        Icon(
                            Icons.Close,
                            remove,
                            Modifier.size(18.dp).clip(CircleShape).hand().clickable { onRemoveTag(tag) },
                        )
                    },
                    colors = InputChipDefaults.inputChipColors(containerColor = tagColor(tag.id)),
                    border = null,
                    modifier = Modifier.hand(),
                )
            }
            AssistChip(onClick = { adding = true }, label = { Text("+ " + stringResource(Res.string.tag_add)) }, modifier = Modifier.hand())
        }
        if (picking) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MOODS.forEach { (value, face) ->
                    TextButton({
                        onMood(value)
                        picking = false
                    }, modifier = Modifier.hand()) { Text(face, style = MaterialTheme.typography.titleLarge) }
                }
                if (mood != null) {
                    TextButton({
                        onMood(null)
                        picking = false
                    }, modifier = Modifier.hand()) { Text(stringResource(Res.string.mood_clear)) }
                }
            }
        }
    }
    if (adding) {
        TagPicker(
            services,
            title = stringResource(Res.string.tags),
            chosen = tags,
            multi = true,
            onPick = { onAddTag(it.id, it.name) },
            onDismiss = { adding = false },
            onRemove = onRemoveTag,
            onCreate = onCreateTag,
        )
    }
}

/** A tag as Keep shows labels: a rounded chip in the tag's color, no "#". [onClick]: opens something (its page). */
@Composable
internal fun TagChip(tag: TagRef, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        color = tagColor(tag.id),
        shape = shape,
        modifier = modifier.then(if (onClick != null) Modifier.clip(shape).hand().clickable(onClick = onClick) else Modifier),
    ) {
        Text(
            LocalTags.current.name(tag),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** An entry's tags on a line of their own under the text; a tap opens a tag's page. */
@Composable
internal fun TagRibbon(tags: List<TagRef>, modifier: Modifier = Modifier) {
    val open = LocalOpenTag.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { tag -> TagChip(tag, onClick = open?.let { { it(tag.id) } }) }
    }
}

/** Templates to start a new, empty entry from, one's [own] first (ADR-061), then the standard ones; optional, one tap. */
@Composable
internal fun TemplateRow(own: List<app.benbow.client.OwnTemplate>, onPick: (String) -> Unit) {
    val swipeRow = rememberLazyListState()
    // One's own come in after the standard ones are drawn: the row starts at them, not where the standard ones began.
    LaunchedEffect(own.size) { swipeRow.scrollToItem(0) }
    androidx.compose.foundation.lazy.LazyRow(
        state = swipeRow,
        modifier = Modifier.mouseSwipe(swipeRow),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(own, key = { it.id }) { template ->
            SuggestionChip(
                onClick = { onPick(template.id) },
                label = { Text(template.name) },
                icon = { Icon(Icons.Template, null, Modifier.size(16.dp)) },
                modifier = Modifier.hand(),
            )
        }
        if (own.isNotEmpty()) {
            item(key = "between") { VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp)) }
        }
        items(app.benbow.client.Templates.builtIn, key = { "s:" + it.id }) { template ->
            SuggestionChip(onClick = { onPick(template.id) }, label = { Text(template.name) }, modifier = Modifier.hand())
        }
    }
}
