package app.benbow.core.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Log mel filter banks exactly as kaldi-native-fbank computes them for GigaAM (sherpa-onnx's reference script): 25 ms
 * periodic Hann windows every 10 ms at 16 kHz, no dither, DC removal or pre-emphasis, a 400-point FFT (not rounded
 * to a power of two), power spectrum, 64 Kaldi mel bins over 0..8000 Hz, natural log floored at float epsilon.
 */
class KaldiFbank(
    private val bins: Int = 64,
    private val rate: Int = 16_000,
    private val frameLength: Int = rate / 40,
    private val frameShift: Int = rate / 100,
    lowFreq: Double = 0.0,
    highFreq: Double = 8_000.0,
) {
    private val window = DoubleArray(frameLength) { 0.5 - 0.5 * cos(2 * PI * it / frameLength) }
    private val fft = Fft(frameLength)

    /** Per mel bin: the first FFT bin it covers and the triangle's weights from there. */
    private val starts = IntArray(bins)
    private val weights = arrayOfNulls<DoubleArray>(bins)

    init {
        val fftBins = frameLength / 2
        val binWidth = rate.toDouble() / frameLength
        val melLow = mel(lowFreq)
        val melHigh = mel(highFreq)
        val delta = (melHigh - melLow) / (bins + 1)
        for (b in 0 until bins) {
            val left = melLow + b * delta
            val center = melLow + (b + 1) * delta
            val right = melLow + (b + 2) * delta
            val row = DoubleArray(fftBins)
            var first = -1
            var last = -1
            for (i in 0 until fftBins) {
                val m = mel(binWidth * i)
                if (m > left && m < right) {
                    row[i] = if (m <= center) (m - left) / (center - left) else (right - m) / (right - center)
                    if (first < 0) first = i
                    last = i
                }
            }
            starts[b] = maxOf(first, 0)
            weights[b] = if (first < 0) DoubleArray(0) else row.copyOfRange(first, last + 1)
        }
    }

    /** Frames that fit whole (Kaldi's snip_edges), each [bins] long. */
    fun compute(samples: FloatArray): Array<FloatArray> {
        val count = if (samples.size < frameLength) 0 else 1 + (samples.size - frameLength) / frameShift
        val frame = DoubleArray(frameLength)
        val power = DoubleArray(frameLength / 2 + 1)
        return Array(count) { f ->
            val start = f * frameShift
            for (i in 0 until frameLength) frame[i] = samples[start + i] * window[i]
            fft.power(frame, power)
            FloatArray(bins) { b ->
                val w = weights[b]!!
                val s = starts[b]
                var energy = 0.0
                for (i in w.indices) energy += w[i] * power[s + i]
                ln(max(energy, FLOAT_EPSILON)).toFloat()
            }
        }
    }

    private companion object {
        const val FLOAT_EPSILON = 1.1920928955078125e-7

        fun mel(freq: Double) = 1127.0 * ln(1.0 + freq / 700.0)
    }
}

/** A mixed-radix FFT for any size (Kaldi's 400 = 4·4·5·5), forward direction, in the style of KISS FFT. */
internal class Fft(private val n: Int) {
    private val factors = factorize(n)
    private val twRe = DoubleArray(n) { cos(-2 * PI * it / n) }
    private val twIm = DoubleArray(n) { sin(-2 * PI * it / n) }
    private val outRe = DoubleArray(n)
    private val outIm = DoubleArray(n)
    private val inIm = DoubleArray(n)
    private val scratchRe = DoubleArray(factors.maxOrNull() ?: 1)
    private val scratchIm = DoubleArray(factors.maxOrNull() ?: 1)

    /** |X[k]|² for k = 0..n/2 of the real signal [x]. */
    fun power(x: DoubleArray, out: DoubleArray) {
        transform(x, inIm)
        for (k in 0..n / 2) out[k] = outRe[k] * outRe[k] + outIm[k] * outIm[k]
    }

    /** The complex spectrum of [re] + i·[im], left in [outRe]/[outIm]; for tests. */
    internal fun transform(re: DoubleArray, im: DoubleArray): Pair<DoubleArray, DoubleArray> {
        work(0, re, im, 0, 1, 0)
        return outRe to outIm
    }

    private fun work(out: Int, re: DoubleArray, im: DoubleArray, from: Int, stride: Int, level: Int) {
        val p = factors[level]
        val m = sizeAfter(level)
        if (m == 1) {
            for (i in 0 until p) {
                outRe[out + i] = re[from + i * stride]
                outIm[out + i] = im[from + i * stride]
            }
        } else {
            for (i in 0 until p) work(out + i * m, re, im, from + i * stride, stride * p, level + 1)
        }
        butterfly(out, stride, p, m)
    }

    private fun sizeAfter(level: Int): Int {
        var m = 1
        for (l in level + 1 until factors.size) m *= factors[l]
        return m
    }

    private fun butterfly(out: Int, stride: Int, p: Int, m: Int) {
        for (u in 0 until m) {
            var k = u
            for (q in 0 until p) {
                scratchRe[q] = outRe[out + k]
                scratchIm[q] = outIm[out + k]
                k += m
            }
            k = u
            for (q1 in 0 until p) {
                var tw = 0
                var sr = scratchRe[0]
                var si = scratchIm[0]
                for (q in 1 until p) {
                    tw += stride * k
                    if (tw >= n) tw -= n
                    sr += scratchRe[q] * twRe[tw] - scratchIm[q] * twIm[tw]
                    si += scratchRe[q] * twIm[tw] + scratchIm[q] * twRe[tw]
                }
                outRe[out + k] = sr
                outIm[out + k] = si
                k += m
            }
        }
    }

    private companion object {
        fun factorize(n: Int): IntArray {
            val result = ArrayList<Int>()
            var rest = n
            for (f in intArrayOf(4, 2, 3, 5)) {
                while (rest % f == 0) {
                    result += f
                    rest /= f
                }
            }
            var f = 7
            while (rest > 1) {
                while (rest % f == 0) {
                    result += f
                    rest /= f
                }
                f += 2
            }
            return if (result.isEmpty()) intArrayOf(1) else result.toIntArray()
        }
    }
}
