package app.benbow.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.benbow.core.text.Atom
import app.benbow.ui.resources.Res
import app.benbow.ui.resources.unknown_block
import org.jetbrains.compose.resources.stringResource

/**
 * How [BenbowEditor] draws atom paragraphs, each over its own line: the app supplies pictures (it knows the files);
 * link cards, dividers and blocks from a newer version have a built-in look. Atoms are never edited in place, only removed
 * whole.
 */
open class AtomViews {
    /** Height of a picture laid out across [width]. */
    open fun pictureHeight(atom: Atom.Attachment, width: Dp): Dp = 56.dp

    /**
     * Whether a tap on [atom] while writing selects it, its bubble offering [openPicture] and [pictureSettings] (owner,
     * 2026-10-30): a picture, a video, a drawing. Sounds keep their own buttons.
     */
    open fun selectable(atom: Atom.Attachment): Boolean = false

    /** Opens [atom] (the viewer, a drawing's canvas). */
    open fun openPicture(atom: Atom.Attachment) {}

    /** Opens how [atom] shows in its post («Вложение»). */
    open fun pictureSettings(atom: Atom.Attachment) {}

    /** Height of a row of pictures laid out across [width] (ADR-061): one height for all of them. */
    open fun rowHeight(atom: Atom.AttachmentRow, width: Dp): Dp = 120.dp

    /**
     * A row's pictures side by side. While writing a tap on one does [onItem] (it is chosen: [selected]); reading,
     * [onItem] is null and a tap opens it, as a picture's does.
     */
    @Composable
    open fun AttachmentRow(atom: Atom.AttachmentRow, selected: String?, onItem: ((String) -> Unit)?) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            atom.refs.forEach { ref ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight().then(
                        if (onItem != null) Modifier.clickable(interactionSource = null, indication = null) { onItem(ref) } else Modifier,
                    ),
                ) {}
            }
        }
    }

    @Composable
    open fun Picture(atom: Atom.Attachment) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
        }
    }

    internal fun height(atom: Atom, width: Dp, coming: Map<String, PictureLoading> = emptyMap()): Dp = when (atom) {
        is Atom.Attachment -> pictureHeight(atom, width)
        is Atom.AttachmentRow -> rowHeight(atom, width)
        is Atom.LinkCard -> linkCardHeight(atom, width, coming[atom.href])
        is Atom.Divider -> 28.dp
        is Atom.Unknown -> 44.dp
    }

    @Composable
    internal fun Content(atom: Atom, key: String) {
        when (atom) {
            is Atom.Attachment -> {
                val host = LocalAtomHost.current
                if (host == null || host.readOnly || !selectable(atom)) {
                    Picture(atom)
                } else {
                    // While writing a tap selects it, as a card (its bubble offers the rest); the picture's own tap is under.
                    Box(Modifier.fillMaxSize()) {
                        Picture(atom)
                        val selected = host.state.selectedAtom == key
                        Box(
                            Modifier.matchParentSize().pointerHoverIcon(
                                PointerIcon.Hand,
                            ).clickable(interactionSource = null, indication = null) {
                                host.state.selectedAtom = if (selected) null else key
                            },
                        )
                    }
                }
            }

            is Atom.AttachmentRow -> {
                // While writing a tap chooses one of its pictures, the row's bubble then acting on it (ADR-061).
                val state = LocalAtomHost.current?.takeIf { !it.readOnly }?.state
                val chosen = state?.selectedPart?.takeIf { state.selectedAtom == key }
                AttachmentRow(
                    atom,
                    chosen,
                    state?.let {
                        { ref ->
                            if (chosen == ref) {
                                it.selectedAtom = null
                            } else {
                                it.selectedAtom = key
                                it.selectedPart = ref
                            }
                        }
                    },
                )
            }

            is Atom.LinkCard -> EditorLinkCard(atom, key)

            is Atom.Divider -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant)
            }

            is Atom.Unknown -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        stringResource(Res.string.unknown_block),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    companion object {
        val Default = AtomViews()
    }
}
