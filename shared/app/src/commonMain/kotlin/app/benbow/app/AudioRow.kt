package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.audio_pause
import app.benbow.app.resources.audio_play
import app.benbow.app.resources.media_audio
import app.benbow.app.resources.speech_transcribe
import app.benbow.app.resources.voice_note
import app.benbow.app.resources.voice_replace
import app.benbow.client.Media
import app.benbow.client.SpeechModel
import app.benbow.core.audio.TrackTags
import app.benbow.core.sync.AttachmentState
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource

/** The words a track falls back on: a voice note's title, and a sound with no name at all. */
@Immutable
internal class TrackNames(val voice: String, val sound: String) {
    /** What the player shows for a sound: the caption given by hand, else the file's tags, else its name (ADR-035). */
    fun track(entryId: String?, attachment: AttachmentState, tags: TrackTags?): Track {
        val meta = attachment.meta
        val voice = meta.kind == "voice"
        val name = meta.name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        return Track(
            id = meta.id,
            entryId = entryId,
            sha256 = meta.sha256,
            mime = meta.mime,
            title = attachment.caption ?: tags?.title ?: (if (voice) this.voice else name) ?: sound,
            artist = tags?.artist,
            album = tags?.album,
            cover = meta.previewSha256,
            durationMs = meta.durationMs,
            voice = voice,
            recordedAt = if (voice) meta.capturedAt else null,
        )
    }

    /** An entry's sounds as a queue, in the order given (its gallery's). */
    fun queue(entryId: String?, attachments: List<AttachmentState>, tracks: Map<String, TrackTags>): List<Track> =
        attachments.filter { it.isSound }.map { track(entryId, it, tracks[it.meta.id]) }
}

@Composable
internal fun rememberTrackNames(): TrackNames {
    val voice = stringResource(Res.string.voice_note)
    val sound = stringResource(Res.string.media_audio)
    return remember(voice, sound) { TrackNames(voice, sound) }
}

internal val AttachmentState.isSound: Boolean get() = meta.kind == "voice" || meta.kind == "audio"

/**
 * A voice note or a sound, the way VK showed them: the play button on the left (a track's cover with the button over
 * it), the title, and the artist and length; while it's the current track, a seek bar with the time. Used in the feed,
 * in posts, in the text and under comments. A tap hands the player [queue] (the sounds that follow this one).
 */
@Composable
internal fun AudioRow(
    services: AppServices,
    track: Track,
    attachment: AttachmentState,
    queue: () -> List<Track>,
    modifier: Modifier = Modifier,
    onRename: ((String?) -> Unit)? = null,
    transcript: String? = null,
    transcribing: Boolean = false,
    onTranscribe: (() -> Unit)? = null,
    /** In the text the whole transcript shows (the line is sized for it); elsewhere it folds to a few lines. */
    folded: Boolean = true,
    /** «Заменить текстом» (7.10e): in the editor, a voice note with its transcript can give way to the words. */
    onReplace: (() -> Unit)? = null,
    /** What a blank title goes back to: the name without the caption. */
    defaultTitle: String = track.title,
) {
    val player = services.player
    val state = player.state.collectAsState()
    // Only the current track's row follows the position; the others don't recompose as it plays.
    val mine by remember(track.id) { derivedStateOf { state.value?.takeIf { it.track.id == track.id } } }
    var renaming by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val playing = mine?.playing == true
                PlayButton(services, track, playing, loading = mine?.loading == true && !playing) { player.toggle(track, queue) }
                Column(Modifier.weight(1f)) {
                    if (renaming && onRename != null) {
                        TitleField(attachment.caption.orEmpty(), defaultTitle) {
                            renaming = false
                            onRename(it)
                        }
                    } else {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (onRename != null) Modifier.hand().clickable { renaming = true } else Modifier,
                        )
                    }
                    val now = mine
                    val total = now?.durationMs?.takeIf { it > 0 } ?: track.durationMs ?: 0
                    if (now != null && total > 0) {
                        SeekLine(now.positionMs, total, onSeek = player::seek)
                    } else {
                        Text(
                            listOfNotNull(track.artist, track.durationMs?.let(::durationLabel)).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when {
                    transcribing -> CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp)

                    transcript == null && onTranscribe != null -> IconButton(onTranscribe, modifier = Modifier.hand()) {
                        Icon(Icons.Transcribe, stringResource(Res.string.speech_transcribe), tint = colors.onSurfaceVariant)
                    }

                    transcript != null && onReplace != null -> TextButton(onReplace, modifier = Modifier.hand()) {
                        Text(stringResource(Res.string.voice_replace))
                    }
                }
            }
            if (transcript != null) {
                var open by remember { mutableStateOf(false) }
                Text(
                    transcript,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (!folded || open) Int.MAX_VALUE else FOLDED_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().then(if (folded) Modifier.hand().clickable { open = !open } else Modifier)
                        .padding(start = TRANSCRIPT_START, end = TRANSCRIPT_END, bottom = 10.dp),
                )
            }
        }
    }
}

