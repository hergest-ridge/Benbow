package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.look_close
import app.benbow.app.resources.look_shuffle
import app.benbow.app.resources.media_fit
import app.benbow.app.resources.media_fit_fill
import app.benbow.app.resources.media_fit_fill_hint
import app.benbow.app.resources.media_fit_natural
import app.benbow.app.resources.media_fit_natural_hint
import app.benbow.app.resources.media_fit_whole
import app.benbow.app.resources.media_fit_whole_hint
import app.benbow.app.resources.media_frame
import app.benbow.app.resources.media_frame_corners
import app.benbow.app.resources.media_frame_in_row
import app.benbow.app.resources.media_frame_in_text
import app.benbow.app.resources.media_frame_none
import app.benbow.app.resources.media_frame_polaroid
import app.benbow.app.resources.media_frame_strip
import app.benbow.app.resources.media_frame_tape
import app.benbow.app.resources.media_look_open
import app.benbow.app.resources.media_look_title
import app.benbow.app.resources.media_play
import app.benbow.app.resources.media_play_hint
import app.benbow.core.media.MediaLook
import app.benbow.core.sync.AttachmentState
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * «Вложение» (owner, 2026-10-30): how one attachment shows in its post, wherever it stands. Its frame (its own shape,
 * filling a frame cropped around what stays in sight, or whole in it), and for a GIF or a video whether it plays by
 * itself. As «Оформление записки»: a pane beside the note in a wide window, a sheet on a phone; every choice is saved
 * at once, so the post behind changes with it.
 */

/** What «Вложение» shows and changes: one attachment of a note, its look, and how each part of it is set. */
internal class MediaTarget(
    val attachment: AttachmentState,
    val look: MediaLook,
    val onFit: (MediaLook.Fit) -> Unit,
    val onFocus: (MediaLook.Focus?) -> Unit,
    val onPlay: (Boolean?) -> Unit,
    val onOpen: () -> Unit,
    /** Whether it stands in the text, where a scrapbook's frame holds it ([onFrame]). */
    val inText: Boolean = false,
    val onFrame: (String?) -> Unit = {},
    /** In a row of pictures (ADR-061), which shows them without frames. */
    val inRow: Boolean = false,
)

/** On a phone: a sheet opened whole. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaLookSheet(services: AppServices, target: MediaTarget, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(Res.string.media_look_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            MediaLookParts(services, target)
        }
    }
}

/** In a wide window: a pane beside the open note, only ✕ to close it. */
@Composable
internal fun MediaLookPanel(services: AppServices, target: MediaTarget, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.media_look_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClose, Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.look_close)) }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                MediaLookParts(services, target)
            }
        }
    }
}

@Composable
private fun MediaLookParts(services: AppServices, target: MediaTarget) {
    val meta = target.attachment.meta
    val look = target.look
    val shape = RoundedCornerShape(12.dp)
    // The picture as its post will show it, in a frame of the shape it takes there.
    val w = meta.width ?: 0
    val h = meta.height ?: 0
    val own = if (w > 0 && h > 0) w.toFloat() / h else 4f / 3
    val frame = if (look.fit == MediaLook.Fit.Natural) own.coerceAtLeast(MIN_FRAME) else 4f / 3
    CompositionLocalProvider(LocalMediaLooks provides mapOf(meta.id to look)) {
        val held = look.frameKind?.takeIf { target.inText }
        val size = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().heightIn(max = 360.dp)
            .aspectRatio(frame, matchHeightConstraintsFirst = true)
        if (held != null) {
            // In its scrapbook's frame, as the text shows it.
            val page = MaterialTheme.colorScheme.background
            Box(size.padding(14.dp)) {
                Box(Modifier.fillMaxSize().scrapFrame(held.first, meta.id.hashCode() + held.second * 7919, page)) {
                    FramedPicture(services.media, target.attachment, frame)
                }
            }
        } else {
            Box(
                size.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            ) { FramedPicture(services.media, target.attachment, frame) }
        }
    }
    PartTitle(Res.string.media_fit)
    val fits = listOf(
        MediaLook.Fit.Natural to (Res.string.media_fit_natural to Res.string.media_fit_natural_hint),
        MediaLook.Fit.Fill to (Res.string.media_fit_fill to Res.string.media_fit_fill_hint),
        MediaLook.Fit.Whole to (Res.string.media_fit_whole to Res.string.media_fit_whole_hint),
    )
    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        fits.forEach { (fit, names) ->
            FilterChip(look.fit == fit, { target.onFit(fit) }, { Text(stringResource(names.first)) }, Modifier.hand())
        }
    }
    Text(
        stringResource(fits.first { it.first == look.fit }.second.second),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
    )
    // Filling its frame: a tap on the whole picture says what stays in sight.
    if (look.fit == MediaLook.Fit.Fill && meta.kind != "drawing") FocusPicker(services, target, own)
    val gif = meta.kind == "image" && meta.mime == "image/gif"
    if (gif || meta.kind == "video") {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(Res.string.media_play), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(Res.string.media_play_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // As by default again ([MediaLook.play] null) once it matches the default: a GIF plays, a video doesn't.
            Switch(look.plays(gif), { on -> target.onPlay(on.takeIf { it != gif }) }, modifier = Modifier.hand())
        }
    }
    if (meta.kind == "image" || meta.kind == "video" || meta.kind == "drawing") FrameChoices(services, target)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(target.onOpen, modifier = Modifier.hand()) { Text(stringResource(Res.string.media_look_open)) }
    }
}

