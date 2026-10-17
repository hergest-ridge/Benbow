package app.benbow.client

import app.benbow.core.audio.RandomRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await

// The browser's side of Platform.kt (ADR-029): a file is a Blob (kept on disk by the browser, in IndexedDB), the page
// has one thread.

/**
 * A media file in the browser: its [blob] (what plays and uploads, never copied whole into Kotlin) and, for small
 * files, its [bytes] too (drawings read as text, pictures decoded at once).
 */
actual open class LocalFile(val name: String, val blob: JsAny, val size: Long, val bytes: ByteArray? = null) {
    internal var url: String? = null
}

actual fun LocalFile.readAllText(): String = checkNotNull(bytes) { "$name is too large to read as text here" }.decodeToString()

/** Slices of the blob, each read on its own (a song's tags without its audio). */
actual fun LocalFile.randomRead(): RandomRead = object : RandomRead {
    override val size: Long = this@randomRead.size

    override suspend fun read(offset: Long, length: Int): ByteArray {
        val end = minOf(size, offset + length)
        if (offset >= end) return ByteArray(0)
        bytes?.let { return it.copyOfRange(offset.toInt(), end.toInt()) }
        return blobRange(blob, offset.toDouble(), end.toDouble()).await<JsAny>().toByteArray()
    }
}

/** A blob: URL, valid in this page and in windows it opens (printing). */
actual fun LocalFile.uri(): String = url ?: objectUrl(blob).also { url = it }

actual abstract class ByteSource : AutoCloseable {
    /** Everything at once; a [BlobSource] has no synchronous read and is taken whole by the media store instead. */
    abstract fun readAll(): ByteArray

    actual open override fun close() = Unit
}

/** A file the user picked, dropped or pasted, as the browser holds it: [BrowserMedia] stores the blob as it is. */
class BlobSource(val blob: JsAny) : ByteSource() {
    override fun readAll(): ByteArray = throw UnsupportedOperationException("A picked file is read asynchronously")
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

actual suspend fun unzipFiles(bytes: ByteArray, maxTotal: Long): List<Pair<String, ByteArray>> = readZip(bytes, maxTotal)
