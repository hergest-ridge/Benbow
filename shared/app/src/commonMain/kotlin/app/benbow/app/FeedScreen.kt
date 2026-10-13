package app.benbow.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.comments
import app.benbow.app.resources.feed_empty
import app.benbow.app.resources.feed_title
import app.benbow.client.FeedItem
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** A row of the feed: an entry, or the day line drawn above a day's entries. */
internal sealed interface FeedRow {
    val key: String

    data class Item(val item: FeedItem) : FeedRow {
        override val key get() = item.summary.id
    }

    data class Day(val day: String) : FeedRow {
        override val key get() = "day:$day"
    }
}

/** Newest first, top down: each day's line above its entries. */
internal fun feedRows(items: List<FeedItem>): List<FeedRow> = buildList {
    items.forEachIndexed { i, item ->
        if (items.getOrNull(i - 1)?.summary?.day != item.summary.day) add(FeedRow.Day(item.summary.day))
        add(FeedRow.Item(item))
    }
}

internal val REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢", "🔥")

internal class FeedViewModel(private val services: AppServices) : ViewModel() {
    val rows: StateFlow<List<FeedRow>?> = services.repo.feed().map(
        ::feedRows,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun react(id: String, emoji: String, on: Boolean) {
        services.scope.launch { services.repo.toggleReaction(id, emoji, on) }
    }
}

/** Everything as a stream, newest on top; long-press to react. Writing happens on Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { FeedViewModel(services) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val status by services.sync.status.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.feed_title)) },
                actions = { SyncBadge(status) { nav.open(Route.Server) } },
            )
        },
    ) { padding ->
        val list = rows ?: return@Scaffold
        if (list.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(Res.string.feed_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(list, key = { it.key }) { row ->
                when (row) {
                    is FeedRow.Day -> DayHeader(row.day)

                    is FeedRow.Item -> FeedBubble(
                        services,
                        row.item,
                        onOpen = { nav.open(Route.Editor(row.item.summary.id, row.item.summary.day, readOnly = true)) },
                        onComments = { nav.open(Route.Editor(row.item.summary.id, row.item.summary.day, readOnly = true)) },
                    ) { emoji, on ->
                        vm.react(row.item.summary.id, emoji, on)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedBubble(
    services: AppServices,
    item: FeedItem,
    onOpen: () -> Unit,
    onComments: () -> Unit,
    onReact: (String, Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val entry = item.summary
    var picking by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Surface(
            color = swatch(entry.background) ?: colors.surfaceContainerLow,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = onOpen, onLongClick = {
                picking =
                    true
            }),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entry.cover?.let { sha ->
                    val load by rememberMediaFile(services.media, sha)
                    (load as? MediaLoad.Ready)?.let {
                        AsyncImage(
                            it.file,
                            null,
                            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                if (item.text.isNotBlank()) {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (item.kind == "journal") 6 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.tags.isNotEmpty()) TagRibbon(entry.tags)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.reactions.sorted().forEach { emoji ->
                        Surface(
                            onClick = { onReact(emoji, false) },
                            color = colors.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(emoji, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
                    }
                    if (item.comments > 0) {
                        Text(
                            "💬 ${item.comments}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.clip(
                                RoundedCornerShape(8.dp),
                            ).clickable(onClick = onComments).padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    Box(Modifier.weight(1f))
                    if (entry.favorite) Text("★", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    moodEmoji(entry.mood)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    if (entry.mediaCount >
                        1
                    ) {
                        Text("+${entry.mediaCount - 1}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Text(timeLabel(entry.createdAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(picking, { picking = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.comments)) }, onClick = {
                picking = false
                onComments()
            })
            Row(Modifier.padding(horizontal = 8.dp)) {
                REACTIONS.forEach { emoji ->
                    val on = emoji in item.reactions
                    DropdownMenuItem(text = { Text(emoji, style = MaterialTheme.typography.titleLarge) }, onClick = {
                        picking = false
                        onReact(emoji, !on)
                    }, modifier = Modifier.size(width = 52.dp, height = 48.dp))
                }
            }
        }
    }
}
