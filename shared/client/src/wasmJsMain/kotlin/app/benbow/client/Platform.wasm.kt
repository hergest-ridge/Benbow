package app.benbow.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// The browser's side of Platform.kt (ADR-029): files are bytes in memory, the page has one thread.

actual open class LocalFile(val name: String, val bytes: ByteArray)

actual fun LocalFile.readAllText(): String = bytes.decodeToString()

@OptIn(ExperimentalEncodingApi::class)
actual fun LocalFile.uri(): String = "data:application/octet-stream;base64," + Base64.encode(bytes)

actual abstract class ByteSource : AutoCloseable {
    abstract fun readAll(): ByteArray

    actual open override fun close() = Unit
}

private class BytesSource(private val bytes: ByteArray) : ByteSource() {
    override fun readAll() = bytes
}

actual fun byteSourceOf(bytes: ByteArray): ByteSource = BytesSource(bytes)

actual fun ByteSource.readUpTo(limit: Int): ByteArray = readAll().let { if (it.size > limit) it.copyOf(limit) else it }

actual abstract class ByteSink : AutoCloseable {
    abstract fun write(bytes: ByteArray)

    actual open override fun close() = Unit
}

actual fun ByteSink.writeAll(bytes: ByteArray) = write(bytes)

actual val IoDispatcher: CoroutineDispatcher = Dispatchers.Default

actual fun unzipFiles(bytes: ByteArray, maxTotal: Long): List<Pair<String, ByteArray>> =
    throw UnsupportedOperationException("ZIP archives open in the desktop and Android apps for now; pick the .ics files instead")
