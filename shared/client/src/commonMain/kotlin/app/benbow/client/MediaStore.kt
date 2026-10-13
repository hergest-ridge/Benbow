package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.api.UploadStart
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Media files on this device, stored by SHA-256 under [root] (docs/sync.md#blobs). Importing copies
 * the source into app storage first, so deleting it from the gallery later can't lose it. A captured
 * file stays until the server confirmed it; downloaded files are a cache.
 */
class MediaStore(
    private val root: File,
    private val db: BenbowDb,
    private val apiFor: (Connection) -> BenbowApi,
    private val now: () -> Long,
    private val chunkSize: Int = 8 * 1024 * 1024,
) {
    private val q get() = db.benbowQueries
    private val incoming = File(root, ".incoming")
    private val downloads = ConcurrentHashMap<String, Mutex>()

    /** Copies [input] into the media folder (fsynced, then renamed into place). Doesn't touch the DB: attach it next. */
    suspend fun import(input: InputStream, mime: String, name: String?, capturedAt: Long?): MediaFile = withContext(Dispatchers.IO) {
        incoming.mkdirs()
        val tmp = File(incoming, UUID.randomUUID().toString())
        val digest = MessageDigest.getInstance("SHA-256")
        val size = FileOutputStream(tmp).use { out ->
            val counted = DigestOutputStream(out, digest)
            val n = input.copyTo(counted)
            counted.flush()
            out.fd.sync()
            n
        }
        val sha = digest.digest().toHex()
        val target = place(tmp, sha)
        MediaFile(sha, size, mime, target.absolutePath, name, capturedAt)
    }

    /** The file if it is on this device, without network. */
    fun localFile(sha256: String): File? {
        val row = q.selectMedia(sha256).executeAsOneOrNull() ?: return null
        val file = File(row.path).takeIf { it.exists() } ?: return null
        q.touchMedia(now(), sha256)
        return file
    }

    /** The file, downloaded and verified first when it isn't here yet. */
    suspend fun file(sha256: String): File {
        localFile(sha256)?.let { return it }
        return downloads.computeIfAbsent(sha256) { Mutex() }.withLock {
            localFile(sha256) ?: download(sha256)
        }
    }

    /** Files captured here that the server hasn't confirmed yet. */
    fun pendingCount(): Flow<Long> = q.pendingUploadCount().asFlow().mapToOne(Dispatchers.Default)

    /**
     * Uploads every captured file the server hasn't confirmed, resuming where the server stopped.
     * Returns how many finished. A file is marked uploaded only after the server verified it (S7).
     */
    suspend fun uploadPending(): Int = withContext(Dispatchers.IO) {
        val connection = Settings(db).connection ?: return@withContext 0
        val api = apiFor(connection)
        var finished = 0
        for (media in q.pendingUploads().executeAsList()) {
            val file = File(media.path)
            if (!file.exists()) continue // never expected; the row stays so it isn't forgotten
            upload(api, UploadStart(media.sha256, media.size, media.mime, media.name, media.captured_at), file)
            q.markUploaded(media.sha256)
            finished++
        }
        finished
    }

    private suspend fun upload(api: BenbowApi, announce: UploadStart, file: File) {
        var status = api.startUpload(announce)
        var restarts = 0
        while (!status.complete) {
            val id = checkNotNull(status.id) { "Upload without id" }
            val chunk = readChunk(file, status.offset)
            status = try {
                api.uploadChunk(id, status.offset, chunk)
            } catch (e: ChecksumMismatchException) {
                if (++restarts > 1) throw e // twice in a row: the local file itself is suspect; keep it, report
                api.startUpload(announce)
            }
        }
    }

    private fun readChunk(file: File, offset: Long): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val bytes = ByteArray(minOf(chunkSize.toLong(), raf.length() - offset).toInt())
        raf.seek(offset)
        raf.readFully(bytes)
        bytes
    }

    private suspend fun download(sha256: String): File = withContext(Dispatchers.IO) {
        val connection = Settings(db).connection ?: throw PairingException("Not paired with a server")
        incoming.mkdirs()
        val tmp = File(incoming, UUID.randomUUID().toString())
        val mime = try {
            apiFor(connection).downloadBlob(sha256, tmp)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        val actual = sha256Of(tmp)
        if (actual != sha256) {
            tmp.delete()
            error("Downloaded file doesn't match its SHA-256")
        }
        val target = place(tmp, sha256)
        q.putMedia(sha256, target.absolutePath, target.length(), mime, "downloaded", 1, null, null, now())
        target
    }

    private fun place(tmp: File, sha256: String): File {
        val target = File(root, sha256)
        if (target.exists()) {
            tmp.delete() // same content already here
        } else {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        return target
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