/** A voice note's round button, or a track's cover with the button over it. */
@Composable
private fun PlayButton(services: AppServices, track: Track, playing: Boolean, loading: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(if (playing) Res.string.audio_pause else Res.string.audio_play)
    val cover = track.cover?.takeUnless { track.voice }
    if (cover != null) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHighest).hand()
                .clickable(onClick = onClick).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            CoverImage(services.media, cover, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            PlayGlyph(playing, loading, Color.White)
        }
    } else {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = colors.primary,
            contentColor = colors.onPrimary,
            modifier = Modifier.size(42.dp).hand().semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) { PlayGlyph(playing, loading, colors.onPrimary) }
        }
    }
}

/** Play or pause, or a spinner while the file comes. */
@Composable
internal fun PlayGlyph(playing: Boolean, loading: Boolean, tint: Color, size: Dp = 24.dp) {
    if (loading) {
        CircularProgressIndicator(Modifier.size(size * 0.8f), color = tint, strokeWidth = 2.dp)
    } else {
        Icon(if (playing) Icons.Pause else Icons.Play, null, Modifier.size(size), tint = tint)
    }
}

/** A track's cover from the media store (fetched if it isn't here yet: it's small). */
@Composable
internal fun CoverImage(media: Media, sha256: String, modifier: Modifier = Modifier) {
    val load by rememberMediaFile(media, sha256)
    (load as? MediaLoad.Ready)?.let { AsyncImage(it.file, null, modifier, contentScale = ContentScale.Crop) }
}

/** The seek bar with the time where it points (where a finger drags it, while it does). */
@Composable
internal fun SeekLine(positionMs: Long, totalMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: (positionMs.toFloat() / totalMs).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * totalMs).toLong()) }
                dragging = null
            },
            modifier = Modifier.weight(1f).height(28.dp).hand(),
        )
        Text(
            durationLabel((fraction * totalMs).toLong()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Where a transcript starts and ends in the row: under the title, past the play button. */
internal val TRANSCRIPT_START = 64.dp
internal val TRANSCRIPT_END = 12.dp
private const val FOLDED_LINES = 3

/**
 * A sound with what was said in it, wired to the player and to this device's transcriber (7.6): the button shows once
 * the model is here, and never for music (a file that names its artist, ADR-035). [queue] lists the sounds a tap plays from; by
 * default just this one.
 */
@Composable
internal fun VoiceRow(
    services: AppServices,
    entryId: String?,
    attachment: AttachmentState,
    transcript: String?,
    modifier: Modifier = Modifier,
    onRename: ((String?) -> Unit)? = null,
    folded: Boolean = true,
    onReplace: (() -> Unit)? = null,
    tags: TrackTags? = null,
    queue: (TrackNames.() -> List<Track>)? = null,
) {
    val transcriptions = services.transcriptions
    val model = transcriptions?.model?.state?.collectAsState()?.value
    val running = transcriptions?.running?.collectAsState()?.value.orEmpty()
    val names = rememberTrackNames()
    val track = remember(names, entryId, attachment, tags) { names.track(entryId, attachment, tags) }
    AudioRow(
        services,
        track,
        attachment,
        queue = { queue?.invoke(names) ?: listOf(track) },
        modifier = modifier,
        onRename = onRename,
        transcript = transcript,
        transcribing = attachment.meta.id in running,
        onTranscribe = if (transcriptions != null && entryId != null && model == SpeechModel.State.Ready && tags?.isMusic != true) {
            { transcriptions.transcribe(entryId, attachment) }
        } else {
            null
        },
        folded = folded,
        onReplace = onReplace,
        defaultTitle = remember(names, attachment, tags) { names.track(entryId, attachment.copy(caption = null), tags).title },
    )
}

/** The title in place: Done (or leaving the field) keeps it; blank goes back to [hint], the default name. */
@Composable
private fun TitleField(initial: String, hint: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val finish = {
        if (!done) {
            done = true
            onDone(text.text)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finish() }),
        decorationBox = { inner ->
            // A hairline under the title says it's being edited.
            Box(
                Modifier.drawBehind {
                    val y = size.height + 1.dp.toPx()
                    drawLine(colors.primary, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                },
            ) {
                if (text.text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                inner()
            }
        },
        modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
            if (focused && !it.isFocused) finish()
            focused = it.isFocused
        },
    )
}
