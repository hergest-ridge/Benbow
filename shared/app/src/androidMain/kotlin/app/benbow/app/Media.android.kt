package app.benbow.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.benbow.app.resources.Res
import app.benbow.app.resources.media_camera
import app.benbow.app.resources.media_gallery
import app.benbow.app.resources.media_mic_denied
import app.benbow.app.resources.media_recording
import app.benbow.app.resources.media_voice
import app.benbow.app.resources.voice_discard
import app.benbow.app.resources.voice_tap_to_finish
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import java.io.File

@Composable
internal actual fun MediaBar(services: AppServices, entryId: suspend () -> String, modifier: Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
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
    // A tap starts a recording that runs until the next tap (hands free); holding records only while held.
    var locked by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> denied = !granted }
    val keep: (Boolean) -> Unit = { save ->
        locked = false
        val result = recorder.stop()
        if (!save || result == null || result.second < MIN_VOICE_MS) {
            result?.first?.delete()
        } else {
            val (file, duration) = result
            services.attach(entryId, { file.inputStream() }, onAttached = { file.delete() }) {
                MediaProbe("audio/mp4", file.name, "voice", durationMs = duration, capturedAt = System.currentTimeMillis())
            }
        }
    }
    // Leaving the screen mid-recording keeps what was said rather than dropping it.
    DisposableEffect(recorder) { onDispose { if (recorder.recording) keep(true) } }

    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) {
            Icon(Icons.Gallery, stringResource(Res.string.media_gallery))
        }
        IconButton({
            val file = File(File(context.filesDir, "capture").apply { mkdirs() }, "photo-${System.currentTimeMillis()}.jpg")
            photoPath = file.path
            camera.launch(uriFor(context, file))
        }) { Icon(Icons.Camera, stringResource(Res.string.media_camera)) }
        // Hold to record and release to keep (Telegram-style), or tap to start and tap again to keep.
        Box(
            Modifier.size(48.dp).pointerInput(recorder) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (locked) {
                        waitForUpOrCancellation()
                        keep(true)
                        return@awaitEachGesture
                    }
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    if (!recorder.start()) return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val up = waitForUpOrCancellation()
                    if (up != null && up.uptimeMillis - down.uptimeMillis < TAP_MS) {
                        locked = true // a tap: keep recording, hands free, until the next tap
                    } else {
                        keep(up != null)
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Mic,
                stringResource(Res.string.media_voice),
                tint = if (recorder.recording) MaterialTheme.colorScheme.error else LocalContentColor.current,
            )
        }
        when {
            recorder.recording -> {
                val elapsed by produceState(0L, recorder.recording) {
                    while (recorder.recording) {
                        value = recorder.elapsedMs()
                        delay(200)
                    }
                }
                Text(
                    "● ${durationLabel(elapsed)}" + if (locked) " · " + stringResource(Res.string.voice_tap_to_finish) else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (locked) IconButton({ keep(false) }) { Icon(Icons.Close, stringResource(Res.string.voice_discard)) }
            }

            denied -> Text(stringResource(Res.string.media_mic_denied), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal actual fun MediaPlayer(file: File, mime: String, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file))) // the container is sniffed; files have no extension
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = modifier.fillMaxSize())
}

private fun uriFor(context: android.content.Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private const val MAX_PICK = 20
private const val MIN_VOICE_MS = 700L

/** A press shorter than this is a tap: the recording goes on hands free. */
private const val TAP_MS = 350L

@Composable
internal actual fun Modifier.attachOnDrop(services: AppServices, entryId: suspend () -> String, onHover: (Boolean) -> Unit): Modifier = this
