package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.home_answer
import app.benbow.app.resources.home_block_remove_long
import app.benbow.app.resources.home_customize
import app.benbow.app.resources.home_drafts
import app.benbow.app.resources.home_on_this_day
import app.benbow.app.resources.home_pinned
import app.benbow.app.resources.home_resurfaced
import app.benbow.app.resources.home_today
import app.benbow.app.resources.home_today_empty
import app.benbow.app.resources.home_write
import app.benbow.app.resources.years_ago
import app.benbow.client.BlockSize
import app.benbow.client.BoardNote
import app.benbow.client.EntrySummary
import app.benbow.client.HomeBlock
import app.benbow.client.HomeBlockType
import app.benbow.client.Prompts
import app.benbow.client.SearchQuery
import app.benbow.client.Templates
import app.benbow.client.figures
import app.benbow.client.packHome
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What Home's blocks do when tapped. */
internal class HomeActions(
    /** Reads an entry as in the feed, with its comments. */
    val open: (EntrySummary) -> Unit,
    /** Goes on writing a draft. */
    val edit: (EntrySummary) -> Unit,
    val write: (template: String) -> Unit,
    val newEntry: () -> Unit,
    val checkin: (mood: Int, emotions: List<String>, text: String) -> Unit,
    /** A block's long-press menu: «Убрать с главной», «Настроить главную» (with the block picked). */
    val remove: (HomeBlock) -> Unit,
    val customize: (HomeBlock?) -> Unit,
    /** Search with a block's selection (a tag block's chip). */
    val search: (SearchQuery) -> Unit,
    /** The board's block (ADR-040): a note opened, the board itself. */
    val openNote: (BoardNote) -> Unit = {},
    val board: () -> Unit = {},
    /** Trackers' blocks (ADR-050): the trackers, a tracker's sheet. */
    val trackers: () -> Unit = {},
    val openTracker: (String) -> Unit = {},
    /** Posts opened in place with «Показать ещё», by «<block>/<entry>». */
    val expanded: MutableMap<String, Boolean> = mutableMapOf(),
) {
    /** A post's text for its card (owner): in full with its paragraphs and marks, cut after a while with «Показать ещё». */
    fun post(home: HomeState, block: HomeBlock, entry: EntrySummary): PostText? {
        val body = home.bodies[entry.id] ?: return null
        val key = "${block.id}/${entry.id}"
        return PostText(body, expanded[key] == true) { expanded[key] = true }
    }
}

/** A block (or a piece of a list block): a long press anywhere on it opens its menu and no click follows. */
@Composable
private fun Held(block: HomeBlock, actions: HomeActions, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.longPress {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menu = true
        },
    ) {
        content()
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.home_block_remove_long)) }, onClick = {
                menu = false
                actions.remove(block)
            }, modifier = Modifier.hand())
            DropdownMenuItem(text = { Text(stringResource(Res.string.home_customize)) }, onClick = {
                menu = false
                actions.customize(block)
            }, modifier = Modifier.hand())
        }
    }
}

/** Whether a block has something to show; an empty one leaves no gap (the empty day stays: it invites). */
internal fun HomeState.shows(block: HomeBlock): Boolean = when (block.kind) {
    HomeBlockType.Pinned -> pinned.isNotEmpty()
    HomeBlockType.Drafts -> drafts.isNotEmpty()
    HomeBlockType.OnThisDay -> lookBack(block).isNotEmpty()
    HomeBlockType.Favorite -> resurfaced != null
    HomeBlockType.Stats -> stats != null && block.figures.any(stats::has)
    HomeBlockType.Tag -> galleries[block.id]?.picks?.isNotEmpty() == true
    HomeBlockType.Notes -> notes.isNotEmpty()
    else -> true
}

/**
 * The blocks row by row (ADR-034), [columns] to a row (two on a phone, four on a wide Home). A list block is lazy items
 * of its own, so a long day scrolls as before; other rows share their width by span. [after] adds what belongs right
 * under a block (the kindness card under the check-in). Rows glide when blocks come, go or move.
 */
