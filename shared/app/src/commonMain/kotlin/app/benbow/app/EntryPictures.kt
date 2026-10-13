package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.attachment_gone
import app.benbow.core.sync.AttachmentState
import app.benbow.core.text.Atom
import app.benbow.ui.editor.AtomViews
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource

/**
 * Pictures inside an entry's text: each atom finds its attachment by id and takes its own shape (within reason);
 * videos show their preview with a play button, sounds and files a row with their name. A tap opens the viewer.
 */
internal class EntryPictures(
    private val services: AppServices,
    private val attachments: List<AttachmentState>,
    private val onOpen: (attachmentId: String) -> Unit,
) : AtomViews() {
    private fun find(atom: Atom.Attachment) = attachments.firstOrNull { it.meta.id == atom.ref }

    override fun pictureHeight(atom: Atom.Attachment, width: Dp): Dp {
        val meta = find(atom)?.meta ?: return 48.dp
        if (meta.kind != "image" && meta.kind != "video") return 64.dp
        val w = meta.width ?: 0
        val h = meta.height ?: 0
        val shaped = if (w > 0 && h > 0) width * (h.toFloat() / w) else width * 0.66f
        return shaped.coerceIn(120.dp, 520.dp) + 8.dp // a little air above and below
    }

    @Composable
    override fun Picture(atom: Atom.Attachment) {
        val attachment = find(atom)
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(12.dp)
        Box(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
            if (attachment == null) {
                Text(
                    stringResource(Res.string.attachment_gone),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                return@Box
            }
            val meta = attachment.meta
            Box(
                Modifier.fillMaxSize().clip(shape).background(colors.surfaceContainerHigh).clickable { onOpen(meta.id) },
                contentAlignment = Alignment.Center,
            ) {
                if (meta.kind == "image" || meta.kind == "video") {
                    BestPicture(services.media, attachment, Modifier.fillMaxSize())
                    if (meta.kind == "video") PlayBadge()
                } else {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp),
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
        }
    }
}
