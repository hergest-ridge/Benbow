package app.benbow.android.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import app.benbow.core.speech.KaldiFbank
import app.benbow.core.speech.detokenize
import app.benbow.core.speech.isSilent
import app.benbow.core.speech.speechPieces
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * GigaAM v3 (RNN-T with punctuation) on ONNX Runtime, as sherpa-onnx's reference script runs it: Kaldi fbank, the
 * encoder over the whole piece (plus 2 s of silence so the last word comes out), then greedy decoding with at most
 * one token per encoder frame. Sessions are made from memory: the sandbox can't open files by path.
 */
internal class GigaAm(encoder: ByteBuffer, decoder: ByteBuffer, joiner: ByteBuffer, private val tokens: Array<String>) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "benbow").also { it.setTelemetry(false) }
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) }
    private val encoder = env.createSession(encoder, options)
    private val decoder = env.createSession(decoder, options)
    private val joiner = env.createSession(joiner, options)
    private val fbank = KaldiFbank()

    // Inputs and outputs by position, as the reference script uses them (the export's names are incidental).
    private val encoderIn = this.encoder.inputNames.toList()
    private val decoderIn = this.decoder.inputNames.toList()
    private val joinerIn = this.joiner.inputNames.toList()
    private val blank = tokens.size - 1

    fun transcribe(samples: FloatArray): String = speechPieces(samples, RATE)
        .filterNot { isSilent(samples, it) }
        .map { piece(samples.copyOfRange(it.first, it.last + 1)) }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    private fun piece(audio: FloatArray): String {
        val features = fbank.compute(audio + FloatArray(2 * RATE))
        val frames = features.size
        if (frames == 0) return ""
        val bins = features[0].size
        val x = FloatArray(bins * frames)
        for (t in 0 until frames) for (c in 0 until bins) x[c * frames + t] = features[t][c]
        val encoded: FloatArray
        val dim: Int
        val steps: Int
        tensor(x, 1, bins.toLong(), frames.toLong()).use { input ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)).use { length ->
                encoder.run(mapOf<String, OnnxTensor>(encoderIn[0] to input, encoderIn[1] to length)).use { result ->
                    val out = result.get(0) as OnnxTensor
                    val shape = out.info.shape
                    dim = shape[1].toInt()
                    steps = shape[2].toInt()
                    encoded = FloatArray(dim * steps).also { out.floatBuffer.get(it) }
                }
            }
        }
        return detokenize(greedy(encoded, dim, steps), tokens)
    }

    private fun greedy(encoded: FloatArray, dim: Int, steps: Int): List<Int> {
        val ids = ArrayList<Int>()
        var state = Prediction(FloatArray(HIDDEN), FloatArray(HIDDEN))
        var next = predict(blank, state)
        val frame = FloatArray(dim)
        for (t in 0 until steps) {
            for (d in 0 until dim) frame[d] = encoded[d * steps + t]
            val best = join(frame, next.output)
            if (best != blank) {
                ids += best
                state = next.state
                next = predict(best, state)
            }
        }
        return ids
    }

    private class Prediction(val h: FloatArray, val c: FloatArray)

    private class Step(val output: FloatArray, val state: Prediction)

    /** The prediction network after [token]: its output and the LSTM state that follows. */
    private fun predict(token: Int, state: Prediction): Step {
        val target = OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(token)), longArrayOf(1, 1))
        val length = OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1))
        val h = tensor(state.h, 1, 1, HIDDEN.toLong())
        val c = tensor(state.c, 1, 1, HIDDEN.toLong())
        try {
            decoder.run(
                mapOf<String, OnnxTensor>(decoderIn[0] to target, decoderIn[1] to length, decoderIn[2] to h, decoderIn[3] to c),
            ).use { r ->
                fun floats(index: Int) = (r.get(index) as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                return Step(floats(0), Prediction(floats(2), floats(3)))
            }
        } finally {
            target.close()
            length.close()
            h.close()
            c.close()
        }
    }

    /** The joiner's best token for one encoder frame and the current prediction. */
    private fun join(frame: FloatArray, prediction: FloatArray): Int {
        val e = tensor(frame, 1, frame.size.toLong(), 1)
        val p = tensor(prediction, 1, prediction.size.toLong(), 1)
        try {
            joiner.run(mapOf<String, OnnxTensor>(joinerIn[0] to e, joinerIn[1] to p)).use { r ->
                val logits = (r.get(0) as OnnxTensor).floatBuffer
                var best = 0
                var max = Float.NEGATIVE_INFINITY
                for (i in 0 until logits.remaining()) {
                    val v = logits.get(i)
                    if (v > max) {
                        max = v
                        best = i
                    }
                }
                return best
            }
        } finally {
            e.close()
            p.close()
        }
    }

    private fun tensor(data: FloatArray, vararg shape: Long) = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    override fun close() {
        encoder.close()
        decoder.close()
        joiner.close()
        options.close()
    }

    private companion object {
        const val RATE = 16_000
        const val HIDDEN = 320
        const val THREADS = 4
    }
}
