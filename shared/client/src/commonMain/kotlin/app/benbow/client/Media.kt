package app.benbow.client

import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Media files on this device, by SHA-256 (docs/sync.md#blobs): a folder on Android and desktop ([MediaStore] there),
 * memory in the browser (ADR-029). A file captured here stays until the server confirmed it; downloaded ones are a cache.
 */
interface Media {
    /** Copies [input] in (the source may go away later). Doesn't touch the DB: attach it next. */
    suspend fun import(input: ByteSource, mime: String, name: String?, capturedAt: Long?): MediaFile

    /** The file if it is on this device, without network. */
    fun localFile(sha256: String): LocalFile?

    /** A file [import] just stored, before it is attached (a sound's tags are read from it then, ADR-035). */
    fun imported(file: MediaFile): LocalFile

    /** The file, downloaded and verified first when it isn't here yet. */
    suspend fun file(sha256: String): LocalFile

    /** True while a file captured here hasn't been confirmed by the server ("not safe yet"). */
    fun awaitingUpload(sha256: String): Flow<Boolean>

    /** Keeps large local files under [capBytes]; returns the bytes freed (an evicted file is downloaded again when needed). */
    fun evict(capBytes: Long): Long

    /** What this device keeps, and what [evict] may let go right now by the same rule («Освободить на телефоне»). */
    fun usage(): Flow<MediaUsage>

    /** Files captured here that the server hasn't confirmed yet. */
    fun pendingCount(): Flow<Long>

    /** Uploads every captured file the server hasn't confirmed; returns how many finished. */
    suspend fun uploadPending(): Int
}

/** Bytes of files on this device: all of them, and those the server holds and has backed up (they may go). */
data class MediaUsage(val total: Long, val freeable: Long)

/** Failures in a row after which the rest of the queue waits for the next sync (the server is likely gone). */
internal const val UPLOAD_FAILURES_IN_A_ROW = 3

/**
 * Runs [upload] for each file (true = sent and confirmed) so that one that fails doesn't keep the rest waiting:
 * on 2026-10-19 one stuck file held 65 others back for hours. The first failure is rethrown at the end, so the
 * sync reports it and tries again later; the files that went through are done.
 */
internal suspend fun <T> uploadEach(files: List<T>, upload: suspend (T) -> Boolean): Int {
    var finished = 0
    var first: Exception? = null
    var inARow = 0
    for (file in files) {
        try {
            if (upload(file)) finished++
            inARow = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (first == null) first = e
            if (++inARow >= UPLOAD_FAILURES_IN_A_ROW) break
        }
    }
    first?.let { throw it }
    return finished
}
