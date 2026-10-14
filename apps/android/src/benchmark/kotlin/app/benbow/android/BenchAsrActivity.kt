package app.benbow.android

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.benbow.android.asr.ASR_RATE
import app.benbow.android.asr.AsrModel
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
        val model = AsrModel(find("encoder"), find("decoder"), find("joiner"), find("tokens"), find("silero_vad"))
        val inputs = (
            File(dir, "test").listFiles().orEmpty().filter {
                it.name.endsWith(".wav")
            } + listOfNotNull(speak(report))
            ).sortedBy { it.name }
        runBlocking {
            withAsr { asr ->
                var t = SystemClock.elapsedRealtime()
                val error = asr.load(model)
                report.appendLine("load: ${SystemClock.elapsedRealtime() - t} ms ${error ?: "ok"}")
                report.appendLine("network from the sandbox: ${asr.probeNetwork()}")
                if (error != null) return@withAsr
                for (wav in inputs) {
                    val samples = wavTo16k(wav.readBytes())
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
