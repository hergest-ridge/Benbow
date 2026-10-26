package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.block_notes
import app.benbow.client.BlockSize
import app.benbow.client.BoardNote
import app.benbow.client.Media
import app.benbow.ui.mouseSwipe
import org.jetbrains.compose.resources.stringResource

/**
 * The board's pinned notes on Home (ADR-040, K7): a strip of their cards (M), or the first six in two columns (L).
 * The title opens the board, a card its note.
 */
@Composable
internal fun NotesBlock(
    notes: List<BoardNote>,
    size: BlockSize,
    media: Media,
    onOpen: (BoardNote) -> Unit,
    onBoard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).hand().clickable(onClick = onBoard)
                .padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // As Home's other sections are titled, with a way on to the board.
            Text(
                stringResource(Res.string.block_notes),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(Icons.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
        val open = { note: BoardNote -> Modifier.clip(CARD_SHAPE).hand().clickable { onOpen(note) } }
        if (size == BlockSize.L) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shown = notes.take(6)
                repeat(2) { lane ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.filterIndexed { i, _ -> i % 2 == lane }.forEach { BoardCard(it, media, false, open(it)) }
                    }
                }
            }
        } else {
            val swipeRow = rememberLazyListState()
            LazyRow(
                state = swipeRow,
                modifier = Modifier.mouseSwipe(swipeRow),
                contentPadding = PaddingValues(end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(notes, key = { it.id }) { BoardCard(it, media, false, Modifier.width(168.dp).then(open(it))) }
            }
        }
    }
}
