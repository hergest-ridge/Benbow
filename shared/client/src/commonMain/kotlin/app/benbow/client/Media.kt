package app.benbow.client

import kotlinx.coroutines.flow.Flow

/**
 * Media files on this device, by SHA-256 (docs/sync.md#blobs): a folder on Android and desktop ([MediaStore] there),
 * memory in the browser (ADR-029). A file captured here stays until the server confirmed it; downloaded ones are a cache.
 */
interface Media {
    /** Copies [input] in (the source may go away later). Doesn't touch the DB: attach it next. */
    suspend fun import(input: ByteSource, mime: String, name: String?, capturedAt: Long?): MediaFile

    /** The file if it is on this device, without network. */
    fun localFile(sha256: String): LocalFile?

    /** The file, downloaded and verified first when it isn't here yet. */
    suspend fun file(sha256: String): LocalFile

    /** True while a file captured here hasn't been confirmed by the server ("not safe yet"). */
    fun awaitingUpload(sha256: String): Flow<Boolean>

    /** Keeps large local files under [capBytes]; returns the bytes freed (an evicted file is downloaded again when needed). */
    fun evict(capBytes: Long): Long

    /** Files captured here that the server hasn't confirmed yet. */
    fun pendingCount(): Flow<Long>

    /** Uploads every captured file the server hasn't confirmed; returns how many finished. */
    suspend fun uploadPending(): Int
}
