package app.benbow.app

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** One ExoPlayer for every play button; the position is read four times a second while something plays. */
@Composable
internal actual fun rememberAudioPlayback(): AudioPlayback {
    val context = LocalContext.current
    val playback = remember {
        object : AudioPlayback {
            val player = ExoPlayer.Builder(context).build()
            val state = MutableStateFlow<AudioNow?>(null)
            override val now: StateFlow<AudioNow?> = state

            init {
                player.addListener(
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                player.pause()
                                player.seekTo(0)
                            }
                            publish()
                        }
                    },
                )
            }

            fun publish() {
                val id = state.value?.id ?: return
                state.value = AudioNow(id, player.isPlaying, player.currentPosition, player.duration.coerceAtLeast(0))
            }

            override fun toggle(id: String, file: File, mime: String) {
                if (state.value?.id == id) {
                    if (player.isPlaying) player.pause() else player.play()
                    return
                }
                state.value = AudioNow(id, false, 0, 0)
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                player.play()
            }

            override fun seek(positionMs: Long) {
                player.seekTo(positionMs)
                publish()
            }
        }
    }
    LaunchedEffect(playback) {
        while (true) {
            if (playback.player.isPlaying) playback.publish()
            delay(250)
        }
    }
    DisposableEffect(playback) { onDispose { playback.player.release() } }
    return playback
}
