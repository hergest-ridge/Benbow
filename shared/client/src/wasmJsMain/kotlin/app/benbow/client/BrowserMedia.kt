package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.api.UploadStart
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray

/**
 * Media in the browser (ADR-029): each file is a Blob in IndexedDB under `media/<sha256>`, which the browser keeps on
 * disk. A file attached here is stored there before the entry refers to it (data-safety S3) and stays until the server
 * confirmed it (S7) and the backup had time to copy it; downloads are a cache trimmed to the cap, least recently
 * viewed first, like the apps' media folder ([MediaStore]).
 */
class BrowserMedia(
    private val db: BenbowDb,
    private val apiFor: (Connection) -> BenbowApi,
    private val now: () -> Long,
    private val shelf: FileShelf,
    private val chunkSize: Int = 8 * 1024 * 1024,
) : Media {
    private val q get() = db.benbowQueries
    private val files = mutableMapOf<String, LocalFile>() // handles: the data stays in the browser's blob storage
    private val loads = mutableMapOf<String, Mutex>()
    private val touched = mutableSetOf<String>() // a view is noted once per visit, not on every frame that shows it
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var storing = 0

    /** A file is still on its way into browser storage: closing the page now would lose it. */
    val unsaved: Boolean get() = storing > 0

    override suspend fun import(input: ByteSource, mime: String, name: String?, capturedAt: Long?): MediaFile {
        val blob = (input as? BlobSource)?.blob ?: blobOf(input.readAll().toUint8Array(), mime)
        val sha = blobSha256(blob).await<JsString>().toString()
        val size = blobSize(blob).toLong()
        storing++
        try {
            shelf.store(key(sha), blob)
        } finally {
            storing--
        }
        files[sha] = handle(sha, blob, size)
        return MediaFile(sha, size, mime, "idb:$sha", name, capturedAt)
    }

    override fun localFile(sha256: String): LocalFile? = files[sha256]?.also { touch(sha256) }

    override fun imported(file: MediaFile): LocalFile = checkNotNull(files[file.sha256]) { "${file.sha256} wasn't imported here" }

    override suspend fun file(sha256: String): LocalFile {
        localFile(sha256)?.let { return it }
        return loads.getOrPut(sha256) { Mutex() }.withLock {
            files[sha256] ?: fromShelf(sha256) ?: download(sha256)
        }
    }

    private suspend fun fromShelf(sha256: String): LocalFile? {
        val blob = shelf.load(key(sha256)) ?: return null
        if (q.selectMedia(sha256).executeAsOneOrNull() == null) {
            // Stored but no longer listed (evicted while it was being read): list it again as a download.
            q.putMedia(
                sha256, "idb:$sha256", blobSize(blob).toLong(),
                blobType(blob).ifEmpty {
                    "application/octet-stream"
                },
                "downloaded", 1, null, null, now(),
            )
        }
        return remember(sha256, blob)
    }

    private suspend fun remember(sha256: String, blob: JsAny): LocalFile {
        val size = blobSize(blob).toLong()
        val bytes = if (size <= SMALL_FILE) blobBytes(blob).await<JsAny>().toByteArray() else null
        touch(sha256)
        return handle(sha256, blob, size, bytes).also { files[sha256] = it }
    }

    private fun handle(sha256: String, blob: JsAny, size: Long, bytes: ByteArray? = null) = LocalFile(sha256, blob, size, bytes)

    private fun touch(sha256: String) {
        if (touched.add(sha256)) q.touchMedia(now(), sha256)
    }

    override fun usage(): Flow<MediaUsage> = q.mediaUsage(SMALL_FILE, now() - SETTLE_MS) { total, freeable ->
        MediaUsage(total, freeable)
    }.asFlow().mapToOne(Dispatchers.Default)

    override fun awaitingUpload(sha256: String): Flow<Boolean> = q.selectMedia(sha256).asFlow().mapToOneOrNull(Dispatchers.Default).map {
        it != null && it.origin == "captured" && it.uploaded == 0L
    }

    /** Same rule as the apps: only files the server holds (and, for ones captured here, has backed up) go. */
    override fun evict(capBytes: Long): Long {
        val candidates = q.evictable(SMALL_FILE, now() - SETTLE_MS).executeAsList()
        var total = candidates.sumOf { it.size }
        var freed = 0L
        for (file in candidates) {
            if (total <= capBytes) break
            files.remove(file.sha256)
            q.forgetMedia(file.sha256)
            scope.launch { runCatching { shelf.delete(key(file.sha256)) } }
            total -= file.size
            freed += file.size
        }
        return freed
    }

    override fun pendingCount(): Flow<Long> = q.pendingUploadCount().asFlow().mapToOne(Dispatchers.Default)

    /** Uploads in slices read from the stored blob; marked uploaded only after the server verified the file (S7). */
    override suspend fun uploadPending(): Int {
        val connection = Settings(db).connection ?: return 0
        val api = apiFor(connection)
        return uploadEach(q.pendingUploads().executeAsList()) { media ->
            // never expected; the row stays
            val blob = files[media.sha256]?.blob ?: shelf.load(key(media.sha256)) ?: return@uploadEach false
            upload(api, UploadStart(media.sha256, media.size, media.mime, media.name, media.captured_at), blob)
            q.markUploaded(now(), media.sha256)
            true
        }
    }

    private suspend fun upload(api: BenbowApi, announce: UploadStart, blob: JsAny) {
        var status = api.startUpload(announce)
        var restarts = 0
        while (!status.complete) {
            val id = checkNotNull(status.id) { "Upload without id" }
            val end = minOf(announce.size, status.offset + chunkSize)
            val chunk = blobRange(blob, status.offset.toDouble(), end.toDouble()).await<JsAny>().toByteArray()
            status = try {
                api.uploadChunk(id, status.offset, chunk)
            } catch (e: ChecksumMismatchException) {
                if (++restarts > 1) throw e
                api.startUpload(announce)
            }
        }
    }

    /** Streams the file into a blob in slices, checks its hash, then stores it for the next visits. */
    private suspend fun download(sha256: String): LocalFile {
        val connection = Settings(db).connection ?: throw PairingException("Not paired with a server")
        val parts = newParts()
        val mime = apiFor(connection).downloadBlob(sha256) { channel ->
            while (!channel.isClosedForRead) {
                val chunk = channel.readRemaining(PART_BYTES).readByteArray()
                if (chunk.isNotEmpty()) addPart(parts, chunk.toUint8Array())
            }
        }
        val blob = blobOfParts(parts, mime)
        check(blobSha256(blob).await<JsString>().toString() == sha256) { "Downloaded file doesn't match its SHA-256" }
        shelf.store(key(sha256), blob)
        q.putMedia(sha256, "idb:$sha256", blobSize(blob).toLong(), mime, "downloaded", 1, null, null, now())
        return remember(sha256, blob)
    }

    private fun key(sha256: String) = "media/$sha256"

    companion object {
        /** Previews, drawings, voice notes: read into memory at once; larger files stay blobs. */
        const val SMALL_FILE = 1024L * 1024
        const val SETTLE_MS = 7L * 24 * 3_600_000
        private const val PART_BYTES = 1024L * 1024
    }
}
