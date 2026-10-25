package app.benbow.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.benbow.app.resources.Res
import app.benbow.app.resources.media_audio
import app.benbow.app.resources.media_camera
import app.benbow.app.resources.media_gallery
import app.benbow.app.resources.media_mic_denied
import app.benbow.app.resources.recorder_text
import app.benbow.app.resources.recorder_text_about
import app.benbow.app.resources.recorder_voice
import app.benbow.app.resources.recorder_voice_about
import app.benbow.app.resources.recording_text
import app.benbow.app.resources.recording_voice
import app.benbow.client.SpeechModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File

@Composable
internal actual fun MediaBar(
    services: AppServices,
    entryId: suspend () -> String,
    dictation: DictationTarget?,
    modifier: Modifier,
    tools: @Composable RowScope.() -> Unit,
) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        uris.forEach { uri ->
            services.attach(entryId, { context.contentResolver.openInputStream(uri)!! }) { AndroidProbe.uri(context, uri) }
        }
    }
    // Sounds from elsewhere (ADR-035): the system's file picker, so music apps' and recorders' folders are all there.
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            services.attach(entryId, { context.contentResolver.openInputStream(uri)!! }) { AndroidProbe.uri(context, uri) }
        }
    }
    // Survives the activity being recreated while the camera app is in front.
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = photoPath?.let(::File) ?: return@rememberLauncherForActivityResult
        photoPath = null
        if (saved && file.length() > 0) {
            services.attach(entryId, { file.inputStream() }, onAttached = { file.delete() }) { AndroidProbe.photo(file) }
        } else {
            file.delete()
        }
    }
    val recorder = remember { VoiceRecorder(context) }
    var denied by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> denied = !granted }
    val entry by rememberUpdatedState(entryId)
    val target by rememberUpdatedState(dictation)
    val dictations = services.dictations?.takeIf { dictation != null }
    val keys = remember(recorder, dictations) {
        RecorderKeys(
            start = { mode ->
                if (!micAllowed(context)) {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                    false
                } else {
                    services.player.pause() // the microphone mustn't hear the music
                    when (mode) {
                        RecorderMode.Voice -> recorder.start()
                        RecorderMode.Text -> target?.let { dictations?.start(it) } == true
                    }
                }
            },
            finish = { mode, keep ->
                when (mode) {
                    RecorderMode.Voice -> keepVoice(services, entry, recorder, keep)
                    RecorderMode.Text -> dictations?.stop(keep)
                }
            },
        )
    }
    // A take that ends by itself (the microphone failed) lets its key up.
    if (dictations != null) LaunchedEffect(dictations) { dictations.active.collect { if (it == null) keys.ended(RecorderMode.Text) } }
    // Leaving the screen mid-take keeps what was said rather than dropping it.
    DisposableEffect(keys) { onDispose { keys.up(keep = true) } }

    val down = keys.down
    val meter by produceState(0L to 0f, down) {
        while (down != null) {
            value = when (down) {
                RecorderMode.Voice -> recorder.elapsedMs() to recorder.level()
                RecorderMode.Text -> dictations?.active?.value?.let { it.elapsedMs to it.level } ?: (0L to 0f)
            }
            delay(80)
        }
        value = 0L to 0f
    }
    val pending = dictations?.pending?.collectAsState()?.value ?: 0
    val failing = dictations?.failing?.collectAsState()?.value == true
    val model = dictations?.model?.state?.collectAsState()?.value

    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (down == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) {
                        Icon(Icons.Gallery, stringResource(Res.string.media_gallery))
                    }
                    IconButton({
                        val file = File(File(context.filesDir, "capture").apply { mkdirs() }, "photo-${System.currentTimeMillis()}.jpg")
                        photoPath = file.path
                        camera.launch(uriFor(context, file))
                    }) { Icon(Icons.Camera, stringResource(Res.string.media_camera)) }
                    IconButton({ pickAudio.launch(arrayOf("audio/*")) }) { Icon(Icons.MusicNote, stringResource(Res.string.media_audio)) }
                    tools()
                    if (denied) {
                        Text(stringResource(Res.string.media_mic_denied), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                }
            } else {
                val text = down == RecorderMode.Text && !failing
                RecordingStrip(
                    label = stringResource(if (text) Res.string.recording_text else Res.string.recording_voice),
                    lamp = if (text) TextLamp else VoiceLamp,
                    elapsedMs = meter.first,
                    level = meter.second,
                    pending = if (down == RecorderMode.Text) pending else 0,
                    onDiscard = { keys.up(keep = false) },
                )
            }
        }
        KeyHousing(Modifier.padding(start = 4.dp)) {
            RecorderKey(
                Icons.Mic,
                stringResource(Res.string.recorder_voice),
                stringResource(Res.string.recorder_voice_about),
                VoiceLamp,
                down = down == RecorderMode.Voice,
                level = { meter.second },
                onPress = { keys.press(RecorderMode.Voice) },
                onRelease = { keys.release(RecorderMode.Voice, it) },
            )
            if (dictations != null) {
                RecorderKey(
                    Icons.Transcribe,
                    stringResource(Res.string.recorder_text),
                    stringResource(Res.string.recorder_text_about),
                    if (failing) VoiceLamp else TextLamp,
                    down = down == RecorderMode.Text,
                    level = { meter.second },
                    onPress = { keys.press(RecorderMode.Text) },
                    onRelease = { keys.release(RecorderMode.Text, it) },
                    locked = model != SpeechModel.State.Ready,
                    onLocked = { services.messages.tryEmit(AppMessage.DictationNeedsModel) },
                )
            }
        }
    }
}

