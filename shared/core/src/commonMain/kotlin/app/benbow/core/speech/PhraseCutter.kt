package app.benbow.core.speech

import kotlin.math.sqrt

/**
 * Cuts speech into phrases while it's recorded, for dictation (7.10): a phrase is ready once a pause of [pauseMs]
 * follows at least [minSpeechMs] of speech, so its text can appear while the next one is said. Speech is told from the
 * room by its loudness over the room's own noise floor (the quietest moment of the last few seconds), so a fan or the
 * street doesn't count as talking. Silence before a phrase is trimmed to a short lead-in, a click or a breath with
 * silence around it is no phrase, and a phrase that runs past [maxSeconds] without a pause is cut at its quietest
 * moment, as [speechPieces] does (the model is trained on short utterances).
 */
class PhraseCutter(
    private val rate: Int = 16_000,
    private val pauseMs: Int = 700,
    private val minSpeechMs: Int = 250,
    private val maxSeconds: Int = 20,
) {
    private val frame = rate / 50
    private val frameMs = 1000 * frame / rate
    private val leadIn = rate * LEAD_IN_MS / 1000
    private var buffer = FloatArray(rate * 4)
    private var size = 0

    /** Samples at the buffer's start already judged speech or not: whole frames. */
    private var judged = 0
    private var speechMs = 0
    private var pauseRunMs = 0
    private val levels = DoubleArray(FLOOR_FRAMES)
    private var levelCount = 0

    /** Adds samples as they're recorded; returns the phrases they finish (most often none). */
    fun add(samples: FloatArray): List<FloatArray> {
        append(samples)
        val done = ArrayList<FloatArray>(1)
        while (size - judged >= frame) {
            judge(level(judged))
            judged += frame
            if (pauseRunMs >= pauseMs) {
                if (speechMs >= minSpeechMs) done += take(judged) else speechMs = 0 // a click or a breath
            }
            if (speechMs == 0 && judged > leadIn) drop(judged - leadIn)
            if (judged >= maxSeconds * rate) {
                done += take(quietestPoint(buffer, judged / 2, judged, rate / 10))
                speechMs = judged * 1000 / rate // the rest was said without a pause
            }
        }
        return done
    }

    /** What's left when the recording stops: the last phrase, or null when nothing more was said. */
    fun finish(): FloatArray? {
        val last = if (speechMs >= minSpeechMs) buffer.copyOf(size) else null
        size = 0
        judged = 0
        speechMs = 0
        pauseRunMs = 0
        return last
    }

    private fun judge(level: Double) {
        levels[levelCount % FLOOR_FRAMES] = level
        levelCount++
        var floor = Double.MAX_VALUE
        for (i in 0 until minOf(levelCount, FLOOR_FRAMES)) floor = minOf(floor, levels[i])
        if (level > maxOf(MIN_SPEECH_LEVEL, floor * SPEECH_OVER_FLOOR)) {
            speechMs += frameMs
            pauseRunMs = 0
        } else {
            pauseRunMs += frameMs
        }
    }

    /** RMS of the frame starting at [at]. */
    private fun level(at: Int): Double {
        var sum = 0.0
        for (i in at until at + frame) sum += buffer[i] * buffer[i]
        return sqrt(sum / frame)
    }

    private fun append(samples: FloatArray) {
        if (size + samples.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + samples.size))
        samples.copyInto(buffer, size)
        size += samples.size
    }

    /** The first [n] samples as a phrase; what follows starts the next one. */
    private fun take(n: Int): FloatArray {
        val phrase = buffer.copyOf(n)
        drop(n)
        speechMs = 0
        pauseRunMs = 0
        return phrase
    }

    private fun drop(n: Int) {
        buffer.copyInto(buffer, 0, n, size)
        size -= n
        judged -= n
    }

    private companion object {
        const val LEAD_IN_MS = 300

        /** The noise floor is the quietest frame of the last 3 s: speech always has a quiet moment in that time. */
        const val FLOOR_FRAMES = 150

        /** About 10 dB over the room. */
        const val SPEECH_OVER_FLOOR = 3.0

        /** About -50 dBFS, where [isSilent] draws the line too. */
        const val MIN_SPEECH_LEVEL = 0.003
    }
}
