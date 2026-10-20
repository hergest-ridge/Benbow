package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.board_checked
import app.benbow.app.resources.board_files
import app.benbow.app.resources.board_from_journal
import app.benbow.app.resources.board_tick
import app.benbow.client.BoardNote
import app.benbow.client.CardLine
import app.benbow.client.Media
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A note's card on the board (ADR-040), Keep-like: as wide as its column and as tall as its words, the note's color,
 * its picture on top at its own proportions (so the card has its height before the picture loads), a title only when
 * more follows it, its open boxes, how many are ticked, its tags. [words] lets the open note's sheet fade them alone.
 */
@Composable
internal fun BoardCard(
    note: BoardNote,
    media: Media?,
    chosen: Boolean,
    modifier: Modifier = Modifier,
    words: Modifier = Modifier,
    /** Ticks an open box right on the card (its block's key); null: the boxes only show. */
    onTick: ((String) -> Unit)? = null,
    /** The card's thin line; none while its sheet moves (owner: lines moving with it look wrong). */
    lined: Boolean = true,
) {
    val color = swatch(note.entry.background)
    val outline = when {
        chosen -> MaterialTheme.colorScheme.primary
        color == null && lined -> MaterialTheme.colorScheme.outlineVariant
        else -> Color.Transparent
    }
    Column(
        modifier.fillMaxWidth().clip(CARD_SHAPE).background(color ?: boardCardColor())
            .border(if (chosen) 2.dp else 1.dp, outline, CARD_SHAPE),
    ) {
        Column(words.fillMaxWidth()) { CardWords(note, media, onTick) }
    }
}

/**
 * A card without a color of its own, apart from the page (owner): a little darker on a dark page (not black: owner,
 * Keep's cards stand off their page only a little), white on a light one. Its open note's page and the sheet between
 * them take it too.
 */
@Composable
internal fun boardCardColor(): Color {
    val colors = MaterialTheme.colorScheme
    return if (colors.background.luminance() < 0.5f) lerp(colors.background, Color.Black, 0.2f) else colors.surfaceContainerLowest
}

/** Rows of text a card shows at most (Keep's card: some 26, its title's included). */
private const val CARD_ROWS = 26

@Composable
private fun CardWords(note: BoardNote, media: Media?, onTick: ((String) -> Unit)?) {
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
    if (card.title.isEmpty() && card.lines.isEmpty() && card.ticked == 0 && files <= 0 && tags.isEmpty() && !note.fromJournal) return
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (card.title.isNotEmpty()) {
            Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (card.lines.isNotEmpty()) CardLines(card.lines, card.more, rows = if (card.title.isEmpty()) CARD_ROWS else CARD_ROWS - 2, onTick)
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
        if (note.fromJournal) {
            Text(
                stringResource(Res.string.board_from_journal, dayLabel(note.entry.day)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A card's lines, [rows] rows of text at most in all (owner: Keep's card ends after some 26), the last one shown ending
 * in "…" when there is more. Each line is measured in turn with the rows still left to it.
 */
@Composable
private fun CardLines(lines: List<CardLine>, more: Boolean, rows: Int, onTick: ((String) -> Unit)?) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium
    val row = with(density) { (style.lineHeight.takeIf { it.isSp } ?: (style.fontSize * 1.4f)).toPx() }
    val gap = with(density) { 2.dp.roundToPx() }
    SubcomposeLayout { constraints ->
        val loose = constraints.copy(minHeight = 0)
        val shown = ArrayList<Placeable>()
        var left = rows
        for ((i, line) in lines.withIndex()) {
            if (left <= 0) break
            val most = left
            val end = more && i == lines.lastIndex
            var placeable = subcompose(i) { CardLineRow(line, end, most, onTick) }.single().measure(loose)
            left -= max(1, (placeable.height / row).roundToInt())
            // The rows ran out here with more to come: this line ends the card.
            if (left <= 0 && !end && (i < lines.lastIndex || more)) {
                placeable = subcompose("$i…") { CardLineRow(line, true, most, onTick) }.single().measure(loose)
            }
            shown += placeable
        }
        val height = shown.sumOf { it.height } + gap * (shown.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            var y = 0
            shown.forEach {
                it.place(0, y)
                y += it.height + gap
            }
        }
    }
}

@Composable
private fun CardLineRow(line: CardLine, last: Boolean, rows: Int, onTick: ((String) -> Unit)?) {
    val text = if (last) line.text + "…" else line.text
    val style = if (line.heading) {
        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.bodyMedium
    }
    when {
        line.box -> Row(verticalAlignment = Alignment.Top) {
            val key = line.key
            val tick = if (onTick != null && key != null) {
                Modifier.clip(CircleShape).hand().clickable { onTick(key) }
            } else {
                Modifier
            }
            Box(Modifier.offset((-4).dp, (-3).dp).size(28.dp).then(tick), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.CheckBoxBlank,
                    stringResource(Res.string.board_tick).takeIf { onTick != null },
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text, style = style, maxLines = rows, overflow = TextOverflow.Ellipsis)
        }

        line.bullet != null -> Row {
            Text(line.bullet!!, style = style, modifier = Modifier.width(20.dp))
            Text(text, style = style, maxLines = rows, overflow = TextOverflow.Ellipsis)
        }

        // A blank line keeps its height: the gap the note has there.
        else -> Text(text.ifEmpty { " " }, style = style, maxLines = rows, overflow = TextOverflow.Ellipsis)
    }
}
