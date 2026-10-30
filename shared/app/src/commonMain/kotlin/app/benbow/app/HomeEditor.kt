package app.benbow.app

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.block_checkin
import app.benbow.app.resources.block_checkin_about
import app.benbow.app.resources.block_drafts
import app.benbow.app.resources.block_drafts_about
import app.benbow.app.resources.block_drafts_empty
import app.benbow.app.resources.block_favorite
import app.benbow.app.resources.block_favorite_about
import app.benbow.app.resources.block_favorite_empty
import app.benbow.app.resources.block_notes
import app.benbow.app.resources.block_notes_about
import app.benbow.app.resources.block_notes_empty
import app.benbow.app.resources.block_on_this_day
import app.benbow.app.resources.block_on_this_day_about
import app.benbow.app.resources.block_on_this_day_empty
import app.benbow.app.resources.block_pinned
import app.benbow.app.resources.block_pinned_about
import app.benbow.app.resources.block_pinned_empty
import app.benbow.app.resources.block_prompt
import app.benbow.app.resources.block_prompt_about
import app.benbow.app.resources.block_stats
import app.benbow.app.resources.block_stats_about
import app.benbow.app.resources.block_stats_empty
import app.benbow.app.resources.block_tag
import app.benbow.app.resources.block_tag_about
import app.benbow.app.resources.block_tag_empty
import app.benbow.app.resources.block_tag_named
import app.benbow.app.resources.block_today
import app.benbow.app.resources.block_today_about
import app.benbow.app.resources.block_tracker
import app.benbow.app.resources.block_tracker_about
import app.benbow.app.resources.block_trackers
import app.benbow.app.resources.block_trackers_about
import app.benbow.app.resources.cancel
import app.benbow.app.resources.figure_days
import app.benbow.app.resources.figure_moments
import app.benbow.app.resources.figure_mood
import app.benbow.app.resources.figure_pictures
import app.benbow.app.resources.figure_since
import app.benbow.app.resources.figure_tags
import app.benbow.app.resources.home_also_months
import app.benbow.app.resources.home_block_add
import app.benbow.app.resources.home_block_down
import app.benbow.app.resources.home_block_empty
import app.benbow.app.resources.home_block_list
import app.benbow.app.resources.home_block_remove
import app.benbow.app.resources.home_block_up
import app.benbow.app.resources.home_editor_hint
import app.benbow.app.resources.home_editor_title
import app.benbow.app.resources.home_figures_one
import app.benbow.app.resources.home_figures_three
import app.benbow.app.resources.home_removed
import app.benbow.app.resources.home_reset
import app.benbow.app.resources.home_reset_done
import app.benbow.app.resources.home_size_l
import app.benbow.app.resources.home_size_list
import app.benbow.app.resources.home_size_m
import app.benbow.app.resources.home_size_s
import app.benbow.app.resources.home_tag_change
import app.benbow.app.resources.home_tag_newest
import app.benbow.app.resources.home_tag_pick
import app.benbow.app.resources.home_tag_random
import app.benbow.app.resources.home_tracker_change
import app.benbow.app.resources.tag_picker_done
import app.benbow.app.resources.untitled
import app.benbow.app.resources.veil_undo
import app.benbow.client.BlockSize
import app.benbow.client.HomeBlock
import app.benbow.client.HomeBlockType
import app.benbow.client.HomeLayout
import app.benbow.client.HomeLayoutEditor
import app.benbow.client.StatFigure
import app.benbow.client.TagRef
import app.benbow.client.addable
import app.benbow.client.figureCount
import app.benbow.client.figures
import app.benbow.client.packHome
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

internal val HomeBlockType.title: StringResource get() = when (this) {
    HomeBlockType.Checkin -> Res.string.block_checkin
    HomeBlockType.Prompt -> Res.string.block_prompt
    HomeBlockType.Pinned -> Res.string.block_pinned
    HomeBlockType.Today -> Res.string.block_today
    HomeBlockType.Drafts -> Res.string.block_drafts
    HomeBlockType.OnThisDay -> Res.string.block_on_this_day
    HomeBlockType.Favorite -> Res.string.block_favorite
    HomeBlockType.Stats -> Res.string.block_stats
    HomeBlockType.Notes -> Res.string.block_notes
    HomeBlockType.Tag -> Res.string.block_tag
    HomeBlockType.Trackers -> Res.string.block_trackers
    HomeBlockType.Tracker -> Res.string.block_tracker
}

