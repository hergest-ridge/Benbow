package app.benbow.core.speech

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhraseCutterTest {
    @Test
    fun aPauseAfterSpeechEndsAPhrase() {
        val (phrases, last) = cut(silence(0.5) + speech(1.5) + silence(1.0) + speech(1.5))
        assertEquals(1, phrases.size)
        // The 0.3 s lead-in, the words, then the 0.7 s pause that ended them.
        assertSeconds(2.5, phrases[0])
        // The rest of the pause (0.3 s) and the second phrase.
        assertSeconds(1.8, assertNotNull(last))
    }

    @Test
    fun shortGapsStayInOnePhrase() {
        val (phrases, last) = cut(speech(1.0) + silence(0.3) + speech(1.0))
        assertEquals(0, phrases.size)
        assertSeconds(2.3, assertNotNull(last))
    }

    @Test
    fun silenceBeforeSpeechIsTrimmedToALeadIn() {
        val (phrases, last) = cut(silence(5.0) + speech(1.0))
        assertEquals(0, phrases.size)
        assertSeconds(1.3, assertNotNull(last))
    }

    @Test
    fun aClickIsNoPhrase() {
        val (phrases, last) = cut(silence(0.5) + speech(0.06) + silence(2.0))
        assertEquals(0, phrases.size)
        assertNull(last)
    }

    @Test
    fun talkWithoutAPauseIsCutAtItsQuietestMoment() {
        val (phrases, last) = cut(speech(14.0) + silence(0.2) + speech(16.0))
        assertEquals(1, phrases.size)
        // Cut inside the short dip at 14.0-14.2 s (the very start may go while the room is being learned).
        assertTrue(phrases[0].seconds in 13.9..14.1, "first ${phrases[0].seconds} s")
        assertSeconds(16.15, assertNotNull(last))
    }

    @Test
    fun noiseInTheRoomIsNotSpeech() {
        val audio = noisy(silence(1.0) + speech(1.5) + silence(1.0) + speech(1.5) + silence(0.2), 0.02f)
        val (phrases, last) = cut(audio)
        assertEquals(1, phrases.size)
        assertSeconds(2.5, phrases[0])
        assertNotNull(last)
        val (quiet, quietLast) = cut(noisy(silence(5.0), 0.02f))
        assertEquals(0, quiet.size)
        assertNull(quietLast)
    }

    @Test
    fun chunkSizeDoesNotChangeTheCuts() {
        val audio = silence(0.4) + speech(1.2) + silence(0.9) + speech(0.8) + silence(1.0) + speech(1.0)
        val (small, smallLast) = cut(audio, chunk = 37)
        val (big, bigLast) = cut(audio, chunk = 1_600)
        assertEquals(2, big.size)
        assertEquals(big.map { it.size }, small.map { it.size })
        assertEquals(bigLast?.size, smallLast?.size)
    }

    /** Feeds [audio] in chunks as a microphone would; the phrases as they came, then what [PhraseCutter.finish] gave. */
    private fun cut(audio: FloatArray, chunk: Int = RATE / 10): Pair<List<FloatArray>, FloatArray?> {
        val cutter = PhraseCutter()
        val phrases = ArrayList<FloatArray>()
        var at = 0
        while (at < audio.size) {
            val end = minOf(audio.size, at + chunk)
            phrases += cutter.add(audio.copyOfRange(at, end))
            at = end
        }
        return phrases to cutter.finish()
    }

    /** A voice-like tone: 220 Hz, louder and quieter four times a second like syllables. */
    private fun speech(seconds: Double, amplitude: Double = 0.2) = FloatArray((seconds * RATE).toInt()) {
        val t = it.toDouble() / RATE
        (amplitude * (0.6 + 0.4 * sin(2 * PI * 4 * t)) * sin(2 * PI * 220 * t)).toFloat()
    }

    private fun silence(seconds: Double) = FloatArray((seconds * RATE).toInt())

    private fun noisy(audio: FloatArray, amplitude: Float): FloatArray {
        val random = Random(7)
        return FloatArray(audio.size) { audio[it] + (random.nextFloat() * 2 - 1) * amplitude }
    }

    private val FloatArray.seconds get() = size.toDouble() / RATE

    private fun assertSeconds(expected: Double, phrase: FloatArray) =
        assertTrue(kotlin.math.abs(phrase.seconds - expected) <= 0.05, "expected $expected s, got ${phrase.seconds} s")

    private companion object {
        const val RATE = 16_000
    }
}
