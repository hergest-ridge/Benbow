package app.benbow.android.asr

import android.content.Context
import app.benbow.app.Recognizer
import app.benbow.app.Transcriber
import app.benbow.client.SpeechModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app's [Transcriber]: binds the sandboxed [AsrService], hands it the model once per session, then sends each
 * sound decoded to 16 kHz mono, or dictation's samples as they are. Decoding happens here (the sandbox can't reach the
 * system's codecs); recognition happens there.
 */
class SandboxTranscriber(private val context: Context) : Transcriber {
    override suspend fun <T> session(model: SpeechModel, block: suspend (Recognizer) -> T): T = withContext(Dispatchers.IO) {
        context.withAsr { asr ->
            val files = AsrModel(
                model.file("encoder.int8.onnx"),
                model.file("decoder.onnx"),
                model.file("joiner.onnx"),
                model.file("tokens.txt"),
            )
            asr.load(files)?.let { error(it) }
            block(
                object : Recognizer {
                    override suspend fun file(sound: File) = withContext(Dispatchers.IO) { asr.transcribe(decodeTo16k(sound)) }

                    override suspend fun samples(samples: FloatArray) = withContext(Dispatchers.IO) { asr.transcribe(samples) }
                },
            )
        }
    }
}
