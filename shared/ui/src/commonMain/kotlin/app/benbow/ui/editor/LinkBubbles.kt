package app.benbow.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.benbow.core.text.Atom
import app.benbow.core.text.Links
import app.benbow.core.text.Mark
import app.benbow.ui.resources.Res
import app.benbow.ui.resources.card_large
import app.benbow.ui.resources.card_refresh
import app.benbow.ui.resources.card_remove
import app.benbow.ui.resources.card_row
import app.benbow.ui.resources.card_to_text
import app.benbow.ui.resources.link_copy
import app.benbow.ui.resources.link_edit
import app.benbow.ui.resources.link_entry
import app.benbow.ui.resources.link_open
import app.benbow.ui.resources.link_unlink
import app.benbow.ui.resources.picture_open
import app.benbow.ui.resources.picture_out_of_text
import app.benbow.ui.resources.picture_row
import app.benbow.ui.resources.picture_settings
import app.benbow.ui.resources.row_out_of_text
import app.benbow.ui.resources.row_split
import app.benbow.ui.resources.row_take_out
import app.benbow.ui.sideScroll
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The editor an atom is drawn in (null: the feed and other places that only read). */
internal class AtomHost(val state: EditorState, val readOnly: Boolean)

internal val LocalAtomHost = staticCompositionLocalOf<AtomHost?> { null }

/** A card inside the editor: while writing a tap selects it (its bubble offers the rest); reading, a tap opens it. */
@Composable
internal fun EditorLinkCard(card: Atom.LinkCard, key: String) {
    val host = LocalAtomHost.current
    val modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)
    if (host == null || host.readOnly) {
        ReadingLinkCard(card, modifier)
        return
    }
    val selected = host.state.selectedAtom == key
    LinkCardView(card, modifier.focusProperties { canFocus = false }, selected = selected, onClick = {
        host.state.selectedAtom = if (selected) null else key
    })
}

/**
 * Places [content] just above [anchor] (in the editor's coordinates), or below it when there is no room above, kept
 * inside the editor's width. The layer itself takes no touches; only the bubble does.
 */
@Composable
internal fun BubbleAt(anchor: Rect, modifier: Modifier, content: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val bubble = measurables.first().measure(Constraints(maxWidth = width))
        layout(width, constraints.minHeight) {
            val x = anchor.left.roundToInt().coerceIn(0, (width - bubble.width).coerceAtLeast(0))
            val above = anchor.top.roundToInt() - bubble.height - gap
            bubble.place(x, if (above >= 0) above else anchor.bottom.roundToInt() + gap, zIndex = 1f)
        }
    }
}

/** Over a link the cursor was put in: where it goes (a tap opens it), then edit, copy and unlink. */
@Composable
internal fun LinkBubble(mark: Mark, onOpen: () -> Unit, onEdit: () -> Unit, onCopy: (() -> Unit)?, onUnlink: () -> Unit) {
    BubbleSurface {
        val where = when (mark) {
            is Mark.Link -> Links.host(mark.href).ifEmpty { Links.display(mark.href) }
            else -> stringResource(Res.string.link_entry)
        }
        Row(
            Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).bubbleClick(onOpen).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                where,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Icon(
                EditorIcons.OpenInNew,
                stringResource(Res.string.link_open),
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        BubbleIcon(EditorIcons.Edit, stringResource(Res.string.link_edit), onEdit)
        if (onCopy != null) BubbleIcon(EditorIcons.Copy, stringResource(Res.string.link_copy), onCopy)
        BubbleIcon(EditorIcons.LinkOff, stringResource(Res.string.link_unlink), onUnlink)
    }
}

/** Over a selected card: open it, its look, down into the text, read its page anew, edit its address and title, remove it. */
@Composable
internal fun CardBubble(
    card: Atom.LinkCard,
    onOpen: () -> Unit,
    onLarge: (Boolean) -> Unit,
    onToText: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    BubbleSurface(scroll = true) {
        BubbleLabel(EditorIcons.OpenInNew, stringResource(Res.string.link_open), onOpen)
        if (card.image != null) {
            if (card.large) {
                BubbleLabel(EditorIcons.Row, stringResource(Res.string.card_row)) { onLarge(false) }
            } else {
                BubbleLabel(EditorIcons.Large, stringResource(Res.string.card_large)) { onLarge(true) }
            }
        }
        BubbleLabel(EditorIcons.ToText, stringResource(Res.string.card_to_text), onToText)
        BubbleIcon(EditorIcons.Refresh, stringResource(Res.string.card_refresh), onRefresh)
        BubbleIcon(EditorIcons.Edit, stringResource(Res.string.link_edit), onEdit)
        BubbleIcon(EditorIcons.Delete, stringResource(Res.string.card_remove), onRemove)
    }
}

/** Over a selected picture (owner, 2026-10-30): open it, how it shows in its post, out of the text (it stays an attachment). */
@Composable
internal fun PictureBubble(onOpen: () -> Unit, onSettings: () -> Unit, onRemove: () -> Unit, onRow: (() -> Unit)? = null) {
    BubbleSurface(scroll = true) {
        BubbleLabel(EditorIcons.OpenInNew, stringResource(Res.string.picture_open), onOpen)
        BubbleLabel(EditorIcons.Tune, stringResource(Res.string.picture_settings), onSettings)
        // Beside the picture above it (ADR-061).
        onRow?.let { BubbleLabel(EditorIcons.Columns, stringResource(Res.string.picture_row), it) }
        BubbleIcon(EditorIcons.Delete, stringResource(Res.string.picture_out_of_text), onRemove)
    }
}

/** A picture chosen in a row (ADR-061): it opens, how it shows, out of the row; the row apart; the row out of the text. */
@Composable
internal fun RowBubble(onOpen: () -> Unit, onSettings: () -> Unit, onOut: () -> Unit, onSplit: () -> Unit, onRemove: () -> Unit) {
    BubbleSurface(scroll = true) {
        BubbleLabel(EditorIcons.OpenInNew, stringResource(Res.string.picture_open), onOpen)
        BubbleLabel(EditorIcons.Tune, stringResource(Res.string.picture_settings), onSettings)
        BubbleLabel(EditorIcons.Below, stringResource(Res.string.row_take_out), onOut)
        BubbleLabel(EditorIcons.Split, stringResource(Res.string.row_split), onSplit)
        BubbleIcon(EditorIcons.Delete, stringResource(Res.string.row_out_of_text), onRemove)
    }
}

@Composable
private fun BubbleSurface(scroll: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            (if (scroll) Modifier.sideScroll(rememberScrollState()) else Modifier).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun BubbleIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).bubbleClick(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun BubbleLabel(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).bubbleClick(onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Bubble buttons never take the focus, so the keyboard and the cursor stay where they are. */
private fun Modifier.bubbleClick(onClick: () -> Unit): Modifier =
    focusProperties { canFocus = false }.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)
