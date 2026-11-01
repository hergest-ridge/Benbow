@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.app

import app.benbow.client.LocalFile
import app.benbow.client.uri
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

/** A video playing in a post by itself: muted (so the browser lets it start), round and round, no controls. */
internal fun inlineVideoElement(url: String, crop: Boolean): HTMLElement = js(
    """(() => {
        const el = document.createElement('video');
        el.src = url; el.muted = true; el.loop = true; el.autoplay = true; el.playsInline = true; el.controls = false;
        el.style.width = '100%'; el.style.height = '100%'; el.style.objectFit = crop ? 'cover' : 'contain';
        el.style.pointerEvents = 'none';
        return el;
    })()""",
)

/**
 * The page's one <audio> under the app's player (ADR-035). The browser's media keys and its media panel reach the
 * queue through the Media Session API, which also shows the track's title, artist and cover there.
 */
class BrowserAudio : AudioEngine {
    override var events: AudioEvents? = null
    private val audio = newAudio(
        onState = { playing, position, duration, waiting -> events?.state(playing, position.toLong(), duration.toLong(), waiting) },
        onEnded = { events?.ended() },
        onError = { events?.failed() },
        onAction = { action ->
            when (action) {
                "next" -> events?.next()
                "previous" -> events?.previous()
            }
        },
    )

    override fun load(file: LocalFile, track: Track, cover: LocalFile?) {
        setSource(audio, file.uri())
        describe(track.title, track.artist.orEmpty(), track.album.orEmpty(), cover?.uri())
    }

    override fun play() = playAudio(audio)

    override fun pause() = pauseAudio(audio)

    override fun seek(positionMs: Long) = seekTo(audio, positionMs / 1000.0)

    override fun stop() = stopAudio(audio)
}

/**
 * An Audio that reports (playing, position ms, duration ms, waiting for data) on every change and four times a second
 * while playing; the end, a failure and the media keys' next / previous come on their own.
 */
private fun newAudio(
    onState: (Boolean, Double, Double, Boolean) -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
    onAction: (String) -> Unit,
): JsAny = js(
    """(() => {
        const a = new Audio();
        let waiting = false;
        const report = () => onState(!a.paused && !a.ended, a.currentTime * 1000, isFinite(a.duration) ? a.duration * 1000 : 0, waiting);
        for (const name of ['play', 'pause', 'timeupdate', 'loadedmetadata', 'seeked']) a.addEventListener(name, report);
        a.addEventListener('waiting', () => { waiting = true; report(); });
        a.addEventListener('playing', () => { waiting = false; report(); });
        a.addEventListener('ended', () => onEnded());
        a.addEventListener('error', () => { if (a.getAttribute('src')) onError(); });
        const session = navigator.mediaSession;
        if (session) {
            const on = (name, handler) => { try { session.setActionHandler(name, handler); } catch (e) {} };
            on('play', () => a.play());
            on('pause', () => a.pause());
            on('nexttrack', () => onAction('next'));
            on('previoustrack', () => onAction('previous'));
            on('seekto', d => { a.currentTime = d.seekTime; });
            a.addEventListener('play', () => { session.playbackState = 'playing'; });
            a.addEventListener('pause', () => { session.playbackState = 'paused'; });
        }
        return a;
    })()""",
)

private fun setSource(audio: JsAny, url: String): Unit = js("{ audio.src = url; }")

/** The browser's media panel: what plays. [art] is a blob: URL of the cover, or null. */
private fun describe(title: String, artist: String, album: String, art: String?): Unit = js(
    """{
        if (navigator.mediaSession && typeof MediaMetadata !== 'undefined')
            navigator.mediaSession.metadata = new MediaMetadata({ title, artist, album, artwork: art ? [{ src: art }] : [] });
    }""",
)

private fun playAudio(audio: JsAny): Unit = js("{ audio.play().catch(e => console.warn('Benbow: audio', e)); }")

private fun pauseAudio(audio: JsAny): Unit = js("{ audio.pause(); }")

private fun seekTo(audio: JsAny, seconds: Double): Unit = js("{ audio.currentTime = seconds; }")

private fun stopAudio(audio: JsAny): Unit = js(
    """{
        audio.pause(); audio.removeAttribute('src'); audio.load();
        if (navigator.mediaSession) { navigator.mediaSession.metadata = null; navigator.mediaSession.playbackState = 'none'; }
    }""",
)
