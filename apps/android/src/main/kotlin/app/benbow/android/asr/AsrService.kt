package app.benbow.android.asr

import android.app.Service
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.system.Os
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speech recognition (GigaAM via sherpa-onnx) in an isolated process: Android runs it as a throwaway user with no
 * permissions, so the system itself keeps it off the network and out of the app's files, whatever the native code
 * would try. The model arrives as descriptors and is copied into memory files the sandbox owns (it can't open the
 * app's files by path); audio arrives through a pipe; only text goes back.
 */
class AsrService : Service() {
    private var recognizer: OfflineRecognizer? = null
    private var vadConfig: VadModelConfig? = null

    /** The VAD model stays in its memory file for as long as recognizers are made from it. */
    private var vadFile: ParcelFileDescriptor? = null

    private val binder = object : IAsr.Stub() {
        override fun load(
            encoder: ParcelFileDescriptor,
            decoder: ParcelFileDescriptor,
            joiner: ParcelFileDescriptor,
            tokens: ParcelFileDescriptor,
            vad: ParcelFileDescriptor,
        ): String? = synchronized(this@AsrService) {
            runCatching {
                recognizer?.release()
                val files = listOf(encoder, decoder, joiner, tokens).map(::inMemory)
                try {
                    val (enc, dec, join, tok) = files.map(::pathOf)
                    val model = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(encoder = enc, decoder = dec, joiner = join),
                        tokens = tok,
                        modelType = "nemo_transducer",
                        numThreads = THREADS,
                    )
                    recognizer =
                        OfflineRecognizer(
                            config = OfflineRecognizerConfig(
                                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 64),
                                modelConfig = model,
                            ),
                        )
                } finally {
                    files.forEach { it.close() } // the recognizer has read them into its own memory
                }
                vadFile?.close()
                val vadMemory = inMemory(vad).also { vadFile = it }
                vadConfig = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = pathOf(vadMemory),
                        threshold = 0.5f,
                        minSilenceDuration = 0.4f,
                        minSpeechDuration = 0.25f,
                        windowSize = WINDOW,
                        maxSpeechDuration = 20f,
                    ),
                    sampleRate = RATE,
                    numThreads = 1,
                )
            }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
        }

        override fun transcribe(pcm: ParcelFileDescriptor): String = synchronized(this@AsrService) {
            val recognizer = checkNotNull(recognizer) { "model not loaded" }
            val samples = readSamples(pcm)
            val vad = Vad(config = checkNotNull(vadConfig))
            val pieces = ArrayList<String>()
            fun drain() {
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    val stream = recognizer.createStream()
                    stream.acceptWaveform(segment.samples, RATE)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim().takeIf { it.isNotEmpty() }?.let(pieces::add)
                    stream.release()
                }
            }
            var i = 0
            while (i + WINDOW <= samples.size) {
                vad.acceptWaveform(samples.copyOfRange(i, i + WINDOW))
                drain()
                i += WINDOW
            }
            vad.flush()
            drain()
            vad.release()
            pieces.joinToString(" ")
        }

        override fun probeNetwork(): String {
            val dns = runCatching { InetAddress.getByName("github.com").hostAddress }
                .fold({ "REACHED ($it)" }, { "blocked (${it.javaClass.simpleName}: ${it.message})" })
            val tcp = runCatching { Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 3_000) } }
                .fold({ "REACHED" }, { "blocked (${it.javaClass.simpleName}: ${it.message})" })
            return "dns $dns; tcp $tcp"
        }
    }

    override fun onBind(intent: Intent) = binder

    override fun onDestroy() {
        recognizer?.release()
        vadFile?.close()
        super.onDestroy()
    }

    /** Copies a passed file into a memory file this process owns, so native code can open it by path. */
    private fun inMemory(source: ParcelFileDescriptor): ParcelFileDescriptor {
        val memory = Os.memfd_create("asr", 0)
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                var off = 0
                while (off < n) off += Os.write(memory, buffer, off, n - off)
            }
        }
        return ParcelFileDescriptor.dup(memory).also { Os.close(memory) }
    }

    private fun pathOf(file: ParcelFileDescriptor) = "/proc/self/fd/${file.fd}"

    private fun readSamples(pcm: ParcelFileDescriptor): FloatArray {
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(pcm).use { it.readBytes() }
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }

    private companion object {
        const val RATE = 16_000

        /** Silero VAD reads 512-sample windows at 16 kHz. */
        const val WINDOW = 512
        const val THREADS = 4
    }
}
