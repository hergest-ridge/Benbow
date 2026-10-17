package app.benbow.app

import app.benbow.client.SpeechModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DictationTest {
    @Test
    fun phrasesBecomeTextInOrderInOneSession() {
        var n = 0
        val transcriber = FakeTranscriber { "фраза ${++n}" }
        val texts = ArrayList<String>()
        val pending = MutableStateFlow(0)
        val end = runBlocking {
            dictate(transcriber, ReadyModel, chunks(speech(1.5) + silence(1.0) + speech(1.5) + silence(1.0) + speech(1.0)), pending) {
                texts += it
            }
        }
        assertEquals(DictationEnd.Text, end)
        assertEquals(listOf("фраза 1", "фраза 2", "фраза 3"), texts)
        assertEquals(1, transcriber.sessions)
        assertEquals(0, pending.value)
    }

    @Test
    fun aFailedPhraseKeepsWhatCameBeforeAndEndsAsFailed() {
        var n = 0
        val transcriber = FakeTranscriber { if (++n == 1) " первая " else error("the recognizer stopped") }
        val texts = ArrayList<String>()
        val failures = ArrayList<String>()
        val end = runBlocking {
            dictate(transcriber, ReadyModel, chunks(speech(1.5) + silence(1.0) + speech(1.5) + silence(1.0) + speech(1.0)), onFailure = {
                failures += it
            }) { texts += it }
        }
        assertEquals(DictationEnd.Failed, end)
        assertEquals(listOf("первая"), texts)
        assertEquals(listOf("the recognizer stopped"), failures)
        assertEquals(2, n, "nothing is recognized after the failure")
    }

    @Test
    fun aModelThatWontLoadFails() {
        val failures = ArrayList<String>()
        val end = runBlocking {
            dictate(FakeTranscriber(opens = false) { "never" }, ReadyModel, chunks(speech(1.5)), onFailure = { failures += it }) {
                error("no text without a model")
            }
        }
        assertEquals(DictationEnd.Failed, end)
        assertEquals(listOf("the model didn't load"), failures)
    }

    @Test
    fun silenceIsNothingAndSpeechWithoutWordsIsKept() {
        val silent = FakeTranscriber { "не должно быть" }
        assertEquals(DictationEnd.Silence, runBlocking { dictate(silent, ReadyModel, chunks(silence(3.0))) { error("no text") } })
        assertEquals(0, silent.phrases)
        val mumbled = FakeTranscriber { "" }
        assertEquals(DictationEnd.Failed, runBlocking { dictate(mumbled, ReadyModel, chunks(speech(1.5))) { error("no text") } })
        assertEquals(1, mumbled.phrases)
    }

    @Test
    fun aMicrophoneErrorFails() {
        val audio = flow {
            chunks(speech(1.5) + silence(1.0)).collect { emit(it) }
            error("microphone read failed")
        }
        val texts = ArrayList<String>()
        val end = runBlocking { dictate(FakeTranscriber { "до сбоя" }, ReadyModel, audio) { texts += it } }
        assertEquals(DictationEnd.Failed, end)
        assertEquals(listOf("до сбоя"), texts)
    }

    @Test
    fun theMicrophoneNeverWaitsForRecognition() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val micDone = CompletableDeferred<Unit>()
        val transcriber = FakeTranscriber {
            gate.await()
            "текст"
        }
        val audio = flow {
            chunks(speech(1.5) + silence(1.0) + speech(1.5)).collect { emit(it) }
            micDone.complete(Unit)
        }
        val pending = MutableStateFlow(0)
        val texts = ArrayList<String>()
        val running = async { dictate(transcriber, ReadyModel, audio, pending) { texts += it } }
        withTimeout(5_000) { micDone.await() }
        assertTrue(texts.isEmpty())
        assertEquals(2, pending.value, "both phrases wait for text")
        gate.complete(Unit)
        assertEquals(DictationEnd.Text, running.await())
        assertEquals(listOf("текст", "текст"), texts)
    }

    private class FakeTranscriber(private val opens: Boolean = true, private val hear: suspend (FloatArray) -> String) : Transcriber {
        var sessions = 0
        var phrases = 0

        override suspend fun <T> session(model: SpeechModel, block: suspend (Recognizer) -> T): T {
            sessions++
            if (!opens) error("the model didn't load")
            return block(
                object : Recognizer {
                    override suspend fun file(sound: java.io.File) = error("not for dictation")

                    override suspend fun samples(samples: FloatArray): String {
                        phrases++
                        return hear(samples)
                    }
                },
            )
        }
    }

    private object ReadyModel : SpeechModel {
        override val state = MutableStateFlow<SpeechModel.State>(SpeechModel.State.Ready)

        override fun file(name: String) = error("unused")

        override suspend fun fetch() = Unit

        override fun delete() = Unit
    }

    /** The take as a microphone hands it over: 100 ms at a time. */
    private fun chunks(audio: FloatArray): Flow<FloatArray> = flow {
        for (at in audio.indices step RATE / 10) emit(audio.copyOfRange(at, minOf(audio.size, at + RATE / 10)))
    }

    /** A voice-like tone: 220 Hz, louder and quieter four times a second like syllables. */
    private fun speech(seconds: Double) = FloatArray((seconds * RATE).toInt()) {
        val t = it.toDouble() / RATE
        (0.2 * (0.6 + 0.4 * sin(2 * PI * 4 * t)) * sin(2 * PI * 220 * t)).toFloat()
    }

    private fun silence(seconds: Double) = FloatArray((seconds * RATE).toInt())

    private companion object {
        const val RATE = 16_000
    }
}
