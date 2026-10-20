package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.board_checked
import app.benbow.app.resources.board_files
import app.benbow.client.BoardNote
import app.benbow.client.CardLine
import app.benbow.client.Media
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource

/**
 * A note's card on the board (ADR-040), Keep-like: as wide as its column and as tall as its words, the note's color,
 * its picture on top at its own proportions (so the card has its height before the picture loads), a title only when
 * more follows it, its open boxes, how many are ticked, its tags. [words] lets the open note's sheet fade them alone.
 */
@Composable
internal fun BoardCard(note: BoardNote, media: Media?, chosen: Boolean, modifier: Modifier = Modifier, words: Modifier = Modifier) {
    val color = swatch(note.entry.background)
    val outline = when {
        chosen -> MaterialTheme.colorScheme.primary
        color == null -> MaterialTheme.colorScheme.outlineVariant
        else -> Color.Transparent
    }
    Column(
        modifier.fillMaxWidth().clip(CARD_SHAPE).background(color ?: MaterialTheme.colorScheme.surface)
            .border(if (chosen) 2.dp else 1.dp, outline, CARD_SHAPE),
    ) {
        Column(words.fillMaxWidth()) { CardWords(note, media) }
    }
}

@Composable
private fun CardWords(note: BoardNote, media: Media?) {
    val picture = note.picture
    if (picture != null && media != null) {
        val load by rememberMediaFile(media, picture.sha256)
        Box(
            Modifier.fillMaxWidth().aspectRatio((picture.ratio ?: 1f).coerceIn(0.5f, 2.4f))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            (load as? MediaLoad.Ready)?.let { AsyncImage(it.file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }
    val card = note.card
    val files = note.entry.mediaCount - if (picture != null) 1 else 0
    val tags = note.entry.tags
    if (card.title.isEmpty() && card.lines.isEmpty() && card.ticked == 0 && files <= 0 && tags.isEmpty()) return
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (card.title.isNotEmpty()) {
            Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (card.lines.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                card.lines.forEachIndexed { i, line -> CardLineRow(line, last = card.more && i == card.lines.lastIndex) }
            }
        }
        if (card.ticked > 0) {
            Text(
                stringResource(Res.string.board_checked, card.ticked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (files > 0) {
            Text(
                stringResource(Res.string.board_files, files),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tags.take(3).forEach { TagChip(it) }
            }
        }
    }
}

@Composable
private fun CardLineRow(line: CardLine, last: Boolean) {
    val text = if (last) line.text + "…" else line.text
    val style = if (line.heading) {
        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.bodyMedium
    }
    when {
        line.box -> Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.CheckBoxBlank, null, Modifier.padding(top = 1.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(text, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }

        line.bullet != null -> Row {
            Text(line.bullet!!, style = style, modifier = Modifier.width(20.dp))
            Text(text, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }

        // A blank line keeps its height: the gap the note has there.
        else -> Text(text.ifEmpty { " " }, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
