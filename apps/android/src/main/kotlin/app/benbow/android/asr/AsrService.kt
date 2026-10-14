package app.benbow.android.asr

import android.app.Service
import android.content.Intent
import android.os.ParcelFileDescriptor
import app.benbow.core.speech.parseTokens
import java.io.FileInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Speech recognition (GigaAM on ONNX Runtime) in an isolated process: Android runs it as a throwaway user with no
 * permissions, so the system itself keeps it off the network and out of the app's files, whatever the native code
 * (or ONNX Runtime's own telemetry) would try. The model arrives as read-only descriptors and is mapped into memory;
 * audio arrives through a pipe; only text goes back.
 */
class AsrService : Service() {
    private var model: GigaAm? = null

    private val binder = object : IAsr.Stub() {
        override fun load(
            encoder: ParcelFileDescriptor,
            decoder: ParcelFileDescriptor,
            joiner: ParcelFileDescriptor,
            tokens: ParcelFileDescriptor,
        ): String? = synchronized(this@AsrService) {
            runCatching {
                model?.close()
                model = null
                val vocabulary = ParcelFileDescriptor.AutoCloseInputStream(tokens).use { it.readBytes().decodeToString() }
                model = GigaAm(mapped(encoder), mapped(decoder), mapped(joiner), parseTokens(vocabulary))
            }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
        }

        override fun transcribe(pcm: ParcelFileDescriptor): String = synchronized(this@AsrService) {
            checkNotNull(model) { "model not loaded" }.transcribe(readSamples(pcm))
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
        model?.close()
        super.onDestroy()
    }

    /** The passed file, mapped read-only (file-backed pages, so 230 MB doesn't push other apps out of memory). */
    private fun mapped(file: ParcelFileDescriptor): ByteBuffer = file.use {
        FileInputStream(it.fileDescriptor).channel.use { channel -> channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()) }
    }

    private fun readSamples(pcm: ParcelFileDescriptor): FloatArray {
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(pcm).use { it.readBytes() }
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }
}
