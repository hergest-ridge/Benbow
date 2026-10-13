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
import app.benbow.app.resources.tag_categories
import app.benbow.app.resources.tag_category
import app.benbow.app.resources.tag_color
import app.benbow.app.resources.tags
import app.benbow.app.resources.tags_empty
import app.benbow.app.resources.tags_uncategorized
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** All tags grouped by category, in their colors. Tap a category for its color; tap a tag for its category and own color. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagsScreen(services: AppServices, nav: Navigator) {
    val tags by services.repo.tags().collectAsStateWithLifecycle(null)
    val looks = LocalTagLooks.current
    val categories = stringArrayResource(Res.array.tag_categories)
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
        val groups = all.groupBy { it.category ?: none }
        val order =
            categories.filter { it in groups } + groups.keys.filter { it !in categories && it != none }.sorted() +
                listOfNotNull(none.takeIf { it in groups })
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            order.forEach { group ->
                item(key = "g:$group") {
                    if (group == none) {
                        CategoryTitle(group, null, null)
                    } else {
                        CategoryTitle(group, swatch(looks.categoryColor(group))) { toggle("c:$group") }
                        if (editing == "c:$group") {
                            SwatchPicker(
                                selected = looks.categoryColors[group.lowercase()],
                                noneLabel = stringResource(Res.string.color_default),
                                onPick = { color -> services.scope.launch { services.repo.setCategoryColor(group, color) } },
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                items(groups.getValue(group).sortedBy { it.name.lowercase() }, key = { "t:" + it.name }) { tag ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { toggle("t:" + tag.name) }.padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TagChip(tag.name)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${tag.uses}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (editing == "t:" + tag.name) {
                            Column(
                                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(stringResource(Res.string.tag_category), style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    (categories + none).forEach { category ->
                                        FilterChip((tag.category ?: none) == category, {
                                            services.scope.launch { services.repo.setTagCategory(tag.name, category.takeIf { it != none }) }
                                        }, { Text(category) })
                                    }
                                }
                                Text(stringResource(Res.string.tag_color), style = MaterialTheme.typography.labelLarge)
                                SwatchPicker(
                                    selected = looks.ownColor(tag.name),
                                    noneLabel = stringResource(Res.string.color_as_category),
                                    onPick = { color -> services.scope.launch { services.repo.setTagColor(tag.name, color) } },
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
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
