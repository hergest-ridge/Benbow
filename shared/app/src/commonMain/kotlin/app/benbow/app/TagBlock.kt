package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.client.BlockSize
import app.benbow.client.EntrySummary
import app.benbow.client.Media
import app.benbow.client.SearchQuery
import app.benbow.client.TagRef
import coil3.compose.AsyncImage

/**
 * A tag block's entries (ADR-034): [query] is what search shows for it (the tag and "with media" while it has any,
 * else the tag alone), [count] how many that is here, [picks] the ones on its tiles.
 */
internal data class TagGallery(val tag: String, val query: SearchQuery, val count: Int, val picks: List<EntrySummary>)

/** How many tiles a tag block has: 2×2 in S, a strip of four in M, 3×3 in L. */
internal val BlockSize.cells: Int get() = if (this == BlockSize.L) 9 else 4

private val BlockSize.columns: Int get() = when (this) {
    BlockSize.M -> 4
    BlockSize.L -> 3
    else -> 2
}

/**
 * A tag's entries as a small gallery under its chip. In S the whole block opens search; in M and L a tile opens its
 * entry and the chip opens search with the same selection.
 */
@Composable
internal fun TagBlock(
    gallery: TagGallery,
    size: BlockSize,
    media: Media,
    onSearch: () -> Unit,
    onOpen: (EntrySummary) -> Unit,
    modifier: Modifier,
) {
    val whole = size == BlockSize.S
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(
            vertical = 4.dp,
        ).then(if (whole) Modifier.clickable(onClick = onSearch).hand() else Modifier),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.then(if (whole) Modifier else Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSearch).hand()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val name = LocalTags.current.name(TagRef(gallery.tag, gallery.tag))
                Surface(color = tagColor(gallery.tag), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(
                    if (gallery.count >= SEARCH_LIMIT) "$SEARCH_LIMIT+" else gallery.count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            gallery.picks.take(size.cells).chunked(size.columns).let { rows ->
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(size.columns) { i ->
                            val entry = row.getOrNull(i)
                            val cell = Modifier.weight(1f).aspectRatio(1f)
                            if (entry == null) {
                                Spacer(cell)
                            } else {
                                EntryTile(entry, media, cell, onClick = if (whole) null else ({ onOpen(entry) }))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * An entry as a square tile: its picture; without one, its first line on paper in the entry's color (a note) or a
 * note sign (sound or files); under the curtain, only the curtain. [onClick] null leaves taps to what holds it.
 */
@Composable
internal fun EntryTile(entry: EntrySummary, media: Media, modifier: Modifier, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val curtained = entry.curtained(LocalGentle.current)
    val paper = (if (curtained) null else swatch(entry.background)) ?: colors.surfaceContainerHigh
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(paper)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).hand() else Modifier),
    ) {
        val cover = entry.cover
        when {
            curtained -> Curtain(Modifier.fillMaxSize())

            cover != null -> {
                val load by rememberMediaFile(media, cover)
                (load as? MediaLoad.Ready)?.let { AsyncImage(it.file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }

            entry.mediaCount > 0 -> Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(Icons.MusicNote, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                TileText(entry.title.ifBlank { entry.preview }, lines = 2)
            }

            else -> Box(Modifier.fillMaxSize().padding(8.dp)) { TileText(entry.title.ifBlank { entry.preview }, lines = 4) }
        }
    }
}

@Composable
private fun TileText(text: String, lines: Int) {
    Text(text, style = MaterialTheme.typography.labelMedium, maxLines = lines, overflow = TextOverflow.Ellipsis)
}

/** Search finds at most this many (the `search` query's limit). */
private const val SEARCH_LIMIT = 300
