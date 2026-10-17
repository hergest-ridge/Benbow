package app.benbow.app

import androidx.compose.runtime.Immutable
import app.benbow.client.LocalFile
import app.benbow.client.Media
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A sound the player can play (ADR-035): an attachment and what to show for it. */
@Immutable
data class Track(
    /** The attachment's id. */
    val id: String,
    val entryId: String?,
    val sha256: String,
    val mime: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    /** The preview's SHA-256: the file's cover. */
    val cover: String? = null,
    val durationMs: Long? = null,
    val voice: Boolean = false,
    /** When a voice note was recorded (its line under the title). */
    val recordedAt: Long? = null,
)

/** What the player holds: a queue and where in it, and how the current track goes. */
@Immutable
data class PlayerState(
    val queue: List<Track>,
    val index: Int,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    /** The engine's length once it knows it, else the attachment's. */
    val durationMs: Long = 0,
    /** Fetching the file or waiting for data. */
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val track: Track get() = queue[index]
    val hasNext: Boolean get() = index + 1 < queue.size
}

/** What plays sounds on this platform, one at a time; the [AudioPlayer] keeps the queue. Called on the main thread. */
interface AudioEngine {
    /** Set by the player: where the engine reports. */
    var events: AudioEvents?

    /** Makes [file] the sound, from its start; [cover] pictures it in the system's media controls. */
    fun load(file: LocalFile, track: Track, cover: LocalFile?)

    fun play()

    fun pause()

    fun seek(positionMs: Long)

    /** Nothing loaded any more: the system's controls go away. */
    fun stop()

    /** Whether the app itself plays [mime] here; what it can't goes to [openOutside] (desktop: flac, ogg). */
    fun canPlay(mime: String): Boolean = true

    fun openOutside(file: LocalFile, mime: String) = Unit
}

/** What an engine tells the player, on the main thread. */
interface AudioEvents {
    /** [durationMs] is 0 while not known; [buffering] while it waits for data. */
    fun state(playing: Boolean, positionMs: Long, durationMs: Long, buffering: Boolean)

    fun ended()

    fun failed()

    /** From outside the app: the notification, a headset, the keyboard's media keys. */
    fun next()

    fun previous()
}

/**
 * The app's one player (ADR-035): a queue of tracks over this platform's [engine]. A track's file is fetched when it's
 * its turn, so scrolling past sounds never downloads them. After the last track it stops on it, paused.
 */
class AudioPlayer(private val media: Media, private val engine: AudioEngine, private val onFailure: () -> Unit = {}) : AudioEvents {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val current = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = current
    private var loading: Job? = null

    init {
        engine.events = this
    }

    /** A row's play button: its track pauses or goes on when it's the current one; otherwise it starts with [queue]. */
    fun toggle(track: Track, queue: () -> List<Track>) {
        if (current.value?.track?.id == track.id) return playPause()
        val list = queue().takeIf { list -> list.any { it.id == track.id } } ?: listOf(track)
        start(list, list.indexOfFirst { it.id == track.id })
    }

    fun playPause() {
        val now = current.value ?: return
        when {
            now.loading && !now.playing -> return
            now.failed -> start(now.queue, now.index)
            now.playing -> engine.pause()
            else -> engine.play()
        }
    }

    /** Quiet now (a recording starts: the microphone mustn't hear the music). */
    fun pause() {
        if (current.value?.playing == true) engine.pause()
    }

    override fun next() {
        val now = current.value ?: return
        if (now.hasNext) start(now.queue, now.index + 1)
    }

    /** Back to the track's start after a few seconds of it, else to the one before. */
    override fun previous() {
        val now = current.value ?: return
        if (now.positionMs > RESTART_MS || now.index == 0) seek(0) else start(now.queue, now.index - 1)
    }

    fun skipTo(index: Int) {
        val now = current.value ?: return
        if (index in now.queue.indices) start(now.queue, index)
    }

    fun seek(positionMs: Long) {
        if (current.value == null) return
        engine.seek(positionMs)
        current.update { it?.copy(positionMs = positionMs) }
    }

    /** ✕: the player goes away. */
    fun close() {
        loading?.cancel()
        engine.stop()
        current.value = null
    }

    private fun start(queue: List<Track>, index: Int) {
        loading?.cancel()
        val track = queue[index]
        current.value = PlayerState(queue, index, durationMs = track.durationMs ?: 0, loading = true)
        loading = main.launch {
            val file = try {
                media.localFile(track.sha256) ?: media.file(track.sha256)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch failed()
            }
            val cover = track.cover?.let { sha ->
                try {
                    media.localFile(sha) ?: media.file(sha)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            if (!engine.canPlay(track.mime)) {
                engine.stop()
                engine.openOutside(file, track.mime)
                current.update { it?.copy(loading = false, playing = false) }
                return@launch
            }
            engine.load(file, track, cover)
            engine.play()
        }
    }

    override fun state(playing: Boolean, positionMs: Long, durationMs: Long, buffering: Boolean) {
        current.update {
            it?.copy(
                playing = playing,
                positionMs = positionMs,
                durationMs = if (durationMs > 0) durationMs else it.durationMs,
                loading = buffering,
                failed = false,
            )
        }
    }

    override fun ended() {
        val now = current.value ?: return
        if (now.hasNext) return start(now.queue, now.index + 1)
        engine.pause()
        engine.seek(0)
        current.update { it?.copy(playing = false, positionMs = 0, loading = false) }
    }

    override fun failed() {
        if (current.value == null) return
        current.update { it?.copy(playing = false, loading = false, failed = true) }
        onFailure()
    }

    private companion object {
        const val RESTART_MS = 3_000L
    }
}

/** Plays nothing: tests, and platforms without a player. Reports what it's asked as if it played. */
class SilentAudio : AudioEngine {
    override var events: AudioEvents? = null
    private var position = 0L
    private var length = 0L

    override fun load(file: LocalFile, track: Track, cover: LocalFile?) {
        position = 0
        length = track.durationMs ?: 0
    }

    override fun play() {
        events?.state(true, position, length, false)
    }

    override fun pause() {
        events?.state(false, position, length, false)
    }

    override fun seek(positionMs: Long) {
        position = positionMs
    }

    override fun stop() = Unit
}
