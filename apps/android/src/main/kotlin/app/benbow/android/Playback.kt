package app.benbow.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.benbow.app.AudioEngine
import app.benbow.app.AudioEvents
import app.benbow.app.Track
import java.io.File

/**
 * The phone's player (ADR-035): one ExoPlayer for the process. It takes audio focus (another app's sound or a video in
 * the viewer pauses it) and pauses when headphones go; [PlaybackService] shows it to the system, so it plays on in the
 * background with a notification, on the lock screen and from a headset. Main thread only.
 */
class AndroidAudio(private val context: Context) : AudioEngine {
    override var events: AudioEvents? = null
    private val main = Handler(Looper.getMainLooper())

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build().apply {
            addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        report()
                        if (isPlaying) main.post(tick)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) events?.ended() else report()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        events?.failed()
                    }
                },
            )
        }
    }

    /** The position, four times a second while it plays. */
    private val tick = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            report()
            if (player.isPlaying) main.postDelayed(this, 250)
        }
    }

    private fun report() {
        events?.state(
            player.isPlaying,
            player.currentPosition,
            player.duration.coerceAtLeast(0),
            player.playbackState == Player.STATE_BUFFERING,
        )
    }

    override fun load(file: File, track: Track, cover: File?) {
        val content = if (track.voice) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(content).build(), true)
        val metadata = MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
            .apply { cover?.let { setArtworkUri(Uri.fromFile(it)) } }.build()
        // The container is sniffed: media files are named by their hash, without an extension.
        player.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId(track.id).setMediaMetadata(metadata).build())
        player.prepare()
        context.startService(Intent(context, PlaybackService::class.java))
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seek(positionMs: Long) = player.seekTo(positionMs)

    override fun stop() {
        player.stop()
        player.clearMediaItems()
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}

/**
 * Shows the app's player to the system (media3-session): the notification while it plays (Media3 keeps the service in
 * the foreground then), the lock screen, headset and Bluetooth buttons. Not exported: only the system's media controls
 * see it. Next and previous go to the app's queue.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val audio = (application as BenbowApplication).audio
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, QueuePlayer(audio)).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped out of the recent apps: playing on is what the notification is for; paused, the service goes. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release() // the player stays: it is the app's
        session = null
        super.onDestroy()
    }

    /** One sound at a time underneath, but the system's controls offer next and previous: the queue is the app's. */
    @OptIn(UnstableApi::class) // ForwardingPlayer: stable in use for years, still marked so
    private class QueuePlayer(private val audio: AndroidAudio) : ForwardingPlayer(audio.player) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon().addAll(*QUEUE).build()

        override fun isCommandAvailable(command: Int): Boolean = command in QUEUE || super.isCommandAvailable(command)

        override fun seekToNext() {
            audio.events?.next()
        }

        override fun seekToNextMediaItem() {
            audio.events?.next()
        }

        override fun seekToPrevious() {
            audio.events?.previous()
        }

        override fun seekToPreviousMediaItem() {
            audio.events?.previous()
        }

        private companion object {
            val QUEUE = intArrayOf(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        }
    }
}