/** The текст key for a quick note (7.10d): the same key in the same housing, beside the field. */
@Composable
internal actual fun DictationKey(services: AppServices, target: DictationTarget, modifier: Modifier) {
    val dictations = services.dictations ?: return
    val context = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) services.messages.tryEmit(AppMessage.MicDenied)
    }
    val current by rememberUpdatedState(target)
    val keys = remember(dictations) {
        RecorderKeys(
            start = {
                if (!micAllowed(context)) {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                    false
                } else {
                    services.player.pause()
                    dictations.start(current)
                }
            },
            finish = { _, keep -> dictations.stop(keep) },
        )
    }
    LaunchedEffect(dictations) { dictations.active.collect { if (it == null) keys.ended(RecorderMode.Text) } }
    DisposableEffect(keys) { onDispose { keys.up(keep = true) } }
    val down = keys.down
    val level by produceState(0f, down) {
        while (down != null) {
            value = dictations.active.value?.level ?: 0f
            delay(80)
        }
        value = 0f
    }
    val failing by dictations.failing.collectAsState()
    val model by dictations.model.state.collectAsState()
    KeyHousing(modifier) {
        RecorderKey(
            Icons.Transcribe,
            stringResource(Res.string.recorder_text),
            stringResource(Res.string.recorder_text_about),
            if (failing) VoiceLamp else TextLamp,
            down = down == RecorderMode.Text,
            level = { level },
            onPress = { keys.press(RecorderMode.Text) },
            onRelease = { keys.release(RecorderMode.Text, it) },
            locked = model != SpeechModel.State.Ready,
            onLocked = { services.messages.tryEmit(AppMessage.DictationNeedsModel) },
        )
    }
}

/** The voice key came up: the recording becomes a voice note, unless it was thrown away or too short to be one. */
private fun keepVoice(services: AppServices, entryId: suspend () -> String, recorder: VoiceRecorder, keep: Boolean) {
    val result = recorder.stop()
    if (!keep || result == null || result.second < MIN_VOICE_MS) {
        result?.first?.delete()
        return
    }
    val (file, duration) = result
    services.attach(entryId, { file.inputStream() }, onAttached = { file.delete() }) {
        MediaProbe("audio/mp4", file.name, "voice", durationMs = duration, capturedAt = System.currentTimeMillis())
    }
}

private fun micAllowed(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
internal actual fun MediaPlayer(file: File, mime: String, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(file) {
        // A video takes audio focus, so the app's player (ADR-035) pauses while it plays.
        ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.DEFAULT, true).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file))) // the container is sniffed; files have no extension
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = modifier.fillMaxSize())
}

internal actual suspend fun coverJpeg(image: ByteArray, max: Int): ByteArray? = withContext(Dispatchers.Default) {
    AndroidProbe.cover(image, max)
}

private fun uriFor(context: android.content.Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private const val MAX_PICK = 20
private const val MIN_VOICE_MS = 700L

@Composable
internal actual fun Modifier.attachOnDrop(
    services: AppServices,
    entryId: suspend () -> String,
    onHover: (Boolean) -> Unit,
    onLink: (String) -> Unit,
): Modifier = this
