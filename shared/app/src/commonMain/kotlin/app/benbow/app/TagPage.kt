package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.gentle
import app.benbow.app.resources.gentle_count
import app.benbow.app.resources.gentle_levels
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.restore
import app.benbow.app.resources.tag_edit
import app.benbow.app.resources.tag_page_empty
import app.benbow.app.resources.tag_page_since
import app.benbow.app.resources.tag_page_trashed
import app.benbow.client.EntrySummary
import app.benbow.client.GentleRule
import app.benbow.client.GentleRules
import app.benbow.client.SearchQuery
import app.benbow.client.TagCount
import app.benbow.client.TagRef
import app.benbow.client.restoreTag
import app.benbow.client.ruleFor
import app.benbow.client.tags
import app.benbow.client.trashedTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** Opens a tag's page from its chip anywhere (feed, a post, a list); null where chips stay still. */
internal val LocalOpenTag = compositionLocalOf<((String) -> Unit)?> { null }

internal class TagPageViewModel(services: AppServices, tagId: String) : ViewModel() {
    class State(val tag: TagCount?, val trashed: Boolean, val entries: List<EntrySummary>, val rules: GentleRules)

    val state: StateFlow<State?> = combine(
        services.repo.tags(),
        services.repo.trashedTags(),
        services.repo.search(SearchQuery(tag = tagId)),
        services.repo.gentleRules(),
    ) { tags, trashed, entries, rules ->
        val tag = tags.firstOrNull { it.id == tagId }
        State(tag ?: trashed.firstOrNull { it.id == tagId }, tag == null && trashed.any { it.id == tagId }, entries, rules)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * A tag's page (7.9 T4), from its chip anywhere or from "Ещё → Теги": its color and name, category, a few words, how
 * many entries since when and Бережно's level, then its entries as search shows them (quiet ones under a curtain).
 * «Изменить» opens the tag's editor; a tag in the trash can come back from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagPage(services: AppServices, nav: Navigator, route: Route.Tag) {
    val vm = viewModel(key = "tag-${route.tagId}") { TagPageViewModel(services, route.tagId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val registry = LocalTags.current
    var editing by remember { mutableStateOf(false) }
    val current = state
    val tag = current?.tag
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { BackButton(nav::back) },
            title = { Text(tag?.name.orEmpty()) },
            actions = {
                if (tag != null && current.trashed.not()) {
                    IconButton({ editing = true }, Modifier.hand()) { Icon(Icons.Edit, stringResource(Res.string.tag_edit)) }
                }
            },
        )
    }) { padding ->
        if (current == null || tag == null) return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "about") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TagChip(TagRef(tag.id, tag.name))
                        tag.category?.let {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                listOfNotNull(registry.categoryEmoji[it], registry.categoryName(it)).joinToString(" "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    registry.description(tag.id)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    val since = current.entries.minOfOrNull { it.day }
                    Hint(
                        listOfNotNull(
                            pluralStringResource(Res.plurals.gentle_count, tag.uses, tag.uses),
                            since?.let {
                                stringResource(Res.string.tag_page_since, dateLabel(it, stringArrayResource(Res.array.months_genitive)))
                            },
                        ).joinToString(" · "),
                    )
                    current.rules.ruleFor(GentleRule.TAG, tag.id)?.takeIf { it.veil != null }?.let { rule ->
                        val level = stringArrayResource(Res.array.gentle_levels)[LEVEL_INDEX.getValue(rule.level)]
                        Hint(stringResource(Res.string.gentle) + ": " + level.lowercase())
                    }
                    if (current.trashed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(Res.string.tag_page_trashed),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton({ services.scope.launch { services.repo.restoreTag(tag.id) } }, Modifier.hand()) {
                                Text(stringResource(Res.string.restore))
                            }
                        }
                    }
                }
            }
            if (current.entries.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Hint(stringResource(Res.string.tag_page_empty)) }
                }
            }
            items(current.entries, key = { it.id }) { entry ->
                EntryCard(entry, onClick = {
                    nav.open(Route.Editor(entry.id, entry.day, readOnly = true))
                }, media = services.media, showDay = true)
            }
        }
    }
    if (editing) TagEditor(services, route.tagId) { editing = false }
}
