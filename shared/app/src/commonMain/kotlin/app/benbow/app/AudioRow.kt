package app.benbow.app

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.audio_pause
import app.benbow.app.resources.audio_play
import app.benbow.app.resources.voice_note
import app.benbow.client.MediaStore
import app.benbow.core.sync.AttachmentState
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import java.io.File

/** What the app is playing: one sound at a time. */
data class AudioNow(val id: String, val playing: Boolean, val positionMs: Long, val durationMs: Long)

/** The app's one audio player: starting a sound stops the last; [now] feeds the play buttons and seek bars. */
interface AudioPlayback {
    val now: StateFlow<AudioNow?>

    /** Plays [file] as [id], or pauses / resumes it when it's the current one. */
    fun toggle(id: String, file: File, mime: String)

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
internal fun AudioRow(media: MediaStore, attachment: AttachmentState, modifier: Modifier = Modifier) {
    val audio = LocalAudio.current
    val now by (audio?.now ?: remember { kotlinx.coroutines.flow.MutableStateFlow<AudioNow?>(null) }).collectAsState()
    val meta = attachment.meta
    val mine = now?.takeIf { it.id == meta.id }
    val load by rememberMediaFile(media, meta.sha256)
    val file = (load as? MediaLoad.Ready)?.file
    val title = if (meta.kind == "voice") stringResource(Res.string.voice_note) else meta.name?.substringBeforeLast('.') ?: meta.kind
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
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
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        }
    }
}
