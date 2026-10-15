@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.app

import app.benbow.client.LocalFile
import app.benbow.client.uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.w3c.dom.HTMLElement

// Playback in the browser (ADR-029): its own <video> and <audio> elements, fed from the media store's blobs.

/** A <video> (or <audio>) with the browser's controls, filling its box, playing [url]. */
internal fun mediaElement(tag: String, url: String): HTMLElement = js(
    """(() => {
        const el = document.createElement(tag);
        el.src = url; el.controls = true; el.preload = 'metadata'; el.playsInline = true;
        el.style.width = '100%'; el.style.height = '100%'; el.style.objectFit = 'contain'; el.style.background = 'black';
        return el;
    })()""",
)

internal fun pauseMedia(element: HTMLElement): Unit = js("{ element.pause(); }")

/** The app's one audio player, like ExoPlayer on Android: a hidden <audio> whose state goes to [AudioPlayback.now]. */
internal class BrowserAudio : AudioPlayback {
    private val state = MutableStateFlow<AudioNow?>(null)
    override val now: StateFlow<AudioNow?> = state
    private val audio = newAudio { playing, position, duration ->
        state.value?.let { state.value = it.copy(playing = playing, positionMs = position.toLong(), durationMs = duration.toLong()) }
    }

    override fun toggle(id: String, file: LocalFile, mime: String) {
        if (state.value?.id == id) {
            togglePlay(audio)
            return
        }
        state.value = AudioNow(id, false, 0, 0)
        playFrom(audio, file.uri())
    }

    override fun seek(positionMs: Long) = seekTo(audio, positionMs / 1000.0)

    fun release() = stopAudio(audio)
}

/** An Audio that reports (playing, position ms, duration ms) on every change and four times a second while playing. */
private fun newAudio(onState: (Boolean, Double, Double) -> Unit): JsAny = js(
    """(() => {
        const a = new Audio();
        const report = () => onState(!a.paused && !a.ended, a.currentTime * 1000, isFinite(a.duration) ? a.duration * 1000 : 0);
        for (const name of ['play', 'pause', 'timeupdate', 'loadedmetadata', 'seeked']) a.addEventListener(name, report);
        a.addEventListener('ended', () => { a.currentTime = 0; report(); });
        return a;
    })()""",
)

private fun playFrom(audio: JsAny, url: String): Unit =
    js("{ audio.src = url; audio.play().catch(e => console.warn('Benbow: audio', e)); }")

private fun togglePlay(audio: JsAny): Unit = js("{ if (audio.paused) audio.play(); else audio.pause(); }")

private fun seekTo(audio: JsAny, seconds: Double): Unit = js("{ audio.currentTime = seconds; }")

private fun stopAudio(audio: JsAny): Unit = js("{ audio.pause(); audio.removeAttribute('src'); audio.load(); }")
