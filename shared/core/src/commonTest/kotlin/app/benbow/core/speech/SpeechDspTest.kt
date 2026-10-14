package app.benbow.core.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechDspTest {
    @Test
    fun theFftMatchesAPlainDftAtKaldisSize() {
        val n = 400
        val random = Random(3)
        val re = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val im = DoubleArray(n)
        val (outRe, outIm) = Fft(n).transform(re, im)
        for (k in listOf(0, 1, 7, 100, 199, 200, 333)) {
            var r = 0.0
            var i = 0.0
            for (t in 0 until n) {
                r += re[t] * cos(-2 * PI * k * t / n)
                i += re[t] * sin(-2 * PI * k * t / n)
            }
            assertEquals(r, outRe[k], 1e-9, "re[$k]")
            assertEquals(i, outIm[k], 1e-9, "im[$k]")
        }
    }

    @Test
    fun aToneLightsTheMelBinsAroundItsPitch() {
        val rate = 16_000
        val tone = FloatArray(rate) { (0.5 * sin(2 * PI * 1_000 * it / rate)).toFloat() }
        val frames = KaldiFbank().compute(tone)
        assertEquals(1 + (rate - 400) / 160, frames.size, "Kaldi's snip_edges framing")
        val loudest = frames[50].indices.maxBy { frames[50][it] }
        // 1 kHz is mel 1000; 0..8 kHz is mel 0..2840 in 65 steps of 43.7, so bin 22 peaks there.
        assertTrue(loudest in 21..23, "loudest bin $loudest")
        assertTrue(frames[50][loudest] - frames[50][60] > 10, "far bins stay quiet")
    }

    @Test
    fun silenceIsFlooredNotMinusInfinity() {
        val frames = KaldiFbank().compute(FloatArray(4_000))
        assertTrue(frames.all { f -> f.all { abs(it - kotlin.math.ln(1.1920929e-7).toFloat()) < 1e-3 } })
    }

    @Test
    fun longAudioIsCutAtItsQuietestMoment() {
        val rate = 16_000
        val random = Random(1)
        // 50 s of "speech" with a pause at 15 s and another at 33 s.
        val audio = FloatArray(50 * rate) { i ->
            val s = i / rate.toDouble()
            if (s in 14.8..15.2 || s in 32.8..33.2) 0f else random.nextFloat() - 0.5f
        }
        val pieces = speechPieces(audio, rate)
        assertEquals(3, pieces.size)
        assertEquals(15.0, pieces[0].last / rate.toDouble(), 0.2)
        assertEquals(33.0, pieces[1].last / rate.toDouble(), 0.2)
        assertEquals(audio.size - 1, pieces.last().last)
        assertTrue(isSilent(FloatArray(rate), 0 until rate))
    }

    @Test
    fun tokensBecomeWords() {
        val tokens = parseTokens("<unk> 0\n▁при 1\nвет 2\n! 3\n▁мир 4\n<blk> 5\n")
        assertEquals(6, tokens.size)
        assertEquals("привет! мир", detokenize(listOf(1, 2, 3, 4), tokens))
    }
}
