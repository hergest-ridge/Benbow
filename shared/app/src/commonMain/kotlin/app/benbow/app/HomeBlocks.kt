package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.days_written
import app.benbow.app.resources.home_answer
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
import app.benbow.client.Templates
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
)

/** Whether a block has something to show; an empty one leaves no gap (the empty day stays: it invites). */
internal fun HomeState.shows(block: HomeBlock): Boolean = when (block.kind) {
    HomeBlockType.Pinned -> pinned.isNotEmpty()
    HomeBlockType.Drafts -> drafts.isNotEmpty()
    HomeBlockType.OnThisDay -> onThisDay.isNotEmpty()
    HomeBlockType.Favorite -> resurfaced != null
    HomeBlockType.Stats -> daysThisMonth > 0
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
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { block ->
                        Box(Modifier.weight((block.blockSize?.span(2) ?: 2).toFloat())) { TileBlock(block, home, actions, services) }
                    }
                }
            }
        }
        row.forEach { after(it) }
    }
}

@Composable
private fun TileBlock(block: HomeBlock, home: HomeState, actions: HomeActions, services: AppServices) {
    when (block.kind) {
        HomeBlockType.Checkin -> CheckinCard(actions.checkin)

        HomeBlockType.Prompt -> Prompt(Prompts.ofTheDay(home.today), stringResource(Res.string.home_answer)) {
            actions.write(Templates.PROMPT.id)
        }

        HomeBlockType.Favorite -> home.resurfaced?.let { entry ->
            Column {
                SectionTitle(stringResource(Res.string.home_resurfaced))
                EntryCard(entry, onClick = { actions.open(entry) }, media = services.media, showDay = true)
            }
        }

        HomeBlockType.Stats -> {
            val days = home.daysThisMonth.toInt()
            Text(
                pluralStringResource(Res.plurals.days_written, days, days),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }

        else -> Unit
    }
}

private fun LazyListScope.listBlock(block: HomeBlock, home: HomeState, actions: HomeActions, services: AppServices) {
    val key = block.id
    when (block.kind) {
        HomeBlockType.Pinned -> section(key, Res.string.home_pinned, home.pinned, actions, services)

        HomeBlockType.Drafts -> section(key, Res.string.home_drafts, home.drafts, actions, services)

        HomeBlockType.Today -> {
            item(key = "$key/title") { SectionTitle(stringResource(Res.string.home_today)) }
            if (home.todays.isEmpty()) {
                item(key = "$key/empty") {
                    Prompt(stringResource(Res.string.home_today_empty), stringResource(Res.string.home_write), actions.newEntry)
                }
            } else {
                items(home.todays, key = { "$key/${it.id}" }) { EntryCard(it, onClick = { actions.open(it) }, media = services.media) }
            }
        }

        HomeBlockType.OnThisDay -> {
            item(key = "$key/title") { SectionTitle(stringResource(Res.string.home_on_this_day)) }
            home.onThisDay.groupBy { it.day.take(4).toInt() }.forEach { (year, entries) ->
                val ago = home.today.take(4).toInt() - year
                item(key = "$key/$year") {
                    Text(
                        pluralStringResource(Res.plurals.years_ago, ago, ago),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 2.dp),
                    )
                }
                items(entries, key = { "$key/${it.id}" }) { EntryCard(it, onClick = { actions.open(it) }, media = services.media) }
            }
        }

        else -> Unit
    }
}

private fun LazyListScope.section(
    key: String,
    title: StringResource,
    entries: List<EntrySummary>,
    actions: HomeActions,
    services: AppServices,
) {
    if (entries.isEmpty()) return
    item(key = "$key/title") { SectionTitle(stringResource(title)) }
    items(entries, key = { "$key/${it.id}" }) { EntryCard(it, onClick = { actions.open(it) }, media = services.media, showDay = true) }
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
