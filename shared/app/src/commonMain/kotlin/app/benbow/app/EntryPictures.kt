package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import app.benbow.app.resources.attachment_purged
import app.benbow.app.resources.drawing_title
import app.benbow.core.audio.TrackTags
import app.benbow.core.media.MediaLook
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
    private val onRename: ((attachmentId: String, names: SoundNames) -> Unit)? = null,
    private val entryId: String? = null,
    private val transcripts: Map<String, String> = emptyMap(),
    /** What each sound file says it is (ADR-035). */
    private val tracks: Map<String, TrackTags> = emptyMap(),
    /** How tall a transcript is at a width (the editor measures it with the row's text style). */
    private val textHeight: (String, Dp) -> Dp = { _, _ -> 0.dp },
    /** «Заменить текстом» (7.10e): the editor puts a voice note's transcript in its place. */
    private val onReplace: ((attachmentId: String, text: String) -> Unit)? = null,
    /** Attachments deleted for good (ADR-041): where one stood, the text says so. */
    private val purged: Set<String> = emptySet(),
    /** How each attachment shows (owner, 2026-10-30): its shape sets its height here. */
    private val looks: Map<String, MediaLook> = emptyMap(),
    /** The tallest a picture of its own shape stands: most of the window's height (a tall one beyond shows whole). */
    private val tallest: Dp = 520.dp,
    /** «Настроить» over a picture selected while writing: how it shows in its post («Вложение»). */
    private val onSettings: ((attachmentId: String) -> Unit)? = null,
) : AtomViews() {
    private fun find(atom: Atom.Attachment) = attachments.firstOrNull { it.meta.id == atom.ref }

    override fun selectable(atom: Atom.Attachment): Boolean =
        onSettings != null && find(atom)?.meta?.kind.let { it == "image" || it == "video" || it == "drawing" }

    override fun openPicture(atom: Atom.Attachment) = onOpen(atom.ref)

    override fun pictureSettings(atom: Atom.Attachment) {
        onSettings?.invoke(atom.ref)
    }

    override fun pictureHeight(atom: Atom.Attachment, width: Dp): Dp {
        val meta = find(atom)?.meta ?: return 48.dp
        if (find(atom)?.isSound == true) {
            val text = transcripts[meta.id] ?: return 72.dp
            return 72.dp + textHeight(text, width - TRANSCRIPT_START - TRANSCRIPT_END) + 10.dp
        }
        if (meta.kind != "image" && meta.kind != "video" && meta.kind != "drawing") return 64.dp
        val w = meta.width ?: 0
        val h = meta.height ?: 0
        // By its own shape (owner: the default), up to most of the window; filling or whole, a 4:3 frame as posts had.
        // Held by a scrapbook's frame, it lies inside room for the frame's turn and tape (a polaroid's border too).
        val look = looks[meta.id]
        val held = look?.frameKind?.first
        val room = if (held != null) HOLD_ROOM * 2 else 0.dp
        val polaroid = held == "polaroid"
        val inner = width - room - if (polaroid) 12.dp else 0.dp
        val tall = when (look?.fit ?: MediaLook.Fit.Natural) {
            MediaLook.Fit.Natural -> (if (w > 0 && h > 0) inner * (h.toFloat() / w) else inner * FRAME).coerceIn(MIN_HEIGHT, tallest)
            else -> (inner * FRAME).coerceIn(MIN_HEIGHT, FRAME_MAX)
        }
        return tall + room + (if (polaroid) 26.dp else 0.dp) + 8.dp // a little air above and below
    }

    /** Each picture's width to height in a row (ADR-061): its own, between 1:2 and 2:1; a file or a sound, square. */
    private fun ratios(atom: Atom.AttachmentRow): List<Float> = atom.refs.map { ref ->
        val meta = attachments.firstOrNull { it.meta.id == ref }?.meta
        val w = meta?.width ?: 0
        val h = meta?.height ?: 0
        if (w > 0 && h > 0 && meta?.kind != "voice" && meta?.kind != "audio") (w.toFloat() / h).coerceIn(ROW_NARROWEST, ROW_WIDEST) else 1f
    }

    // By size (owner): one height for the row, each picture as wide as its shape asks, the row as wide as the text.
    override fun rowHeight(atom: Atom.AttachmentRow, width: Dp): Dp {
        val ratios = ratios(atom)
        return ((width - ROW_GAP * (ratios.size - 1)) / ratios.sum()).coerceIn(MIN_ROW, tallest) + 8.dp
    }

    @Composable
    override fun AttachmentRow(atom: Atom.AttachmentRow, selected: String?, onItem: ((String) -> Unit)?) {
        val ratios = ratios(atom)
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(12.dp)
        Row(Modifier.fillMaxSize().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            atom.refs.forEachIndexed { i, ref ->
                val attachment = attachments.firstOrNull { it.meta.id == ref }
                Box(
                    Modifier.weight(ratios[i]).fillMaxHeight().clip(shape).background(colors.surfaceContainerHigh)
                        .then(if (ref == selected) Modifier.border(2.dp, colors.primary, shape) else Modifier)
                        .hand().clickable(interactionSource = null, indication = mediaIndication()) { onItem?.invoke(ref) ?: onOpen(ref) },
                    contentAlignment = Alignment.Center,
                ) {
                    val meta = attachment?.meta
                    when {
                        meta == null -> Text(
                            stringResource(if (ref in purged) Res.string.attachment_purged else Res.string.attachment_gone),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )

                        meta.kind == "image" || meta.kind == "video" || meta.kind == "drawing" -> BoxWithConstraints(
                            Modifier.fillMaxSize(),
                        ) {
                            FramedPicture(services.media, attachment, frame = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f)
                        }

                        else -> Column(
                            Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(if (meta.kind == "voice" || meta.kind == "audio") Icons.Mic else Icons.Attach, null, Modifier.size(22.dp))
                            Text(
                                meta.name ?: meta.kind,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun Picture(atom: Atom.Attachment) {
        val attachment = find(atom)
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(12.dp)
        Box(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
            if (attachment == null) {
                Text(
                    stringResource(if (atom.ref in purged) Res.string.attachment_purged else Res.string.attachment_gone),
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
                    onRename = onRename?.let { rename -> { names -> rename(meta.id, names) } },
                    folded = false,
                    onReplace = onReplace?.let { replace -> transcripts[meta.id]?.let { text -> { replace(meta.id, text) } } },
                    tags = tracks[meta.id],
                    queue = { queue(entryId, attachments, tracks) },
                )
                return@Box
            }
            val label = if (meta.kind == "drawing") stringResource(Res.string.drawing_title) else null
            val held = looks[meta.id]?.frameKind
            if (held != null && meta.kind != "voice" && meta.kind != "audio") {
                // In a scrapbook's frame (owner, 2026-10-30): a print, square-cornered, turned a little on the page.
                val seed = meta.id.hashCode() + held.second * SHUFFLE_STEP
                Box(Modifier.fillMaxSize().padding(HOLD_ROOM)) {
                    Box(
                        Modifier.fillMaxSize().scrapFrame(held.first, seed, colors.background)
                            .hand().clickable(interactionSource = null, indication = mediaIndication()) { onOpen(meta.id) },
                    ) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            FramedPicture(services.media, attachment, frame = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f)
                        }
                    }
                }
                return@Box
            }
            Box(
                Modifier.fillMaxSize().clip(
                    shape,
                ).background(colors.surfaceContainerHigh).hand().clickable(interactionSource = null, indication = mediaIndication()) {
                    onOpen(meta.id)
                }
                    .semantics { label?.let { contentDescription = it } },
                contentAlignment = Alignment.Center,
            ) {
                if (meta.kind == "image" || meta.kind == "video" || meta.kind == "drawing") {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        FramedPicture(services.media, attachment, frame = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f)
                    }
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

/** A 4:3 frame for a picture filling it or shown whole in it, at most as tall as posts' pictures were. */
private const val FRAME = 0.75f
private val FRAME_MAX = 520.dp
private val MIN_HEIGHT = 120.dp

/** A row's gap between its pictures, its lowest height, and the narrowest and widest shape a picture in it takes. */
private val ROW_GAP = 4.dp
private val MIN_ROW = 72.dp
private const val ROW_NARROWEST = 0.5f
private const val ROW_WIDEST = 2f

/** Room around a picture in a scrapbook's frame for its turn and its tape; a shuffle's step through the frame's seeds. */
private val HOLD_ROOM = 14.dp
private const val SHUFFLE_STEP = 7919
