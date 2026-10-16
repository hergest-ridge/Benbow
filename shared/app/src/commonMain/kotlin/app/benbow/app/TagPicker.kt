package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.gentle_count
import app.benbow.app.resources.tag_picker_create
import app.benbow.app.resources.tag_picker_done
import app.benbow.app.resources.tag_picker_find
import app.benbow.app.resources.tag_picker_find_or_create
import app.benbow.app.resources.tag_picker_nothing
import app.benbow.app.resources.tag_picker_recent
import app.benbow.app.resources.tag_picker_together
import app.benbow.app.resources.tag_remove
import app.benbow.app.resources.tags_uncategorized
import app.benbow.client.TagChoice
import app.benbow.client.TagHit
import app.benbow.client.TagMatch
import app.benbow.client.TagRef
import app.benbow.client.TagRegistry
import app.benbow.client.TagSearch
import app.benbow.client.TagUsage
import app.benbow.client.tagName
import app.benbow.client.tagRegistry
import app.benbow.client.tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What the picker offers: every tag outside the trash, and how they were used lately (without what Бережно keeps away). */
private class TagChoices(val all: List<TagChoice>, val usage: TagUsage)

/**
 * The tag picker (7.9 T3): a full-screen sheet on a phone, a dialog in a wide window. The search field comes first;
 * before anything is typed it offers the recent tags, those that go with the chosen ones and the rest by category;
 * while typing, forgiving matches ([TagSearch]) with their category, uses and last day, then «Создать» with a
 * category. [multi] (the editor): a tap puts a tag on or takes it off and the picker stays, chosen tags on top,
 * Backspace in an empty field takes off the last; otherwise a tap picks one and closes. Enter takes the highlighted
 * line (arrows move it). [hidden]: tags not to offer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagPicker(
    services: AppServices,
    title: String,
    chosen: List<TagRef>,
    multi: Boolean,
    onPick: (TagChoice) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (TagRef) -> Unit = {},
    onCreate: ((name: String, category: String?) -> Unit)? = null,
    hidden: Set<String> = emptySet(),
) {
    val gentle = LocalGentle.current
    val source = remember(gentle) {
        combine(services.repo.tags(), services.repo.journal(), services.repo.tagRegistry()) { tags, entries, registry ->
            // With Бережно on, a tag never comes up on its own from a curtained or quiet entry.
            val usage = TagUsage(if (gentle) entries.filter { it.veil == null } else entries)
            TagChoices(TagChoice.of(tags, registry, usage), usage)
        }.flowOn(Dispatchers.Default)
    }
    val choices by source.collectAsStateWithLifecycle(null)
    val pick = { choice: TagChoice ->
        val on = chosen.firstOrNull { it.id == choice.id }
        if (multi && on != null) onRemove(on) else onPick(choice)
        if (!multi) onDismiss()
    }
    PickerFrame(onDismiss) {
        PickerContent(title, chosen, multi, choices, hidden, pick, onRemove, onCreate, onDismiss)
    }
}

/** A dialog in a wide window, else a sheet as tall as the screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (LocalWide.current) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.9f).fillMaxHeight(0.8f),
            ) { content() }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Box(Modifier.fillMaxSize().imePadding()) { content() }
        }
    }
}

@Composable
private fun PickerContent(
    title: String,
    chosen: List<TagRef>,
    multi: Boolean,
    choices: TagChoices?,
    hidden: Set<String>,
    pick: (TagChoice) -> Unit,
    onRemove: (TagRef) -> Unit,
    onCreate: ((name: String, category: String?) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val registry = LocalTags.current
    var query by remember { mutableStateOf("") }
    var active by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf<String?>(null) }
    val on = chosen.mapTo(HashSet()) { it.id }
    val offered = choices?.all.orEmpty().filter { it.id !in hidden }
    val clean = tagName(query)
    val hits = remember(query, offered) { TagSearch.find(query, offered).take(MAX_HITS) }
    // A tag with just this name is offered above, so «Создать» doesn't make a twin by accident.
    val create = onCreate?.takeIf { clean.isNotEmpty() && hits.none { it.tag.name.equals(clean, ignoreCase = true) } }
    // In the editor the field empties after each pick, ready for the next tag.
    val clear = {
        if (multi) query = ""
        active = 0
    }
    val choose = { tag: TagChoice ->
        pick(tag)
        clear()
    }
    val act = {
        when {
            active < hits.size -> choose(hits[active].tag)

            create != null -> {
                create(clean, category)
                clear()
            }
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.tag_picker_done)) }
        }
        if (multi && chosen.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chosen.forEach { tag ->
                    val name = registry.name(tag)
                    InputChip(
                        selected = false,
                        onClick = { onRemove(tag) },
                        label = { Text(name) },
                        trailingIcon = { Icon(Icons.Close, stringResource(Res.string.tag_remove, name), Modifier.size(16.dp)) },
                        colors = InputChipDefaults.inputChipColors(containerColor = tagColor(tag.id)),
                        border = null,
                        modifier = Modifier.hand(),
                    )
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                active = 0
            },
            placeholder = {
                Text(
                    stringResource(
                        if (onCreate !=
                            null
                        ) {
                            Res.string.tag_picker_find_or_create
                        } else {
                            Res.string.tag_picker_find
                        },
                    ),
                )
            },
            leadingIcon = { Icon(Icons.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { act() }),
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = 20.dp,
                vertical = 8.dp,
            ).focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val lines = hits.size + if (create != null) 1 else 0
                when (event.key) {
                    Key.DirectionDown -> active = (active + 1).coerceAtMost(lines - 1).coerceAtLeast(0)

                    Key.DirectionUp -> active = (active - 1).coerceAtLeast(0)

                    Key.Enter, Key.NumPadEnter -> act()

                    Key.Backspace -> if (multi && query.isEmpty() &&
                        chosen.isNotEmpty()
                    ) {
                        onRemove(chosen.last())
                    } else {
                        return@onPreviewKeyEvent false
                    }

                    else -> return@onPreviewKeyEvent false
                }
                true
            },
        )
        val all = choices ?: return@Column
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (clean.isEmpty()) {
                suggestions(all, offered, on, multi, registry, pick)
            } else {
                itemsIndexed(hits, key = { _, hit ->
                    hit.tag.id
                }) { i, hit -> HitRow(hit, hit.tag.id in on, i == active) { choose(hit.tag) } }
                if (create != null) {
                    item(key = "create") {
                        CreateRow(clean, category, registry, hits.size == active, { category = it }) {
                            create(clean, category)
                            clear()
                        }
                    }
                } else if (hits.isEmpty()) {
                    item(key = "nothing") {
                        Text(
                            stringResource(Res.string.tag_picker_nothing),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Before anything is typed: recent tags, those that go with the chosen ones, then every tag by category. Archived ones rest. */
