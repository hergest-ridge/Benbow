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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.attachment_gone
import app.benbow.app.resources.drawing_title
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
    private val onRename: ((attachmentId: String, caption: String?) -> Unit)? = null,
    private val entryId: String? = null,
    private val transcripts: Map<String, String> = emptyMap(),
    /** How tall a transcript is at a width (the editor measures it with the row's text style). */
    private val textHeight: (String, Dp) -> Dp = { _, _ -> 0.dp },
    /** «Заменить текстом» (7.10e): the editor puts a voice note's transcript in its place. */
    private val onReplace: ((attachmentId: String, text: String) -> Unit)? = null,
) : AtomViews() {
    private fun find(atom: Atom.Attachment) = attachments.firstOrNull { it.meta.id == atom.ref }

    override fun pictureHeight(atom: Atom.Attachment, width: Dp): Dp {
        val meta = find(atom)?.meta ?: return 48.dp
        if (meta.kind == "voice" || meta.kind == "audio") {
            val text = transcripts[meta.id] ?: return 72.dp
            return 72.dp + textHeight(text, width - TRANSCRIPT_START - TRANSCRIPT_END) + 10.dp
        }
        if (meta.kind != "image" && meta.kind != "video" && meta.kind != "drawing") return 64.dp
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
            if (meta.kind == "voice" || meta.kind == "audio") {
                VoiceRow(
                    services,
                    entryId,
                    attachment,
                    transcripts[meta.id],
                    Modifier.align(Alignment.Center),
                    onRename = onRename?.let { rename -> { caption -> rename(meta.id, caption) } },
                    folded = false,
                    onReplace = onReplace?.let { replace -> transcripts[meta.id]?.let { text -> { replace(meta.id, text) } } },
                )
                return@Box
            }
            val label = if (meta.kind == "drawing") stringResource(Res.string.drawing_title) else null
            Box(
                Modifier.fillMaxSize().clip(shape).background(colors.surfaceContainerHigh).hand().clickable { onOpen(meta.id) }
                    .semantics { label?.let { contentDescription = it } },
                contentAlignment = Alignment.Center,
            ) {
                if (meta.kind == "image" || meta.kind == "video" || meta.kind == "drawing") {
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
