package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.ago_half_year
import app.benbow.app.resources.ago_month
import app.benbow.app.resources.home_on_this_day
import app.benbow.app.resources.home_resurfaced
import app.benbow.app.resources.years_ago
import app.benbow.client.EntrySummary
import app.benbow.client.HomeBlock
import app.benbow.client.Media
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** An entry On this day brings back, and how long ago it was: 12 a year, 1 a month, 6 half a year. */
internal data class LookBack(val entry: EntrySummary, val monthsAgo: Int)

/** What an On this day block shows: years ago, and a month and half a year ago when it was asked to. */
internal fun HomeState.lookBack(block: HomeBlock): List<LookBack> =
    if (block.config[HomeBlock.ALSO] == HomeBlock.MONTHS) onThisDay else onThisDay.filter { it.monthsAgo % 12 == 0 }

@Composable
internal fun agoLabel(monthsAgo: Int): String = when (monthsAgo) {
    1 -> stringResource(Res.string.ago_month)
    6 -> stringResource(Res.string.ago_half_year)
    else -> (monthsAgo / 12).let { pluralStringResource(Res.plurals.years_ago, it, it) }
}

/** On this day in M: a strip of cards, nearest first, each with its picture when it has one. */
@Composable
internal fun OnThisDayStrip(items: List<LookBack>, media: Media, onOpen: (EntrySummary) -> Unit) {
    Column {
        BlockTitle(stringResource(Res.string.home_on_this_day))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.entry.id }) { item ->
                val entry = item.entry
                val paper = swatch(entry.background) ?: MaterialTheme.colorScheme.surfaceContainerLow
                // The color and the look are drawn under the card (a Surface would paint over the look).
                Surface(
                    onClick = { onOpen(entry) },
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(200.dp).padding(vertical = 4.dp).hand().clip(RoundedCornerShape(16.dp)).background(paper)
                        .then(entryBackdrop(entry.id, entry.look, paper, muted = true)),
                ) {
                    Column {
                        entry.cover?.let { cover -> Picture(media, cover, Modifier.fillMaxWidth().height(112.dp)) }
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                agoLabel(item.monthsAgo),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Words(entry, if (entry.cover == null) 4 else 2)
                        }
                    }
                }
            }
        }
    }
}

/** On this day in L: one large page per entry to swipe through, its picture full size, the time ago over it. */
@Composable
internal fun OnThisDayPager(items: List<LookBack>, media: Media, onOpen: (EntrySummary) -> Unit) {
    val pager = rememberPagerState { items.size }
    Column {
        BlockTitle(stringResource(Res.string.home_on_this_day))
        Box {
            HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 12.dp), pageSpacing = 8.dp, key = {
                items[it].entry.id
            }) { page ->
                val item = items[page]
                val entry = item.entry
                val paper = swatch(entry.background) ?: MaterialTheme.colorScheme.surfaceContainerLow
                Box(
                    Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)).background(paper)
                        .then(entryBackdrop(entry.id, entry.look, paper, muted = true))
                        .clickable { onOpen(entry) }.hand(),
                ) {
                    val cover = entry.cover
                    if (cover != null) {
                        Picture(media, cover, Modifier.fillMaxSize())
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            contentColor = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(agoLabel(item.monthsAgo), style = MaterialTheme.typography.labelMedium)
                                if (entry.title.isNotBlank()) {
                                    Text(
                                        entry.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    } else {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                agoLabel(item.monthsAgo),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Words(entry, 8)
                        }
                    }
                }
            }
            PagerArrows(pager)
        }
        if (items.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                repeat(items.size) { i ->
                    val color = if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    Box(Modifier.padding(horizontal = 3.dp).size(6.dp).clip(CircleShape).background(color))
                }
            }
        }
    }
}

/** The favorite in S: its picture (or its first line on paper) with its day, under the block's name. */
@Composable
internal fun FavoriteTile(entry: EntrySummary, media: Media, onOpen: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).hand(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.home_resurfaced),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            EntryTile(entry, media, Modifier.fillMaxWidth().aspectRatio(1f), onClick = null)
            Text(
                dayLabel(entry.day),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun BlockTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Picture(media: Media, sha256: String, modifier: Modifier) {
    val load by rememberMediaFile(media, sha256)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        (load as? MediaLoad.Ready)?.let { AsyncImage(it.file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun Words(entry: EntrySummary, lines: Int) {
    if (entry.title.isNotBlank()) {
        Text(
            entry.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (entry.preview.isNotBlank()) {
        Text(
            entry.preview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
