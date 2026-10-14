package app.benbow.android.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** The model's files, as the server hands them out (sherpa-onnx's GigaAM v3 package plus Silero VAD). */
class AsrModel(val encoder: File, val decoder: File, val joiner: File, val tokens: File, val vad: File) {
    val files get() = listOf(encoder, decoder, joiner, tokens, vad)
}

/** Binds [AsrService] for the length of [block]; the sandboxed process goes away when nothing holds it. */
suspend fun <T> Context.withAsr(block: suspend (IAsr) -> T): T {
    val connected = CompletableDeferred<IAsr>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            connected.complete(IAsr.Stub.asInterface(binder))
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit

        override fun onBindingDied(name: ComponentName) {
            connected.completeExceptionally(IllegalStateException("speech recognizer stopped"))
        }

        override fun onNullBinding(name: ComponentName) {
            connected.completeExceptionally(IllegalStateException("speech recognizer unavailable"))
        }
    }
    check(bindService(Intent(this, AsrService::class.java), connection, Context.BIND_AUTO_CREATE)) { "can't start the speech recognizer" }
    try {
        return block(connected.await())
    } finally {
        unbindService(connection)
    }
}

/** Hands the model to the sandbox as read-only descriptors; null when it loaded. */
fun IAsr.load(model: AsrModel): String? {
    val open = model.files.map { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
    try {
        return load(open[0], open[1], open[2], open[3], open[4])
    } finally {
        open.forEach { it.close() } // the sandbox got its own copies of the descriptors
    }
}

/** Sends 16 kHz mono samples through a pipe (a binder call can't carry megabytes) and waits for the text. */
fun IAsr.transcribe(samples: FloatArray): String {
    val (read, write) = ParcelFileDescriptor.createPipe()
    val writer = thread(name = "asr-audio") {
        ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
            val chunk = ByteBuffer.allocate(CHUNK * 4).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < samples.size) {
                chunk.clear()
                val end = minOf(samples.size, i + CHUNK)
                for (j in i until end) chunk.putFloat(samples[j])
                out.write(chunk.array(), 0, chunk.position())
                i = end
            }
        }
    }
    try {
        return transcribe(read)
    } finally {
        read.close()
        writer.join()
    }
}

private const val CHUNK = 16_384
