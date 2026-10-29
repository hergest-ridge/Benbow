package app.benbow.client

import app.benbow.core.audio.RandomRead
import kotlinx.coroutines.CoroutineDispatcher

// What differs between Android and desktop (the JVM) and the browser (ADR-029). On the JVM these are the
// java.io types themselves, so that code reads as before; the browser has its own small versions.

/** A media file on this device: a java.io.File on Android and desktop, bytes kept in memory in the browser. */
expect open class LocalFile

/** Its whole content as text (a drawing's SVG). */
expect fun LocalFile.readAllText(): String

/** Its whole content (a font from the server, carried into a printed page). */
expect fun LocalFile.readAllBytes(): ByteArray

/** The file in pieces: a sound's tags are read without reading its audio (ADR-035). */
expect fun LocalFile.randomRead(): RandomRead

/** An address a page can show it by (a printable page): a file: URI on the JVM, a data: URL in the browser. */
expect fun LocalFile.uri(): String

/** Bytes to read once (a picked or captured file): an InputStream on the JVM, a picked file's bytes in the browser. */
expect abstract class ByteSource : AutoCloseable {
    open override fun close()
}

expect fun byteSourceOf(bytes: ByteArray): ByteSource

/** Reads at most [limit] bytes (one more than a size cap tells that the source is over it). */
expect fun ByteSource.readUpTo(limit: Int): ByteArray

/** Where a saved file goes: an OutputStream on the JVM, a download in the browser (when it is closed). */
expect abstract class ByteSink : AutoCloseable {
    open override fun close()
}

expect fun ByteSink.writeAll(bytes: ByteArray)

/** For blocking work: Dispatchers.IO on the JVM; the browser has one thread. */
expect val IoDispatcher: CoroutineDispatcher

/** The files of a ZIP archive (name, bytes), refusing more than [maxTotal] bytes unpacked. */
expect suspend fun unzipFiles(bytes: ByteArray, maxTotal: Long): List<Pair<String, ByteArray>>
