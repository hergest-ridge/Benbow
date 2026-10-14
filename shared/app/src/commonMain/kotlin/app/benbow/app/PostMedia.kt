package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.core.sync.AttachmentState
import coil3.compose.AsyncImage

/**
 * A read post's pictures that aren't placed in its text ([PostMedia]): one swipeable card like the feed, an album grid
 * like Telegram's, or large thumbnails (one or two in view). Sounds and files follow as rows; everything opens the viewer.
 */
@Composable
internal fun PostMedia(
    services: AppServices,
    attachments: List<AttachmentState>,
    layout: PostMedia,
    onOpen: (AttachmentState) -> Unit,
    modifier: Modifier = Modifier,
    onRename: ((attachmentId: String, caption: String?) -> Unit)? = null,
    entryId: String? = null,
    transcripts: Map<String, String> = emptyMap(),
) {
    if (attachments.isEmpty()) return
    val (visual, other) = attachments.partition { it.meta.kind == "image" || it.meta.kind == "video" }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visual.isNotEmpty()) {
            when (layout) {
                PostMedia.Carousel -> MediaCarousel(services.media, visual, onOpen)
                PostMedia.Grid -> AlbumGrid(services, visual, onOpen)
                PostMedia.Strip -> LargeStrip(services, visual, onOpen)
            }
        }
        other.forEach {
            if (it.meta.kind == "voice" ||
                it.meta.kind == "audio"
            ) {
                VoiceRow(
                    services,
                    entryId,
                    it,
                    transcripts[it.meta.id],
                    onRename = onRename?.let { rename -> { caption -> rename(it.meta.id, caption) } },
                )
            } else {
                FileRow(it) { onOpen(it) }
            }
        }
    }
}

/** Telegram-like albums: one wide, two side by side, one big and two small, then rows of two and three; "+N" past six. */
@Composable
private fun AlbumGrid(services: AppServices, items: List<AttachmentState>, onOpen: (AttachmentState) -> Unit) {
    val gap = 3.dp
    val shown = items.take(6)
    val more = items.size - shown.size
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), verticalArrangement = Arrangement.spacedBy(gap)) {
        when (shown.size) {
            1 -> Tile(services, shown[0], Modifier.fillMaxWidth().aspectRatio(ratioOf(shown[0]).coerceIn(0.8f, 1.9f)), onOpen = onOpen)

            2 -> Row(Modifier.fillMaxWidth().aspectRatio(1.6f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                shown.forEach { Tile(services, it, Modifier.weight(1f).fillMaxHeight(), onOpen = onOpen) }
            }

            3 -> Row(Modifier.fillMaxWidth().aspectRatio(1.4f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Tile(services, shown[0], Modifier.weight(2f).fillMaxHeight(), onOpen = onOpen)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Tile(services, shown[1], Modifier.weight(1f).fillMaxWidth(), onOpen = onOpen)
                    Tile(services, shown[2], Modifier.weight(1f).fillMaxWidth(), onOpen = onOpen)
                }
            }

            else -> shown.chunked(if (shown.size == 4) 2 else 3).let { rows ->
                // Two on top, the rest below in threes (4 → 2+2, 5 → 2+3, 6 → 3+3).
                val layoutRows = if (shown.size == 5) listOf(shown.take(2), shown.drop(2)) else rows
                layoutRows.forEachIndexed { r, row ->
                    Row(
                        Modifier.fillMaxWidth().aspectRatio(
                            if (row.size ==
                                2
                            ) {
                                2f
                            } else {
                                3f
                            },
                        ),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        row.forEachIndexed { c, item ->
                            val last = r == layoutRows.lastIndex && c == row.lastIndex
                            Tile(
                                services,
                                item,
                                Modifier.weight(1f).fillMaxHeight(),
                                more = if (last &&
                                    more > 0
                                ) {
                                    more
                                } else {
                                    0
                                },
                                onOpen = onOpen,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Large thumbnails in a row, each in its own shape: one or two in view without scrolling. */
@Composable
private fun LargeStrip(services: AppServices, items: List<AttachmentState>, onOpen: (AttachmentState) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(0.dp)) {
        items(items, key = { it.meta.id }) { item ->
            Tile(
                services,
                item,
                Modifier.height(200.dp).width(200.dp * ratioOf(item).coerceIn(0.6f, 1.6f)).clip(RoundedCornerShape(12.dp)),
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun Tile(services: AppServices, item: AttachmentState, modifier: Modifier, more: Int = 0, onOpen: (AttachmentState) -> Unit) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable {
            onOpen(item)
        },
        contentAlignment = Alignment.Center,
    ) {
        BestPicture(services.media, item, Modifier.fillMaxSize())
        if (item.meta.kind == "video") PlayBadge()
        if (more > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Text("+$more", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }
        }
    }
}

/** A sound or a file: its kind, name and length on one row. */
@Composable
private fun FileRow(item: AttachmentState, onClick: () -> Unit) {
    val meta = item.meta
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (meta.kind == "voice" || meta.kind == "audio") Icons.Mic else Icons.Attach, null, Modifier.size(22.dp))
            Text(
                listOfNotNull(meta.name, meta.durationMs?.let(::durationLabel)).joinToString(" · ").ifEmpty { meta.kind },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun ratioOf(item: AttachmentState): Float {
    val w = item.meta.width ?: 0
    val h = item.meta.height ?: 0
    return if (w > 0 && h > 0) w.toFloat() / h else 4f / 3
}