private val HomeBlockType.about: StringResource get() = when (this) {
    HomeBlockType.Checkin -> Res.string.block_checkin_about
    HomeBlockType.Prompt -> Res.string.block_prompt_about
    HomeBlockType.Pinned -> Res.string.block_pinned_about
    HomeBlockType.Today -> Res.string.block_today_about
    HomeBlockType.Drafts -> Res.string.block_drafts_about
    HomeBlockType.OnThisDay -> Res.string.block_on_this_day_about
    HomeBlockType.Favorite -> Res.string.block_favorite_about
    HomeBlockType.Stats -> Res.string.block_stats_about
    HomeBlockType.Notes -> Res.string.block_notes_about
    HomeBlockType.Tag -> Res.string.block_tag_about
    HomeBlockType.Trackers -> Res.string.block_trackers_about
    HomeBlockType.Tracker -> Res.string.block_tracker_about
}

/** When a block that has nothing to show now (and so isn't on Home) comes up. */
private val HomeBlockType.whenEmpty: StringResource get() = when (this) {
    HomeBlockType.Pinned -> Res.string.block_pinned_empty
    HomeBlockType.Drafts -> Res.string.block_drafts_empty
    HomeBlockType.OnThisDay -> Res.string.block_on_this_day_empty
    HomeBlockType.Favorite -> Res.string.block_favorite_empty
    HomeBlockType.Stats -> Res.string.block_stats_empty
    HomeBlockType.Notes -> Res.string.block_notes_empty
    HomeBlockType.Tag -> Res.string.block_tag_empty
    else -> about
}

/** A block's name in the editor: a tag block goes by its tag, a tracker's by the tracker. */
@Composable
private fun blockName(block: HomeBlock): String {
    val tag = block.config[HomeBlock.TAG]
    val tracker = block.config[HomeBlock.TRACKER]
    return when {
        block.kind == HomeBlockType.Tag && tag != null -> LocalTags.current.name(TagRef(tag, tag))

        block.kind == HomeBlockType.Tracker && tracker != null -> LocalTrackerTitles.current[tracker]
            ?: stringResource(HomeBlockType.Tracker.title)

        else -> block.kind?.let { stringResource(it.title) }.orEmpty()
    }
}

/** Trackers' titles by id, for tracker blocks' names in the editor (ADR-050). */
private val LocalTrackerTitles = compositionLocalOf { emptyMap<String, String>() }

private val StatFigure.label: StringResource get() = when (this) {
    StatFigure.Days -> Res.string.figure_days
    StatFigure.Moments -> Res.string.figure_moments
    StatFigure.Pictures -> Res.string.figure_pictures
    StatFigure.Tags -> Res.string.figure_tags
    StatFigure.Since -> Res.string.figure_since
    StatFigure.Mood -> Res.string.figure_mood
}

private val BlockSize.label: StringResource get() = when (this) {
    BlockSize.S -> Res.string.home_size_s
    BlockSize.M -> Res.string.home_size_m
    BlockSize.L -> Res.string.home_size_l
    BlockSize.List -> Res.string.home_size_list
}

/**
 * «Настроить главную» (ADR-034): a schematic of Home's grid. A tap picks a block, then ↑/↓ or «Убрать» (with «Вернуть»);
 * «Добавить блок» lists the blocks Home doesn't have; «Вернуть обычную главную». Every change shows and is saved at
 * once; [home] (Home behind the sheet, live) tells which blocks have nothing to show now.
 */
