package app.benbow.desktop

import app.benbow.app.AudioEngine
import app.benbow.app.AudioEvents
import app.benbow.app.Track
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import java.awt.Desktop
import java.awt.EventQueue
import java.io.File

/**
 * Desktop's player (ADR-035): JavaFX Media, which plays mp3, AAC (m4a), WAV and AIFF on its own thread. What it can't
 * play (flac, ogg) opens in the system's player, as everything did before. Its reports reach the app on Swing's thread.
 */
class JavaFxAudio : AudioEngine {
    override var events: AudioEvents? = null
    private var player: MediaPlayer? = null
    private var reported = 0L

    /** JavaFX starts once, on first use; where it can't (a broken runtime), sounds open outside. */
    private val ready: Boolean by lazy {
        try {
            Platform.setImplicitExit(false) // no JavaFX windows: it mustn't stop when "the last one" closes
            Platform.startup {}
            true
        } catch (_: IllegalStateException) {
            true // already running
        } catch (_: Throwable) {
            false
        }
    }

    override fun canPlay(mime: String): Boolean = extensionOf(mime) in PLAYABLE && ready

    override fun load(file: File, track: Track, cover: File?) {
        // JavaFX goes by the file's extension; media files are named by their hash, so it gets a named copy.
        val named = named(file, extensionOf(track.mime))
        fx {
            player?.dispose()
            player = MediaPlayer(Media(named.toURI().toString())).apply {
                statusProperty().addListener { _, _, _ -> report(force = true) }
                currentTimeProperty().addListener { _, _, _ -> report(force = false) }
                setOnEndOfMedia { swing { events?.ended() } }
                setOnError { swing { events?.failed() } }
            }
        }
    }

    override fun play() = fx {
        player?.let { if (it.status == MediaPlayer.Status.UNKNOWN) it.isAutoPlay = true else it.play() }
    }

    override fun pause() = fx { player?.pause() }

    override fun seek(positionMs: Long) = fx { player?.seek(Duration.millis(positionMs.toDouble())) }

    override fun stop() = fx {
        player?.dispose()
        player = null
    }

    override fun openOutside(file: File, mime: String) {
        runCatching { Desktop.getDesktop().open(named(file, extensionOf(mime))) }
    }

    /** Status changes at once, the position about four times a second. On JavaFX's thread. */
    private fun MediaPlayer.report(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - reported < 250) return
        reported = now
        val playing = status == MediaPlayer.Status.PLAYING
        val position = currentTime.toMillis().toLong()
        val length = media.duration.takeUnless { it.isUnknown || it.isIndefinite }?.toMillis()?.toLong() ?: 0
        val buffering = status == MediaPlayer.Status.STALLED
        swing { events?.state(playing, position, length, buffering) }
    }

    private fun fx(block: () -> Unit) {
        if (ready) Platform.runLater(block)
    }

    private fun swing(block: () -> Unit) = EventQueue.invokeLater(block)

    private fun named(file: File, extension: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "benbow-audio").apply { mkdirs() }
        val copy = File(dir, "${file.name}.$extension")
        if (!copy.exists() || copy.length() != file.length()) file.copyTo(copy, overwrite = true)
        return copy
    }

    private fun extensionOf(mime: String): String = when (val subtype = mime.substringAfter('/').substringBefore(';').lowercase()) {
        "mpeg", "mp3" -> "mp3"
        "mp4", "x-m4a", "m4a" -> "m4a"
        "aac" -> "aac"
        "wav", "x-wav", "wave", "vnd.wave" -> "wav"
        "aiff", "x-aiff" -> "aiff"
        "ogg", "opus" -> "ogg"
        "flac", "x-flac" -> "flac"
        else -> subtype
    }

    private companion object {
        /** What JavaFX Media decodes (docs: javafx.scene.media). */
        val PLAYABLE = setOf("mp3", "m4a", "aac", "wav", "aiff")
    }
}
