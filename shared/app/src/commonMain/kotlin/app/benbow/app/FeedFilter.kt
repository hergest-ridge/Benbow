package app.benbow.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.benbow.app.resources.Res
import app.benbow.app.resources.feed_filter
import app.benbow.app.resources.feed_filter_categories
import app.benbow.app.resources.feed_filter_done
import app.benbow.app.resources.feed_filter_hint
import app.benbow.app.resources.feed_filter_pick_tag
import app.benbow.app.resources.feed_filter_picker
import app.benbow.app.resources.feed_filter_reset
import app.benbow.app.resources.feed_filter_tags
import app.benbow.app.resources.feed_filter_years
import app.benbow.client.FeedKey
import app.benbow.client.TagRef
import app.benbow.client.TagRegistry
import app.benbow.client.Veil
import app.benbow.ui.sideScroll
import org.jetbrains.compose.resources.stringResource

/**
 * The feed's lens (ADR-036 N5): posts with any of these [tags] or [categories] (ids), in any of these [years]. It lives
 * as long as the app does: a new start shows the whole feed again, so it never looks as if posts went missing.
 */
data class FeedFilter(val tags: Set<String> = emptySet(), val categories: Set<String> = emptySet(), val years: Set<String> = emptySet()) {
    val active: Boolean get() = bySubject || years.isNotEmpty()

    /** By tags or categories: a deliberate look at a subject, like search. */
    val bySubject: Boolean get() = tags.isNotEmpty() || categories.isNotEmpty()

    fun matches(key: FeedKey, registry: TagRegistry): Boolean {
        if (years.isNotEmpty() && key.day.take(4) !in years) return false
        return !bySubject || key.tags.any { id -> registry.root(id) in tags || registry.category(id) in categories }
    }
}

/**
 * Whether the feed shows a post. Quiet ones stay out while Бережно is on (ADR-031), except under a filter by tag or
 * category: asking for a subject is deliberate, as in search, so its quiet posts come, under a curtain.
 */
internal fun FeedFilter.shows(key: FeedKey, gentle: Boolean, registry: TagRegistry): Boolean =
    matches(key, registry) && (!gentle || key.veil != Veil.Quiet || bySubject)

/** What the filter offers: the years, categories and most used tags of the feed as it shows without a filter. */
internal class FeedChoices(val years: List<String>, val categories: List<String>, val tags: List<String>) {
    companion object {
        fun of(keys: List<FeedKey>, registry: TagRegistry): FeedChoices {
            val uses = HashMap<String, Int>()
            val categories = HashSet<String>()
            keys.forEach { key ->
                key.tags.forEach { id ->
                    val root = registry.root(id)
                    if (!registry.inTrash(root)) uses[root] = (uses[root] ?: 0) + 1
                    registry.category(id)?.let(categories::add)
                }
            }
            return FeedChoices(
                years = keys.map { it.day.take(4) }.distinct(),
                categories = registry.categories.map { it.id }.filter { it in categories },
                tags = uses.entries.filterNot { registry.isArchived(it.key) }
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { registry.names[it.key].orEmpty() })
                    .take(QUICK_TAGS).map { it.key },
            )
        }
    }
}

/** The filter at work, under the feed's title: each part with its ×, and «Сбросить» for all of them. */
@Composable
internal fun FeedFilterRow(filter: FeedFilter, onChange: (FeedFilter) -> Unit, modifier: Modifier = Modifier) {
    val registry = LocalTags.current
    Row(
        modifier.fillMaxWidth().sideScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        filter.tags.forEach { id ->
            RemovableChip(registry.names[id] ?: id) { onChange(filter.copy(tags = filter.tags - id)) }
        }
        filter.categories.forEach { id ->
            RemovableChip(registry.categoryName(id)) { onChange(filter.copy(categories = filter.categories - id)) }
        }
        filter.years.sortedDescending().forEach { year ->
            RemovableChip(year) { onChange(filter.copy(years = filter.years - year)) }
        }
        if (listOf(filter.tags, filter.categories, filter.years).sumOf { it.size } > 1) {
            TextButton({ onChange(FeedFilter()) }, Modifier.hand()) { Text(stringResource(Res.string.feed_filter_reset)) }
        }
    }
}

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    InputChip(
        true,
        onRemove,
        { Text(label) },
        trailingIcon = { Icon(Icons.Close, null, Modifier.size(16.dp)) },
        modifier = Modifier.hand(),
    )
}

/**
 * The filter's sheet (a dialog in a wide window): tags (the most used, and any through the tag picker), categories and
 * years; a post with any of the tags or categories shows. It changes the feed as it is tapped. No counts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedFilterSheet(
    services: AppServices,
    filter: FeedFilter,
    choices: FeedChoices,
    onChange: (FeedFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val registry = LocalTags.current
    var picking by remember { mutableStateOf(false) }
    val content: @Composable () -> Unit = {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(
                stringResource(Res.string.feed_filter),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                stringResource(Res.string.feed_filter_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Section(stringResource(Res.string.feed_filter_tags))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (filter.tags + choices.tags).distinct().forEach { id ->
                    val on = id in filter.tags
                    FilterChip(
                        on,
                        { onChange(filter.copy(tags = if (on) filter.tags - id else filter.tags + id)) },
                        { Text(registry.names[id] ?: id) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = tagColor(id)),
                        modifier = Modifier.hand(),
                    )
                }
                AssistChip({ picking = true }, { Text(stringResource(Res.string.feed_filter_pick_tag)) }, modifier = Modifier.hand())
            }
            if (choices.categories.isNotEmpty()) {
                Section(stringResource(Res.string.feed_filter_categories))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.categories.forEach { id ->
                        val on = id in filter.categories
                        FilterChip(
                            on,
                            { onChange(filter.copy(categories = if (on) filter.categories - id else filter.categories + id)) },
                            { Text(registry.categoryName(id)) },
                            modifier = Modifier.hand(),
                        )
                    }
                }
            }
            if (choices.years.size > 1) {
                Section(stringResource(Res.string.feed_filter_years))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.years.forEach { year ->
                        val on = year in filter.years
                        FilterChip(
                            on,
                            { onChange(filter.copy(years = if (on) filter.years - year else filter.years + year)) },
                            { Text(year) },
                            modifier = Modifier.hand(),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                if (filter.active) {
                    TextButton({ onChange(FeedFilter()) }, Modifier.hand()) { Text(stringResource(Res.string.feed_filter_reset)) }
                }
                TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.feed_filter_done)) }
            }
        }
    }
    if (LocalWide.current) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 520.dp)) {
                Column(Modifier.padding(top = 12.dp)) { content() }
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) { content() }
    }
    if (picking) {
        TagPicker(
            services,
            title = stringResource(Res.string.feed_filter_picker),
            chosen = filter.tags.map { TagRef(it, registry.names[it] ?: it) },
            multi = true,
            onPick = { onChange(filter.copy(tags = filter.tags + registry.root(it.id))) },
            onDismiss = { picking = false },
            onRemove = { onChange(filter.copy(tags = filter.tags - registry.root(it.id))) },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

/** Tags offered at once; the rest through the tag picker. */
private const val QUICK_TAGS = 8
