package app.benbow.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

// Shared by the Android and desktop targets (src/jvmShared is a source dir of both).

actual typealias LocalFile = java.io.File

actual fun LocalFile.readAllText(): String = readText()

actual fun LocalFile.uri(): String = toPath().toUri().toString()

actual typealias ByteSource = java.io.InputStream

actual fun byteSourceOf(bytes: ByteArray): ByteSource = ByteArrayInputStream(bytes)

actual fun ByteSource.readUpTo(limit: Int): ByteArray = readNBytes(limit)

actual typealias ByteSink = java.io.OutputStream

actual fun ByteSink.writeAll(bytes: ByteArray) = write(bytes)

actual val IoDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun unzipFiles(bytes: ByteArray, maxTotal: Long): List<Pair<String, ByteArray>> {
    val files = mutableListOf<Pair<String, ByteArray>>()
    var total = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val data = zip.readBytes()
            total += data.size
            require(total <= maxTotal) { "The archive unpacks to more than ${maxTotal / 1024 / 1024} MB" }
            files += entry.name to data
        }
    }
    return files
}
