package app.benbow.android.asr

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.benbow.core.speech.resample
import java.io.File
import java.nio.ByteOrder

/** What the recognizer hears: mono float samples at 16 kHz. */
const val ASR_RATE = 16_000

/** Decodes a voice note or sound (AAC in MP4, MP3, ...) with the platform codecs into 16 kHz mono. */
fun decodeTo16k(file: File): FloatArray {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("no audio track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            return drain(extractor, codec, format)
        } finally {
            codec.release()
        }
    } finally {
        extractor.release()
    }
}

private fun drain(extractor: MediaExtractor, codec: MediaCodec, input: MediaFormat): FloatArray {
    var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    var float = false
    val mono = FloatList()
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    while (true) {
        if (!inputDone) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                if (size < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
            out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let {
                rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                float =
                    it.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    it.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            }

            out >= 0 -> {
                val buffer = codec.getOutputBuffer(out)!!.order(ByteOrder.LITTLE_ENDIAN)
                buffer.position(info.offset).limit(info.offset + info.size)
                if (float) {
                    val f = buffer.asFloatBuffer()
                    while (f.remaining() >= channels) mono.add((0 until channels).sumOf { f.get().toDouble() }.toFloat() / channels)
                } else {
                    val s = buffer.asShortBuffer()
                    while (s.remaining() >= channels) mono.add((0 until channels).sumOf { s.get().toInt() } / (channels * 32768f))
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }
    return resample(mono.toArray(), rate, ASR_RATE)
}

/** Reads a 16-bit PCM WAV (as text-to-speech writes) into 16 kHz mono. */
fun wavTo16k(bytes: ByteArray): FloatArray {
    val b = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 12
    var channels = 1
    var rate = ASR_RATE
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4, Charsets.US_ASCII)
        val size = b.getInt(pos + 4)
        if (id == "fmt ") {
            channels = b.getShort(pos + 10).toInt()
            rate = b.getInt(pos + 12)
        }
        if (id == "data") {
            val end = minOf(bytes.size, pos + 8 + size)
            val frames = (end - pos - 8) / (2 * channels)
            val mono = FloatArray(frames) { i ->
                var sum = 0
                for (c in 0 until channels) sum += b.getShort(pos + 8 + (i * channels + c) * 2)
                sum / (channels * 32768f)
            }
            return resample(mono, rate, ASR_RATE)
        }
        pos += 8 + size + (size and 1)
    }
    error("no audio in the WAV")
}

private const val TIMEOUT_US = 10_000L

/** A growable float array: a minute of 44.1 kHz audio is millions of samples. */
private class FloatList {
    private var data = FloatArray(1 shl 16)
    private var size = 0

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun toArray() = data.copyOf(size)
}
