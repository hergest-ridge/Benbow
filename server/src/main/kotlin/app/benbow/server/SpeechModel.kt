package app.benbow.server

import app.benbow.core.api.SpeechModelFile
import app.benbow.core.api.SpeechModelSpec
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream

/**
 * The speech-recognition model the server hands to paired phones (7.6, ADR-025), kept in `<root>/<id>/`. It is
 * fetched only when the owner presses the button on the admin page, from the pinned official URLs, and every file is
 * checked against [SpeechModelSpec] before it's used: this is the server's only request to the internet.
 */
class SpeechModel(
    private val root: Path,
    private val fetch: Fetch = HttpFetch(),
    private val spec: Spec = Spec(SpeechModelSpec.archive, SpeechModelSpec.files),
) {
    /** What to fetch and what must come out; tests use small made-up files. */
    class Spec(val archive: SpeechModelSpec.Source, val files: List<SpeechModelFile>)

    /** Downloads [url] into [target], reporting bytes as they come. */
    fun interface Fetch {
        fun download(url: String, size: Long, target: Path, progress: (Long) -> Unit)
    }

    sealed interface State {
        data object Missing : State

        data class Downloading(val done: Long, val total: Long) : State

        data object Ready : State

        data class Failed(val message: String) : State
    }

    private val log = LoggerFactory.getLogger("benbow.speech")
    private val dir = root.resolve(SpeechModelSpec.ID)

    @Volatile
    var state: State = if (complete()) State.Ready else State.Missing
        private set

    val files get() = spec.files

    /** A ready file by name, or null (unknown name, or the model isn't here). */
    fun file(name: String): Path? =
        spec.files.firstOrNull { it.name == name }?.let { dir.resolve(it.name) }?.takeIf { state == State.Ready }

    /** Starts the one-time download in the background; does nothing while one runs or when the model is here. */
    @Synchronized
    fun download() {
        if (state is State.Downloading || state == State.Ready) return
        state = State.Downloading(0, spec.archive.size)
        Thread({ runDownload() }, "speech-model").apply { isDaemon = true }.start()
    }

    /** Fetches, checks and unpacks; the state says how it went. Public for tests (synchronous). */
    fun runDownload() {
        val work = root.resolve(".download").createDirectories()
        val total = spec.archive.size
        try {
            val done = AtomicLong()
            val archive = work.resolve("model.tar.bz2")
            fetchChecked(spec.archive, archive) { state = State.Downloading(done.addAndGet(it), total) }
            val unpacked = unpack(archive, work)
            archive.deleteIfExists()
            dir.createDirectories()
            for (file in spec.files) {
                val source = unpacked.getValue(file.name)
                check(sha256(source) == file.sha256) { "${file.name} doesn't match its pinned hash" }
                Files.move(source, dir.resolve(file.name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            state = State.Ready
            log.info("Speech model {} ready in {}", SpeechModelSpec.ID, dir)
        } catch (e: Exception) {
            log.warn("Speech model download failed", e)
            state = State.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            Files.list(work).use { s -> s.forEach { it.deleteIfExists() } }
        }
    }

    private fun fetchChecked(source: SpeechModelSpec.Source, target: Path, progress: (Long) -> Unit) {
        fetch.download(source.url, source.size, target, progress)
        check(target.fileSize() == source.size) { "${target.name}: ${target.fileSize()} bytes, expected ${source.size}" }
        check(sha256(target) == source.sha256) { "${target.name} doesn't match its pinned hash" }
    }

    /** Takes only the files the spec names (by base name) out of the archive, nothing else. */
    private fun unpack(archive: Path, work: Path): Map<String, Path> {
        val wanted = spec.files.map { it.name }.toSet()
        val out = HashMap<String, Path>()
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (!entry.isFile || name !in wanted || name in out) continue
                val target = work.resolve(name)
                target.outputStream().use { tar.copyTo(it) }
                out[name] = target
            }
        }
        val missing = wanted - out.keys
        check(missing.isEmpty()) { "the archive lacks $missing" }
        return out
    }

    private fun complete() = spec.files.all { dir.resolve(it.name).let { p -> p.exists() && p.fileSize() == it.size } }
}

internal fun sha256(path: Path): String = path.inputStream().use(::sha256)

internal fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Plain HTTPS from the JDK, in parallel byte ranges: GitHub's file host slows a single long stream to a crawl.
 * Each range is retried on its own.
 */
class HttpFetch(private val parts: Int = 8) : SpeechModel.Fetch {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun download(url: String, size: Long, target: Path, progress: (Long) -> Unit) {
        RandomAccessFile(target.toFile(), "rw").use { it.setLength(size) }
        val step = (size + parts - 1) / parts
        val pool = Executors.newFixedThreadPool(parts)
        try {
            val jobs = (0 until parts).map { p ->
                val from = p * step
                val to = minOf(size, from + step) - 1
                pool.submit { if (from <= to) range(url, target, from, to, progress) }
            }
            jobs.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun range(url: String, target: Path, from: Long, to: Long, progress: (Long) -> Unit) {
        var pos = from
        var attempts = 0
        while (pos <= to) {
            try {
                val request = HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=$pos-$to").build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                check(response.statusCode() == 206) { "HTTP ${response.statusCode()}" }
                RandomAccessFile(target.toFile(), "rw").use { file ->
                    file.seek(pos)
                    response.body().use { body ->
                        val buffer = ByteArray(1 shl 16)
                        while (pos <= to) {
                            val n = body.read(buffer, 0, minOf(buffer.size.toLong(), to - pos + 1).toInt())
                            if (n < 0) break
                            file.write(buffer, 0, n)
                            pos += n
                            progress(n.toLong())
                        }
                    }
                }
            } catch (e: Exception) {
                if (++attempts >= MAX_ATTEMPTS) throw e
                Thread.sleep(1_000L * attempts)
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 20
    }
}
