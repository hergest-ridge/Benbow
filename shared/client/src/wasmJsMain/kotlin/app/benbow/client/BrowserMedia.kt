package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.Sha256
import app.benbow.core.api.UploadStart
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray

/**
 * Media in the browser (ADR-029): files live in memory, fetched from the server when shown and kept while the page is
 * open, least recently viewed going first past [capBytes]. A file attached here is uploaded with the next sync; the
 * picked original stays on the PC's disk until then.
 */
class BrowserMedia(
    private val db: BenbowDb,
    private val apiFor: (Connection) -> BenbowApi,
    private val now: () -> Long,
    private val chunkSize: Int = 8 * 1024 * 1024,
) : Media {
    private val q get() = db.benbowQueries
    private val files = LinkedHashMap<String, LocalFile>() // least recently used first: a use moves it to the end
    private val downloads = HashMap<String, Mutex>()

    override suspend fun import(input: ByteSource, mime: String, name: String?, capturedAt: Long?): MediaFile {
        val bytes = input.readAll()
        val sha = Sha256.hex(bytes)
        files[sha] = LocalFile(name ?: sha, bytes)
        return MediaFile(sha, bytes.size.toLong(), mime, "memory:$sha", name, capturedAt)
    }

    override fun localFile(sha256: String): LocalFile? = files.remove(sha256)?.also { files[sha256] = it }

    override suspend fun file(sha256: String): LocalFile {
        localFile(sha256)?.let { return it }
        return downloads.getOrPut(sha256) { Mutex() }.withLock { localFile(sha256) ?: download(sha256) }
    }

    override fun awaitingUpload(sha256: String): Flow<Boolean> = q.selectMedia(sha256).asFlow().mapToOneOrNull(Dispatchers.Default).map {
        it != null && it.origin == "captured" && it.uploaded == 0L
    }

    override fun evict(capBytes: Long): Long {
        var total = files.values.sumOf { it.bytes.size.toLong() }
        var freed = 0L
        val pending = q.pendingUploads().executeAsList().map { it.sha256 }.toSet()
        for (sha in files.keys.toList()) {
            if (total <= capBytes) break
            if (sha in pending) continue
            val size = files.remove(sha)?.bytes?.size?.toLong() ?: 0
            total -= size
            freed += size
        }
        return freed
    }

    override fun pendingCount(): Flow<Long> = q.pendingUploadCount().asFlow().mapToOne(Dispatchers.Default)

    override suspend fun uploadPending(): Int {
        val connection = Settings(db).connection ?: return 0
        val api = apiFor(connection)
        var finished = 0
        for (media in q.pendingUploads().executeAsList()) {
            val bytes = files[media.sha256]?.bytes ?: continue // gone with a reload: the row stays, so it shows as waiting
            var status = api.startUpload(UploadStart(media.sha256, media.size, media.mime, media.name, media.captured_at))
            while (!status.complete) {
                val id = checkNotNull(status.id) { "Upload without id" }
                val end = minOf(bytes.size.toLong(), status.offset + chunkSize).toInt()
                status = api.uploadChunk(id, status.offset, bytes.copyOfRange(status.offset.toInt(), end))
            }
            q.markUploaded(now(), media.sha256)
            finished++
        }
        return finished
    }

    private suspend fun download(sha256: String): LocalFile {
        val connection = Settings(db).connection ?: throw PairingException("Not paired with a server")
        var bytes = ByteArray(0)
        val mime = apiFor(connection).downloadBlob(sha256) { channel -> bytes = channel.readRemaining().readByteArray() }
        check(Sha256.hex(bytes) == sha256) { "Downloaded file doesn't match its SHA-256" }
        val file = LocalFile(sha256, bytes)
        files[sha256] = file
        q.putMedia(sha256, "memory:$sha256", bytes.size.toLong(), mime, "downloaded", 1, null, null, now())
        return file
    }
}
