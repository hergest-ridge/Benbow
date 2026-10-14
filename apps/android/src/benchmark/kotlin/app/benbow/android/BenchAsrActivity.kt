package app.benbow.android

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.benbow.android.asr.ASR_RATE
import app.benbow.android.asr.AsrModel
import app.benbow.android.asr.decodeTo16k
import app.benbow.android.asr.load
import app.benbow.android.asr.transcribe
import app.benbow.android.asr.wavTo16k
import app.benbow.android.asr.withAsr
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Benchmark builds only: tries the sandboxed recognizer on made-up speech. The model is pushed by adb into this
 * build's external files (`asr/`), test WAVs into `asr/test/`; a phrase is also spoken by the phone's own TTS. The
 * report (load and decode times, text, the sandbox's network probe) lands in `asr/report.txt`. No microphone.
 */
class BenchAsrActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = getExternalFilesDir("asr")!!
        thread {
            val report = StringBuilder()
            runCatching { run(dir, report) }.onFailure { report.appendLine("FAILED: ${it.stackTraceToString()}") }
            File(dir, "report.txt").writeText(report.toString())
            Log.i("BenbowAsr", report.toString())
            runOnUiThread { finish() }
        }
    }

    private fun run(dir: File, report: StringBuilder) {
        fun find(prefix: String) = dir.listFiles()!!.first { it.name.startsWith(prefix) && it.isFile }
        val model = AsrModel(find("encoder"), find("decoder"), find("joiner"), find("tokens"))
        val spoken = speak(report)
        // The phrase also as a voice note is stored (AAC in MP4), so the real decoding path is tried too.
        val voice = spoken?.let { File(cacheDir, "tts-phrase.m4a").also { m4a -> encodeAac(wavTo16k(it.readBytes()), m4a) } }
        val inputs = (dir.listFiles()!!.filter { it.name.endsWith(".wav") } + listOfNotNull(spoken, voice)).sortedBy { it.name }
        runBlocking {
            withAsr { asr ->
                var t = SystemClock.elapsedRealtime()
                val error = asr.load(model)
                report.appendLine("load: ${SystemClock.elapsedRealtime() - t} ms ${error ?: "ok"}")
                report.appendLine("network from the sandbox: ${asr.probeNetwork()}")
                if (error != null) return@withAsr
                for (wav in inputs) {
                    val samples = if (wav.name.endsWith(".m4a")) decodeTo16k(wav) else wavTo16k(wav.readBytes())
                    val seconds = samples.size / ASR_RATE.toFloat()
                    t = SystemClock.elapsedRealtime()
                    val text = asr.transcribe(samples)
                    val ms = SystemClock.elapsedRealtime() - t
                    report.appendLine(
                        "${wav.name}: %.1f s audio, $ms ms (x%.1f faster than real time)".format(seconds, seconds * 1000 / ms),
                    )
                    report.appendLine("  -> $text")
                }
            }
        }
        voice?.let { attachAsVoiceNote(it, report) }
    }

    /** A note "Проверка голоса" with the phrase as its voice note, for trying transcription in the app itself. */
    private fun attachAsVoiceNote(m4a: File, report: StringBuilder) = runBlocking {
        val services = (application as BenbowApplication).services
        val today = java.time.LocalDate.now().toString()
        val entry = services.repo.createNote(today, "Проверка голоса")
        val file = m4a.inputStream().use { services.media.import(it, "audio/mp4", "voice-check.m4a", null) }
        services.repo.attach(entry, file, "voice", durationMs = 12_000)
        report.appendLine("attached a voice note to a new entry")
    }

    /** 16 kHz mono samples into AAC in an MP4 container, the way voice notes are stored. */
    private fun encodeAac(samples: FloatArray, target: File) {
        val format = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, ASR_RATE, 1).apply {
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = android.media.MediaMuxer(target.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = android.media.MediaCodec.BufferInfo()
        var track = -1
        var pos = 0
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val n = minOf((samples.size - pos), buffer.remaining() / 2)
                    for (i in 0 until n) buffer.putShort((samples[pos + i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                    val time = pos * 1_000_000L / ASR_RATE
                    pos += n
                    if (pos >= samples.size) {
                        codec.queueInputBuffer(index, 0, n * 2, time, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, n * 2, time, 0)
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, 10_000)
            if (out == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
            } else if (out >= 0) {
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info)
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
    }

    /** Has the phone's text-to-speech say a phrase into a WAV; null when no Russian voice is there. */
    private fun speak(report: StringBuilder): File? {
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts = TextToSpeech(this) {
            status = it
            ready.countDown()
        }
        try {
            ready.await(10, TimeUnit.SECONDS)
            val lang = tts.setLanguage(Locale.forLanguageTag("ru-RU"))
            report.appendLine("tts: engine ${tts.defaultEngine}, init $status, ru $lang")
            if (status != TextToSpeech.SUCCESS || lang < TextToSpeech.LANG_AVAILABLE) return null
            val file = File(cacheDir, "tts-phrase.wav")
            val done = CountDownLatch(1)
            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) = done.countDown()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = done.countDown()
                },
            )
            tts.synthesizeToFile(PHRASE, Bundle(), file, "phrase")
            done.await(30, TimeUnit.SECONDS)
            report.appendLine("tts phrase: $PHRASE")
            return file.takeIf { it.length() > 44 }
        } finally {
            tts.shutdown()
        }
    }

    private companion object {
        const val PHRASE = "Сегодня утром мы гуляли у моря. Ветер был холодный, но солнце светило ярко! " +
            "Вечером я записал пару идей для проекта, и, кажется, понял, как устроить синхронизацию."
    }
}
