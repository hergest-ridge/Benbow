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
import androidx.compose.ui.platform.LocalHapticFeedback
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
    val open: (EntrySummary) -> Unit,
    val write: (template: String) -> Unit,
    val newEntry: () -> Unit,
    val checkin: (mood: Int, emotions: List<String>, text: String) -> Unit,
    /** A block's long-press menu: «Убрать с главной», «Настроить главную» (with the block picked). */
    val remove: (HomeBlock) -> Unit,
    val customize: (HomeBlock?) -> Unit,
    /** Search with a block's selection (a tag block's chip). */
    val search: (SearchQuery) -> Unit,
)

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
    else -> true
}

/**
 * The blocks row by row (ADR-034). A list block is lazy items of its own, so a long day scrolls as before; other rows
 * share their width by span. [after] adds what belongs right under a block (the kindness card under the check-in).
 */
internal fun LazyListScope.homeBlocks(
    home: HomeState,
    actions: HomeActions,
    services: AppServices,
    after: LazyListScope.(HomeBlock) -> Unit,
) {
    packHome(home.blocks).forEach { row ->
        val single = row.singleOrNull()
        if (single?.blockSize == BlockSize.List) {
            listBlock(single, home, actions, services)
        } else {
            item(key = row.joinToString("+") { it.id }) {
                // Side by side, blocks keep 12 dp from the edges and 8 dp between them, and are as tall as the taller one.
                val alone = row.size == 1
                val side = if (alone) Modifier else Modifier.height(IntrinsicSize.Max)
                Row(Modifier.fillMaxWidth().padding(horizontal = if (alone) 0.dp else 8.dp).then(side)) {
                    row.forEach { block ->
                        Held(block, actions, Modifier.weight((block.blockSize?.span(2) ?: 2).toFloat())) {
                            val edges = if (alone) {
                                Modifier.padding(
                                    horizontal = 12.dp,
                                )
                            } else {
                                Modifier.padding(horizontal = 4.dp).fillMaxHeight()
                            }
                            TileBlock(block, home, actions, services, edges)
                        }
                    }
                }
            }
        }
        row.forEach { after(it) }
    }
    item(key = "customize") {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
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

@Composable
private fun TileBlock(block: HomeBlock, home: HomeState, actions: HomeActions, services: AppServices, edges: Modifier) {
    when (block.kind) {
        HomeBlockType.Checkin -> CheckinCard(actions.checkin)

        HomeBlockType.Prompt -> Prompt(Prompts.ofTheDay(home.today), stringResource(Res.string.home_answer)) {
            actions.write(Templates.PROMPT.id)
        }

        HomeBlockType.Favorite -> home.resurfaced?.let { entry ->
            if (block.blockSize == BlockSize.S) {
                FavoriteTile(entry, services.media, onOpen = { actions.open(entry) }, modifier = edges)
                return@let
            }
            Column {
                SectionTitle(stringResource(Res.string.home_resurfaced))
                EntryCard(entry, onClick = { actions.open(entry) }, media = services.media, showDay = true)
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
        HomeBlockType.Pinned -> section(block, Res.string.home_pinned, home.pinned, actions, services)

        HomeBlockType.Drafts -> section(block, Res.string.home_drafts, home.drafts, actions, services)

        HomeBlockType.Today -> {
            held(block, actions, "$key/title") { SectionTitle(stringResource(Res.string.home_today)) }
            if (home.todays.isEmpty()) {
                held(block, actions, "$key/empty") {
                    Prompt(stringResource(Res.string.home_today_empty), stringResource(Res.string.home_write), actions.newEntry)
                }
            } else {
                heldItems(block, actions, home.todays, key = {
                    "$key/${it.id}"
                }) { EntryCard(it, onClick = { actions.open(it) }, media = services.media) }
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
                    EntryCard(it, onClick = { actions.open(it) }, media = services.media)
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
    actions: HomeActions,
    services: AppServices,
) {
    if (entries.isEmpty()) return
    val key = block.id
    held(block, actions, "$key/title") { SectionTitle(stringResource(title)) }
    heldItems(block, actions, entries, key = {
        "$key/${it.id}"
    }) { EntryCard(it, onClick = { actions.open(it) }, media = services.media, showDay = true) }
}

/** A list block's pieces are lazy items, each with the block's long-press menu. */
private fun LazyListScope.held(block: HomeBlock, actions: HomeActions, key: String, content: @Composable () -> Unit) {
    item(key = key) { Held(block, actions) { content() } }
}

private fun LazyListScope.heldItems(
    block: HomeBlock,
    actions: HomeActions,
    entries: List<EntrySummary>,
    key: (EntrySummary) -> String,
    content: @Composable (EntrySummary) -> Unit,
) {
    items(entries, key = key) { Held(block, actions) { content(it) } }
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
private fun Prompt(text: String, action: String, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            TextButton(onAction, modifier = Modifier.hand()) { Text(action) }
        }
    }
}
