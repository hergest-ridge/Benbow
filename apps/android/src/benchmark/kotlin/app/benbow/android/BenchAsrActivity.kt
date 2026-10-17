package app.benbow.android

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.benbow.android.asr.ASR_RATE
import app.benbow.android.asr.AsrModel
import app.benbow.android.asr.IAsr
import app.benbow.android.asr.decodeTo16k
import app.benbow.android.asr.load
import app.benbow.android.asr.transcribe
import app.benbow.android.asr.wavTo16k
import app.benbow.android.asr.withAsr
import app.benbow.app.encodeAac
import app.benbow.core.speech.PhraseCutter
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Benchmark builds only: tries the sandboxed recognizer on made-up speech. The model is pushed by adb into this
 * build's external files (`asr/`), test WAVs into `asr/test/`; a phrase is also spoken by the phone's own TTS. The
 * report (load and decode times, text, the sandbox's network probe, dictation whole vs phrase by phrase) lands in
 * `asr/report.txt`. No microphone.
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
        val said = speak(listOf(PHRASE) + DICTATION.flatMap { it.second }, report)
        val spoken = said?.first()?.also { report.appendLine("tts phrase: $PHRASE") }
        // The phrase also as a voice note is stored (AAC in MP4), so the real decoding path is tried too.
        val voice = spoken?.let { File(cacheDir, "tts-phrase.m4a").also { m4a -> encodeAac(wavTo16k(it.readBytes()), ASR_RATE, m4a) } }
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
                said?.let { compareDictation(asr, it.drop(1), report) }
            }
        }
        voice?.let { attachAsVoiceNote(it, report) }
    }

    /**
     * Dictation (7.10): each case is said in parts with a 1 s pause between them, as one dictates. It's recognized
     * once whole (pieces of 10-22 s, as voice notes are) and once phrase by phrase as [PhraseCutter] cuts it live, to
     * see what the short pieces cost in words and punctuation.
     */
    private fun compareDictation(asr: IAsr, said: List<File>, report: StringBuilder) {
        val pause = FloatArray(ASR_RATE)
        var next = 0
        for ((name, parts) in DICTATION) {
            val audio = parts.map { wavTo16k(said[next++].readBytes()) }.reduce { a, b -> a + pause + b }
            val reference = parts.joinToString(" ")
            var t = SystemClock.elapsedRealtime()
            val whole = asr.transcribe(audio)
            val wholeMs = SystemClock.elapsedRealtime() - t
            val cutter = PhraseCutter()
            val phrases = ArrayList<FloatArray>()
            for (at in audio.indices step ASR_RATE / 10) phrases += cutter.add(audio.copyOfRange(at, minOf(audio.size, at + ASR_RATE / 10)))
            cutter.finish()?.let { phrases += it }
            val texts = phrases.map { phrase ->
                t = SystemClock.elapsedRealtime()
                asr.transcribe(phrase) to SystemClock.elapsedRealtime() - t
            }
            val live = texts.joinToString(" ") { it.first }
            report.appendLine("dictation «$name»: %.1f s".format(audio.size / ASR_RATE.toFloat()))
            report.appendLine("  reference: $reference")
            report.appendLine("  whole:   WER %.1f%%, $wholeMs ms -> $whole".format(100 * wordErrors(reference, whole)))
            report.appendLine("  phrases: WER %.1f%% -> $live".format(100 * wordErrors(reference, live)))
            for ((i, phrase) in phrases.withIndex()) {
                report.appendLine("    %.1f s, ${texts[i].second} ms: ${texts[i].first}".format(phrase.size / ASR_RATE.toFloat()))
            }
        }
    }

    /** Word error rate: the edit distance between the words (case, "ё" and punctuation aside) over the reference's count. */
    private fun wordErrors(reference: String, heard: String): Double {
        fun words(text: String) = text.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val r = words(reference)
        val h = words(heard)
        var previous = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val row = IntArray(h.size + 1).also { it[0] = i }
            for (j in 1..h.size) row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
            previous = row
        }
        return previous[h.size].toDouble() / r.size
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

    /** Has the phone's text-to-speech say each text into its own WAV; null when no Russian voice is there. */
    private fun speak(texts: List<String>, report: StringBuilder): List<File>? {
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
            val done = ConcurrentHashMap<String, CountDownLatch>()
            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        done[utteranceId]?.countDown()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        done[utteranceId]?.countDown()
                    }
                },
            )
            return texts.mapIndexed { i, text ->
                val file = File(cacheDir, "tts-$i.wav")
                val latch = CountDownLatch(1).also { done["u$i"] = it }
                tts.synthesizeToFile(text, Bundle(), file, "u$i")
                latch.await(60, TimeUnit.SECONDS)
                file.takeIf { it.length() > 44 } ?: return null
            }
        } finally {
            tts.shutdown()
        }
    }

    private companion object {
        const val PHRASE = "Сегодня утром мы гуляли у моря. Ветер был холодный, но солнце светило ярко! " +
            "Вечером я записал пару идей для проекта, и, кажется, понял, как устроить синхронизацию."

        /** Dictation cases: the parts are said with a 1 s pause between them. */
        val DICTATION = listOf(
            "паузы между предложениями" to listOf(
                "Сегодня я проснулся рано и долго не мог понять, какой сейчас день.",
                "За окном шёл мелкий дождь, и во дворе никого не было.",
                "Я сварил кофе, сел у окна и записал несколько мыслей о работе.",
                "Потом позвонила сестра, мы проговорили почти час.",
                "Вечером хочу дочитать книгу и лечь пораньше.",
            ),
            "паузы посреди предложений" to listOf(
                "Когда мы приехали на дачу,",
                "оказалось, что ключи остались дома,",
                "поэтому пришлось ждать соседа.",
                "Он пришёл через полчаса,",
                "принёс запасной ключ и банку мёда.",
            ),
            "сплошная речь" to listOf(
                "Сегодня я проснулся рано и долго не мог понять, какой сейчас день. За окном шёл мелкий дождь, и во " +
                    "дворе никого не было. Я сварил кофе, сел у окна и записал несколько мыслей о работе. Когда мы " +
                    "приехали на дачу, оказалось, что ключи остались дома, поэтому пришлось ждать соседа. Он пришёл " +
                    "через полчаса, принёс запасной ключ и банку мёда. Вечером хочу дочитать книгу и лечь пораньше.",
            ),
        )
    }
}
