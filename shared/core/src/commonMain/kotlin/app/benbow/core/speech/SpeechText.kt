package app.benbow.core.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** GigaAM's `tokens.txt`: "piece id" per line; a line with only an id is the word separator. */
fun parseTokens(text: String): Array<String> {
    val pairs = text.lines().filter { it.isNotBlank() }.map { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size == 1) fields[0].toInt() to " " else fields[1].toInt() to fields[0]
    }
    val tokens = Array(pairs.maxOf { it.first } + 1) { "" }
    for ((id, piece) in pairs) tokens[id] = piece
    return tokens
}

/** Pieces to text: SentencePiece marks word starts with "▁". */
fun detokenize(ids: List<Int>, tokens: Array<String>): String = ids.joinToString("") { tokens[it] }.replace('▁', ' ').trim()

/**
 * Cuts long audio into pieces of at most [maxSeconds] at the quietest moment between [minSeconds] and [maxSeconds]
 * of each piece, so no word is split (the model is trained on short utterances).
 */
fun speechPieces(samples: FloatArray, rate: Int = 16_000, minSeconds: Int = 10, maxSeconds: Int = 22): List<IntRange> {
    val pieces = ArrayList<IntRange>()
    val window = rate / 10
    var start = 0
    while (samples.size - start > maxSeconds * rate) {
        val bestAt = quietestPoint(samples, start + minSeconds * rate, start + maxSeconds * rate, window)
        pieces += start until bestAt
        start = bestAt
    }
    if (start < samples.size) pieces += start until samples.size
    return pieces
}

/** The middle of the quietest [window] of samples between [from] and [to], stepping half a window; [to] if none fits. */
internal fun quietestPoint(samples: FloatArray, from: Int, to: Int, window: Int): Int {
    var bestAt = to
    var bestEnergy = Double.MAX_VALUE
    var at = from
    while (at + window <= to) {
        var energy = 0.0
        for (i in at until at + window) energy += samples[i] * samples[i]
        if (energy < bestEnergy) {
            bestEnergy = energy
            bestAt = at + window / 2
        }
        at += window / 2
    }
    return bestAt
}

/** True when a piece holds nothing worth recognizing (its loudness stays under about -50 dBFS). */
fun isSilent(samples: FloatArray, range: IntRange): Boolean {
    var sum = 0.0
    for (i in range) sum += samples[i] * samples[i]
    return sqrt(sum / maxOf(1, range.count())) < 0.003
}

/** Band-limited resampling (windowed sinc), so a 44.1 kHz voice note doesn't fold high tones into speech. */
fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
    if (from == to || input.isEmpty()) return input
    val ratio = from.toDouble() / to
    val cutoff = minOf(1.0, 1 / ratio) * 0.95
    val out = FloatArray(floor(input.size / ratio).toInt())
    for (i in out.indices) {
        val t = i * ratio
        val center = floor(t).toInt()
        var sum = 0.0
        var weight = 0.0
        for (k in center - TAPS + 1..center + TAPS) {
            if (k < 0 || k >= input.size) continue
            val x = t - k
            val w = 0.5 + 0.5 * cos(PI * x / TAPS) // Hann window
            val s = if (x == 0.0) cutoff else sin(PI * x * cutoff) / (PI * x)
            sum += input[k] * s * w
            weight += s * w
        }
        out[i] = if (weight == 0.0) 0f else (sum / weight).toFloat()
    }
    return out
}

private const val TAPS = 16