/** «Рамка» (owner, 2026-10-30): the picture itself in each of a scrapbook's frames; «Перемешать» lays the chosen one anew. */
@Composable
private fun FrameChoices(services: AppServices, target: MediaTarget) {
    PartTitle(Res.string.media_frame)
    if (!target.inText || target.inRow) {
        Text(
            stringResource(if (target.inRow) Res.string.media_frame_in_row else Res.string.media_frame_in_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        return
    }
    val chosen = target.look.frameKind
    val names = listOf(
        Res.string.media_frame_tape,
        Res.string.media_frame_strip,
        Res.string.media_frame_polaroid,
        Res.string.media_frame_corners,
    )
    val page = MaterialTheme.colorScheme.background
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FrameTile(stringResource(Res.string.media_frame_none), chosen == null, { target.onFrame(null) }) {
            Box(Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(6.dp))) {
                BestPicture(services.media, target.attachment, Modifier.fillMaxSize(), moving = false)
            }
        }
        MediaLook.FRAMES.forEachIndexed { i, kind ->
            val seed = target.attachment.meta.id.hashCode() + (if (chosen?.first == kind) chosen.second else 0) * 7919
            FrameTile(stringResource(names[i]), chosen?.first == kind, { target.onFrame(kind) }) {
                Box(Modifier.fillMaxSize().padding(10.dp)) {
                    Box(Modifier.fillMaxSize().scrapFrame(kind, seed, page)) {
                        BestPicture(services.media, target.attachment, Modifier.fillMaxSize(), moving = false)
                    }
                }
            }
        }
    }
    if (chosen != null) {
        TextButton(
            { target.onFrame("${chosen.first}.${Random.nextInt(1, 1_000)}") },
            modifier = Modifier.padding(horizontal = 12.dp).hand(),
        ) { Text(stringResource(Res.string.look_shuffle)) }
    }
}

@Composable
private fun FrameTile(name: String, on: Boolean, onPick: () -> Unit, picture: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(88.dp, 72.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
                .border(
                    if (on) 3.dp else 1.dp,
                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                )
                .hand().clickable(onClick = onPick)
                .semantics {
                    contentDescription = name
                    selected = on
                },
        ) { picture() }
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The whole picture, a ring where its focus is: a tap there moves it (the middle by default). */
@Composable
private fun FocusPicker(services: AppServices, target: MediaTarget, own: Float) {
    val focus = target.look.focus ?: MediaLook.Focus(0.5f, 0.5f)
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(
        Modifier.padding(
            horizontal = 20.dp,
            vertical = 10.dp,
        ).fillMaxWidth().heightIn(max = 220.dp).aspectRatio(own, matchHeightConstraintsFirst = true)
            .clip(shape).background(Color.Black).testTag("media-focus")
            .pointerInput(target.attachment.meta.id) {
                detectTapGestures { at ->
                    target.onFocus(MediaLook.Focus((at.x / size.width).coerceIn(0f, 1f), (at.y / size.height).coerceIn(0f, 1f)))
                }
            }.hand(),
    ) {
        BestPicture(services.media, target.attachment, Modifier.fillMaxSize(), moving = false)
        val ring = 28.dp
        Box(
            Modifier.offset {
                IntOffset(
                    (focus.x * constraints.maxWidth - ring.toPx() / 2).roundToInt(),
                    (focus.y * constraints.maxHeight - ring.toPx() / 2).roundToInt(),
                )
            }.size(ring).border(3.dp, Color.White, CircleShape).padding(3.dp).border(1.dp, Color.Black.copy(alpha = 0.4f), CircleShape),
        )
    }
}

@Composable
private fun PartTitle(text: org.jetbrains.compose.resources.StringResource) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
    )
}

/** The tallest a picture's own frame gets in this preview (a taller one shows whole within it). */
private const val MIN_FRAME = 0.6f
