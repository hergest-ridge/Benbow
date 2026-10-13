package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.tag_categories
import app.benbow.app.resources.tags
import app.benbow.app.resources.tags_empty
import app.benbow.app.resources.tags_uncategorized
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** All tags grouped by category (People, Places, Projects, Emotions); tap a tag to put it in a category. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagsScreen(services: AppServices, nav: Navigator) {
    val tags by services.repo.tags().collectAsStateWithLifecycle(null)
    val categories = stringArrayResource(Res.array.tag_categories)
    val none = stringResource(Res.string.tags_uncategorized)
    var editing by remember { mutableStateOf<String?>(null) }
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
                    Text(
                        group,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(groups.getValue(group).sortedBy { it.name.lowercase() }, key = { "t:" + it.name }) { tag ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            editing = if (editing ==
                                tag.name
                            ) {
                                null
                            } else {
                                tag.name
                            }
                        }.padding(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        Text("#${tag.name} · ${tag.uses}", style = MaterialTheme.typography.bodyLarge)
                        if (editing == tag.name) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                (categories + none).forEach { category ->
                                    val selected = (tag.category ?: none) == category
                                    FilterChip(selected, {
                                        editing = null
                                        services.scope.launch { services.repo.setTagCategory(tag.name, category.takeIf { it != none }) }
                                    }, { Text(category) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
