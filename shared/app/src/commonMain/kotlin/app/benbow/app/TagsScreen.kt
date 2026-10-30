package app.benbow.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.category_empty
import app.benbow.app.resources.category_new
import app.benbow.app.resources.color_as_category
import app.benbow.app.resources.gentle_count
import app.benbow.app.resources.menu
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.tag_color
import app.benbow.app.resources.tag_edit
import app.benbow.app.resources.tag_new
import app.benbow.app.resources.tags
import app.benbow.app.resources.tags_archive
import app.benbow.app.resources.tags_find
import app.benbow.app.resources.tags_from_archive
import app.benbow.app.resources.tags_merge_into
import app.benbow.app.resources.tags_selected
import app.benbow.app.resources.tags_sort_name
import app.benbow.app.resources.tags_sort_recent
import app.benbow.app.resources.tags_sort_uses
import app.benbow.app.resources.tags_to_archive
import app.benbow.app.resources.tags_to_category
import app.benbow.app.resources.tags_trash_title
import app.benbow.app.resources.tags_uncategorized
import app.benbow.client.TagChoice
import app.benbow.client.TagRef
import app.benbow.client.TagRegistry
import app.benbow.client.TagSearch
import app.benbow.client.TagUsage
import app.benbow.client.archiveTag
import app.benbow.client.createCategory
import app.benbow.client.createTag
import app.benbow.client.mergeTags
import app.benbow.client.setTagCategory
import app.benbow.client.setTagColor
import app.benbow.client.tagRegistry
import app.benbow.client.tags
import app.benbow.client.trashTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal class TagsViewModel(services: AppServices) : ViewModel() {
    /** Every tag outside the trash, archived ones too, with its last day. */
    val tags: StateFlow<List<TagChoice>?> =
        combine(services.repo.tags(), services.repo.journal(), services.repo.tagRegistry()) { tags, entries, registry ->
            TagChoice.of(tags, registry, TagUsage(entries))
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private enum class Sort { USES, NAME, RECENT }

/** What a selection is about to get. */
private enum class Batch { CATEGORY, COLOR, MERGE, TRASH }

/**
 * "Ещё → Теги" (7.9 T4): find a tag, sort them, every category with its tags (empty ones too), the tags without one,
 * then the archive. A tap opens a tag's page, a long press starts choosing several (to a category, a color, merged
 * into one, archived, to the trash); a category's pencil opens its editor; «+» makes a tag or a category.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagsScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { TagsViewModel(services) }
    val all by vm.tags.collectAsStateWithLifecycle()
    val registry = LocalTags.current
    val repo = services.repo
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.USES) }
    var collapsed by remember { mutableStateOf(setOf(ARCHIVE)) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var editingTag by remember { mutableStateOf<String?>(null) }
    var editingCategory by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf<Boolean?>(null) } // true: a tag, false: a category
    var adding by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf<Batch?>(null) }
    val write = { action: () -> Unit -> services.scope.launch { action() } }
    val tags = all.orEmpty()
    val chosen = tags.filter { it.id in selected }
    Scaffold(topBar = {
        if (selected.isEmpty()) {
            TopAppBar(
                navigationIcon = { BackButton(nav::back) },
                title = { Text(stringResource(Res.string.tags)) },
                actions = {
                    Box {
                        IconButton({ adding = true }, Modifier.hand()) { Icon(Icons.Add, stringResource(Res.string.tag_new)) }
                        DropdownMenu(adding, { adding = false }) {
                            DropdownMenuItem({ Text(stringResource(Res.string.tag_new)) }, {
                                adding = false
                                creating = true
                            }, Modifier.hand())
                            DropdownMenuItem({ Text(stringResource(Res.string.category_new)) }, {
                                adding = false
                                creating = false
                            }, Modifier.hand())
                        }
                    }
                },
            )
        } else {
            SelectionBar(chosen, onClear = { selected = emptySet() }, onBatch = { batch = it }) { archive ->
                write { chosen.forEach { repo.archiveTag(it.id, archive) } }
                selected = emptySet()
            }
        }
    }) { padding ->
        val list = all ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "find") {
                OutlinedTextField(
                    query,
                    { query = it },
                    placeholder = { Text(stringResource(Res.string.tags_find)) },
                    leadingIcon = { Icon(Icons.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val row: @Composable (TagChoice) -> Unit = { tag ->
                TagLine(
                    tag,
                    selecting = selected.isNotEmpty(),
                    on = tag.id in selected,
                    onClick = {
                        if (selected.isNotEmpty()) selected = selected.toggle(tag.id) else nav.open(Route.Tag(tag.id))
                    },
                    onLongClick = { selected = selected.toggle(tag.id) },
                )
            }
            if (query.isNotBlank()) {
                items(TagSearch.find(query, list), key = { "h:" + it.tag.id }) { row(it.tag) }
            } else {
                item(key = "sort") {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SORTS.forEach { (value, label) ->
                            FilterChip(sort == value, { sort = value }, { Text(stringResource(label)) }, Modifier.hand())
                        }
                    }
                }
                val live = list.filterNot { it.archived }.sortedWith(sort.order())
                val groups = live.groupBy { it.categoryId }
                registry.categories.forEach { category ->
                    section(category.id, registry, groups[category.id].orEmpty(), collapsed, { collapsed = collapsed.toggle(it) }, {
                        editingCategory =
                            it
                    }, row)
                }
                groups[null]?.let { section(NONE, registry, it, collapsed, { collapsed = collapsed.toggle(it) }, null, row) }
                list.filter { it.archived }.sortedWith(sort.order()).takeIf { it.isNotEmpty() }?.let {
                    section(ARCHIVE, registry, it, collapsed, { collapsed = collapsed.toggle(it) }, null, row)
                }
            }
        }
    }
    editingTag?.let { TagEditor(services, it) { editingTag = null } }
    editingCategory?.let { CategoryEditor(services, it) { editingCategory = null } }
    creating?.let { tag ->
        NameDialog(stringResource(if (tag) Res.string.tag_new else Res.string.category_new), onDismiss = { creating = null }) { name ->
            creating = null
            services.scope.launch {
                if (tag) repo.createTag(name)?.let { editingTag = it } else repo.createCategory(name)?.let { editingCategory = it }
            }
        }
    }
    batch?.let { what ->
        BatchDialog(what, chosen, registry, onDismiss = { batch = null }) { act ->
            write { act(repo, services) }
            batch = null
            selected = emptySet()
        }
    }
}

private const val ARCHIVE = "archive"
private const val NONE = "none"

private val SORTS = listOf(
    Sort.USES to Res.string.tags_sort_uses,
    Sort.NAME to Res.string.tags_sort_name,
    Sort.RECENT to Res.string.tags_sort_recent,
)

private fun Sort.order(): Comparator<TagChoice> = when (this) {
    Sort.USES -> compareByDescending<TagChoice> { it.uses }.thenBy { it.name.lowercase() }
    Sort.NAME -> compareBy { it.name.lowercase() }
    Sort.RECENT -> compareByDescending<TagChoice> { it.lastDay.orEmpty() }.thenBy { it.name.lowercase() }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

/** A category (or «Без категории», or the archive) with its tags; a tap on the title folds it. */
private fun LazyListScope.section(
    id: String,
    registry: TagRegistry,
    tags: List<TagChoice>,
    collapsed: Set<String>,
    onFold: (String) -> Unit,
    onEdit: ((String) -> Unit)?,
    row: @Composable (TagChoice) -> Unit,
) {
    val open = id !in collapsed
    item(key = "c:$id") {
        val title = when (id) {
            NONE -> stringResource(Res.string.tags_uncategorized)
            ARCHIVE -> stringResource(Res.string.tags_archive)
            else -> listOfNotNull(registry.categoryEmoji[id], registry.categoryName(id)).joinToString(" ")
        }
        Row(
            Modifier.fillMaxWidth().hand().clickable { onFold(id) }.padding(start = 20.dp, end = 4.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.ChevronRight,
                null,
                Modifier.size(18.dp).rotate(if (open) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            if (onEdit != null) {
                Box(Modifier.size(10.dp).background(markColor(registry.categoryColor(id)) ?: Color.Transparent, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "  ${tags.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onEdit != null) {
                IconButton({ onEdit(id) }, Modifier.hand()) {
                    Icon(
                        Icons.Edit,
                        stringResource(Res.string.tag_edit),
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (!open) return
    if (tags.isEmpty()) {
        item(key = "e:$id") {
            Text(
                stringResource(Res.string.category_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 44.dp, top = 2.dp, bottom = 6.dp),
            )
        }
    }
    items(tags, key = { "$id:" + it.id }) { row(it) }
}

/** A tag: its chip, a few words about it, how many entries and when last. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagLine(tag: TagChoice, selecting: Boolean, on: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .hand().combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Icon(if (on) Icons.CheckBox else Icons.CheckBoxBlank, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TagChip(TagRef(tag.id, tag.name))
            tag.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(pluralStringResource(Res.plurals.gentle_count, tag.uses, tag.uses), style = MaterialTheme.typography.bodySmall)
            tag.lastDay?.let {
                Text(
                    dayLabel(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** While choosing: how many, and what to do with them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(chosen: List<TagChoice>, onClear: () -> Unit, onBatch: (Batch) -> Unit, onArchive: (Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val archive = !chosen.all { it.archived }
    TopAppBar(
        navigationIcon = { IconButton(onClear, Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.cancel)) } },
        title = { Text(stringResource(Res.string.tags_selected, chosen.size)) },
        actions = {
            IconButton({ onBatch(Batch.TRASH) }, Modifier.hand()) { Icon(Icons.Delete, stringResource(Res.string.move_to_trash)) }
            Box {
                IconButton({ menu = true }, Modifier.hand()) { Icon(Icons.More, stringResource(Res.string.menu)) }
                DropdownMenu(menu, { menu = false }) {
                    val item = @Composable { label: String, action: () -> Unit ->
                        DropdownMenuItem({ Text(label) }, {
                            menu = false
                            action()
                        }, Modifier.hand())
                    }
                    item(stringResource(Res.string.tags_to_category)) { onBatch(Batch.CATEGORY) }
                    item(stringResource(Res.string.tag_color)) { onBatch(Batch.COLOR) }
                    if (chosen.size > 1) item(stringResource(Res.string.tags_merge_into)) { onBatch(Batch.MERGE) }
                    item(stringResource(if (archive) Res.string.tags_to_archive else Res.string.tags_from_archive)) { onArchive(archive) }
                }
            }
        },
    )
}

/** One step for every chosen tag: a category, a color, merged into one of them, or the trash. */
@Composable
private fun BatchDialog(
    what: Batch,
    chosen: List<TagChoice>,
    registry: TagRegistry,
    onDismiss: () -> Unit,
    onDo: ((app.benbow.client.JournalRepository, AppServices) -> Unit) -> Unit,
) {
    val title = when (what) {
        Batch.CATEGORY -> stringResource(Res.string.tags_to_category)
        Batch.COLOR -> stringResource(Res.string.tag_color)
        Batch.MERGE -> stringResource(Res.string.tags_merge_into)
        Batch.TRASH -> stringResource(Res.string.tags_trash_title, chosen.size)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when (what) {
                Batch.CATEGORY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (
                        listOf<Pair<String?, String>>(null to stringResource(Res.string.tags_uncategorized)) +
                            registry.categories.map { it.id to it.name }
                        )
                        .forEach { (id, label) ->
                            FilterChip(false, {
                                onDo { repo, _ -> chosen.forEach { repo.setTagCategory(it.id, id) } }
                            }, { Text(label) }, Modifier.hand())
                        }
                }

                Batch.COLOR -> SwatchPicker(null, stringResource(Res.string.color_as_category), { color ->
                    onDo { repo, _ -> chosen.forEach { repo.setTagColor(it.id, color) } }
                })

                Batch.MERGE -> Column {
                    chosen.forEach { target ->
                        TextButton({
                            onDo { repo, services ->
                                val merged = chosen.filter { it.id != target.id && repo.mergeTags(it.id, target.id) }.map { it.id }
                                if (merged.isNotEmpty()) services.messages.tryEmit(AppMessage.TagsMerged(merged, target.name))
                            }
                        }, Modifier.hand()) { TagChip(TagRef(target.id, target.name)) }
                    }
                }

                Batch.TRASH -> Text(chosen.joinToString(", ") { it.name })
            }
        },
        confirmButton = {
            if (what == Batch.TRASH) {
                TextButton({
                    onDo { repo, services ->
                        chosen.forEach { repo.trashTag(it.id) }
                        services.messages.tryEmit(AppMessage.TagsTrashed(chosen.map { it.id }, chosen.map { it.name }))
                    }
                }, Modifier.hand()) { Text(stringResource(Res.string.move_to_trash), color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}
