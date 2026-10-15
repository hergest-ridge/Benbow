package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.audio_pause
import app.benbow.app.resources.audio_play
import app.benbow.app.resources.speech_transcribe
import app.benbow.app.resources.voice_note
import app.benbow.client.LocalFile
import app.benbow.client.Media
import app.benbow.client.SpeechModel
import app.benbow.core.sync.AttachmentState
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/** What the app is playing: one sound at a time. */
data class AudioNow(val id: String, val playing: Boolean, val positionMs: Long, val durationMs: Long)

/** The app's one audio player: starting a sound stops the last; [now] feeds the play buttons and seek bars. */
interface AudioPlayback {
    val now: StateFlow<AudioNow?>

    /** Plays [file] as [id], or pauses / resumes it when it's the current one. */
    fun toggle(id: String, file: LocalFile, mime: String)

    fun seek(positionMs: Long)
}

/** Android plays in the app (Media3); desktop hands the file to the system player. */
@Composable
internal expect fun rememberAudioPlayback(): AudioPlayback

internal val LocalAudio = compositionLocalOf<AudioPlayback?> { null }

/**
 * A voice note or a sound, the way VK showed them: a round play button on the left, the title and length; while it's the
 * one playing, a seek bar with the time appears in the row. Used in the feed, in posts, in the text and under comments.
 */
@Composable
internal fun AudioRow(
    media: Media,
    attachment: AttachmentState,
    modifier: Modifier = Modifier,
    onRename: ((String?) -> Unit)? = null,
    transcript: String? = null,
    transcribing: Boolean = false,
    onTranscribe: (() -> Unit)? = null,
    /** In the text the whole transcript shows (the line is sized for it); elsewhere it folds to a few lines. */
    folded: Boolean = true,
) {
    val audio = LocalAudio.current
    val now by (audio?.now ?: remember { kotlinx.coroutines.flow.MutableStateFlow<AudioNow?>(null) }).collectAsState()
    val meta = attachment.meta
    val mine = now?.takeIf { it.id == meta.id }
    val load by rememberMediaFile(media, meta.sha256)
    val file = (load as? MediaLoad.Ready)?.file
    val fallback = if (meta.kind == "voice") stringResource(Res.string.voice_note) else meta.name?.substringBeforeLast('.') ?: meta.kind
    val title = attachment.caption ?: fallback
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
                Surface(
                    onClick = { if (file != null) audio?.toggle(meta.id, file, meta.mime) },
                    enabled = file != null,
                    shape = CircleShape,
                    color = colors.primary,
                    contentColor = colors.onPrimary,
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (playing) Icons.Pause else Icons.Play,
                            stringResource(if (playing) Res.string.audio_pause else Res.string.audio_play),
                            Modifier.size(24.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    if (renaming && onRename != null) {
                        TitleField(attachment.caption.orEmpty(), fallback) {
                            renaming = false
                            onRename(it)
                        }
                    } else {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (onRename != null) Modifier.clickable { renaming = true } else Modifier,
                        )
                    }
                    val total = mine?.durationMs?.takeIf { it > 0 } ?: meta.durationMs ?: 0
                    if (mine != null && total > 0) {
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        val fraction = dragging ?: (mine.positionMs.toFloat() / total).coerceIn(0f, 1f)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Slider(
                                value = fraction,
                                onValueChange = { dragging = it },
                                onValueChangeFinished = {
                                    dragging?.let { audio?.seek((it * total).toLong()) }
                                    dragging = null
                                },
                                modifier = Modifier.weight(1f).height(28.dp),
                            )
                            Text(
                                durationLabel((fraction * total).toLong()),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            meta.durationMs?.let(::durationLabel).orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                when {
                    transcribing -> CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp)

                    transcript == null && onTranscribe != null -> IconButton(onTranscribe) {
                        Icon(Icons.Transcribe, stringResource(Res.string.speech_transcribe), tint = colors.onSurfaceVariant)
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
                    modifier = Modifier.fillMaxWidth().then(if (folded) Modifier.clickable { open = !open } else Modifier)
                        .padding(start = TRANSCRIPT_START, end = TRANSCRIPT_END, bottom = 10.dp),
                )
            }
        }
    }
}

/** Where a transcript starts and ends in the row: under the title, past the play button. */
internal val TRANSCRIPT_START = 64.dp
internal val TRANSCRIPT_END = 12.dp
private const val FOLDED_LINES = 3

/** A sound with what was said in it, wired to this device's transcriber (7.6): the button shows once the model is here. */
@Composable
internal fun VoiceRow(
    services: AppServices,
    entryId: String?,
    attachment: AttachmentState,
    transcript: String?,
    modifier: Modifier = Modifier,
    onRename: ((String?) -> Unit)? = null,
    folded: Boolean = true,
) {
    val transcriptions = services.transcriptions
    val model = transcriptions?.model?.state?.collectAsState()?.value
    val running = transcriptions?.running?.collectAsState()?.value.orEmpty()
    AudioRow(
        services.media,
        attachment,
        modifier,
        onRename,
        transcript = transcript,
        transcribing = attachment.meta.id in running,
        onTranscribe = if (transcriptions != null && entryId != null && model == SpeechModel.State.Ready) {
            { transcriptions.transcribe(entryId, attachment) }
        } else {
            null
        },
        folded = folded,
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