internal fun LazyListScope.homeBlocks(
    home: HomeState,
    actions: HomeActions,
    services: AppServices,
    columns: Int,
    after: LazyListScope.(HomeBlock) -> Unit,
) {
    packHome(home.blocks, columns).forEach { row ->
        val single = row.singleOrNull()
        if (single?.blockSize == BlockSize.List) {
            listBlock(single, home, actions, services)
        } else {
            item(key = row.joinToString("+") { it.id }) { TileRow(row, home, actions, services, columns, Modifier.animateItem()) }
        }
        row.forEach { after(it) }
    }
    item(key = "customize") {
        Box(Modifier.animateItem().fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            TextButton({ actions.customize(null) }, modifier = Modifier.hand()) {
                Icon(Icons.Blocks, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(Res.string.home_customize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * One row of blocks. A block keeps 12 dp at its sides, as it does alone; side by side the row draws each 8 dp wider on
 * both sides than its share, so they keep 12 dp from the edges and 8 dp between them. Side by side they are as tall as
 * the taller one, unless one scrolls sideways (On this day: a lazy list can't be asked its height in advance).
 */
@Composable
private fun TileRow(row: List<HomeBlock>, home: HomeState, actions: HomeActions, services: AppServices, columns: Int, modifier: Modifier) {
    val alone = row.size == 1
    // A lazy strip or pager can't be measured ahead: rows with one keep their own heights.
    val even = !alone && row.none { it.kind == HomeBlockType.OnThisDay || it.kind == HomeBlockType.Notes }
    val sides = Modifier.padding(horizontal = if (alone) 0.dp else 8.dp)
    Row(modifier.fillMaxWidth().then(sides).then(if (even) Modifier.height(IntrinsicSize.Max) else Modifier)) {
        row.forEach { block ->
            val share = Modifier.weight((block.blockSize?.span(columns) ?: columns).toFloat())
            val tall = if (even) Modifier.fillMaxHeight() else Modifier
            Held(block, actions, if (alone) share else share.bleed(8.dp)) {
                TileBlock(block, home, actions, services, Modifier.padding(horizontal = 12.dp).then(tall), tall)
            }
        }
    }
}

/** Draws the content [by] wider than its place on each side (a negative padding: the room inside cards stays theirs). */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) return@layout measurable.measure(constraints).let { layout(it.width, it.height) { it.place(0, 0) } }
    val extra = by.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** [edges]: the room at a block's sides and its height, for blocks that leave both to the row; [tall] the height alone. */
@Composable
private fun TileBlock(block: HomeBlock, home: HomeState, actions: HomeActions, services: AppServices, edges: Modifier, tall: Modifier) {
    when (block.kind) {
        HomeBlockType.Checkin -> CheckinCard(actions.checkin, tall)

        HomeBlockType.Prompt -> Prompt(Prompts.ofTheDay(home.today), stringResource(Res.string.home_answer), tall) {
            actions.write(Templates.PROMPT.id)
        }

        HomeBlockType.Favorite -> home.resurfaced?.let { entry ->
            if (block.blockSize == BlockSize.S) {
                FavoriteTile(entry, services.media, onOpen = { actions.open(entry) }, modifier = edges)
                return@let
            }
            Column {
                SectionTitle(stringResource(Res.string.home_resurfaced))
                EntryCard(entry, onClick = {
                    actions.open(entry)
                }, media = services.media, showDay = true, post = actions.post(home, block, entry))
            }
        }

        HomeBlockType.Stats -> home.stats?.let { stats ->
            StatsBlock(stats, block.figures, block.blockSize ?: BlockSize.M, home.today, edges)
        }

        HomeBlockType.OnThisDay -> {
            val items = home.lookBack(block)
            if (block.blockSize == BlockSize.L) {
                OnThisDayPager(items, services.media, actions.open)
            } else {
                OnThisDayStrip(items, services.media, actions.open)
            }
        }

        HomeBlockType.Notes -> NotesBlock(
            home.notes,
            block.blockSize ?: BlockSize.M,
            services.media,
            actions.openNote,
            actions.board,
            edges,
        )

        HomeBlockType.Trackers -> TrackersBlock(services, block.blockSize ?: BlockSize.M, actions.trackers, actions.openTracker, edges)

        HomeBlockType.Tracker -> block.config[HomeBlock.TRACKER]?.let { tracker ->
            TrackerBlock(services, tracker, block.blockSize ?: BlockSize.S, actions.openTracker, edges)
        }

        HomeBlockType.Tag -> home.galleries[block.id]?.let { gallery ->
            TagBlock(
                gallery,
                block.blockSize ?: BlockSize.M,
                services.media,
                onSearch = { actions.search(gallery.query) },
                onOpen = actions.open,
                modifier = edges,
            )
        }

        else -> Unit
    }
}

private fun LazyListScope.listBlock(block: HomeBlock, home: HomeState, actions: HomeActions, services: AppServices) {
    val key = block.id
    when (block.kind) {
        HomeBlockType.Pinned -> section(block, Res.string.home_pinned, home.pinned, home, actions, services)

        HomeBlockType.Drafts -> section(block, Res.string.home_drafts, home.drafts, home, actions, services, open = actions.edit)

        HomeBlockType.Today -> {
            held(block, actions, "$key/title") { SectionTitle(stringResource(Res.string.home_today)) }
            if (home.todays.isEmpty()) {
                held(block, actions, "$key/empty") {
                    Prompt(stringResource(Res.string.home_today_empty), stringResource(Res.string.home_write), onAction = actions.newEntry)
                }
            } else {
                heldItems(block, actions, home.todays, key = {
                    "$key/${it.id}"
                }) { EntryCard(it, onClick = { actions.open(it) }, media = services.media, post = actions.post(home, block, it)) }
            }
        }

        HomeBlockType.OnThisDay -> {
            held(block, actions, "$key/title") { SectionTitle(stringResource(Res.string.home_on_this_day)) }
            home.lookBack(block).groupBy { it.monthsAgo }.forEach { (ago, items) ->
                held(block, actions, "$key/$ago") {
                    Text(
                        agoLabel(ago),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 2.dp),
                    )
                }
                heldItems(block, actions, items.map { it.entry }, key = { "$key/${it.id}" }) {
                    EntryCard(it, onClick = { actions.open(it) }, media = services.media, post = actions.post(home, block, it))
                }
            }
        }

        else -> Unit
    }
}

private fun LazyListScope.section(
    block: HomeBlock,
    title: StringResource,
    entries: List<EntrySummary>,
    home: HomeState,
    actions: HomeActions,
    services: AppServices,
    open: (EntrySummary) -> Unit = actions.open,
) {
    if (entries.isEmpty()) return
    val key = block.id
    held(block, actions, "$key/title") { SectionTitle(stringResource(title)) }
    heldItems(block, actions, entries, key = {
        "$key/${it.id}"
    }) { EntryCard(it, onClick = { open(it) }, media = services.media, showDay = true, post = actions.post(home, block, it)) }
}

/** A list block's pieces are lazy items, each with the block's long-press menu. */
private fun LazyListScope.held(block: HomeBlock, actions: HomeActions, key: String, content: @Composable () -> Unit) {
    item(key = key) { Held(block, actions, Modifier.animateItem()) { content() } }
}

private fun LazyListScope.heldItems(
    block: HomeBlock,
    actions: HomeActions,
    entries: List<EntrySummary>,
    key: (EntrySummary) -> String,
    content: @Composable (EntrySummary) -> Unit,
) {
    items(entries, key = key) { Held(block, actions, Modifier.animateItem()) { content(it) } }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Prompt(text: String, action: String, modifier: Modifier = Modifier, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            TextButton(onAction, modifier = Modifier.hand()) { Text(action) }
        }
    }
}