private fun androidx.compose.foundation.lazy.LazyListScope.suggestions(
    all: TagChoices,
    offered: List<TagChoice>,
    on: Set<String>,
    multi: Boolean,
    registry: TagRegistry,
    pick: (TagChoice) -> Unit,
) {
    val live = offered.filterNot { it.archived }
    val byId = live.associateBy { it.id }
    val recent = all.usage.recent.mapNotNull(byId::get).filter { it.id !in on }.take(MAX_CHIPS)
    val together = if (multi) all.usage.together(on).mapNotNull(byId::get).take(MAX_CHIPS) else emptyList()
    if (together.isNotEmpty()) chipSection("together", { stringResource(Res.string.tag_picker_together) }, null, together, on, pick)
    if (recent.isNotEmpty()) chipSection("recent", { stringResource(Res.string.tag_picker_recent) }, null, recent, on, pick)
    val groups = live.groupBy { it.categoryId }
    registry.categories.filter { it.id in groups }.forEach { category ->
        val name = listOfNotNull(registry.categoryEmoji[category.id], category.name).joinToString(" ")
        chipSection("c:" + category.id, { name }, registry.categoryColor(category.id), groups.getValue(category.id), on, pick)
    }
    groups[null]?.let { chipSection("none", { stringResource(Res.string.tags_uncategorized) }, null, it, on, pick) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.chipSection(
    key: String,
    title: @Composable () -> String,
    color: String?,
    tags: List<TagChoice>,
    on: Set<String>,
    pick: (TagChoice) -> Unit,
) {
    item(key = key) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                swatch(color)?.let {
                    Box(Modifier.size(10.dp).background(it, CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag -> TagToggle(tag, tag.id in on) { pick(tag) } }
            }
        }
    }
}

/** A tag as a chip in its color, with a check while it's on. */
@Composable
private fun TagToggle(tag: TagChoice, on: Boolean, onClick: () -> Unit) {
    val color = tagColor(tag.id)
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(tag.name) },
        leadingIcon = if (on) {
            { Icon(Icons.Check, null, Modifier.size(16.dp)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = color,
            selectedContainerColor = color,
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = null,
        modifier = Modifier.hand(),
    )
}

/** A found tag: its name with the typed part in bold, then its category, uses and last day, or the description it matched by. */
@Composable
private fun HitRow(hit: TagHit, on: Boolean, active: Boolean, onClick: () -> Unit) {
    val tag = hit.tag
    Row(
        Modifier.fillMaxWidth().background(if (active) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .hand().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(
                12.dp,
            ).background(tagColor(tag.id), CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    val range = hit.range
                    if (range == null || range.last >= tag.name.length) {
                        append(tag.name)
                    } else {
                        append(tag.name.substring(0, range.first))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(tag.name.substring(range.first, range.last + 1)) }
                        append(tag.name.substring(range.last + 1))
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                tag.category,
                pluralStringResource(Res.plurals.gentle_count, tag.uses, tag.uses),
                tag.lastDay?.let { dayLabel(it) },
            ).joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            tag.description?.takeIf { hit.where == TagMatch.DESCRIPTION }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (on) Icon(Icons.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** «Создать «…»» with a category to put the new tag in. */
@Composable
private fun CreateRow(
    name: String,
    category: String?,
    registry: TagRegistry,
    active: Boolean,
    onCategory: (String?) -> Unit,
    onCreate: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(if (active) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().hand().clickable(onClick = onCreate).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Add, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(Res.string.tag_picker_create, name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val options =
            listOf<Pair<String?, String>>(null to stringResource(Res.string.tags_uncategorized)) +
                registry.categories.map { it.id to it.name }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options, key = { it.first ?: "" }) { (id, label) ->
                FilterChip(
                    selected = category == id,
                    onClick = { onCategory(id) },
                    label = { Text(label) },
                    colors =
                    id?.let {
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor =
                            swatch(registry.categoryColor(it)) ?: Color.Unspecified,
                        )
                    }
                        ?: FilterChipDefaults.filterChipColors(),
                    modifier = Modifier.hand(),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}

private const val MAX_HITS = 50
private const val MAX_CHIPS = 12
