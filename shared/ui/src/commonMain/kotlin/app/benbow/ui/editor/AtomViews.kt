package app.benbow.ui.editor

import androidx.compose.foundation.layout.Box
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

    @Composable
    open fun Picture(atom: Atom.Attachment) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
        }
    }

    internal fun height(atom: Atom, width: Dp): Dp = when (atom) {
        is Atom.Attachment -> pictureHeight(atom, width)
        is Atom.LinkCard -> linkCardHeight(atom, width)
        is Atom.Divider -> 28.dp
        is Atom.Unknown -> 44.dp
    }

    @Composable
    internal fun Content(atom: Atom, key: String) {
        when (atom) {
            is Atom.Attachment -> Picture(atom)

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
