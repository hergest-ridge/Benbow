package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.color_as_category
import app.benbow.app.resources.color_default
import app.benbow.app.resources.tag_category
import app.benbow.app.resources.tag_color
import app.benbow.app.resources.tags
import app.benbow.app.resources.tags_empty
import app.benbow.app.resources.tags_uncategorized
import app.benbow.client.TagRef
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** All tags grouped by category, in their colors. Tap a category for its color; tap a tag for its category and own color. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagsScreen(services: AppServices, nav: Navigator) {
    val tags by services.repo.tags().collectAsStateWithLifecycle(null)
    val looks = LocalTags.current
    val none = stringResource(Res.string.tags_uncategorized)
    var editing by remember { mutableStateOf<String?>(null) }
    val toggle = { key: String -> editing = if (editing == key) null else key }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.tags)) })
    }) { padding ->
        val all = tags ?: return@Scaffold
        if (all.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.tags_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        // By category id; tags without one come last.
        val groups = all.groupBy { it.category }
        val order: List<String?> =
            looks.categories.map { it.id }.filter { it in groups } + if (null in groups) listOf(null) else emptyList()
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            order.forEach { group ->
                item(key = "g:$group") {
                    if (group == null) {
                        CategoryTitle(none, null, null)
                    } else {
                        CategoryTitle(looks.categoryName(group), swatch(looks.categoryColor(group))) { toggle("c:$group") }
                        if (editing == "c:$group") {
                            SwatchPicker(
                                selected = looks.categoryColors[group],
                                noneLabel = stringResource(Res.string.color_default),
                                onPick = { color -> services.scope.launch { services.repo.setCategoryColor(group, color) } },
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                items(groups.getValue(group).sortedBy { it.name.lowercase() }, key = { "t:" + it.id }) { tag ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().hand().clickable {
                                toggle("t:" + tag.id)
                            }.padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TagChip(TagRef(tag.id, tag.name))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${tag.uses}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (editing == "t:" + tag.id) {
                            Column(
                                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(stringResource(Res.string.tag_category), style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    (looks.categories.map { it.id to it.name } + (null to none)).forEach { (category, name) ->
                                        FilterChip(tag.category == category, {
                                            services.scope.launch { services.repo.setTagCategory(tag.id, category) }
                                        }, { Text(name) }, modifier = Modifier.hand())
                                    }
                                }
                                Text(stringResource(Res.string.tag_color), style = MaterialTheme.typography.labelLarge)
                                SwatchPicker(
                                    selected = looks.ownColor(tag.id),
                                    noneLabel = stringResource(Res.string.color_as_category),
                                    onPick = { color -> services.scope.launch { services.repo.setTagColor(tag.id, color) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTitle(name: String, color: Color?, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.hand().clickable(onClick = onClick) else Modifier)
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}