@Composable
internal fun HomeEditor(services: AppServices, start: HomeLayout, home: HomeState, select: String?, columns: Int, onDismiss: () -> Unit) {
    val editor = remember { HomeLayoutEditor(services.repo, services.scope, start) }
    DisposableEffect(editor) { onDispose { editor.close() } }
    val layout by editor.layout.collectAsState()
    var selected by remember { mutableStateOf(select) }
    var adding by remember { mutableStateOf(false) }

    /** The tag picker for a tag block: its id, or "" for a new one. */
    var pickingTag by remember { mutableStateOf<String?>(null) }

    /** The same for a tracker block (ADR-050). */
    var pickingTracker by remember { mutableStateOf<String?>(null) }
    val trackers by remember { services.trackers.trackers() }.collectAsStateWithLifecycle(emptyList())
    val untitled = stringResource(Res.string.untitled)
    val trackerTitles = trackers.filter { !it.archived }.associate { it.id to trackerTitle(it, untitled) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun offerUndo(text: suspend () -> String, undo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(text(), getString(Res.string.veil_undo), duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }
    CompositionLocalProvider(LocalTrackerTitles provides trackerTitles) {
        PickerFrame(onDismiss) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.home_editor_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onDismiss, modifier = Modifier.hand()) { Text(stringResource(Res.string.tag_picker_done)) }
                    }
                    Text(
                        stringResource(Res.string.home_editor_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Schematic(layout, home, selected, columns) { selected = it }
                    val drawn = layout.blocks.filter { it.blockSize != null }
                    drawn.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let { at ->
                        val block = drawn[at]
                        val name = blockName(block)
                        BlockTools(
                            block,
                            name,
                            empty = !home.shows(block),
                            canUp = at > 0,
                            canDown = at < drawn.lastIndex,
                            onMove = { editor.move(block.id, it) },
                            onResize = { editor.resize(block.id, it) },
                            onConfigure = { key, value -> editor.configure(block.id, key, value) },
                            onPickTag = { pickingTag = block.id },
                            onPickTracker = { pickingTracker = block.id },
                            onRemove = {
                                val (removed, place) = editor.remove(block.id) ?: return@BlockTools
                                selected = null
                                offerUndo({ getString(Res.string.home_removed, name) }) { editor.put(removed, place) }
                            },
                        )
                    }
                    // A tracker's block only once there is one (ADR-050).
                    val addable = layout.addable().filter { kind -> kind != HomeBlockType.Tracker || trackerTitles.isNotEmpty() }
                    if (adding && addable.isNotEmpty()) {
                        Catalog(addable, onPick = { kind ->
                            when (kind) {
                                HomeBlockType.Tag -> pickingTag = ""

                                HomeBlockType.Tracker -> pickingTracker = ""

                                else -> {
                                    selected = editor.add(kind, after = selected).id
                                    adding = false
                                }
                            }
                        }, onCancel = { adding = false })
                    } else if (addable.isNotEmpty()) {
                        OutlinedButton({ adding = true }, modifier = Modifier.fillMaxWidth().hand()) {
                            Text(stringResource(Res.string.home_block_add))
                        }
                    }
                    if (layout.custom) {
                        TextButton({
                            val before = editor.reset()
                            selected = null
                            offerUndo({ getString(Res.string.home_reset_done) }) { editor.restore(before) }
                        }, modifier = Modifier.align(Alignment.CenterHorizontally).hand()) { Text(stringResource(Res.string.home_reset)) }
                    }
                    Spacer(Modifier.height(56.dp)) // room for the snackbar over the last button
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
    pickingTracker?.let { target ->
        TrackerPickDialog(trackerTitles, onDismiss = { pickingTracker = null }) { id ->
            if (target.isEmpty()) {
                selected = editor.add(HomeBlockType.Tracker, after = selected, config = mapOf(HomeBlock.TRACKER to id)).id
                adding = false
            } else {
                editor.configure(target, HomeBlock.TRACKER, id)
            }
            pickingTracker = null
        }
    }
    pickingTag?.let { target ->
        val current = layout.blocks.find { it.id == target }?.config?.get(HomeBlock.TAG)
        TagPicker(
            services,
            title = stringResource(Res.string.home_tag_pick),
            chosen = listOfNotNull(current?.let { TagRef(it, it) }),
            multi = false,
            onPick = { tag ->
                if (target.isEmpty()) {
                    selected = editor.add(HomeBlockType.Tag, after = selected, config = mapOf(HomeBlock.TAG to tag.id)).id
                    adding = false
                } else {
                    editor.configure(target, HomeBlock.TAG, tag.id)
                }
                pickingTag = null
            },
            onDismiss = { pickingTag = null },
        )
    }
}

/** Home's rows in miniature, in the same packing; tiles glide to their new places. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Schematic(layout: HomeLayout, home: HomeState, selected: String?, columns: Int, onSelect: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        LookaheadScope {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                packHome(layout.blocks, columns).forEach { row ->
                    key(row.first().id) {
                        Row(Modifier.animateBounds(this@LookaheadScope), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { block ->
                                Tile(
                                    block,
                                    empty = !home.shows(block),
                                    selected = block.id == selected,
                                    onClick = { onSelect(block.id) },
                                    modifier = Modifier.weight((block.blockSize?.span(columns) ?: columns).toFloat()),
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
private fun Tile(block: HomeBlock, empty: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val size = block.blockSize
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
        modifier = modifier.height(
            when (size) {
                BlockSize.L -> 88.dp
                BlockSize.List -> 60.dp
                else -> 44.dp
            },
        ).hand(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                blockName(block),
                style = MaterialTheme.typography.labelLarge,
                color = if (empty && !selected) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val notes = listOfNotNull(
                stringResource(Res.string.home_block_list).takeIf { size == BlockSize.List },
                stringResource(Res.string.home_block_empty).takeIf { empty },
            )
            if (notes.isNotEmpty()) {
                Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BlockTools(
    block: HomeBlock,
    name: String,
    empty: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
    onResize: (BlockSize) -> Unit,
    onConfigure: (key: String, value: String?) -> Unit,
    onPickTag: () -> Unit,
    onPickTracker: () -> Unit,
    onRemove: () -> Unit,
) {
    val kind = block.kind ?: return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                if (kind == HomeBlockType.Tag) stringResource(Res.string.block_tag_named, name) else name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(if (empty) kind.whenEmpty else kind.about),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
            )
            if (kind.sizes.size > 1) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kind.sizes.sortedBy { it.ordinal }.forEach { size ->
                        FilterChip(block.blockSize == size, {
                            onResize(size)
                        }, { Text(stringResource(size.label)) }, modifier = Modifier.hand())
                    }
                }
            }
            if (kind == HomeBlockType.Tag) {
                val random = block.config[HomeBlock.ORDER] == HomeBlock.RANDOM
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!random, {
                        onConfigure(HomeBlock.ORDER, null)
                    }, { Text(stringResource(Res.string.home_tag_newest)) }, modifier = Modifier.hand())
                    FilterChip(
                        random,
                        { onConfigure(HomeBlock.ORDER, HomeBlock.RANDOM) },
                        { Text(stringResource(Res.string.home_tag_random)) },
                        modifier = Modifier.hand(),
                    )
                }
                TextButton(onPickTag, modifier = Modifier.hand()) { Text(stringResource(Res.string.home_tag_change)) }
            }
            if (kind == HomeBlockType.Tracker) {
                TextButton(onPickTracker, modifier = Modifier.hand()) { Text(stringResource(Res.string.home_tracker_change)) }
            }
            if (kind == HomeBlockType.OnThisDay) {
                val months = block.config[HomeBlock.ALSO] == HomeBlock.MONTHS
                FilterChip(
                    months,
                    { onConfigure(HomeBlock.ALSO, if (months) null else HomeBlock.MONTHS) },
                    { Text(stringResource(Res.string.home_also_months)) },
                    modifier = Modifier.hand(),
                )
            }
            if (kind == HomeBlockType.Stats) {
                // S shows one figure: a tap picks it. M up to three: taps add and take away (a fourth lets the first go).
                val count = (block.blockSize ?: BlockSize.M).figureCount
                val chosen = block.figures
                Text(
                    stringResource(if (count == 1) Res.string.home_figures_one else Res.string.home_figures_three),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatFigure.entries.forEach { figure ->
                        FilterChip(figure in chosen, {
                            val next = when {
                                count == 1 -> listOf(figure)
                                figure in chosen -> (chosen - figure).ifEmpty { chosen }
                                else -> (chosen + figure).takeLast(count)
                            }
                            onConfigure(HomeBlock.FIGURES, next.joinToString(",") { it.value })
                        }, { Text(stringResource(figure.label)) }, modifier = Modifier.hand())
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onMove(-1) }, enabled = canUp, modifier = Modifier.hand()) {
                    Icon(Icons.ArrowUp, stringResource(Res.string.home_block_up), Modifier.size(22.dp))
                }
                IconButton({ onMove(1) }, enabled = canDown, modifier = Modifier.hand()) {
                    Icon(Icons.ArrowDown, stringResource(Res.string.home_block_down), Modifier.size(22.dp))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onRemove, modifier = Modifier.hand()) { Text(stringResource(Res.string.home_block_remove)) }
            }
        }
    }
}

/** The blocks Home doesn't have, each with a line on what it is for. */
@Composable
private fun Catalog(kinds: List<HomeBlockType>, onPick: (HomeBlockType) -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.home_block_add), style = MaterialTheme.typography.titleMedium)
        kinds.forEach { kind ->
            Surface(
                onClick = { onPick(kind) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().hand(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(kind.title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(kind.about),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(onCancel, modifier = Modifier.hand()) { Text(stringResource(Res.string.cancel)) }
    }
}
