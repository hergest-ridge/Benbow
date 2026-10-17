package app.benbow.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * The microphone for dictation (7.10): 16 kHz mono 16-bit, what the recognizer takes, from the source tuned for speech
 * recognition. Each take is written to `capture/dictation-<time>.wav` as it's recorded, with the entry it belongs to in
 * a `.entry` file beside it, so a take outlives the app dying mid-way and becomes a voice note on the next start. A
 * take whose text is in is renamed `.done.wav` while it waits for «Приложить запись»; those are dropped on start.
 */
class AndroidDictationMic(private val context: Context) : DictationMic {
    private val dir get() = File(context.filesDir, "capture").apply { mkdirs() }

    @SuppressLint("MissingPermission") // the key asks for RECORD_AUDIO before it starts a take
    override fun start(): MicTake? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, RATE),
            )
        } catch (_: Exception) {
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        val file = File(dir, "dictation-${System.currentTimeMillis()}.wav")
        val wav = try {
            WavWriter(file)
        } catch (_: Exception) {
            record.release()
            return null
        }
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        } catch (_: Exception) {
            record.release()
            wav.close()
            file.delete()
            return null
        }
        return AndroidTake(file, record, wav)
    }

    override fun leftovers(): List<TakeFile> {
        val files = dir.listFiles { f -> f.name.startsWith("dictation-") }.orEmpty()
        files.filter { it.name.endsWith(".done.wav") || it.name.endsWith(".m4a") }.forEach { it.delete() }
        files.filter { it.name.endsWith(".entry") && !File(it.path.removeSuffix(".entry") + ".wav").exists() }.forEach { it.delete() }
        return files.filter {
            it.name.endsWith(".wav") && !it.name.endsWith(".done.wav") && it.exists()
        }.sortedBy { it.name }.map(::StoredTake)
    }
}

/** A take on disk: its WAV, the entry beside it, and the voice note made from it. */
private open class StoredTake(wav: File) : TakeFile {
    @Volatile
    protected var wav: File = wav
    private val base = wav.path.removeSuffix(".wav")
    protected val sidecar = File("$base.entry")
    private val encoded = File("$base.m4a")

    override val entryId: String? get() = sidecar.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    override val capturedAt: Long = wav.name.removePrefix("dictation-").substringBefore('.').toLongOrNull() ?: wav.lastModified()

    override suspend fun voiceNote(): VoiceNoteFile = withContext(Dispatchers.IO) {
        val samples = readWav(wav)
        encodeAac(samples, RATE, encoded)
        VoiceNoteFile(
            open = { encoded.inputStream() },
            name = "voice-$capturedAt.m4a",
            durationMs = samples.size * 1000L / RATE,
            capturedAt = capturedAt,
            delete = { encoded.delete() },
        )
    }

    override fun delete() {
        wav.delete()
        sidecar.delete()
        encoded.delete()
    }
}

/** A take being recorded: a thread reads the microphone, writes the WAV and hands the samples on. */
private class AndroidTake(file: File, private val record: AudioRecord, private val writer: WavWriter) :
    StoredTake(file),
    MicTake {
    private val samples = Channel<FloatArray>(Channel.UNLIMITED)
    override val audio: Flow<FloatArray> = samples.consumeAsFlow()

    @Volatile
    override var level = 0f
        private set

    private val startedAt = SystemClock.elapsedRealtime()

    @Volatile
    private var stoppedAt = 0L

    override val elapsedMs: Long get() = (stoppedAt.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()) - startedAt

    init {
        thread(name = "dictation-mic") { read() }
    }

    private fun read() {
        val buffer = ShortArray(RATE / 10)
        var failure: Throwable? = null
        try {
            while (stoppedAt == 0L) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) error("microphone read failed ($n)")
                if (n == 0) continue
                writer.write(buffer, n)
                val chunk = FloatArray(n) { buffer[it] / 32768f }
                var sum = 0.0
                for (x in chunk) sum += x * x
                level = levelOf(sqrt(sum / n).toFloat())
                samples.trySend(chunk)
            }
        } catch (e: Exception) {
            failure = e
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { writer.close() }
            level = 0f
            samples.close(failure)
        }
    }

    override fun stop() {
        if (stoppedAt == 0L) stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun belongsTo(entryId: String) = sidecar.writeText(entryId)

    override fun finished() {
        val done = File(wav.path.removeSuffix(".wav") + ".done.wav")
        if (wav.renameTo(done)) wav = done
        sidecar.delete()
    }
}

/** 16-bit mono PCM into a WAV file as it comes; the sizes in the header are written on [close]. */
private class WavWriter(file: File) {
    private val out = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(header(0))
    }
    private var bytes = 0
    private val chunk = ByteBuffer.allocate(RATE / 5 * 2).order(ByteOrder.LITTLE_ENDIAN)

    fun write(samples: ShortArray, n: Int) {
        chunk.clear()
        for (i in 0 until n) chunk.putShort(samples[i])
        out.write(chunk.array(), 0, n * 2) // reaches the system at once: an app crash keeps it
        bytes += n * 2
    }

    fun close() {
        out.seek(0)
        out.write(header(bytes))
        out.fd.sync()
        out.close()
    }

    private fun header(data: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".encodeToByteArray())
        putInt(36 + data)
        put("WAVEfmt ".encodeToByteArray())
        putInt(16)
        putShort(1) // PCM
        putShort(1) // mono
        putInt(RATE)
        putInt(RATE * 2)
        putShort(2)
        putShort(16)
        put("data".encodeToByteArray())
        putInt(data)
    }.array()
}

/** Our own WAV's samples; a header a crash left unfinished doesn't matter, the data runs to the end of the file. */
private fun readWav(file: File): FloatArray {
    val bytes = file.readBytes()
    val data = ByteBuffer.wrap(bytes, 44.coerceAtMost(bytes.size), (bytes.size - 44).coerceAtLeast(0)).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(data.remaining() / 2) { data.getShort() / 32768f }
}

/** Mono samples into AAC in an MP4 container, the way voice notes are stored. */
fun encodeAac(samples: FloatArray, rate: Int, target: File) {
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()
    val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val info = MediaCodec.BufferInfo()
    var track = -1
    var pos = 0
    var inputDone = false
    try {
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                    val n = minOf(samples.size - pos, buffer.remaining() / 2)
                    for (i in 0 until n) buffer.putShort((samples[pos + i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                    val time = pos * 1_000_000L / rate
                    pos += n
                    if (pos >= samples.size) {
                        codec.queueInputBuffer(index, 0, n * 2, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, n * 2, time, 0)
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, 10_000)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
            } else if (out >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info)
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    } finally {
        codec.stop()
        codec.release()
        if (track >= 0) muxer.stop()
        muxer.release()
    }
}

private const val RATE = 16_000
