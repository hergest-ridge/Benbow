package app.benbow.app

import app.benbow.client.SpeechModel
import app.benbow.core.speech.PhraseCutter
import app.benbow.core.speech.isSilent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** How a dictation ended (7.10). */
enum class DictationEnd {
    /** What was said is text now. */
    Text,

    /** Nothing was said. */
    Silence,

    /** The microphone or recognition failed, or no words came out: the caller keeps the take as a voice note. */
    Failed,
}

/**
 * Dictation (7.10): text from the microphone, phrase by phrase while it's said. [audio] brings the take as 16 kHz mono
 * samples and ends when the key comes up. [PhraseCutter] cuts it at pauses; one recognizer session, opened while the
 * first words are said, turns the phrases into text in order and hands each to [onPhrase]. [pending] counts phrases
 * cut but not text yet (the editor's "…"). The microphone never waits for recognition. When something fails,
 * [onFailure] hears at once (the screen can say the take becomes a voice note) and the end is [DictationEnd.Failed]:
 * the phrases already handed over stay, and the caller keeps the whole take, so nothing said is lost.
 */
suspend fun dictate(
    transcriber: Transcriber,
    model: SpeechModel,
    audio: Flow<FloatArray>,
    pending: MutableStateFlow<Int> = MutableStateFlow(0),
    onFailure: (String) -> Unit = {},
    onPhrase: suspend (String) -> Unit,
): DictationEnd = coroutineScope {
    val phrases = Channel<FloatArray>(Channel.UNLIMITED)
    val heard = async {
        try {
            transcriber.session(model) { recognizer ->
                var words = 0
                for (phrase in phrases) {
                    val text = try {
                        recognizer.samples(phrase).trim()
                    } finally {
                        pending.update { it - 1 }
                    }
                    if (text.isNotEmpty()) {
                        words++
                        onPhrase(text)
                    }
                }
                words
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pending.value = 0
            onFailure(e.message ?: e::class.simpleName.orEmpty())
            null
        }
    }
    val cutter = PhraseCutter()
    var audible = 0
    fun send(phrase: FloatArray) {
        if (heard.isCompleted || isSilent(phrase, phrase.indices)) return // after a failure the take is a voice note
        audible++
        pending.update { it + 1 }
        phrases.trySend(phrase)
    }
    val micFailed = try {
        audio.collect { chunk -> cutter.add(chunk).forEach(::send) }
        cutter.finish()?.let(::send)
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(e.message ?: e::class.simpleName.orEmpty())
        true
    } finally {
        phrases.close()
    }
    val words = heard.await()
    pending.value = 0
    when {
        micFailed || words == null -> DictationEnd.Failed

        audible == 0 -> DictationEnd.Silence

        words == 0 -> DictationEnd.Failed

        // something was said, but nothing came out as words: keep the sound
        else -> DictationEnd.Text
    }
}
